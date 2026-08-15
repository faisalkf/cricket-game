package com.example.cricketgame.engine

import com.example.cricketgame.data.PitchLength
import com.example.cricketgame.data.PitchLine
import kotlin.random.Random

data class BowlingInput(
    val targetLine: PitchLine,
    val targetLength: PitchLength,
    val releaseTimingError: Float, // 0.0 = perfect release, 1.0 = max error (early/late vs run-up)
    val bowlingSkill: Int,         // 1-99
    val postPitchTilt: Float       // -1.0..1.0, applied after pitch for deviation
)

data class BowlingOutput(
    val actualLine: PitchLine,
    val actualLength: PitchLength,
    val onStumps: Boolean
)

object BowlingResolver {

    /**
     * Higher bowlingSkill = release timing error matters less (skilled bowlers are more
     * forgiving of a slightly early/late release) and tilt deviation is more reliably applied
     * in the intended direction rather than randomly.
     */
    fun resolve(input: BowlingInput): BowlingOutput {
        val skillFactor = input.bowlingSkill / 99.0
        val effectiveError = input.releaseTimingError * (1.0 - skillFactor * 0.5)

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
