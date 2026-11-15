package com.example.test.pet

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import com.google.android.filament.Colors
import com.google.android.filament.IndirectLight
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.google.android.filament.utils.rotation
import com.google.android.filament.utils.scale
import com.google.android.filament.utils.translation
import com.google.android.filament.utils.transpose
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dogs bundled in assets. A model generated from a photo can be added here later.
 * [furMaterial] / [lightFurMaterial] are the glTF materials recolored with the owner's dog photo.
 */
enum class Breed(val label: String, val asset: String, val furMaterial: String, val lightFurMaterial: String) {
    SHIBA("시바", "Shiba Inu by Quaternius - y4wdQpg767.glb", "Main", "Main_Light"),
    HUSKY("허스키", "Husky by Quaternius - wcWiuEqwzq.glb", "Material", "Material.001"),

    /** The owner's dog built from a photo and put on the Shiba skeleton (tools/bind_to_skeleton.py). */
    CUSTOM("우리 강아지", "custom_dog.glb", "", "");

    companion object {
        /** Breeds whose model file is actually bundled. */
        fun available(context: Context): List<Breed> {
            val files = context.assets.list("")?.toSet().orEmpty()
            return entries.filter { it.asset in files }
        }
    }
}

/**
 * 3D dog drawn with Filament on a transparent TextureView, laid over [SceneryView].
 * The clip (Idle / Walk / Gallop ...) follows speed and mood, with a short cross-fade between them.
 */
class Dog3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), Choreographer.FrameCallback {

    var mood: Mood = Mood.NORMAL

    /** 0.5 (갓 태어남) .. 1.0 (최대 크기) */
    var scale: Float = PetEngine.MIN_SCALE
        set(value) {
            if (field == value) return
            field = value
            placeModel()
        }

    var speedKph: Float = 0f

    var breed: Breed? = null
        set(value) {
            if (field == value || value == null) return
            field = value
            loadModel(value)
        }

    /** Fur colors from the owner's photo; null keeps the model's own colors. */
    var furColors: FurColors? = null
        set(value) {
            if (field == value) return
            val wasCustom = field != null
            field = value
            if (value != null) applyFurColors()
            else if (wasCustom) breed?.let(::loadModel)   // original colors live in the file
        }

    private val viewer: ModelViewer
    private val clips = HashMap<String, Int>()
    private var clip = -1
    private var clipTime = 0f
    private var prevClip = -1
    private var prevTime = 0f
    private var fade = 1f
    private var oneShot: String? = null
    private var lastFrameNanos = 0L
    private var running = false

    // Rest pose of every node. Each clip moves only some bones (Idle leaves the legs alone), so
    // the pose is reset every frame; otherwise bones keep the last pose of the previous clip.
    private var restInstances = IntArray(0)
    private var restTransforms = emptyArray<FloatArray>()

    // Model space -> "feet at origin, length 1" transform, from the bounding box.
    private var fitScale = 1f
    private var fitCenter = Float3()

    init {
        val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
        viewer = ModelViewer(this, uiHelper = uiHelper, manipulator = null)
        viewer.autoPlayAnimations = false
        viewer.view.blendMode = View.BlendMode.TRANSLUCENT
        viewer.renderer.clearOptions = viewer.renderer.clearOptions.apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
        // Flat ambient light so the low-poly colors read well without an environment map.
        viewer.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(35_000f)
            .build(viewer.engine)
        viewer.engine.lightManager.let { lm ->
            val sun = lm.getInstance(viewer.light)
            lm.setDirection(sun, 0.4f, -1f, -0.6f)
            lm.setIntensity(sun, 70_000f)
        }

        // Slightly above, looking at the world origin; placeModel() moves the dog onto the road.
        viewer.camera.lookAt(
            0.0, CAMERA_DISTANCE * sin(PITCH), CAMERA_DISTANCE * cos(PITCH),
            0.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
        )
    }

    /** Plays a clip once (e.g. "Eating"), then goes back to the speed/mood clip. */
    fun playOnce(name: String) {
        if (name in clips) oneShot = name
    }

    /** Little jump for a reward: a running leap while driving, a hop when parked. */
    fun celebrate() = playOnce(if (speedKph >= 1f) "Gallop_Jump" else "Jump_ToIdle")

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val dt = if (lastFrameNanos == 0L) 0f else min((frameTimeNanos - lastFrameNanos) / 1e9f, 0.1f)
        lastFrameNanos = frameTimeNanos
        animate(dt)
        viewer.render(frameTimeNanos)
    }

    private fun animate(dt: Float) {
        val animator = viewer.animator ?: return
        if (clips.isEmpty()) return

        val want = clips[oneShot ?: loopClipName()] ?: clips.getValue("Idle")
        if (want != clip) {
            prevClip = clip
            prevTime = clipTime
            clip = want
            clipTime = 0f
            fade = if (prevClip < 0) 1f else 0f
        }

        val tm = viewer.engine.transformManager
        for (i in restInstances.indices) tm.setTransform(restInstances[i], restTransforms[i])

        val duration = animator.getAnimationDuration(clip)
        clipTime += dt * playbackRate()
        if (oneShot != null && clipTime >= duration) {
            oneShot = null
            clipTime = duration
        } else if (duration > 0f) {
            clipTime %= duration
        }
        animator.applyAnimation(clip, clipTime)

        if (fade < 1f && prevClip >= 0) {
            fade = min(fade + dt / CROSS_FADE_SEC, 1f)
            val prevDuration = animator.getAnimationDuration(prevClip)
            if (prevDuration > 0f) prevTime = (prevTime + dt) % prevDuration
            animator.applyCrossFade(prevClip, prevTime, fade)
        }
        animator.updateBoneMatrices()
    }

    private fun loopClipName() = when {
        speedKph >= GALLOP_KPH -> "Gallop"
        speedKph >= 1f -> "Walk"
        mood == Mood.EXHAUSTED || mood == Mood.HUNGRY -> "Idle_2_HeadLow"
        mood == Mood.HAPPY -> "Idle_2"
        else -> "Idle"
    }

    private fun playbackRate(): Float {
        if (oneShot != null) return 1f
        val tired = if (mood == Mood.EXHAUSTED) 0.7f else 1f
        return tired * when {
            speedKph >= GALLOP_KPH -> (0.8f + speedKph / 120f).coerceAtMost(1.8f)
            speedKph >= 1f -> 0.7f + speedKph / GALLOP_KPH * 0.6f
            else -> 1f
        }
    }

    private fun loadModel(breed: Breed) {
        val bytes = try {
            context.assets.open(breed.asset).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "cannot read ${breed.asset}", e)
            return
        }
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
        viewer.loadModelGlb(buffer)

        clips.clear()
        clip = -1
        prevClip = -1
        oneShot = null
        viewer.animator?.let { a ->
            for (i in 0 until a.animationCount) {
                // The files carry each clip twice ("Gallop" and "AnimalArmature|Gallop"); keep the short name.
                val name = a.getAnimationName(i)
                if ('|' !in name) clips.putIfAbsent(name, i)
            }
        }
        Log.d(TAG, "loaded ${breed.asset}: clips=${clips.keys}")
        saveRestPose()
        applyFurColors()

        val box = viewer.asset?.boundingBox ?: return
        val c = box.center
        val h = box.halfExtent
        fitScale = 1f / (2f * max(h[0], max(h[1], h[2])))
        fitCenter = Float3(c[0], c[1] - h[1], c[2])   // bottom-center = feet
        Log.d(TAG, "bbox center=${c.toList()} half=${h.toList()}")
        placeModel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placeModel()
    }

    private fun applyFurColors() {
        val colors = furColors ?: return
        val b = breed ?: return
        val instances = viewer.asset?.instance?.materialInstances ?: return
        for (mi in instances) {
            val color = when (mi.name) {
                b.furMaterial -> colors.main
                b.lightFurMaterial -> colors.light
                else -> continue
            }
            mi.setParameter(
                "baseColorFactor", Colors.RgbaType.SRGB,
                (color shr 16 and 0xFF) / 255f, (color shr 8 and 0xFF) / 255f, (color and 0xFF) / 255f, 1f,
            )
        }
    }

    /** Must run before any clip is applied. The root is skipped: placeModel() owns it. */
    private fun saveRestPose() {
        val asset = viewer.asset ?: return
        val tm = viewer.engine.transformManager
        val instances = asset.entities
            .filter { it != asset.root && tm.hasComponent(it) }
            .map { tm.getInstance(it) }
        restInstances = instances.toIntArray()
        restTransforms = Array(instances.size) { tm.getTransform(instances[it], FloatArray(16)) }
    }

    private fun placeModel() {
        val asset = viewer.asset ?: return
        if (width == 0 || height == 0) return
        val size = MODEL_LENGTH * scale * fitScale
        val m = translation(feetPosition()) *
            rotation(Float3(0f, 1f, 0f), MODEL_YAW_DEG) *
            scale(Float3(size)) *
            translation(-fitCenter)
        val tm = viewer.engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), transpose(m).toFloatArray())
    }

    /**
     * World point that the camera shows at [SceneryView.DOG_X], [SceneryView.DOG_FEET_Y].
     * It lies on the plane through the origin facing the camera, so it is CAMERA_DISTANCE away.
     */
    private fun feetPosition(): Float3 {
        val halfH = CAMERA_DISTANCE * HALF_FOV_TAN
        val halfW = halfH * width / height
        val right = (2.0 * SceneryView.DOG_X - 1.0) * halfW
        val up = (1.0 - 2.0 * SceneryView.DOG_FEET_Y) * halfH
        // camera up axis is (0, cos, -sin) because the camera is pitched down
        return Float3(right.toFloat(), (up * cos(PITCH)).toFloat(), (-up * sin(PITCH)).toFloat())
    }

    private companion object {
        init {
            Utils.init()
        }

        const val TAG = "Dog3DView"
        const val GALLOP_KPH = 30f
        const val CROSS_FADE_SEC = 0.3f

        // Framing. The dog faces left like the scenery expects (the world scrolls to the right).
        const val CAMERA_DISTANCE = 4.0
        val PITCH = Math.toRadians(5.0)
        // ModelViewer uses a 28 mm lens on a 24 mm tall sensor: tan(vertical fov / 2) = 12 / 28.
        const val HALF_FOV_TAN = 12.0 / 28.0
        const val MODEL_YAW_DEG = -80f    // nearly side-on: at a 3/4 angle the far legs look lifted over the flat 2D road
        const val MODEL_LENGTH = 2.2f    // world units at full size
    }
}
