package com.example.cricketgame.model

import com.example.cricketgame.data.MatchFormat
import com.example.cricketgame.data.Team
import com.example.cricketgame.data.TossChoice

class Match(
    val format: MatchFormat,
    val playerTeam: Team,
    val cpuTeam: Team
) {
    var tossWinnerIsPlayer: Boolean = false
    var tossChoice: TossChoice = TossChoice.BAT

    lateinit var inningsOne: Innings
    var inningsTwo: Innings? = null

    val firstBattingTeam: Team
        get() = if ((tossWinnerIsPlayer && tossChoice == TossChoice.BAT) ||
            (!tossWinnerIsPlayer && tossChoice == TossChoice.BOWL)
        ) playerTeam else cpuTeam

    fun startInningsOne() {
        val battingFirst = firstBattingTeam
        val bowlingFirst = if (battingFirst == playerTeam) cpuTeam else playerTeam
        inningsOne = Innings(
            battingTeam = battingFirst,
            bowlingTeam = bowlingFirst,
            totalOvers = format.overs
        )
    }

    fun startInningsTwo() {
        val target = inningsOne.totalRuns + 1
        inningsTwo = Innings(
            battingTeam = inningsOne.bowlingTeam,
            bowlingTeam = inningsOne.battingTeam,
            totalOvers = format.overs,
            target = target
        )
    }

    val isMatchComplete: Boolean
        get() = inningsTwo?.isComplete == true

    val result: String
        get() {
            val two = inningsTwo ?: return "In progress"
            return when {
                two.totalRuns >= (two.target ?: Int.MAX_VALUE) ->
                    "${two.battingTeam.name} win by ${10 - two.wickets} wickets"
                two.totalRuns < (two.target?.minus(1) ?: 0) ->
                    "${two.bowlingTeam.name} win by ${(two.target ?: 1) - 1 - two.totalRuns} runs"
                else -> "Match tied"
            }
        }
}
