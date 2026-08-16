package com.example.cricketgame.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.BowlingTimingZones
import com.example.cricketgame.data.DeliveryTiming
import com.example.cricketgame.data.PitchLength

/**
 * v2 bowling control surface - the same unified slider control as BattingControls (see
 * MatchViewModel's class doc): drag the single horizontal slider throughout the run-up sweep,
 * release at the chosen moment. The slider's left-right position at release (-1f..1f) sets the
 * delivery's line, replacing both the old 2D press-and-hold pitch-tap (line + length) and the
 * accelerometer tilt nudge that used to ride on top of it - one value now does what both used to.
 * Release timing against the sweep (unchanged RED->YELLOW->GREEN->RED zones) sets delivery
 * quality, with GREEN further split by MatchViewModel into a standard-best-ball/perfect-ball tier
 * (see BowlingTimingZones.greenTier). Length is no longer player-chosen - the bowler always aims
 * for a good length; only release accuracy can still knock it off that, same as before.
 */
@Composable
fun BowlingControls(
    runUpProgress: Float,
    pitchLength: PitchLength,
    postPitchTilt: Float,
    bowlerName: String,
    shot: BatterShot? = null,
    // Per-ball counter from MatchUiState - keys the shot-impact (swoosh/dust) animation so it
    // restarts exactly once per new ball; see rememberShotImpactProgress.
    ballSeq: Int = 0,
    onDeliveryReleased: (direction: Float, deliveryTiming: DeliveryTiming) -> Unit
) {
    var direction by remember { mutableStateOf(0f) }

    // Long-lived gesture callback captures a stale parameter value otherwise - this keeps the
    // release handler reading whatever the sweep actually is at the instant of release.
    val liveProgress by rememberUpdatedState(runUpProgress)

    val currentZone = BowlingTimingZones.classify(runUpProgress)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Bowling: $bowlerName")
        Spacer(Modifier.height(8.dp))
        Text("Timing: ${zoneLabel(currentZone)}", color = zoneColor(currentZone))
        Spacer(Modifier.height(4.dp))
        TimingGauge(progress = runUpProgress, bands = BowlingGaugeBands)
        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxWidth().height(500.dp)) {
            BowlingAimPitch(
                progress = runUpProgress,
                pitchLength = pitchLength,
                postPitchTilt = postPitchTilt,
                shot = shot,
                ballSeq = ballSeq
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("Line: ${"%.2f".format(direction)}  (left = leg side, right = off side; drag and release to bowl)")
        Slider(
            value = direction,
            onValueChange = { direction = it },
            valueRange = -1f..1f,
            onValueChangeFinished = {
                onDeliveryReleased(direction, BowlingTimingZones.classify(liveProgress))
            }
        )
    }
}

@Composable
private fun BowlingAimPitch(
    progress: Float,
    pitchLength: PitchLength,
    postPitchTilt: Float,
    shot: BatterShot?,
    ballSeq: Int
) {
    val impact = rememberShotImpactProgress(ballSeq, shot)
    val postOutcome = rememberPostOutcomeProgress(ballSeq, shot, progress)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val batterCenterX = w / 2f - 100f
        val batterFeetY = h * 0.10f

        // Shared with PitchBackdrop (the batting screen's pitch) so both screens read as the
        // same place.
        drawPitchBackground(w, h)

        // the bowler's own end at the bottom - this is the player's own bowler, run-up animated
        // from the same release-timing sweep they're aiming against, matching PitchBackdrop's
        // layout so both screens read as the same place
        drawStumps(centerX = w / 2f, baseY = h * 0.94f, scale = 1.6f)
        // batting-end stumps (the target, and where length is measured toward) at the top
        val breakT = stumpsBreakT(shot, postOutcome)
        if (breakT != null) {
            drawBrokenStumps(centerX = w / 2f, baseY = h * 0.06f, scale = 1.6f, breakT = breakT)
        } else {
            drawStumps(centerX = w / 2f, baseY = h * 0.06f, scale = 1.6f)
        }

        drawBowlerFigure(centerX = w / 2f + 90f, feetY = h * 0.80f, progress = progress, scale = 1.6f)
        drawBatterFigure(
            centerX = batterCenterX, feetY = batterFeetY, scale = 1.6f, shot = shot,
            swooshProgress = impact.swoosh, dustProgress = impact.dust
        )

        drawTravellingBall(w, h, progress, pitchLength, postPitchTilt, shot, postOutcome, batterCenterX, batterFeetY)
    }
}

private val BowlingGaugeBands = listOf(
    GaugeBand(0f, BowlingTimingZones.YELLOW_START, Color(0xFFD32F2F)),
    GaugeBand(BowlingTimingZones.YELLOW_START, BowlingTimingZones.GREEN_START, Color(0xFFFFC107)),
    GaugeBand(BowlingTimingZones.GREEN_START, BowlingTimingZones.LATE_RED_START, Color(0xFF4CAF50)),
    GaugeBand(BowlingTimingZones.LATE_RED_START, 1f, Color(0xFFD32F2F)),
    // A bright sliver marking BowlingTimingZones' DARK "perfect ball" tier, drawn on top of the
    // GREEN band it sits inside - otherwise, like batting's six tier, it'd be an invisible,
    // unaimable target.
    GaugeBand(
        BowlingTimingZones.GREEN_CENTER - BowlingTimingZones.DARK_GREEN_HALF_WIDTH,
        BowlingTimingZones.GREEN_CENTER + BowlingTimingZones.DARK_GREEN_HALF_WIDTH,
        Color(0xFFFFD700)
    )
)

private fun zoneLabel(zone: DeliveryTiming): String = when (zone) {
    DeliveryTiming.EARLY_RED -> "EARLY - weak ball"
    DeliveryTiming.YELLOW -> "YELLOW - decent"
    DeliveryTiming.GREEN -> "GREEN - best ball"
    DeliveryTiming.LATE_RED -> "LATE - NO BALL!"
}

private fun zoneColor(zone: DeliveryTiming): Color = when (zone) {
    DeliveryTiming.EARLY_RED, DeliveryTiming.LATE_RED -> Color(0xFFD32F2F)
    DeliveryTiming.YELLOW -> Color(0xFFFFC107)
    DeliveryTiming.GREEN -> Color(0xFF4CAF50)
}
