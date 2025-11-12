package com.example.daewoo.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.annotation.RawRes
import com.example.daewoo.R
import com.fifth.maplocationlib.IndoorEstimate
import org.json.JSONObject
import kotlin.math.max
import com.example.daewoo.utils.GeofenceWatcher
import com.example.daewoo.utils.PreferenceHelper

// 1031 김명권 추가
import com.example.daewoo.LoginActivity
import com.example.daewoo.dtos.LocationDto
import com.example.daewoo.utils.sendLocationData
import okhttp3.OkHttpClient
// 1031 김명권 추가 끝

class FlatmapBackgroundService : Service(), LocationListener {

    private val notificationId = 2001
    private val channelId = "flatmap_background_channel"
    private lateinit var locationManager: LocationManager
    private var indoorEstimator: IndoorEstimate? = null
    private var indoorEstimatorRunning = false
    private var locationUpdatesActive = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 서버 전송용 변수 1031 김명권 추가
    private var userId: String? = null
    private var accessToken: String? = null
    private val logClient = OkHttpClient()
    // 1031 김명권 추가 끝

    // Local POI dictionary loaded from res/raw/poi.json
    private val poiDict: MutableMap<String, Pair<Double, Double>> = mutableMapOf()

    /** Parse POI JSON text of the form {"name":[lat,lng], ...} into poiDict. Returns parsed count. */
    private fun parsePoiJson(json: String): Int {
        return try {
            Log.d(TAG, "POI_DICT(from raw): $json")
            val obj = JSONObject(json)
            poiDict.clear()
            var count = 0
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val arr = obj.optJSONArray(name)
                if (arr != null && arr.length() >= 2) {
                    val lat = arr.optDouble(0, Double.NaN)
                    val lng = arr.optDouble(1, Double.NaN)
                    if (!lat.isNaN() && !lng.isNaN()) {
                        poiDict[name] = Pair(lat, lng)
                        count++
                    }
                }
            }
            Log.i(TAG, "Parsed POI_DICT entries = $count")
            count
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse POI_DICT: ${t.message}")
            0
        }
    }

    /** Load POIs from res/raw/poi.json; returns parsed count. */
    private fun loadPoiFromRaw(@RawRes resId: Int = R.raw.poi): Int {
        return try {
            val text = resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parsePoiJson(text)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load res/raw/poi.json: ${t.message}")
            0
        }
    }

    /** Compute nearest POI from last shared GPS and trigger LocationService. Returns true if handled. */
    private fun tryComputeNearestPoiAndTrigger(): Boolean {
        val payload = AppSharedState.flatmapLastGpsPayload ?: return false
        val parts = payload.split(',')
        if (parts.size < 2) return false
        val lat = parts[0].toDoubleOrNull() ?: return false
        val lng = parts[1].toDoubleOrNull() ?: return false
        if (poiDict.isEmpty()) return false

        var bestName: String? = null
        var bestDist = Double.MAX_VALUE
        for ((name, ll) in poiDict) {
            val d = haversineMeters(lat, lng, ll.first, ll.second)
            if (d < bestDist) {
                bestDist = d
                bestName = name
            }
        }
        var name = bestName ?: return false
        Log.i(TAG, "Nearest(LOCAL): name=$name, dist=${"%.1f".format(bestDist)}m")
        val buildingCode = "109"
        name="109"
        locationSetTrigger(buildingCode, name)
        mainHandler.post {
            Toast.makeText(applicationContext, "실내 확정: $name → 실내 추적 전환", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }

    private fun ensureIndoorEstimator(): IndoorEstimate {
        val existing = indoorEstimator
        if (existing != null) {
            return existing
        }
        return IndoorEstimate(this, indoorListener).also { indoorEstimator = it }
    }

    private val indoorListener = object : IndoorEstimate.IndoorLikelihoodListener {
        override fun onIndoorLikelihoodUpdated(likelihood: Float) {
            AppSharedState.flatmapIndoorLikelihood = likelihood
        }

        override fun onStrongIndoorConfirmed() {
            AppSharedState.flatmapStrongIndoorConfirmed = true
            this@FlatmapBackgroundService.onStrongIndoorConfirmed()
        }

        override fun onStrongOutdoorConfirmed() {
            AppSharedState.flatmapStrongIndoorConfirmed = false
            Log.i(TAG, "Strong outdoor confirmed (background).")
        }

        override fun onDebugMessage(message: String) {
            AppSharedState.flatmapIndoorDebugMessage = message
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 사용자 정보 및 토큰 로드 1031 김명권 추가
        val pref = getSharedPreferences("USER_PREF", Context.MODE_PRIVATE)
        this.userId = pref.getString("USER_ID", "null")
        this.accessToken = pref.getString("ACCESS_TOKEN", null)
        // 1031 김명권 추가 끝
    }

    // 위치 데이터 전송 함수 1031 김명권 추가
    private fun sendLogDataToServer(locationData: LocationDto) {
        val token = this.accessToken
        if (token == null) {
            Log.w(TAG, "AccessToken is null. Skip sending logs.")
            return
        }

        // utils/LogDataUtils.kt의 sendLocationData 함수 사용
        sendLocationData(logClient, locationData, token) {
            // 401 인증 만료 시
            mainHandler.post {
                Toast.makeText(applicationContext, "인증 오류: 토큰 만료. 재로그인 해주세요.", Toast.LENGTH_LONG).show()
                val intent = Intent(this@FlatmapBackgroundService, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            }
        }
    }
    // 1031 김명권 추가 끝

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(notificationId, buildNotification())
        AppSharedState.flatmapBackgroundActive = true
        startLocationUpdates()
        startIndoorEstimator()
        seedLastKnownLocation()
        if (poiDict.isEmpty()) {
            loadPoiFromRaw()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        stopIndoorEstimator()
        indoorEstimator = null
        AppSharedState.flatmapBackgroundActive = false
        stopForeground(true)
    }

    private fun startIndoorEstimator() {
        if (indoorEstimatorRunning) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot start IndoorEstimate: location permission missing.")
            AppSharedState.flatmapIndoorLikelihood = 0f
            AppSharedState.flatmapIndoorLikelihoodTimestamp = 0L
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            indoorEstimatorRunning = true
            mainHandler.post {
                runCatching { ensureIndoorEstimator().start() }
                    .onFailure { error ->
                        indoorEstimatorRunning = false
                        Log.e(TAG, "Failed to start IndoorEstimate in background: ${error.message}")
                    }
            }
        }
    }

    private fun stopIndoorEstimator() {
        if (!indoorEstimatorRunning) return
        indoorEstimatorRunning = false
        indoorEstimator?.let { estimator ->
            mainHandler.post {
                runCatching { estimator.stop() }
            }
        }
    }

    private fun onStrongIndoorConfirmed() {
        Log.i(TAG, "Strong indoor detected (background); computing nearest POI locally (raw)")
        if (poiDict.isEmpty()) {
            loadPoiFromRaw()
        }
        val handled = tryComputeNearestPoiAndTrigger()
        if (!handled) {
            Log.w(TAG, "Local POI compute unavailable; no Unity fallback (disabled)")
            mainHandler.post {
                Toast.makeText(applicationContext, "POI 정보가 없거나 현재 위치가 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun locationSetTrigger(testbed: String, poiName: String) {
        mainHandler.post {
            PreferenceHelper.setStartScreen(this@FlatmapBackgroundService, testbed)
            PreferenceHelper.setIndoorPoiName(this@FlatmapBackgroundService, poiName)
            val intent = Intent(this@FlatmapBackgroundService, LocationService::class.java)
            Log.i(TAG, "Handing indoor trigger to LocationService: testbed=$testbed, poi=$poiName")
            ContextCompat.startForegroundService(this@FlatmapBackgroundService, intent)
            stopLocationUpdates()
            stopIndoorEstimator()
            AppSharedState.flatmapBackgroundActive = false
            stopSelf()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        if (locationUpdatesActive) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission missing; background GPS will not run.")
            AppSharedState.flatmapLastGpsPayload = null
            AppSharedState.flatmapLastGpsTimestamp = 0L
            return
        }
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val registeredProviders = mutableListOf<String>()
        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    1000L,
                    0.5f,
                    this,
                    Looper.getMainLooper()
                )
                registeredProviders += provider
            }.onFailure { error ->
                Log.v(TAG, "Failed to register provider $provider: ${error.message}")
            }
        }
        locationUpdatesActive = registeredProviders.isNotEmpty()
        if (locationUpdatesActive) {
            Log.d(TAG, "Background location updates started (providers=${registeredProviders.joinToString()}).")
        } else {
            Log.w(TAG, "No location providers could be registered for background tracking.")
        }
    }

    private fun stopLocationUpdates() {
        if (!this::locationManager.isInitialized) return
        if (!locationUpdatesActive) return
        runCatching { locationManager.removeUpdates(this) }
            .onFailure { Log.w(TAG, "Failed to remove background location updates: ${it.message}") }
        locationUpdatesActive = false
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocation() {
        if (!hasLocationPermission()) return
        val hasFreshShared =
            AppSharedState.flatmapLastGpsPayload != null &&
                    AppSharedState.flatmapLastGpsTimestamp > 0L &&
                    System.currentTimeMillis() - AppSharedState.flatmapLastGpsTimestamp < STALE_LOCATION_THRESHOLD_MS
        if (hasFreshShared) {
            Log.d(TAG, "Seed skipped: shared GPS already populated (ts=${AppSharedState.flatmapLastGpsTimestamp})")
            return
        }
        val gps = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val network = runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val passive = runCatching { locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        val chosen = gps ?: network ?: passive
        chosen?.let { updateSharedLocation(it, "Seed last known location (${it.provider ?: "unknown"})") }
    }

    override fun onLocationChanged(location: Location) {
        updateSharedLocation(location, "FlatmapBackgroundService update (${location.provider ?: "unknown"})")
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onProviderEnabled(provider: String) {
        if (!this::locationManager.isInitialized) return
        locationUpdatesActive = false
        startLocationUpdates()
        seedLastKnownLocation()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onProviderDisabled(provider: String) {
        if (!this::locationManager.isInitialized) return
        stopLocationUpdates()
        if (hasLocationPermission()) {
            startLocationUpdates()
            seedLastKnownLocation()
        }
    }

    private fun updateSharedLocation(location: Location, reason: String) {
        val locationTimestamp = if (location.time > 0L) location.time else System.currentTimeMillis()
        if (AppSharedState.flatmapLastGpsTimestamp > 0L &&
            locationTimestamp + STALE_LOCATION_THRESHOLD_MS < AppSharedState.flatmapLastGpsTimestamp
        ) {
            Log.d(
                TAG,
                "Skipping stale background location ($reason): provider=${location.provider} ts=$locationTimestamp existing=${AppSharedState.flatmapLastGpsTimestamp}"
            )
            return
        }
        val latStr = "%.6f".format(location.latitude)
        val lngStr = "%.6f".format(location.longitude)
        val accStr = if (location.hasAccuracy()) "%.1f".format(location.accuracy) else "5"
        val storedTimestamp = max(locationTimestamp, System.currentTimeMillis())
        AppSharedState.flatmapLastGpsPayload = "$latStr,$lngStr,$accStr"
        AppSharedState.flatmapLastGpsTimestamp = storedTimestamp
        Log.d(TAG, "$reason → $latStr,$lngStr,$accStr (ts=$storedTimestamp)")
        GeofenceWatcher.onLocation(applicationContext, location.latitude, location.longitude, storedTimestamp)

        // 서버로 위치 데이터 전송 1031 김명권 추가
        // 앱이 포그라운드에 있는지 확인
        val isAppRunning = AppSharedState.isAppRunning

        val locationData = LocationDto(
            userId = this.userId ?: "guest",
            mapId = 0, // 0은 '실외'
            userX = location.longitude, // userX에 경도(Longitude)
            userY = location.latitude, // userY에 위도(Latitude)
            userZ = if (location.hasAltitude()) location.altitude else 0.0,
            userDirection = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
            userFloor = 0.0, // 실외이므로 0층
            userStatus = "Active", // 서비스가 실행 중이지만, 앱의 상태에 따라 결정
            background = !isAppRunning // 앱이 실행 중이지 않을 때(true)가 백그라운드 상태
        )
        sendLogDataToServer(locationData)
        // 1031 김명권 추가 끝
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Flatmap Background Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.enableVibration(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Flatmap 모드 유지 중")
            .setContentText("실내 추정과 위치를 계속 수집합니다.")
            .setOngoing(true)
            .setVibrate(longArrayOf(0L))
            .build()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Foreground location permission missing (background service).")
            return false
        }
        if (!backgroundGranted) {
            Log.w(TAG, "ACCESS_BACKGROUND_LOCATION not granted; background IndoorEstimate will be limited.")
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "FlatmapBgService"
        private const val STALE_LOCATION_THRESHOLD_MS = 10_000L
    }
}
