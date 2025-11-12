package com.fifth.maplocationlib

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.util.ArrayDeque
import kotlin.math.log10

class IndoorEstimate(private val context: Context, private val listener: IndoorLikelihoodListener) {

    interface IndoorLikelihoodListener {
        fun onIndoorLikelihoodUpdated(likelihood: Float)
        fun onStrongIndoorConfirmed()
        fun onStrongOutdoorConfirmed()
        fun onDebugMessage(message: String) // For relaying debug messages
    }

    private var locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var lastLocation: Location? = null
    private var lastLocationUpdateAt: Long = 0L

    private var gnssCallback: GnssStatus.Callback? = null
    private var lastGnssStatus: GnssStatus? = null
    private var startedAt: Long = 0L

    // CN0 and Accuracy windows for calculating indoor likelihood
    private val cn0Window: ArrayDeque<Float> = ArrayDeque()
    private val accWindow: ArrayDeque<Float> = ArrayDeque()
    private val windowMax = 10 // Max size for CN0 and accuracy windows

    // Likelihood window for smoothing and detecting strong indoor state
    private val likelihoodWindow: ArrayDeque<Float> = ArrayDeque()
    private val likelihoodWindowMax = 5 // Max size for likelihood window (recent 5 samples)
    private var strongIndoorStartTime: Long? = null
    private var strongOutdoorStartTime: Long? = null
    private val strongIndoorDurationThreshold = 3000L // 3 seconds to confirm strong indoor

    private var indoorUpdateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            lastLocationUpdateAt = System.currentTimeMillis()
            updateIndoorEstimate(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun start() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            listener.onDebugMessage("ACCESS_FINE_LOCATION permission not granted.")
            return
        }

        startedAt = System.currentTimeMillis()

        // Request location updates
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            // Also request coarse location to help when starting indoors
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            } catch (_: Throwable) {
                // Some devices/providers may be unavailable; ignore
            }
            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastKnownNetwork = try { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Throwable) { null }
            val lastKnownBest = lastKnownLocation ?: lastKnownNetwork
            if (lastKnownBest != null) {
                lastLocation = lastKnownBest
                lastLocationUpdateAt = System.currentTimeMillis()
                updateIndoorEstimate(lastKnownBest)
            }
        } catch (e: SecurityException) {
            listener.onDebugMessage("SecurityException in requestLocationUpdates: ${e.message}")
            return
        }

        registerGnssCallback()

        indoorUpdateJob?.cancel() // Cancel any existing job
        indoorUpdateJob = scope.launch {
            while (isActive) {
                updateIndoorWhenPossible()
                delay(1000L) // Update every second
            }
        }
        listener.onDebugMessage("IndoorEstimate started.")
    }

    fun stop() {
        locationManager.removeUpdates(locationListener)
        try { locationManager.removeUpdates(locationListener) } catch (_: Throwable) {}
        unregisterGnssCallback()
        indoorUpdateJob?.cancel()
        indoorUpdateJob = null
        // Clear windows and reset states
        cn0Window.clear()
        accWindow.clear()
        likelihoodWindow.clear()
        strongIndoorStartTime = null
        listener.onDebugMessage("IndoorEstimate stopped.")
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun registerGnssCallback() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            listener.onDebugMessage("Cannot register GNSS callback: ACCESS_FINE_LOCATION permission not granted.")
            return
        }
        if (gnssCallback == null) {
            gnssCallback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    lastGnssStatus = status
                    // Optionally, could trigger an immediate updateIndoorEstimate if a new location is also recent
                }

                override fun onStarted() {
                    listener.onDebugMessage("GNSS callback started.")
                }

                override fun onStopped() {
                    listener.onDebugMessage("GNSS callback stopped.")
                }
            }
            locationManager.registerGnssStatusCallback(gnssCallback!!,
                Handler(Looper.getMainLooper())
            )
            listener.onDebugMessage("GNSS callback registered.")
        }
    }

    private fun unregisterGnssCallback() {
        gnssCallback?.let {
            locationManager.unregisterGnssStatusCallback(it)
            gnssCallback = null
            listener.onDebugMessage("GNSS callback unregistered.")
        }
    }

    private fun updateIndoorWhenPossible() {
        val now = System.currentTimeMillis()
        val isStale = (lastLocation == null) || (now - lastLocationUpdateAt >= 5000L)

        // If location is missing/stale (typical when starting indoors), synthesize a dummy Location
        // so that the scorer can still progress and confirm indoor state.
        val locForScoring = if (isStale) {
            val dummy = Location("synth").apply {
                // Coarse accuracy to reflect uncertainty indoors
                accuracy = 200f
                // Optionally set a timestamp
                time = now
            }
            dummy
        } else {
            lastLocation
        }

        // Drive the same scoring pipeline using the (real or dummy) location
        locForScoring?.let { updateIndoorEstimate(it) }

        // Check if location is recent enough (e.g., within last 5 seconds)
        // This check ensures we don't use stale location data for periodic updates.
        // The primary update mechanism is onLocationChanged.
        // This periodic check is more for the strong indoor confirmation logic.
        if (locForScoring != null && System.currentTimeMillis() - lastLocationUpdateAt < 5000) {
            // If needed, one could re-trigger updateIndoorEstimate(currentLoc) here
            // if updates solely based on GNSS changes (without new location) are desired.
        }

        // Check for strong indoor state persistence
        // Ensure the window has enough data points and all recent likelihoods are high
        if (likelihoodWindow.size >= likelihoodWindowMax && likelihoodWindow.all { it > 0.25f }) {
            if (strongIndoorStartTime == null) {
                strongIndoorStartTime = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - (strongIndoorStartTime ?: 0L) >= strongIndoorDurationThreshold) {
                listener.onStrongIndoorConfirmed()
                // Reset strongIndoorStartTime after confirmation to prevent continuous firing
                // if the condition remains true. It will re-trigger if conditions persist.
                strongIndoorStartTime = null
            }
        } else if(likelihoodWindow.size >= likelihoodWindowMax && likelihoodWindow.all { it < 0.15f }) {
            listener.onStrongOutdoorConfirmed()
            // If conditions for strong indoor are no longer met, reset the timer.
            strongIndoorStartTime = null
        }else {
            // If conditions for strong indoor are no longer met, reset the timer.
            strongIndoorStartTime = null
        }
    }

    private fun updateIndoorEstimate(location: Location) {
        // 0) 최근 위치 콜백이 3초 이상 끊겼는지(실내 가능성 가중치 보정)
        val nowMs = System.currentTimeMillis()
        val staleMs = (nowMs - lastLocationUpdateAt).coerceAtLeast(0L)
        // 3초 이후부터 7초까지 선형 상승 (0.0 ~ 1.0), 7초 넘으면 1.0 고정
        val noCallbackScore = when {
            staleMs <= 3000L -> 0f
            staleMs >= 7000L -> 1f
            else -> (staleMs - 3000L).toFloat() / 4000f
        }

        // 1) 롤링 윈도우에 accuracy 추가
        location.accuracy.takeIf { it.isFinite() }?.let { acc ->
            accWindow.addLast(acc)
            while (accWindow.size > windowMax) accWindow.removeFirst()
        }

        // 2) 최근 GNSS 상태로 평균 C/N0와 사용 위성 수 계산
        val (usedSvCount, avgCn0) = lastGnssStatus?.let { st ->
            var used = 0
            var sumCn0 = 0f
            var cnt = 0
            for (i in 0 until st.satelliteCount) {
                val cn0 = try { st.getCn0DbHz(i) } catch (_: Throwable) { 0f }
                val usedInFix = try { st.usedInFix(i) } catch (_: Throwable) { false }
                if (cn0 > 0) { sumCn0 += cn0; cnt++ }
                if (usedInFix) used++
            }
            val avg = if (cnt > 0) sumCn0 / cnt else 0f
            used to avg
        } ?: (0 to 0f)

        // 3) 점수 계산
        val accNow = location.accuracy.coerceAtLeast(0.5f)
        val accScore = when {
            accNow <= 8f  -> 0.0f
            accNow <= 20f -> (accNow - 8f) / (20f - 8f) * 0.5f
            accNow <= 60f -> 0.5f + (accNow - 20f) / (60f - 20f) * 0.5f
            else -> 1.0f
        }

        val svScore = when {
            usedSvCount >= 15 -> 0.0f
            usedSvCount >= 8  -> (15 - usedSvCount) / 7f * 0.6f
            else               -> 0.6f + (8 - usedSvCount).coerceAtMost(8) / 8f * 0.4f
        }

        val cn0Score = when {
            avgCn0 >= 35f -> 0.0f
            avgCn0 >= 20f -> (35f - avgCn0) / 10f * 0.7f
            else          -> 0.8f + (25f - avgCn0).coerceAtMost(15f) / 15f * 0.3f
        }

        // 가중치: accuracy 0.30, usedSv 0.30, cn0 0.30, no-callback 0.15
        val indoorLikelihoodRaw = (
            accScore * 0.30f +
            svScore * 0.30f +
            cn0Score * 0.30f +
            noCallbackScore * 0.15f
        ).coerceIn(0f, 1f)

        // 최근 5회 점수 큐잉 후 평균을 현재 점수로 사용
        likelihoodWindow.addLast(indoorLikelihoodRaw)
        while (likelihoodWindow.size > likelihoodWindowMax) likelihoodWindow.removeFirst()
        val indoorLikelihood = if (likelihoodWindow.isNotEmpty())
            (likelihoodWindow.sum() / likelihoodWindow.size).coerceIn(0f, 1f)
        else
            indoorLikelihoodRaw

        // Indoor/Outdoor strong-state hysteresis (two thresholds)
        // - Indoor: likelihood >= 0.7 for 5s
        // - Outdoor: likelihood < 0.45 for 5s
        if (indoorLikelihood >= 0.7f) {
            if (strongIndoorStartTime == null) {
                strongIndoorStartTime = nowMs
            } else if (nowMs - strongIndoorStartTime!! >= 5000L) {
                listener.onStrongIndoorConfirmed()
                strongIndoorStartTime = null // reset after trigger
            }
            // firmly indoor → reset outdoor timer
            strongOutdoorStartTime = null
        } else if (indoorLikelihood < 0.45f) {
            if (strongOutdoorStartTime == null) {
                strongOutdoorStartTime = nowMs
            } else if (nowMs - strongOutdoorStartTime!! >= 5000L) {
                listener.onStrongOutdoorConfirmed()
                strongOutdoorStartTime = null // reset after trigger
            }
            // firmly outdoor → reset indoor timer
            strongIndoorStartTime = null
        } else {
            // gray zone (0.45 ≤ likelihood < 0.7) → reset both timers to avoid stale confirmations
            strongIndoorStartTime = null
            strongOutdoorStartTime = null
        }

        val label = when {
            indoorLikelihood >= 0.7f -> "실내(높음)"
            indoorLikelihood >= 0.45f -> "실내(가능)"
            else -> "실외"
        }
        Log.d("indoorestimate", "${indoorLikelihoodRaw}")
        listener.onIndoorLikelihoodUpdated(indoorLikelihood)

        val debugMsg = "IndoorLikelihood: %.2f [%s] (noCb: %.2f, accS: %.2f, svS: %.2f, cn0S: %.2f | usedSv: %d, avgCn0: %.1f, accNow: %.1f)".format(
            indoorLikelihood, label, noCallbackScore, accScore, svScore, cn0Score, usedSvCount, avgCn0, accNow
        )
        listener.onDebugMessage(debugMsg)
    }
}