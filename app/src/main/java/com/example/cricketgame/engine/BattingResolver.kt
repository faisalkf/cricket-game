package com.example.cricketgame.engine

import com.example.cricketgame.data.*
import kotlin.random.Random

/**
 * Resolves a single delivery once we know:
 *  - timingQuality (from where the player released the slider vs the RED/YELLOW/GREEN indicator)
 *  - aggression (from slider position: DEFENSIVE / GROUND / AERIAL)
 *  - batsman skill
 *  - whether the ball actually ends up on the stumps (from bowler's pitch + deviation)
 *  - field mode
 *
 * v1 balancing note: probabilities below are a first pass and expected to be tuned via
 * playtesting - see design doc section 10 (Open Items).
 */
object BattingResolver {

    fun resolve(
        battingSkill: Int,
        timing: TimingQuality,
        aggression: Aggression,
        onStumps: Boolean,
        fieldMode: FieldMode
    ): Pair<BallOutcome, Int> {

        // --- RED: ball is missed entirely ---
        if (timing == TimingQuality.RED) {
            return if (onStumps) {
                val outcome = if (Random.nextBoolean()) BallOutcome.WICKET_BOWLED else BallOutcome.WICKET_LBW
                outcome to 0
            } else {
                BallOutcome.DOT to 0
            }
        }

        // skill factor 0.0 - 1.0, used to bias toward better outcomes for stronger batsmen
        val skillFactor = battingSkill / 99.0

        // --- YELLOW: mistimed contact ---
        if (timing == TimingQuality.YELLOW) {
            return when (aggression) {
                Aggression.DEFENSIVE -> BallOutcome.DOT to 0
                Aggression.GROUND -> {
                    if (Random.nextDouble() < 0.5 + skillFactor * 0.2) BallOutcome.RUN_1 to 1
                    else BallOutcome.DOT to 0
                }
                Aggression.AERIAL -> {
                    // mistimed + aggressive = real catch risk, higher when field is attacking
                    val catchChance = if (fieldMode == FieldMode.ATTACKING) 0.45 else 0.25
                    if (Random.nextDouble() < catchChance) BallOutcome.WICKET_CAUGHT to 0
                    else if (Random.nextDouble() < 0.3 + skillFactor * 0.2) BallOutcome.RUN_2 to 2
                    else BallOutcome.DOT to 0
                }
            }
        }

        // --- GREEN: perfect timing ---
        return when (aggression) {
            Aggression.DEFENSIVE -> {
                // Safe guaranteed runs, no dismissal risk
                if (Random.nextDouble() < 0.5) BallOutcome.RUN_1 to 1
                else BallOutcome.RUN_2 to 2
            }
            Aggression.GROUND -> {
                val boundaryChance = 0.15 + skillFactor * 0.25
                when {
                    Random.nextDouble() < boundaryChance -> BallOutcome.FOUR to 4
                    Random.nextDouble() < 0.6 -> BallOutcome.RUN_2 to 2
                    else -> BallOutcome.RUN_1 to 1
                }
            }
            Aggression.AERIAL -> {
                val catchChance = if (fieldMode == FieldMode.ATTACKING) 0.15 else 0.05
                val sixChance = 0.25 + skillFactor * 0.35 // strong batsmen much more likely to clear the rope
                when {
                    Random.nextDouble() < catchChance -> BallOutcome.WICKET_CAUGHT to 0
                    Random.nextDouble() < sixChance -> BallOutcome.SIX to 6
                    else -> BallOutcome.FOUR to 4
                }
            }
        }
    }
}
