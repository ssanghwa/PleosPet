package com.example.test.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

class DinnerMenuTest {

    private val october = LocalDate.of(2026, 10, 23)

    @Test
    fun rainBeatsTemperature() {
        assertEquals(MenuMood.RAINY, DinnerMenu.mood(Weather(30f, 61), 7))
    }

    @Test
    fun snow() {
        assertEquals(MenuMood.SNOWY, DinnerMenu.mood(Weather(-2f, 73), 1))
    }

    @Test
    fun temperatureBands() {
        assertEquals(MenuMood.COLD, DinnerMenu.mood(Weather(3f, 0), 11))
        assertEquals(MenuMood.HOT, DinnerMenu.mood(Weather(31f, 1), 8))
        assertEquals(MenuMood.MILD, DinnerMenu.mood(Weather(18f, 2), 10))
    }

    @Test
    fun noWeather_fallsBackToSeason() {
        assertEquals(MenuMood.COLD, DinnerMenu.mood(null, 1))
        assertEquals(MenuMood.HOT, DinnerMenu.mood(null, 7))
        assertEquals(MenuMood.MILD, DinnerMenu.mood(null, 10))
    }

    @Test
    fun sameDay_sameMenus_nextDay_rotates() {
        val w = Weather(12f, 1)
        val today = DinnerMenu.recommend(w, october)
        assertEquals(today, DinnerMenu.recommend(w, october))
        assertEquals(2, today.menus.size)
        assertNotEquals(today.menus[0], today.menus[1])
        assertNotEquals(today.menus, DinnerMenu.recommend(w, october.plusDays(1)).menus)
    }

    @Test
    fun weatherLabel() {
        assertEquals("비", Weather(10f, 63).label)
        assertEquals("맑음", Weather(10f, 0).label)
    }
}
