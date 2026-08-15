package com.example.cricketgame.data

/**
 * A single player.
 * - battingSkill: 1-99. Higher = wider effective GREEN timing window + higher six/four probability
 *   on aggressive green shots.
 * - bowlingSkill: 1-99. Only the last 5 players in a team's order (isBowler = true) have a
 *   meaningful bowlingSkill in v1 - they're the fixed bowling lineup.
 */
data class Player(
    val id: String,
    val name: String,
    val battingSkill: Int,
    val bowlingSkill: Int,
    val isBowler: Boolean
)
