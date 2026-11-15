package com.example.test.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Background behind the 3D dog, drawn with Canvas. While the car moves the scenery scrolls;
 * scroll speed follows the vehicle speed. [Dog3DView] stands on the road on top of this.
 */
class SceneryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 0.5 (갓 태어남) .. 1.0 (최대 크기), sizes the shadow under the dog */
    var scale: Float = PetEngine.MIN_SCALE

    var speedKph: Float = 0f

    private var lastFrameAt = 0L
    private var scroll = 0f     // px the world has moved

    private val sky = Paint()
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 120) }
    private val hillFar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 214, 160) }
    private val hillNear = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(132, 192, 120) }
    private val trunk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140, 100, 70) }
    private val leaves = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 160, 90) }
    private val road = Paint().apply { color = Color.rgb(120, 124, 132) }
    private val roadEdge = Paint().apply { color = Color.rgb(98, 160, 88) }
    private val laneMark = Paint().apply { color = Color.rgb(250, 250, 240) }
    private val speedLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); strokeCap = Paint.Cap.ROUND
    }

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 0, 0, 0) }
    private val rect = RectF()

    private val running get() = speedKph >= 1f
    private val groundY get() = height * 0.80f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sky.shader = LinearGradient(
            0f, 0f, 0f, h * 0.6f,
            Color.rgb(140, 200, 255), Color.rgb(225, 242, 255), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 0f else min((now - lastFrameAt) / 1000f, 0.05f)
        lastFrameAt = now

        if (running) {
            scroll += speedKph.coerceAtMost(200f) * width / 120f * dt   // 120 km/h ≈ one screen width per second
        }

        drawScene(canvas)
        drawDogGround(canvas)
        postInvalidateOnAnimation()
    }

    // --- scenery (dog faces left, so the world moves to the right) ---

    private fun drawScene(canvas: Canvas) {
        val w = width.toFloat()
        val g = groundY
        canvas.drawRect(0f, 0f, w, g, sky)
        canvas.drawCircle(w * 0.82f, height * 0.16f, height * 0.07f, sun)

        drawHills(canvas, hillFar, scroll * 0.1f, g - height * 0.10f, height * 0.16f, w * 0.55f)
        drawHills(canvas, hillNear, scroll * 0.25f, g - height * 0.02f, height * 0.12f, w * 0.38f)

        val treeGap = w * 0.33f
        val treeOffset = (scroll * 0.5f) % treeGap
        var x = -treeGap + treeOffset
        while (x < w + treeGap) {
            drawTree(canvas, x, g - height * 0.02f, height * 0.12f)
            x += treeGap
        }

        canvas.drawRect(0f, g - height * 0.02f, w, height.toFloat(), roadEdge)
        canvas.drawRect(0f, g + height * 0.02f, w, height.toFloat(), road)
        val dash = w * 0.12f
        val dashOffset = scroll % (dash * 2)
        x = -dash * 2 + dashOffset
        val laneY = g + height * 0.09f
        while (x < w) {
            canvas.drawRect(x, laneY, x + dash, laneY + height * 0.012f, laneMark)
            x += dash * 2
        }
    }

    private fun drawHills(canvas: Canvas, paint: Paint, offset: Float, baseY: Float, amp: Float, period: Float) {
        val start = -(period - offset % period)
        var x = start
        while (x < width + period) {
            rect.set(x, baseY - amp, x + period, baseY + amp)
            canvas.drawOval(rect, paint)
            x += period
        }
    }

    private fun drawTree(canvas: Canvas, x: Float, baseY: Float, size: Float) {
        canvas.drawRect(x - size * 0.08f, baseY - size * 0.6f, x + size * 0.08f, baseY, trunk)
        canvas.drawCircle(x, baseY - size * 0.8f, size * 0.38f, leaves)
    }


    // --- under the dog (the dog itself is Dog3DView) ---

    private fun drawDogGround(canvas: Canvas) {
        val unit = height / 11f * scale
        val cx = width * DOG_X
        val g = height * DOG_FEET_Y

        rect.set(cx - unit * 2.4f, g - unit * 0.3f, cx + unit * 2.4f, g + unit * 0.3f)
        canvas.drawOval(rect, shadow)
        if (running && speedKph > 80f) drawSpeedLines(canvas, cx, g - unit * 2.5f, unit)
    }

    private fun drawSpeedLines(canvas: Canvas, cx: Float, cy: Float, u: Float) {
        speedLine.strokeWidth = u * 0.12f
        val len = u * (1.5f + speedKph / 60f)
        for (i in 0 until 4) {
            val y = cy + (i - 1.5f) * u * 0.8f
            val start = cx + u * 3.2f + ((scroll * 0.8f + i * 40f) % (u * 1.5f))
            canvas.drawLine(start, y, start + len, y, speedLine)
        }
    }

    companion object {
        /** Where the dog's feet stand, as a fraction of the view. Dog3DView frames its camera on this. */
        const val DOG_X = 0.45f
        const val DOG_FEET_Y = 0.86f
    }
}
