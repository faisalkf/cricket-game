package com.example.cricketgame.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.Aggression
import com.example.cricketgame.data.BattingTimingZones
import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.data.TimingQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared match-screen visuals, kept to plain Canvas shapes (no drawables/assets) so the
 * screen builds reliably everywhere: a colored timing bar with a moving needle (bands are
 * caller-supplied so batting's 5-zone sweep and bowling's 4-zone sweep can each use their own
 * zone layout), and a simple top-down pitch with stumps, a bowler, a batter and a travelling
 * ball. Figures/background lean on gradient brushes rather than flat fills for a bit of shading,
 * but stay well within plain Canvas primitives - no bitmaps.
 */

/** One colored segment of a [TimingGauge], as a from/to fraction (0f..1f) of the bar's width. */
data class GaugeBand(val from: Float, val to: Float, val color: Color)

/** Batting's symmetric RED/YELLOW/GREEN/YELLOW/RED sweep - matches BattingTimingZones.classify,
 *  with a bright sliver dropped in the middle of GREEN marking BattingTimingZones' DARK "six
 *  tier" (drawn after/on top of the GREEN band it sits inside, so it isn't just painted over) -
 *  otherwise that tier would be an invisible, unaimable pixel-wide target. */
val BattingGaugeBands = listOf(
    GaugeBand(0f, BattingTimingZones.YELLOW_START, Color(0xFFD32F2F)),
    GaugeBand(BattingTimingZones.YELLOW_START, BattingTimingZones.GREEN_START, Color(0xFFFFC107)),
    GaugeBand(BattingTimingZones.GREEN_START, BattingTimingZones.GREEN_END, Color(0xFF4CAF50)),
    GaugeBand(BattingTimingZones.GREEN_END, BattingTimingZones.RED_START, Color(0xFFFFC107)),
    GaugeBand(BattingTimingZones.RED_START, 1f, Color(0xFFD32F2F)),
    GaugeBand(
        BattingTimingZones.GREEN_CENTER - BattingTimingZones.DARK_GREEN_HALF_WIDTH,
        BattingTimingZones.GREEN_CENTER + BattingTimingZones.DARK_GREEN_HALF_WIDTH,
        Color(0xFFFFD700)
    )
)

/**
 * A just-played shot's details, used to briefly swap the batter's resting stance for a pose
 * reflecting the aggression/timing, plus a post-shot trajectory line toward wherever the unified
 * slider's release position sent it. Shared between the batting screen (the player's own shot)
 * and the bowling screen (the CPU batsman's shot against the player's delivery) - same data
 * either way.
 */
data class BatterShot(
    val aggression: Aggression,
    val timingQuality: TimingQuality,
    val direction: Float,
    val runs: Int,
    // Whether this delivery was on the stumps - only meaningful when timingQuality is RED (a
    // miss), where it decides whether the ball's post-outcome flight ends by breaking the stumps
    // or stopping near the batsman (see missFateFor/missedBallOffset).
    val onStumps: Boolean = false
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

/** Duration of the bat-swoosh sweep/trail animation once a shot is played. */
private const val SWOOSH_DURATION_MS = 260

/** Duration of the dust-puff burst at the point of bat-ball contact. */
private const val DUST_DURATION_MS = 320

/** Bat-swoosh + dust-puff progress (each 0f..1f) for a just-played shot. */
data class ShotImpactProgress(val swoosh: Float, val dust: Float)

/**
 * Drives [ShotImpactProgress] for a just-played shot, keyed on [ballSeq] (a per-ball counter
 * from MatchUiState, not the shot's own content) so it restarts exactly once per new ball rather
 * than on every recomposition - shared between PitchBackdrop and BowlingAimPitch so both
 * screens' bat-swoosh/dust-puff timing feel identical. Dust only plays when contact was actually
 * made (skipped on a RED-zone miss - there's nothing to puff).
 */
@Composable
internal fun rememberShotImpactProgress(ballSeq: Int, shot: BatterShot?): ShotImpactProgress {
    val swoosh = remember { Animatable(0f) }
    val dust = remember { Animatable(0f) }
    LaunchedEffect(ballSeq) {
        if (shot == null) return@LaunchedEffect
        swoosh.snapTo(0f)
        dust.snapTo(0f)
        launch { swoosh.animateTo(1f, tween(SWOOSH_DURATION_MS, easing = FastOutSlowInEasing)) }
        if (shot.timingQuality != TimingQuality.RED) {
            launch { dust.animateTo(1f, tween(DUST_DURATION_MS, easing = LinearOutSlowInEasing)) }
        }
    }
    return ShotImpactProgress(swoosh.value, dust.value)
}

/** Duration of the ball's post-outcome flight - continuing on to the stumps/batsman on a miss
 *  (see [missedBallOffset]), or its aggression-shaped trajectory after contact (see
 *  [contactBallOffset]) - varying by shot type so aerial reads as the fastest ball and defensive
 *  the slowest, per Aggression's own scale, even though aerial actually travels the furthest. */
private fun postOutcomeDurationMs(shot: BatterShot): Int = when {
    shot.timingQuality == TimingQuality.RED -> 320
    shot.aggression == Aggression.DEFENSIVE -> 650
    shot.aggression == Aggression.GROUND -> if (shot.timingQuality == TimingQuality.GREEN) 420 else 520
    else -> 380 // AERIAL
}

/**
 * Drives the ball's post-outcome animation (0f..1f), keyed on [ballSeq] like
 * [rememberShotImpactProgress]. Waits for the pre-contact flight to actually finish arriving
 * ([runUpProgress] reaching ~1f via MatchViewModel's finishBallFlight catch-up) before starting,
 * so the ball visibly completes its approach before continuing on to its post-outcome path
 * rather than the two overlapping or racing each other.
 */
@Composable
internal fun rememberPostOutcomeProgress(ballSeq: Int, shot: BatterShot?, runUpProgress: Float): Float {
    val t = remember { Animatable(0f) }
    val liveRunUpProgress = rememberUpdatedState(runUpProgress)
    LaunchedEffect(ballSeq) {
        t.snapTo(0f)
        if (shot == null) return@LaunchedEffect
        while (liveRunUpProgress.value < 0.999f) delay(16)
        t.animateTo(1f, tween(postOutcomeDurationMs(shot), easing = LinearOutSlowInEasing))
    }
    return t.value
}

/**
 * The batting-side pitch, deliberately matching BowlingControls' BowlingAimPitch in size and
 * proportions (same 500dp height, same 0.30-0.70 pitch strip, same stump margins and 1.6x
 * figure scale) so the two screens read as the same place rather than two differently-scaled
 * pitches. It has no aim target since batting isn't tap-to-aim, but does animate the bowler's
 * run-up and the ball travelling down the pitch as the delivery is bowled.
 */
@Composable
fun PitchBackdrop(
    progress: Float,
    pitchLength: PitchLength,
    postPitchTilt: Float,
    showBall: Boolean,
    shot: BatterShot? = null,
    ballSeq: Int = 0,
    modifier: Modifier = Modifier
) {
    val impact = rememberShotImpactProgress(ballSeq, shot)
    val postOutcome = rememberPostOutcomeProgress(ballSeq, shot, progress)
    Canvas(modifier = modifier.fillMaxWidth().height(500.dp)) {
        val w = size.width
        val h = size.height
        val batterCenterX = w / 2f - 100f
        val batterFeetY = h * 0.10f

        drawPitchBackground(w, h)

        val breakT = stumpsBreakT(shot, postOutcome)
        if (breakT != null) {
            drawBrokenStumps(centerX = w / 2f, baseY = h * 0.06f, scale = 1.6f, breakT = breakT)
        } else {
            drawStumps(centerX = w / 2f, baseY = h * 0.06f, scale = 1.6f)
        }
        drawStumps(centerX = w / 2f, baseY = h * 0.94f, scale = 1.6f)

        // Bowler at the bottom, batter at the top - matches BowlingAimPitch's layout so both
        // screens read as the same place, and the ball always travels bottom -> top.
        drawBowlerFigure(centerX = w / 2f + 90f, feetY = h * 0.80f, progress = progress, scale = 1.6f)
        drawBatterFigure(
            centerX = batterCenterX, feetY = batterFeetY, scale = 1.6f, shot = shot,
            swooshProgress = impact.swoosh, dustProgress = impact.dust
        )

        if (showBall) {
            val ballPos = ballOffsetFor(
                w, h, progress, pitchLength, postPitchTilt, shot, postOutcome, batterCenterX, batterFeetY
            )
            val radius = ballRadiusFor(shot, postOutcome)
            drawCircle(Color(0xFFB71C1C), radius = radius, center = ballPos)
            drawCircle(Color.White, radius = radius, center = ballPos, style = Stroke(width = 2f))
        }
    }
}

/** Y position (within a canvas of height h) for the ball as it travels bowler->batsman end,
 *  synced to the run-up sweep's progress. Shared so both pitch views move it identically.
 *  The bowler's end is at the BOTTOM of the canvas and the batter's end at the TOP (see
 *  PitchBackdrop/BowlingAimPitch), so progress 0f->1f sweeps y from bottom to top. */
internal fun ballTravelY(h: Float, progress: Float): Float = h * (0.90f - 0.80f * progress.coerceIn(0f, 1f))

/** Where along the run-up sweep (0f..1f) the ball pitches/bounces on its way up the pitch, before
 *  the post-pitch curve (see [ballTravelX]) starts bending it: a short ball bounces early
 *  (closer to the bowler), a full/yorker length barely bounces at all (closer to the batsman),
 *  good length lands roughly in between. */
private fun bouncePointProgress(length: PitchLength): Float = when (length) {
    PitchLength.SHORT -> 0.32f
    PitchLength.GOOD_LENGTH -> 0.55f
    PitchLength.FULL_YORKER -> 0.80f
}

/** X position (within a canvas of width w) for the ball as it travels bowler->batsman end.
 *  Dead straight down the middle until [bouncePointProgress], then bends toward [postPitchTilt]'s
 *  side (negative = leg side, positive = off side) with an ease-in bend, so the deviation is
 *  subtle right off the bounce and most pronounced by the time it reaches the batsman - the same
 *  postPitchTilt sampled at release that BowlingResolver already uses for line/length drift, now
 *  also driving a visible curve rather than only an invisible stat. */
internal fun ballTravelX(w: Float, progress: Float, pitchLength: PitchLength, postPitchTilt: Float): Float {
    val bounce = bouncePointProgress(pitchLength)
    val p = progress.coerceIn(0f, 1f)
    if (p <= bounce) return w / 2f
    val t = (p - bounce) / (1f - bounce)
    val bend = t * t
    val maxDeviation = w * 0.08f
    return w / 2f + postPitchTilt.coerceIn(-1f, 1f) * maxDeviation * bend
}

/** How a missed (RED-zone) delivery's ball ends up, once its post-outcome flight completes. */
private enum class MissBallFate {
    /** On the stumps - the ball carries through to break them (bowled/lbw, see [drawBrokenStumps]). */
    HITS_STUMPS,
    /** Not on the stumps - the ball stops roughly at the batsman, as if off the pad/body (dot ball). */
    HITS_BODY,
    /** Collides with neither - sails past and off the top of the screen. Not reachable from the
     *  current engine (BattingResolver's RED branch is exactly HITS_STUMPS or HITS_BODY, see
     *  [missFateFor]), but implemented as a real, working path rather than a stub so the ball
     *  never has nowhere-defined to go if that ever changes (e.g. a future wide-ball concept). */
    SAILS_PAST
}

private fun missFateFor(shot: BatterShot): MissBallFate =
    if (shot.onStumps) MissBallFate.HITS_STUMPS else MissBallFate.HITS_BODY

/** The ball's position [t] (0f..1f) of the way through a missed delivery's post-outcome flight,
 *  continuing on from wherever it was when it arrived ([arrival]) per [fate]. */
private fun missedBallOffset(w: Float, h: Float, fate: MissBallFate, t: Float, arrival: Offset, batterCenterX: Float): Offset {
    val tc = t.coerceIn(0f, 1f)
    return when (fate) {
        MissBallFate.HITS_STUMPS -> lerp(arrival, Offset(w / 2f, h * 0.06f), tc)
        MissBallFate.HITS_BODY -> {
            // A short, quick settle toward the batsman's stance rather than the ball's own
            // straight-line arrival point, then holds - reads as striking the pad and stopping.
            val settle = (tc / 0.35f).coerceIn(0f, 1f)
            Offset(lerpFloat(arrival.x, batterCenterX + 24f, settle), arrival.y + 4f)
        }
        MissBallFate.SAILS_PAST -> Offset(arrival.x, lerpFloat(arrival.y, -40f, tc))
    }
}

/** The ball's position [t] (0f..1f) of the way through a successfully-hit delivery's
 *  post-outcome flight, starting from the bat contact point [batStart] - shape/distance/speed
 *  determined by [BatterShot.aggression] (see class doc), magnitude nudged by [BatterShot.runs]
 *  (a boundary travels further/faster than a single or a dot/catch) without trying to model
 *  anything more elaborate than that. */
private fun contactBallOffset(shot: BatterShot, t: Float, batStart: Offset, w: Float, scale: Float): Offset {
    val tc = t.coerceIn(0f, 1f)
    val dirX = shot.direction.coerceIn(-1f, 1f)
    val runFactor = when {
        shot.runs >= 6 -> 1.3f
        shot.runs >= 4 -> 1.1f
        shot.runs >= 1 -> 0.85f
        else -> 0.6f // dot ball or a catch - short/low, still visibly a shot
    }
    return when (shot.aggression) {
        Aggression.DEFENSIVE -> {
            // Slow and short - barely leaves the bat, whatever the outcome.
            val end = Offset(batStart.x + dirX * 24f * scale * runFactor, batStart.y + 16f * scale)
            lerp(batStart, end, tc)
        }
        Aggression.GROUND -> {
            // Stays low (constant ball size, see ballRadiusFor) - a flat, mostly horizontal path
            // out toward the boundary in the tilt-determined direction.
            val end = Offset(batStart.x + dirX * w * 0.42f * runFactor, batStart.y + 50f * scale)
            lerp(batStart, end, tc)
        }
        Aggression.AERIAL -> {
            // A looping arc rather than a straight line - the quadratic bezier's control point is
            // pulled wide and "up" (toward smaller y) of the straight-line midpoint, and the ball
            // itself is drawn larger mid-flight (see ballRadiusFor) to read as lofted rather than
            // along the ground.
            val end = Offset(batStart.x + dirX * w * 0.48f * runFactor, batStart.y + 90f * scale * runFactor)
            val control = Offset(
                (batStart.x + end.x) / 2f + dirX * w * 0.10f,
                minOf(batStart.y, end.y) - 70f * scale
            )
            quadraticBezier(batStart, control, end, tc)
        }
    }
}

/** Plain scalar lerp - androidx.compose.ui.geometry.lerp only overloads Offset/Size/Rect/etc.,
 *  not bare Float, so the handful of single-axis interpolations above (missedBallOffset) need
 *  their own. */
private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

private fun quadraticBezier(p0: Offset, p1: Offset, p2: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
        u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y
    )
}

/** The ball's on-screen position at any point across a delivery: its pre-contact flight down the
 *  pitch (bent post-pitch by [ballTravelX]) while [shot] is null or the post-outcome animation
 *  hasn't started yet, then handed off to [missedBallOffset]/[contactBallOffset] for the rest of
 *  the flight once it has (see [rememberPostOutcomeProgress]). */
internal fun ballOffsetFor(
    w: Float,
    h: Float,
    progress: Float,
    pitchLength: PitchLength,
    postPitchTilt: Float,
    shot: BatterShot?,
    postOutcomeT: Float,
    batterCenterX: Float,
    batterFeetY: Float,
    scale: Float = 1.6f
): Offset {
    val preContact = Offset(ballTravelX(w, progress, pitchLength, postPitchTilt), ballTravelY(h, progress))
    if (shot == null || postOutcomeT <= 0f) return preContact
    return if (shot.timingQuality == TimingQuality.RED) {
        missedBallOffset(w, h, missFateFor(shot), postOutcomeT, preContact, batterCenterX)
    } else {
        val batStart = Offset(batterCenterX + 14f * scale, batterFeetY - 14f * scale)
        contactBallOffset(shot, postOutcomeT, batStart, w, scale)
    }
}

/** How far through its post-outcome flight ([0f..1f]) an on-stumps miss needs to be before the
 *  stumps start breaking, and how far into that break it is - null while there's nothing to
 *  break (no shot, a miss elsewhere, or contact was made). */
internal fun stumpsBreakT(shot: BatterShot?, postOutcomeT: Float): Float? {
    if (shot == null || shot.timingQuality != TimingQuality.RED || !shot.onStumps) return null
    val breakStart = 0.55f
    if (postOutcomeT <= breakStart) return 0f
    return ((postOutcomeT - breakStart) / (1f - breakStart)).coerceIn(0f, 1f)
}

/** The ball's drawn radius - constant, except a brief mid-flight bulge on an aerial shot to read
 *  as lofted/higher rather than along the ground (see [contactBallOffset]). */
internal fun ballRadiusFor(shot: BatterShot?, postOutcomeT: Float): Float {
    val base = 14f
    if (shot == null || postOutcomeT <= 0f) return base
    if (shot.timingQuality == TimingQuality.RED || shot.aggression != Aggression.AERIAL) return base
    return base + 10f * sin(postOutcomeT.coerceIn(0f, 1f) * PI.toFloat())
}

/**
 * The pitch/outfield background shared by both pitch views - a mown-stripe outfield with a soft
 * vignette and a pitch strip with a worn-centre gradient, rather than the flat single-color
 * fills this used to be, for a bit more illustrated richness. internal (not private) so
 * BowlingControls' pitch view can reuse it, keeping both screens visually identical.
 */
internal fun DrawScope.drawPitchBackground(w: Float, h: Float) {
    // Outfield: alternating mown-stripe bands (a real groundskeeping look) instead of one flat
    // green fill.
    val stripeCount = 12
    val stripeH = h / stripeCount
    for (i in 0 until stripeCount) {
        val shade = if (i % 2 == 0) Color(0xFF3E7C3E) else Color(0xFF4A8C4A)
        drawRect(shade, topLeft = Offset(0f, i * stripeH), size = Size(w, stripeH + 1f))
    }
    // Soft vignette for a touch of depth toward the edges.
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x2A000000)),
            center = Offset(w / 2f, h / 2f),
            radius = maxOf(w, h) * 0.72f
        ),
        size = Size(w, h)
    )

    val stripLeft = w * 0.30f
    val stripRight = w * 0.70f
    // Pitch strip: a soft vertical gradient - lighter/fresher near both ends, a touch worn and
    // darker through the middle where most deliveries land - rather than a flat tan fill.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE3CB92), Color(0xFFD9B876), Color(0xFFC8A968),
                Color(0xFFD9B876), Color(0xFFE3CB92)
            )
        ),
        topLeft = Offset(stripLeft, 0f),
        size = Size(stripRight - stripLeft, h)
    )
    // Faint centre seam for a bit of texture on the strip itself.
    drawLine(Color(0x1A3E2723), Offset(w / 2f, h * 0.08f), Offset(w / 2f, h * 0.92f), strokeWidth = 1.5f)

    drawLine(Color.White, Offset(stripLeft, h * 0.06f), Offset(stripRight, h * 0.06f), strokeWidth = 3f)
    drawLine(Color.White, Offset(stripLeft, h * 0.94f), Offset(stripRight, h * 0.94f), strokeWidth = 3f)
}

/** A soft grounding shadow beneath a figure's feet - a cheap depth cue shared by both figures. */
private fun DrawScope.drawFootShadow(centerX: Float, feetY: Float, scale: Float) {
    drawOval(
        Color(0x33000000),
        topLeft = Offset(centerX - 16f * scale, feetY - 3f * scale),
        size = Size(32f * scale, 8f * scale)
    )
}

/** internal (not private) so BowlingControls' larger single-pitch view can reuse these at a bigger scale. */
internal fun DrawScope.drawStumps(centerX: Float, baseY: Float, scale: Float = 1f) {
    val height = 34f * scale
    val spacing = 10f * scale
    val strokeW = 4f * scale
    // A light-to-dark gradient across the three stumps gives them a rounder, more turned-wood
    // feel than a flat brown fill.
    val woodBrush = Brush.horizontalGradient(listOf(Color(0xFF6D4C37), Color(0xFF3E2723)))
    listOf(-spacing, 0f, spacing).forEach { dx ->
        drawLine(
            woodBrush,
            Offset(centerX + dx, baseY),
            Offset(centerX + dx, baseY - height),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
    }
    drawLine(
        Color(0xFF3E2723),
        Offset(centerX - spacing - 3f * scale, baseY - height),
        Offset(centerX + spacing + 3f * scale, baseY - height),
        strokeWidth = strokeW,
        cap = StrokeCap.Round
    )
}

/**
 * The stumps mid-break, for a bowled/lbw miss (see [stumpsBreakT]): a simple scatter rather than
 * a physically simulated one - the bail flings up and outward, and each of the three stumps tips
 * over and slides away from its base by a different amount, all driven off one [breakT] (0f..1f,
 * 0 = still upright, 1 = fully scattered/fallen) so it reads as a quick, deliberate "wicket down"
 * beat rather than a re-implementation of [drawStumps] with jitter.
 */
internal fun DrawScope.drawBrokenStumps(centerX: Float, baseY: Float, scale: Float, breakT: Float) {
    val height = 34f * scale
    val spacing = 10f * scale
    val strokeW = 4f * scale
    val woodBrush = Brush.horizontalGradient(listOf(Color(0xFF6D4C37), Color(0xFF3E2723)))
    val t = breakT.coerceIn(0f, 1f)

    // The bail (top crossbar) is knocked clean off - flung sideways and up before it'd fall out
    // of frame, rather than following it all the way down.
    val bailDx = 44f * scale * t
    val bailDy = -22f * scale * sin((t * PI / 1.4f).toFloat()).coerceAtLeast(0f)
    drawLine(
        Color(0xFF3E2723),
        Offset(centerX - spacing - 3f * scale + bailDx, baseY - height + bailDy),
        Offset(centerX + spacing + 3f * scale + bailDx, baseY - height + bailDy),
        strokeWidth = strokeW,
        cap = StrokeCap.Round
    )

    // Each stump tips over about its own base by a different amount/direction, rather than all
    // three falling identically, so the scatter reads as pieces separating rather than one rigid
    // unit toppling.
    val stumps = listOf(-spacing to -1f, 0f to 0.5f, spacing to 1f) // (base dx, fall bias)
    stumps.forEach { (dx, bias) ->
        val base = Offset(centerX + dx, baseY)
        val fallAngle = (PI.toFloat() / 2.3f) * bias * t
        val slide = 20f * scale * bias * t
        val tip = Offset(
            base.x + slide + sin(fallAngle) * height,
            base.y - cos(fallAngle) * height
        )
        drawLine(woodBrush, base, tip, strokeWidth = strokeW, cap = StrokeCap.Round)
    }
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
    // The bowler sits at the BOTTOM of the pitch, so running in toward release means moving UP
    // the canvas (toward the batter's end) - feet's y decreases as approach nears 1.
    val feet = feetY + (1f - approach) * 18f * scale
    val bodyTop = feet - 58f * scale
    val bodyBottom = feet - 14f * scale
    val headCenter = Offset(centerX, bodyTop - 12f * scale)
    val headRadius = 12f * scale

    drawFootShadow(centerX, feet, scale)

    // Fielding side's shirt/cap - reuses the ball/wicket-flash red already in the palette rather
    // than introducing a new color, distinguishing it from the batter's blue. A gradient rather
    // than a flat fill gives the torso a bit of shading/sheen.
    val shirt = Color(0xFFB71C1C)
    val shirtOutline = Color(0xFF7F0000)
    val shirtBrush = Brush.verticalGradient(listOf(Color(0xFFE53935), shirt, shirtOutline))

    drawCircle(shirt, radius = headRadius, center = headCenter)
    drawCircle(shirtOutline, radius = headRadius, center = headCenter, style = Stroke(width = 2f * scale))
    // Cap brim - a small kit detail giving the head more silhouette than a bare circle.
    drawRoundRect(
        Color.White,
        topLeft = Offset(headCenter.x, headCenter.y - 3f * scale),
        size = Size(13f * scale, 4f * scale),
        cornerRadius = CornerRadius(2f * scale, 2f * scale)
    )
    drawLine(shirtBrush, Offset(centerX, bodyTop), Offset(centerX, bodyBottom), strokeWidth = 14f * scale, cap = StrokeCap.Round)

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

/** Wood-tone gradient shared by the resting-stance bat and the in-swing bat (see [drawBatSwoosh]). */
private val BatBrush = Brush.linearGradient(listOf(Color(0xFFA1887F), Color(0xFF6D4C41)))

/**
 * The batter figure. With [shot] null, this is the resting stance shown while waiting for the
 * next delivery. With [shot] set (briefly, right after a ball is resolved), it instead shows a
 * pose roughly matching the aggression/timing that was played. The ball's own post-outcome flight
 * (see [ballOffsetFor]) - not this figure - carries the actual trajectory now.
 * [swooshProgress]/[dustProgress] (each 0f..1f, see [rememberShotImpactProgress]) drive the
 * bat-swing trail and contact dust puff.
 */
internal fun DrawScope.drawBatterFigure(
    centerX: Float,
    feetY: Float,
    scale: Float = 1f,
    shot: BatterShot? = null,
    swooshProgress: Float = 1f,
    dustProgress: Float = 0f
) {
    val bodyTop = feetY - 58f * scale
    val bodyBottom = feetY - 14f * scale
    val headCenter = Offset(centerX, bodyTop - 12f * scale)
    val headRadius = 12f * scale

    drawFootShadow(centerX, feetY, scale)

    // A helmet (rather than a skin-tone head) reads clearly against both the green outfield
    // and the tan pitch regardless of exact shade - a plain skin tone nearly disappeared
    // against the pitch color here. The outline stroke guarantees contrast either way.
    val helmet = Color(0xFF1565C0)
    val helmetBrush = Brush.verticalGradient(listOf(Color(0xFF42A5F5), helmet, Color(0xFF0D3C73)))
    drawCircle(helmet, radius = headRadius, center = headCenter)
    drawCircle(Color(0xFF0D3C73), radius = headRadius, center = headCenter, style = Stroke(width = 2f * scale))
    // Grille - a few short parallel lines across the front, a bit more kit detail than the
    // single mouth-guard line this used to be.
    for (i in -1..1) {
        drawLine(
            Color.White,
            Offset(headCenter.x - 6f * scale, headCenter.y + 2f * scale + i * 3f * scale),
            Offset(headCenter.x + 6f * scale, headCenter.y + 2f * scale + i * 3f * scale),
            strokeWidth = 1.2f * scale
        )
    }

    drawLine(helmetBrush, Offset(centerX, bodyTop), Offset(centerX, bodyBottom), strokeWidth = 14f * scale, cap = StrokeCap.Round)

    // Pads - chunkier rounded leg guards with a thin trim stripe, standing in for the plain thin
    // leg lines this used to be.
    val padColor = Color(0xFFF5F5F5)
    listOf(-10f, 10f).forEach { dx ->
        val padTopLeft = Offset(centerX + dx * scale - 4.5f * scale, bodyBottom)
        val padSize = Size(9f * scale, feetY - bodyBottom)
        drawRoundRect(padColor, topLeft = padTopLeft, size = padSize, cornerRadius = CornerRadius(4f * scale, 4f * scale))
        drawLine(
            helmet.copy(alpha = 0.7f),
            Offset(padTopLeft.x, padTopLeft.y + 4f * scale),
            Offset(padTopLeft.x + padSize.width, padTopLeft.y + 4f * scale),
            strokeWidth = 1.2f * scale
        )
    }

    if (shot == null) {
        // resting stance - bat grounded just in front, waiting for the next ball
        drawLine(
            BatBrush,
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
    drawBatSwoosh(batStart, batEnd, swooshProgress, shot, strokeWidth = 6f * scale)
    if (!missed) drawDustPuff(batStart, dustProgress, scale)
    // The ball itself now animates the actual post-contact/post-miss path (see
    // ballOffsetFor/contactBallOffset/missedBallOffset, drawn by the caller alongside the
    // pre-contact ball) instead of this figure drawing a separate static directional stub for it.
}

/** How pronounced the bat-swoosh trail is: longer, more numerous and brighter for an aggressive
 *  aerial shot, shortest and dimmest for a defensive block or a missed (RED-zone) swing. */
private data class SwooshSpec(val trailCount: Int, val trailSpacing: Float, val baseAlpha: Float, val color: Color)

private fun swooshSpecFor(shot: BatterShot): SwooshSpec = when {
    shot.timingQuality == TimingQuality.RED -> SwooshSpec(2, 0.16f, 0.20f, Color(0xFFBCAAA4))
    shot.aggression == Aggression.DEFENSIVE -> SwooshSpec(2, 0.14f, 0.26f, Color(0xFFECEFF1))
    shot.aggression == Aggression.GROUND -> SwooshSpec(4, 0.12f, 0.40f, Color(0xFFFFE082))
    else -> SwooshSpec(6, 0.10f, 0.55f, Color(0xFFFFF59D)) // AERIAL - longest, brightest trail
}

/**
 * A brief arc/trail following the bat as it swings from [batStart] toward [batEnd], keyed to
 * [progress] (0f..1f, see [rememberShotImpactProgress]) so the bat visibly sweeps into place
 * over a couple hundred milliseconds rather than appearing instantly, leaving a fading trail of
 * earlier positions behind it. Intensity (trail length/brightness) comes from [swooshSpecFor],
 * scaled by [shot]'s aggression.
 */
internal fun DrawScope.drawBatSwoosh(batStart: Offset, batEnd: Offset, progress: Float, shot: BatterShot, strokeWidth: Float) {
    val spec = swooshSpecFor(shot)
    val head = progress.coerceIn(0f, 1f)
    for (i in spec.trailCount downTo 1) {
        val t = (head - i * spec.trailSpacing).coerceIn(0f, 1f)
        if (t <= 0f) continue
        val alpha = spec.baseAlpha * (1f - i.toFloat() / (spec.trailCount + 1))
        if (alpha <= 0.02f) continue
        drawLine(
            spec.color.copy(alpha = alpha),
            batStart,
            lerp(batStart, batEnd, t),
            strokeWidth = strokeWidth * 0.65f,
            cap = StrokeCap.Round
        )
    }
    drawLine(BatBrush, batStart, lerp(batStart, batEnd, head), strokeWidth = strokeWidth, cap = StrokeCap.Round)
}

/**
 * A brief particle burst at the point of bat-ball contact, keyed to [progress] (0f..1f, see
 * [rememberShotImpactProgress]) - a handful of dust-colored specks radiating outward and fading
 * as the ball is struck away. Skipped entirely once the burst completes (see caller: only drawn
 * on non-miss contact in the first place).
 */
internal fun DrawScope.drawDustPuff(center: Offset, progress: Float, scale: Float) {
    val t = progress.coerceIn(0f, 1f)
    if (t <= 0f || t >= 1f) return
    val particleCount = 6
    val maxRadius = 30f * scale
    val alpha = (1f - t) * 0.65f
    if (alpha <= 0.02f) return
    for (i in 0 until particleCount) {
        val angle = (i / particleCount.toFloat()) * (2f * PI).toFloat() + 0.4f
        val dist = maxRadius * t
        val p = Offset(center.x + cos(angle) * dist, center.y - sin(angle) * dist * 0.55f)
        val r = (4.5f - 3f * t) * scale
        drawCircle(Color(0xFFD9C9A3).copy(alpha = alpha), radius = r.coerceAtLeast(1f), center = p)
    }
}
