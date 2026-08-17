package com.example.cricketgame.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.TimingQuality

/**
 * v3 batting control surface - the floating slider panel over the shared full-screen
 * [PitchBackdrop] (see BowlingControls' matching doc). Same unified slider control (see
 * MatchViewModel's class doc, shared identically with BowlingControls): drag the single
 * horizontal slider throughout the run-up sweep, release ("lift") at the chosen moment. The
 * slider's left-right position at release (-1f..1f, leg side .. off side) sets shot direction -
 * replacing the old accelerometer tilt reading entirely. Which zone the sweep was in at that same
 * instant sets timing quality and, within GREEN, the aggression tier (see
 * MatchViewModel.battingAggressionFor) - there's no separate player-chosen aggression slider
 * anymore; it's fully implied by timing precision.
 *
 * Trimmed to one status line (colored by [timingIndicator] instead of spelling out the zone in a
 * full sentence) plus the gauge and slider, no separate numeric-readout line - on the batting
 * screen the CPU's bowler run-up animates through the bottom ~12% of [PitchBackdrop], and the old
 * three-line-plus-instructions panel ran roughly twice as tall as that, sitting well on top of it.
 *
 * This composable is intentionally UI-only; BattingResolver.resolve() does the actual scoring.
 */
@Composable
fun BattingControls(
    runUpProgress: Float,
    timingIndicator: TimingQuality,
    onPlayShot: (direction: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var direction by remember { mutableStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xB3141414))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            timingIndicator.name,
            color = timingQualityColor(timingIndicator),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        TimingGauge(progress = runUpProgress)
        Spacer(Modifier.height(2.dp))
        Slider(
            value = direction,
            onValueChange = { direction = it },
            valueRange = -1f..1f,
            onValueChangeFinished = { onPlayShot(direction) }
        )
    }
}

/** Shared RED/YELLOW/GREEN -> color mapping for the compact timing labels (batting's own
 *  [TimingQuality] here; bowling's finer-grained [com.example.cricketgame.data.DeliveryTiming]
 *  has its own [zoneColor] in BowlingControls). */
internal fun timingQualityColor(quality: TimingQuality): Color = when (quality) {
    TimingQuality.RED -> Color(0xFFEF5350)
    TimingQuality.YELLOW -> Color(0xFFFFC107)
    TimingQuality.GREEN -> Color(0xFF66BB6A)
}
