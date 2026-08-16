package com.example.cricketgame.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.TimingQuality

/**
 * v2 batting control surface - the unified slider control (see MatchViewModel's class doc, shared
 * identically with BowlingControls): drag the single horizontal slider throughout the run-up
 * sweep, release ("lift") at the chosen moment. The slider's left-right position at release
 * (-1f..1f, leg side .. off side) sets shot direction - replacing the old accelerometer tilt
 * reading entirely. Which zone the sweep was in at that same instant sets timing quality and,
 * within GREEN, the aggression tier (see MatchViewModel.battingAggressionFor) - there's no
 * separate player-chosen aggression slider anymore; it's fully implied by timing precision.
 *
 * This composable is intentionally UI-only; BattingResolver.resolve() does the actual scoring.
 */
@Composable
fun BattingControls(
    runUpProgress: Float,
    timingIndicator: TimingQuality,
    onPlayShot: (direction: Float) -> Unit
) {
    var direction by remember { mutableStateOf(0f) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Timing: ${timingIndicator.name} - release the slider on GREEN")
        Spacer(Modifier.height(4.dp))
        TimingGauge(progress = runUpProgress)
        Spacer(Modifier.height(12.dp))

        Text("Shot direction: ${"%.2f".format(direction)}  (left = leg side, right = off side)")
        Slider(
            value = direction,
            onValueChange = { direction = it },
            valueRange = -1f..1f,
            onValueChangeFinished = { onPlayShot(direction) }
        )
    }
}
