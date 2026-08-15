package com.example.cricketgame.engine

import com.example.cricketgame.data.BallOutcome
import com.example.cricketgame.data.Player
import kotlin.random.Random

/**
 * Used when the CPU is batting - no slider/tilt UI shown to the player, just ball + outcome
 * animation. Resolves purely from batsman skill vs bowler skill (bowler skill = player's chosen
 * bowler if manual, or CPU bowler skill if the player also let CPU bowl).
 */
object CpuInningsSimulator {

    fun simulateBall(batsman: Player, bowler: Player): Pair<BallOutcome, Int> {
        val battingEdge = batsman.battingSkill / 99.0
        val bowlingEdge = bowler.bowlingSkill / 99.0
        // net advantage in favor of the batsman, clamped 0..1
        val net = (0.5 + (battingEdge - bowlingEdge) * 0.5).coerceIn(0.05, 0.95)

        val roll = Random.nextDouble()
        return when {
            roll < (1 - net) * 0.15 -> {
                val outcome = if (Random.nextBoolean()) BallOutcome.WICKET_BOWLED else BallOutcome.WICKET_CAUGHT
                outcome to 0
            }
            roll < (1 - net) * 0.15 + 0.35 -> BallOutcome.DOT to 0
            roll < (1 - net) * 0.15 + 0.35 + 0.25 -> BallOutcome.RUN_1 to 1
            roll < (1 - net) * 0.15 + 0.35 + 0.25 + 0.10 -> BallOutcome.RUN_2 to 2
            roll < (1 - net) * 0.15 + 0.35 + 0.25 + 0.10 + net * 0.15 -> BallOutcome.FOUR to 4
            else -> BallOutcome.SIX to 6
        }
    }
}
