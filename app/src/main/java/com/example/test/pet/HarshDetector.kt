package com.example.test.pet

enum class HarshEvent { ACCEL, BRAKE }

/**
 * Finds harsh acceleration / braking from speed samples.
 *
 * The car reports speed only when it changes, and at an uneven rate, so comparing two neighbouring
 * samples is noisy. Instead the speed now is compared with the speed one [windowMs] ago, holding each
 * sample's value until the next one arrives.
 */
class HarshDetector(
    private val windowMs: Long = 1000L,
    private val cooldownMs: Long = 3000L,
) {
    private val times = ArrayDeque<Long>()
    private val speeds = ArrayDeque<Float>()
    private var lastEventAt: Long? = null

    fun onSpeed(atMs: Long, kph: Float): HarshEvent? {
        times.addLast(atMs)
        speeds.addLast(kph)
        // Keep exactly one sample at or before the window start; it holds the speed back then.
        while (times.size >= 2 && times[1] <= atMs - windowMs) {
            times.removeFirst()
            speeds.removeFirst()
        }
        if (times.first() > atMs - windowMs) return null   // not enough history yet

        val before = speeds.first()
        if (maxOf(before, kph) < MIN_KPH) return null      // creeping in traffic
        val rate = (kph - before) / (windowMs / 1000f)
        val event = when {
            rate >= ACCEL_KPH_PER_SEC -> HarshEvent.ACCEL
            rate <= -BRAKE_KPH_PER_SEC -> HarshEvent.BRAKE
            else -> return null
        }
        lastEventAt?.let { if (atMs - it < cooldownMs) return null }
        lastEventAt = atMs
        return event
    }

    companion object {
        // 약 3.3 m/s², 4.2 m/s². 운전 습관 분석에서 흔히 쓰는 급가속·급제동 기준과 비슷합니다.
        const val ACCEL_KPH_PER_SEC = 12f
        const val BRAKE_KPH_PER_SEC = 15f
        const val MIN_KPH = 5f
    }
}
