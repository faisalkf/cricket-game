package com.example.cricketgame.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.Aggression
import com.example.cricketgame.data.TimingQuality

/**
 * v1 batting control surface.
 *
 * - Slider position (0f..1f) maps to Aggression: <0.33 Defensive, 0.33-0.66 Ground, >0.66 Aerial.
 * - "Release" (lifting the slider thumb, onValueChangeFinished) is the shot. The MatchViewModel
 *   compares the moment of release against its own live run-up sweep to get TimingQuality, so
 *   this composable only needs to report the chosen aggression.
 * - Device tilt is read via TiltSensorController upstream and passed in as `tiltDirection`
 *   purely for display here (the caller already has the live value to hand to the ViewModel).
 *
 * This composable is intentionally UI-only; BattingResolver.resolve() does the actual scoring.
 */
@Composable
fun BattingControls(
    runUpProgress: Float,
    timingIndicator: TimingQuality,
    tiltDirection: Float,
    onPlayShot: (aggression: Aggression) -> Unit
) {
    var sliderPosition by remember { mutableStateOf(0.5f) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Tilt: ${"%.2f".format(tiltDirection)}  (left = leg side, right = off side)")
        Spacer(Modifier.height(8.dp))

        Text("Timing: ${timingIndicator.name} - release the slider on GREEN")
        Spacer(Modifier.height(4.dp))
        TimingGauge(progress = runUpProgress)
        Spacer(Modifier.height(12.dp))

        Text(aggressionLabel(sliderPosition))
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = {
                onPlayShot(sliderToAggression(sliderPosition))
            }
        )
    }
}

private fun sliderToAggression(value: Float): Aggression = when {
    value < 0.33f -> Aggression.DEFENSIVE
    value < 0.66f -> Aggression.GROUND
    else -> Aggression.AERIAL
}

private fun aggressionLabel(value: Float): String = when (sliderToAggression(value)) {
    Aggression.DEFENSIVE -> "Defensive"
    Aggression.GROUND -> "Ground shot"
    Aggression.AERIAL -> "Aerial / Aggressive"
}
