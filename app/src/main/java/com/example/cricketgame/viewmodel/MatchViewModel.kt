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
import kotlin.random.Random

/** Which sub-state the match screen is in for the current delivery. */
enum class DeliveryPhase { RUN_UP, BALL_RESULT, INNINGS_BREAK, MATCH_OVER }

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
 * RED -> YELLOW -> GREEN -> YELLOW -> RED, batting via aggression-slider release timed against
 * that sweep, and bowling via press-and-hold pitch targeting + tilt-based post-pitch deviation.
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

    /** Called by BowlingControls when the player releases their press-and-hold delivery. */
    fun bowlDelivery(
        targetLine: PitchLine,
        targetLength: PitchLength,
        releaseTimingError: Float,
        postPitchTilt: Float
    ) {
        if (_uiState.value.phase != DeliveryPhase.RUN_UP) return
        runUpJob?.cancel()

        val bowler = currentBowler
        val output = BowlingResolver.resolve(
            BowlingInput(targetLine, targetLength, releaseTimingError, bowler.bowlingSkill, postPitchTilt)
        )

        // CPU batsman's approach is rolled now, biased by skill and by how accurate the delivery was.
        val batsman = currentStriker
        val skillFactor = batsman.battingSkill / 99.0
        val timing = rollCpuBattingTiming(skillFactor)
        val aggression = rollCpuAggression(skillFactor)
        val fieldMode = currentFieldMode()
        val (outcome, runs) = BattingResolver.resolve(batsman.battingSkill, timing, aggression, output.onStumps, fieldMode)

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
            )
        )
    }

    // --- delivery setup -----------------------------------------------------------------

    private fun startDelivery() {
        if (innings.ballsInCurrentOver == 0) {
            currentBowler = nextBowlerForOver()
        }

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
        val periodMs = (1600 - currentBowler.bowlingSkill * 8).coerceIn(800, 1600)
        runUpJob = viewModelScope.launch {
            var elapsed = 0L
            while (isActive) {
                val phase = (elapsed % periodMs).toFloat() / periodMs
                val progress = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                _runUp.value = RunUpState(progress, timingQualityFor(progress))
                delay(16)
                elapsed += 16
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
        val error = (Random.nextDouble() * (1.0 - skillFactor * 0.6)).coerceIn(0.0, 1.0)
        val line = if (Random.nextDouble() < 0.4 + skillFactor * 0.3) PitchLine.ON_STUMPS else PitchLine.entries.random()
        val length = if (Random.nextDouble() < 0.4 + skillFactor * 0.3) PitchLength.GOOD_LENGTH else PitchLength.entries.random()
        val tilt = Random.nextDouble(-1.0, 1.0).toFloat()

        val output = BowlingResolver.resolve(BowlingInput(line, length, error.toFloat(), bowler.bowlingSkill, tilt))
        cpuDeliveryOnStumps = output.onStumps
        cpuDeliveryLine = output.actualLine
        cpuDeliveryLength = output.actualLength
    }

    private fun rollCpuBattingTiming(skillFactor: Double): TimingQuality {
        val roll = Random.nextDouble()
        return when {
            roll < 0.15 + (1 - skillFactor) * 0.15 -> TimingQuality.RED
            roll < 0.55 -> TimingQuality.YELLOW
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

    private fun applyBallResult(result: BallResult) {
        innings.recordBall(result)

        val isWicket = result.outcome.name.startsWith("WICKET")
        if (isWicket) strikerIndex++

        _uiState.update {
            it.copy(
                phase = DeliveryPhase.BALL_RESULT,
                score = innings.totalRuns,
                wickets = innings.wickets,
                oversText = "${innings.oversCompleted}.${innings.ballsInCurrentOver}",
                lastBallSummary = summaryFor(result),
                recentBalls = recentBallsDisplay()
            )
        }

        viewModelScope.launch {
            delay(1400)
            advanceAfterResult()
        }
    }

    private fun advanceAfterResult() {
        if (innings.ballsInCurrentOver == 0 && innings.ballsBowled > 0) {
            lastOverBowlerId = currentBowler.id
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

    private fun summaryFor(result: BallResult): String = when (result.outcome) {
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

    private fun recentBallsDisplay(): List<String> {
        val ballsThisOver = innings.ballsInCurrentOver
        if (ballsThisOver == 0) return emptyList()
        val start = (innings.balls.size - ballsThisOver).coerceAtLeast(0)
        return innings.balls.subList(start, innings.balls.size).map { shortCode(it.outcome) }
    }

    private fun shortCode(outcome: BallOutcome): String = when (outcome) {
        BallOutcome.DOT -> "•"
        BallOutcome.RUN_1 -> "1"
        BallOutcome.RUN_2 -> "2"
        BallOutcome.RUN_3 -> "3"
        BallOutcome.FOUR -> "4"
        BallOutcome.SIX -> "6"
        BallOutcome.WICKET_BOWLED, BallOutcome.WICKET_LBW, BallOutcome.WICKET_CAUGHT -> "W"
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
