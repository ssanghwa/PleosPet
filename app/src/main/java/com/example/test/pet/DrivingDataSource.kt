package com.example.test.pet

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Gear
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener

/**
 * Collects the driving signals the pet reacts to.
 * - gear, odometer: Pleos Vehicle SDK
 * - speed: Android Car API (the Pleos SDK has no speed API)
 *
 * Callbacks arrive on binder threads; the listener must hop to the main thread itself.
 */
class DrivingDataSource(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onSpeed(kph: Float)
        fun onGear(gear: VehicleGear)
        fun onOdometer(km: Float)
    }

    private val vehicle = Vehicle(context)
    private lateinit var gear: Gear
    private lateinit var odometer: Odometer
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            gear?.let(listener::onGear)
        }
        override fun onFailed(e: Exception) {
            Log.e(TAG, "gear listener failed", e)
        }
    }

    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(distance: Float?) {
            distance?.let(listener::onOdometer)
        }
        override fun onFailed(e: Exception) {
            Log.e(TAG, "odometer listener failed", e)
        }
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val metersPerSec = value.value as? Float ?: return
            listener.onSpeed(metersPerSec * 3.6f)
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.e(TAG, "speed error: property=$propertyId area=$areaId")
        }
    }

    fun start(speedPermissionGranted: Boolean) {
        vehicle.initialize()
        gear = vehicle.getGear()
        odometer = vehicle.getOdometer()

        gear.getCurrentGearSelectionState({ g -> g?.let(listener::onGear) }) { e -> Log.e(TAG, "gear read failed", e) }
        gear.registerGearSelection(gearListener)
        odometer.getCurrentDistanceDriven({ km -> km?.let(listener::onOdometer) }) { e ->
            Log.e(TAG, "odometer read failed", e)
        }
        odometer.registerDistanceDriven(odometerListener)

        if (speedPermissionGranted) startSpeed() else Log.w(TAG, "CAR_SPEED not granted, speed disabled")
    }

    private fun startSpeed() {
        val c = Car.createCar(context)
        if (c == null) {
            Log.e(TAG, "Car service unavailable")
            return
        }
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        propertyManager = pm
        @Suppress("DEPRECATION") // subscribePropertyEvents needs API 35; the car runs API 34.
        pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)
    }

    fun stop() {
        @Suppress("DEPRECATION")
        propertyManager?.unregisterCallback(speedCallback)
        car?.disconnect()
        gear.unregisterGearSelection(gearListener)
        odometer.unregisterDistanceDriven(odometerListener)
        vehicle.release()
    }

    private companion object {
        const val TAG = "DrivingDataSource"
    }
}
