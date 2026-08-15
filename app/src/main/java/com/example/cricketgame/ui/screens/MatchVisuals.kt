package com.example.cricketgame.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.Aggression
import com.example.cricketgame.data.TimingQuality

/**
 * Shared match-screen visuals, kept to plain Canvas shapes (no drawables/assets) so the
 * screen builds reliably everywhere: a colored timing bar with a moving needle (bands are
 * caller-supplied so batting's 5-zone sweep and bowling's 4-zone sweep can each use their own
 * zone layout), and a simple top-down pitch with stumps, a bowler, a batter and a travelling
 * ball.
 */

/** One colored segment of a [TimingGauge], as a from/to fraction (0f..1f) of the bar's width. */
data class GaugeBand(val from: Float, val to: Float, val color: Color)

/** Batting's symmetric RED/YELLOW/GREEN/YELLOW/RED sweep - matches MatchViewModel.timingQualityFor. */
val BattingGaugeBands = listOf(
    GaugeBand(0f, 0.2f, Color(0xFFD32F2F)),
    GaugeBand(0.2f, 0.35f, Color(0xFFFFC107)),
    GaugeBand(0.35f, 0.65f, Color(0xFF4CAF50)),
    GaugeBand(0.65f, 0.8f, Color(0xFFFFC107)),
    GaugeBand(0.8f, 1f, Color(0xFFD32F2F))
)

/**
 * A just-played shot's details, used to briefly swap the batter's resting stance for a pose
 * reflecting the aggression/timing, plus a post-shot trajectory line toward wherever tilt sent
 * it. Shared between the batting screen (the player's own shot) and the bowling screen (the CPU
 * batsman's shot against the player's delivery) - same data either way.
 */
data class BatterShot(
    val aggression: Aggression,
    val timingQuality: TimingQuality,
    val tiltDirection: Float,
    val runs: Int
)

@Composable
fun TimingGauge(progress: Float, bands: List<GaugeBand> = BattingGaugeBands, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(28.dp)) {
        val w = size.width
        val h = size.height

        bands.forEach { band ->
            drawRect(band.color, topLeft = Offset(w * band.from, 0f), size = Size(w * (band.to - band.from), h))
        }

        val x = progress.coerceIn(0f, 1f) * w
        drawLine(Color.Black, Offset(x, -4f), Offset(x, h + 4f), strokeWidth = 5f)
        drawCircle(Color.White, radius = 9f, center = Offset(x, h / 2f))
        drawCircle(Color.Black, radius = 9f, center = Offset(x, h / 2f), style = Stroke(width = 2f))
    }
}

/**
 * The batting-side pitch, deliberately matching BowlingControls' BowlingAimPitch in size and
 * proportions (same 500dp height, same 0.30-0.70 pitch strip, same stump margins and 1.6x
 * figure scale) so the two screens read as the same place rather than two differently-scaled
 * pitches. It has no aim target since batting isn't tap-to-aim, but does animate the bowler's
 * run-up and the ball travelling down the pitch as the delivery is bowled.
 */
@Composable
fun PitchBackdrop(ballProgress: Float, showBall: Boolean, shot: BatterShot? = null, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(500.dp)) {
        val w = size.width
        val h = size.height

        drawRect(Color(0xFF3F7D3F), size = Size(w, h)) // outfield

        val stripLeft = w * 0.30f
        val stripRight = w * 0.70f
        drawRect(
            Color(0xFFD9B876),
            topLeft = Offset(stripLeft, 0f),
            size = Size(stripRight - stripLeft, h)
        ) // pitch

        drawLine(Color.White, Offset(stripLeft, h * 0.06f), Offset(stripRight, h * 0.06f), strokeWidth = 3f)
        drawLine(Color.White, Offset(stripLeft, h * 0.94f), Offset(stripRight, h * 0.94f), strokeWidth = 3f)

        drawStumps(centerX = w / 2f, baseY = h * 0.06f, scale = 1.6f)
        drawStumps(centerX = w / 2f, baseY = h * 0.94f, scale = 1.6f)

        drawBowlerFigure(centerX = w / 2f + 90f, feetY = h * 0.20f, progress = ballProgress, scale = 1.6f)
        drawBatterFigure(centerX = w / 2f - 100f, feetY = h * 0.90f, scale = 1.6f, shot = shot)

        if (showBall) {
            val ballY = ballTravelY(h, ballProgress)
            drawCircle(Color(0xFFB71C1C), radius = 14f, center = Offset(w / 2f, ballY))
            drawCircle(Color.White, radius = 14f, center = Offset(w / 2f, ballY), style = Stroke(width = 2f))
        }
    }
}

/** Y position (within a canvas of height h) for the ball as it travels bowler->batsman end,
 *  synced to the run-up sweep's progress. Shared so both pitch views move it identically. */
internal fun ballTravelY(h: Float, progress: Float): Float = h * (0.10f + 0.80f * progress.coerceIn(0f, 1f))

/** internal (not private) so BowlingControls' larger single-pitch view can reuse these at a bigger scale. */
internal fun DrawScope.drawStumps(centerX: Float, baseY: Float, scale: Float = 1f) {
    val height = 34f * scale
    val spacing = 10f * scale
    val strokeW = 4f * scale
    listOf(-spacing, 0f, spacing).forEach { dx ->
        drawLine(
            Color(0xFF3E2723),
            Offset(centerX + dx, baseY),
            Offset(centerX + dx, baseY - height),
            strokeWidth = strokeW
        )
    }
    drawLine(
        Color(0xFF3E2723),
        Offset(centerX - spacing - 3f * scale, baseY - height),
        Offset(centerX + spacing + 3f * scale, baseY - height),
        strokeWidth = strokeW
    )
}

/**
 * The fielding side's bowler at the bowling end, animated purely from the run-up sweep's
 * progress (0f..1f): a coarse 2-frame running cycle (bucketed by progress, not smoothly
 * interpolated) with a slight approach toward the crease, switching to a fixed delivery/release
 * stride once the sweep is most of the way through. Reused for both the CPU bowler (batting
 * screens, driven by the batting sweep) and the player's own bowler (bowling screen, driven by
 * their release-timing sweep) - same function, same poses, either way.
 */
internal fun DrawScope.drawBowlerFigure(centerX: Float, feetY: Float, progress: Float, scale: Float = 1f) {
    val approach = progress.coerceIn(0f, 1f)
    val feet = feetY - (1f - approach) * 18f * scale
    val bodyTop = feet - 58f * scale
    val bodyBottom = feet - 14f * scale
    val headCenter = Offset(centerX, bodyTop - 12f * scale)
    val headRadius = 12f * scale

    // Fielding side's shirt/cap - reuses the ball/wicket-flash red already in the palette rather
    // than introducing a new color, distinguishing it from the batter's blue.
    val shirt = Color(0xFFB71C1C)
    val shirtOutline = Color(0xFF7F0000)

    drawCircle(shirt, radius = headRadius, center = headCenter)
    drawCircle(shirtOutline, radius = headRadius, center = headCenter, style = Stroke(width = 2f * scale))
    drawLine(shirt, Offset(centerX, bodyTop), Offset(centerX, bodyBottom), strokeWidth = 14f * scale, cap = StrokeCap.Round)

    if (approach > 0.8f) {
        // delivery stride: front leg planted, back leg trailing, bowling arm raised overhead
        drawLine(Color(0xFFEEEEEE), Offset(centerX, bodyBottom), Offset(centerX + 16f * scale, feet), strokeWidth = 8f * scale, cap = StrokeCap.Round)
        drawLine(Color(0xFFEEEEEE), Offset(centerX, bodyBottom), Offset(centerX - 10f * scale, feet - 6f * scale), strokeWidth = 8f * scale, cap = StrokeCap.Round)
        drawLine(shirt, Offset(centerX, bodyTop + 6f * scale), Offset(centerX + 8f * scale, bodyTop - 24f * scale), strokeWidth = 6f * scale, cap = StrokeCap.Round)
    } else {
        // coarse running cycle: a discrete pose switch between two strides, not smooth interpolation
        val strideForward = (approach * 10f).toInt() % 2 == 0
        val frontDx = if (strideForward) 14f else -14f
        drawLine(Color(0xFFEEEEEE), Offset(centerX, bodyBottom), Offset(centerX + frontDx * scale, feet), strokeWidth = 8f * scale, cap = StrokeCap.Round)
        drawLine(Color(0xFFEEEEEE), Offset(centerX, bodyBottom), Offset(centerX - frontDx * scale, feet), strokeWidth = 8f * scale, cap = StrokeCap.Round)
        drawLine(shirt, Offset(centerX, bodyTop + 6f * scale), Offset(centerX - frontDx * 0.5f * scale, bodyTop - 6f * scale), strokeWidth = 6f * scale, cap = StrokeCap.Round)
    }
}

/**
 * The batter figure. With [shot] null, this is the resting stance shown while waiting for the
 * next delivery. With [shot] set (briefly, right after a ball is resolved), it instead shows a
 * pose roughly matching the aggression/timing that was played, plus a simple directional
 * trajectory line toward wherever tilt sent it - longer/greener for a boundary, a short white
 * stub for a single or dot, nothing for a miss.
 */
internal fun DrawScope.drawBatterFigure(centerX: Float, feetY: Float, scale: Float = 1f, shot: BatterShot? = null) {
    val bodyTop = feetY - 58f * scale
    val bodyBottom = feetY - 14f * scale
    val headCenter = Offset(centerX, bodyTop - 12f * scale)
    val headRadius = 12f * scale

    // A helmet (rather than a skin-tone head) reads clearly against both the green outfield
    // and the tan pitch regardless of exact shade - a plain skin tone nearly disappeared
    // against the pitch color here. The outline stroke guarantees contrast either way.
    drawCircle(Color(0xFF1565C0), radius = headRadius, center = headCenter)
    drawCircle(Color(0xFF0D3C73), radius = headRadius, center = headCenter, style = Stroke(width = 2f * scale))
    drawLine(
        Color.White,
        Offset(headCenter.x - 6f * scale, headCenter.y + 3f * scale),
        Offset(headCenter.x + 6f * scale, headCenter.y + 3f * scale),
        strokeWidth = 1.5f * scale
    )

    drawLine(Color(0xFF1565C0), Offset(centerX, bodyTop), Offset(centerX, bodyBottom), strokeWidth = 14f * scale, cap = StrokeCap.Round)
    drawLine(Color(0xFFEEEEEE), Offset(centerX, bodyBottom), Offset(centerX - 10f * scale, feetY), strokeWidth = 8f * scale, cap = StrokeCap.Round)
    drawLine(Color(0xFFEEEEEE), Offset(centerX, bodyBottom), Offset(centerX + 10f * scale, feetY), strokeWidth = 8f * scale, cap = StrokeCap.Round)

    val batColor = Color(0xFF8D6E63)
    if (shot == null) {
        // resting stance - bat grounded just in front, waiting for the next ball
        drawLine(
            batColor,
            Offset(centerX + 10f * scale, bodyBottom - 6f * scale),
            Offset(centerX + 22f * scale, feetY - 2f * scale),
            strokeWidth = 6f * scale,
            cap = StrokeCap.Round
        )
        return
    }

    val missed = shot.timingQuality == TimingQuality.RED
    val batStart = Offset(centerX + 8f * scale, bodyBottom - 4f * scale)
    val batEnd = when {
        missed -> Offset(centerX - 22f * scale, feetY + 6f * scale) // bat trails behind - beaten by the ball
        shot.aggression == Aggression.DEFENSIVE -> Offset(centerX + 10f * scale, feetY) // checked, straight and close
        shot.aggression == Aggression.GROUND -> {
            val reach = if (shot.timingQuality == TimingQuality.GREEN) 36f else 24f
            Offset(centerX + reach * scale, feetY - 8f * scale)
        }
        else -> { // AERIAL
            val reach = if (shot.timingQuality == TimingQuality.GREEN) 30f else 20f
            Offset(centerX + reach * scale, bodyTop - reach * scale)
        }
    }
    drawLine(batColor, batStart, batEnd, strokeWidth = 6f * scale, cap = StrokeCap.Round)

    // Post-shot trajectory: a plain directional line with a dot tip (no arrowhead geometry -
    // stays simple) from the bat toward wherever tilt sent it, length/color roughly reflecting
    // the outcome. Skipped entirely on a miss - there's no shot to show a direction for.
    if (!missed) {
        val trajLen = when {
            shot.runs >= 6 -> 130f
            shot.runs >= 4 -> 100f
            shot.runs >= 1 -> 55f
            else -> 15f // dot ball - minimal stub, still shows contact was made
        } * scale
        val dirX = shot.tiltDirection.coerceIn(-1f, 1f)
        val trajStart = Offset(centerX + 14f * scale, bodyBottom)
        val trajEnd = Offset(trajStart.x + dirX * trajLen, trajStart.y - trajLen * 0.4f)
        val trajColor = if (shot.runs >= 4) Color(0xFF2E7D32) else Color.White.copy(alpha = 0.85f)
        val trajWidth = (if (shot.runs >= 4) 5f else 3f) * scale
        drawLine(trajColor, trajStart, trajEnd, strokeWidth = trajWidth, cap = StrokeCap.Round)
        drawCircle(trajColor, radius = trajWidth * 0.9f, center = trajEnd)
    }
}
