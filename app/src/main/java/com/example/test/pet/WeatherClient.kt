package com.example.test.pet

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Current weather from Open-Meteo (no API key). The result, or null on any failure,
 * is delivered on the main thread.
 */
class WeatherClient {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun fetch(latitude: Double, longitude: Double, callback: (Weather?) -> Unit) {
        executor.execute {
            val weather = try {
                load(latitude, longitude)
            } catch (e: Exception) {
                Log.w(TAG, "weather failed", e)
                null
            }
            main.post { callback(weather) }
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun load(latitude: Double, longitude: Double): Weather {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
                latitude, longitude,
            )
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            return Weather(
                tempC = current.getDouble("temperature_2m").toFloat(),
                code = current.getInt("weather_code"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "WeatherClient"
        const val TIMEOUT_MS = 5000
    }
}
