package com.example.test.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.OnServerConnectionListener
import ai.pleos.playground.llm.listener.ResultListener
import com.example.test.BuildConfig

/**
 * Lets the dog say event lines in its own words with the Pleos LLM SDK: the server model when
 * the app key is set in local.properties, otherwise the car's on-device model.
 *
 * Every request has a fixed fallback line: if the model is missing, slow or says something too
 * long, the fallback is shown instead, so the screen never waits on the model.
 * Results come back on the main thread.
 */
class DogTalker(context: Context) {

    /** Who is talking; filled in by the screen before each request. */
    data class Persona(val name: String, val breed: String, val stage: String, val mood: Mood)

    private val server = BuildConfig.PLEOS_CLIENT_ID.isNotEmpty() && BuildConfig.PLEOS_CLIENT_SECRET.isNotEmpty()
    private val llm = LLM(context, if (server) Mode.SERVER else Mode.ON_DEVICE)
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var busy = false
    private var request = 0

    fun start() {
        try {
            llm.initialize()
            if (server) register() else ready = true
        } catch (e: Exception) {
            Log.w(TAG, "LLM unavailable, using fixed lines", e)
        }
    }

    fun stop() {
        try {
            llm.release()
        } catch (e: Exception) {
            Log.w(TAG, "LLM release failed", e)
        }
        ready = false
    }

    /** The server model answers only after the app key is accepted. */
    private fun register() {
        llm.registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET, object : OnServerConnectionListener {
            override fun onConnected() {
                main.post {
                    Log.i(TAG, "server LLM connected")
                    ready = true
                }
            }

            override fun onFailed(message: String) {
                Log.w(TAG, "server LLM registration failed: $message")
            }
        })
    }

    /**
     * Shows [fallback] right away through [show], then replaces it with the model's line
     * if one arrives in time. Requests while one is running just use the fallback.
     */
    fun say(persona: Persona, situation: String, fallback: String, show: (String) -> Unit) {
        show(fallback)
        if (!ready || busy) return
        busy = true
        val id = ++request
        val timeout = Runnable {
            if (id == request && busy) {
                Log.d(TAG, "timeout: $situation")
                busy = false
            }
        }
        main.postDelayed(timeout, TIMEOUT_MS)
        try {
            llm.generateContent(prompt(persona, situation), object : ResultListener {
                override fun onResponse(response: String, isFinal: Boolean) {
                    main.post {
                        if (id != request || !busy) return@post   // too late, fallback already stands
                        busy = false
                        main.removeCallbacks(timeout)
                        val line = clean(response)
                        Log.d(TAG, "line for '$situation': '$response' -> ${line ?: "(rejected)"}")
                        if (line != null) show(line)
                    }
                }

                override fun onError(message: String) {
                    main.post {
                        Log.w(TAG, "LLM error: $message")
                        if (id == request) busy = false
                        main.removeCallbacks(timeout)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "LLM request failed", e)
            busy = false
            main.removeCallbacks(timeout)
        }
    }

    private fun prompt(p: Persona, situation: String) = """
        너는 운전자와 함께 차를 타는 강아지 '${p.name}'(${p.breed}, ${p.stage})야. 지금 기분은 ${moodWord(p.mood)}.
        상황: $situation
        이 상황에서 네가 운전자에게 하는 말을 강아지 말투로 딱 한 문장만 써.
        ${MAX_CHARS}자 이내, 따옴표·이모지·설명 없이 대사만.
    """.trimIndent()

    private fun moodWord(m: Mood) = when (m) {
        Mood.HAPPY -> "신남"
        Mood.NORMAL -> "평온함"
        Mood.HUNGRY -> "배고픔"
        Mood.NERVOUS -> "불안함"
        Mood.EXHAUSTED -> "지침"
    }

    companion object {
        private const val TAG = "DogTalker"
        private const val TIMEOUT_MS = 4000L
        const val MAX_CHARS = 25

        /** First line only, without quotes; null if empty or too long to read at a glance. */
        fun clean(raw: String): String? {
            val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            val text = line.trim('"', '\'', '“', '”', '‘', '’', ' ').removePrefix("-").trim()
            return text.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }
        }
    }
}
