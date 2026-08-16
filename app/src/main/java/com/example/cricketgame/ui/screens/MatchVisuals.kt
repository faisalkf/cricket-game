package com.example.cricketgame.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Path
import com.example.cricketgame.data.Aggression
import com.example.cricketgame.data.BallOutcome
import com.example.cricketgame.data.BattingTimingZones
import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.data.ShotSide
import com.example.cricketgame.data.TimingQuality
import com.example.cricketgame.data.sideFromDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
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
 * The one shared pitch view for BOTH the batting and bowling screens (no more separate
 * per-screen pitch composables - BowlingControls used to own its own near-identical copy; now it
 * just contributes the floating slider overlay, and this is the single full-screen backdrop
 * either way, so there's exactly one place drawing the bowler/batter/keeper/ball to keep in sync).
 * Fills the whole screen - the caller layers the scoreboard/timing HUD and the slider control as
 * floating overlays on top rather than this pushing them down.
 *
 * [creaseProgress] is [BowlingTimingZones.LATE_RED_START] when the PLAYER is bowling (ties the
 * bowler's run-up/crease visual to the exact same no-ball threshold - see [drawBowlerFigure]) or
 * its 1f default otherwise (the CPU bowler on the batting screen, whose progress follows the
 * unrelated batting sweep and has no no-ball concept to sync to).
 */
@Composable
fun PitchBackdrop(
    progress: Float,
    pitchLength: PitchLength,
    postPitchTilt: Float,
    showBall: Boolean,
    shot: BatterShot? = null,
    outcome: BallOutcome? = null,
    ballSeq: Int = 0,
    creaseProgress: Float = 1f,
    modifier: Modifier = Modifier
) {
    val impact = rememberShotImpactProgress(ballSeq, shot)
    val postOutcome = rememberPostOutcomeProgress(ballSeq, shot, progress)
    val keeperState = keeperStateFor(shot, outcome)
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val batterCenterX = w / 2f - 100f
        val batterFeetY = h * 0.10f
        val stripLeft = w * 0.30f
        val stripRight = w * 0.70f
        val creaseY = h * 0.88f
        val runUpStartY = h * 0.99f

        drawPitchBackground(w, h)
        drawCrease(stripLeft, stripRight, creaseY)

        val breakT = stumpsBreakT(shot, postOutcome)
        if (breakT != null) {
            drawBrokenStumps(centerX = w / 2f, baseY = h * 0.06f, scale = 1.6f, breakT = breakT)
        } else {
            drawStumps(centerX = w / 2f, baseY = h * 0.06f, scale = 1.6f)
        }
        drawStumps(centerX = w / 2f, baseY = h * 0.94f, scale = 1.6f)
        drawWicketkeeperFigure(centerX = batterCenterX, baseY = h * 0.045f, scale = 1.1f, state = keeperState, stateProgress = postOutcome)

        // Bowler at the bottom, batter at the top - the ball always travels bottom -> top.
        drawBowlerFigure(
            centerX = w / 2f + 90f, creaseY = creaseY, runUpStartY = runUpStartY,
            progress = progress, scale = 1.6f, creaseProgress = creaseProgress
        )
        drawBatterFigure(
            centerX = batterCenterX, feetY = batterFeetY, scale = 1.6f, shot = shot,
            swooshProgress = impact.swoosh, dustProgress = impact.dust
        )

        if (showBall) {
            drawTravellingBall(w, h, progress, pitchLength, postPitchTilt, shot, postOutcome, batterCenterX, batterFeetY)
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

/** How high (px) the ball floats above its ground/shadow position ([ballTravelX]/[ballTravelY]) at
 *  a point in its pre-contact bowler->batsman flight - PART 2's bounce arc: it falls from the
 *  bowler's overhead release down to the pitch (height 0 exactly at [bouncePointProgress]'s bounce
 *  point, eased so the fall visibly accelerates rather than being linear), then rises again toward
 *  the batsman, leveling out at a lower height than the release (contact happens around bat height,
 *  not overhead) - a genuine dip-then-rise parabolic arc rather than the flat/straight travel path
 *  this used to be. Purely a drawn vertical offset for where the ball SPRITE is painted - the
 *  ground/shadow position from ballTravelX/ballTravelY (and everything downstream of it, like the
 *  post-pitch curve) is unaffected; see PitchBackdrop/BowlingAimPitch for how the two combine. */
internal fun ballHeightFor(progress: Float, pitchLength: PitchLength): Float {
    val bounce = bouncePointProgress(pitchLength)
    val p = progress.coerceIn(0f, 1f)
    val releaseHeight = 42f
    val contactHeight = 22f
    return if (p <= bounce) {
        val t = if (bounce <= 0f) 1f else p / bounce
        releaseHeight * (1f - t * t) // eased fall - slow at first, accelerating into the bounce
    } else {
        val t = (p - bounce) / (1f - bounce)
        val riseEase = 1f - (1f - t) * (1f - t) // eased rise - quick off the bounce, leveling out
        contactHeight * riseEase
    }
}

/** Whether the ball is still on its pre-contact bowler->batsman flight (as opposed to its
 *  post-outcome miss/contact trajectory) - shared by the pitch views to decide whether to draw the
 *  bounce-arc height offset/shadow (pre-contact only; post-outcome trajectories have their own
 *  established visuals, e.g. the aerial shot's own mid-flight radius bulge). Mirrors the same
 *  condition [ballOffsetFor] uses internally to pick which position function to call. */
internal fun isPreContactBall(shot: BatterShot?, postOutcomeT: Float): Boolean = shot == null || postOutcomeT <= 0f

/**
 * Draws the travelling ball itself, shared by PitchBackdrop and BowlingAimPitch so both pitch
 * views draw it identically: while still on its pre-contact flight ([isPreContactBall]), a ground
 * shadow at its actual travel position plus the ball sprite offset upward by the bounce-arc height
 * (see [ballHeightFor]) - the shadow stays anchored to the flight path so the gap between it and
 * the ball is what actually reads as "height" (a standard cheap pseudo-3D trick, not a real
 * z-axis). Past that (post-outcome), just the ball at its own position, undecorated - those
 * trajectories already have their own established visual language (e.g. the aerial shot's radius
 * bulge in [ballRadiusFor]) and don't need a ground shadow.
 */
internal fun DrawScope.drawTravellingBall(
    w: Float,
    h: Float,
    progress: Float,
    pitchLength: PitchLength,
    postPitchTilt: Float,
    shot: BatterShot?,
    postOutcomeT: Float,
    batterCenterX: Float,
    batterFeetY: Float
) {
    val groundPos = ballOffsetFor(w, h, progress, pitchLength, postPitchTilt, shot, postOutcomeT, batterCenterX, batterFeetY)
    val radius = ballRadiusFor(shot, postOutcomeT)
    val drawPos = if (isPreContactBall(shot, postOutcomeT)) {
        val height = ballHeightFor(progress, pitchLength)
        drawOval(
            Color(0x33000000),
            topLeft = Offset(groundPos.x - radius * 0.9f, groundPos.y - radius * 0.32f),
            size = Size(radius * 1.8f, radius * 0.64f)
        )
        Offset(groundPos.x, groundPos.y - height)
    } else {
        groundPos
    }
    drawCircle(Color(0xFFB71C1C), radius = radius, center = drawPos)
    drawCircle(Color.White, radius = radius, center = drawPos, style = Stroke(width = 2f))
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
internal fun lerpFloat(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

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
internal fun DrawScope.drawFootShadow(centerX: Float, feetY: Float, scale: Float) {
    drawOval(
        Color(0x33000000),
        topLeft = Offset(centerX - 16f * scale, feetY - 3f * scale),
        size = Size(32f * scale, 8f * scale)
    )
}

// --- PART 1: shared limb/torso primitives, for a more human silhouette than a flat single-line
// stick figure - a small joint-bend and a tapered torso read as an actual body far more than
// uniform-width strokes do, without needing a real 3D/skeletal rig. Shared by both the bowler and
// batter figures below so the upgrade is consistent across both screens.

/** A two-segment limb (e.g. upper arm + forearm, or thigh + shin) bent at [joint] - a single
 *  straight line reads as a rigid stick limb; two shorter segments either side of a small joint
 *  circle reads as an actual elbow/knee. */
internal fun DrawScope.drawJointedLimb(from: Offset, joint: Offset, to: Offset, brush: Brush, strokeWidth: Float) {
    drawLine(brush, from, joint, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawLine(brush, joint, to, strokeWidth = strokeWidth * 0.82f, cap = StrokeCap.Round)
    drawCircle(brush, radius = strokeWidth * 0.42f, center = joint)
}

/** A leg bent at the knee toward [foot] from [hip] - the knee sits partway down and biased toward
 *  the foot's x (rather than straight below the hip), for a bent running/stance silhouette. */
internal fun DrawScope.drawBentLeg(hip: Offset, foot: Offset, brush: Brush, strokeWidth: Float) {
    val knee = Offset(hip.x + (foot.x - hip.x) * 0.55f, hip.y + (foot.y - hip.y) * 0.55f)
    drawJointedLimb(hip, knee, foot, brush, strokeWidth)
}

/** An arm bent at the elbow toward [hand] from [shoulder] - see [drawBentLeg]. */
internal fun DrawScope.drawBentArm(shoulder: Offset, hand: Offset, brush: Brush, strokeWidth: Float) {
    val elbow = Offset(shoulder.x + (hand.x - shoulder.x) * 0.55f, shoulder.y + (hand.y - shoulder.y) * 0.4f)
    drawJointedLimb(shoulder, elbow, hand, brush, strokeWidth)
}

/** The torso as a tapered polygon (wider at the shoulders, narrower at the waist) rather than a
 *  single flat-width stroke, plus a faint shaded stripe down one side for a bit of rounded volume
 *  instead of a flat cutout - both purely Canvas-primitive depth/shape cues, no bitmaps. */
internal fun DrawScope.drawTorso(centerX: Float, top: Float, bottom: Float, shoulderWidth: Float, waistWidth: Float, brush: Brush) {
    val path = Path().apply {
        moveTo(centerX - shoulderWidth / 2f, top)
        lineTo(centerX + shoulderWidth / 2f, top)
        lineTo(centerX + waistWidth / 2f, bottom)
        lineTo(centerX - waistWidth / 2f, bottom)
        close()
    }
    drawPath(path, brush)
    drawLine(
        Color.Black.copy(alpha = 0.14f),
        Offset(centerX - waistWidth * 0.22f, top + (bottom - top) * 0.1f),
        Offset(centerX - waistWidth * 0.22f, bottom),
        strokeWidth = waistWidth * 0.22f,
        cap = StrokeCap.Round
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
 * The bowling (front-foot) crease - a clean dashed line the whole width of the pitch strip, plus a
 * short return-crease tick at each end, distinct from the plain solid boundary lines
 * drawPitchBackground already draws at the very top/bottom of the strip so it doesn't read as just
 * another one of those. This is the line drawBowlerFigure's run-up animates toward - see its doc
 * for how the two stay in sync via a shared progress value rather than a second timing system.
 */
internal fun DrawScope.drawCrease(stripLeft: Float, stripRight: Float, y: Float) {
    val dashLength = 10f
    val gapLength = 6f
    var x = stripLeft
    while (x < stripRight) {
        val segEnd = (x + dashLength).coerceAtMost(stripRight)
        drawLine(Color.White, Offset(x, y), Offset(segEnd, y), strokeWidth = 3f)
        x += dashLength + gapLength
    }
    listOf(stripLeft, stripRight).forEach { endX ->
        drawLine(Color.White, Offset(endX, y - 8f), Offset(endX, y + 8f), strokeWidth = 3f)
    }
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
 * The fielding side's bowler at the bowling end, now a real run-up rather than a near-static pose
 * cycle: at progress 0f they're all the way back at [runUpStartY] and run FORWARD (decreasing y,
 * toward the batter) as progress advances, reaching the front-foot crease at [creaseY] exactly
 * when progress hits [creaseProgress] - crucially, this is the SAME progress value that already
 * drives the RED/YELLOW/GREEN/LATE_RED timing sweep, not a second/parallel clock, so "have they
 * reached the crease yet" and "is this release a no-ball yet" can never disagree. On the bowling
 * screen the caller passes creaseProgress = BowlingTimingZones.LATE_RED_START (0.70) - the
 * existing no-ball threshold - so releasing before the bowler visually reaches the line is a legal
 * delivery and releasing after (into LATE_RED) means the bowler has already visibly overstepped it
 * (progress beyond creaseProgress keeps easing them a short distance further past [creaseY], a
 * visible "foot fault"). Elsewhere (the CPU bowler on the batting screen, whose progress follows
 * the unrelated batting sweep and has no no-ball concept at all) the caller just leaves
 * creaseProgress at its 1f default - the bowler still runs the full distance and arrives at the
 * crease right as they release, with nothing to overstep.
 *
 * Poses scale with the SAME creaseProgress rather than fixed 0.8f/0.93f cutoffs, so a well-timed
 * bowling-screen release (GREEN ends at 0.70, i.e. AT creaseProgress) actually shows the arm
 * coming over near the crease instead of the release pose only ever appearing deep in no-ball
 * territory: a 3-frame running cycle (wide stride left, feet-together mid-stride, wide stride
 * right) that cycles increasingly fast as they near the gather/release phase, then a distinct
 * "gather" beat (planting, bowling arm drawn back to cock it) before a clear "release" beat (arm
 * fully extended overhead). Reused for both the CPU bowler (batting screens) and the player's own
 * bowler (bowling screen) - same function, same poses, either way. Limbs are jointed (see
 * [drawBentLeg]/[drawBentArm]) and the torso a tapered polygon (see [drawTorso]) for a more human
 * silhouette; [depthScale] also very subtly grows the whole figure as they near the crease, a
 * cheap perspective cue suggesting they're physically closing the distance, not just sliding.
 */
internal fun DrawScope.drawBowlerFigure(
    centerX: Float,
    creaseY: Float,
    runUpStartY: Float,
    progress: Float,
    scale: Float = 1f,
    creaseProgress: Float = 1f
) {
    val approach = progress.coerceIn(0f, 1f)
    val runUpDistance = runUpStartY - creaseY
    // A short overstep distance past the crease, only ever reached if approach runs past
    // creaseProgress (the bowling screen's no-ball zone) - proportional to the run-up's own
    // distance so it stays sensible at any screen size, capped so a tiny run-up can't produce a
    // silly-looking overstep.
    val oversteppedY = creaseY - (runUpDistance * 0.25f).coerceAtMost(24f)
    val feetProgress = (approach / creaseProgress).coerceIn(0f, 1f)
    val feet = if (approach <= creaseProgress) {
        lerpFloat(runUpStartY, creaseY, feetProgress)
    } else {
        val overstepT = ((approach - creaseProgress) / (1f - creaseProgress)).coerceIn(0f, 1f)
        lerpFloat(creaseY, oversteppedY, overstepT)
    }
    val depthScale = scale * (0.94f + 0.06f * feetProgress)
    val bodyTop = feet - 58f * depthScale
    val bodyBottom = feet - 14f * depthScale
    val headCenter = Offset(centerX, bodyTop - 12f * depthScale)
    val headRadius = 12f * depthScale

    drawFootShadow(centerX, feet, depthScale)

    // Fielding side's shirt/trouser palette - reuses the ball/wicket-flash red already in the
    // palette rather than introducing a new color, distinguishing it from the batter's blue.
    // Gradients rather than flat fills give both a bit of shading/sheen.
    val shirt = Color(0xFFB71C1C)
    val shirtOutline = Color(0xFF7F0000)
    val shirtBrush = Brush.verticalGradient(listOf(Color(0xFFE53935), shirt, shirtOutline))
    val trouserBrush = Brush.linearGradient(listOf(Color.White, Color(0xFFCFCFCF)))

    drawCircle(shirt, radius = headRadius, center = headCenter)
    drawCircle(shirtOutline, radius = headRadius, center = headCenter, style = Stroke(width = 2f * depthScale))
    // Cap brim - a small kit detail giving the head more silhouette than a bare circle.
    drawRoundRect(
        Color.White,
        topLeft = Offset(headCenter.x, headCenter.y - 3f * depthScale),
        size = Size(13f * depthScale, 4f * depthScale),
        cornerRadius = CornerRadius(2f * depthScale, 2f * depthScale)
    )
    drawTorso(centerX, bodyTop, bodyBottom, shoulderWidth = 20f * depthScale, waistWidth = 13f * depthScale, brush = shirtBrush)

    val armStroke = 7f * depthScale
    val legStroke = 8f * depthScale
    val hip = Offset(centerX, bodyBottom)
    val shoulder = Offset(centerX, bodyTop + 6f * depthScale)

    when {
        approach >= creaseProgress * 0.93f -> {
            // release: front leg planted well forward, back leg trailing, bowling arm fully
            // extended overhead through an elbow joint - the clear release beat. Scaled off
            // creaseProgress (not a fixed 0.93f) so a well-timed bowling-screen release - GREEN
            // ends right at creaseProgress - actually reaches this pose, instead of it only ever
            // appearing deep in no-ball territory.
            drawBentLeg(hip, Offset(centerX + 18f * depthScale, feet), trouserBrush, legStroke)
            drawBentLeg(hip, Offset(centerX - 12f * depthScale, feet - 6f * depthScale), trouserBrush, legStroke)
            drawBentArm(shoulder, Offset(centerX + 10f * depthScale, bodyTop - 26f * depthScale), shirtBrush, armStroke)
        }
        approach >= creaseProgress * 0.8f -> {
            // gather: front leg starting to plant, bowling arm drawn back and down to cock it -
            // a distinct beat between running and the release, rather than jumping straight there.
            drawBentLeg(hip, Offset(centerX + 10f * depthScale, feet), trouserBrush, legStroke)
            drawBentLeg(hip, Offset(centerX - 8f * depthScale, feet - 4f * depthScale), trouserBrush, legStroke)
            drawBentArm(shoulder, Offset(centerX - 14f * depthScale, bodyBottom + 6f * depthScale), shirtBrush, armStroke)
        }
        else -> {
            val cycleIndex = ((feetProgress.pow(1.6f)) * 18f).toInt() % 3
            val frontDx = when (cycleIndex) { 0 -> 14f; 2 -> -14f; else -> 0f }
            // Feet lift slightly together at the mid-stride frame (cycleIndex 1) to read as an
            // airborne moment between footfalls, rather than three grounded poses in a row.
            val airborne = cycleIndex == 1
            val liftY = if (airborne) 5f * depthScale else 0f
            drawBentLeg(hip, Offset(centerX + frontDx * depthScale, feet - liftY), trouserBrush, legStroke)
            drawBentLeg(hip, Offset(centerX - frontDx * depthScale, feet - liftY), trouserBrush, legStroke)
            drawBentArm(shoulder, Offset(centerX - frontDx * 0.6f * depthScale, bodyTop - 4f * depthScale), shirtBrush, armStroke)
        }
    }
}

/** Wood-tone gradient shared by the resting-stance bat and the in-swing bat (see [drawBatSwoosh]). */
private val BatBrush = Brush.linearGradient(listOf(Color(0xFFA1887F), Color(0xFF6D4C41)))

/**
 * The batter figure. With [shot] null, this is the resting stance shown while waiting for the
 * next delivery. With [shot] set (briefly, right after a ball is resolved), it instead shows a
 * pose roughly matching the aggression/timing/side that was played (see the batEnd table below -
 * PART 3's on/off-side shapes, layered onto the existing aggression-tier shapes). The ball's own
 * post-outcome flight (see [ballOffsetFor]) - not this figure - carries the actual trajectory now.
 * [swooshProgress]/[dustProgress] (each 0f..1f, see [rememberShotImpactProgress]) drive the
 * bat-swing trail and contact dust puff. Torso is a tapered polygon (see [drawTorso], PART 1) and
 * the bat arm a jointed shoulder->elbow->grip chain (see [drawBentArm]) rather than a floating bat
 * line disconnected from the body; pads stay the existing rounded-rect shape (already a
 * deliberately stylized, not stick-line, leg guard - there's little to gain jointing a leg that's
 * mostly hidden under padding anyway).
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
    val shoulder = Offset(centerX, bodyTop + 6f * scale)

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

    drawTorso(centerX, bodyTop, bodyBottom, shoulderWidth = 18f * scale, waistWidth = 12f * scale, brush = helmetBrush)

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
        val grip = Offset(centerX + 10f * scale, bodyBottom - 6f * scale)
        drawBentArm(shoulder, grip, helmetBrush, 6f * scale)
        drawLine(BatBrush, grip, Offset(centerX + 22f * scale, feetY - 2f * scale), strokeWidth = 6f * scale, cap = StrokeCap.Round)
        return
    }

    val missed = shot.timingQuality == TimingQuality.RED
    // PART 3: on/off-side categorization - same left/right split as everywhere else that reads a
    // slider release direction (ballTravelX, contactBallOffset, MatchViewModel.lineFromDirection):
    // negative = ON_SIDE (leg side), non-negative = OFF_SIDE. sideSign mirrors the swing's X
    // offsets so the bat visibly swings toward the correct side, and the GROUND/AERIAL shapes
    // below further differ in target height/reach by side (not just mirrored X) so an on-side shot
    // reads as a distinct, flatter cross-batted swing (pull/flick/slog) rather than just the
    // off-side drive shape flipped left-right.
    val side = sideFromDirection(shot.direction)
    val sideSign = if (side == ShotSide.ON_SIDE) -1f else 1f
    val batStart = Offset(centerX + 8f * scale, bodyBottom - 4f * scale)
    val batEnd = when {
        missed -> Offset(centerX - 22f * scale, feetY + 6f * scale) // bat trails behind - beaten by the ball, side-agnostic
        shot.aggression == Aggression.DEFENSIVE -> Offset(centerX + 6f * scale * sideSign, feetY) // checked, straight and close - a small side nudge only
        shot.aggression == Aggression.GROUND -> {
            val reach = if (shot.timingQuality == TimingQuality.GREEN) 36f else 24f
            if (side == ShotSide.OFF_SIDE) {
                Offset(centerX + reach * scale, feetY - 8f * scale) // driven out in front - a vertical-bat drive shape
            } else {
                Offset(centerX - reach * scale, bodyBottom + 6f * scale) // swung across the body, flatter/higher - a cross-batted pull/flick shape
            }
        }
        else -> { // AERIAL
            val reach = if (shot.timingQuality == TimingQuality.GREEN) 30f else 20f
            if (side == ShotSide.OFF_SIDE) {
                Offset(centerX + reach * scale, bodyTop - reach * scale) // high and vertical - a lofted drive shape
            } else {
                Offset(centerX - reach * scale, bodyTop - reach * 0.6f * scale) // flatter and more horizontal - a slog/pull shape
            }
        }
    }
    drawBentArm(shoulder, batStart, helmetBrush, 6f * scale)
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
