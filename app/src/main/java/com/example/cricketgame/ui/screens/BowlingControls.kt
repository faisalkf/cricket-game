package com.example.cricketgame.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.BowlingTimingZones
import com.example.cricketgame.data.DeliveryTiming

/**
 * v3 bowling control surface - now just the floating slider panel; the pitch itself (bowler
 * run-up, batter, wicketkeeper, ball) lives entirely in the shared full-screen [PitchBackdrop],
 * drawn once by MatchScreen underneath this and BattingControls' equivalent panel rather than
 * each screen owning a near-duplicate pitch view. Same unified control as BattingControls: drag
 * the single horizontal slider throughout the run-up sweep, release at the chosen moment. The
 * slider's left-right position at release (-1f..1f) sets the delivery's line, replacing both the
 * old 2D press-and-hold pitch-tap (line + length) and the accelerometer tilt nudge that used to
 * ride on top of it - one value now does what both used to. Release timing against the sweep sets
 * delivery quality, with GREEN further split by MatchViewModel into a standard-best-ball/
 * perfect-ball tier (see BowlingTimingZones.greenTier). Length is no longer player-chosen - the
 * bowler always aims for a good length; only release accuracy can still knock it off that.
 */
@Composable
fun BowlingControls(
    runUpProgress: Float,
    bowlerName: String,
    onDeliveryReleased: (direction: Float, deliveryTiming: DeliveryTiming) -> Unit,
    modifier: Modifier = Modifier
) {
    var direction by remember { mutableStateOf(0f) }

    // Long-lived gesture callback captures a stale parameter value otherwise - this keeps the
    // release handler reading whatever the sweep actually is at the instant of release.
    val liveProgress by rememberUpdatedState(runUpProgress)

    val currentZone = BowlingTimingZones.classify(runUpProgress)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC1B1B1B), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(16.dp)
    ) {
        Text("Bowling: $bowlerName", color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Timing: ${zoneLabel(currentZone)}", color = zoneColor(currentZone))
        Spacer(Modifier.height(4.dp))
        TimingGauge(progress = runUpProgress, bands = BowlingGaugeBands)
        Spacer(Modifier.height(8.dp))
        Text(
            "Line: ${"%.2f".format(direction)}  (left = leg side, right = off side; drag and release to bowl)",
            color = Color.White
        )
        Slider(
            value = direction,
            onValueChange = { direction = it },
            valueRange = -1f..1f,
            onValueChangeFinished = {
                onDeliveryReleased(direction, BowlingTimingZones.classify(liveProgress))
            }
        )
    }
}

private val BowlingGaugeBands = listOf(
    GaugeBand(0f, BowlingTimingZones.YELLOW_START, Color(0xFFD32F2F)),
    GaugeBand(BowlingTimingZones.YELLOW_START, BowlingTimingZones.GREEN_START, Color(0xFFFFC107)),
    GaugeBand(BowlingTimingZones.GREEN_START, BowlingTimingZones.LATE_RED_START, Color(0xFF4CAF50)),
    GaugeBand(BowlingTimingZones.LATE_RED_START, 1f, Color(0xFFD32F2F)),
    // A bright sliver marking BowlingTimingZones' DARK "perfect ball" tier, drawn on top of the
    // GREEN band it sits inside - otherwise, like batting's six tier, it'd be an invisible,
    // unaimable target.
    GaugeBand(
        BowlingTimingZones.GREEN_CENTER - BowlingTimingZones.DARK_GREEN_HALF_WIDTH,
        BowlingTimingZones.GREEN_CENTER + BowlingTimingZones.DARK_GREEN_HALF_WIDTH,
        Color(0xFFFFD700)
    )
)

private fun zoneLabel(zone: DeliveryTiming): String = when (zone) {
    DeliveryTiming.EARLY_RED -> "EARLY - weak ball"
    DeliveryTiming.YELLOW -> "YELLOW - decent"
    DeliveryTiming.GREEN -> "GREEN - best ball"
    DeliveryTiming.LATE_RED -> "LATE - NO BALL!"
}

private fun zoneColor(zone: DeliveryTiming): Color = when (zone) {
    DeliveryTiming.EARLY_RED, DeliveryTiming.LATE_RED -> Color(0xFFD32F2F)
    DeliveryTiming.YELLOW -> Color(0xFFFFC107)
    DeliveryTiming.GREEN -> Color(0xFF4CAF50)
}
