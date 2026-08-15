package com.example.cricketgame.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.TimingQuality

/**
 * v1 placeholder match screen. Wires together:
 *  - a scoreboard header (score / wickets / overs)
 *  - BattingControls when the player is batting
 *  - BowlingControls when the player is bowling manually
 *  - a simplified CPU-innings ball-by-ball readout otherwise (per design doc section 6)
 *
 * TODO: connect this to a real MatchViewModel that holds the Match/Innings state, drives the
 * bowler run-up animation (and derives the live RED/YELLOW/GREEN timing indicator from it),
 * and calls BattingResolver / BowlingResolver / CpuInningsSimulator to advance the game.
 */
@Composable
fun MatchScreen(onMatchComplete: () -> Unit) {
    // Placeholder local state standing in for the real ViewModel-driven state.
    var score by remember { mutableStateOf(0) }
    var wickets by remember { mutableStateOf(0) }
    var overs by remember { mutableStateOf("0.0") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Score: $score / $wickets   Overs: $overs", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))

        // Example wiring for the batting turn - swap for bowling/CPU state as appropriate.
        BattingControls(
            timingIndicator = TimingQuality.GREEN, // TODO: drive from run-up animation timer
            tiltDirection = 0f,                    // TODO: drive from SensorManager
            onPlayShot = { aggression, timing ->
                // TODO: call BattingResolver.resolve(...) with real batsman/onStumps/fieldMode
                // and update score/wickets/overs from the returned outcome.
                score += 1
            }
        )
    }
}
