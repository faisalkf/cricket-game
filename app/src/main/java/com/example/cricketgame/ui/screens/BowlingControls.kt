package com.example.cricketgame.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.data.PitchLine

/**
 * v1 bowling control surface.
 *
 * - Press and hold on the pitch map -> sets targetLine/targetLength (TODO: map hold coordinates
 *   to the PitchLine/PitchLength grid based on where on the pitch-map composable the press lands).
 * - Release timed against the bowler's run-up animation -> releaseTimingError (0f = perfect).
 * - After the ball pitches, tilt is sampled again for postPitchTilt deviation
 *   (TODO: wire up SensorManager, same as batting).
 *
 * This composable is UI-only; BowlingResolver.resolve() does the actual pitch-accuracy math.
 */
@Composable
fun BowlingControls(
    onDeliveryReleased: (targetLine: PitchLine, targetLength: PitchLength, releaseTimingError: Float) -> Unit
) {
    var isHolding by remember { mutableStateOf(false) }
    var holdStartMillis by remember { mutableStateOf(0L) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(if (isHolding) "Holding... release as the bowler delivers" else "Press and hold to mark your line & length")
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isHolding = true
                            holdStartMillis = System.currentTimeMillis()
                            tryAwaitRelease()
                            isHolding = false

                            // TODO: derive targetLine/targetLength from press coordinates
                            // within this Box instead of hardcoding ON_STUMPS/GOOD_LENGTH.
                            val heldMillis = System.currentTimeMillis() - holdStartMillis
                            val idealHoldMillis = 900L // tune against bowler run-up animation length
                            val error = (kotlin.math.abs(heldMillis - idealHoldMillis) / idealHoldMillis.toFloat())
                                .coerceIn(0f, 1f)

                            onDeliveryReleased(PitchLine.ON_STUMPS, PitchLength.GOOD_LENGTH, error)
                        }
                    )
                }
        )
    }
}
