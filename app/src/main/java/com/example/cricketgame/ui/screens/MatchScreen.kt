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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cricketgame.data.BowlingTimingZones
import com.example.cricketgame.data.MatchFormat
import com.example.cricketgame.data.Team
import com.example.cricketgame.data.TimingQuality
import com.example.cricketgame.data.TossChoice
import com.example.cricketgame.sound.SoundEffects
import com.example.cricketgame.viewmodel.MatchUiState
import com.example.cricketgame.viewmodel.DeliveryPhase
import com.example.cricketgame.viewmodel.MatchViewModel
import com.example.cricketgame.viewmodel.OverSummary
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Real match screen, driven by [MatchViewModel]: a full-screen [PitchBackdrop] as the base layer
 * (shared by both batting and bowling now - see its doc), with everything else floating on top of
 * it rather than pushing it down - the scoreboard/timing HUD near the top, the slider control near
 * the bottom (BattingControls or BowlingControls, whichever side the player's on), the outcome of
 * each ball as a fading badge, and an end-of-over scorecard/innings-break/match-over overlay when
 * applicable.
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

    // The just-played shot's pose/trajectory only makes sense while its outcome is showing.
    val batterShot = if (uiState.phase == DeliveryPhase.BALL_RESULT) batterShotFrom(uiState) else null
    // Ties the bowler's run-up/crease visual to the exact same no-ball threshold used to actually
    // resolve a no-ball (see PitchBackdrop/drawBowlerFigure's doc) - only meaningful while the
    // PLAYER is the one bowling; the CPU bowler's progress follows the unrelated batting sweep and
    // has no no-ball concept to sync to, so it keeps PitchBackdrop's 1f default there.
    val creaseProgress = if (!uiState.isPlayerBatting) BowlingTimingZones.LATE_RED_START else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(translationX = shakeOffset.x, translationY = shakeOffset.y)
    ) {
        PitchBackdrop(
            progress = runUp.progress,
            pitchLength = runUp.pitchLength,
            postPitchTilt = runUp.postPitchTilt,
            showBall = uiState.phase == DeliveryPhase.RUN_UP || uiState.phase == DeliveryPhase.BALL_RESULT,
            shot = batterShot,
            outcome = uiState.lastBallOutcome,
            ballSeq = uiState.ballSeq,
            creaseProgress = creaseProgress,
            modifier = Modifier.fillMaxSize()
        )

        ScoreboardOverlay(uiState, modifier = Modifier.align(Alignment.TopCenter))

        when (uiState.phase) {
            // Both phases keep the same controls mounted - the outcome of a delivery is
            // shown as an overlay on top rather than swapping to a different screen.
            DeliveryPhase.RUN_UP, DeliveryPhase.BALL_RESULT -> {
                if (uiState.isPlayerBatting) {
                    BattingControls(
                        runUpProgress = runUp.progress,
                        timingIndicator = runUp.quality,
                        onPlayShot = { direction -> viewModel.playBattingShot(direction) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                } else {
                    BowlingControls(
                        runUpProgress = runUp.progress,
                        bowlerName = uiState.bowlerName,
                        onDeliveryReleased = { direction, deliveryTiming ->
                            viewModel.bowlDelivery(direction, deliveryTiming)
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
            DeliveryPhase.OVER_BREAK -> {
                EndOfOverOverlay(
                    summary = uiState.overSummary,
                    onContinue = { viewModel.continueAfterOver() },
                    modifier = Modifier.matchParentSize()
                )
            }
            DeliveryPhase.INNINGS_BREAK -> {
                CenteredMessage("Innings break")
            }
            DeliveryPhase.MATCH_OVER -> {
                CenteredMessage(uiState.matchResult ?: "Match complete")
            }
        }

        OutcomeOverlay(
            visible = uiState.phase == DeliveryPhase.BALL_RESULT,
            summary = uiState.lastBallSummary,
            modifier = Modifier.matchParentSize()
        )
    }
}

/** The floating scoreboard/status HUD - score, overs, target, who's bowling to whom, this over's
 *  balls so far - a semi-transparent panel near the top of the screen rather than a solid header
 *  pushing the pitch down, kept compact so it doesn't cover the batter/keeper standing just below
 *  it on the full-screen pitch. */
@Composable
private fun ScoreboardOverlay(uiState: MatchUiState, modifier: Modifier = Modifier) {
    val targetSuffix = uiState.target?.let { "   Target: $it" } ?: ""
    Column(
        modifier = modifier
            .padding(top = 12.dp, start = 12.dp, end = 12.dp)
            .background(Color(0xCC1B1B1B), shape = RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            "${uiState.battingTeamName} ${uiState.score}/${uiState.wickets}   Overs: ${uiState.oversText}$targetSuffix",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        Text("${uiState.bowlerName} to ${uiState.strikerName}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        if (uiState.recentBalls.isNotEmpty()) {
            Text("This over: ${uiState.recentBalls.joinToString(" ")}", color = Color.White)
        }
    }
}

/** A simple centered message over a dimming scrim, shared by the innings-break/match-over states -
 *  both just show text over the (now atmospheric, static) pitch behind them. */
@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = Color.White)
    }
}

/**
 * The end-of-over scorecard break: a dimming scrim plus a centered card summarizing the over that
 * just finished (runs, ball-by-ball codes, running score/wickets), with a Continue button the
 * player must tap before the next over's first delivery starts - see
 * MatchViewModel.showOverBreak/continueAfterOver. [summary] is only ever null for a single
 * recomposition right as the phase flips (defensive - shouldn't linger), in which case this draws
 * nothing rather than crash on a stale/missing snapshot.
 */
@Composable
private fun EndOfOverOverlay(summary: OverSummary?, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    if (summary == null) return
    Box(modifier = modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1B1B1B), shape = RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Over ${summary.oversCompleted} complete", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text("This over: ${summary.runsThisOver} run${if (summary.runsThisOver == 1) "" else "s"}", color = Color.White)
            if (summary.ballCodes.isNotEmpty()) {
                Text(summary.ballCodes.joinToString(" "), color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Score: ${summary.totalScore}/${summary.totalWickets}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onContinue) { Text("Continue") }
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
        aggression, timing, uiState.lastBallDirection, uiState.lastBallRuns, uiState.lastBallOnStumps
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
                    .padding(top = 90.dp)
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
