package com.example.cricketgame.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

    NavHost(navController = navController, startDestination = Routes.FORMAT_SELECT) {
        composable(Routes.FORMAT_SELECT) {
            FormatSelectScreen(onFormatChosen = { navController.navigate(Routes.TEAM_SELECT) })
        }
        composable(Routes.TEAM_SELECT) {
            TeamSelectScreen(onTeamChosen = { navController.navigate(Routes.TOSS) })
        }
        composable(Routes.TOSS) {
    TossScreen(onTossResolved = { won, choice ->
        navController.navigate(Routes.MATCH)
    })
        }

        composable(Routes.MATCH) {
            MatchScreen(onMatchComplete = { navController.navigate(Routes.RESULT) })
        }
        composable(Routes.RESULT) {
            ResultScreen(onPlayAgain = {
                navController.popBackStack(Routes.FORMAT_SELECT, inclusive = false)
            })
        }
    }
}
