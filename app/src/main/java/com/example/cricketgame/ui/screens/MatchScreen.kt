package com.example.cricketgame.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cricketgame.data.BowlingTimingZones
import com.example.cricketgame.data.MatchFormat
import com.example.cricketgame.data.Team
import com.example.cricketgame.data.TimingQuality
import com.example.cricketgame.data.TossChoice
import com.example.cricketgame.sound.SoundEffects
import com.example.cricketgame.ui.scene3d.Pitch3DScene
import com.example.cricketgame.viewmodel.MatchUiState
import com.example.cricketgame.viewmodel.DeliveryPhase
import com.example.cricketgame.viewmodel.MatchViewModel
import com.example.cricketgame.viewmodel.OverSummary
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Real match screen, driven by [MatchViewModel]: a full-screen [Pitch3DScene] (SceneView/Filament
 * 3D, replacing the old Canvas-based PitchBackdrop - see its doc) as the base layer, shared by
 * both batting and bowling, with everything else floating on top of it in dedicated 2D Compose
 * space rather than pushing it down or overlaying the viewport - the scoreboard/timing HUD near
 * the top, the slider control near the bottom (BattingControls or BowlingControls, whichever side
 * the player's on), the outcome of each ball as a fading badge, and an end-of-over scorecard/
 * innings-break/match-over overlay when applicable.
 */
@Composable
fun MatchScreen(
    format: MatchFormat,
    playerTeam: Team,
    cpuTeam: Team,
    tossWinnerIsPlayer: Boolean,
    tossChoice: TossChoice?,
    onMatchComplete: (result: String) -> Unit
) {
    val viewModel: MatchViewModel = viewModel(
        factory = MatchViewModel.factory(format, playerTeam, cpuTeam, tossWinnerIsPlayer, tossChoice)
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val runUp by viewModel.runUp.collectAsStateWithLifecycle()

    // Fire the nav callback once, exactly when the ViewModel decides the match is over.
    LaunchedEffect(uiState.matchResult) {
        uiState.matchResult?.let { onMatchComplete(it) }
    }

    val soundEffects = remember { SoundEffects() }
    DisposableEffect(Unit) { onDispose { soundEffects.release() } }

    val isWicket = uiState.lastBallSummary?.startsWith("OUT") == true
    val isBoundarySix = uiState.lastBallRuns >= 6

    // ballSeq (not lastBallSummary) is the trigger key - two consecutive balls can produce
    // identical summary text, which wouldn't otherwise re-fire a keyed effect. Skipped at
    // ballSeq==0 so nothing plays/shakes on the very first composition, before any ball exists.
    LaunchedEffect(uiState.ballSeq) {
        if (uiState.ballSeq == 0) return@LaunchedEffect
        when {
            isWicket -> soundEffects.playWicket()
            isBoundarySix -> soundEffects.playBoundary()
            uiState.lastBallTimingQuality != null && uiState.lastBallTimingQuality != TimingQuality.RED ->
                soundEffects.playImpact() // contact was made but it wasn't a boundary/wicket
            // else: a total miss (RED, no contact) - deliberately silent
        }
    }

    // Brief, subtle screen shake - only for a six or a wicket, never on routine balls.
    val shakeOffset = rememberScreenShakeOffset(ballSeq = uiState.ballSeq, shouldShake = isWicket || isBoundarySix)

    // The just-played shot's pose/trajectory only makes sense while its outcome is showing.
    val batterShot = if (uiState.phase == DeliveryPhase.BALL_RESULT) batterShotFrom(uiState) else null
    // Ties the bowler's run-up/crease visual to the exact same no-ball threshold used to actually
    // resolve a no-ball (see PitchBackdrop/drawBowlerFigure's doc) - only meaningful while the
    // PLAYER is the one bowling; the CPU bowler's progress follows the unrelated batting sweep and
    // has no no-ball concept to sync to, so it keeps PitchBackdrop's 1f default there.
    val creaseProgress = if (!uiState.isPlayerBatting) BowlingTimingZones.LATE_RED_START else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(translationX = shakeOffset.x, translationY = shakeOffset.y)
    ) {
        Pitch3DScene(
            progress = runUp.progress,
            pitchLength = runUp.pitchLength,
            postPitchTilt = runUp.postPitchTilt,
            showBall = uiState.phase == DeliveryPhase.RUN_UP || uiState.phase == DeliveryPhase.BALL_RESULT,
            isPlayerBatting = uiState.isPlayerBatting,
            shot = batterShot,
            outcome = uiState.lastBallOutcome,
            ballSeq = uiState.ballSeq,
            creaseProgress = creaseProgress,
            modifier = Modifier.fillMaxSize()
        )

        ScoreboardOverlay(uiState, modifier = Modifier.align(Alignment.TopCenter))

        when (uiState.phase) {
            // Both phases keep the same controls mounted - the outcome of a delivery is
            // shown as an overlay on top rather than swapping to a different screen.
            DeliveryPhase.RUN_UP, DeliveryPhase.BALL_RESULT -> {
                if (uiState.isPlayerBatting) {
                    BattingControls(
                        runUpProgress = runUp.progress,
                        timingIndicator = runUp.quality,
                        onPlayShot = { direction -> viewModel.playBattingShot(direction) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                } else {
                    BowlingControls(
                        runUpProgress = runUp.progress,
                        bowlerName = uiState.bowlerName,
                        onDeliveryReleased = { direction, deliveryTiming ->
                            viewModel.bowlDelivery(direction, deliveryTiming)
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
            DeliveryPhase.OVER_BREAK -> {
                EndOfOverOverlay(
                    summary = uiState.overSummary,
                    onContinue = { viewModel.continueAfterOver() },
                    modifier = Modifier.matchParentSize()
                )
            }
            DeliveryPhase.INNINGS_BREAK -> {
                CenteredMessage("Innings break")
            }
            DeliveryPhase.MATCH_OVER -> {
                CenteredMessage(uiState.matchResult ?: "Match complete")
            }
        }

        OutcomeOverlay(
            visible = uiState.phase == DeliveryPhase.BALL_RESULT,
            summary = uiState.lastBallSummary,
            modifier = Modifier.matchParentSize()
        )
    }
}

/**
 * The floating scoreboard/status HUD - score, overs, target, who's bowling to whom, this over's
 * balls so far. Deliberately a thin, full-bleed strip pinned to the very top edge (no side
 * margins/rounded card - reads as a frame around the action, not a panel sitting on it) rather
 * than the old multi-line rounded card: that card ran ~100dp+ tall, which on the shared
 * full-screen [PitchBackdrop] directly overlapped the keeper (drawn at 4.5% of canvas height),
 * the far-end stumps (6%) and the batter's feet (10%) - all real figures, not incidental. Two
 * compact lines at small type sizes keep this at roughly a third of that height so it clears
 * that zone instead of sitting on top of it; the second line folds the bowler/striker names and
 * this-over ball log together rather than giving each its own row.
 */
@Composable
private fun ScoreboardOverlay(uiState: MatchUiState, modifier: Modifier = Modifier) {
    val targetSuffix = uiState.target?.let { "  Target: $it" } ?: ""
    val recentSuffix = if (uiState.recentBalls.isNotEmpty()) "  •  ${uiState.recentBalls.joinToString(" ")}" else ""
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xB3141414))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            "${uiState.battingTeamName} ${uiState.score}/${uiState.wickets}  •  ${uiState.oversText}$targetSuffix",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1
        )
        Text(
            "${uiState.bowlerName} to ${uiState.strikerName}$recentSuffix",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xCCFFFFFF),
            maxLines = 1
        )
    }
}

/** A simple centered message over a dimming scrim, shared by the innings-break/match-over states -
 *  both just show text over the (now atmospheric, static) pitch behind them. */
@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = Color.White)
    }
}

/**
 * The end-of-over scorecard break: a dimming scrim plus a centered card summarizing the over that
 * just finished (runs, ball-by-ball codes, running score/wickets), with a Continue button the
 * player must tap before the next over's first delivery starts - see
 * MatchViewModel.showOverBreak/continueAfterOver. [summary] is only ever null for a single
 * recomposition right as the phase flips (defensive - shouldn't linger), in which case this draws
 * nothing rather than crash on a stale/missing snapshot.
 */
@Composable
private fun EndOfOverOverlay(summary: OverSummary?, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    if (summary == null) return
    Box(modifier = modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1B1B1B), shape = RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Over ${summary.oversCompleted} complete", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text("This over: ${summary.runsThisOver} run${if (summary.runsThisOver == 1) "" else "s"}", color = Color.White)
            if (summary.ballCodes.isNotEmpty()) {
                Text(summary.ballCodes.joinToString(" "), color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Score: ${summary.totalScore}/${summary.totalWickets}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onContinue) { Text("Continue") }
        }
    }
}

/** Duration of the screen-shake effect - short and subtle by design, not disorienting. */
private const val SHAKE_DURATION_MS = 260

/**
 * A brief, decaying horizontal shake offset - only animated when [shouldShake] is true (a six
 * or a wicket), keyed on [ballSeq] so it restarts exactly once per new ball. Applied to the
 * whole screen's root Modifier via graphicsLayer, not just the pitch, for a bit more impact -
 * kept short (260ms) and low-amplitude so it reads as a jolt rather than something disorienting.
 */
@Composable
private fun rememberScreenShakeOffset(ballSeq: Int, shouldShake: Boolean): Offset {
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(ballSeq) {
        if (!shouldShake) {
            offset = Offset.Zero
            return@LaunchedEffect
        }
        var elapsed = 0L
        while (elapsed < SHAKE_DURATION_MS) {
            val t = elapsed / SHAKE_DURATION_MS.toFloat()
            val decay = 1f - t
            val dx = sin(t * 6 * Math.PI).toFloat() * 14f * decay
            offset = Offset(dx, 0f)
            delay(16)
            elapsed += 16
        }
        offset = Offset.Zero
    }
    return offset
}

/** Builds the just-played shot's pose/trajectory details from ui state, or null if incomplete. */
private fun batterShotFrom(uiState: MatchUiState): BatterShot? {
    val aggression = uiState.lastBallAggression ?: return null
    val timing = uiState.lastBallTimingQuality ?: return null
    return BatterShot(
        aggression, timing, uiState.lastBallDirection, uiState.lastBallRuns, uiState.lastBallOnStumps
    )
}

/**
 * A short-lived badge for the last ball's outcome ("1 run", "FOUR!", "OUT - Bowled!", ...) that
 * appears and fades over whatever's currently on screen, plus a quick red flash for a wicket.
 * Always composed (so AnimatedVisibility can animate both the enter AND the exit), just empty
 * while [visible] is false.
 *
 * Pinned to the top-END corner (below [ScoreboardOverlay]'s strip), not top-center: the batter
 * stands just left of horizontal center near the top of [PitchBackdrop] (batterCenterX is
 * w/2 - 100), so a center-anchored badge sat directly over the batter/keeper/stumps at exactly
 * the moment there's an outcome to show for them - the worst possible timing. A small corner
 * chip at a reduced type size (titleMedium, not headlineMedium) keeps it legible without
 * blanketing the middle of the pitch where the action actually is.
 */
@Composable
private fun OutcomeOverlay(visible: Boolean, summary: String?, modifier: Modifier = Modifier) {
    // The ViewModel clears lastBallSummary the instant the next delivery's RUN_UP begins, which
    // would otherwise blank the badge mid fade-out - keep showing the last real value instead.
    var displayedSummary by remember { mutableStateOf("") }
    LaunchedEffect(summary) {
        summary?.let { displayedSummary = it }
    }
    val isWicket = displayedSummary.startsWith("OUT")
    val isBoundary = displayedSummary == "FOUR!" || displayedSummary == "SIX!"
    val badgeColor = when {
        isWicket -> Color(0xFFD32F2F)
        isBoundary -> Color(0xFF2E7D32)
        displayedSummary.startsWith("No ball") -> Color(0xFFFF8F00)
        else -> Color(0xFF37474F)
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = visible && isWicket,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(500))
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x4DD32F2F)))
        }

        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(initialScale = 0.6f, animationSpec = tween(220)) + fadeIn(tween(160)),
            exit = scaleOut(targetScale = 0.7f, animationSpec = tween(450)) + fadeOut(tween(450))
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 52.dp, end = 12.dp)
                    .background(badgeColor, shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    displayedSummary,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}
