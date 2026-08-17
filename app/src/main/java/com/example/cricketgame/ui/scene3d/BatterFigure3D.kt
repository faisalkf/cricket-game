package com.example.cricketgame.ui.scene3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.example.cricketgame.data.Aggression
import com.example.cricketgame.data.TimingQuality
import com.example.cricketgame.ui.screens.BatterShot
import com.example.cricketgame.ui.screens.ShotImpactProgress
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation

/**
 * The batter: a resting stance while waiting for the next delivery, or (briefly, right after a
 * ball is resolved) a swing pose reflecting [shot]'s aggression/timing/direction - same trigger
 * MatchVisuals.drawBatterFigure's 2D version uses ([shot] is only non-null while
 * DeliveryPhase.BALL_RESULT is showing, see MatchScreen). [swoosh] (0f..1f, from the SAME
 * [com.example.cricketgame.ui.screens.rememberShotImpactProgress] the 2D bat-swing trail used -
 * not a new animation clock) drives the swing from rest to full extension.
 *
 * The batter faces the bowler (+Z, up-field) - the opposite forward direction from the bowler's
 * own run-up (-Z, toward the batter), so this figure's "forward" arm/leg angles are the mirror
 * sign of [BowlerFigure3D]'s.
 */
@Composable
internal fun SceneScope.BatterFigure3D(
    lateralX: Float,
    feetZ: Float,
    shot: BatterShot?,
    swoosh: Float,
    materialLoader: MaterialLoader
) {
    val batMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF8D6E4A), metallic = 0f, roughness = 0.5f)
    }

    val restLeftLeg = 6f
    val restRightLeg = -6f
    val restLeftArm = -35f
    val restRightArm = -50f

    val pose: HumanoidRig3D.Pose
    val batAngle: Float
    if (shot == null) {
        pose = HumanoidRig3D.Pose(
            leftLeg = HumanoidRig3D.LimbPose(restLeftLeg),
            rightLeg = HumanoidRig3D.LimbPose(restRightLeg),
            leftArm = HumanoidRig3D.LimbPose(restLeftArm),
            rightArm = HumanoidRig3D.LimbPose(restRightArm)
        )
        batAngle = -40f
    } else {
        val t = swoosh.coerceIn(0f, 1f)
        // Bigger, more open swing the more aggressive/well-timed the shot was - a defensive prod
        // barely moves, a well-timed aerial shot swings through hard.
        val swingMagnitude = when {
            shot.timingQuality == TimingQuality.RED -> 20f // a miss - just an incomplete poke
            shot.aggression == Aggression.DEFENSIVE -> 25f
            shot.aggression == Aggression.AERIAL -> 130f
            else -> 90f // GROUND
        }
        // Off side (positive direction) swings the bat/arms out to +X, leg side to -X - reused as
        // a lean via the leg angles too so the whole figure reads as committing to a side.
        val sideLean = shot.direction.coerceIn(-1f, 1f) * 15f * t
        val swingArm = restRightArm - swingMagnitude * t // toward +Z (through the ball) as t -> 1

        pose = HumanoidRig3D.Pose(
            leftLeg = HumanoidRig3D.LimbPose(restLeftLeg + sideLean),
            rightLeg = HumanoidRig3D.LimbPose(restRightLeg + sideLean),
            leftArm = HumanoidRig3D.LimbPose(restLeftArm - swingMagnitude * 0.6f * t),
            rightArm = HumanoidRig3D.LimbPose(swingArm)
        )
        batAngle = -40f - swingMagnitude * t
    }

    HumanoidFigure3D(
        feet = Position(x = lateralX, y = 0f, z = feetZ),
        pose = pose,
        kitColor = Color(0xFF1565C0), // batting side's blue - matches the 2D batter's palette
        materialLoader = materialLoader
    )

    // The bat itself - not welded to the rotated arm segment (this rig doesn't expose its inner
    // joint transforms to callers), just a separate prop roughly at hand height/reach that swings
    // through the same angle - close enough for a primitive placeholder figure.
    Node(
        position = Position(x = lateralX + 0.12f, y = 0.55f, z = feetZ + 0.15f),
        rotation = Rotation(x = batAngle)
    ) {
        CylinderNode(
            radius = 0.025f,
            height = 0.5f,
            materialInstance = batMaterial,
            position = Position(y = -0.25f)
        )
    }
}

/** Convenience overload taking the raw [com.example.cricketgame.ui.screens.ShotImpactProgress] so
 *  callers don't have to unpack `.swoosh` themselves. */
@Composable
internal fun SceneScope.BatterFigure3D(
    lateralX: Float,
    feetZ: Float,
    shot: BatterShot?,
    impact: ShotImpactProgress,
    materialLoader: MaterialLoader
) = BatterFigure3D(lateralX, feetZ, shot, impact.swoosh, materialLoader)
