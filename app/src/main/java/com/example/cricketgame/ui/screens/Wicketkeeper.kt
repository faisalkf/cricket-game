package com.example.cricketgame.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.cricketgame.data.BallOutcome
import com.example.cricketgame.data.TimingQuality

/**
 * The wicketkeeper: a fielding-side figure behind the batting-end stumps, visual-only for now and
 * deliberately kept in its own file/functions - not folded into drawBatterFigure/drawBowlerFigure
 * - so keeper-specific mechanics (byes, stumpings, ...) have a clean, separate place to grow into
 * later without touching the batter/bowler drawing code. Nothing below implements those mechanics
 * yet; [keeperStateFor] only reacts to the two outcomes already in the engine that plausibly
 * involve the keeper (a caught dismissal, or the ball simply reaching them off a missed shot).
 */

/** The keeper's current reaction pose. Only three for now, per the existing outcomes wired up in
 *  [keeperStateFor] - extending this (e.g. a STUMPING pose) is exactly the kind of follow-up this
 *  file is structured to make easy, without new cases here or in [keeperStateFor] leaking into the
 *  batter/bowler code. */
enum class KeeperState { READY, GATHERING, CATCHING }

/**
 * Derives the keeper's pose purely from data the engine already produces - no new gameplay logic:
 *  - [BallOutcome.WICKET_CAUGHT] -> CATCHING (a clean take).
 *  - A missed delivery that wasn't on the stumps (RED timing, not [BatterShot.onStumps]) -> the
 *    existing "dot ball, ball reaches the keeper" case -> GATHERING.
 *  - Anything else (contact was made, or a bowled/lbw miss that never reaches the keeper at all)
 *    -> READY, the resting stance.
 */
fun keeperStateFor(shot: BatterShot?, outcome: BallOutcome?): KeeperState = when {
    shot == null -> KeeperState.READY
    outcome == BallOutcome.WICKET_CAUGHT -> KeeperState.CATCHING
    shot.timingQuality == TimingQuality.RED && !shot.onStumps -> KeeperState.GATHERING
    else -> KeeperState.READY
}

/**
 * Draws the keeper in a low, wide-kneed crouch (much shorter than the standing batter/bowler
 * figures - see [drawTorso]/[drawBentLeg]/[drawBentArm], reused from MatchVisuals so the limb/torso
 * treatment stays consistent across all three figures), reacting per [state]. [stateProgress]
 * (0f..1f) drives the reach/rise of the GATHERING/CATCHING poses - deliberately the SAME
 * postOutcome progress value the ball/bat animations already use (passed in by the caller) rather
 * than a separate clock, so the keeper's reaction is timed consistently with everything else on
 * screen instead of animating on its own schedule.
 */
internal fun DrawScope.drawWicketkeeperFigure(centerX: Float, baseY: Float, scale: Float, state: KeeperState, stateProgress: Float) {
    val feet = baseY
    val bodyBottom = feet - 20f * scale
    val bodyTop = bodyBottom - 26f * scale
    val headCenter = Offset(centerX, bodyTop - 9f * scale)
    val headRadius = 9f * scale

    drawFootShadow(centerX, feet, scale * 0.8f)

    // A distinct green kit - neither team's own red/blue - reads clearly as a third, neutral role.
    val kit = Color(0xFF2E7D32)
    val kitOutline = Color(0xFF1B4D1F)
    val kitBrush = Brush.verticalGradient(listOf(Color(0xFF66BB6A), kit, kitOutline))

    drawCircle(kit, radius = headRadius, center = headCenter)
    drawCircle(kitOutline, radius = headRadius, center = headCenter, style = Stroke(width = 2f * scale))

    drawTorso(centerX, bodyTop, bodyBottom, shoulderWidth = 15f * scale, waistWidth = 11f * scale, brush = kitBrush)

    // A wide, bent-knee base - the low crouch a keeper holds between deliveries - rather than the
    // standing legs the other two figures use.
    val hip = Offset(centerX, bodyBottom)
    val legStroke = 6f * scale
    drawBentLeg(hip, Offset(centerX + 13f * scale, feet), kitBrush, legStroke)
    drawBentLeg(hip, Offset(centerX - 13f * scale, feet), kitBrush, legStroke)

    val shoulder = Offset(centerX, bodyTop + 5f * scale)
    val gloveColor = Color(0xFFF5F5F5)
    val armStroke = 5f * scale
    val t = stateProgress.coerceIn(0f, 1f)

    when (state) {
        KeeperState.READY -> {
            // gloves down and forward, low and ready - the resting stance between deliveries.
            val hands = Offset(centerX, bodyBottom + 7f * scale)
            drawBentArm(shoulder, hands, kitBrush, armStroke)
            drawCircle(gloveColor, radius = 3.5f * scale, center = hands)
        }
        KeeperState.GATHERING -> {
            // leans and reaches down/across to collect the ball off the pad/body.
            val reachX = lerpFloat(0f, 16f, t) * scale
            val hands = Offset(centerX + reachX, bodyBottom + 9f * scale)
            drawBentArm(shoulder, hands, kitBrush, armStroke)
            drawCircle(gloveColor, radius = 3.5f * scale, center = hands)
        }
        KeeperState.CATCHING -> {
            // both gloves rise together out in front - a clean take.
            val rise = lerpFloat(0f, 24f, t) * scale
            val hands = Offset(centerX, bodyTop - rise)
            drawBentArm(shoulder, hands, kitBrush, armStroke)
            drawCircle(gloveColor, radius = 4.5f * scale, center = hands)
        }
    }
}
