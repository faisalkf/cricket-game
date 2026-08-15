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

/**
 * Shared match-screen visuals, kept to plain Canvas shapes (no drawables/assets) so the
 * screen builds reliably everywhere: a RED/YELLOW/GREEN/YELLOW/RED timing bar with a moving
 * needle, and a simple top-down pitch with stumps, a batter figure and a travelling ball.
 */

@Composable
fun TimingGauge(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(28.dp)) {
        val w = size.width
        val h = size.height

        fun segment(from: Float, to: Float, color: Color) {
            drawRect(color, topLeft = Offset(w * from, 0f), size = Size(w * (to - from), h))
        }
        segment(0f, 0.2f, Color(0xFFD32F2F))
        segment(0.2f, 0.35f, Color(0xFFFFC107))
        segment(0.35f, 0.65f, Color(0xFF4CAF50))
        segment(0.65f, 0.8f, Color(0xFFFFC107))
        segment(0.8f, 1f, Color(0xFFD32F2F))

        val x = progress.coerceIn(0f, 1f) * w
        drawLine(Color.Black, Offset(x, -4f), Offset(x, h + 4f), strokeWidth = 5f)
        drawCircle(Color.White, radius = 9f, center = Offset(x, h / 2f))
        drawCircle(Color.Black, radius = 9f, center = Offset(x, h / 2f), style = Stroke(width = 2f))
    }
}

@Composable
fun PitchBackdrop(ballProgress: Float, showBall: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
        val w = size.width
        val h = size.height

        drawRect(Color(0xFF3F7D3F), size = Size(w, h)) // outfield

        val stripLeft = w * 0.38f
        val stripRight = w * 0.62f
        drawRect(
            Color(0xFFD9B876),
            topLeft = Offset(stripLeft, 0f),
            size = Size(stripRight - stripLeft, h)
        ) // pitch

        drawLine(Color.White, Offset(stripLeft, h * 0.12f), Offset(stripRight, h * 0.12f), strokeWidth = 3f)
        drawLine(Color.White, Offset(stripLeft, h * 0.88f), Offset(stripRight, h * 0.88f), strokeWidth = 3f)

        drawStumps(centerX = w / 2f, baseY = h * 0.10f)
        drawStumps(centerX = w / 2f, baseY = h * 0.92f)

        drawBatterFigure(centerX = w / 2f - 62f, feetY = h * 0.87f)

        if (showBall) {
            val ballY = h * (0.14f + 0.72f * ballProgress.coerceIn(0f, 1f))
            drawCircle(Color(0xFFB71C1C), radius = 9f, center = Offset(w / 2f, ballY))
            drawCircle(Color.White, radius = 9f, center = Offset(w / 2f, ballY), style = Stroke(width = 1.5f))
        }
    }
}

private fun DrawScope.drawStumps(centerX: Float, baseY: Float) {
    val height = 34f
    val spacing = 10f
    listOf(-spacing, 0f, spacing).forEach { dx ->
        drawLine(
            Color(0xFF3E2723),
            Offset(centerX + dx, baseY),
            Offset(centerX + dx, baseY - height),
            strokeWidth = 4f
        )
    }
    drawLine(
        Color(0xFF3E2723),
        Offset(centerX - spacing - 3f, baseY - height),
        Offset(centerX + spacing + 3f, baseY - height),
        strokeWidth = 4f
    )
}

private fun DrawScope.drawBatterFigure(centerX: Float, feetY: Float) {
    val bodyTop = feetY - 58f
    val bodyBottom = feetY - 14f
    val headCenter = Offset(centerX, bodyTop - 12f)

    // A helmet (rather than a skin-tone head) reads clearly against both the green outfield
    // and the tan pitch regardless of exact shade - a plain skin tone nearly disappeared
    // against the pitch color here. The outline stroke guarantees contrast either way.
    drawCircle(Color(0xFF1565C0), radius = 12f, center = headCenter)
    drawCircle(Color(0xFF0D3C73), radius = 12f, center = headCenter, style = Stroke(width = 2f))
    drawLine(Color.White, Offset(headCenter.x - 6f, headCenter.y + 3f), Offset(headCenter.x + 6f, headCenter.y + 3f), strokeWidth = 1.5f)

    drawLine(Color(0xFF1565C0), Offset(centerX, bodyTop), Offset(centerX, bodyBottom), strokeWidth = 14f, cap = StrokeCap.Round)
    drawLine(Color(0xFFEEEEEE), Offset(centerX, bodyBottom), Offset(centerX - 10f, feetY), strokeWidth = 8f, cap = StrokeCap.Round)
    drawLine(Color(0xFFEEEEEE), Offset(centerX, bodyBottom), Offset(centerX + 10f, feetY), strokeWidth = 8f, cap = StrokeCap.Round)
    // Bat is kept short so it doesn't reach all the way to the batting-end stumps drawn
    // separately just to the right of this figure.
    drawLine(
        Color(0xFF8D6E63),
        Offset(centerX + 10f, bodyBottom - 6f),
        Offset(centerX + 22f, feetY - 2f),
        strokeWidth = 6f,
        cap = StrokeCap.Round
    )
}
