package com.example.test.pet

import android.content.Context
import android.location.Location
import android.util.Log
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * Tells when the car has arrived home, using the navigation app (Pleos NaviHelper SDK).
 *
 * "Home" is the HOME bookmark in the navigation app. Arrival is checked when route guidance
 * ends at the destination, and also whenever the caller asks (e.g. on shifting to P), so a
 * commute without guidance counts too.
 */
class HomeArrivalSource(context: Context, private val listener: Listener) {

    interface Listener {
        /** Called on a binder thread; hop to the main thread yourself. */
        fun onArrivedHome(latitude: Double, longitude: Double)
    }

    private val navi = NaviHelper(context)
    private var home: BookmarkInfo? = null
    private var checkPending = false

    private val naviListener = object : NaviHelperEventListener {
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            home = bookmarkInfoList.firstOrNull { it.category == BookmarkType.HOME }
            Log.d(TAG, "home bookmark: ${home?.let { "${it.name} ${it.latitude},${it.longitude}" }}")
        }

        override fun onBookMarkChanged() = requestBookmarks()

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.d(TAG, "arrived: ${destinationArrivedInfo.name} ${destinationArrivedInfo.address}")
            checkNow()
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            Log.d(TAG, "location: ${currentLocationInfo.latitude},${currentLocationInfo.longitude}")
            if (!checkPending) return
            checkPending = false
            val h = home ?: run {
                Log.d(TAG, "no home bookmark set")
                return
            }
            val meters = FloatArray(1)
            Location.distanceBetween(
                currentLocationInfo.latitude, currentLocationInfo.longitude, h.latitude, h.longitude, meters,
            )
            Log.d(TAG, "distance to home: ${meters[0].toInt()} m")
            if (meters[0] <= HOME_RADIUS_M) {
                listener.onArrivedHome(currentLocationInfo.latitude, currentLocationInfo.longitude)
            }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error: $errorCode")
        }
    }

    fun start() {
        navi.addListener(naviListener)
        navi.initialize(
            onConnected = { requestBookmarks() },
            onFailed = { Log.e(TAG, "navi service unavailable", it) },
        )
    }

    /** Asks for the current position; [Listener.onArrivedHome] fires if it is near home. */
    fun checkNow() {
        if (home == null) requestBookmarks()
        checkPending = true
        try {
            navi.getCurrentLocationInfo()
        } catch (e: Exception) {
            Log.w(TAG, "location request failed", e)
        }
    }

    fun stop() {
        navi.removeListener(naviListener)
        navi.release()
    }

    private fun requestBookmarks() {
        try {
            navi.getBookmarkInfo()
        } catch (e: Exception) {
            Log.w(TAG, "bookmark request failed", e)
        }
    }

    private companion object {
        const val TAG = "HomeArrival"
        const val HOME_RADIUS_M = 300f
    }
}
