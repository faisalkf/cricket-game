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

/** Ease-in exponent for the bowling run-up sweep: t^POWER starts slow and accelerates into release. */
private const val RUN_UP_EASE_POWER = 2.2f

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
    val matchResult: String? = null
)

/**
 * Fast-changing (~60fps) run-up state, kept separate from [MatchUiState] so the timing
 * gauge/ball animation can recompose on their own without redrawing the whole scoreboard.
 */
data class RunUpState(
    val progress: Float = 0f,       // 0f..1f, one full RED->GREEN->RED sweep per delivery
    val quality: TimingQuality = TimingQuality.RED
)

/**
 * Drives a real ball-by-ball match: a bowler run-up timer that sweeps the timing indicator
 * RED -> YELLOW -> GREEN -> YELLOW -> RED (oscillating) while batting, and RED -> YELLOW ->
 * GREEN -> RED (single, slower pass - see [com.example.cricketgame.data.BowlingTimingZones])
 * while bowling, where a late release (past GREEN, no yellow buffer) is a no-ball. Batting is
 * an aggression-slider release timed against its sweep; bowling is press-and-hold pitch
 * targeting where release timing sets delivery quality and tilt nudges the aim.
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

    private var runUpJob: Job? = null

    private val _uiState = MutableStateFlow(buildInitialUiState())
    val uiState: StateFlow<MatchUiState> = _uiState.asStateFlow()

    private val _runUp = MutableStateFlow(RunUpState())
    val runUp: StateFlow<RunUpState> = _runUp.asStateFlow()

    init {
        currentStriker = innings.battingTeam.players[0]
        startDelivery()
    }

    /** Called by BattingControls when the player releases the aggression slider. */
    fun playBattingShot(aggression: Aggression, tiltDirection: Float) {
        if (_uiState.value.phase != DeliveryPhase.RUN_UP) return
        runUpJob?.cancel()

        val timing = _runUp.value.quality
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
                tiltDirection = tiltDirection,
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
     * Called by BowlingControls when the player releases their press-and-hold delivery.
     * [deliveryTiming] is where in the single-pass RED->YELLOW->GREEN->RED sweep the release
     * landed (see [BowlingTimingZones]): EARLY_RED is a weak/short ball (easy pickings for an
     * aggressive, well-timed batsman), YELLOW is comfortably scoreable, GREEN is the hardest to
     * score off, and LATE_RED is a no-ball - the batting team gets an automatic run and it
     * doesn't count as a legal ball, but the rushed release makes it the hardest of all four to
     * add extra runs against (and it can never dismiss the batsman).
     */
    fun bowlDelivery(
        targetLine: PitchLine,
        targetLength: PitchLength,
        deliveryTiming: DeliveryTiming,
        postPitchTilt: Float
    ) {
        if (_uiState.value.phase != DeliveryPhase.RUN_UP) return
        runUpJob?.cancel()

        val bowler = currentBowler
        val output = BowlingResolver.resolve(
            BowlingInput(targetLine, targetLength, deliveryTiming, bowler.bowlingSkill, postPitchTilt)
        )

        // CPU batsman's approach is rolled now, biased by skill and by how good the release was.
        val batsman = currentStriker
        val skillFactor = batsman.battingSkill / 99.0
        val timing = rollCpuBattingTiming(skillFactor, deliveryTiming)
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
                tiltDirection = postPitchTilt,
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

        _uiState.update {
            it.copy(
                phase = DeliveryPhase.RUN_UP,
                isPlayerBatting = playerBatting,
                strikerName = currentStriker.name,
                bowlerName = currentBowler.name,
                lastBallSummary = null
            )
        }
        startRunUpLoop()
    }

    private fun startRunUpLoop() {
        runUpJob?.cancel()
        val playerBowling = innings.bowlingTeam.id == playerTeam.id
        runUpJob = viewModelScope.launch {
            var elapsed = 0L
            if (playerBowling) {
                // Single one-way pass per ball (see BowlingTimingZones), deliberately slower
                // than the batting sweep below so the four RED/YELLOW/GREEN/RED zones stay
                // readable. Loops as a sawtooth rather than folding back, so holding past the
                // no-ball zone just starts a fresh pass instead of freezing there. The run-up
                // itself accelerates toward release, so progress is eased (t^POWER) rather than
                // linear in time - it crawls through EARLY_RED and picks up speed into GREEN.
                val periodMs = (3600 - currentBowler.bowlingSkill * 16).coerceIn(2200, 3600)
                while (isActive) {
                    val t = (elapsed % periodMs).toFloat() / periodMs
                    val progress = t.pow(RUN_UP_EASE_POWER)
                    _runUp.value = RunUpState(progress, timingQualityFor(progress)) // quality unused while bowling
                    delay(16)
                    elapsed += 16
                }
            } else {
                val periodMs = (1600 - currentBowler.bowlingSkill * 8).coerceIn(800, 1600)
                while (isActive) {
                    val phase = (elapsed % periodMs).toFloat() / periodMs
                    val progress = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                    _runUp.value = RunUpState(progress, timingQualityFor(progress))
                    delay(16)
                    elapsed += 16
                }
            }
        }
    }

    private fun timingQualityFor(progress: Float): TimingQuality = when {
        progress < 0.2f || progress > 0.8f -> TimingQuality.RED
        progress < 0.35f || progress > 0.65f -> TimingQuality.YELLOW
        else -> TimingQuality.GREEN
    }

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
     * the batting team banks the automatic run regardless of how this roll comes out.
     */
    private fun rollCpuBattingTiming(skillFactor: Double, deliveryTiming: DeliveryTiming): TimingQuality {
        val (baseRed, baseYellow, baseGreen) = when (deliveryTiming) {
            DeliveryTiming.EARLY_RED -> Triple(0.10, 0.25, 0.65)
            DeliveryTiming.YELLOW -> Triple(0.20, 0.45, 0.35)
            DeliveryTiming.GREEN -> Triple(0.45, 0.35, 0.20)
            DeliveryTiming.LATE_RED -> Triple(0.60, 0.28, 0.12)
        }
        // stronger batsmen claw a little probability back from RED into GREEN
        val skillShift = skillFactor * 0.15
        val pRed = (baseRed - skillShift).coerceAtLeast(0.03)
        val pGreen = baseGreen + skillShift
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
                recentBalls = currentOverCodes.toList()
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
