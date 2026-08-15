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

    // legal balls bowled this innings (6 per over, v1 ignores wides/no-balls for simplicity)
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

    fun recordBall(result: BallResult) {
        balls.add(result)
        totalRuns += result.runsScored
        ballsBowled++
        if (isWicket(result.outcome)) wickets++

        if (ballsInCurrentOver == 0 && ballsBowled > 0) {
            val bowlerId = result.bowler.id
            bowlerOversUsed[bowlerId] = (bowlerOversUsed[bowlerId] ?: 0) + 1
        }
    }

    fun oversRemainingFor(bowler: Player): Int =
        maxOversPerBowler - (bowlerOversUsed[bowler.id] ?: 0)

    private fun isWicket(outcome: com.example.cricketgame.data.BallOutcome): Boolean =
        outcome.name.startsWith("WICKET")
}
