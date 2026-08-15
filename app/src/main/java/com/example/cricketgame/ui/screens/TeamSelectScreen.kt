package com.example.cricketgame.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.Team
import com.example.cricketgame.data.TeamRepository

@Composable
fun TeamSelectScreen(onTeamChosen: (Team) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Pick your team")
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(TeamRepository.allTeams) { team ->
                Button(
                    onClick = { onTeamChosen(team) },
                    modifier = Modifier.padding(4.dp).fillMaxWidth()
                ) {
                    Text(team.name)
                }
            }
        }
    }
}
