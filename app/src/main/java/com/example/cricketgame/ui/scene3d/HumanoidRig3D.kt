package com.example.cricketgame.ui.scene3d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation

/**
 * The primitive-based low-poly humanoid rig shared by [BowlerFigure3D], [BatterFigure3D] and
 * [WicketkeeperFigure3D] - boxes/cylinders/spheres assembled into a basic humanoid shape, per the
 * migration brief's explicit fallback: free, properly-licensed, *automatable* 3D character models
 * did not pan out (see the migration commit history for what was actually checked - Sketchfab's
 * download endpoint requires an authenticated account's API token, which this sandboxed session
 * has no way to obtain; Poly Haven has no humanoid/character models at all; Mixamo is
 * interactive-only). This is placeholder geometry for this pass, not a permanent art decision -
 * swapping in real rigged/animated glTF models later only means swapping what each figure's
 * composable draws, since gameplay state feeds into pose the same way either way.
 *
 * This is a real jointed rig, not floating shapes: each limb is a [Node] pivoted at its shoulder/
 * hip with a child [CylinderNode] hanging along -Y from that pivot, so rotating the pivot's X
 * angle swings the limb through the same Y-Z plane the pitch itself runs along - a positive angle
 * swings a limb toward the batter's end (-Z), negative swings it back toward the bowler's run-up
 * (+Z). That convention is what [BowlerFigure3D]'s run/gather/release poses and [BatterFigure3D]'s
 * stance/swing poses are built from.
 */
internal object HumanoidRig3D {
    /** One limb's pivot angle (degrees, see class doc for the sign convention) and length scale
     *  (1f = the rig's default proportions - shortened for e.g. the keeper's crouched legs). */
    data class LimbPose(val angleDeg: Float, val lengthScale: Float = 1f)

    /** Everything needed to pose one figure for one frame. Angles only - the rig itself owns
     *  proportions (torso/head/limb sizes), keeping every caller's pose logic free of raw meters. */
    data class Pose(
        val leftLeg: LimbPose = LimbPose(0f),
        val rightLeg: LimbPose = LimbPose(0f),
        val leftArm: LimbPose = LimbPose(0f),
        val rightArm: LimbPose = LimbPose(0f),
        /** 0f = standing fully upright, 1f = a full crouch (hips lowered, legs shortened) - only
         *  the keeper uses a non-zero resting value, but any figure can. */
        val crouch: Float = 0f
    )
}

/**
 * Draws one primitive humanoid at [feet] (world position, meters - the ground point between the
 * figure's feet) built from [pose]. [heightM] is the standing (crouch = 0) height from feet to
 * head-top; proportions below it are fractions of that so every figure using this rig shares one
 * set of ratios. [kitColor] tints the torso/limbs (unlit is deliberately NOT used - basic lit PBR
 * color per the migration brief's "color, not textures, is fine for now").
 */
@Composable
internal fun SceneScope.HumanoidFigure3D(
    feet: Position,
    pose: HumanoidRig3D.Pose,
    kitColor: Color,
    materialLoader: MaterialLoader,
    heightM: Float = 1.75f
) {
    val kitMaterial = remember(materialLoader, kitColor) {
        materialLoader.createColorInstance(kitColor, metallic = 0f, roughness = 0.65f)
    }
    val skinMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color(0xFFC68863), metallic = 0f, roughness = 0.7f)
    }

    val crouch = pose.crouch.coerceIn(0f, 1f)
    val standingHipY = heightM * 0.52f
    val legLen = standingHipY * (1f - crouch * 0.35f) // crouching shortens the effective leg length
    val hipY = legLen
    val torsoLen = heightM * 0.32f
    val headRadius = heightM * 0.065f
    val hipSpacing = heightM * 0.10f
    val shoulderSpacing = heightM * 0.16f
    val shoulderY = hipY + torsoLen
    val armLen = heightM * 0.30f
    val limbRadius = heightM * 0.035f

    Node(position = feet) {
        // Torso - a capsule rather than a plain cylinder so the shoulders/waist don't read as a
        // hard-edged pipe.
        CapsuleNode(
            radius = limbRadius * 1.7f,
            height = torsoLen - limbRadius * 1.7f * 2f,
            materialInstance = kitMaterial,
            position = Position(y = hipY + torsoLen / 2f)
        )
        // Head
        SphereNode(
            radius = headRadius,
            materialInstance = skinMaterial,
            position = Position(y = shoulderY + headRadius * 1.3f)
        )

        // Legs - hip-pivoted so LimbPose.angleDeg swings the whole leg, not just its mesh.
        Node(position = Position(x = -hipSpacing, y = hipY), rotation = Rotation(x = pose.leftLeg.angleDeg)) {
            CylinderNode(
                radius = limbRadius,
                height = legLen * pose.leftLeg.lengthScale,
                materialInstance = kitMaterial,
                position = Position(y = -legLen * pose.leftLeg.lengthScale / 2f)
            )
        }
        Node(position = Position(x = hipSpacing, y = hipY), rotation = Rotation(x = pose.rightLeg.angleDeg)) {
            CylinderNode(
                radius = limbRadius,
                height = legLen * pose.rightLeg.lengthScale,
                materialInstance = kitMaterial,
                position = Position(y = -legLen * pose.rightLeg.lengthScale / 2f)
            )
        }

        // Arms - shoulder-pivoted, same convention as the legs.
        Node(position = Position(x = -shoulderSpacing, y = shoulderY), rotation = Rotation(x = pose.leftArm.angleDeg)) {
            CylinderNode(
                radius = limbRadius * 0.85f,
                height = armLen * pose.leftArm.lengthScale,
                materialInstance = skinMaterial,
                position = Position(y = -armLen * pose.leftArm.lengthScale / 2f)
            )
        }
        Node(position = Position(x = shoulderSpacing, y = shoulderY), rotation = Rotation(x = pose.rightArm.angleDeg)) {
            CylinderNode(
                radius = limbRadius * 0.85f,
                height = armLen * pose.rightArm.lengthScale,
                materialInstance = skinMaterial,
                position = Position(y = -armLen * pose.rightArm.lengthScale / 2f)
            )
        }
    }
}
