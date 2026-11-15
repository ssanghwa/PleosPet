package com.example.test

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener

class MainActivity : AppCompatActivity() {

    private lateinit var vehicle: Vehicle
    private lateinit var door: Door
    private lateinit var doorPositionText: TextView
    private lateinit var doorRangeText: TextView

    private val targetDoor = DoorArea(DoorValue.REAR)

    // Called by the SDK whenever the trunk position changes.
    private val doorPositionListener = object : DoorPositionListener {
        override fun onPositionUpdated(position: Int?) {
            Log.d(TAG, "[listener] position changed: $position (thread=${Thread.currentThread().name})")
            runOnUiThread { doorPositionText.text = "트렁크 위치: $position" }
        }

        override fun onFailed(e: Exception) {
            Log.e(TAG, "[listener] failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        doorPositionText = findViewById(R.id.door_position_text)
        doorRangeText = findViewById(R.id.door_range_text)
        findViewById<Button>(R.id.read_button).setOnClickListener { readDoorPosition() }
        findViewById<Button>(R.id.set_one_button).setOnClickListener { setDoorPosition(1) }
        findViewById<Button>(R.id.set_max_button).setOnClickListener { setDoorPosition(100) }
        findViewById<Button>(R.id.set_zero_button).setOnClickListener { setDoorPosition(0) }
        findViewById<Button>(R.id.open_mirror_button).setOnClickListener {
            startActivity(Intent(this, MirrorActivity::class.java))
        }

        vehicle = Vehicle(this)
        vehicle.initialize()
        door = vehicle.getDoor()

        readDoorPositionRange()
        readDoorPosition()
        door.registerDoorPosition(targetDoor, doorPositionListener)
    }

    // Asks which position values (min..max) the trunk supports.
    private fun readDoorPositionRange() {
        door.checkDoorPositionCapabilities(
            { bounds ->
                val range = bounds[targetDoor]
                Log.d(TAG, "position range: $range")
                runOnUiThread { doorRangeText.text = "설정 가능 범위: ${range?.minValue} ~ ${range?.maxValue}" }
            },
            { e: Exception ->
                Log.e(TAG, "Failed to get position range", e)
            }
        )
    }

    private fun readDoorPosition() {
        Log.d(TAG, "read requested (thread=${Thread.currentThread().name})")
        door.getCurrentDoorPosition(
            area = targetDoor,
            onComplete = { pos: Int? ->
                Log.d(TAG, "REAR door position: $pos (thread=${Thread.currentThread().name})")
                runOnUiThread { doorPositionText.text = "트렁크 위치: $pos" }
            },
            onFailed = { e: Exception ->
                Log.e(TAG, "Failed to get door position", e)
                runOnUiThread { doorPositionText.text = "조회 실패: ${e.message}" }
            }
        )
    }

    private fun setDoorPosition(position: Int) {
        Log.d(TAG, "set requested: $position")
        door.setDoorPosition(
            targetDoor,
            position,
            { _, value -> Log.d(TAG, "set done: $value") },
            { e: Exception ->
                Log.e(TAG, "Failed to set door position", e)
                runOnUiThread { doorPositionText.text = "설정 실패: ${e.message}" }
            }
        )
    }

    override fun onDestroy() {
        // Release Vehicle SDK resources when the app no longer needs them.
        door.unregisterDoorPosition(targetDoor, doorPositionListener)
        vehicle.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
