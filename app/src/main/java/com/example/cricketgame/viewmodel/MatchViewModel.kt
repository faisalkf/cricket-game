package com.example.cricketgame.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cricketgame.data.*
import com.example.cricketgame.engine.BattingResolver
import com.example.cricketgame.engine.BowlingInput
import com.example.cricketgame.engine.BowlingResolver
import com.example.cricketgame.model.BallResult
import com.example.cricketgame.model.Innings
import com.example.cricketgame.model.Match
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.random.Random

/** Which sub-state the match screen is in for the current delivery. */
enum class DeliveryPhase { RUN_UP, BALL_RESULT, INNINGS_BREAK, MATCH_OVER }

/** Ease-in exponent for both sweeps: t^POWER starts slow and accelerates toward release/GREEN. */
private const val RUN_UP_EASE_POWER = 2.2f

/** Duration of the short catch-up animation that finishes the ball's travel once a shot/delivery
 *  is released mid-sweep, so it visually arrives at the batsman/stumps instead of jumping there. */
private const val BALL_FLIGHT_FINISH_MS = 220

/** Slower-changing state - the scoreboard, whose turn it is, the last outcome. */
data class MatchUiState(
    val format: MatchFormat,
    val battingTeamName: String = "",
    val bowlingTeamName: String = "",
    val isPlayerBatting: Boolean = true,
    val strikerName: String = "",
    val bowlerName: String = "",
    val score: Int = 0,
    val wickets: Int = 0,
    val oversText: String = "0.0",
    val target: Int? = null,
    val isSecondInnings: Boolean = false,
    val recentBalls: List<String> = emptyList(),
    val phase: DeliveryPhase = DeliveryPhase.RUN_UP,
    val lastBallSummary: String? = null,
    // Details of the just-played shot, for the batter's brief shot-pose/trajectory visual -
    // null whenever there's no delivery to show yet (see MatchScreen/BatterShot).
    val lastBallAggression: Aggression? = null,
    val lastBallTimingQuality: TimingQuality? = null,
    // The unified slider's release position for the just-played delivery: shot direction while
    // batting, line/curve-bias while bowling - see MatchScreen/BatterShot.
    val lastBallDirection: Float = 0f,
    val lastBallRuns: Int = 0,
    // Whether the just-played delivery was on the stumps - drives the missed-ball visual (stumps
    // broken vs. stopped at the batsman, see BatterShot/MatchVisuals) exactly like it already
    // drives BattingResolver's own bowled/lbw-vs-dot split.
    val lastBallOnStumps: Boolean = false,
    val matchResult: String? = null,
    // Increments once per resolved ball (see applyBallResult) - a stable key for one-shot
    // per-ball effects (shot-impact animation, screen shake, sound) that lastBallSummary alone
    // can't provide, since two consecutive balls can produce identical summary text.
    val ballSeq: Int = 0
)

/**
 * Fast-changing (~60fps) run-up state, kept separate from [MatchUiState] so the timing
 * gauge/ball animation can recompose on their own without redrawing the whole scoreboard.
 */
data class RunUpState(
    val progress: Float = 0f,       // 0f..1f, one eased one-way sweep per delivery
    val quality: TimingQuality = TimingQuality.RED,
    // Also drives the pitch visuals (bowler run-up figure + travelling ball) directly - the
    // ball's Y position on the pitch is a function of this same value (see ballTravelY), so its
    // visual travel is always in sync with the timing gauge/quality classification above it: a
    // RED-zone release is early in the ball's flight, GREEN is well along, and a completed sweep
    // has the ball arriving at the stumps.
    //
    // pitchLength/postPitchTilt below are this delivery's actual (or, before it's known, best
    // current guess at) pitch length and post-pitch tilt - see ballTravelX - carried alongside
    // progress/quality (rather than reset every tick) so the bend they drive stays stable across
    // a delivery once set. Known upfront for CPU bowling (rolled before the sweep even starts);
    // for the player's own bowling they default to a straight ball until release actually locks
    // them in (see bowlDelivery), since the real values aren't known before then.
    val pitchLength: PitchLength = PitchLength.GOOD_LENGTH,
    val postPitchTilt: Float = 0f
)

/**
 * Drives a real ball-by-ball match: a bowler run-up timer that sweeps the timing indicator once
 * per ball, accelerating toward release (progress eased as t^[RUN_UP_EASE_POWER]) rather than at
 * constant speed - RED -> YELLOW -> GREEN -> YELLOW -> RED while batting (how the incoming ball
 * should be read as it arrives), and RED -> YELLOW -> GREEN -> RED while bowling (see
 * [com.example.cricketgame.data.BowlingTimingZones]), where a late release (past GREEN, no
 * yellow buffer) is a no-ball. Both batting and bowling share one unified control: a single
 * horizontal slider, dragged throughout the run-up and released at the player's chosen moment.
 * The slider's left-right position AT RELEASE sets direction (shot direction while batting, line
 * while bowling); the release TIMING against the sweep above sets outcome quality - batting's
 * GREEN zone further splits into a LIGHT ground-shot tier and a thin DARK six tier by how close to
 * dead-center the release landed (see [com.example.cricketgame.data.BattingTimingZones]),
 * bowling's GREEN likewise splits into a standard best-ball tier and a tougher "perfect ball" tier
 * (see [com.example.cricketgame.data.GreenTier]).
 *
 * v1 simplification (matches the rest of the engine layer): only a single "current batsman" is
 * tracked per side rather than a striker/non-striker pair - no strike rotation on odd runs.
 */
class MatchViewModel(
    private val format: MatchFormat,
    private val playerTeam: Team,
    cpuTeam: Team,
    tossWinnerIsPlayer: Boolean,
    tossChoice: TossChoice?
) : ViewModel() {

    private val match = Match(format, playerTeam, cpuTeam).apply {
        this.tossWinnerIsPlayer = tossWinnerIsPlayer
        this.tossChoice = tossChoice ?: TossChoice.entries.random()
        startInningsOne()
    }

    private var innings: Innings = match.inningsOne

    private var strikerIndex = 0
    private lateinit var currentStriker: Player
    private lateinit var currentBowler: Player

    private var bowlerCyclePos = -1
    private var lastOverBowlerId: String? = null

    // True right after a no-ball, so startDelivery() re-bowls with the same bowler instead of
    // treating the retry as the start of a new over.
    private var awaitingRebowl = false

    // Short display codes for the over-in-progress, tracked directly (rather than sliced off
    // innings.balls) since a no-ball doesn't advance ballsInCurrentOver but still needs to show.
    private val currentOverCodes = mutableListOf<String>()

    // Auto-rolled delivery parameters for whichever side is CPU-controlled this ball.
    private var cpuDeliveryOnStumps = false
    private var cpuDeliveryLine = PitchLine.ON_STUMPS
    private var cpuDeliveryLength = PitchLength.GOOD_LENGTH
    private var cpuDeliveryTilt = 0f

    private var runUpJob: Job? = null

    private val _uiState = MutableStateFlow(buildInitialUiState())
    val uiState: StateFlow<MatchUiState> = _uiState.asStateFlow()

    private val _runUp = MutableStateFlow(RunUpState())
    val runUp: StateFlow<RunUpState> = _runUp.asStateFlow()

    init {
        currentStriker = innings.battingTeam.players[0]
        startDelivery()
    }

    /**
     * Called by BattingControls when the player releases the unified slider. [direction] is the
     * slider's left-right position at that instant (-1f leg side .. +1f off side) - the shot's
     * direction. Aggression is no longer chosen separately; it's fully implied by how the release
     * landed against the sweep (see [battingAggressionFor]).
     */
    fun playBattingShot(direction: Float) {
        if (_uiState.value.phase != DeliveryPhase.RUN_UP) return
        val progress = _runUp.value.progress
        // On/off-side mismatch penalty (see downgradeForSideMismatch): the delivery's actual side
        // is whatever cpuDeliveryTilt landed on for this ball (rolled in rollCpuDelivery, and also
        // what's driving the pre-contact ball curve via _runUp.postPitchTilt) - aimed the wrong
        // side of it and the achieved timing is downgraded a tier before it does anything else.
        val timing = downgradeForSideMismatch(_runUp.value.quality, direction, cpuDeliveryTilt)
        val aggression = battingAggressionFor(timing, progress)
        finishBallFlight()
        val striker = currentStriker
        val fieldMode = currentFieldMode()
        val (outcome, runs) = BattingResolver.resolve(
            battingSkill = striker.battingSkill,
            timing = timing,
            aggression = aggression,
            onStumps = cpuDeliveryOnStumps,
            fieldMode = fieldMode
        )
        applyBallResult(
            BallResult(
                batsman = striker,
                bowler = currentBowler,
                timingQuality = timing,
                aggression = aggression,
                direction = direction,
                pitchLine = cpuDeliveryLine,
                pitchLength = cpuDeliveryLength,
                onStumps = cpuDeliveryOnStumps,
                fieldMode = fieldMode,
                outcome = outcome,
                runsScored = runs
            )
        )
    }

    /**
     * Maps a batting release's timing against the sweep to the aggression tier that drives both
     * BattingResolver's outcome odds and the shot's trajectory shape (see MatchVisuals -
     * unchanged from the old player-chosen aggression's behavior, just now selected by timing
     * precision instead of a separate slider choice): YELLOW is always the safe/defensive tier;
     * GREEN splits by [BattingTimingZones.greenTier] into the broad ground-shot tier (LIGHT) and
     * a thin, dead-center six tier (DARK). RED's aggression value is never actually read -
     * BattingResolver's RED branch resolves the miss before aggression comes into it - so
     * DEFENSIVE here is just an inert placeholder.
     */
    private fun battingAggressionFor(timing: TimingQuality, progress: Float): Aggression = when (timing) {
        TimingQuality.RED -> Aggression.DEFENSIVE
        TimingQuality.YELLOW -> Aggression.DEFENSIVE
        TimingQuality.GREEN -> when (BattingTimingZones.greenTier(progress)) {
            GreenTier.DARK -> Aggression.AERIAL
            GreenTier.LIGHT -> Aggression.GROUND
        }
    }

    /**
     * PART 5 mismatch mechanic - TUNE HERE. If a batting shot's side (from [shotDirection]) and
     * the actual delivery's side (from [deliveryDirection]) don't match - e.g. the batsman aimed
     * off-side but the ball actually came in on-side, or vice versa - the achieved [rawTiming] is
     * knocked down one tier (GREEN->YELLOW, YELLOW->RED) before it drives anything else: the
     * outcome roll (BattingResolver.resolve), the aggression-tier trajectory
     * ([battingAggressionFor]), AND the displayed/visualized timing quality (BatterShot, via
     * BallResult.timingQuality) - the batsman genuinely was less ready for a ball on the side they
     * weren't aiming at, whatever the raw release timing was. RED stays RED (already the worst
     * tier - there's nothing lower to fall to). First-pass magnitude (a flat one-tier downgrade,
     * side-independent, no skill mitigation) - expected to need tuning after playtesting; a softer
     * option would make it a probability rather than a certainty, or let higher batting skill
     * partially resist it.
     */
    private fun downgradeForSideMismatch(rawTiming: TimingQuality, shotDirection: Float, deliveryDirection: Float): TimingQuality {
        val mismatch = sideFromDirection(shotDirection) != sideFromDirection(deliveryDirection)
        return if (mismatch) downgradeTimingTier(rawTiming) else rawTiming
    }

    private fun downgradeTimingTier(timing: TimingQuality): TimingQuality = when (timing) {
        TimingQuality.GREEN -> TimingQuality.YELLOW
        TimingQuality.YELLOW -> TimingQuality.RED
        TimingQuality.RED -> TimingQuality.RED
    }

    /**
     * Called by BowlingControls when the player releases the unified slider. [direction] is the
     * slider's left-right position at that instant (-1f leg side .. +1f off side), driving both
     * the target line (see [lineFromDirection]) and the post-pitch curve bias, replacing the old
     * separate tap-target-line + accelerometer-tilt inputs with one value. [deliveryTiming] is
     * where in the single-pass RED->YELLOW->GREEN->RED sweep the release landed (see
     * [BowlingTimingZones]): EARLY_RED is a weak/short ball (easy pickings for an aggressive,
     * well-timed batsman), YELLOW is comfortably scoreable, GREEN is the hardest to score off -
     * split by [BowlingTimingZones.greenTier] into the bowler's standard best ball (LIGHT) and an
     * even tougher, thin-sliver "perfect ball" tier (DARK) - and LATE_RED is a no-ball: the
     * batting team gets an automatic run and it doesn't count as a legal ball, but the rushed
     * release makes it the hardest of all to add extra runs against (and it can never dismiss the
     * batsman). Length is no longer player-chosen (the old 2D pitch-tap is gone) - the bowler
     * always aims for a good length, and only release accuracy (same as before) can knock it off
     * that per BowlingResolver's existing drift chance.
     */
    fun bowlDelivery(direction: Float, deliveryTiming: DeliveryTiming) {
        if (_uiState.value.phase != DeliveryPhase.RUN_UP) return

        val bowler = currentBowler
        val progress = _runUp.value.progress
        val greenTier = if (deliveryTiming == DeliveryTiming.GREEN) BowlingTimingZones.greenTier(progress) else null
        val targetLine = lineFromDirection(direction)
        val output = BowlingResolver.resolve(
            BowlingInput(targetLine, PitchLength.GOOD_LENGTH, deliveryTiming, bowler.bowlingSkill, direction, greenTier)
        )
        // Lock in this delivery's actual pitch length and the direction sampled at release - the
        // same value BowlingResolver just used for line/length drift - before easing the
        // remaining flight to completion, so the post-pitch curve (see ballTravelX) bends the
        // right way for the whole catch-up animation rather than only starting to apply mid-way.
        _runUp.update { it.copy(pitchLength = output.actualLength, postPitchTilt = direction) }
        finishBallFlight()

        // CPU batsman's approach is rolled now, biased by skill and by how good the release was.
        val batsman = currentStriker
        val skillFactor = batsman.battingSkill / 99.0
        val rawTiming = rollCpuBattingTiming(skillFactor, deliveryTiming, greenTier)
        // On/off-side mismatch penalty (see downgradeForSideMismatch/rollCpuShotSide) - applied to
        // the CPU batsman too, for consistency with the human-batting path above, but kept
        // shallow: the CPU's shot side is a single extra roll purely for this probability, not a
        // real simulated shot direction with its own visual (see rollCpuShotSide's doc for why).
        val cpuShotSide = rollCpuShotSide(skillFactor, sideFromDirection(direction))
        val timing = if (cpuShotSide != sideFromDirection(direction)) downgradeTimingTier(rawTiming) else rawTiming
        val aggression = rollCpuAggression(skillFactor)
        val fieldMode = currentFieldMode()
        val (rawOutcome, rawRuns) = BattingResolver.resolve(batsman.battingSkill, timing, aggression, output.onStumps, fieldMode)

        val isNoBall = deliveryTiming == DeliveryTiming.LATE_RED
        // A no-ball can never dismiss the batsman - any wicket becomes a dot instead - and the
        // batting team gets the automatic penalty run on top of whatever was scored off it.
        val outcome = if (isNoBall && rawOutcome.name.startsWith("WICKET")) BallOutcome.DOT else rawOutcome
        val runs = if (isNoBall) rawRuns + 1 else rawRuns

        applyBallResult(
            BallResult(
                batsman = batsman,
                bowler = bowler,
                timingQuality = timing,
                aggression = aggression,
                direction = direction,
                pitchLine = output.actualLine,
                pitchLength = output.actualLength,
                onStumps = output.onStumps,
                fieldMode = fieldMode,
                outcome = outcome,
                runsScored = runs
            ),
            legalDelivery = !isNoBall
        )
    }

    /** Maps the unified slider's release position to a target line - the same thirds split the
     *  old 2D pitch-tap's X-axis used, just fed from -1f..1f instead of a 0f..1f tap fraction. */
    private fun lineFromDirection(direction: Float): PitchLine = when {
        direction < -1f / 3f -> PitchLine.OUTSIDE_LEG
        direction > 1f / 3f -> PitchLine.OUTSIDE_OFF
        else -> PitchLine.ON_STUMPS
    }

    // --- delivery setup -----------------------------------------------------------------

    private fun startDelivery() {
        // ballsInCurrentOver==0 also holds true when re-bowling straight after a no-ball at the
        // start of an over - awaitingRebowl distinguishes that from an actual new over.
        if (innings.ballsInCurrentOver == 0 && !awaitingRebowl) {
            currentBowler = nextBowlerForOver()
        }
        awaitingRebowl = false

        val playerBatting = innings.battingTeam.id == playerTeam.id
        if (playerBatting) {
            rollCpuDelivery()
        }

        // Fresh run-up state for the new delivery. When the CPU is bowling, its pitch
        // length/tilt are already rolled above, so the post-pitch curve (see ballTravelX) can
        // reflect them from the very first frame; when the player is bowling, both default to a
        // straight ball until their release locks in the real values (see bowlDelivery).
        _runUp.value = RunUpState(
            pitchLength = if (playerBatting) cpuDeliveryLength else PitchLength.GOOD_LENGTH,
            postPitchTilt = if (playerBatting) cpuDeliveryTilt else 0f
        )

        _uiState.update {
            it.copy(
                phase = DeliveryPhase.RUN_UP,
                isPlayerBatting = playerBatting,
                strikerName = currentStriker.name,
                bowlerName = currentBowler.name,
                lastBallSummary = null,
                lastBallAggression = null,
                lastBallTimingQuality = null,
                lastBallDirection = 0f,
                lastBallRuns = 0
            )
        }
        startRunUpLoop()
    }

    private fun startRunUpLoop() {
        runUpJob?.cancel()
        val playerBowling = innings.bowlingTeam.id == playerTeam.id
        // A genuine single one-way pass per ball (see BowlingTimingZones for bowling's
        // RED->YELLOW->GREEN->RED, timingQualityFor below for batting's symmetric five-zone
        // read), deliberately slower while bowling so its four zones stay readable. The run-up
        // itself accelerates toward release, so progress is eased (t^POWER) rather than linear
        // in time. The loop below is bounded by periodMs - it does NOT wrap back to zero and
        // sweep again while waiting; if it reaches the end with no release, resolveNoAction()
        // auto-resolves the ball instead (see there), so a stalled player never sees the gauge
        // repeat/oscillate and never waits indefinitely.
        val periodMs = if (playerBowling) {
            (3600 - currentBowler.bowlingSkill * 16).coerceIn(2200, 3600)
        } else {
            (1600 - currentBowler.bowlingSkill * 8).coerceIn(800, 1600)
        }
        runUpJob = viewModelScope.launch {
            var elapsed = 0L
            while (isActive && elapsed < periodMs) {
                val progress = easedSweep(elapsed, periodMs)
                _runUp.update { it.copy(progress = progress, quality = timingQualityFor(progress)) }
                delay(16)
                elapsed += 16
            }
            if (isActive) resolveNoAction(playerBowling)
        }
    }

    /** One eased one-way sweep (t^[RUN_UP_EASE_POWER]) of [durationMs], sampled at [elapsed] ms
     *  and clamped (not wrapped) at 1f once elapsed passes durationMs - shared shape for the
     *  timing gauge and the ball's pitch-travel visuals, which are now driven by this same
     *  [RunUpState.progress] value rather than a separate clock. */
    private fun easedSweep(elapsed: Long, durationMs: Int): Float {
        val t = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
        return t.pow(RUN_UP_EASE_POWER)
    }

    /**
     * Called when the run-up sweep reaches its natural end with no release from the player -
     * resolves the ball through the exact same paths as a manual release right at the end of
     * the sweep (so the outcome overlay and auto-advance to the next ball are identical either
     * way): a miss while batting - OUT if the delivery was on the stumps, otherwise a dot ball,
     * same as any RED-zone release (see BattingResolver) - or a no-ball while bowling, same as
     * a LATE_RED release (automatic +1 run, doesn't consume a ball of the over, same bowler
     * re-bowls). Either way the ball is always resolved; the sweep never repeats or stalls.
     */
    private fun resolveNoAction(playerBowling: Boolean) {
        _runUp.update { it.copy(progress = 1f, quality = TimingQuality.RED) }
        if (playerBowling) {
            bowlDelivery(0f, DeliveryTiming.LATE_RED)
        } else {
            playBattingShot(0f)
        }
    }

    /**
     * Cancels the run-up sweep and eases the ball's remaining travel - whatever fraction is
     * left of [RunUpState.progress], the same value that positions the ball on the pitch -
     * smoothly to completion over a short, fixed duration, so releasing a shot/delivery never
     * makes the ball visually jump straight to the batsman or stumps, however early or late in
     * the sweep it was played.
     */
    private fun finishBallFlight() {
        runUpJob?.cancel()
        val startProgress = _runUp.value.progress
        if (startProgress >= 1f) return
        runUpJob = viewModelScope.launch {
            var elapsed = 0L
            while (isActive) {
                val t = (elapsed.toFloat() / BALL_FLIGHT_FINISH_MS).coerceIn(0f, 1f)
                val progress = startProgress + (1f - startProgress) * t
                _runUp.update { it.copy(progress = progress, quality = timingQualityFor(progress)) }
                if (t >= 1f) break
                delay(16)
                elapsed += 16
            }
        }
    }

    private fun timingQualityFor(progress: Float): TimingQuality = BattingTimingZones.classify(progress)

    // --- CPU auto-roll helpers (used for whichever side isn't the player this ball) --------

    private fun rollCpuDelivery() {
        val bowler = currentBowler
        val skillFactor = bowler.bowlingSkill / 99.0
        val line = if (Random.nextDouble() < 0.4 + skillFactor * 0.3) PitchLine.ON_STUMPS else PitchLine.entries.random()
        val length = if (Random.nextDouble() < 0.4 + skillFactor * 0.3) PitchLength.GOOD_LENGTH else PitchLength.entries.random()
        val tilt = Random.nextDouble(-1.0, 1.0).toFloat()
        // CPU bowling doesn't go through the interactive sweep, so it can never produce a
        // no-ball here - just a plain accuracy roll skewed by skill.
        val timing = rollCpuDeliveryTiming(skillFactor)

        val output = BowlingResolver.resolve(BowlingInput(line, length, timing, bowler.bowlingSkill, tilt))
        cpuDeliveryOnStumps = output.onStumps
        cpuDeliveryLine = output.actualLine
        cpuDeliveryLength = output.actualLength
        cpuDeliveryTilt = tilt
    }

    private fun rollCpuDeliveryTiming(skillFactor: Double): DeliveryTiming {
        val pLate = (0.06 - skillFactor * 0.03).coerceAtLeast(0.02)
        val pEarly = (0.22 - skillFactor * 0.08).coerceAtLeast(0.05)
        val pYellow = 0.40
        val pGreen = (1.0 - pLate - pEarly - pYellow).coerceAtLeast(0.10)
        val roll = Random.nextDouble() * (pLate + pEarly + pYellow + pGreen)
        return when {
            roll < pLate -> DeliveryTiming.LATE_RED
            roll < pLate + pEarly -> DeliveryTiming.EARLY_RED
            roll < pLate + pEarly + pYellow -> DeliveryTiming.YELLOW
            else -> DeliveryTiming.GREEN
        }
    }

    /**
     * Biases the CPU batsman's own shot-timing roll by how good the player's delivery release
     * was: a weak EARLY_RED ball is easy to time well (high GREEN chance), a well-released GREEN
     * ball is the hardest to time, and a rushed LATE_RED no-ball is hardest of all - even though
     * the batting team banks the automatic run regardless of how this roll comes out. A DARK-tier
     * "perfect ball" (see [greenTier]) claws a further chunk of probability from GREEN back into
     * RED on top of GREEN's own base odds - noticeably harder to score off than a standard LIGHT
     * green ball, and since RED+onStumps is already a wicket via BattingResolver, this is also
     * where the perfect ball's modestly elevated wicket chance comes from, with no separate wicket
     * mechanic needed.
     */
    private fun rollCpuBattingTiming(skillFactor: Double, deliveryTiming: DeliveryTiming, greenTier: GreenTier?): TimingQuality {
        val (baseRed, baseYellow, baseGreen) = when (deliveryTiming) {
            DeliveryTiming.EARLY_RED -> Triple(0.10, 0.25, 0.65)
            DeliveryTiming.YELLOW -> Triple(0.20, 0.45, 0.35)
            DeliveryTiming.GREEN -> Triple(0.45, 0.35, 0.20)
            DeliveryTiming.LATE_RED -> Triple(0.60, 0.28, 0.12)
        }
        val tierShift = if (deliveryTiming == DeliveryTiming.GREEN && greenTier == GreenTier.DARK) 0.18 else 0.0
        // stronger batsmen claw a little probability back from RED into GREEN
        val skillShift = skillFactor * 0.15
        val pRed = (baseRed + tierShift - skillShift).coerceAtLeast(0.03)
        val pGreen = (baseGreen - tierShift + skillShift).coerceAtLeast(0.03)
        val pYellow = baseYellow
        val roll = Random.nextDouble() * (pRed + pYellow + pGreen)
        return when {
            roll < pRed -> TimingQuality.RED
            roll < pRed + pYellow -> TimingQuality.YELLOW
            else -> TimingQuality.GREEN
        }
    }

    private fun rollCpuAggression(skillFactor: Double): Aggression {
        val roll = Random.nextDouble()
        return when {
            roll < 0.3 -> Aggression.DEFENSIVE
            roll < 0.7 + skillFactor * 0.1 -> Aggression.GROUND
            else -> Aggression.AERIAL
        }
    }

    /**
     * PART 5's on/off-side mismatch, wired into the CPU batting path (see [downgradeForSideMismatch]
     * for the human-batting version and the mechanic's full doc). The CPU doesn't have a real
     * simulated shot direction of its own - only aggression is rolled (see [rollCpuAggression]) -
     * so rather than build out a whole parallel "CPU shot direction" concept purely to compare
     * sides (which would also need its own visual, per BatterShot/MatchVisuals, to not be
     * misleading), this is kept intentionally shallow: a single roll for just the SIDE the CPU
     * ends up committed to, weighted by skill (a stronger batsman reads the actual line better and
     * more often ends up on the matching, non-penalized side), used only to decide whether the
     * mismatch penalty applies to this ball - not stored or visualized anywhere.
     */
    private fun rollCpuShotSide(skillFactor: Double, actualSide: ShotSide): ShotSide {
        val matchChance = (0.5 + skillFactor * 0.3).coerceAtMost(0.9)
        return if (Random.nextDouble() < matchChance) actualSide else oppositeSide(actualSide)
    }

    private fun oppositeSide(side: ShotSide): ShotSide = when (side) {
        ShotSide.ON_SIDE -> ShotSide.OFF_SIDE
        ShotSide.OFF_SIDE -> ShotSide.ON_SIDE
    }

    private fun currentFieldMode(): FieldMode {
        val target = innings.target
        return if (target != null) {
            val ballsLeft = (innings.totalOvers * 6 - innings.ballsBowled).coerceAtLeast(1)
            val runsNeeded = (target - innings.totalRuns).coerceAtLeast(0)
            val requiredRunRate = runsNeeded.toDouble() / ballsLeft * 6.0
            if (requiredRunRate > 9.0) FieldMode.ATTACKING else FieldMode.DEFENSIVE
        } else {
            val oversLeft = innings.totalOvers - innings.oversCompleted
            if (oversLeft <= 3) FieldMode.ATTACKING else FieldMode.DEFENSIVE
        }
    }

    private fun nextBowlerForOver(): Player {
        val bowlers = innings.bowlingTeam.bowlers
        var pos = bowlerCyclePos
        var candidate = bowlers[0]
        var found = false
        for (attempt in 0 until bowlers.size * 2) {
            pos = (pos + 1) % bowlers.size
            candidate = bowlers[pos]
            val eligible = innings.oversRemainingFor(candidate) > 0
            val notLastOver = candidate.id != lastOverBowlerId
            if (eligible && (notLastOver || bowlers.size == 1)) {
                found = true
                break
            }
        }
        if (!found) {
            candidate = bowlers.firstOrNull { innings.oversRemainingFor(it) > 0 } ?: candidate
        }
        bowlerCyclePos = pos
        return candidate
    }

    // --- resolving the ball ------------------------------------------------------------

    private fun applyBallResult(result: BallResult, legalDelivery: Boolean = true) {
        innings.recordBall(result, legalDelivery)

        val isWicket = result.outcome.name.startsWith("WICKET")
        if (isWicket) strikerIndex++

        currentOverCodes.add(displayCode(result, legalDelivery))
        awaitingRebowl = !legalDelivery

        _uiState.update {
            it.copy(
                phase = DeliveryPhase.BALL_RESULT,
                score = innings.totalRuns,
                wickets = innings.wickets,
                oversText = "${innings.oversCompleted}.${innings.ballsInCurrentOver}",
                lastBallSummary = summaryFor(result, legalDelivery),
                lastBallAggression = result.aggression,
                lastBallTimingQuality = result.timingQuality,
                lastBallDirection = result.direction,
                lastBallRuns = result.runsScored,
                lastBallOnStumps = result.onStumps,
                recentBalls = currentOverCodes.toList(),
                ballSeq = it.ballSeq + 1
            )
        }

        viewModelScope.launch {
            delay(1400)
            advanceAfterResult()
        }
    }

    private fun advanceAfterResult() {
        // Guard with !awaitingRebowl too: a no-ball bowled as the first ball of an over also
        // has ballsInCurrentOver==0 (it never advanced), which would otherwise look identical
        // to a just-completed over and wrongly wipe the no-ball's display code.
        if (innings.ballsInCurrentOver == 0 && innings.ballsBowled > 0 && !awaitingRebowl) {
            lastOverBowlerId = currentBowler.id
            currentOverCodes.clear()
        }

        if (innings.isComplete) {
            finishInnings()
            return
        }

        currentStriker = innings.battingTeam.players[strikerIndex.coerceIn(0, 10)]
        startDelivery()
    }

    private fun finishInnings() {
        if (innings === match.inningsOne) {
            _uiState.update { it.copy(phase = DeliveryPhase.INNINGS_BREAK, lastBallSummary = "Innings break") }
            viewModelScope.launch {
                delay(1800)
                match.startInningsTwo()
                innings = match.inningsTwo!!
                strikerIndex = 0
                bowlerCyclePos = -1
                lastOverBowlerId = null
                awaitingRebowl = false
                currentOverCodes.clear()
                currentStriker = innings.battingTeam.players[0]

                _uiState.update {
                    it.copy(
                        battingTeamName = innings.battingTeam.name,
                        bowlingTeamName = innings.bowlingTeam.name,
                        isSecondInnings = true,
                        target = innings.target,
                        score = 0,
                        wickets = 0,
                        oversText = "0.0",
                        recentBalls = emptyList()
                    )
                }
                startDelivery()
            }
        } else {
            val resultText = match.result
            _uiState.update { it.copy(phase = DeliveryPhase.MATCH_OVER, matchResult = resultText, lastBallSummary = resultText) }
        }
    }

    private fun summaryFor(result: BallResult, legalDelivery: Boolean): String {
        if (!legalDelivery) {
            val extra = result.runsScored - 1
            return if (extra > 0) "No ball! +$extra runs" else "No ball! 1 run"
        }
        return when (result.outcome) {
            BallOutcome.DOT -> "Dot ball"
            BallOutcome.RUN_1 -> "1 run"
            BallOutcome.RUN_2 -> "2 runs"
            BallOutcome.RUN_3 -> "3 runs"
            BallOutcome.FOUR -> "FOUR!"
            BallOutcome.SIX -> "SIX!"
            BallOutcome.WICKET_BOWLED -> "OUT - Bowled!"
            BallOutcome.WICKET_LBW -> "OUT - LBW!"
            BallOutcome.WICKET_CAUGHT -> "OUT - Caught!"
        }
    }

    private fun displayCode(result: BallResult, legalDelivery: Boolean): String {
        if (!legalDelivery) {
            val extra = result.runsScored - 1
            return if (extra > 0) "Nb+$extra" else "Nb"
        }
        return when (result.outcome) {
            BallOutcome.DOT -> "•"
            BallOutcome.RUN_1 -> "1"
            BallOutcome.RUN_2 -> "2"
            BallOutcome.RUN_3 -> "3"
            BallOutcome.FOUR -> "4"
            BallOutcome.SIX -> "6"
            BallOutcome.WICKET_BOWLED, BallOutcome.WICKET_LBW, BallOutcome.WICKET_CAUGHT -> "W"
        }
    }

    private fun buildInitialUiState(): MatchUiState = MatchUiState(
        format = format,
        battingTeamName = innings.battingTeam.name,
        bowlingTeamName = innings.bowlingTeam.name,
        isPlayerBatting = innings.battingTeam.id == playerTeam.id,
        target = innings.target
    )

    companion object {
        fun factory(
            format: MatchFormat,
            playerTeam: Team,
            cpuTeam: Team,
            tossWinnerIsPlayer: Boolean,
            tossChoice: TossChoice?
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MatchViewModel(format, playerTeam, cpuTeam, tossWinnerIsPlayer, tossChoice)
            }
        }
    }
}
