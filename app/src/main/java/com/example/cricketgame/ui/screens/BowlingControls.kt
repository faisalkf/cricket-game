package com.example.cricketgame.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.data.PitchLine
import com.example.cricketgame.data.TimingQuality

/**
 * v1 bowling control surface.
 *
 * - Press and hold anywhere on the 3x3 pitch map: X position picks the line (leg / stumps / off),
 *   Y position picks the length (short near the bowler's end -> yorker near the batsman's end).
 * - Release is timed against the same run-up sweep the batting side uses; how far from the GREEN
 *   center the sweep was at release becomes releaseTimingError for BowlingResolver.
 * - Device tilt is sampled at the moment of release and passed through as postPitchTilt - a
 *   simple v1 stand-in for "the seam movement/turn you tried to impart after the ball pitches".
 */
@Composable
fun BowlingControls(
    runUpProgress: Float,
    timingQuality: TimingQuality,
    tiltDirection: Float,
    bowlerName: String,
    onDeliveryReleased: (targetLine: PitchLine, targetLength: PitchLength, releaseTimingError: Float, postPitchTilt: Float) -> Unit
) {
    var isHolding by remember { mutableStateOf(false) }
    var aimFraction by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

    // Long-lived gesture callbacks capture stale parameter values otherwise - these keep the
    // release handler reading whatever the sweep/tilt actually are at the instant of release.
    val liveQuality by rememberUpdatedState(timingQuality)
    val liveTilt by rememberUpdatedState(tiltDirection)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Bowling: $bowlerName")
        Spacer(Modifier.height(8.dp))
        Text(if (isHolding) "Release as the sweep hits GREEN" else "Press and hold to aim your line & length")
        Spacer(Modifier.height(8.dp))
        Text("Timing: ${timingQuality.name}")
        Spacer(Modifier.height(4.dp))
        TimingGauge(progress = runUpProgress)
        Spacer(Modifier.height(8.dp))
        Text("Tilt: ${"%.2f".format(tiltDirection)}  (left = leg side, right = off side)")
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(Color(0xFF3F7D3F))
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

                            val quality = liveQuality
                            val error = releaseErrorFor(quality)
                            val line = lineFromFraction(aimFraction.x)
                            val length = lengthFromFraction(aimFraction.y)
                            onDeliveryReleased(line, length, error, liveTilt)
                        }
                    )
                }
        ) {
            PitchAimGrid(aimFraction = aimFraction, isHolding = isHolding)
        }
    }
}

@Composable
private fun PitchAimGrid(aimFraction: Offset, isHolding: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // pitch strip
        val stripLeft = w * 0.3f
        val stripRight = w * 0.7f
        drawRect(
            color = Color(0xFFD9B876),
            topLeft = Offset(stripLeft, 0f),
            size = androidx.compose.ui.geometry.Size(stripRight - stripLeft, h)
        )

        val gridColor = Color(0x33000000)
        // vertical line-boundaries (leg | stumps | off)
        listOf(1f / 3f, 2f / 3f).forEach { frac ->
            drawLine(gridColor, Offset(w * frac, 0f), Offset(w * frac, h), strokeWidth = 2f)
        }
        // horizontal length-boundaries (short | good length | yorker)
        listOf(1f / 3f, 2f / 3f).forEach { frac ->
            drawLine(gridColor, Offset(0f, h * frac), Offset(w, h * frac), strokeWidth = 2f, cap = StrokeCap.Round)
        }

        // stumps at the batting end (bottom)
        val stumpsY = h * 0.94f
        val stumpsCenterX = w / 2f
        drawLine(Color(0xFF3E2723), Offset(stumpsCenterX - 14f, stumpsY), Offset(stumpsCenterX - 14f, stumpsY - 40f), strokeWidth = 4f)
        drawLine(Color(0xFF3E2723), Offset(stumpsCenterX, stumpsY), Offset(stumpsCenterX, stumpsY - 40f), strokeWidth = 4f)
        drawLine(Color(0xFF3E2723), Offset(stumpsCenterX + 14f, stumpsY), Offset(stumpsCenterX + 14f, stumpsY - 40f), strokeWidth = 4f)

        if (isHolding) {
            val target = Offset(aimFraction.x * w, aimFraction.y * h)
            drawCircle(Color(0xFFB71C1C), radius = 16f, center = target)
            drawCircle(Color.White, radius = 16f, center = target, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}

private fun releaseErrorFor(quality: TimingQuality): Float = when (quality) {
    TimingQuality.GREEN -> 0f
    TimingQuality.YELLOW -> 0.5f
    TimingQuality.RED -> 1f
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
