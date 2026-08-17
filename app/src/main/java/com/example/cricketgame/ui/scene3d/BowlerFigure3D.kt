package com.example.cricketgame.ui.scene3d

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position

/**
 * The bowler: a real run-up rather than a static pose, driven off the SAME [progress] value that
 * drives the RED/YELLOW/GREEN/LATE_RED timing sweep (see PitchGeometry3D.bowlerFeetZ's doc and
 * its crease-sync tests) - not a second animation clock. Poses/thresholds below mirror
 * MatchVisuals.drawBowlerFigure's 2D version: a running stride cycle, then a distinct "gather"
 * beat (front leg planting, bowling arm drawn back to cock it), then "release" (arm fully
 * extended overhead) - both later thresholds scaled off [creaseProgress] itself so a well-timed
 * release on the bowling screen (GREEN ends right at creaseProgress) actually reaches the release
 * pose, rather than it only ever appearing deep in no-ball territory.
 */
@Composable
internal fun SceneScope.BowlerFigure3D(
    progress: Float,
    creaseProgress: Float,
    lateralX: Float,
    materialLoader: MaterialLoader
) {
    val approach = progress.coerceIn(0f, 1f)
    val feetZ = PitchGeometry3D.bowlerFeetZ(progress, creaseProgress)
    val strideProgress = PitchGeometry3D.bowlerStrideProgress(progress, creaseProgress)

    val pose = when {
        approach >= creaseProgress * 0.93f -> {
            // Release: front leg planted well forward (batter-ward, +Z sign convention - see
            // HumanoidRig3D's doc), back leg trailing, bowling arm (right) fully extended
            // overhead.
            HumanoidRig3D.Pose(
                leftLeg = HumanoidRig3D.LimbPose(35f),
                rightLeg = HumanoidRig3D.LimbPose(-25f),
                leftArm = HumanoidRig3D.LimbPose(-20f),
                rightArm = HumanoidRig3D.LimbPose(165f) // swung up and slightly forward overhead
            )
        }
        approach >= creaseProgress * 0.8f -> {
            // Gather: front leg starting to plant, bowling arm drawn back and down to cock it -
            // a distinct beat between running and release rather than jumping straight there.
            HumanoidRig3D.Pose(
                leftLeg = HumanoidRig3D.LimbPose(15f),
                rightLeg = HumanoidRig3D.LimbPose(-10f),
                leftArm = HumanoidRig3D.LimbPose(10f),
                rightArm = HumanoidRig3D.LimbPose(-75f) // cocked back behind the body
            )
        }
        else -> {
            // Running stride cycle - three frames (wide stride, mid-stride, wide stride the other
            // way), cycling faster as strideProgress nears the gather/release phase, same
            // strideCycleIndex the geometry math already exposes.
            val cycle = PitchGeometry3D.strideCycleIndex(strideProgress)
            val swing = when (cycle) { 0 -> 28f; 2 -> -28f; else -> 0f }
            HumanoidRig3D.Pose(
                leftLeg = HumanoidRig3D.LimbPose(swing),
                rightLeg = HumanoidRig3D.LimbPose(-swing),
                leftArm = HumanoidRig3D.LimbPose(-swing * 0.6f),
                rightArm = HumanoidRig3D.LimbPose(swing * 0.6f)
            )
        }
    }

    HumanoidFigure3D(
        feet = Position(x = lateralX, y = PitchGeometry3D.strideBobM(strideProgress), z = feetZ),
        pose = pose,
        kitColor = Color(0xFFB71C1C), // fielding side's red - matches the 2D bowler's palette
        materialLoader = materialLoader
    )
}
