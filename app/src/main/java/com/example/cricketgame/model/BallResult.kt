package com.example.cricketgame.model

import com.example.cricketgame.data.*

data class BallResult(
    val batsman: Player,
    val bowler: Player,
    val timingQuality: TimingQuality,
    val aggression: Aggression,
    val tiltDirection: Float,       // -1.0 (leg side) .. +1.0 (off side)
    val pitchLine: PitchLine,
    val pitchLength: PitchLength,
    val onStumps: Boolean,          // did the delivery actually end up hitting the stumps line?
    val fieldMode: FieldMode,
    val outcome: BallOutcome,
    val runsScored: Int
)
