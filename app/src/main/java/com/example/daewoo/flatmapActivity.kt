package com.example.daewoo


import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fifth.maplocationlib.IndoorEstimate
import com.fifth.maplocationlib.NativeLib
import com.unity3d.player.IUnityPlayerLifecycleEvents
import com.unity3d.player.UnityPlayer
import com.example.daewoo.utils.GeofenceWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import android.widget.Toast
import kotlinx.coroutines.Job
//import kotlinx.coroutines.delay // Duplicate import
//import kotlinx.coroutines.launch // Duplicate import
import android.os.Handler
import android.os.Looper
import com.example.daewoo.bg.AppSharedState
import com.example.daewoo.bg.FlatmapBackgroundService
import com.example.daewoo.utils.PreferenceHelper

import androidx.annotation.RawRes
import org.json.JSONObject

// 김명권 1031 추가
import okhttp3.OkHttpClient
import com.example.daewoo.utils.sendLocationData
import com.example.daewoo.dtos.LocationDto
import com.example.daewoo.LoginActivity
// 김명권 1031 추가 끝

class flatmapActivity : AppCompatActivity(), SensorEventListener, IUnityPlayerLifecycleEvents {
    // Default GPS fallback coordinates and accuracy
    private val DEFAULT_LAT = 37.3908
    private val DEFAULT_LNG = 126.9823
    private val DEFAULT_ACC_STR = "5"
    private lateinit var indoorEstimator: IndoorEstimate
    private var testbed: String = ""
    private var startScreen: String = "MAIN"
    private var startScreenPending: Boolean = false
    private var appStartTime: Long = 0
    private var indoorUpdateJob: Job? = null
    private var gpsResendJob: Job? = null

    // Initial GPS payload to send after Unity is ready
    private var pendingInitialGpsPayload: String? = null

    // POI dictionary parsed from Unity: name -> (lat, lng)
    private val poiDict: MutableMap<String, Pair<Double, Double>> = mutableMapOf()

    // 1031 김명권 추가
    private var userId: String? = null
    private var accessToken: String? = null
    private val logClient = OkHttpClient()
    // 1031 김명권 추가 끝

    private var isAppRunning: Boolean
        get() = AppSharedState.isAppRunning
        set(value) {
            AppSharedState.isAppRunning = value
        }
    private var isFlatmapServiceActive: Boolean
        get() = AppSharedState.flatmapBackgroundActive
        set(value) {
            AppSharedState.flatmapBackgroundActive = value
        }


    /** Unity messaging control flag and helper */
    private var allowUnityMessages: Boolean = false
    private var unityAwaitingScene: Boolean = false
    private var unityPendingSceneCode: String? = null
    private var unityQueuedSceneCode: String? = null
    private var unityLastRequestedSceneCode: String? = null
    private fun unitySend(obj: String?, method: String?, param: String?, skipGate: Boolean = false) {
        try {
            val o = obj?.trim().orEmpty()
            val m = method?.trim().orEmpty()
            val p = param ?: ""
            if (o.isEmpty() || m.isEmpty()) {
                Log.w("UnitySend", "Skip send: empty object/method")
                return
            }
            if (!skipGate && !allowUnityMessages) {
                Log.w("UnitySend", "Skip send: Unity not ready yet")
                return
            }
            UnityHolder.sendMessage(o, m, p)
        } catch (t: Throwable) {
            Log.e("UnitySend", "UnitySendMessage threw: ${t.message}")
        }
    }

    protected fun updateUnityCommandLineArguments(cmdLine: String?): String? {
        return cmdLine
    }

    private lateinit var sensorManager: SensorManager

    lateinit var locationManager: LocationManager
    lateinit var locationListener: LocationListener


    // 최근 위치 보관 (코루틴 주기 업데이트용)
    private var lastLocation: android.location.Location? = null

    // 마지막 위치 콜백 시각(ms)
    private var lastLocationUpdateAt: Long = 0L


    @SuppressLint("MissingPermission")
    private fun getFreshLastLocation(lm: LocationManager): android.location.Location? {
        val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val net =
            runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val pas =
            runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        Log.d("gps_init2", "${gps}, ${net}, ${pas}")

        val candidates = listOfNotNull(gps, net, pas)
        if (candidates.isEmpty()) return null

        return candidates.minWithOrNull(Comparator { a, b ->
            val accA = if (a.hasAccuracy()) a.accuracy else Float.MAX_VALUE
            val accB = if (b.hasAccuracy()) b.accuracy else Float.MAX_VALUE
            when {
                accA < accB -> -1
                accA > accB -> 1
                else -> {
                    val tA = a.time.takeIf { it > 0 } ?: 0L
                    val tB = b.time.takeIf { it > 0 } ?: 0L
                    // Newer wins on tie
                    -tA.compareTo(tB)
                }
            }
        })
    }

//    lateinit var debug: TextView


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appStartTime = System.currentTimeMillis()
        requestAllPermissions()

        setContentView(R.layout.activity_flatmap)

        // 사용자 정보 및 토큰 로드 1031 김명권 추가
        val pref = getSharedPreferences("USER_PREF", Context.MODE_PRIVATE)
        this.userId = pref.getString("USER_ID", "null")
        this.accessToken = pref.getString("ACCESS_TOKEN", null)

        // 토큰이 없으면 로그인 화면으로
        if (this.accessToken == null || this.userId == null) {
            Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return // onCreate 중단
        }
        // 1031 김명권 추가 끝

        patchUnityCurrentActivity()

        // Load POIs from res/raw/poi.json (no Unity needed)
        if (poiDict.isEmpty()) {
            loadPoiFromRaw()
        }

//        debug = findViewById(R.id.flatmapdebug)

        indoorEstimator = IndoorEstimate(this, object : IndoorEstimate.IndoorLikelihoodListener {
            override fun onIndoorLikelihoodUpdated(likelihood: Float) {
                // This can be used to log or for minor UI updates if needed
                Log.d("flatmapActivity", "Indoor Likelihood: $likelihood")
                AppSharedState.flatmapIndoorLikelihood = likelihood
//                this@flatmapActivity.onIndoorLikelyDetected()

            }

            override fun onStrongIndoorConfirmed() {
                AppSharedState.flatmapStrongIndoorConfirmed = true
                this@flatmapActivity.onStrongIndoorConfirmed()
            }

            override fun onStrongOutdoorConfirmed() {
                AppSharedState.flatmapStrongIndoorConfirmed = false
            }

            override fun onDebugMessage(message: String) {
                AppSharedState.flatmapIndoorDebugMessage = message
//                runOnUiThread {
//                    debug.text = message
//                }
            }
        })

        // --- Navigation data verification (LoginActivity -> flatmapActivity) ---

//        debug.append("\n[NAV] uid=${matchUid}, at=${matchAt}, rt=${matchRt}")

        UnityHolder.initOnce(this)
        val unityContainer = findViewById<FrameLayout>(R.id.unitySurfaceView2)
        UnityHolder.attachTo(unityContainer)
        UnityHolder.player.requestFocus()

        startScreen = PreferenceHelper.getStartScreen(this, "MAIN")
        testbed = if (startScreen.equals("main", ignoreCase = true)) "" else startScreen
        startScreenPending = true
        Log.d("flatmapqwer", startScreen)
        unityContainer.post {
            sendStartScreenIfReady()
        }


        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = (getSystemService(Context.LOCATION_SERVICE) as LocationManager)

        Log.d("GPS_INIT", "Activity onCreate: seed initial GPS (single-shot)")
        if (AppSharedState.flatmapLastGpsPayload != null) {
            pendingInitialGpsPayload = AppSharedState.flatmapLastGpsPayload
            if (AppSharedState.flatmapLastGpsTimestamp > 0L) {
                lastLocationUpdateAt = AppSharedState.flatmapLastGpsTimestamp
            }
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val chosen = getFreshLastLocation(locationManager)
            if (chosen != null) {
                lastLocation = chosen
                lastLocationUpdateAt = chosen.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                val latStr = "%.6f".format(chosen.latitude)
                val lngStr = "%.6f".format(chosen.longitude)
                val accStr =
                    if (chosen.hasAccuracy()) "${"%.1f".format(chosen.accuracy)}" else DEFAULT_ACC_STR
                pendingInitialGpsPayload = "$latStr,$lngStr,$accStr"
                AppSharedState.flatmapLastGpsPayload = pendingInitialGpsPayload
                AppSharedState.flatmapLastGpsTimestamp = lastLocationUpdateAt
                Log.i("GPS_INIT", "Seeded initial GPS from lastKnown → $pendingInitialGpsPayload")
            } else {
                lastLocationUpdateAt = System.currentTimeMillis()
                pendingInitialGpsPayload =
                    "${"%.6f".format(DEFAULT_LAT)},${"%.6f".format(DEFAULT_LNG)},$DEFAULT_ACC_STR"
                AppSharedState.flatmapLastGpsPayload = pendingInitialGpsPayload
                AppSharedState.flatmapLastGpsTimestamp = lastLocationUpdateAt
                Log.i(
                    "GPS_INIT",
                    "No lastKnown available. Using default → $pendingInitialGpsPayload"
                )
            }
        } else {
            Log.w("GPS_INIT", "Skipping initial GPS seed: permission not granted yet")
        }

        locationListener = LocationListener { location ->
            if (location != null) {
                val lat = location.latitude
                val lng = location.longitude

                lastLocation = location
                lastLocationUpdateAt = System.currentTimeMillis()

                val acc = if (location.hasAccuracy()) location.accuracy else 5f
                val payload = "${"%.6f".format(lat)},${"%.6f".format(lng)},${"%.1f".format(acc)}"
                unitySend("MapWebViewBridge", "OnGps", payload)
                Log.d("GPS_INIT", "Seeded initial GPS from LocationManager → $payload")
                AppSharedState.flatmapLastGpsPayload = payload
                AppSharedState.flatmapLastGpsTimestamp = lastLocationUpdateAt
                GeofenceWatcher.onLocation(applicationContext, lat, lng, lastLocationUpdateAt)
                // No longer calling old updateIndoorEstimate here

                // 서버로 위치 데이터 전송 // 1031 김명권 추가 끝
                val locationData = LocationDto(
                    userId = this.userId ?: "guest",
                    mapId = 0, // -1은 '실외' 또는 '맵 없음'을 의미
                    userX = lng, // userX에 경도(Longitude)
                    userY = lat, // userY에 위도(Latitude)
                    userZ = if (location.hasAltitude()) location.altitude else 0.0,
                    userDirection = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
                    userFloor = 0.0, // 실외이므로 0층
                    userStatus = "Active", // 앱이 활성화된 상태
                    background = false // 액티비티가 포그라운드에 있음
                )
                sendLogDataToServer(locationData)
                // 1031 김명권 추가 끝
            } else {
                Log.w("LocationListener", "Received null location update from LocationManager.")
            }
        }
        // Do not start location updates, gps resend job, or sendInitialGpsIfReady() here.
        // Only set up the listener, indoorEstimator, UnityHolder, and prepare cached GPS.
        // Do not start location updates, gps resend job, or sendInitialGpsIfReady() here.
        // Only set up the listener, indoorEstimator, UnityHolder, and prepare cached GPS.
    }

    private fun patchUnityCurrentActivity() {
        try {
            val unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer")
            val currentActivityField = unityPlayerClass.getDeclaredField("currentActivity")
            currentActivityField.isAccessible = true
            val cur = currentActivityField.get(null)
            if (cur !== this) {
                currentActivityField.set(null, this)
                Log.d("UnityBridge", "Patched UnityPlayer.currentActivity to flatmapActivity")
            }
        } catch (t: Throwable) {
            Log.e(
                "UnityBridge",
                "Failed to patch UnityPlayer.currentActivity in flatmapActivity",
                t
            )
        }
    }

    private fun startFlatmapServiceIfNeeded() {
        if (!isFlatmapServiceRunning()) {
            PreferenceHelper.setStartScreen(this, testbed)
            val serviceIntent = Intent(this, FlatmapBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        isFlatmapServiceActive = true
    }

    private fun stopFlatmapServiceIfRunning() {
        if (isFlatmapServiceRunning()) {
            stopService(Intent(this, FlatmapBackgroundService::class.java))
        }
        isFlatmapServiceActive = false
    }

    @Suppress("DEPRECATION")
    private fun isFlatmapServiceRunning(): Boolean {
        val activityManager =
            getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return activityManager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == FlatmapBackgroundService::class.java.name }
    }

    private fun requestAllPermissions() {

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }

    private fun sendStartScreenIfReady() {
        if (startScreen.isBlank()) return
        if (!startScreenPending) return
        if (unityAwaitingScene && unityPendingSceneCode == startScreen) {
            startScreenPending = false
            Log.d("UnityInit", "Skip duplicate pending scene → $startScreen")
            return
        }
        if (unityAwaitingScene) {
            unityQueuedSceneCode = startScreen
            startScreenPending = false
            Log.d("UnityInit", "Queued start screen request → $startScreen")
            return
        }
        if (!unityAwaitingScene && unityLastRequestedSceneCode == startScreen) {
            startScreenPending = false
            Log.d("UnityInit", "Skip duplicate start screen → $startScreen")
            return
        }
        startScreenPending = false
        unityPendingSceneCode = startScreen
        unitySend("AndroidBridge", "OnTestbedSelected", startScreen, skipGate = true)
        unityAwaitingScene = true
        allowUnityMessages = false
        unityLastRequestedSceneCode = startScreen
        Log.d("UnityInit", "Sent start screen to Unity → $startScreen")
    }

    private fun sendInitialGpsIfReady() {
        if (!allowUnityMessages) return
        // Prefer pending payload; otherwise synthesize from lastLocation; otherwise fallback to default
        val payload = pendingInitialGpsPayload
            ?: AppSharedState.flatmapLastGpsPayload
            ?: run {
                val loc = lastLocation ?: getFreshLastLocation(locationManager)
                if (loc != null) {
                    val latStr = "%.6f".format(loc.latitude)
                    val lngStr = "%.6f".format(loc.longitude)
                    val accStr =
                        if (loc.hasAccuracy()) "${"%.1f".format(loc.accuracy)}" else DEFAULT_ACC_STR
                    val computed = "$latStr,$lngStr,$accStr"
                    AppSharedState.flatmapLastGpsPayload = computed
                    AppSharedState.flatmapLastGpsTimestamp = System.currentTimeMillis()
                    GeofenceWatcher.onLocation(applicationContext, loc.latitude, loc.longitude, AppSharedState.flatmapLastGpsTimestamp)
                    computed
                } else {
                    val fallback =
                        "${"%.6f".format(DEFAULT_LAT)},${"%.6f".format(DEFAULT_LNG)},$DEFAULT_ACC_STR"
                    AppSharedState.flatmapLastGpsPayload = fallback
                    AppSharedState.flatmapLastGpsTimestamp = System.currentTimeMillis()
                    GeofenceWatcher.onLocation(applicationContext, DEFAULT_LAT, DEFAULT_LNG, AppSharedState.flatmapLastGpsTimestamp)
                    fallback
                }
            }
        pendingInitialGpsPayload = payload
        unitySend("MapWebViewBridge", "OnGps", payload)
        Log.d("init", "[INIT] ensured GPS → $payload")
    }

    @Suppress("unused")
    fun onUnitySceneLoaded(sceneName: String) {
        runOnUiThread {
            Log.d("UnityBridge", "Scene loaded: $sceneName (flatmapActivity)")
            unityAwaitingScene = false
            allowUnityMessages = true
            unityPendingSceneCode = null
            val queued = unityQueuedSceneCode
            unityQueuedSceneCode = null
            if (queued != null && queued != startScreen) {
                startScreen = queued
                PreferenceHelper.setStartScreen(this@flatmapActivity, startScreen)
                testbed = if (startScreen.equals("main", ignoreCase = true)) "" else startScreen
            }
            if (queued != null) {
                startScreenPending = true
                sendStartScreenIfReady()
            } else {
                sendInitialGpsIfReady()
            }
        }
    }

    private fun resendCachedGpsOnce() {
        if (!allowUnityMessages) return
        val payload = pendingInitialGpsPayload
            ?: AppSharedState.flatmapLastGpsPayload
            ?: run {
                val loc = lastLocation ?: getFreshLastLocation(locationManager)
                if (loc != null) {
                    val latStr = "%.6f".format(loc.latitude)
                    val lngStr = "%.6f".format(loc.longitude)
                    val accStr =
                        if (loc.hasAccuracy()) "${"%.1f".format(loc.accuracy)}" else DEFAULT_ACC_STR
                    val computed = "$latStr,$lngStr,$accStr"
                    AppSharedState.flatmapLastGpsPayload = computed
                    AppSharedState.flatmapLastGpsTimestamp = System.currentTimeMillis()
                    computed
                } else {
                    val fallback =
                        "${"%.6f".format(DEFAULT_LAT)},${"%.6f".format(DEFAULT_LNG)},$DEFAULT_ACC_STR"
                    AppSharedState.flatmapLastGpsPayload = fallback
                    AppSharedState.flatmapLastGpsTimestamp = System.currentTimeMillis()
                    fallback
                }
            }
        pendingInitialGpsPayload = payload
        unitySend("MapWebViewBridge", "OnGps", payload)
        Log.d("GPS_REPEAT", "Resent cached GPS → $payload")
    }

    // 위치 데이터 전송 함수 1031 김명권 추가
    private fun sendLogDataToServer(locationData: LocationDto) {
        val token = this.accessToken
        if (token == null) {
            Log.w("flatmapActivity", "AccessToken is null. Skip sending logs.")
            return
        }

        // utils/LogDataUtils.kt의 sendLocationData 함수 사용
        sendLocationData(logClient, locationData, token) {
            // 401 인증 만료 시
            runOnUiThread {
                Toast.makeText(this@flatmapActivity, "인증 오류: 토큰 만료. 재로그인 해주세요.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@flatmapActivity, LoginActivity::class.java))
                finish()
            }
        }
    }
    // 1031 김명권 추가 끝

    fun locationSetTrigger(testbed: String) {
        runOnUiThread {
            suppressFlatmapServiceOnStop = true
            PreferenceHelper.setStartScreen(this@flatmapActivity, testbed)
            PreferenceHelper.setIndoorPoiName(this@flatmapActivity, null)
            Log.d("indoorintent", testbed)

            // Reset indoor confirmation flag so the next flatmap launch doesn't auto trigger a return
            AppSharedState.flatmapStrongIndoorConfirmed = false

            stopFlatmapServiceIfRunning()
            startActivity(Intent(this@flatmapActivity, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        suppressFlatmapServiceOnStop = false
        stopFlatmapServiceIfRunning()
        isAppRunning = true
        UnityHolder.onResume()
        patchUnityCurrentActivity()

        if (pendingInitialGpsPayload == null) {
            pendingInitialGpsPayload = AppSharedState.flatmapLastGpsPayload
            if (AppSharedState.flatmapLastGpsTimestamp > 0L) {
                lastLocationUpdateAt = AppSharedState.flatmapLastGpsTimestamp
            }
        }
        allowUnityMessages = !unityAwaitingScene
        sendStartScreenIfReady()
        sendInitialGpsIfReady()
        if (poiDict.isEmpty()) {
            loadPoiFromRaw()
        }
        if (AppSharedState.flatmapStrongIndoorConfirmed) {
            AppSharedState.flatmapStrongIndoorConfirmed = false
            onStrongIndoorConfirmed()
        }
        if (gpsResendJob == null || !gpsResendJob!!.isActive) {
            gpsResendJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val ageMs = now - lastLocationUpdateAt
                    if (ageMs >= 1000L && lastLocation == null) {
                        lastLocation = getFreshLastLocation(locationManager) ?: lastLocation
                    }
                    val hasCached = (pendingInitialGpsPayload != null) || (lastLocation != null)
                    if (hasCached && ageMs >= 1000L) {
                        resendCachedGpsOnce()
                    }
                    delay(1000)
                }
            }
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                300L,
                0.5f,
                locationListener
            )
        } catch (e: SecurityException) {
            Log.e("GPS", "Location permission missing onResume: ${e.message}")
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            indoorEstimator.start()
        } else {
            Log.w(
                "flatmapActivity",
                "Location permission not granted. IndoorEstimator not started."
            )
            // Optionally, request permissions again or inform the user
        }
    }

    override fun onPause() {
        super.onPause()
//        UnityHolder.onPause()

        allowUnityMessages = false
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: SecurityException) {
            Log.e("GPS", "removeUpdates permission issue: ${e.message}")
        } catch (t: Throwable) {
            Log.w("GPS", "removeUpdates failed: ${t.message}")
        }
        indoorEstimator.stop()
        sensorManager.unregisterListener(this)
        indoorUpdateJob?.cancel()
        gpsResendJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFlatmapServiceIfRunning()
        indoorEstimator.stop() // Ensure it's stopped
        indoorUpdateJob?.cancel()
        gpsResendJob?.cancel()
        isAppRunning = false
        AppSharedState.flatmapStrongIndoorConfirmed = false
    }


    override fun onLowMemory() {
        super.onLowMemory()
        UnityHolder.player.lowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) {
            UnityHolder.player.lowMemory()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        UnityHolder.player.configurationChanged(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        UnityHolder.player.windowFocusChanged(hasFocus)
        patchUnityCurrentActivity()

    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return UnityHolder.player.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                return true
            }
        }

        return UnityHolder.player.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        return UnityHolder.player.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        return UnityHolder.player.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
    }

    override fun onSensorChanged(p0: SensorEvent?) {
        TODO("Not yet implemented")
    }


    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        TODO("Not yet implemented")
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        isAppRunning = false
        val shouldStartService = !suppressFlatmapServiceOnStop
        suppressFlatmapServiceOnStop = false
        if (shouldStartService) {
            startFlatmapServiceIfNeeded()
        }
        super.onStop()
    }

    override fun onUnityPlayerUnloaded() {
    }

    override fun onUnityPlayerQuitted() {
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newStartScreen = PreferenceHelper.getStartScreen(this, startScreen)
        if (!newStartScreen.isNullOrBlank()) {
            startScreen = newStartScreen
            PreferenceHelper.setStartScreen(this, startScreen)
            testbed = if (startScreen.equals("main", ignoreCase = true)) "" else startScreen
            startScreenPending = true
            sendStartScreenIfReady()
        }
        UnityHolder.player.newIntent(intent)
    }


    private fun updateIndoorWhenPossible() {
        val currentLoc = lastLocation
        val currentTime = System.currentTimeMillis()

        // If location is null or older than 5 seconds, consider it stale.
        if (currentLoc == null || (currentTime - lastLocationUpdateAt) > 5000L) {
            val baseTime = if (lastLocationUpdateAt != 0L) lastLocationUpdateAt else appStartTime
            val staleMs = (currentTime - baseTime).coerceAtLeast(0L)

//            runOnUiThread {
//                debug.text = buildString {
//                    append("위치 신호: 수신 중단\n")
//                    append("stale=${"%.1f".format(staleMs / 1000f)}s (no fix)\n")
//                    append("실내 가능성: 추정 보류 (위치 미수신)")
//                }
//            }
            Log.d("IndoorEst", "flatmapActivity: no-fix or stale GPS, stale=${staleMs}ms")
        } else {
            // IndoorEstimate.onDebugMessage will handle updating the debug text with detailed info.
            // No action needed here if GPS is active, as IndoorEstimate's listener will update UI.
        }
    }

    private fun onStrongIndoorConfirmed() {
        AppSharedState.flatmapStrongIndoorConfirmed = true
        Log.i(
            "IndoorEst",
            "Indoor confirmed in flatmapActivity; computing nearest POI locally (raw)"
        )
        if (poiDict.isEmpty()) {
            loadPoiFromRaw()
        }
        val handled = tryComputeNearestPoiAndTrigger()
        if (!handled) {
            runOnUiThread {
                Toast.makeText(this, "POI 정보가 없거나 위치가 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var suppressFlatmapServiceOnStop = false

    private var waitingIndoorConfirm = false
    private fun requestIndoorConfirmFromUnity() {
        waitingIndoorConfirm = true
        unitySend("MapWebViewBridge", "OnBuildingHint", "indoor")
        android.util.Log.i("Indoor", "Request indoor confirm -> Unity (OnBuildingHint: indoor)")
    }

    fun onPoiDictJson(json: String) {
        try {
            Log.d("UnityBridge", "POI_DICT: $json")
            val count = parsePoiJson(json)
            if (AppSharedState.flatmapStrongIndoorConfirmed && count > 0) {
                tryComputeNearestPoiAndTrigger()
            }
        } catch (t: Throwable) {
            Log.e("UnityBridge", "Failed to parse POI_DICT: ${t.message}")
        }
    }

    @JvmName("onNearestPoi")
    fun onNearestPoi(json: String?) {
        try {
            if (!waitingIndoorConfirm) {
                android.util.Log.w("Indoor", "onNearestPoi ignored: not waiting (Unity path)")
                return
            }
            waitingIndoorConfirm = false

            val obj = org.json.JSONObject(json ?: "{}")
            val name = obj.optString("name", "")
            val lat = obj.optDouble("lat", 0.0)
            val lng = obj.optDouble("lng", 0.0)
            val dist = obj.optDouble("dist", -1.0)

            android.util.Log.i(
                "Indoor",
                "Nearest from Unity: name=$name, ($lat,$lng), dist=${"%.1f".format(dist)}m"
            )

//            val buildingCode = when {
//                name.contains("109", ignoreCase = true) -> "109"
//                name.contains("110", ignoreCase = true) -> "110"
//                else -> {
//                    android.widget.Toast.makeText(this, "알 수 없는 POI: $name", android.widget.Toast.LENGTH_SHORT).show()
//                    return
//                }
//            }
            val buildingCode = "109"

            locationSetTrigger(buildingCode) // 여기 주석처리하면 씬 전환 안됨 1031 김명권
            android.widget.Toast.makeText(
                this,
                "실내 확정: $name → 씬 전환",
                android.widget.Toast.LENGTH_SHORT
            ).show()

        } catch (t: Throwable) {
            waitingIndoorConfirm = false
            android.util.Log.e("Indoor", "onNearestPoi parse failed: ${t.message}")
        }
    }

    private fun onIndoorLikelyDetected() {
        if (!waitingIndoorConfirm) {
            requestIndoorConfirmFromUnity()
        }
    }

//    @androidx.annotation.Keep
//    fun onNearestPoiFromUnity(poiName: String?, latitude: Double, longitude: Double, altitude: Double) {
//        val currentPoiName = poiName ?: "Unknown POI"
//        Log.i("Indoor_UnityCallback", "onNearestPoiFromUnity called:")
//        Log.i("Indoor_UnityCallback", "  POI Name: $currentPoiName")
//        Log.i("Indoor_UnityCallback", "  Latitude: $latitude")
//        Log.i("Indoor_UnityCallback", "  Longitude: $longitude")
//        Log.i("Indoor_UnityCallback", "  Altitude: $altitude")
//
//        runOnUiThread {
////            Toast.makeText(this, "Unity Nearest POI: $currentPoiName", Toast.LENGTH_LONG).show()
////            debug.append("\n[Unity] Nearest POI: $currentPoiName (${"%.5f".format(latitude)}, ${"%.5f".format(longitude)})")
//        }
//
////        val testbedToSet = when {
////            currentPoiName.contains("109", ignoreCase = true) -> "109"
////            currentPoiName.contains("110", ignoreCase = true) -> "110"
////            else -> {
////                Log.w("Indoor_UnityCallback", "Unknown POI name for testbed determination: $currentPoiName")
////                null
////            }
////        }
//        val testbedToSet = "109"
//
//        if (testbedToSet != null) {
//            Log.i("Indoor_UnityCallback", "Determined testbed: $testbedToSet from POI: $currentPoiName. Triggering scene change.")
//            locationSetTrigger(testbedToSet)
//        } else {
//            Log.e("Indoor_UnityCallback", "Could not determine testbed from POI: $currentPoiName. Scene change aborted.")
//            runOnUiThread {
//                Toast.makeText(this, "알 수 없는 POI($currentPoiName)입니다. 장소를 설정할 수 없습니다.", Toast.LENGTH_LONG).show()
//            }
//        }
//    }
//

    // Haversine distance in meters
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    // Try to compute nearest POI from poiDict and current/last location.
    // Returns true if a scene change was triggered.
    private fun tryComputeNearestPoiAndTrigger(): Boolean {
        // Require at least one POI and a location reference
        val loc = lastLocation ?: run {
            // As a fallback, parse the cached payload if available: "lat,lng,acc"
            val payload = pendingInitialGpsPayload ?: AppSharedState.flatmapLastGpsPayload
            if (payload != null) {
                val parts = payload.split(",")
                if (parts.size >= 2) {
                    val lat = parts[0].toDoubleOrNull()
                    val lng = parts[1].toDoubleOrNull()
                    if (lat != null && lng != null) {
                        android.location.Location("cached").apply {
                            latitude = lat; longitude = lng
                        }
                    } else null
                } else null
            } else null
        } ?: return false

        if (poiDict.isEmpty()) return false

        var bestName: String? = null
        var bestLat = 0.0
        var bestLng = 0.0
        var bestDist = Double.MAX_VALUE

        for ((name, pair) in poiDict) {
            val (plat, plng) = pair
            val d = haversineMeters(loc.latitude, loc.longitude, plat, plng)
            if (d < bestDist) {
                bestDist = d
                bestName = name
                bestLat = plat
                bestLng = plng
            }
        }

        if (bestName == null) return false

        Log.i(
            "Indoor",
            "Nearest (local): name=$bestName, ($bestLat,$bestLng), dist=${"%.1f".format(bestDist)}m"
        )

        // Map POI name to building testbed code
        var buildingCode = when {
            bestName.contains("109") -> "109"
            bestName.contains("110") -> "110"
            else -> null
        }
        buildingCode = "109"
        bestName = "109동"
        return if (buildingCode != null) {
            locationSetTrigger(buildingCode)
            runOnUiThread {
                Toast.makeText(this, "실내 확정(로컬 계산): $bestName → 씬 전환", Toast.LENGTH_SHORT).show()
            }
            true
        } else {
            Log.w("Indoor", "Unknown POI name for testbed: $bestName (no scene change)")
            false
        }
    }

    /** Parse POI JSON text of the form {"name":[lat,lng], ...} into poiDict. Returns parsed count. */
    private fun parsePoiJson(json: String): Int {
        return try {
            Log.d("UnityBridge", "POI_DICT(from raw): $json")
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
            Log.i("UnityBridge", "Parsed POI_DICT entries = $count")
            count
        } catch (t: Throwable) {
            Log.e("UnityBridge", "Failed to parse POI_DICT: ${t.message}")
            0
        }
    }

    /** Load POIs from res/raw/poi.json; returns parsed count. */
    private fun loadPoiFromRaw(@RawRes resId: Int = R.raw.poi): Int {
        return try {
            val text = resources.openRawResource(resId).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            parsePoiJson(text)
        } catch (t: Throwable) {
            Log.e("UnityBridge", "Failed to load res/raw/poi.json: ${t.message}")
            0
        }
    }
}
