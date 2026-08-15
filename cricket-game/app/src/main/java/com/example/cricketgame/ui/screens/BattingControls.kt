package com.example.cricketgame.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.Aggression
import com.example.cricketgame.data.TimingQuality

/**
 * v1 batting control surface.
 *
 * - Slider position (0f..1f) maps to Aggression: <0.33 Defensive, 0.33-0.66 Ground, >0.66 Aerial.
 * - "Release" (onPlayShot) captures the slider position at release time AND asks the timing
 *   engine (driven by a separate countdown/animation tied to the bowler's run-up - not shown
 *   here) what TimingQuality that release corresponds to.
 * - Device tilt (direction) is read separately via SensorManager (TODO: wire up
 *   android.hardware.SensorEventListener / TYPE_ROTATION_VECTOR or TYPE_ACCELEROMETER and feed
 *   the resulting -1f..1f value in as `tiltDirection`).
 *
 * This composable is intentionally UI-only; BattingResolver.resolve() does the actual scoring.
 */
@Composable
fun BattingControls(
    timingIndicator: TimingQuality?, // updated externally as the bowler approaches release
    tiltDirection: Float,
    onPlayShot: (aggression: Aggression, timing: TimingQuality) -> Unit
) {
    var sliderPosition by remember { mutableStateOf(0.5f) }

    val indicatorColor = when (timingIndicator) {
        TimingQuality.RED -> Color.Red
        TimingQuality.YELLOW -> Color(0xFFFFC107)
        TimingQuality.GREEN -> Color(0xFF4CAF50)
        null -> Color.Gray
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Tilt: ${"%.2f".format(tiltDirection)}  (left = leg side, right = off side)")
        Spacer(Modifier.height(8.dp))

        Text("Timing", color = indicatorColor)
        Spacer(Modifier.height(4.dp))

        Text(aggressionLabel(sliderPosition))
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = {
                val aggression = sliderToAggression(sliderPosition)
                val timing = timingIndicator ?: TimingQuality.RED
                onPlayShot(aggression, timing)
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
