package com.example.cricketgame.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cricketgame.data.TossChoice
import kotlin.random.Random

@Composable
fun TossScreen(onTossResolved: (won: Boolean, choice: TossChoice?) -> Unit) {
    var tossWon by remember { mutableStateOf<Boolean?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (tossWon == null) {
            Button(onClick = { tossWon = Random.nextBoolean() }) {
                Text("Flip the coin")
            }
        } else if (tossWon == true) {
            Text("You won the toss!")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onTossResolved(true, TossChoice.BAT) }, modifier = Modifier.fillMaxWidth()) {
                Text("Elect to Bat")
            }
            Button(onClick = { onTossResolved(true, TossChoice.BOWL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Elect to Bowl")
            }
            Button(onClick = { onTossResolved(true, null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Let CPU decide")
            }
        } else {
            Text("CPU won the toss and will decide.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onTossResolved(false, null) }) {
                Text("Continue")
            }
        }
    }
}
