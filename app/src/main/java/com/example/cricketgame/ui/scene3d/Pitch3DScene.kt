package com.example.cricketgame.ui.scene3d

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.cricketgame.data.BallOutcome
import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.ui.screens.BatterShot
import com.example.cricketgame.ui.screens.isPreContactBall
import com.example.cricketgame.ui.screens.rememberPostOutcomeProgress
import com.example.cricketgame.ui.screens.rememberShotImpactProgress
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader

/**
 * The full-screen 3D pitch - the SceneView/Filament replacement for the old Canvas-based
 * PitchBackdrop (see MatchVisuals.kt, kept around for now only as the source of a few small
 * reused pure helpers - [isPreContactBall], [rememberShotImpactProgress],
 * [rememberPostOutcomeProgress] - not for any drawing). Same role as PitchBackdrop: the single
 * full-screen backdrop shared by both the batting and bowling screens, with MatchScreen layering
 * the scoreboard/timing HUD and slider control as separate 2D Compose overlays in dedicated space
 * above/below it, per the existing minimal-HUD layout - none of that UI lives inside this 3D
 * scene.
 *
 * Every visual here is driven by the exact same gameplay state PitchBackdrop was driven by -
 * [progress] is still the one shared run-up/timing-sweep value (see PitchGeometry3D's doc for how
 * the bowler's run-up and the crease/no-ball sync derive from it), [shot]/[outcome] still come
 * straight from MatchViewModel's resolved ball outcome. Nothing about BattingResolver,
 * BowlingResolver, or MatchViewModel changed for this migration - only how the same numbers get
 * turned into pixels (now voxels).
 *
 * [isPlayerBatting] drives which of the two fixed camera setups is active - see the
 * `LaunchedEffect` below. Cameras here are NOT the orbit/pan/zoom `cameraManipulator` SceneView
 * defaults to (that's for user-drag camera control, which this game doesn't want - the player
 * controls the delivery/shot via the slider, not the camera) - `cameraManipulator = null` below
 * disables that, and the camera is instead scripted directly per role.
 */
@Composable
fun Pitch3DScene(
    progress: Float,
    pitchLength: PitchLength,
    postPitchTilt: Float,
    showBall: Boolean,
    isPlayerBatting: Boolean,
    shot: BatterShot? = null,
    outcome: BallOutcome? = null,
    ballSeq: Int = 0,
    creaseProgress: Float = 1f,
    modifier: Modifier = Modifier
) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberCameraNode(engine)

    // The two fixed camera rigs - behind-and-above the bowler looking down the whole pitch toward
    // the batter (both the run-up itself and the distant batter/stumps stay in frame throughout),
    // or behind-and-above the batter looking back toward the bowler's end (the delivery/run-up and
    // the ball's flight/landing stay in frame). Re-pushed from a LaunchedEffect rather than
    // rememberCameraNode's own apply block, per SceneView's documented pattern for changing a
    // node's transform reactively after creation (the apply block only runs once, at creation).
    LaunchedEffect(isPlayerBatting) {
        if (isPlayerBatting) {
            cameraNode.position = Position(x = 0f, y = 1.7f, z = -3.2f)
            cameraNode.lookAt(Position(x = 0f, y = 1.1f, z = PitchGeometry3D.BOWLING_CREASE_Z))
        } else {
            cameraNode.position = Position(x = 0f, y = 2.6f, z = PitchGeometry3D.RUN_UP_START_Z + 4.5f)
            cameraNode.lookAt(Position(x = 0f, y = 1f, z = 0f))
        }
    }

    val impact = rememberShotImpactProgress(ballSeq, shot)
    val postOutcome = rememberPostOutcomeProgress(ballSeq, shot, progress)

    val groundMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFF3E7C3E), metallic = 0f, roughness = 1f)
    }
    val pitchMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFD9B876), metallic = 0f, roughness = 0.9f)
    }
    val stumpsMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFE8D9B0), metallic = 0f, roughness = 0.6f)
    }
    val creaseMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color.White)
    }
    val ballMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFB71C1C), metallic = 0.1f, roughness = 0.4f)
    }

    SceneView(
        modifier = modifier.fillMaxSize(),
        engine = engine,
        materialLoader = materialLoader,
        cameraNode = cameraNode,
        cameraManipulator = null,
        // Keep our authored world-space coordinates (pitch/stumps/figures placed at real meters
        // along Z) instead of SceneView auto-translating content so its bounding box centers on
        // the origin - see the SceneView docs' "How far orbitHomePosition actually puts the
        // camera" section for why that default would otherwise silently fight our fixed cameras.
        autoCenterContent = false
    ) {
        // Ground - one big plane comfortably covering the pitch + run-up + surrounds.
        PlaneNode(size = Size(200f, 200f), materialInstance = groundMaterial)

        // The 22-yard pitch strip itself, sitting a hair above the ground plane to avoid z-fighting.
        PlaneNode(
            size = Size(PitchGeometry3D.PITCH_WIDTH_M, PitchGeometry3D.PITCH_LENGTH_M + 2f),
            position = Position(y = 0.01f, z = PitchGeometry3D.PITCH_LENGTH_M / 2f),
            materialInstance = pitchMaterial
        )

        // The bowling (front-foot) crease - the line BowlerFigure3D's run-up animates toward, in
        // sync with the same no-ball threshold (see PitchGeometry3D.bowlerFeetZ's doc).
        CubeNode(
            size = Size(PitchGeometry3D.PITCH_WIDTH_M, 0.02f, 0.05f),
            position = Position(y = 0.02f, z = PitchGeometry3D.BOWLING_CREASE_Z),
            materialInstance = creaseMaterial
        )

        StumpsAt(z = 0f, materialInstance = stumpsMaterial)
        StumpsAt(z = PitchGeometry3D.BOWLING_CREASE_Z, materialInstance = stumpsMaterial)

        BowlerFigure3D(
            progress = progress,
            creaseProgress = creaseProgress,
            lateralX = 0f,
            materialLoader = materialLoader
        )
        BatterFigure3D(
            lateralX = 0f,
            feetZ = 0f,
            shot = shot,
            impact = impact,
            materialLoader = materialLoader
        )
        WicketkeeperFigure3D(
            lateralX = 0f,
            feetZ = -1.3f, // behind the batting-end stumps, not on top of them
            shot = shot,
            outcome = outcome,
            stateProgress = postOutcome,
            materialLoader = materialLoader
        )

        if (showBall) {
            val ballPos = if (isPreContactBall(shot, postOutcome)) {
                PitchGeometry3D.ballPreContactPosition(progress, pitchLength, postPitchTilt)
            } else {
                val arrival = PitchGeometry3D.ballPreContactPosition(1f, pitchLength, postPitchTilt)
                PitchGeometry3D.ballPostOutcomePosition(arrival, shot!!, postOutcome)
            }
            SphereNode(
                radius = PitchGeometry3D.BALL_RADIUS_M,
                materialInstance = ballMaterial,
                position = Position(x = ballPos.x, y = ballPos.y, z = ballPos.z)
            )
        }
    }
}

/** Three stumps at [z], centered on the pitch strip (X = 0) - shared by both ends. */
@Composable
private fun SceneScope.StumpsAt(z: Float, materialInstance: MaterialInstance) {
    val spacing = 0.1f
    val height = PitchGeometry3D.STUMPS_HEIGHT_M
    for (dx in floatArrayOf(-spacing, 0f, spacing)) {
        CylinderNode(
            radius = 0.018f,
            height = height,
            materialInstance = materialInstance,
            position = Position(x = dx, y = height / 2f, z = z)
        )
    }
}
