package com.example.cricketgame.model

import com.example.cricketgame.data.Player
import com.example.cricketgame.data.Team

class Innings(
    val battingTeam: Team,
    val bowlingTeam: Team,
    val totalOvers: Int,
    val target: Int? = null // null for the first innings; set for the chase
) {
    val balls = mutableListOf<BallResult>()

    var totalRuns = 0
        private set
    var wickets = 0
        private set

    // legal balls bowled this innings (6 per over; wides are still ignored in v1, but no-balls
    // from a mistimed player-bowled delivery are modeled - see recordBall's legalDelivery param)
    var ballsBowled = 0
        private set

    /** overs bowled so far, per bowler - enforced against the max-overs-per-bowler cap */
    val bowlerOversUsed = mutableMapOf<String, Int>()

    val oversCompleted: Int get() = ballsBowled / 6
    val ballsInCurrentOver: Int get() = ballsBowled % 6
    val isComplete: Boolean
        get() = wickets >= 10 ||
                oversCompleted >= totalOvers ||
                (target != null && totalRuns >= target)

    val maxOversPerBowler: Int get() = maxOf(1, totalOvers / 5)

    /**
     * @param legalDelivery false for a no-ball: runs still count, but it doesn't consume a ball
     * of the over (the bowler must bowl it again) and can never be a wicket (enforced by the
     * caller - a no-ball's outcome should never be a WICKET_* by the time it gets here).
     */
    fun recordBall(result: BallResult, legalDelivery: Boolean = true) {
        balls.add(result)
        totalRuns += result.runsScored
        if (isWicket(result.outcome)) wickets++

        if (legalDelivery) {
            ballsBowled++
            if (ballsInCurrentOver == 0 && ballsBowled > 0) {
                val bowlerId = result.bowler.id
                bowlerOversUsed[bowlerId] = (bowlerOversUsed[bowlerId] ?: 0) + 1
            }
        }
    }

    fun oversRemainingFor(bowler: Player): Int =
        maxOversPerBowler - (bowlerOversUsed[bowler.id] ?: 0)

    private fun isWicket(outcome: com.example.cricketgame.data.BallOutcome): Boolean =
        outcome.name.startsWith("WICKET")
}
