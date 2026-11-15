package com.example.test.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DogTalkerTest {

    @Test
    fun keepsFirstLine_withoutQuotes() {
        assertEquals("깜짝 놀랐어요, 천천히 가요!", DogTalker.clean("\"깜짝 놀랐어요, 천천히 가요!\"\n(설명: 놀란 강아지)"))
    }

    @Test
    fun skipsBlankLines_andDash() {
        assertEquals("간식 고마워요!", DogTalker.clean("\n\n- 간식 고마워요!"))
    }

    @Test
    fun rejectsEmptyOrTooLong() {
        assertNull(DogTalker.clean("   \n  "))
        assertNull(DogTalker.clean("가".repeat(DogTalker.MAX_CHARS + 1)))
    }
}
