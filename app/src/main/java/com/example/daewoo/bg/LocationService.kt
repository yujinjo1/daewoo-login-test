package com.example.daewoo.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Vibrator
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.daewoo.LoginActivity
import com.example.daewoo.R
import com.example.daewoo.dtos.LocationDto
import com.example.daewoo.dtos.SensorDto
import com.example.daewoo.dtos.SensorXYZ
import com.example.daewoo.utils.PreferenceHelper
import com.example.daewoo.utils.sendLocationData
import com.example.daewoo.utils.sendSensorData
import com.fifth.maplocationlib.IndoorEstimate
import com.fifth.maplocationlib.MapMatching
import com.fifth.maplocationlib.NativeLib
import com.fifth.maplocationlib.RotationPatternDetector
import com.fifth.maplocationlib.UserStateMonitor
import com.fifth.maplocationlib.sensors.MovingAverage
import com.fifth.maplocationlib.utils.StairsAreaProvider
import com.fifth.pdr_ext.PDR
import com.fifth.pdr_ext.PDRM
import com.fifth.pdr_ext.SMcpp
import com.unity3d.player.UnityPlayer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.LinkedList
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import com.example.daewoo.utils.GeofenceWatcher

class LocationService : Service(), SensorEventListener, LocationListener {

    private val nativeLibOwnerTag = "LocationService"

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var startupStateCheckJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var indoorEstimator: IndoorEstimate? = null
    private var indoorEstimatorRunning = false
    private var outdoorSwitchTriggered = false

    private val userStateMonitor: UserStateMonitor
        get() = SharedComponentProvider.getUserStateMonitor(this)
    private val rotationPatternDetector: RotationPatternDetector
        get() = SharedComponentProvider.rotationPatternDetector
    private var stateflag: Boolean = false
    private lateinit var locationManager: LocationManager
    private var locationUpdatesActive = false
    private lateinit var mapMatching: MapMatching
    private lateinit var stairsArea: Map<Int, List<FloatArray>>
    private val floorChangeDetection: com.fifth.maplocationlib.FloorChangeDetection
        get() = SharedComponentProvider.getFloorChangeDetection(this, testbed)

    private var sensorMaster: SensorMaster? = null
    private var sensorMasterBound: Boolean = false
    private var sensorListenerRegistered: Boolean = false
    private var sensorBindRetryPending: Boolean = false
    private val sensorBindRetryRunnable = object : Runnable {
        override fun run() {
            sensorBindRetryPending = false
            connectSensorMaster()
        }
    }

    private val sensorServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SensorMaster.LocalBinder ?: return
            sensorMaster = binder.getService()
            sensorMasterBound = true
            registerWithSensorMaster()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            unregisterFromSensorMaster()
            sensorMasterBound = false
            sensorMaster = null
            scheduleSensorMasterBindRetry()
        }
    }

    private fun registerWithSensorMaster() {
        if (sensorMasterBound && !sensorListenerRegistered) {
            sensorMaster?.registerListener(this)
            sensorListenerRegistered = true
            clearSensorMasterBindRetry()
        }
    }

    private fun unregisterFromSensorMaster() {
        if (sensorListenerRegistered) {
            sensorMaster?.unregisterListener(this)
            sensorListenerRegistered = false
        }
    }

    private fun connectSensorMaster() {
        if (sensorMasterBound || sensorListenerRegistered) {
            return
        }
        if (!AppSharedState.sensorMasterRunning) {
            ContextCompat.startForegroundService(this, Intent(this, SensorMaster::class.java))
            scheduleSensorMasterBindRetry()
            return
        }
        val bound = bindService(Intent(this, SensorMaster::class.java), sensorServiceConnection, 0)
        if (!bound) {
            scheduleSensorMasterBindRetry()
        }
    }

    private fun scheduleSensorMasterBindRetry() {
        if (sensorBindRetryPending) return
        sensorBindRetryPending = true
        mainHandler.postDelayed(sensorBindRetryRunnable, 2000L)
    }

    private fun clearSensorMasterBindRetry() {
        if (!sensorBindRetryPending) return
        mainHandler.removeCallbacks(sensorBindRetryRunnable)
        sensorBindRetryPending = false
    }

    private val nativeLib: NativeLib
        get() = NativeLibProvider.instance
    private val PdrManager: PDRM
        get() = AppSharedState.pdrManager
    private val SensorManagercpp: SMcpp
        get() = AppSharedState.sensorManagercpp

    private var testbed: String = ""
    private var appStartTime: Long = 0L

    private var userId: String? = null
    private var accessToken: String? = null

    private var locationDto: LocationDto
        get() = AppSharedState.locationDto
        set(value) { AppSharedState.locationDto = value }
    private var sensorDto: SensorDto
        get() = AppSharedState.sensorDto
        set(value) { AppSharedState.sensorDto = value }
    private var currentUserStates: Map<String, Any> = mapOf()

    private lateinit var vibrator: Vibrator

    private val indoorListener = object : IndoorEstimate.IndoorLikelihoodListener {
        override fun onIndoorLikelihoodUpdated(likelihood: Float) {
            AppSharedState.flatmapIndoorLikelihood = likelihood
        }

        override fun onStrongIndoorConfirmed() {
            AppSharedState.flatmapStrongIndoorConfirmed = true
            outdoorSwitchTriggered = false
        }

        override fun onStrongOutdoorConfirmed() {
            AppSharedState.flatmapStrongIndoorConfirmed = false
            Log.i(TAG, "Strong outdoor confirmed (LocationService). Switching to FlatmapBackgroundService.")
            handleStrongOutdoorTransition()
        }

        override fun onDebugMessage(message: String) {
            AppSharedState.flatmapIndoorDebugMessage = message
        }
    }

    private var logisstep: Boolean
        get() = AppSharedState.logisstep
        set(value) { AppSharedState.logisstep = value }

    private var elevationMode: Int
        get() = AppSharedState.elevationMode
        set(value) { AppSharedState.elevationMode = value }
    private var cumulativeStepLength: Float
        get() = AppSharedState.cumulativeStepLength
        set(value) { AppSharedState.cumulativeStepLength = value }

    private var lastGyro: FloatArray
        get() = AppSharedState.lastGyro
        set(value) { AppSharedState.lastGyro = value }
    private var lastAcc: FloatArray
        get() = AppSharedState.lastAcc
        set(value) { AppSharedState.lastAcc = value }
    private var lastLinAcc: FloatArray
        get() = AppSharedState.lastLinAcc
        set(value) { AppSharedState.lastLinAcc = value }
    private var lastMag: FloatArray
        get() = AppSharedState.lastMag
        set(value) { AppSharedState.lastMag = value }
    private var lastQuat: FloatArray
        get() = AppSharedState.lastQuat
        set(value) { AppSharedState.lastQuat = value }
    private var lastLight: Float
        get() = AppSharedState.lastLight
        set(value) { AppSharedState.lastLight = value }
    private var lastPressureHpa: Float
        get() = AppSharedState.lastPressureHpa
        set(value) { AppSharedState.lastPressureHpa = value }

    private var magMatrix: FloatArray
        get() = AppSharedState.magMatrix
        set(value) { AppSharedState.magMatrix = value }
    private var accMatrix: FloatArray
        get() = AppSharedState.accMatrix
        set(value) { AppSharedState.accMatrix = value }
    private var gravMatrix: FloatArray
        get() = AppSharedState.gravMatrix
        set(value) { AppSharedState.gravMatrix = value }

    private var fusedOrientation: FloatArray
        get() = AppSharedState.fusedOrientation
        set(value) { AppSharedState.fusedOrientation = value }

    private var mapBasedGyroAngle: Float
        get() = AppSharedState.mapBasedGyroAngle
        set(value) { AppSharedState.mapBasedGyroAngle = value }
    private var mapBasedGyroAngleCaliValue: Int
        get() = AppSharedState.mapBasedGyroAngleCaliValue
        set(value) { AppSharedState.mapBasedGyroAngleCaliValue = value }
    private var angletmp: Float
        get() = AppSharedState.angletmp
        set(value) { AppSharedState.angletmp = value }
    private var gyroCaliValue: Float
        get() = AppSharedState.gyroCaliValue
        set(value) { AppSharedState.gyroCaliValue = value }
    private var rotateCaliValue: Float
        get() = AppSharedState.rotateCaliValue
        set(value) { AppSharedState.rotateCaliValue = value }
    private var compassDirection: Float
        get() = AppSharedState.compassDirection
        set(value) { AppSharedState.compassDirection = value }

    private var stepLength: Float
        get() = AppSharedState.stepLength
        set(value) { AppSharedState.stepLength = value }
    private var stepCount: Int
        get() = AppSharedState.stepCount
        set(value) { AppSharedState.stepCount = value }
    private var res_distance: Float
        get() = AppSharedState.resDistance
        set(value) { AppSharedState.resDistance = value }

    private var statereal: Int = 0
    private var statetmp: Int = 0
    private var previousPhoneState: Int
        get() = AppSharedState.previousPhoneState
        set(value) { AppSharedState.previousPhoneState = value }
    private var notMovingStartTimestamp: Long
        get() = AppSharedState.notMovingStartTimestamp
        set(value) { AppSharedState.notMovingStartTimestamp = value }
    private var wasMoving: Boolean = true
    private var firststep: Boolean
        get() = AppSharedState.firststep
        set(value) { AppSharedState.firststep = value }
    private var initialized: Boolean
        get() = AppSharedState.initialized
        set(value) { AppSharedState.initialized = value }
    private var stairsHardResetFlag: Boolean
        get() = AppSharedState.stairsHardResetFlag
        set(value) { AppSharedState.stairsHardResetFlag = value }

    private var isSensorStabled: Boolean
        get() = AppSharedState.isSensorStabled
        set(value) { AppSharedState.isSensorStabled = value }
    private var accStableCount: Int
        get() = AppSharedState.accStableCount
        set(value) { AppSharedState.accStableCount = value }
    private var gyroStableCount: Int
        get() = AppSharedState.gyroStableCount
        set(value) { AppSharedState.gyroStableCount = value }

    private var pressureEvent: SensorEvent?
        get() = AppSharedState.pressureEvent
        set(value) { AppSharedState.pressureEvent = value }
    private var accelEvent: SensorEvent?
        get() = AppSharedState.accelEvent
        set(value) { AppSharedState.accelEvent = value }
    private var lastStatereal: Int
        get() = AppSharedState.lastStatereal
        set(value) { AppSharedState.lastStatereal = value }

    private var searchRange: Int
        get() = AppSharedState.searchRange
        set(value) { AppSharedState.searchRange = value }
    private var centerX: Int
        get() = AppSharedState.centerX
        set(value) { AppSharedState.centerX = value }
    private var centerY: Int
        get() = AppSharedState.centerY
        set(value) { AppSharedState.centerY = value }
    private var cur_pos: Array<Float>
        get() = AppSharedState.curPos
        set(value) { AppSharedState.curPos = value }
    private var currentPosition: Array<Float>
        get() = AppSharedState.currentPosition
        set(value) { AppSharedState.currentPosition = value }
    private var currentFloor: Int
        get() = AppSharedState.currentFloor
        set(value) { AppSharedState.currentFloor = value }
    private var previousFloor: Int
        get() = AppSharedState.previousFloor
        set(value) { AppSharedState.previousFloor = value }
    private var pdrResult: Any?
        get() = AppSharedState.pdrResult
        set(value) { AppSharedState.pdrResult = value }

    private var distance: Float
        get() = AppSharedState.distance
        set(value) { AppSharedState.distance = value }

    private val stepQueue: LinkedList<Float>
        get() = AppSharedState.stepQueue
    private val quaternionQueue: LinkedList<FloatArray>
        get() = AppSharedState.quaternionQueue

    private var rotangle: FloatArray
        get() = AppSharedState.rotangle
        set(value) { AppSharedState.rotangle = value }

    private val rotationMovingAveragex: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragey: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragez: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragew: MovingAverage = MovingAverage(10)

    private val statequeue: LinkedList<Int>
        get() = AppSharedState.statequeue
    private val floorQueue: MutableList<Float>
        get() = AppSharedState.floorQueue
    private val floorqueue: LinkedList<Int>
        get() = AppSharedState.floorqueue
    private var rffloor: Float
        get() = AppSharedState.rffloor
        set(value) { AppSharedState.rffloor = value }

    private var isServiceRunning = false
    // Fallback counter for native step index when PDR totalStepCount stays 0
    private var nativeStepIndex: Int = 0

    data class LocationInfo(
        val x: Float,
        val y: Float,
        val floor: Int,
        val orientation: Float,
        val userstate: Int
    )

    companion object {
        private const val TAG = "LocationService"
        private const val STALE_LOCATION_THRESHOLD_MS = 10_000L
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        appStartTime = System.currentTimeMillis()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        SharedComponentProvider.init(this)
        NativeLibProvider.init(assets)
        stairsArea = StairsAreaProvider.load(this)
        loadUserCredentials()
        setupRotationPatternDetector()
        startStartupStateChecks()
        startUserStateLoop()
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        connectSensorMaster()
//        val startScreen = intent?.getStringExtra("START_SCREEN") ?: "MAIN"
//        var startfloor = if(floorChangeDetection.currentFloor == 0)  1 else floorChangeDetection.currentFloor
//        Log.d(TAG, "initialized: $testbed")
//        nativeLib.initializeEngine(floor=startfloor, testbed = testbed)
//        initialized = true
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectSensorMaster()
        testbed = PreferenceHelper.getStartScreen(this, "")
        val indoorPoiName = PreferenceHelper.getIndoorPoiName(this)
        PreferenceHelper.setIndoorPoiName(this, null)
        if (!indoorPoiName.isNullOrBlank()) {
            Log.i(TAG, "Received indoor trigger from FlatmapBackgroundService: poi=$indoorPoiName, testbed=$testbed")
            AppSharedState.flatmapStrongIndoorConfirmed = true
        }
        AppSharedState.flatmapMotionWithoutSteps = false
        ensureMapResources()
        var startfloor = if(floorChangeDetection.currentFloor == 0)  1 else floorChangeDetection.currentFloor
        Log.d(TAG, "initialized: $testbed")
        if (!AppSharedState.nativeLibInitialized) {
            nativeLib.initializeEngine(floor = startfloor, testbed = testbed)
            AppSharedState.nativeLibInitialized = true
            AppSharedState.nativeLibOwner = nativeLibOwnerTag
            Log.i(TAG, "NativeLib initialized by LocationService (floor=$startfloor, testbed=$testbed)")
        } else {
            Log.i(TAG, "NativeLib already initialized by ${AppSharedState.nativeLibOwner}; skipping re-init")
            if (!AppSharedState.isAppRunning) {
                AppSharedState.nativeLibOwner = nativeLibOwnerTag
            }
        }
        initialized = true
        startForegroundNotification()
        isServiceRunning = true
        AppSharedState.isBackgroundServiceActive = true
        outdoorSwitchTriggered = false
        startIndoorEstimatorIfPermitted()
        startLocationUpdates()
        seedLastKnownLocation()
        val updatedData = AppSharedState.locationDto.copy(
            userStatus = "Active", // "Background"
            background = true // 서비스가 시작되었다는 것은 곧 백그라운드 상태임
        )
        sendLogDataToServer(updatedData, AppSharedState.sensorDto)
        return START_STICKY
    }

    private fun loadUserCredentials() {
        val pref: SharedPreferences = getSharedPreferences("USER_PREF", MODE_PRIVATE)
        userId = pref.getString("USER_ID", "guest")
        accessToken = pref.getString("ACCESS_TOKEN", null)
    }

    private fun ensureIndoorEstimator(): IndoorEstimate {
        val existing = indoorEstimator
        if (existing != null) {
            return existing
        }
        return IndoorEstimate(this, indoorListener).also { indoorEstimator = it }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startIndoorEstimatorIfPermitted() {
        if (indoorEstimatorRunning) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot start IndoorEstimate: location permission missing.")
            AppSharedState.flatmapIndoorLikelihood = 0f
            AppSharedState.flatmapIndoorLikelihoodTimestamp = 0L
            return
        }
        ensureIndoorEstimator().start()
        indoorEstimatorRunning = true
    }

    private fun stopIndoorEstimator() {
        indoorEstimator?.let { estimator ->
            runCatching { estimator.stop() }.onFailure { error ->
                Log.w(TAG, "Failed to stop IndoorEstimate: ${error.message}")
            }
        }
        indoorEstimatorRunning = false
        indoorEstimator = null
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (!(fineGranted || coarseGranted)) {
            Log.w(TAG, "Foreground location permission missing.")
            return false
        }
        if (!backgroundGranted) {
            Log.w(TAG, "ACCESS_BACKGROUND_LOCATION not granted; background updates will be unavailable.")
            return false
        }
        return true
    }

    private fun handleStrongOutdoorTransition() {
        if (outdoorSwitchTriggered) {
            Log.d(TAG, "Strong outdoor transition already handled; ignoring duplicate callback.")
            return
        }
        outdoorSwitchTriggered = true
        stopIndoorEstimator()
        stopLocationUpdates()
        if (!AppSharedState.flatmapBackgroundActive) {
            PreferenceHelper.setStartScreen(this, "main")
            PreferenceHelper.setIndoorPoiName(this, null)
            val intent = Intent(this, FlatmapBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        } else {
            Log.i(TAG, "FlatmapBackgroundService already active; skipping restart.")
        }
        AppSharedState.isBackgroundServiceActive = false
        stopSelf()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locationUpdatesActive) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot request location updates: location permission missing.")
            AppSharedState.flatmapLastGpsPayload = null
            AppSharedState.flatmapLastGpsTimestamp = 0L
            return
        }
        val minTimeMs = 500L
        val minDistanceM = 0.5f
        var registered = false
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        providers.forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceM,
                    this
                )
                registered = true
            }.onFailure { error ->
                Log.v(TAG, "Provider $provider registration failed: ${error.message}")
            }
        }
        locationUpdatesActive = registered
        if (registered) {
            Log.d(TAG, "Location updates started (providers=${providers.joinToString()}).")
        } else {
            Log.w(TAG, "No location providers could be registered.")
        }
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesActive) return
        runCatching { locationManager.removeUpdates(this) }
            .onFailure { Log.w(TAG, "Failed to remove location updates: ${it.message}") }
        locationUpdatesActive = false
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocation() {
        if (!hasLocationPermission()) return
        if (AppSharedState.flatmapLastGpsPayload != null && AppSharedState.flatmapLastGpsTimestamp > 0L) {
            Log.d(TAG, "Seed skipped: shared GPS already populated (ts=${AppSharedState.flatmapLastGpsTimestamp})")
            return
        }
        val gps = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val network = runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val passive = runCatching { locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        val chosen = gps ?: network ?: passive
        chosen?.let { updateSharedLocation(it, "Seed last known location (${it.provider ?: "unknown"})") }
    }

    private fun updateSharedLocation(location: Location, reason: String = "LocationService location update") {
        val locationTimestamp = if (location.time > 0L) location.time else System.currentTimeMillis()
        if (AppSharedState.flatmapLastGpsTimestamp > 0L &&
            locationTimestamp + STALE_LOCATION_THRESHOLD_MS < AppSharedState.flatmapLastGpsTimestamp
        ) {
            Log.d(
                TAG,
                "Skipping stale location ($reason): provider=${location.provider} ts=$locationTimestamp existing=${AppSharedState.flatmapLastGpsTimestamp}"
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
    }

    private fun ensureMapResources() {
        mapMatching = MapMatching(this, testbed)
        val correctionPath = if (testbed.isNotBlank()) {
            "$testbed/map_correction_areas.json"
        } else {
            "map_correction_areas.json"
        }
        mapMatching.initialize(correctionPath)
        SharedComponentProvider.getFloorChangeDetection(this, testbed)
    }

    private fun setupRotationPatternDetector() {
        rotationPatternDetector.onStateChanged = {
            val pastState = statereal
            serviceScope.launch {
                statetmp = userStateMonitor.getStatus(statereal)
                repeat(2) {
                    delay(1000)
                    statetmp = userStateMonitor.getStatus(statereal)
                    if (pastState != statetmp) {
                        stateflag = true
                    }
                }
            }
        }
    }

    private fun startStartupStateChecks() {
        startupStateCheckJob?.cancel()
        startupStateCheckJob = serviceScope.launch {
            repeat(5) { iteration ->
                val pastState = statetmp
                statetmp = userStateMonitor.getStatus(statereal)
                if (pastState != statetmp) {
                    stateflag = true
                }
                if (iteration < 4) {
                    delay(1000)
                }
            }
        }
    }

    private fun startUserStateLoop() {
        serviceScope.launch {
            while (isActive) {
                currentUserStates = userStateMonitor.getStates()
//                Log.d("resultstate", "$currentUserStates")
                val isMovingNow = currentUserStates["isMoving"] as? Boolean ?: false
                val motionWithoutSteps = currentUserStates["motionWithoutSteps"] as? Boolean
                    ?: userStateMonitor.detectMotionWithoutSteps()
                AppSharedState.flatmapMotionWithoutSteps = motionWithoutSteps
                if (!firststep && initialized) {
//                    Log.d("resultlo","${wasMoving}  ${isMovingNow}, ${statetmp}, ${statereal}")
                    if (wasMoving && !isMovingNow) {
                        notMovingStartTimestamp = System.currentTimeMillis()
                        statereal = if (userStateMonitor.onTablecheck()) {
                            15
                        } else {
                            25
                        }
                        statetmp = 0
                        UnityPlayer.UnitySendMessage(
                            "GameObject",
                            "OnReceiveState",
                            (statereal).toString()
                        )
                        val updateData = sensorDto.copy(userStateReal = statereal)
                        sendLogDataToServer(locationDto, updateData)
                    } else if (!isMovingNow) {
                        statereal = if (userStateMonitor.onTablecheck()) {
                            15
                        } else {
                            25
                        }
                        statetmp = 0

                    }

                }
                wasMoving = isMovingNow

                floorQueue.add(rffloor)
                if (floorQueue.size > 5) {
                    floorQueue.removeAt(0)
                }
                delay(1000)
            }
        }
    }

    private fun currentUserStatus(): String = if (isServiceRunning) "Background" else "Inactive"

    private fun sendLogDataToServer(locationData: LocationDto, sensorData: SensorDto) {
        val token = accessToken
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "AccessToken is null. Skip sending logs.")
            return
        }

        val client = OkHttpClient()

        sendLocationData(client, locationData, token) {
            mainHandler.post {
                Toast.makeText(this@LocationService, "인증 오류: 토큰 만료. 재로그인 해주세요.", Toast.LENGTH_LONG).show()
                val loginIntent = Intent(this@LocationService, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(loginIntent)
            }
        }

        sendSensorData(client, sensorData, token) {
            mainHandler.post {
                Toast.makeText(this@LocationService, "인증 오류: 토큰 만료. 재로그인 해주세요.", Toast.LENGTH_LONG).show()
                val loginIntent = Intent(this@LocationService, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(loginIntent)
            }
        }
    }

    override fun onDestroy() {
        startupStateCheckJob?.cancel()
        isServiceRunning = false
        AppSharedState.isBackgroundServiceActive = false
        AppSharedState.flatmapMotionWithoutSteps = false
        stopLocationUpdates()
        stopIndoorEstimator()
        serviceScope.cancel()
        clearSensorMasterBindRetry()
        unregisterFromSensorMaster()
        if (sensorMasterBound) {
            unbindService(sensorServiceConnection)
            sensorMasterBound = false
        }
        sensorMaster = null
        if (AppSharedState.nativeLibOwner == nativeLibOwnerTag && !AppSharedState.isAppRunning) {
            nativeLib.destroyEngine()
            AppSharedState.nativeLibInitialized = false
            AppSharedState.nativeLibOwner = null
            initialized = false
            Log.i(TAG, "NativeLib destroyed by LocationService")
        } else {
            Log.i(TAG, "LocationService skipped nativeLib destroy (owner=${AppSharedState.nativeLibOwner}, isAppRunning=${AppSharedState.isAppRunning})")
        }
        rotationPatternDetector.onStateChanged = null

        if (!AppSharedState.isAppRunning) {
            locationDto = locationDto.copy(userStatus = currentUserStatus()) // "Inactive"가 됨
            sendLogDataToServer(locationDto, sensorDto)
        }
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        mapBasedGyroAngle = when (statereal) {
            1, 2, 22, 32 -> (angletmp - rotateCaliValue + mapBasedGyroAngleCaliValue + 360) % 360
            3 -> ((toNormalizedDegree(fusedOrientation[0]) - gyroCaliValue) + mapBasedGyroAngleCaliValue + 180 + 360) % 360
            else -> ((toNormalizedDegree(fusedOrientation[0]) - gyroCaliValue) + mapBasedGyroAngleCaliValue + 360) % 360
        }

        val currentTimeMillis = System.currentTimeMillis()
        var hasWalked = false
        val readyForLocalization = sensorMaster?.isReadyLocalization(event, fusedOrientation) ?: false
        if (!readyForLocalization) {
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accMatrix = event.values.clone()
                lastAcc = event.values.clone()
                SensorManagercpp.updateAccelerometer(accMatrix, currentTimeMillis)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magMatrix = event.values.clone()
                lastMag = event.values.clone()
            }
            Sensor.TYPE_GRAVITY -> gravMatrix = event.values.clone()
            Sensor.TYPE_PRESSURE -> {
                pressureEvent = event
                val elapsedSeconds = (currentTimeMillis - appStartTime).toFloat() / 1000f
                userStateMonitor.updatePressureData(event)
                floorChangeDetection.updatePressureData(event.values[0], elapsedSeconds, mapBasedGyroAngle)
                lastStatereal = statereal
                lastPressureHpa = event.values[0]
                Log.d("local1", "${lastAcc.joinToString()}\t${rotangle[0]},${rotangle[1]},${rotangle[2]}\t${lastLight}\t${lastPressureHpa}\t${distance}")
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                lastLinAcc = event.values.clone()
                accelEvent = event
                SensorManagercpp.updateLinearAccelerometer(event.values, currentTimeMillis)
                userStateMonitor.updateAccelData(event)
                val isStep = PdrManager.isStep(statetmp, statereal)
                logisstep = isStep
                if (isStep) {
                    Log.d("result3", "statetmp=$statetmp statereal=$statereal")
                    Log.d("mapangle", "mbga: ${mapBasedGyroAngle}\tabang: ${mapBasedGyroAngle-mapBasedGyroAngleCaliValue}\tstatetmp: ${statetmp}\trotatecali: ${rotateCaliValue}\tgyro: ${gyroCaliValue}\tangletmp ${angletmp}\tMM: ${mapBasedGyroAngleCaliValue}\ttmp:${statereal}\treal:${statetmp}\tcompass:${compassDirection}")

                    if (statequeue.all { it == statequeue.first() }) {
                        PdrManager.add_headqueue(mapBasedGyroAngle)
                    }
                    pdrResult = PdrManager.getresult()
                    stepCount = (pdrResult as PDR).totalStepCount
                    // Fallback counter for native step index when PDR totalStepCount stays 0
                    nativeStepIndex += 1
                    cumulativeStepLength += stepLength
                    when (statetmp) {
                        0, 3 -> {
                            stepLength = (pdrResult as PDR).stepLength.toFloat() - 0.035f
                            stepLength *= 0.9f
                            stepQueue.add(stepLength)
                            stepQueue.pop()
                            hasWalked = true
                        }
                        2 -> {
                            stepLength = (pdrResult as PDR).stepLength.toFloat()
                            mapBasedGyroAngle =
                                ((pdrResult as PDR).direction.toFloat() - rotateCaliValue + mapBasedGyroAngleCaliValue + 360) % 360
                            angletmp = (pdrResult as PDR).direction.toFloat()
                            hasWalked = true
                        }
                        1 -> {
                            stepLength = (pdrResult as PDR).stepLength.toFloat()
                            mapBasedGyroAngle =
                                ((pdrResult as PDR).direction.toFloat() - rotateCaliValue + mapBasedGyroAngleCaliValue + 360) % 360
                            angletmp = (pdrResult as PDR).direction.toFloat()
                            hasWalked = true
                        }
                        22 -> {
                            stepLength = (pdrResult as PDR).stepLength.toFloat() * 0.82f
                            mapBasedGyroAngle =
                                ((pdrResult as PDR).direction.toFloat() - rotateCaliValue + mapBasedGyroAngleCaliValue + 360) % 360
                            angletmp = (pdrResult as PDR).direction.toFloat()
                            hasWalked = true
                        }
                    }
                    Log.d(
                        TAG,
                        "PDR result dir=${(pdrResult as PDR).direction} stepLen=${(pdrResult as PDR).stepLength} stepCnt=${(pdrResult as PDR).totalStepCount} statetmp=$statetmp statereal=$statereal"
                    )
                }
            }
            Sensor.TYPE_LIGHT -> {
                SensorManagercpp.updateLight(event.values[0], System.currentTimeMillis())
                userStateMonitor.updateLightData(event.values[0])
                lastLight = event.values[0]
            }
            Sensor.TYPE_PROXIMITY -> {
                distance = event.values[0]
                userStateMonitor.updateProximityData(distance)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val tmp = event.values.clone()
                rotationMovingAveragex.newData(tmp[0])
                rotationMovingAveragey.newData(tmp[1])
                rotationMovingAveragez.newData(tmp[2])
                rotationMovingAveragew.newData(tmp[3])
                val currentValues = floatArrayOf(
                    rotationMovingAveragex.getAvg().toFloat(),
                    rotationMovingAveragey.getAvg().toFloat(),
                    rotationMovingAveragez.getAvg().toFloat(),
                    rotationMovingAveragew.getAvg().toFloat()
                )
                lastQuat = currentValues.clone()
                val nowMs = System.currentTimeMillis()
                SensorManagercpp.updateRotationQuaternion(currentValues, nowMs)
                rotangle = SensorManagercpp.getRotationAnglesDeg()
                rotationPatternDetector.addSample(rotangle[0], rotangle[1])
                userStateMonitor.updateRotationData(rotangle)
                if (quaternionQueue.size > 5) {
                    quaternionQueue.poll()
                }
                quaternionQueue.add(currentValues)
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro = event.values.clone()
            }
        }

        userStateMonitor.updateWalkingState(hasWalked)

        if (hasWalked) {
            firststep = false
            statequeue.add(statetmp)
            statequeue.poll()
            val topEntry = statequeue
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
            if (topEntry != null && topEntry.value >= 4) {
                statereal = topEntry.key
            }
            if (stateflag && statequeue.all { it == statequeue.first() }) {
                stateflag = false
                rotateCaliValue =
                    ((angletmp - PdrManager.get_headqueue_peek()!! + mapBasedGyroAngleCaliValue) + 360) % 360
//                if(currentTimeMillis-appStartTime > 10000L)
//                    nativeLib.reSearchStart(30)
            }

            calculateCompassDirection()

            serviceScope.launch {
                // Use PDR's total step if available; otherwise fall back to internal counter to ensure monotonic step index for nativeLib
                val effectiveStepCount = if (stepCount > 0) stepCount else nativeStepIndex
                val allSameState = statequeue.all { it == statequeue.first() }
                val (location, mapIdForLog) = if (allSameState) {
                    updateLocation(stepLength, mapBasedGyroAngle, compassDirection, effectiveStepCount) to 109
                } else {
                    val fallbackStepLength = stepQueue.peek() ?: stepLength
                    val fallbackAngle = PdrManager.get_headqueue_peek() ?: mapBasedGyroAngle
                    updateLocation(fallbackStepLength, fallbackAngle, compassDirection, effectiveStepCount) to 1
                }
                val mapMatchingLocation =
                    mapMatching.getMapMatchingResult(location.x, location.y, location.floor)
                currentPosition = arrayOf(mapMatchingLocation.x, mapMatchingLocation.y)

                if (location.x > 0.0f && location.y > 0.0f) {
                    locationDto = LocationDto(
                        userId = userId ?: "guest",
                        mapId = mapIdForLog,
                        userX = mapMatchingLocation.x.toDouble(),
                        userY = mapMatchingLocation.y.toDouble(),
                        userZ = mapMatchingLocation.floor.toDouble(),
                        userDirection = mapBasedGyroAngle.toDouble(),
                        userFloor = mapMatchingLocation.floor.toDouble(),
                        userStatus = "Active",
                        background = true
                    )
                    sendLogDataToServer(locationDto, sensorDto)
                }
            }
            previousPhoneState = statereal
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                sensorDto = sensorDto.copy(acceleration = SensorXYZ(event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_MAGNETIC_FIELD ->
                sensorDto = sensorDto.copy(magnetic = SensorXYZ(event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_GYROSCOPE ->
                sensorDto = sensorDto.copy(gyro = SensorXYZ(event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_LINEAR_ACCELERATION ->
                sensorDto = sensorDto.copy(linearAcceleration = SensorXYZ(event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_PRESSURE ->
                sensorDto = sensorDto.copy(pressure = event.values[0])
            Sensor.TYPE_LIGHT ->
                sensorDto = sensorDto.copy(light = event.values[0])
            Sensor.TYPE_PROXIMITY ->
                sensorDto = sensorDto.copy(proximity = event.values[0])
            Sensor.TYPE_GAME_ROTATION_VECTOR ->
                sensorDto = sensorDto.copy(rotation = SensorXYZ(rotangle[0], rotangle[1], rotangle[2]))
        }

        sensorDto = sensorDto.copy(
            rf = rffloor,
            userStateReal = statereal,
            stepLength = stepLength
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    private fun startForegroundNotification() {
        val channelId = "location_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.enableVibration(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("백그라운드 실행 중")
            .setContentText("위치 정보를 수집하고 있습니다.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setVibrate(longArrayOf(0L))
            .build()

        startForeground(1, notification)
    }

    private fun updateLocation(stepLength: Float, mapBasedGyroAngle: Float, compassDirection: Float, stepCount: Int): LocationInfo {
        floorChangeDetection.updateGyroData(mapBasedGyroAngle)
        currentFloor = floorChangeDetection.currentFloor

        elevationMode = when {
            floorChangeDetection.getCurrentStairsState() == "계단 확정" ||
                    floorChangeDetection.getCurrentStairsState() == "층 도착" -> 0
            floorChangeDetection.getCurrentElevatorState() == "엘리베이터 확신" ||
                    floorChangeDetection.getCurrentElevatorState() == "엘리베이터 도착 (내리지 않음)" ||
                    floorChangeDetection.getCurrentElevatorState() == "엘리베이터 내림" -> 2
            else -> elevationMode
        }

        if (hasFloorChanged()) {
            searchRange = 50
            stairsHardResetFlag = false
        } else if (!stairsHardResetFlag && floorChangeDetection.getCurrentStairsState() == "계단 확정") {
            val stairsCoord = floorChangeDetection.setStairsInfo(
                currentPosition,
                currentFloor,
                floorChangeDetection.arrivedStairGyroValue,
                floorChangeDetection.elevationStatus
            )
            stairsCoord?.let {
                nativeLib.reSearchStartInStairs(it[0].toInt(), it[1].toInt())
            }
            stairsHardResetFlag = true
        }
        previousFloor = currentFloor

        var isInStairsArea = false
        val floorStairsAreas = stairsArea[currentFloor]
        if (floorStairsAreas != null) {
            for (area in floorStairsAreas) {
                val xMin = area[0]
                val yMin = area[1]
                val xMax = area[2]
                val yMax = area[3]
                if (cur_pos[0] >= xMin && cur_pos[0] <= xMax &&
                    cur_pos[1] >= yMin && cur_pos[1] <= yMax
                ) {
                    isInStairsArea = true
                    break
                }
            }
        }

        var adjustedStepLength = stepLength
        if (isInStairsArea && (res_distance < 4.0f)) {
            adjustedStepLength = 0.3f
        }

        if (initialized) {
            var arrivedGyroValue = floorChangeDetection.arrivedStairGyroValue
            if (elevationMode == 2) {
                arrivedGyroValue = floorChangeDetection.arrivedElevatorGyroValue
            }
            Log.d("result4","${mapBasedGyroAngle - mapBasedGyroAngleCaliValue},${compassDirection}, ${adjustedStepLength}," +
                    "${stepCount}, ${currentFloor}, ${arrivedGyroValue}, ${elevationMode}, ${statereal}, ${statetmp}")
            // Defensive: call nativeLib.processStep, log error if null
            val result = nativeLib.processStep(
                mapBasedGyroAngle - mapBasedGyroAngleCaliValue,
                compassDirection,
                adjustedStepLength,
                stepCount,
                currentFloor,
                arrivedGyroValue,
                elevationMode
            )
            if (result != null) {
                centerX = result[0].toInt()
                centerY = result[1].toInt()
                res_distance = result[2]
                mapBasedGyroAngleCaliValue = result[3].toInt()
                cur_pos = arrayOf(centerX.toFloat(), centerY.toFloat())
                Log.d(TAG, "Location updated: $centerX,$centerY floor=$currentFloor")
            } else {
                Log.e(TAG, "nativeLib.processStep returned null | ang=${mapBasedGyroAngle - mapBasedGyroAngleCaliValue}, comp=$compassDirection, stepLen=$adjustedStepLength, stepIdx=$stepCount, floor=$currentFloor, arrivedGyro=$arrivedGyroValue, elevMode=$elevationMode")
            }
        }

        return LocationInfo(cur_pos[0], cur_pos[1], currentFloor, mapBasedGyroAngle, statereal)
    }

    private fun calculateCompassDirection() {
        val hasMagData = magMatrix.any { it != 0f }
        if (!hasMagData) {
            return
        }

        val gravityRef = when {
            gravMatrix.any { it != 0f } -> gravMatrix
            accMatrix.any { it != 0f } -> accMatrix
            else -> return
        }

        try {
            val rotationMatrix = FloatArray(9)
            val orientationAngles = FloatArray(3)
            val hasRotationMatrix = SensorManager.getRotationMatrix(rotationMatrix, null, gravityRef, magMatrix)

            if (!hasRotationMatrix) {
                Log.w(TAG, "Failed to compute rotation matrix; sensor data unstable.")
                return
            }

            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            compassDirection = (
                    (Math.toDegrees(orientationAngles[0].toDouble()) - 297.02 - 180 + 720) % 360
                    ).toFloat()
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating compass direction: ${e.message}", e)
        }
    }

    private fun getRotationFromQuaternion(quaternion: FloatArray): FloatArray {
        val x = quaternion[0]
        val y = quaternion[1]
        val z = quaternion[2]
        val w = quaternion[3]
        val pitch = Math.toDegrees(
            atan2(
                2.0 * (w * x + y * z),
                1.0 - 2.0 * (x * x + y * y)
            )
        ).toFloat()
        val roll = Math.toDegrees(
            asin(
                (2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0)
            )
        ).toFloat()
        val yaw = Math.toDegrees(
            atan2(
                2.0 * (w * z + x * y),
                1.0 - 2.0 * (y * y + z * z)
            )
        ).toFloat()
        return floatArrayOf(pitch, roll, yaw)
    }

    override fun onLocationChanged(location: Location) {
        updateSharedLocation(location)
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        if (!isServiceRunning) return
        if (!hasLocationPermission()) return
        locationUpdatesActive = false
        startLocationUpdates()
    }

    override fun onProviderDisabled(provider: String) {
        if (!isServiceRunning) return
        stopLocationUpdates()
        if (hasLocationPermission()) {
            startLocationUpdates()
        }
    }

    private fun hasFloorChanged(): Boolean = currentFloor != previousFloor

    fun toNormalizedDegree(value: Float): Float =
        ((Math.toDegrees(value.toDouble()) + 360) % 360).toFloat()
}
