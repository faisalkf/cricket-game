package com.example.cricketgame.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.BowlingTimingZones
import com.example.cricketgame.data.DeliveryTiming
import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.data.PitchLine

/**
 * v1 bowling control surface.
 *
 * - ONE large pitch fills most of the screen. Press and hold anywhere on it to aim - the tap
 *   position directly sets the target line (X) and length (Y), no visible grid.
 * - Release is timed against a single-pass RED->YELLOW->GREEN->RED sweep (see
 *   [BowlingTimingZones]): EARLY_RED/YELLOW/GREEN behave as increasingly good deliveries, and
 *   releasing late (past GREEN, LATE_RED) is a no-ball.
 * - Device tilt is sampled at the moment of release and passed through as postPitchTilt - a
 *   small nudge on top of the tapped target, not an independent aim mechanic.
 */
@Composable
fun BowlingControls(
    runUpProgress: Float,
    tiltDirection: Float,
    bowlerName: String,
    onDeliveryReleased: (targetLine: PitchLine, targetLength: PitchLength, deliveryTiming: DeliveryTiming, postPitchTilt: Float) -> Unit
) {
    var isHolding by remember { mutableStateOf(false) }
    var aimFraction by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

    // Long-lived gesture callbacks capture stale parameter values otherwise - these keep the
    // release handler reading whatever the sweep/tilt actually are at the instant of release.
    val liveProgress by rememberUpdatedState(runUpProgress)
    val liveTilt by rememberUpdatedState(tiltDirection)

    val currentZone = BowlingTimingZones.classify(runUpProgress)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Bowling: $bowlerName")
        Spacer(Modifier.height(8.dp))
        Text(if (isHolding) "Release now to bowl!" else "Press and hold anywhere on the pitch to aim")
        Spacer(Modifier.height(8.dp))
        Text("Timing: ${zoneLabel(currentZone)}", color = zoneColor(currentZone))
        Spacer(Modifier.height(4.dp))
        TimingGauge(progress = runUpProgress, bands = BowlingGaugeBands)
        Spacer(Modifier.height(8.dp))
        Text("Tilt: ${"%.2f".format(tiltDirection)}  (left = leg side, right = off side)")
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            isHolding = true
                            aimFraction = Offset(
                                (offset.x / size.width).coerceIn(0f, 1f),
                                (offset.y / size.height).coerceIn(0f, 1f)
                            )
                            tryAwaitRelease()
                            isHolding = false

                            val deliveryTiming = BowlingTimingZones.classify(liveProgress)
                            val line = lineFromFraction(aimFraction.x)
                            val length = lengthFromFraction(aimFraction.y)
                            onDeliveryReleased(line, length, deliveryTiming, liveTilt)
                        }
                    )
                }
        ) {
            BowlingAimPitch(aimFraction = aimFraction, isHolding = isHolding, liveTilt = tiltDirection)
        }
    }
}

@Composable
private fun BowlingAimPitch(aimFraction: Offset, isHolding: Boolean, liveTilt: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(Color(0xFF3F7D3F), size = Size(w, h)) // outfield, matches the batting backdrop

        val stripLeft = w * 0.30f
        val stripRight = w * 0.70f
        drawRect(
            Color(0xFFD9B876),
            topLeft = Offset(stripLeft, 0f),
            size = Size(stripRight - stripLeft, h)
        ) // pitch

        drawLine(Color.White, Offset(stripLeft, h * 0.06f), Offset(stripRight, h * 0.06f), strokeWidth = 3f)
        drawLine(Color.White, Offset(stripLeft, h * 0.94f), Offset(stripRight, h * 0.94f), strokeWidth = 3f)

        // batting-end stumps (the target, and where length is measured toward) at the bottom
        drawStumps(centerX = w / 2f, baseY = h * 0.94f, scale = 1.6f)
        // a hint of the bowler's own end at the top, for orientation
        drawStumps(centerX = w / 2f, baseY = h * 0.06f, scale = 1.6f)

        drawBatterFigure(centerX = w / 2f - 100f, feetY = h * 0.90f, scale = 1.6f)

        if (isHolding) {
            val touchPoint = Offset(aimFraction.x * w, aimFraction.y * h)
            // The marker is drawn offset above (and nudged sideways by live tilt) rather than
            // right under the touch point, which otherwise hides it under the player's own
            // finger. The nudge updates continuously while still holding - a live preview of
            // the tilt deviation, not just something applied at release.
            val tiltNudge = liveTilt.coerceIn(-1f, 1f) * 70f
            val marker = Offset(
                (touchPoint.x + tiltNudge).coerceIn(40f, w - 40f),
                (touchPoint.y - 140f).coerceIn(40f, h - 40f)
            )
            drawLine(Color.White.copy(alpha = 0.55f), touchPoint, marker, strokeWidth = 2f)
            drawCircle(Color(0xFFB71C1C), radius = 22f, center = marker)
            drawCircle(Color.White, radius = 22f, center = marker, style = Stroke(width = 4f))
            drawLine(Color.White, Offset(marker.x - 32f, marker.y), Offset(marker.x + 32f, marker.y), strokeWidth = 2f)
            drawLine(Color.White, Offset(marker.x, marker.y - 32f), Offset(marker.x, marker.y + 32f), strokeWidth = 2f)
        }
    }
}

private val BowlingGaugeBands = listOf(
    GaugeBand(0f, BowlingTimingZones.YELLOW_START, Color(0xFFD32F2F)),
    GaugeBand(BowlingTimingZones.YELLOW_START, BowlingTimingZones.GREEN_START, Color(0xFFFFC107)),
    GaugeBand(BowlingTimingZones.GREEN_START, BowlingTimingZones.LATE_RED_START, Color(0xFF4CAF50)),
    GaugeBand(BowlingTimingZones.LATE_RED_START, 1f, Color(0xFFD32F2F))
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

private fun lineFromFraction(x: Float): PitchLine = when {
    x < 1f / 3f -> PitchLine.OUTSIDE_LEG
    x < 2f / 3f -> PitchLine.ON_STUMPS
    else -> PitchLine.OUTSIDE_OFF
}

private fun lengthFromFraction(y: Float): PitchLength = when {
    y < 1f / 3f -> PitchLength.SHORT
    y < 2f / 3f -> PitchLength.GOOD_LENGTH
    else -> PitchLength.FULL_YORKER
}
