package com.example.cricketgame.data

/**
 * players is ordered as the batting order: index 0 = strongest batsman, descending skill,
 * last 5 entries (indices 6-10) double as the bowling lineup (isBowler = true),
 * with bowlingSkill ascending toward the very last player (best bowler batting last).
 */
data class Team(
    val id: String,
    val name: String,
    val players: List<Player>
) {
    val bowlers: List<Player> get() = players.filter { it.isBowler }
}
