package com.example.test.pet

import kotlin.math.min

/** How the car is being driven right now, judged against the speed limit. */
enum class DriveLevel { STOPPED, SAFE, OVER, DANGER }

enum class Mood { HAPPY, NORMAL, HUNGRY, NERVOUS, EXHAUSTED }

data class PetState(
    val fullness: Float = 70f,      // 0..100, 밥을 먹으면 오르고 달리면 줄어듦
    val energy: Float = 100f,       // 0..100, 과속하면 빠르게 줄어듦
    val happiness: Float = 70f,     // 0..100, 급가속·급제동에 줄고 간식에 오름
    val safeKm: Float = 0f,         // 안전하게 달린 누적 거리 → 성장
    val snacks: Int = 0,
    val snackProgressKm: Float = 0f,
    val mealsToday: Int = 0,
    val mealDay: Long = 0L,         // mealsToday 를 센 날 (epoch day)
    val smoothSec: Float = 0f,      // 급가속·급제동·과속 없이 달린 연속 시간
)

/**
 * Pet rules. Pure functions only, so they are easy to tune and test without a car.
 */
object PetEngine {

    // 도로 종류를 아직 모르므로 하나의 기준 제한속도를 씁니다.
    const val DEFAULT_SPEED_LIMIT_KPH = 100f

    const val MAX_MEALS_PER_DAY = 3
    const val MAX_SNACKS = 5
    const val KM_PER_SNACK = 5f
    const val SMOOTH_SEC_PER_SNACK = 10 * 60f
    const val KM_TO_FULL_SIZE = 1000f
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 1.0f

    fun classify(speedKph: Float, limitKph: Float = DEFAULT_SPEED_LIMIT_KPH): DriveLevel = when {
        speedKph < 1f -> DriveLevel.STOPPED
        speedKph <= limitKph * 1.1f -> DriveLevel.SAFE
        speedKph <= limitKph * 1.2f -> DriveLevel.OVER
        else -> DriveLevel.DANGER
    }

    /**
     * Time passing: energy follows how hard the car is driven, hunger grows while moving.
     * Rates are per minute so they read like real driving:
     * 위험 과속을 계속하면 약 10분, 과속 주의는 약 40분이면 체력이 바닥납니다.
     */
    fun tick(s: PetState, level: DriveLevel, dtSec: Float): PetState {
        val energyPerMin = when (level) {
            DriveLevel.STOPPED -> 6f
            DriveLevel.SAFE -> 1f
            DriveLevel.OVER -> -2.5f
            DriveLevel.DANGER -> -10f
        }
        val happinessPerMin = when (level) {
            DriveLevel.SAFE -> 1f
            DriveLevel.OVER -> -2f
            DriveLevel.DANGER -> -6f
            DriveLevel.STOPPED -> 0f
        }
        val energyRate = energyPerMin / 60f
        val happinessRate = happinessPerMin / 60f
        val hungerRate = if (level == DriveLevel.STOPPED) 0.005f else 0.01f
        // 부드러운 운전 시간은 안전 주행 중에만 쌓이고, 정차 중에는 멈추며, 과속하면 처음부터.
        val smooth = when (level) {
            DriveLevel.SAFE -> s.smoothSec + dtSec
            DriveLevel.STOPPED -> s.smoothSec
            DriveLevel.OVER, DriveLevel.DANGER -> 0f
        }
        return s.copy(
            energy = clamp(s.energy + energyRate * dtSec),
            happiness = clamp(s.happiness + happinessRate * dtSec),
            fullness = clamp(s.fullness - hungerRate * dtSec),
            smoothSec = smooth,
        )
    }

    fun smoothRewardDue(s: PetState): Boolean = s.smoothSec >= SMOOTH_SEC_PER_SNACK

    /** 10분 연속 부드러운 운전 보상: 간식 하나와 행복. */
    fun rewardSmooth(s: PetState): PetState = s.copy(
        smoothSec = s.smoothSec - SMOOTH_SEC_PER_SNACK,
        snacks = min(s.snacks + 1, MAX_SNACKS),
        happiness = clamp(s.happiness + 5f),
    )

    /** Distance only counts toward growth and snacks when it was driven safely. */
    fun onDistance(s: PetState, km: Float, level: DriveLevel): PetState {
        if (km <= 0f) return s
        val hungrier = s.copy(fullness = clamp(s.fullness - km * 0.5f))
        if (level == DriveLevel.OVER || level == DriveLevel.DANGER) return hungrier

        var progress = s.snackProgressKm + km
        var snacks = s.snacks
        while (progress >= KM_PER_SNACK) {
            progress -= KM_PER_SNACK
            snacks = min(snacks + 1, MAX_SNACKS)
        }
        return hungrier.copy(safeKm = s.safeKm + km, snacks = snacks, snackProgressKm = progress)
    }

    /** 급제동이 더 놀랍니다. 어느 쪽이든 부드러운 운전 연속 시간은 처음부터. */
    fun onHarshEvent(s: PetState, event: HarshEvent): PetState = when (event) {
        HarshEvent.ACCEL -> s.copy(happiness = clamp(s.happiness - 5f), energy = clamp(s.energy - 1f), smoothSec = 0f)
        HarshEvent.BRAKE -> s.copy(happiness = clamp(s.happiness - 8f), energy = clamp(s.energy - 2f), smoothSec = 0f)
    }

    fun mealsLeft(s: PetState, today: Long): Int =
        MAX_MEALS_PER_DAY - if (s.mealDay == today) s.mealsToday else 0

    /** Returns null when no meal is left today. Gear check is the caller's job. */
    fun feed(s: PetState, today: Long): PetState? {
        if (mealsLeft(s, today) <= 0) return null
        val eaten = if (s.mealDay == today) s.mealsToday else 0
        return s.copy(
            fullness = clamp(s.fullness + 35f),
            energy = clamp(s.energy + 10f),
            mealsToday = eaten + 1,
            mealDay = today,
        )
    }

    fun giveSnack(s: PetState): PetState? {
        if (s.snacks <= 0) return null
        return s.copy(snacks = s.snacks - 1, happiness = clamp(s.happiness + 15f))
    }

    fun mood(s: PetState, level: DriveLevel): Mood = when {
        level == DriveLevel.DANGER || s.energy < 25f -> Mood.EXHAUSTED
        level == DriveLevel.OVER -> Mood.NERVOUS
        s.fullness < 25f -> Mood.HUNGRY
        s.happiness >= 70f -> Mood.HAPPY
        else -> Mood.NORMAL
    }

    fun growth(s: PetState): Float = min(s.safeKm / KM_TO_FULL_SIZE, 1f)

    fun scale(s: PetState): Float = MIN_SCALE + (MAX_SCALE - MIN_SCALE) * growth(s)

    fun stageName(s: PetState): String = when {
        s.safeKm < 50f -> "아기"
        s.safeKm < 200f -> "꼬마"
        s.safeKm < 600f -> "청소년"
        s.safeKm < KM_TO_FULL_SIZE -> "청년"
        else -> "성견"
    }

    private fun clamp(v: Float) = v.coerceIn(0f, 100f)
}
