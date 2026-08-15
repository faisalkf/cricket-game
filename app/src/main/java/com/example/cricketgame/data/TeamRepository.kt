package com.example.cricketgame.data

/**
 * Provides the 8 fixed v1 teams. Nation identities are real-sounding (India, Australia, etc.)
 * but every player name is fictional - avoids any real-player / board / league IP issues.
 *
 * Batting order (index 0-10) = descending batting skill.
 * Last 5 (index 6-10) = the fixed bowling lineup, isBowler = true, bowlingSkill ascending
 * (index 10 = best bowler on the team).
 */
object TeamRepository {

    private fun buildTeam(id: String, name: String, namePool: List<String>): Team {
        require(namePool.size == 11) { "Team $name needs exactly 11 names" }

        // Batting skill descends from a strong top order to weaker tail.
        val battingSkills = listOf(88, 85, 82, 78, 74, 68, 55, 48, 40, 32, 25)

        // Only last 5 are bowlers; bowling skill ascends toward index 10 (best bowler batting last).
        val bowlingSkills = listOf(0, 0, 0, 0, 0, 0, 60, 68, 75, 82, 90)

        val players = namePool.mapIndexed { index, playerName ->
            Player(
                id = "$id-p$index",
                name = playerName,
                battingSkill = battingSkills[index],
                bowlingSkill = bowlingSkills[index],
                isBowler = index >= 6
            )
        }
        return Team(id = id, name = name, players = players)
    }

    val allTeams: List<Team> by lazy {
        listOf(
            buildTeam(
                "IND", "India", listOf(
                    "R. Sharma", "V. Kohli", "S. Iyer", "K. Rahul", "H. Pandya",
                    "R. Jadeja", "A. Kumar", "M. Yadav", "B. Singh", "J. Bumrah", "S. Chahal"
                )
            ),
            buildTeam(
                "PAK", "Pakistan", listOf(
                    "B. Azam", "F. Zaman", "M. Rizwan", "S. Khan", "I. Malik",
                    "A. Khan", "H. Ali", "N. Shah", "F. Ahmed", "S. Afridi", "R. Yasir"
                )
            ),
            buildTeam(
                "AUS", "Australia", listOf(
                    "D. Warner", "T. Head", "S. Marsh", "M. Labuschagne", "G. Maxwell",
                    "M. Stoinis", "P. Cummins", "M. Starc", "A. Zampa", "J. Hazlewood", "N. Ellis"
                )
            ),
            buildTeam(
                "ENG", "England", listOf(
                    "J. Buttler", "D. Malan", "J. Root", "H. Brook", "B. Stokes",
                    "M. Ali", "S. Curran", "C. Woakes", "A. Rashid", "M. Wood", "J. Archer"
                )
            ),
            buildTeam(
                "RSA", "South Africa", listOf(
                    "Q. de Kock", "T. Bavuma", "A. Markram", "H. Klaasen", "D. Miller",
                    "M. Jansen", "K. Maharaj", "A. Nortje", "L. Ngidi", "T. Shamsi", "G. Coetzee"
                )
            ),
            buildTeam(
                "NZ", "New Zealand", listOf(
                    "D. Conway", "F. Allen", "K. Williamson", "D. Mitchell", "G. Phillips",
                    "J. Neesham", "M. Santner", "T. Southee", "T. Boult", "L. Ferguson", "I. Sodhi"
                )
            ),
            buildTeam(
                "SL", "Sri Lanka", listOf(
                    "P. Nissanka", "K. Mendis", "C. Asalanka", "D. Shanaka", "B. Fernando",
                    "W. Hasaranga", "D. Chameera", "M. Theekshana", "L. Kumara", "A. Perera", "N. Rajitha"
                )
            ),
            buildTeam(
                "AFG", "Afghanistan", listOf(
                    "R. Gurbaz", "I. Zadran", "R. Zurmatai", "H. Shahidi", "N. Zadran",
                    "M. Nabi", "A. Omarzai", "R. Khan", "N. Ahmadzai", "F. Farooqi", "M. Zadran"
                )
            ),
            buildTeam(
                "BAN", "Bangladesh", listOf(
                    "T. Hasan", "S. Al Hasan", "N. Islam", "M. Miraz", "T. Chowdhury",
                    "M. Riyad", "S. Rahman", "M. Mustafizur", "T. Ahmed", "H. Rana", "M. Nasum"
                )
            )
        )
    }
}
