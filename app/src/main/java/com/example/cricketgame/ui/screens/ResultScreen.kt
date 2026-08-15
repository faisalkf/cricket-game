package com.example.cricketgame.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ResultScreen(result: String, onPlayAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Match Result")
        Spacer(Modifier.height(8.dp))
        Text(result.ifBlank { "Match complete" })
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPlayAgain) {
            Text("Play Again")
        }
    }
}
