package com.example.cricketgame.ui.scene3d

import com.example.cricketgame.data.Aggression
import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.data.TimingQuality
import com.example.cricketgame.ui.screens.BatterShot
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure math for the 3D pitch's world-space layout - deliberately kept free of any
 * SceneView/Filament/Compose import, so the coordinate mapping (the part most likely to have a
 * silent off-by-factor-of-two or wrong-axis bug, and the hardest kind of 3D bug to catch from a
 * code read alone per the migration brief) can be exercised by plain JUnit tests without a device
 * or emulator - see app/src/test's PitchGeometry3DTest.
 *
 * Coordinate system (meters, matching SceneView's convention): +X = off side, -X = leg/on side
 * (same sign convention the existing 2D code already uses for shot direction/postPitchTilt -
 * see MatchVisuals' ballTravelX); +Y = up; +Z = toward the bowler's end, 0 = the batter's popping
 * crease. The ball always travels from high Z (bowler) to Z = 0 (batter), same "bowler's end vs
 * batter's end" relationship the 2D canvas drew bottom-to-top.
 */
object PitchGeometry3D {
    /** A cricket pitch is 22 yards - 20.12m - popping crease to popping crease. */
    const val PITCH_LENGTH_M = 20.12f

    /** How far up-field of the bowling crease the bowler's run-up starts. Not to real-world scale
     *  (a real fast bowler's run-up is 15-20m) - kept shorter so the whole run-up reads clearly in
     *  frame from the bowling-screen camera rather than starting near-offscreen. */
    const val RUN_UP_DISTANCE_M = 7f

    /** A pitch strip is 3.05m (10ft) wide. */
    const val PITCH_WIDTH_M = 3.05f

    /** Real stumps are 0.71m tall. */
    const val STUMPS_HEIGHT_M = 0.71f

    /** Real cricket ball radius is ~3.6cm - nudged up slightly so it stays visible at pitch-shot
     *  camera distances rather than vanishing to a sub-pixel dot. */
    const val BALL_RADIUS_M = 0.055f

    /** Z of the bowling (front-foot) crease - where the run-up ends and the batter's-end popping
     *  crease (Z = 0) is 20.12m away. */
    const val BOWLING_CREASE_Z = PITCH_LENGTH_M

    /** Z where the bowler's run-up animation starts, further up-field than the crease. */
    const val RUN_UP_START_Z = PITCH_LENGTH_M + RUN_UP_DISTANCE_M

    /** How far past the bowling crease an overstepping (no-ball) run-up is allowed to carry, for
     *  the same reason [com.example.cricketgame.ui.screens.drawBowlerFigure] caps its 2D
     *  equivalent - proportional to the run-up's own length so it stays a sensible-looking foot
     *  fault at any distance, capped so a short run-up can't produce a silly overstep. */
    private const val MAX_OVERSTEP_M = 1.2f

    /**
     * The bowler's feet Z position for a given run-up [progress] (0f..1f) - the SAME progress
     * value driving the RED/YELLOW/GREEN/LATE_RED timing sweep, not a second clock (mirrors
     * drawBowlerFigure's 2D version exactly, just in world-space meters instead of canvas
     * pixels). At progress 0 they're at [RUN_UP_START_Z]; they reach [BOWLING_CREASE_Z] exactly
     * when progress hits [creaseProgress] (the no-ball threshold on the bowling screen, or 1f
     * elsewhere - see PitchGeometry3D's caller); progress beyond that eases them a short distance
     * further past the crease - the visual of a no-ball foot fault.
     */
    fun bowlerFeetZ(progress: Float, creaseProgress: Float): Float {
        val approach = progress.coerceIn(0f, 1f)
        val safeCreaseProgress = creaseProgress.coerceIn(0.001f, 1f)
        return if (approach <= safeCreaseProgress) {
            val t = approach / safeCreaseProgress
            lerp(RUN_UP_START_Z, BOWLING_CREASE_Z, t)
        } else {
            val runUpDistance = RUN_UP_START_Z - BOWLING_CREASE_Z
            val oversteppedZ = BOWLING_CREASE_Z - minOf(runUpDistance * 0.18f, MAX_OVERSTEP_M)
            val overstepT = ((approach - safeCreaseProgress) / (1f - safeCreaseProgress)).coerceIn(0f, 1f)
            lerp(BOWLING_CREASE_Z, oversteppedZ, overstepT)
        }
    }

    /** How far (0f..1f) through the run-up the bowler's feet are, ignoring overstep - drives the
     *  running-stride animation cycle, same role as drawBowlerFigure's feetProgress. */
    fun bowlerStrideProgress(progress: Float, creaseProgress: Float): Float =
        (progress.coerceIn(0f, 1f) / creaseProgress.coerceIn(0.001f, 1f)).coerceIn(0f, 1f)

    /** Where along the run-up sweep (0f..1f) the ball pitches/bounces, by delivery length - a
     *  short ball bounces early (closer to the bowler/high Z), full/yorker barely bounces at all
     *  (closer to the batsman/low Z). Same mapping as MatchVisuals' bouncePointProgress. */
    fun bouncePointProgress(length: PitchLength): Float = when (length) {
        PitchLength.SHORT -> 0.32f
        PitchLength.GOOD_LENGTH -> 0.55f
        PitchLength.FULL_YORKER -> 0.80f
    }

    /** The ball's pre-contact world position for a delivery in flight, mirroring
     *  MatchVisuals' ballTravelX/ballTravelY/ballHeightFor combined into one 3D point: Z sweeps
     *  from the bowler's release point down to the batter's end as [progress] advances 0f->1f: X
     *  is dead straight until the bounce, then bends toward [postPitchTilt]'s side with an
     *  ease-in curve; Y is a bounce arc - falling from the release height to the pitch exactly at
     *  the bounce point, then rising again toward (a lower) contact height. */
    fun ballPreContactPosition(progress: Float, pitchLength: PitchLength, postPitchTilt: Float): Vec3 {
        val p = progress.coerceIn(0f, 1f)
        val bounce = bouncePointProgress(pitchLength)

        val z = lerp(RUN_UP_START_Z - RUN_UP_DISTANCE_M, 0f, p) // == lerp(BOWLING_CREASE_Z, 0f, p)

        val maxDeviationM = PITCH_WIDTH_M * 0.9f
        val x = if (p <= bounce) {
            0f
        } else {
            val t = (p - bounce) / (1f - bounce)
            val bend = t * t
            postPitchTilt.coerceIn(-1f, 1f) * maxDeviationM * bend
        }

        val releaseHeight = 2.1f
        val contactHeight = 0.75f
        val y = if (p <= bounce) {
            val t = if (bounce <= 0f) 1f else p / bounce
            releaseHeight * (1f - t * t)
        } else {
            val t = (p - bounce) / (1f - bounce)
            val riseEase = 1f - (1f - t) * (1f - t)
            contactHeight * riseEase
        }

        return Vec3(x, y, z)
    }

    /** Linear interpolation, kept local so this file has zero external dependencies (not even
     *  MatchVisuals' own lerpFloat) - see the file doc for why that isolation matters here. */
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** A small vertical bob (meters) for a mid-stride "airborne" running-figure frame, keyed the
     *  same way drawBowlerFigure's 2D cycle picks its airborne frame - shared so
     *  BowlerFigure3D doesn't reimplement the cycle-index math itself. */
    fun strideCycleIndex(strideProgress: Float): Int =
        ((strideProgress.toDouble().let { Math.pow(it, 1.6) } * 18.0).toInt()) % 3

    /** A gentle sinusoidal bob for a running stride, purely cosmetic - not tied to any gameplay
     *  value, just makes the run-up read as running rather than sliding. */
    fun strideBobM(strideProgress: Float): Float =
        0.04f * kotlin.math.abs(sin(strideProgress * 10f * PI.toFloat()))

    /**
     * The ball's position [t] (0f..1f) through a delivery's post-outcome flight, continuing on
     * from wherever it was when the outcome was decided ([arrival] - normally
     * [ballPreContactPosition] at progress 1f). Simplified from MatchVisuals' 2D version (one
     * fate per branch rather than a `MissBallFate` enum with a `SAILS_PAST` case unreachable from
     * the current engine) but the same shape: a RED-zone miss either carries through to the
     * batter-end stumps (on the stumps) or stops near the batter (off the pad/body, a dot ball);
     * a real shot flies off toward [BatterShot.direction] with a shape/distance set by
     * [BatterShot.aggression], magnitude nudged by [BatterShot.runs].
     */
    fun ballPostOutcomePosition(arrival: Vec3, shot: BatterShot, t: Float): Vec3 {
        val tc = t.coerceIn(0f, 1f)
        if (shot.timingQuality == TimingQuality.RED) {
            return if (shot.onStumps) {
                lerp3(arrival, Vec3(0f, STUMPS_HEIGHT_M * 0.5f, 0f), tc)
            } else {
                val settle = (tc / 0.35f).coerceIn(0f, 1f)
                Vec3(lerp(arrival.x, 0.35f, settle), lerp(arrival.y, 0.1f, settle), arrival.z)
            }
        }

        val dirX = shot.direction.coerceIn(-1f, 1f)
        val runFactor = when {
            shot.runs >= 6 -> 1.3f
            shot.runs >= 4 -> 1.1f
            shot.runs >= 1 -> 0.85f
            else -> 0.55f // a dot ball or a catch - short/low, still visibly a shot
        }
        return when (shot.aggression) {
            Aggression.DEFENSIVE -> {
                val end = Vec3(dirX * 1.2f * runFactor, 0.3f, arrival.z - 0.6f * runFactor)
                lerp3(arrival, end, tc)
            }
            Aggression.GROUND -> {
                val end = Vec3(dirX * 16f * runFactor, 0.15f, arrival.z - 2.5f * runFactor)
                lerp3(arrival, end, tc)
            }
            Aggression.AERIAL -> {
                val end = Vec3(dirX * 20f * runFactor, 0.2f, arrival.z - 3f * runFactor)
                val straight = lerp3(arrival, end, tc)
                // A looping arc, not a straight line - height peaks mid-flight and settles low at
                // the end, the same "lofted" read the 2D version's aerial shots have.
                val arc = 4f * tc * (1f - tc)
                straight.copy(y = straight.y + arc * 3.5f)
            }
        }
    }

    private fun lerp3(a: Vec3, b: Vec3, t: Float): Vec3 = Vec3(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.z, b.z, t))
}

/** A plain, dependency-free 3D vector - PitchGeometry3D's return type, converted to SceneView's
 *  own Position at the call site (ui.scene3d composables only) so this file stays testable
 *  without pulling in Filament. */
data class Vec3(val x: Float, val y: Float, val z: Float)
