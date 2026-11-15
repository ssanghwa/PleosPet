package com.example.test.pet

import kotlin.math.max
import kotlin.math.min

/** Two fur colors painted onto the 3D dog, as 0xRRGGBB (sRGB). */
data class FurColors(val main: Int, val light: Int)

/**
 * Guesses the dog's fur colors from a photo.
 *
 * Works on a small ARGB pixel grid (e.g. a 64x64 thumbnail):
 * 1. Colors that dominate the photo's border are treated as background and dropped,
 *    unless the dog fills the frame (then too little would be left).
 * 2. The rest is grouped into a few colors (k-means). The biggest group is the main fur;
 *    a clearly lighter group is the light fur (belly, face), otherwise a lightened main color.
 *
 * Pure function so it can be tuned in unit tests.
 */
object FurColorExtractor {

    private const val K = 4
    private const val ITERATIONS = 12
    private const val BACKGROUND_DISTANCE = 45
    private const val MIN_LIGHT_SHARE = 0.08f
    private const val MIN_LIGHT_GAIN = 25

    fun extract(pixels: IntArray, width: Int, height: Int): FurColors {
        require(pixels.size == width * height && pixels.isNotEmpty())
        val border = ArrayList<Int>()
        val inner = ArrayList<Int>()
        val bx = max(1, width / 10)
        val by = max(1, height / 10)
        for (y in 0 until height) for (x in 0 until width) {
            val p = pixels[y * width + x] and 0xFFFFFF
            if (x < bx || y < by || x >= width - bx || y >= height - by) border.add(p) else inner.add(p)
        }
        if (inner.isEmpty()) inner.addAll(border)

        // Background = border colors that cover a good part of the border.
        val (bgCenters, bgCounts) = kMeans(border.toIntArray(), 3)
        val background = bgCenters.filterIndexed { i, _ -> bgCounts[i] >= border.size * 0.15f }
        val dog = inner.filter { p -> background.none { distance(it, p) < BACKGROUND_DISTANCE } }
        val subject = if (dog.size >= inner.size / 4) dog else inner   // dog fills the frame

        val (centers, counts) = kMeans(subject.toIntArray(), K)
        val order = centers.indices.sortedByDescending { counts[it] }
        val main = centers[order.first()]
        val total = counts.sum().toFloat()
        val light = order.drop(1)
            .filter { counts[it] / total >= MIN_LIGHT_SHARE && luma(centers[it]) >= luma(main) + MIN_LIGHT_GAIN }
            .maxByOrNull { luma(centers[it]) }
            ?.let { centers[it] }
            ?: blend(main, 0xFFFFFF, if (luma(main) > 200) 0.3f else 0.55f)
        return FurColors(main, light)
    }

    /**
     * Photos are often dim (indoors, shade), so the measured fur looks muddier than the dog does
     * to its owner. A little more saturation and brightness brings it back; the hue is kept.
     */
    fun vivid(colors: FurColors): FurColors = FurColors(vivid(colors.main), vivid(colors.light))

    internal fun vivid(c: Int, saturation: Float = 1.3f, brightness: Float = 1.15f): Int {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val v = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        if (v == 0f) return c
        val s = (v - minC) / v
        // In HSV each channel is v * (1 - s * t), where t (0 for the max channel, 1 for the min)
        // fixes the hue. Keep t, raise s and v.
        val newV = min(1f, v * brightness)
        val newS = min(1f, s * saturation)
        fun channel(x: Float): Int {
            val t = if (s == 0f) 0f else (v - x) / (v - minC)
            return (newV * (1f - newS * t) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return rgb(channel(r), channel(g), channel(b))
    }

    /** Deterministic k-means in RGB. Returns centers and how many pixels each one got. */
    internal fun kMeans(pixels: IntArray, k: Int): Pair<IntArray, IntArray> {
        if (pixels.isEmpty()) return IntArray(1) to IntArray(1)
        val n = min(k, pixels.size)
        // Seed with pixels spread over the brightness range so dark and light fur both get a center.
        val sorted = pixels.sortedBy { luma(it) }
        val centers = IntArray(n) { sorted[(it * 2 + 1) * sorted.size / (2 * n)] }
        val counts = IntArray(n)
        repeat(ITERATIONS) {
            val sr = LongArray(n); val sg = LongArray(n); val sb = LongArray(n)
            counts.fill(0)
            for (p in pixels) {
                var best = 0
                var bestD = Int.MAX_VALUE
                for (c in 0 until n) {
                    val d = distanceSq(centers[c], p)
                    if (d < bestD) { bestD = d; best = c }
                }
                counts[best]++
                sr[best] += (p shr 16 and 0xFF).toLong()
                sg[best] += (p shr 8 and 0xFF).toLong()
                sb[best] += (p and 0xFF).toLong()
            }
            for (c in 0 until n) if (counts[c] > 0) {
                centers[c] = rgb((sr[c] / counts[c]).toInt(), (sg[c] / counts[c]).toInt(), (sb[c] / counts[c]).toInt())
            }
        }
        return centers to counts
    }

    internal fun luma(c: Int): Int = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    private fun distance(a: Int, b: Int) = kotlin.math.sqrt(distanceSq(a, b).toDouble())

    private fun distanceSq(a: Int, b: Int): Int {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    private fun blend(a: Int, b: Int, t: Float): Int = rgb(
        ((a shr 16 and 0xFF) * (1 - t) + (b shr 16 and 0xFF) * t).toInt(),
        ((a shr 8 and 0xFF) * (1 - t) + (b shr 8 and 0xFF) * t).toInt(),
        ((a and 0xFF) * (1 - t) + (b and 0xFF) * t).toInt(),
    )

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
}
