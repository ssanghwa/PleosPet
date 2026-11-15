package com.example.test.pet

import java.time.LocalDate

/** Current weather, as far as the menu cares. [code] is a WMO weather code (Open-Meteo). */
data class Weather(val tempC: Float, val code: Int) {
    val label: String
        get() = when (code) {
            0 -> "맑음"
            1, 2 -> "구름 조금"
            3 -> "흐림"
            45, 48 -> "안개"
            in 51..67, in 80..82 -> "비"
            in 71..77, 85, 86 -> "눈"
            in 95..99 -> "뇌우"
            else -> "흐림"
        }
}

enum class MenuMood { RAINY, SNOWY, COLD, HOT, MILD }

data class DinnerPick(val mood: MenuMood, val menus: List<String>, val reason: String)

/**
 * Dinner suggestion for the ride home, from the weather and the season.
 * Pure rules so it works without network (season only) and is easy to tune.
 */
object DinnerMenu {

    /** 퇴근길로 보는 시간대. 이 시간에 집에 도착하면 저녁 메뉴를 추천합니다. */
    val DINNER_HOURS = 15..22

    fun mood(weather: Weather?, month: Int): MenuMood {
        if (weather != null) {
            when (weather.code) {
                in 71..77, 85, 86 -> return MenuMood.SNOWY
                in 51..67, in 80..82, in 95..99 -> return MenuMood.RAINY
            }
            if (weather.tempC <= 5f) return MenuMood.COLD
            if (weather.tempC >= 27f) return MenuMood.HOT
            return MenuMood.MILD
        }
        // 날씨를 못 받으면 계절로만 판단합니다.
        return when (month) {
            12, 1, 2 -> MenuMood.COLD
            6, 7, 8 -> MenuMood.HOT
            else -> MenuMood.MILD
        }
    }

    fun recommend(weather: Weather?, date: LocalDate): DinnerPick {
        val month = date.monthValue
        val mood = mood(weather, month)
        val (pool, reason) = when (mood) {
            MenuMood.RAINY -> listOf("김치전", "해물파전", "칼국수", "수제비", "감자전") to
                "비 오는 날엔 따끈한 국물이랑 전이 최고예요"
            MenuMood.SNOWY -> listOf("부대찌개", "어묵탕", "샤브샤브", "만둣국", "김치찌개") to
                "눈 오는 날엔 보글보글 끓는 찌개 어때요?"
            MenuMood.COLD -> listOf("김치찌개", "순두부찌개", "감자탕", "굴국밥", "뼈해장국") to
                "쌀쌀하니까 뜨끈한 국물로 몸 녹여요"
            MenuMood.HOT -> listOf("물냉면", "콩국수", "비빔국수", "메밀소바", "물회") to
                "더운 날엔 시원한 면 한 그릇!"
            MenuMood.MILD -> seasonal(month)
        }
        // 같은 날에는 같은 추천이 나오도록 날짜로 고릅니다.
        val start = Math.floorMod(date.toEpochDay(), pool.size.toLong()).toInt()
        val menus = List(2) { pool[(start + it) % pool.size] }
        return DinnerPick(mood, menus, reason)
    }

    private fun seasonal(month: Int): Pair<List<String>, String> = when (month) {
        3, 4, 5 -> listOf("냉이 된장국", "주꾸미볶음", "봄나물 비빔밥", "도다리쑥국") to
            "봄 제철 음식으로 기운 내요"
        6, 7, 8 -> listOf("삼계탕", "장어구이", "오이냉국", "열무국수") to
            "여름 보양식으로 힘내요"
        9, 10, 11 -> listOf("전어구이", "꽃게탕", "버섯전골", "고등어구이") to
            "가을 제철 음식이 맛있을 때예요"
        else -> listOf("방어회", "과메기", "굴국밥", "동태찌개") to
            "겨울 제철 음식으로 든든하게"
    }
}
