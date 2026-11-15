package com.example.test

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.SideMirror
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.listener.HvacSideMirrorHeatStateListener

/**
 * Side mirror heat example: capability -> read -> live updates -> change request -> release.
 */
class MirrorActivity : AppCompatActivity() {

    private lateinit var vehicle: Vehicle
    private lateinit var sideMirror: SideMirror
    private lateinit var rangeText: TextView
    private lateinit var leftText: TextView
    private lateinit var rightText: TextView

    private val leftMirror = MirrorArea(MirrorValue.DRIVER_LEFT)
    private val rightMirror = MirrorArea(MirrorValue.DRIVER_RIGHT)

    // One listener per area, so we know which mirror changed and can unregister with the same object.
    private val leftListener = heatListener("LEFT") { leftText }
    private val rightListener = heatListener("RIGHT") { rightText }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mirror)
        rangeText = findViewById(R.id.mirror_range_text)
        leftText = findViewById(R.id.mirror_left_text)
        rightText = findViewById(R.id.mirror_right_text)
        findViewById<Button>(R.id.mirror_read_button).setOnClickListener { readAll() }
        findViewById<Button>(R.id.mirror_heat_on_button).setOnClickListener { setHeatAll(1) }
        findViewById<Button>(R.id.mirror_heat_off_button).setOnClickListener { setHeatAll(0) }

        vehicle = Vehicle(this)
        vehicle.initialize()
        sideMirror = vehicle.getSideMirror()

        checkCapabilities()
        readAll()
        sideMirror.registerHvacSideMirrorHeatState(leftMirror, leftListener)
        sideMirror.registerHvacSideMirrorHeatState(rightMirror, rightListener)
    }

    // 1) Which mirrors support heat, and what value range each one accepts.
    private fun checkCapabilities() {
        sideMirror.checkHvacSideMirrorHeatCapabilities(
            { bounds ->
                val text = bounds.entries.joinToString("\n") { (area, range) ->
                    "${area.values.joinToString { it.name }}: ${range.minValue} ~ ${range.maxValue}"
                }
                Log.d(TAG, "heat capability: $bounds")
                runOnUiThread { rangeText.text = "지원 범위\n$text" }
            },
            { e: Exception ->
                Log.e(TAG, "Failed to check heat capability", e)
                runOnUiThread { rangeText.text = "지원 범위 확인 실패: ${e.message}" }
            }
        )
    }

    // 2) Read the current heat state of both mirrors.
    private fun readAll() {
        read(leftMirror, "LEFT") { leftText }
        read(rightMirror, "RIGHT") { rightText }
    }

    private fun read(area: MirrorArea, label: String, target: () -> TextView) {
        sideMirror.getHvacSideMirrorHeatState(
            area,
            { state: Int? ->
                Log.d(TAG, "[read] $label heat: $state")
                runOnUiThread { target().text = "$label 미러 열선: ${describe(state)}" }
            },
            { e: Exception ->
                Log.e(TAG, "[read] $label failed", e)
                runOnUiThread { target().text = "$label 미러 조회 실패: ${e.message}" }
            }
        )
    }

    // 4) Change request only. The real state is confirmed by a new read or a listener update.
    private fun setHeatAll(level: Int) {
        listOf(leftMirror to "LEFT", rightMirror to "RIGHT").forEach { (area, label) ->
            Log.d(TAG, "[set] $label heat -> $level")
            sideMirror.setHvacSideMirrorHeat(
                area,
                level,
                { _, value -> Log.d(TAG, "[set] $label request completed: $value") },
                { e: Exception -> Log.e(TAG, "[set] $label failed", e) }
            )
        }
    }

    // 3) Live updates: called when the heat state changes (e.g. from the Virtual Console).
    private fun heatListener(label: String, target: () -> TextView) =
        object : HvacSideMirrorHeatStateListener {
            override fun onHvacSideMirrorHeatStateUpdated(state: Int?) {
                Log.d(TAG, "[listener] $label heat changed: $state")
                runOnUiThread { target().text = "$label 미러 열선: ${describe(state)}" }
            }

            override fun onFailed(e: Exception) {
                Log.e(TAG, "[listener] $label failed", e)
            }
        }

    private fun describe(state: Int?) = when (state) {
        null -> "값 없음"
        0 -> "꺼짐 (0)"
        else -> "켜짐 ($state)"
    }

    override fun onDestroy() {
        // 5) Unregister with the same listener + area, then release the SDK.
        sideMirror.unregisterHvacSideMirrorHeatState(leftMirror, leftListener)
        sideMirror.unregisterHvacSideMirrorHeatState(rightMirror, rightListener)
        vehicle.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MirrorActivity"
    }
}
