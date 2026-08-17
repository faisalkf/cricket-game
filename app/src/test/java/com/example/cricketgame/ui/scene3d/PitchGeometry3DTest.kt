package com.example.cricketgame.ui.scene3d

import com.example.cricketgame.data.BowlingTimingZones
import com.example.cricketgame.data.PitchLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for the pure math behind the 3D pitch - no Android/Filament runtime needed
 * (`./gradlew test`, not `connectedAndroidTest`), which matters a lot here: this sandbox has no
 * usable Android emulator (no KVM access - see the migration's commit history/PR notes), so this
 * is the only real, automated verification the trickiest part of the 3D migration gets. The
 * no-ball/crease-sync tests in particular are standing in for the visual check a real device
 * would normally give you.
 */
class PitchGeometry3DTest {
    private val creaseProgress = BowlingTimingZones.LATE_RED_START // 0.70 - the real no-ball threshold

    @Test
    fun `bowler starts the run-up at RUN_UP_START_Z`() {
        assertEquals(PitchGeometry3D.RUN_UP_START_Z, PitchGeometry3D.bowlerFeetZ(0f, creaseProgress), 1e-4f)
    }

    @Test
    fun `bowler reaches the crease exactly when progress hits creaseProgress`() {
        // This is THE assertion the migration brief calls out as the highest-risk part: the
        // bowler's animated feet position must cross the bowling crease at the exact same
        // progress value where BowlingTimingZones.classify() flips into LATE_RED (a no-ball) -
        // same shared progress, not two clocks that could drift apart.
        val feetZ = PitchGeometry3D.bowlerFeetZ(creaseProgress, creaseProgress)
        assertEquals(PitchGeometry3D.BOWLING_CREASE_Z, feetZ, 1e-4f)
    }

    @Test
    fun `releasing before creaseProgress never oversteps the crease`() {
        val justBefore = creaseProgress - 0.05f
        val feetZ = PitchGeometry3D.bowlerFeetZ(justBefore, creaseProgress)
        assertTrue("feet Z ($feetZ) should be >= the crease (${PitchGeometry3D.BOWLING_CREASE_Z}) before creaseProgress",
            feetZ >= PitchGeometry3D.BOWLING_CREASE_Z)
    }

    @Test
    fun `releasing after creaseProgress - a no-ball - visibly oversteps the crease`() {
        val pastCrease = creaseProgress + 0.15f // still within LATE_RED (which runs to 1f)
        val feetZ = PitchGeometry3D.bowlerFeetZ(pastCrease, creaseProgress)
        assertTrue("feet Z ($feetZ) should be < the crease (${PitchGeometry3D.BOWLING_CREASE_Z}) past creaseProgress - the foot-fault visual",
            feetZ < PitchGeometry3D.BOWLING_CREASE_Z)
    }

    @Test
    fun `overstep is bounded, not runaway, at full progress`() {
        val feetZ = PitchGeometry3D.bowlerFeetZ(1f, creaseProgress)
        val runUpDistance = PitchGeometry3D.RUN_UP_START_Z - PitchGeometry3D.BOWLING_CREASE_Z
        // Never oversteps by more than the capped fraction of the run-up's own length.
        assertTrue(PitchGeometry3D.BOWLING_CREASE_Z - feetZ <= runUpDistance * 0.18f + 1e-3f)
    }

    @Test
    fun `with no no-ball concept (creaseProgress = 1f) the bowler arrives exactly at the crease and no further`() {
        // The CPU bowler on the batting screen has no no-ball rule to sync to - PitchBackdrop's
        // caller leaves creaseProgress at its 1f default there (see MatchScreen's doc).
        val feetZ = PitchGeometry3D.bowlerFeetZ(1f, 1f)
        assertEquals(PitchGeometry3D.BOWLING_CREASE_Z, feetZ, 1e-4f)
    }

    @Test
    fun `bowler feet Z is monotonically non-increasing as progress advances`() {
        var previous = PitchGeometry3D.bowlerFeetZ(0f, creaseProgress)
        var t = 0f
        while (t <= 1f) {
            val current = PitchGeometry3D.bowlerFeetZ(t, creaseProgress)
            assertTrue("feet Z should never move backward up-field as progress advances", current <= previous + 1e-4f)
            previous = current
            t += 0.05f
        }
    }

    @Test
    fun `ball is released from the bowling crease end`() {
        val pos = PitchGeometry3D.ballPreContactPosition(0f, PitchLength.GOOD_LENGTH, postPitchTilt = 0f)
        assertEquals(PitchGeometry3D.BOWLING_CREASE_Z, pos.z, 1e-3f)
    }

    @Test
    fun `ball arrives at the batter end (Z = 0) when progress completes`() {
        val pos = PitchGeometry3D.ballPreContactPosition(1f, PitchLength.GOOD_LENGTH, postPitchTilt = 0f)
        assertEquals(0f, pos.z, 1e-3f)
    }

    @Test
    fun `ball touches the ground exactly at its length's bounce point`() {
        for (length in PitchLength.entries) {
            val bounce = PitchGeometry3D.bouncePointProgress(length)
            val pos = PitchGeometry3D.ballPreContactPosition(bounce, length, postPitchTilt = 0f)
            assertEquals("$length should touch down (Y=0) at its own bounce point", 0f, pos.y, 1e-3f)
        }
    }

    @Test
    fun `ball stays dead straight (X = 0) before the bounce regardless of postPitchTilt`() {
        val bounce = PitchGeometry3D.bouncePointProgress(PitchLength.GOOD_LENGTH)
        val pos = PitchGeometry3D.ballPreContactPosition(bounce / 2f, PitchLength.GOOD_LENGTH, postPitchTilt = 1f)
        assertEquals(0f, pos.x, 1e-4f)
    }

    @Test
    fun `ball drifts toward postPitchTilt's side after the bounce`() {
        val pos = PitchGeometry3D.ballPreContactPosition(1f, PitchLength.GOOD_LENGTH, postPitchTilt = 1f)
        assertTrue("positive postPitchTilt (off side) should drift the ball to positive X", pos.x > 0f)

        val posLeg = PitchGeometry3D.ballPreContactPosition(1f, PitchLength.GOOD_LENGTH, postPitchTilt = -1f)
        assertTrue("negative postPitchTilt (leg side) should drift the ball to negative X", posLeg.x < 0f)
    }
}
