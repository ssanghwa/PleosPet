package com.example.test.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HarshDetectorTest {

    /** Feeds (ms, kph) pairs and returns every event that fired. */
    private fun run(vararg samples: Pair<Long, Float>): List<HarshEvent> {
        val d = HarshDetector()
        return samples.mapNotNull { (t, v) -> d.onSpeed(t, v) }
    }

    @Test
    fun gentleAcceleration_noEvent() {
        // 0 -> 50 km/h over 10 s = 5 km/h per second
        val samples = (0..20).map { i -> i * 500L to i * 2.5f }.toTypedArray()
        assertEquals(emptyList<HarshEvent>(), run(*samples))
    }

    @Test
    fun hardAcceleration_isAccel() {
        val events = run(0L to 10f, 500L to 18f, 1000L to 26f)   // 16 km/h in 1 s
        assertEquals(listOf(HarshEvent.ACCEL), events)
    }

    @Test
    fun hardBraking_isBrake() {
        val events = run(0L to 60f, 1000L to 60f, 1500L to 50f, 2000L to 40f)   // -20 km/h in 1 s
        assertEquals(listOf(HarshEvent.BRAKE), events)
    }

    @Test
    fun speedOnlyReportedOnChange_holdsLastValue() {
        // Cruising at 60 with no updates for 10 s, then a sudden stop is reported once.
        val events = run(0L to 60f, 10_000L to 30f)
        assertEquals(listOf(HarshEvent.BRAKE), events)
    }

    @Test
    fun slowCrawl_ignored() {
        val events = run(0L to 0f, 1000L to 4f)
        assertEquals(emptyList<HarshEvent>(), events)
    }

    @Test
    fun cooldown_countsOneStopOnce() {
        val events = run(0L to 80f, 1000L to 60f, 1500L to 45f, 2000L to 30f, 2500L to 15f, 3000L to 0f)
        assertEquals(listOf(HarshEvent.BRAKE), events)
    }

    @Test
    fun firstSample_noHistory() {
        assertNull(HarshDetector().onSpeed(0L, 100f))
    }
}
