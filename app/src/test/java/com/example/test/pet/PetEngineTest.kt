package com.example.test.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetEngineTest {

    @Test
    fun smoothTime_growsOnlyWhileDrivingSafely() {
        var s = PetState()
        s = PetEngine.tick(s, DriveLevel.SAFE, 60f)
        s = PetEngine.tick(s, DriveLevel.STOPPED, 60f)   // paused, not reset
        assertEquals(60f, s.smoothSec, 0.01f)
        s = PetEngine.tick(s, DriveLevel.OVER, 1f)
        assertEquals(0f, s.smoothSec, 0.01f)
    }

    @Test
    fun tenSmoothMinutes_giveSnack() {
        var s = PetState(snacks = 0, happiness = 50f)
        repeat(10 * 60) { s = PetEngine.tick(s, DriveLevel.SAFE, 1f) }
        assertTrue(PetEngine.smoothRewardDue(s))
        s = PetEngine.rewardSmooth(s)
        assertEquals(1, s.snacks)
        assertFalse(PetEngine.smoothRewardDue(s))
    }

    @Test
    fun snackReward_respectsMax() {
        val s = PetEngine.rewardSmooth(PetState(snacks = PetEngine.MAX_SNACKS, smoothSec = PetEngine.SMOOTH_SEC_PER_SNACK))
        assertEquals(PetEngine.MAX_SNACKS, s.snacks)
    }

    @Test
    fun harshEvent_resetsStreak_brakeHurtsMore() {
        val s = PetState(happiness = 50f, smoothSec = 300f)
        val accel = PetEngine.onHarshEvent(s, HarshEvent.ACCEL)
        val brake = PetEngine.onHarshEvent(s, HarshEvent.BRAKE)
        assertEquals(0f, accel.smoothSec, 0f)
        assertEquals(0f, brake.smoothSec, 0f)
        assertTrue(brake.happiness < accel.happiness)
    }

    @Test
    fun speedingDistance_doesNotEarnSnacks() {
        val s = PetEngine.onDistance(PetState(), 10f, DriveLevel.DANGER)
        assertEquals(0, s.snacks)
        assertEquals(0f, s.safeKm, 0f)
    }
}
