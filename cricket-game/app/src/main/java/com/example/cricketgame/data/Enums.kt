package com.example.cricketgame.data

enum class MatchFormat(val overs: Int) {
    T5(5),
    T10(10),
    T20(20)
}

enum class TossChoice { BAT, BOWL }

enum class Aggression { DEFENSIVE, GROUND, AERIAL }

enum class TimingQuality { RED, YELLOW, GREEN }

enum class FieldMode { ATTACKING, DEFENSIVE }

enum class BallOutcome {
    DOT,
    RUN_1,
    RUN_2,
    RUN_3,
    FOUR,
    SIX,
    WICKET_BOWLED,
    WICKET_LBW,
    WICKET_CAUGHT
}

/** Where the bowler aimed to pitch the ball - simple 3x3 grid for v1 (line x length). */
enum class PitchLine { OUTSIDE_OFF, ON_STUMPS, OUTSIDE_LEG }
enum class PitchLength { SHORT, GOOD_LENGTH, FULL_YORKER }
