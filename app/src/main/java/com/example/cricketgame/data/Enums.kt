package com.example.cricketgame.data

enum class MatchFormat(val overs: Int) {
    T5(5),
    T10(10),
    T20(20)
}

enum class TossChoice { BAT, BOWL }

enum class Aggression { DEFENSIVE, GROUND, AERIAL }

enum class TimingQuality { RED, YELLOW, GREEN }

/**
 * Which side of the pitch a unified-slider release position falls on - the SAME left/right split
 * for both batting (shot direction) and bowling (delivery line), splitting the -1f..1f range at
 * dead center: negative = ON_SIDE (leg side), non-negative = OFF_SIDE. This is a plain 2-way
 * split, unlike [PitchLine]'s 3-way leg/stumps/off grid (which still separately governs whether a
 * bowling delivery ends up on the stumps) - it exists purely to answer "which half did this
 * release land in", for [MatchViewModel]'s on/off-side mismatch penalty (batting shot side vs.
 * actual delivery side) and MatchVisuals' distinct on/off-side bat-swing shapes.
 */
enum class ShotSide { ON_SIDE, OFF_SIDE }

/** See [ShotSide] - splits a -1f..1f release direction at dead center (0f). */
fun sideFromDirection(direction: Float): ShotSide = if (direction < 0f) ShotSide.ON_SIDE else ShotSide.OFF_SIDE

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
 * Precision tier within a sweep's GREEN zone, for both the batting and bowling release sweeps
 * (see [BattingTimingZones.greenTier]/[BowlingTimingZones.greenTier]) - LIGHT is the broad "good
 * timing" bulk of the zone, DARK a thin sliver hugging its exact center for an extra-precise
 * release. Only meaningful when the release actually landed in GREEN in the first place.
 *
 * Drives the unified slider control's outcome mapping: batting's LIGHT -> ground-shot tier, DARK
 * -> six tier (see MatchViewModel.battingAggressionFor); bowling's LIGHT -> the bowler's standard
 * best ball, DARK -> a tougher "perfect ball" tier (see BowlingResolver, MatchViewModel's CPU
 * batting-timing roll).
 */
enum class GreenTier { LIGHT, DARK }

/**
 * Cut points (0f..1f) for the bowling release sweep. Shared by MatchViewModel (to classify the
 * actual release) and BowlingControls (to draw the matching gauge bands), so they can never
 * drift out of sync.
 */
object BowlingTimingZones {
    const val YELLOW_START = 0.30f
    const val GREEN_START = 0.50f
    const val LATE_RED_START = 0.70f

    /** Half-width (either side of [GREEN_CENTER]) of the DARK "perfect ball" sliver - a thin
     *  fraction of the whole GREEN zone's 0.20 width. Public (not private) so the gauge visuals
     *  can draw the sliver where the player can actually see and aim for it. */
    const val DARK_GREEN_HALF_WIDTH = 0.015f
    const val GREEN_CENTER = (GREEN_START + LATE_RED_START) / 2f

    fun classify(progress: Float): DeliveryTiming = when {
        progress < YELLOW_START -> DeliveryTiming.EARLY_RED
        progress < GREEN_START -> DeliveryTiming.YELLOW
        progress < LATE_RED_START -> DeliveryTiming.GREEN
        else -> DeliveryTiming.LATE_RED
    }

    /** Only meaningful when [classify] returns GREEN - which precision tier within it. */
    fun greenTier(progress: Float): GreenTier =
        if (kotlin.math.abs(progress - GREEN_CENTER) <= DARK_GREEN_HALF_WIDTH) GreenTier.DARK else GreenTier.LIGHT
}

/**
 * Cut points (0f..1f) for BATTING's symmetric RED-YELLOW-GREEN-YELLOW-RED release sweep - the
 * mirror of [BowlingTimingZones] for the other control. Shared by MatchViewModel (release
 * classification + aggression-tier mapping) and the batting gauge bands (MatchVisuals'
 * BattingGaugeBands), so they can't drift out of sync either.
 */
object BattingTimingZones {
    const val YELLOW_START = 0.2f
    const val GREEN_START = 0.35f
    const val GREEN_END = 0.65f
    const val RED_START = 0.8f

    /** Half-width of the DARK "six tier" sliver, either side of dead center (0.5) - a thin
     *  fraction of the whole GREEN zone's 0.30 width. Public (not private) so the gauge visuals
     *  can draw the sliver where the player can actually see and aim for it. */
    const val DARK_GREEN_HALF_WIDTH = 0.02f
    const val GREEN_CENTER = (GREEN_START + GREEN_END) / 2f

    fun classify(progress: Float): TimingQuality = when {
        progress < YELLOW_START || progress > RED_START -> TimingQuality.RED
        progress < GREEN_START || progress > GREEN_END -> TimingQuality.YELLOW
        else -> TimingQuality.GREEN
    }

    /** Only meaningful when [classify] returns GREEN - which precision tier within it. */
    fun greenTier(progress: Float): GreenTier =
        if (kotlin.math.abs(progress - GREEN_CENTER) <= DARK_GREEN_HALF_WIDTH) GreenTier.DARK else GreenTier.LIGHT
}
