package com.example.cricketgame.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cricketgame.data.MatchFormat
import com.example.cricketgame.data.Team
import com.example.cricketgame.data.TimingQuality
import com.example.cricketgame.data.TossChoice
import com.example.cricketgame.sound.SoundEffects
import com.example.cricketgame.viewmodel.MatchUiState
import com.example.cricketgame.sensor.TiltSensorController
import com.example.cricketgame.viewmodel.DeliveryPhase
import com.example.cricketgame.viewmodel.MatchViewModel
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Real match screen, driven by [MatchViewModel]:
 *  - a scoreboard header (score / wickets / overs / target)
 *  - BattingControls when the player's team is batting
 *  - BowlingControls when the player's team is bowling
 *  - the outcome of each ball as a fading overlay on top of the same screen (no navigation),
 *    plus innings-break / match-over states
 */
@Composable
fun MatchScreen(
    format: MatchFormat,
    playerTeam: Team,
    cpuTeam: Team,
    tossWinnerIsPlayer: Boolean,
    tossChoice: TossChoice?,
    onMatchComplete: (result: String) -> Unit
) {
    val viewModel: MatchViewModel = viewModel(
        factory = MatchViewModel.factory(format, playerTeam, cpuTeam, tossWinnerIsPlayer, tossChoice)
    )

    val context = LocalContext.current
    val tiltController = remember { TiltSensorController(context) }
    DisposableEffect(Unit) {
        tiltController.start()
        onDispose { tiltController.stop() }
    }
    val tilt by tiltController.tilt.collectAsState()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val runUp by viewModel.runUp.collectAsStateWithLifecycle()

    // Fire the nav callback once, exactly when the ViewModel decides the match is over.
    LaunchedEffect(uiState.matchResult) {
        uiState.matchResult?.let { onMatchComplete(it) }
    }

    val soundEffects = remember { SoundEffects() }
    DisposableEffect(Unit) { onDispose { soundEffects.release() } }

    val isWicket = uiState.lastBallSummary?.startsWith("OUT") == true
    val isBoundarySix = uiState.lastBallRuns >= 6

    // ballSeq (not lastBallSummary) is the trigger key - two consecutive balls can produce
    // identical summary text, which wouldn't otherwise re-fire a keyed effect. Skipped at
    // ballSeq==0 so nothing plays/shakes on the very first composition, before any ball exists.
    LaunchedEffect(uiState.ballSeq) {
        if (uiState.ballSeq == 0) return@LaunchedEffect
        when {
            isWicket -> soundEffects.playWicket()
            isBoundarySix -> soundEffects.playBoundary()
            uiState.lastBallTimingQuality != null && uiState.lastBallTimingQuality != TimingQuality.RED ->
                soundEffects.playImpact() // contact was made but it wasn't a boundary/wicket
            // else: a total miss (RED, no contact) - deliberately silent
        }
    }

    // Brief, subtle screen shake - only for a six or a wicket, never on routine balls.
    val shakeOffset = rememberScreenShakeOffset(ballSeq = uiState.ballSeq, shouldShake = isWicket || isBoundarySix)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .graphicsLayer(translationX = shakeOffset.x, translationY = shakeOffset.y)
    ) {
        val targetSuffix = uiState.target?.let { "   Target: $it" } ?: ""
        Text(
            "${uiState.battingTeamName} ${uiState.score}/${uiState.wickets}   Overs: ${uiState.oversText}$targetSuffix",
            style = MaterialTheme.typography.titleMedium
        )
        Text("${uiState.bowlerName} to ${uiState.strikerName}", style = MaterialTheme.typography.bodyMedium)
        if (uiState.recentBalls.isNotEmpty()) {
            Text("This over: ${uiState.recentBalls.joinToString(" ")}")
        }
        Spacer(Modifier.height(12.dp))

        // BowlingControls owns one large pitch view of its own while the player is bowling
        // (during both RUN_UP and BALL_RESULT, since it now stays mounted through the outcome
        // overlay below), so showing this smaller atmospheric one too would be a redundant
        // stacked pitch. It's still shown for batting, and behind the innings-break/match-over
        // text as a bit of atmosphere.
        val showBackdrop = uiState.isPlayerBatting ||
            uiState.phase == DeliveryPhase.INNINGS_BREAK ||
            uiState.phase == DeliveryPhase.MATCH_OVER
        // The just-played shot's pose/trajectory only makes sense while its outcome is showing.
        val batterShot = if (uiState.phase == DeliveryPhase.BALL_RESULT) batterShotFrom(uiState) else null
        if (showBackdrop) {
            PitchBackdrop(
                progress = runUp.progress,
                pitchLength = runUp.pitchLength,
                postPitchTilt = runUp.postPitchTilt,
                showBall = uiState.phase == DeliveryPhase.RUN_UP || uiState.phase == DeliveryPhase.BALL_RESULT,
                shot = batterShot,
                ballSeq = uiState.ballSeq
            )
            Spacer(Modifier.height(16.dp))
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            when (uiState.phase) {
                // Both phases keep the same controls mounted - the outcome of a delivery is
                // shown as an overlay on top rather than swapping to a different screen.
                DeliveryPhase.RUN_UP, DeliveryPhase.BALL_RESULT -> {
                    if (uiState.isPlayerBatting) {
                        BattingControls(
                            runUpProgress = runUp.progress,
                            timingIndicator = runUp.quality,
                            tiltDirection = tilt,
                            onPlayShot = { aggression -> viewModel.playBattingShot(aggression, tilt) }
                        )
                    } else {
                        BowlingControls(
                            runUpProgress = runUp.progress,
                            pitchLength = runUp.pitchLength,
                            postPitchTilt = runUp.postPitchTilt,
                            tiltDirection = tilt,
                            bowlerName = uiState.bowlerName,
                            shot = batterShot,
                            ballSeq = uiState.ballSeq,
                            onDeliveryReleased = { line, length, deliveryTiming, postTilt ->
                                viewModel.bowlDelivery(line, length, deliveryTiming, postTilt)
                            }
                        )
                    }
                }
                DeliveryPhase.INNINGS_BREAK -> {
                    Text("Innings break", style = MaterialTheme.typography.headlineSmall)
                }
                DeliveryPhase.MATCH_OVER -> {
                    Text(uiState.matchResult ?: "Match complete", style = MaterialTheme.typography.headlineSmall)
                }
            }

            OutcomeOverlay(
                visible = uiState.phase == DeliveryPhase.BALL_RESULT,
                summary = uiState.lastBallSummary,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/** Duration of the screen-shake effect - short and subtle by design, not disorienting. */
private const val SHAKE_DURATION_MS = 260

/**
 * A brief, decaying horizontal shake offset - only animated when [shouldShake] is true (a six
 * or a wicket), keyed on [ballSeq] so it restarts exactly once per new ball. Applied to the
 * whole screen's root Modifier via graphicsLayer, not just the pitch, for a bit more impact -
 * kept short (260ms) and low-amplitude so it reads as a jolt rather than something disorienting.
 */
@Composable
private fun rememberScreenShakeOffset(ballSeq: Int, shouldShake: Boolean): Offset {
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(ballSeq) {
        if (!shouldShake) {
            offset = Offset.Zero
            return@LaunchedEffect
        }
        var elapsed = 0L
        while (elapsed < SHAKE_DURATION_MS) {
            val t = elapsed / SHAKE_DURATION_MS.toFloat()
            val decay = 1f - t
            val dx = sin(t * 6 * Math.PI).toFloat() * 14f * decay
            offset = Offset(dx, 0f)
            delay(16)
            elapsed += 16
        }
        offset = Offset.Zero
    }
    return offset
}

/** Builds the just-played shot's pose/trajectory details from ui state, or null if incomplete. */
private fun batterShotFrom(uiState: MatchUiState): BatterShot? {
    val aggression = uiState.lastBallAggression ?: return null
    val timing = uiState.lastBallTimingQuality ?: return null
    return BatterShot(
        aggression, timing, uiState.lastBallTiltDirection, uiState.lastBallRuns, uiState.lastBallOnStumps
    )
}

/**
 * A short-lived badge for the last ball's outcome ("1 run", "FOUR!", "OUT - Bowled!", ...) that
 * appears and fades over whatever's currently on screen, plus a quick red flash for a wicket.
 * Always composed (so AnimatedVisibility can animate both the enter AND the exit), just empty
 * while [visible] is false.
 */
@Composable
private fun OutcomeOverlay(visible: Boolean, summary: String?, modifier: Modifier = Modifier) {
    // The ViewModel clears lastBallSummary the instant the next delivery's RUN_UP begins, which
    // would otherwise blank the badge mid fade-out - keep showing the last real value instead.
    var displayedSummary by remember { mutableStateOf("") }
    LaunchedEffect(summary) {
        summary?.let { displayedSummary = it }
    }
    val isWicket = displayedSummary.startsWith("OUT")
    val isBoundary = displayedSummary == "FOUR!" || displayedSummary == "SIX!"
    val badgeColor = when {
        isWicket -> Color(0xFFD32F2F)
        isBoundary -> Color(0xFF2E7D32)
        displayedSummary.startsWith("No ball") -> Color(0xFFFF8F00)
        else -> Color(0xFF37474F)
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible && isWicket,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(500))
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x66D32F2F)))
        }

        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(initialScale = 0.6f, animationSpec = tween(220)) + fadeIn(tween(160)),
            exit = scaleOut(targetScale = 0.7f, animationSpec = tween(450)) + fadeOut(tween(450))
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .background(badgeColor, shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(
                    displayedSummary,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
