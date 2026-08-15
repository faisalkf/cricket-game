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

/**
 * Where in the bowler's single-pass release sweep (RED -> YELLOW -> GREEN -> RED) the player let
 * go. Unlike batting's [TimingQuality], the two RED zones are meaningfully different: EARLY_RED
 * is an underpowered/weak delivery, while LATE_RED is a no-ball (released past GREEN, with no
 * yellow buffer before it - a rushed release that gives the batsman less reaction time).
 */
enum class DeliveryTiming { EARLY_RED, YELLOW, GREEN, LATE_RED }

/**
 * Cut points (0f..1f) for the bowling release sweep. Shared by MatchViewModel (to classify the
 * actual release) and BowlingControls (to draw the matching gauge bands), so they can never
 * drift out of sync.
 */
object BowlingTimingZones {
    const val YELLOW_START = 0.30f
    const val GREEN_START = 0.50f
    const val LATE_RED_START = 0.70f

    fun classify(progress: Float): DeliveryTiming = when {
        progress < YELLOW_START -> DeliveryTiming.EARLY_RED
        progress < GREEN_START -> DeliveryTiming.YELLOW
        progress < LATE_RED_START -> DeliveryTiming.GREEN
        else -> DeliveryTiming.LATE_RED
    }
}
