package com.example.cricketgame.ui.scene3d

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.cricketgame.data.BallOutcome
import com.example.cricketgame.ui.screens.BatterShot
import com.example.cricketgame.ui.screens.KeeperState
import com.example.cricketgame.ui.screens.keeperStateFor
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position

/**
 * The wicketkeeper - crouched behind the batting-end stumps, reactive-only for now (see
 * [keeperStateFor]'s doc: it derives READY/GATHERING/CATCHING purely from data the engine
 * already produces, no new gameplay logic). Kept as its own composable/file, same separation
 * principle the original 2D keeper used, so keeper-specific mechanics (byes, stumpings) still
 * have a clean place to grow into later without touching the batter/bowler figures - only the
 * drawing moved from Canvas to this rig, [keeperStateFor] itself is unchanged and reused as-is.
 */
@Composable
internal fun SceneScope.WicketkeeperFigure3D(
    lateralX: Float,
    feetZ: Float,
    shot: BatterShot?,
    outcome: BallOutcome?,
    stateProgress: Float,
    materialLoader: MaterialLoader
) {
    val state = keeperStateFor(shot, outcome)
    val t = stateProgress.coerceIn(0f, 1f)

    val pose = when (state) {
        KeeperState.READY -> HumanoidRig3D.Pose(
            leftLeg = HumanoidRig3D.LimbPose(20f),
            rightLeg = HumanoidRig3D.LimbPose(20f),
            leftArm = HumanoidRig3D.LimbPose(-30f),
            rightArm = HumanoidRig3D.LimbPose(-30f),
            crouch = 0.6f
        )
        KeeperState.GATHERING -> HumanoidRig3D.Pose(
            leftLeg = HumanoidRig3D.LimbPose(20f - 15f * t),
            rightLeg = HumanoidRig3D.LimbPose(20f + 15f * t),
            leftArm = HumanoidRig3D.LimbPose(-30f - 20f * t),
            rightArm = HumanoidRig3D.LimbPose(-30f + 40f * t), // reaches out to one side
            crouch = 0.6f - 0.1f * t
        )
        KeeperState.CATCHING -> HumanoidRig3D.Pose(
            leftLeg = HumanoidRig3D.LimbPose(10f - 10f * t),
            rightLeg = HumanoidRig3D.LimbPose(10f - 10f * t),
            leftArm = HumanoidRig3D.LimbPose(-30f - 100f * t), // both arms rise together
            rightArm = HumanoidRig3D.LimbPose(-30f - 100f * t),
            crouch = 0.6f - 0.5f * t // rises out of the crouch to take the catch
        )
    }

    HumanoidFigure3D(
        feet = Position(x = lateralX, y = 0f, z = feetZ),
        pose = pose,
        kitColor = Color(0xFF2E7D32), // neutral green - distinct from both teams' red/blue
        materialLoader = materialLoader,
        heightM = 1.6f // stands a little shorter than the bowler/batter even before crouch
    )
}
