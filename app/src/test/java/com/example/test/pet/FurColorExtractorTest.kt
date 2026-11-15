package com.example.test.pet

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FurColorExtractorTest {

    private val grass = 0x4F8A3C
    private val orange = 0xC8752E
    private val cream = 0xF2E6D2
    private val black = 0x1A1A1A

    /** w x h image: [background] everywhere, a dog rectangle in the middle with a light lower part. */
    private fun photo(background: Int, fur: Int, belly: Int?, dogFrom: Float = 0.25f, dogTo: Float = 0.75f): IntArray {
        val w = 40; val h = 40
        return IntArray(w * h) { i ->
            val x = i % w / w.toFloat()
            val y = i / w / h.toFloat()
            when {
                x !in dogFrom..dogTo || y !in dogFrom..dogTo -> background
                belly != null && y > dogFrom + (dogTo - dogFrom) * 0.7f -> belly
                else -> fur
            }
        }
    }

    private fun near(expected: Int, actual: Int, tolerance: Int = 12) =
        listOf(16, 8, 0).all { s -> abs((expected shr s and 0xFF) - (actual shr s and 0xFF)) <= tolerance }

    @Test
    fun ignoresBackground_findsFurAndBelly() {
        val c = FurColorExtractor.extract(photo(grass, orange, cream), 40, 40)
        assertTrue("main=%06x".format(c.main), near(orange, c.main))
        assertTrue("light=%06x".format(c.light), near(cream, c.light))
    }

    @Test
    fun dogFillsFrame_stillUsesFur() {
        // Close-up: the border is fur too, so background removal must not eat the dog.
        val c = FurColorExtractor.extract(photo(orange, orange, cream, 0f, 1f), 40, 40)
        assertTrue("main=%06x".format(c.main), near(orange, c.main))
    }

    @Test
    fun vivid_matchesHsvBoost() {
        // Values checked against Python colorsys with s*1.3, v*1.15.
        assertTrue(near(0xA66A3A, FurColorExtractor.vivid(0x906848), 2))
        assertTrue(near(0xCDB8A7, FurColorExtractor.vivid(0xB2A499), 2))
        assertTrue(near(0x1E1E1E, FurColorExtractor.vivid(0x1A1A1A), 2))   // grey stays grey
    }

    @Test
    fun singleColorDog_lightIsLighterMain() {
        val c = FurColorExtractor.extract(photo(grass, black, null), 40, 40)
        assertTrue("main=%06x".format(c.main), near(black, c.main))
        assertTrue(FurColorExtractor.luma(c.light) > FurColorExtractor.luma(c.main) + 50)
    }
}
