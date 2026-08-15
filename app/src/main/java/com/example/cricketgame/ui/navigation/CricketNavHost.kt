package com.example.cricketgame.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cricketgame.data.MatchFormat
import com.example.cricketgame.data.Team
import com.example.cricketgame.data.TeamRepository
import com.example.cricketgame.data.TossChoice
import com.example.cricketgame.ui.screens.*

object Routes {
    const val FORMAT_SELECT = "format_select"
    const val TEAM_SELECT = "team_select"
    const val TOSS = "toss"
    const val MATCH = "match"       // hosts batting/bowling/CPU-innings sub-states
    const val RESULT = "result"
}

@Composable
fun CricketNavHost() {
    val navController = rememberNavController()

    // Selections carried from the setup screens through to the match itself. Kept here (rather
    // than in each screen) since Compose keeps this composable - and therefore this state -
    // alive across navigate() calls within the same NavHost.
    var format by remember { mutableStateOf(MatchFormat.T20) }
    var playerTeam by remember { mutableStateOf<Team?>(null) }
    var cpuTeam by remember { mutableStateOf<Team?>(null) }
    var tossWinnerIsPlayer by remember { mutableStateOf(true) }
    var tossChoice by remember { mutableStateOf<TossChoice?>(TossChoice.BAT) }
    var matchResult by remember { mutableStateOf("") }

    NavHost(navController = navController, startDestination = Routes.FORMAT_SELECT) {
        composable(Routes.FORMAT_SELECT) {
            FormatSelectScreen(onFormatChosen = {
                format = it
                navController.navigate(Routes.TEAM_SELECT)
            })
        }
        composable(Routes.TEAM_SELECT) {
            TeamSelectScreen(onTeamChosen = { team ->
                playerTeam = team
                cpuTeam = TeamRepository.allTeams.filter { it.id != team.id }.random()
                navController.navigate(Routes.TOSS)
            })
        }
        composable(Routes.TOSS) {
            TossScreen(onTossResolved = { won, choice ->
                tossWinnerIsPlayer = won
                tossChoice = choice
                navController.navigate(Routes.MATCH)
            })
        }

        composable(Routes.MATCH) {
            val pt = playerTeam
            val ct = cpuTeam
            if (pt != null && ct != null) {
                MatchScreen(
                    format = format,
                    playerTeam = pt,
                    cpuTeam = ct,
                    tossWinnerIsPlayer = tossWinnerIsPlayer,
                    tossChoice = tossChoice,
                    onMatchComplete = { result ->
                        matchResult = result
                        navController.navigate(Routes.RESULT)
                    }
                )
            }
        }
        composable(Routes.RESULT) {
            ResultScreen(
                result = matchResult,
                onPlayAgain = {
                    playerTeam = null
                    cpuTeam = null
                    navController.popBackStack(Routes.FORMAT_SELECT, inclusive = false)
                }
            )
        }
    }
}
