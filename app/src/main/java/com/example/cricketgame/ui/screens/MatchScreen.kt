package com.example.cricketgame.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 *  - a brief outcome readout between deliveries, and innings-break / match-over states
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

        // BowlingControls owns one large pitch view of its own while the player is bowling, so
        // showing this smaller atmospheric one too would just be a redundant stacked pitch.
        val showBackdrop = uiState.isPlayerBatting || uiState.phase != DeliveryPhase.RUN_UP
        if (showBackdrop) {
            PitchBackdrop(
                ballProgress = if (uiState.phase == DeliveryPhase.RUN_UP) runUp.progress else 1f,
                showBall = uiState.phase == DeliveryPhase.RUN_UP || uiState.phase == DeliveryPhase.BALL_RESULT
            )
            Spacer(Modifier.height(16.dp))
        }

        when (uiState.phase) {
            DeliveryPhase.RUN_UP -> {
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
            DeliveryPhase.BALL_RESULT -> {
                Text(uiState.lastBallSummary ?: "", style = MaterialTheme.typography.headlineSmall)
            }
            DeliveryPhase.INNINGS_BREAK -> {
                Text("Innings break", style = MaterialTheme.typography.headlineSmall)
            }
            DeliveryPhase.MATCH_OVER -> {
                Text(uiState.matchResult ?: "Match complete", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}
