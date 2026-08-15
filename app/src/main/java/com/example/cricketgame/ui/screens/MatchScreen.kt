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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cricketgame.data.MatchFormat
import com.example.cricketgame.data.Team
import com.example.cricketgame.data.TossChoice
import com.example.cricketgame.sensor.TiltSensorController
import com.example.cricketgame.viewmodel.DeliveryPhase
import com.example.cricketgame.viewmodel.MatchViewModel

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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
        if (showBackdrop) {
            PitchBackdrop(
                ballProgress = if (uiState.phase == DeliveryPhase.RUN_UP) runUp.progress else 1f,
                showBall = uiState.phase == DeliveryPhase.RUN_UP || uiState.phase == DeliveryPhase.BALL_RESULT
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
                            tiltDirection = tilt,
                            bowlerName = uiState.bowlerName,
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
