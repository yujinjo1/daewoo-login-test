package com.example.daewoo.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.*

object GeofenceWatcher {
    private const val TAG = "GeofenceWatcher"
    private const val CENTER_LAT = 37.390671
    private const val CENTER_LNG = 126.983410
    private const val PERIMETER_LAT = 37.388611
    private const val PERIMETER_LNG = 126.982437
    private const val EXIT_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour

    private val radiusMeters: Double by lazy {
        distanceMeters(CENTER_LAT, CENTER_LNG, PERIMETER_LAT, PERIMETER_LNG)
    }

    @Volatile
    private var outsideSince: Long = 0L

    @Volatile
    private var triggered: Boolean = false

    fun reset() {
        outsideSince = 0L
        triggered = false
    }

    fun onLocation(context: Context, latitude: Double, longitude: Double, timestampMs: Long) {
        if (triggered) return
        val distance = distanceMeters(CENTER_LAT, CENTER_LNG, latitude, longitude)
        val isOutside = distance > radiusMeters
        if (!isOutside) {
            outsideSince = 0L
            return
        }
        if (outsideSince == 0L) {
            outsideSince = timestampMs
            Log.i(TAG, "Outside geofence detected. Starting timer at $timestampMs (distance=${"%.1f".format(distance)}m)")
            return
        }
        val elapsed = timestampMs - outsideSince
        if (elapsed >= EXIT_TIMEOUT_MS) {
            triggerTermination(context, distance, elapsed)
        }
    }

    private fun triggerTermination(context: Context, distance: Double, elapsed: Long) {
        if (triggered) return
        triggered = true
        Log.w(TAG, "Geofence exit threshold exceeded (distance=${"%.1f".format(distance)}m, elapsed=${elapsed / 1000}s). Terminating app.")
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(0)
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
