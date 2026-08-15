package com.example.cricketgame.engine

import com.example.cricketgame.data.DeliveryTiming
import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.data.PitchLine
import kotlin.random.Random

data class BowlingInput(
    val targetLine: PitchLine,
    val targetLength: PitchLength,
    val deliveryTiming: DeliveryTiming, // where in the RED->YELLOW->GREEN->RED sweep the bowler released
    val bowlingSkill: Int,              // 1-99
    val postPitchTilt: Float            // -1.0..1.0, applied after pitch for deviation
)

data class BowlingOutput(
    val actualLine: PitchLine,
    val actualLength: PitchLength,
    val onStumps: Boolean
)

object BowlingResolver {

    /**
     * Release accuracy comes from where in the single-pass timing sweep the bowler let go
     * (see [DeliveryTiming]) rather than a raw early/late error value:
     *  - GREEN is the most accurate release (least drift).
     *  - YELLOW is comfortably accurate.
     *  - EARLY_RED (let go well before the sweep matures) drifts more - an underpowered ball.
     *  - LATE_RED (the no-ball zone, past GREEN) is the least accurate - a rushed release.
     * Higher bowlingSkill still makes drift less likely regardless of release timing.
     */
    fun resolve(input: BowlingInput): BowlingOutput {
        val skillFactor = input.bowlingSkill / 99.0
        val baseError = when (input.deliveryTiming) {
            DeliveryTiming.GREEN -> 0.08
            DeliveryTiming.YELLOW -> 0.25
            DeliveryTiming.EARLY_RED -> 0.50
            DeliveryTiming.LATE_RED -> 0.65
        }
        val effectiveError = baseError * (1.0 - skillFactor * 0.5)

        // Chance the delivery drifts off target line due to release error
        val drifted = Random.nextDouble() < effectiveError

        val actualLine = if (drifted) {
            // drift moves one step toward the tilt-influenced side
            when (input.targetLine) {
                PitchLine.ON_STUMPS -> if (input.postPitchTilt >= 0) PitchLine.OUTSIDE_OFF else PitchLine.OUTSIDE_LEG
                PitchLine.OUTSIDE_OFF -> if (Random.nextBoolean()) PitchLine.ON_STUMPS else PitchLine.OUTSIDE_OFF
                PitchLine.OUTSIDE_LEG -> if (Random.nextBoolean()) PitchLine.ON_STUMPS else PitchLine.OUTSIDE_LEG
            }
        } else {
            input.targetLine
        }

        val actualLength = if (Random.nextDouble() < effectiveError * 0.5) {
            // small chance length also slips a category
            PitchLength.entries.toTypedArray().random()
        } else {
            input.targetLength
        }

        val onStumps = actualLine == PitchLine.ON_STUMPS

        return BowlingOutput(actualLine, actualLength, onStumps)
    }
}
