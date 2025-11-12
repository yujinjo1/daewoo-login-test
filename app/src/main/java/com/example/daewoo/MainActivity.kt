package com.example.daewoo

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import com.fifth.maplocationlib.MapMatching
import kotlinx.coroutines.*
import com.unity3d.player.IUnityPlayerLifecycleEvents
import com.unity3d.player.UnityPlayer
import com.fifth.maplocationlib.sensors.GyroscopeResetManager
import com.fifth.maplocationlib.NativeLib
import com.fifth.maplocationlib.UserStateMonitor
import com.fifth.maplocationlib.sensors.MovingAverage

import java.util.LinkedList
import kotlin.math.asin
import kotlin.math.atan2


import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.WindowManager//0421동근
import com.fifth.maplocationlib.RotationPatternDetector //0406윤동근
import com.fifth.maplocationlib.utils.StairsAreaProvider

///// 앱 - 서버 통신용 라이브러리 - 0324 김명권 ///////////
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.File

// 0922 김명권
import android.view.View
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import com.example.daewoo.bg.AppSharedState
import com.example.daewoo.bg.LocationService
import com.example.daewoo.bg.NativeLibProvider
import com.example.daewoo.bg.SensorMaster
import com.example.daewoo.dtos.FCMTokenDto
import com.example.daewoo.dtos.LocationDto
import com.example.daewoo.dtos.SensorDto
import com.example.daewoo.dtos.SensorXYZ
import com.example.daewoo.utils.saveFCMToken
import com.example.daewoo.utils.sendLocationData
import com.example.daewoo.utils.sendSensorData
import com.example.daewoo.utils.GeofenceWatcher
import com.example.daewoo.utils.PreferenceHelper
import com.fifth.maplocationlib.IndoorEstimate
import com.google.firebase.messaging.FirebaseMessaging

// 백엔드 통신용 라이브러리 추가 - 0520 오주연
//////////// 0922 김명권 여기까지 //////////////////////

import com.fifth.pdr_ext.PDRM
import com.fifth.pdr_ext.SMcpp
import com.fifth.pdr_ext.PDR
import com.google.firebase.FirebaseApp

import java.io.BufferedWriter
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONObject

class MainActivity : AppCompatActivity(), SensorEventListener, IUnityPlayerLifecycleEvents{
    private val nativeLibOwnerTag = "MainActivity"
    private var testbed: String = ""

    // 클래스 상단 변수 선언부에 추가
    private var pdrLogWriter: BufferedWriter? = null
    private var pdrLogFile: File? = null

    var logisstep: Boolean
        get() = AppSharedState.logisstep
        set(value) { AppSharedState.logisstep = value }
    private var elevationMode: Int
        get() = AppSharedState.elevationMode
        set(value) { AppSharedState.elevationMode = value }
    private var appStartTime: Long = 0
    // ===== CSV logging =====
    private var csvWriter: BufferedWriter? = null
    private var csvFile: File? = null
    private var cumulativeStepLength: Float
        get() = AppSharedState.cumulativeStepLength
        set(value) { AppSharedState.cumulativeStepLength = value }

    // latest sensor caches for CSV rows
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
    private val floorqueue: LinkedList<Int>
        get() = AppSharedState.floorqueue
    private var uid_save: Int = -1 // uid 저장 변수 - 0922 김명권
    private var isAppRunning: Boolean
        get() = AppSharedState.isAppRunning
        set(value) { AppSharedState.isAppRunning = value }
    private var isBackgroundServiceActive: Boolean
        get() = AppSharedState.isBackgroundServiceActive
        set(value) { AppSharedState.isBackgroundServiceActive = value }
    private var stairsHardResetFlag: Boolean
        get() = AppSharedState.stairsHardResetFlag
        set(value) { AppSharedState.stairsHardResetFlag = value }
    private var calibratedGyroAngle: Float = 0.0f
    private var gyroResetFlag = false
    private var initialized: Boolean
        get() = AppSharedState.initialized
        set(value) { AppSharedState.initialized = value }
    private var res_distance: Float
        get() = AppSharedState.resDistance
        set(value) { AppSharedState.resDistance = value }

    private lateinit var debugView: TextView
    private lateinit var debugView2: TextView
    // 계단 영역 정의: 층 정보를 키로, 여러 계단 영역의 리스트를 값으로 가지는 Map
    private lateinit var stairsArea: Map<Int, List<FloatArray>>

    private var sensorMaster: SensorMaster? = null
    private var sensorMasterBound: Boolean = false
    private var sensorListenerRegistered: Boolean = false
    private var activityActiveForSensors: Boolean = false

    private val sensorServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SensorMaster.LocalBinder ?: return
            sensorMaster = binder.getService()
            sensorMasterBound = true
            ensureSensorListenerRegistration()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            detachSensorListener()
            sensorMasterBound = false
            sensorMaster = null
            if (activityActiveForSensors) {
                ensureSensorMasterBinding()
            }
        }
    }

    private fun ensureSensorMasterBinding() {
        if (!AppSharedState.sensorMasterRunning) {
            val serviceIntent = Intent(applicationContext, SensorMaster::class.java)
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        }
        if (!sensorMasterBound) {
            val bindIntent = Intent(this, SensorMaster::class.java)
            bindService(bindIntent, sensorServiceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun ensureSensorListenerRegistration() {
        if (sensorMasterBound && activityActiveForSensors && !sensorListenerRegistered) {
            sensorMaster?.registerListener(this)
            sensorListenerRegistered = true
        }
    }

    private fun detachSensorListener() {
        if (sensorListenerRegistered) {
            sensorMaster?.unregisterListener(this)
            sensorListenerRegistered = false
        }
    }


    private var mapBasedGyroAngleCaliValue: Int
        get() = AppSharedState.mapBasedGyroAngleCaliValue
        set(value) { AppSharedState.mapBasedGyroAngleCaliValue = value }
    private lateinit var mUnityPlayer: UnityPlayer
    private lateinit var unityLayout: FrameLayout
    protected fun updateUnityCommandLineArguments(cmdLine: String?): String? {
        return cmdLine
    }
    private lateinit var mapMatching: MapMatching

    private var cur_pos: Array<Float>
        get() = AppSharedState.curPos
        set(value) { AppSharedState.curPos = value }
    private var compassDirection: Float
        get() = AppSharedState.compassDirection
        set(value) { AppSharedState.compassDirection = value }
    private var cur_floor_int = 0
    private var pastFloorCngDetResult: Int = 0
    private lateinit var vibrator: Vibrator
    private var isFistInit: Boolean = true
    private var magMatrix: FloatArray
        get() = AppSharedState.magMatrix
        set(value) { AppSharedState.magMatrix = value }
    private var accMatrix: FloatArray
        get() = AppSharedState.accMatrix
        set(value) { AppSharedState.accMatrix = value }
    private var gravMatrix: FloatArray
        get() = AppSharedState.gravMatrix
        set(value) { AppSharedState.gravMatrix = value }
    private var pdrResult: Any?
        get() = AppSharedState.pdrResult
        set(value) { AppSharedState.pdrResult = value }
    private var stepLength: Float
        get() = AppSharedState.stepLength
        set(value) { AppSharedState.stepLength = value }
    private var stepCount: Int
        get() = AppSharedState.stepCount
        set(value) { AppSharedState.stepCount = value }
    private val nativeLib: NativeLib
        get() = NativeLibProvider.instance
    private lateinit var floorChangeDetection: com.fifth.maplocationlib.FloorChangeDetection
    private var fusedOrientation: FloatArray
        get() = AppSharedState.fusedOrientation
        set(value) { AppSharedState.fusedOrientation = value }
    private var mapBasedGyroAngle: Float= 0.0f
    private var angletmp: Float
        get() = AppSharedState.angletmp
        set(value) { AppSharedState.angletmp = value }
    private var gyroCaliValue: Float
        get() = AppSharedState.gyroCaliValue
        set(value) { AppSharedState.gyroCaliValue = value }
    private var didConverge = false
    var isSensorStabled: Boolean
        get() = AppSharedState.isSensorStabled
        set(value) { AppSharedState.isSensorStabled = value }
    private lateinit var stateDebug: StateDebug
    private var previousMotionWithoutSteps: Boolean = false
    private var accStableCount: Int
        get() = AppSharedState.accStableCount
        set(value) { AppSharedState.accStableCount = value }
    private var gyroStableCount: Int
        get() = AppSharedState.gyroStableCount
        set(value) { AppSharedState.gyroStableCount = value }
    private var pressureEvent: SensorEvent?
        get() = AppSharedState.pressureEvent
        set(value) { AppSharedState.pressureEvent = value }
    private var pressureBias: Float = 0.48f//0421동근
    private var accelEvent: SensorEvent?
        get() = AppSharedState.accelEvent
        set(value) { AppSharedState.accelEvent = value }
    //    private var liteEvent: SensorEvent? = null // 250219 원준 : 문장 삭제
    private var searchRange: Int
        get() = AppSharedState.searchRange
        set(value) { AppSharedState.searchRange = value }
    private var centerX: Int
        get() = AppSharedState.centerX
        set(value) { AppSharedState.centerX = value }
    private var centerY: Int
        get() = AppSharedState.centerY
        set(value) { AppSharedState.centerY = value }
    private var cur_floor = "0"

    private val mHandler: Handler = Handler(Looper.myLooper()!!)
//    private lateinit var webView : WebView
//    private val HTML_FILE = "file:///android_asset/index.html"


    private lateinit var userStateMonitor: UserStateMonitor

    private val PdrManager: PDRM
        get() = AppSharedState.pdrManager
    private val SensorManagercpp: SMcpp
        get() = AppSharedState.sensorManagercpp
    //20250315 동근 수정 Boolean->Any
    // private var currentUserStates: Map<String, Boolean> = mapOf()
    private var currentUserStates: Map<String, Any> = mapOf()
    private var currentPosition: Array<Float>
        get() = AppSharedState.currentPosition
        set(value) { AppSharedState.currentPosition = value }
    var distance: Float
        get() = AppSharedState.distance
        set(value) { AppSharedState.distance = value }
    private var currentFloor : Int
        get() = AppSharedState.currentFloor
        set(value) { AppSharedState.currentFloor = value }
    private var previousFloor : Int
        get() = AppSharedState.previousFloor
        set(value) { AppSharedState.previousFloor = value }
    private var previousPhoneState: Int
        get() = AppSharedState.previousPhoneState
        set(value) { AppSharedState.previousPhoneState = value }
    private var wasMoving: Boolean
        get() = AppSharedState.wasMoving
        set(value) { AppSharedState.wasMoving = value }
    private var notMovingStartTimestamp: Long
        get() = AppSharedState.notMovingStartTimestamp
        set(value) { AppSharedState.notMovingStartTimestamp = value }

    val stepQueue: LinkedList<Float>
        get() = AppSharedState.stepQueue
    val quaternionQueue: LinkedList<FloatArray>
        get() = AppSharedState.quaternionQueue

    var rotangle: FloatArray
        get() = AppSharedState.rotangle
        set(value) { AppSharedState.rotangle = value }
    private var rotateCaliValue: Float
        get() = AppSharedState.rotateCaliValue
        set(value) { AppSharedState.rotateCaliValue = value }
    private val rotationMovingAveragex: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragey: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragez: MovingAverage = MovingAverage(10)
    private val rotationMovingAveragew: MovingAverage = MovingAverage(10)

    //0406윤동근 추가
    //var statequeue: LinkedList<Int> = LinkedList(listOf(0,0,0,0,0,0))
    val statequeue: LinkedList<Int>
        get() = AppSharedState.statequeue


    var statereal: Int = 0
    var statetmp: Int = 0
    var stateflag: Boolean = false
    //0406윤동근여기까지
    private var firststep: Boolean
        get() = AppSharedState.firststep
        set(value) { AppSharedState.firststep = value }
    private lateinit var indoorEstimator: IndoorEstimate
    private var waitingIndoorConfirm = false

    // token 반영 위해 추가함 0922 김명권
    private var userId: String? = null
    private var accessToken: String? = null

    private fun getLogFile(directory: File, baseName: String, extension: String): File {//로그 출력용
        val initialFile = File(directory, "$baseName$extension")
        if (!initialFile.exists()) {
            return initialFile
        }
        var fileIndex = 1
        var file: File
        do {
            val fileName = "${baseName}$fileIndex$extension"
            file = File(directory, fileName)
            fileIndex++
        } while (file.exists())
        return file
    }
    private var sensorLogFiles: MutableMap<Int, File> = mutableMapOf()


    private var lightEventvalue: Float = 100.0f

    private var rotationPatternDetector: RotationPatternDetector? = null //0406윤동근 추가

    private val gyroResetScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var gyroscopeResetManager: GyroscopeResetManager

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var startupStateCheckJob: Job? = null
    private var totalcount=0
    //동근새로 추가
    var rffloor: Float
        get() = AppSharedState.rffloor
        set(value) { AppSharedState.rffloor = value }
    //여기까지

    var filteredpressure = 999.0f //0421동근
    private var allowUnityMessages: Boolean = false
    private var unityAwaitingScene: Boolean = false
    private var unityStartScreenPending: Boolean = false
    private var unityPendingSceneCode: String? = null
    private var unityQueuedSceneCode: String? = null
    private var unityLastRequestedSceneCode: String? = null

    // When IndoorEstimator triggers navigation to flatmap, skip starting LocationService in onStop() once
    private var suppressServiceOnStop: Boolean = false

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
                unityStartScreenPending = unityStartScreenPending || (obj == "AndroidBridge" && method == "OnTestbedSelected")
                return
            }
            UnityHolder.sendMessage(o, m, p)
        } catch (t: Throwable) {
            Log.e("UnitySend", "UnitySendMessage threw: ${t.message}")
        }
    }

    private fun sendUnityStartScreenIfReady() {
        if (!unityStartScreenPending) return
        val target = testbed
        if (unityAwaitingScene && unityPendingSceneCode == target) {
            unityStartScreenPending = false
            Log.d("UnityInit", "Skip duplicate pending scene → $target (MainActivity)")
            return
        }
        if (unityAwaitingScene) {
            unityQueuedSceneCode = target
            unityStartScreenPending = false
            Log.d("UnityInit", "Queued start screen request → $target (MainActivity)")
            return
        }
        if (!unityAwaitingScene && unityLastRequestedSceneCode == target) {
            unityStartScreenPending = false
            Log.d("UnityInit", "Skip duplicate start screen → $target (MainActivity)")
            return
        }
        unityStartScreenPending = false
        unityPendingSceneCode = target
        unitySend("AndroidBridge", "OnTestbedSelected", target, skipGate = true)
        unityAwaitingScene = true
        allowUnityMessages = false
        unityLastRequestedSceneCode = target
        Log.d("UnityInit", "Sent start screen to Unity → $target (MainActivity)")
    }

    @Suppress("unused")
    fun onUnitySceneLoaded(sceneName: String) {
        runOnUiThread {
            Log.d("UnityBridge", "Scene loaded: $sceneName (MainActivity)")
            unityAwaitingScene = false
            allowUnityMessages = true
            unityPendingSceneCode = null
            val queued = unityQueuedSceneCode
            unityQueuedSceneCode = null
            if (queued != null && queued != testbed) {
                testbed = queued
                PreferenceHelper.setStartScreen(this@MainActivity, testbed)
            }
            if (queued != null) {
                unityStartScreenPending = true
                sendUnityStartScreenIfReady()
            }
        }
    }

    /** Consolidated Unity messaging to avoid duplicate string construction & JNI calls */
    private fun sendGyroDataToUnity(angle: Float) {
        val angleStr = angle.toString()
        unitySend("GameObject", "OnReceiveGyroData", angleStr)
        unitySend("Main Camera", "SetCameraYawRotation", angleStr)
    }
    private var lastPressureForUpdate: Float = 0f //0421동근
    private var lastStatereal: Int
        get() = AppSharedState.lastStatereal //0421동근
        set(value) { AppSharedState.lastStatereal = value }
    private var lastStatetmp: Int = 0 //0421동근

    // 상태 변경 시각 기록용 (ms)
    private var stateChangeTimestamp: Long = 0L

    data class LocationInfo(
        val x: Float,
        val y: Float,
        val floor: Int,
        val orientation : Float,
        val userstate : Int  // 20250315 동근 Boolean -> Int
    )
    private var toneGen: ToneGenerator? = null

    // 전송 객체들 dtos 패키지 내 Dto로 변경 0922 김명권
    private var locationDto: LocationDto
        get() = AppSharedState.locationDto
        set(value) { AppSharedState.locationDto = value }
    private var sensorDto: SensorDto
        get() = AppSharedState.sensorDto
        set(value) { AppSharedState.sensorDto = value }

    // Push Message 팝업을 위한 배너 변수 - 0922 김명권
    private lateinit var bannerLayout: LinearLayout
    private lateinit var bannerTitle: TextView
    private lateinit var bannerBody: TextView
    private lateinit var bannerButton: Button

    // Push 메시지 수신기 작성 - 0922 김명권
    private val fcmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val title = intent?.getStringExtra("title") ?: ""
            val body = intent?.getStringExtra("body") ?: ""
            showBanner(title, body)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        FirebaseApp.initializeApp(this)
        val packageName = "com.example.daewoo"  // uid 수집용 - 0325 김명권
        val uid = getUidForPackage(packageName)             // uid 수집용 - 0325 김명권
        uid_save = uid                                      // uid 수집용 - 0325 김명권
        testbed = PreferenceHelper.getStartScreen(this, "109")

        // 로그인에서 저장한 사용자 ID 및 토큰 불러오기 - 0610 오주연 추가; 로그인 기능과 연동하여 추가함
        val pref =
            getSharedPreferences("USER_PREF", Context.MODE_PRIVATE) // SharedPreferences 객체 가져오기
        this.userId = pref.getString("USER_ID", "null") // userId를 String으로 불러오기
        this.accessToken = pref.getString("ACCESS_TOKEN", null) // ACCESS_TOKEN 불러오기

        // 토큰이 없거나 userId가 유효하지 않으면 로그인 화면으로 이동 - 0922 김명권 수정됨
        if (this.accessToken == null || this.userId == null) { // userId가 null인지 확인
            Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show() // 토스트 메시지 표시
            startActivity(Intent(this, LoginActivity::class.java)) // LoginActivity로 이동
            finish() // 현재 액티비티 종료
            return // 중요: 로그인 액티비티로 이동했으므로 더 이상 진행하지 않음
        }

        GeofenceWatcher.reset()

        // LocationDto 초기화 0922 김명권 수정됨
        locationDto = LocationDto(
            userId = this.userId ?: "guest",
            mapId = -1,
            userX = 0.0f.toDouble(),
            userY = 0.0f.toDouble(),
            userZ = 0.0f.toDouble(),
            userDirection = 0.0f.toDouble(),
            userFloor = 0.0f.toDouble(),
            userStatus = "Active",
            background = false
        )
        // SensorDto 초기화 0922 김명권
        sensorDto = SensorDto(
//            userId = this.userId!!,
            mapId = -1,
            acceleration = SensorXYZ(0f, 0f, 0f),
            magnetic = SensorXYZ(0f, 0f, 0f),
            gyro = SensorXYZ(0f, 0f, 0f),
            linearAcceleration = SensorXYZ(0f, 0f, 0f),
            rotation = SensorXYZ(0f, 0f, 0f),
            pressure = 0f,
            light = 0f,
            proximity = 0f,
            rf = 0f,
            userStateReal = 0,
            stepLength = 0f
        )
        toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        stateDebug = StateDebug(
            activity = this,
            context = this,
            accessToken = this.accessToken
        ) { buildMotionSensorCsvLine() }
        stateDebug.startBuffering()

        indoorEstimator = IndoorEstimate(this, object : IndoorEstimate.IndoorLikelihoodListener {
            override fun onIndoorLikelihoodUpdated(likelihood: Float) {
//                renderDebugView()
            }

            override fun onStrongIndoorConfirmed() {
                // Flag so that onStop() does not start LocationService when moving to flatmap via IndoorEstimator
                Log.d("NAV_FORWARD", "Indoor confirmed; suppressing LocationService onStop once")
            }

            override fun onStrongOutdoorConfirmed() {
                suppressServiceOnStop = true
                this@MainActivity.onStrongOutdoorConfirmed() // 여기 주석처리하면 flatmap으로 안 넘어감
            }

            override fun onDebugMessage(message: String) {

            }
        })

        val version = NativeLib.version // 버전 정보
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stairsArea = StairsAreaProvider.load(this) // ← 이제 context 안전하게 사용 가능

        debugView = findViewById(R.id.debugView)
        debugView2 = findViewById(R.id.debugView2)
        // Initialize Unity
        UnityHolder.initOnce(this)
        val unityContainer = findViewById<FrameLayout>(R.id.unitySurfaceView)
        UnityHolder.attachTo(unityContainer)
        UnityHolder.player.requestFocus()
        patchUnityCurrentActivity()
        // Unity 붙인 직후(붙여넣기 위치) // 가은 유니티 메인 화면 지정
//        val startScreen = intent?.getStringExtra("START_SCREEN") ?: "MAIN"
        Log.d("mainqwer", testbed)
        unityStartScreenPending = true
        unityContainer.post {
            sendUnityStartScreenIfReady()
        }

        mapMatching = MapMatching(this, testbed)
        mapMatching.initialize(if (testbed.isNotBlank()) "$testbed/map_correction_areas.json" else "map_correction_areas.json")


        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        ensureSensorMasterBinding()

        // val binaryMapData = readRawBinaryFile(R.raw.hansando_0f_binarymap)  // 250219 원준 : 문장 삭제
        // val jsonMapData = readJsonFile(R.raw.indoor_map_0f)  // 250219 원준 : 문장 삭제

        // enginePtr = nativeLib.initializeEngine(binaryMapData, jsonMapData) // 250219 원준 : 문장 삭제

        NativeLibProvider.init(assets)  // 250219 원준 : 문장 추가
//        nativeLib.initializeEngine() // 250219 원준 : 문장 추가

        floorChangeDetection = com.fifth.maplocationlib.FloorChangeDetection(this, testbed)

        userStateMonitor = UserStateMonitor(this)
        startStartupStateChecks()

        rotationPatternDetector = RotationPatternDetector()
        rotationPatternDetector?.onStateChanged = {
            val paststate = statereal

            // Run on main with structured concurrency
            coroutineScope.launch {
                // 0s: immediate evaluation
                statetmp = userStateMonitor.getStatus(statereal)

                // +1s, +2s: evaluate again twice at 1s intervals
                repeat(2) {
                    delay(1000)
                    statetmp = userStateMonitor.getStatus(statereal)
                    if (paststate != statetmp) {
                        stateflag = true
//                        gyroCaliValue =
//                            (toNormalizedDegree(fusedOrientation[0]) + mapBasedGyroAngleCaliValue - compassDirection + 360) % 360
//                            (toNormalizedDegree(fusedOrientation[0]) - compassDirection + 360) % 360
                    }
                }

//                if (paststate != statetmp) {
//                    stateflag = true
//                    gyroCaliValue =
//                        (toNormalizedDegree(fusedOrientation[0]) + mapBasedGyroAngleCaliValue - compassDirection + 360) % 360
//                }
            }
        }


        appStartTime = System.currentTimeMillis()
        initPdrLogWriter()  // 이 줄 추가


//        initCsvWriter()

        CoroutineScope(Dispatchers.Main).launch {
            val floorQueue = mutableListOf<Float>() // Queue to track recent floor values
            var tickCounter = 0
            while (isActive) {
                currentUserStates = userStateMonitor.getStates()
                val motionWithoutSteps = currentUserStates["motionWithoutSteps"] as? Boolean ?: false
                if (motionWithoutSteps && !previousMotionWithoutSteps) {
                    val triggered = stateDebug.triggerMotionWithoutStepsCapture()
                    if (triggered) {
                        Log.i("MainActivity", "Motion without steps detected. Capturing sensor window.")
                    }
                }
                previousMotionWithoutSteps = motionWithoutSteps
                val isMovingNow = currentUserStates["isMoving"] as? Boolean ?: false
                if (!firststep && initialized) {
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
//                        val updateData = sensorDto.copy(userStateReal = statereal)
//                        sendLogDataToServer(locationDto, updateData)

                    }

                }
//                sendLogDataToServer(locationDto, updateData)
                //정지 이탈 과정
//                if (!wasMoving && isMovingNow) {
//                    val stillDuration = System.currentTimeMillis() - notMovingStartTimestamp
//                    if (stillDuration >= 30_000) { //멈춰서 30초 안지나면 방향 보정 안함
//                        if (!firststep && initialized) { //앱 시작하고 멈춰 있을 때 방지하기 위함
////                            nativeLib.resetGyroCalibration()
////                            nativeLib.reSearchDirectionOnly()
//
//                            statetmp = 0
//                            for (i: Int in 1..3) {
//                                statequeue.push(0)
//                                statequeue.poll()
//                            }
//                        }
//                    }
//                }
                wasMoving = isMovingNow


                // Add current floor to the queue
                floorQueue.add(rffloor)

                // Keep only the last 3 values
                if (floorQueue.size > 5) {
                    floorQueue.removeAt(0)
                }

                // Check if we have 3 non-zero identical floor values and not initialized yet
                if (!initialized && rffloor != 0f && floorQueue.size == 5 &&
                    floorQueue.all { it == rffloor }) {
                    if (!AppSharedState.nativeLibInitialized) {
                        nativeLib.initializeEngine(floor = rffloor.toInt(), testbed)
                        AppSharedState.nativeLibInitialized = true
                        AppSharedState.nativeLibOwner = nativeLibOwnerTag
                        Log.d("NativeLib", "Initialized by MainActivity (floor=${rffloor.toInt()}, testbed=$testbed)")
                    } else {
                        if (!isLocationServiceRunning()) {
                            AppSharedState.nativeLibOwner = nativeLibOwnerTag
                        }
                        Log.d("NativeLib", "Initialization skipped in MainActivity; already owned by ${AppSharedState.nativeLibOwner}")
                    }
                    floorChangeDetection.currentFloor = rffloor.toInt()
                    repeat(10) {
                        floorqueue.add(rffloor.toInt())
                        floorqueue.poll()
                    }
                    floorChangeDetection.prevFloor = rffloor.toInt()
                    initialized = true
                }

                // Increment tickCounter, and every 10 seconds perform state check
//                tickCounter += 1
//                if (tickCounter % 10 == 0) {
//                    statetmp = userStateMonitor.getStatus(statereal)
//                    if (paststate != statetmp) {
//                        stateflag = true
//                        gyroCaliValue =
//
//                            (toNormalizedDegree(fusedOrientation[0]) + mapBasedGyroAngleCaliValue - compassDirection + 360) % 360
//                            (toNormalizedDegree(fusedOrientation[0]) - compassDirection + 360) % 360
//
//                    }
//                }

                delay(1000) // 매 1초마다 사용자 상태 정보 가져오기. 이 지연시간은 조절하셔도 됩니다. 예를 들어 3000 이라고 설정하면, 3초마다 사용자 상태 정보를 가져오게 됩니다.
            }
        }
        // 권한 요청 수정 - 0922 김명권
        requestAllPermissions()

        createNotificationChannel(this)

        // FCM 토큰 가져오기 - 0922 김명권
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }
                val fcmToken = task.result ?: return@addOnCompleteListener
                val accessToken = this.accessToken // 이미 로그인 후 저장된 JWT 등
                val okHttpClient = OkHttpClient()

                saveFCMToken(
                    client = okHttpClient,
                    fcmTokenDto = FCMTokenDto(token = fcmToken),
                    accessToken = accessToken ?: "",
                    onUnauthorized = {
                        Log.e("FCM", "Saving FCM token failed")
                    }
                )
            }

        // FCM Message Broadcast Receiver 등록 - 0922 김명권
        val filter = IntentFilter("com.example.daewoo.FCM_MESSAGE")
        registerReceiver(fcmReceiver, filter)

        // Push 팝업을 위한 배너 변수 설정 - 0922 김명권
        bannerLayout = findViewById(R.id.bannerLayout)
        bannerTitle = findViewById(R.id.bannerTitle)
        bannerBody = findViewById(R.id.bannerBody)
        bannerButton = findViewById(R.id.bannerButton)

        bannerButton.setOnClickListener {
            bannerLayout.visibility = View.GONE
        }
        checkPermission()

        // SharedPreferences already hold navigation data for cross-component flows.
    }
    // Unity 플러그인들이 UnityPlayer.currentActivity를 요구할 때 null 방지
    private fun patchUnityCurrentActivity() {
        try {
            val unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer")
            val currentActivityField = unityPlayerClass.getDeclaredField("currentActivity")
            currentActivityField.isAccessible = true
            val cur = currentActivityField.get(null)
            if (cur !== this) {
                currentActivityField.set(null, this)
                Log.d("UnityBridge", "Patched UnityPlayer.currentActivity to MainActivity")
            }
        } catch (t: Throwable) {
            Log.e("UnityBridge", "Failed to patch UnityPlayer.currentActivity", t)
        }
    }

    private fun startLocationServiceIfNeeded() {
        if (suppressServiceOnStop) {
            Log.d("NAV_FORWARD", "Suppressed by flag, not starting LocationService")
            return
        }
        if (!hasBackgroundLocationPermission()) {
            Log.w("NAV_FORWARD", "ACCESS_BACKGROUND_LOCATION not granted; LocationService start skipped.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Toast.makeText(
                    this,
                    "백그라운드 위치 권한이 필요합니다. 설정에서 허용해 주세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        if (!isLocationServiceRunning()) {
            PreferenceHelper.setStartScreen(this, testbed)
            PreferenceHelper.setIndoorPoiName(this, null)
            val serviceIntent = Intent(this, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        isBackgroundServiceActive = true
    }

    private fun stopLocationServiceIfRunning() {
        if (isLocationServiceRunning()) {
            stopService(Intent(this, LocationService::class.java))
        }
        isBackgroundServiceActive = false
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun isLocationServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return activityManager.getRunningServices(Int.MAX_VALUE).any { it.service.className == LocationService::class.java.name }
    }

    private fun currentUserStatus(): String {
        return when {
            isAppRunning -> "Active"
            isBackgroundServiceActive -> "Background"
            else -> "Inactive"
        }
    }

    private fun getUidForPackage(packageName: String): Int {    // uid 수집용 - 0922 김명권
        return try {
            val appInfo: ApplicationInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.uid // UID 반환
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("DeviceInfo", "패키지를 찾을 수 없습니다: $packageName", e)
            -1 // 패키지를 찾을 수 없을 경우 -1 반환
        }
    }

    // 권한 관리 부분 변경 - 0922 김명권
    // 알림 권한 요청 안하는 버그 수정 - 0922 김명권
    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        // Android 10 이하만 스토리지 권한 추가
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // Android 13 이상일 때 알림 권한 추가
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
    }

    // 알림 채널 생성 함수 - 0922 김명권
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "fcm_default_channel",
                "FCM Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // 배너 표시 함수 - 0922 김명권
    fun showBanner(title: String, body: String) {
        bannerTitle.text = title
        bannerBody.text = body
        bannerLayout.visibility = View.VISIBLE

        // 5초 뒤 자동 숨김 (선택)
//        Handler(Looper.getMainLooper()).postDelayed({
//            bannerLayout.visibility = View.GONE
//        }, 5000)
    }

    private fun checkPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 101)
            } else {
                ActivityCompat.requestPermissions(this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 101)
            }
        }
    }

    // 250219 원준 : 아래 함수 두개 삭제
//    private fun readRawBinaryFile(resId: Int): ByteArray {
//        return resources.openRawResource(resId).use { it.readBytes() }
//    }
//    private fun readJsonFile(resId: Int): ByteArray {
//        return resources.openRawResource(resId).use { it.readBytes() }
//    }

    override fun onResume() {
        super.onResume()
        isAppRunning = true // 1031 김명권 밑에있던 이거 여기로 옮기기
        val updatedData = locationDto.copy(
            userStatus = currentUserStatus(), // isAppRunning이 true이므로 "Active"가 됨
            background = false // 활성 상태이므로 background는 false
        )
        sendLogDataToServer(updatedData, sensorDto)
        // 1031 김명권 옮기기 끝
        if (AppSharedState.nativeLibInitialized && AppSharedState.nativeLibOwner != nativeLibOwnerTag && !isLocationServiceRunning()) {
            Log.d("NativeLib", "MainActivity taking ownership from ${AppSharedState.nativeLibOwner}")
            AppSharedState.nativeLibOwner = nativeLibOwnerTag
        }
        activityActiveForSensors = true
        ensureSensorMasterBinding()
        ensureSensorListenerRegistration()
        stopLocationServiceIfRunning()
        UnityHolder.onResume()
        patchUnityCurrentActivity()
        allowUnityMessages = !unityAwaitingScene
        sendUnityStartScreenIfReady()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            indoorEstimator.start()
        } else {
            Log.w("MainActivity", "Location permission not granted. IndoorEstimator not started.")
        }
    }

    override fun onPause() {
        super.onPause()
        UnityHolder.onPause()
        allowUnityMessages = false
        activityActiveForSensors = false
        detachSensorListener()
        indoorEstimator.stop()

        // 앱 종료시 상태 전송 0402 김명권 0922 김명권 이거 삭제
//        isAppRunning = false
//        val updatedData = locationData.copy(user_status = isAppRunning) // 앱 종료 - 0402 김명권
//        sendLocationToServer(updatedData)
//        closeCsvWriter()
    }

    override fun onStop() {
        isAppRunning = false
        if (!suppressServiceOnStop) {
            startLocationServiceIfNeeded()
        } else {
            Log.d("NAV_FORWARD", "Skip starting LocationService: moving to flatmap via IndoorEstimator")
            // Reset the flag so subsequent onStop() calls behave normally
            suppressServiceOnStop = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        startupStateCheckJob?.cancel()
        activityActiveForSensors = false
        detachSensorListener()
        if (sensorMasterBound) {
            unbindService(sensorServiceConnection)
            sensorMasterBound = false
        }
        sensorMaster = null
        if (::stateDebug.isInitialized) {
            stateDebug.stopBuffering()
        }
        UnityHolder.onDestroy()
        indoorEstimator.stop()
        if (AppSharedState.nativeLibOwner == nativeLibOwnerTag) {
            if (!isLocationServiceRunning()) {
                nativeLib.destroyEngine()
                AppSharedState.nativeLibInitialized = false
                AppSharedState.nativeLibOwner = null
                initialized = false
                Log.d("NativeLib", "Destroyed by MainActivity")
            } else {
                Log.d("NativeLib", "MainActivity skipped destroy; LocationService still running")
            }
        }

        // 앱 종료시 상태 전송 0402 김명권
//        isAppRunning = false
//        val updatedData = locationDto.copy(userStatus = currentUserStatus()) // 앱 종료
//        sendLogDataToServer(updatedData, sensorDto)
//        unregisterReceiver(fcmReceiver) // Receiver 삭제 - 0922 김명권

        closeCsvWriter()
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

    private fun startStartupStateChecks() {
        startupStateCheckJob?.cancel()
        startupStateCheckJob = coroutineScope.launch {
            repeat(5) { iteration ->
                val paststate = statereal
                statetmp = userStateMonitor.getStatus(statereal)
                if (paststate != statetmp) {
                    stateflag = true
                }
                if (iteration < 4) {
                    delay(1000)
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        UnityHolder.player.windowFocusChanged(hasFocus)
        if (hasFocus) {
            patchUnityCurrentActivity()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE) return UnityHolder.player.injectEvent(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return UnityHolder.player.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // 볼륨 업 키가 눌렸을 때 실행할 함수
                floorChangeDetection.upperFloor()
                return true // 이벤트가 처리되었음을 시스템에 알림
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 볼륨 다운 키가 눌렸을 때 실행할 함수
                floorChangeDetection.lowerFloor()
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

    override fun onSensorChanged(event: SensorEvent) {
        //0406윤동근 아래 수정
        mapBasedGyroAngle = when(statereal) {
            1, 2, 22, 32 -> {
                (angletmp - rotateCaliValue + mapBasedGyroAngleCaliValue+ 360) % 360
            }
            3 -> {
                ((toNormalizedDegree(fusedOrientation[0]) - gyroCaliValue) + mapBasedGyroAngleCaliValue +180 + 360) % 360
            }
            else -> {
                ((toNormalizedDegree(fusedOrientation[0]) - gyroCaliValue) + mapBasedGyroAngleCaliValue + 360) % 360
            }
        }
        if (!(statereal == 2 || statereal == 1 || statereal == 22)){
            UnityPlayer.UnitySendMessage("GameObject", "OnReceiveGyroData", mapBasedGyroAngle.toString())//0315 gaeun
            UnityPlayer.UnitySendMessage("Main Camera", "SetCameraYawRotation", mapBasedGyroAngle.toString())//gaeun showmyposition
        }
        //0406윤동근 여기까지

        val currenttimemillis = System.currentTimeMillis()
        var hasWalked = false
        if (!(sensorMaster?.isReadyLocalization(event, fusedOrientation) ?: false)) {
            return
        }
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accMatrix = event.values.clone()
                lastAcc = event.values.clone()
                SensorManagercpp.updateAccelerometer(accMatrix, currenttimemillis)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magMatrix = event.values.clone()
                lastMag = event.values.clone()
            }
            Sensor.TYPE_GRAVITY -> gravMatrix = event.values.clone()
            Sensor.TYPE_PRESSURE -> {
                //0421동근 내부 대폭변경
                pressureEvent = event
                val currentTime = System.currentTimeMillis()
                val elapsedSeconds = (currentTime - appStartTime).toFloat() / 1000f
                userStateMonitor.updatePressureData(event)
                floorChangeDetection.updatePressureData(event.values[0], elapsedSeconds, mapBasedGyroAngle)

                lastStatereal = statereal
                lastPressureHpa = event.values[0]
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                lastLinAcc = event.values.clone()
                accelEvent = event
                SensorManagercpp.updateLinearAccelerometer(event.values, currenttimemillis)
                var currentValues = event.values.clone()
//                    floorChangeDetection.updateAccelData(accelEvent!!.values)
                userStateMonitor.updateAccelData(event)
                val isstep = PdrManager.isStep(statetmp, statereal)
                //여기까지
                logisstep = isstep

                if (isstep) {
                    Log.d("result3", "statetmp=$statetmp statereal=$statereal")
                    Log.d("mapangle", "mbga: ${mapBasedGyroAngle}\tabang: ${mapBasedGyroAngle - mapBasedGyroAngleCaliValue}\tstatetmp: ${statetmp}\trotatecali: ${rotateCaliValue}\tgyro: ${gyroCaliValue}\tangletmp ${angletmp}\tMM: ${mapBasedGyroAngleCaliValue}\ttmp:${statereal}\treal:${statetmp}\tcompass:${compassDirection}")
                    totalcount++
                    if(statequeue.all { it == statequeue.first() }){ //0406윤동근 추가
                        PdrManager.add_headqueue(mapBasedGyroAngle)
                    }
                    Log.d("result", "${statequeue.joinToString() }}")
//                        vibrator.vibrate(30)
                    pdrResult = PdrManager.getresult()
                    stepCount = (pdrResult as PDR).totalStepCount
                    cumulativeStepLength += stepLength

                    //0406윤동근 아래 when문 복붙 하시는게 편할것 같습니다.
                    when (statetmp) {
                        0, 3 -> {
                            stepLength = (pdrResult as PDR).stepLength.toFloat() - 0.035f
                            stepLength *= 0.9f
                            stepQueue.add(stepLength)
                            stepQueue.pop()
                            hasWalked = true
                            // CSV: accumulate and log on step
                        }
                        2 -> {
//                                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                            stepLength = (pdrResult as PDR).stepLength.toFloat()
                            mapBasedGyroAngle = ((pdrResult as PDR).direction.toFloat()- rotateCaliValue + mapBasedGyroAngleCaliValue+ 360) % 360

                            angletmp = (pdrResult as PDR).direction.toFloat()
                            hasWalked = true
                            UnityPlayer.UnitySendMessage(
                                "GameObject",
                                "OnReceiveGyroData",
                                mapBasedGyroAngle.toString()
                            )
                        }
                        1 -> {
                            stepLength = (pdrResult as PDR).stepLength.toFloat()
                            mapBasedGyroAngle = ((pdrResult as PDR).direction.toFloat() - rotateCaliValue + mapBasedGyroAngleCaliValue+ 360) % 360
                            angletmp = (pdrResult as PDR).direction.toFloat()
                            hasWalked = true
                            UnityPlayer.UnitySendMessage(
                                "GameObject",
                                "OnReceiveGyroData",
                                mapBasedGyroAngle.toString()
                            )
                        }
                        22 -> {
//                                toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                            stepLength = (pdrResult as PDR).stepLength.toFloat()*0.82f
                            mapBasedGyroAngle = ((pdrResult as PDR).direction.toFloat()- rotateCaliValue + mapBasedGyroAngleCaliValue+ 360) % 360

                            angletmp = (pdrResult as PDR).direction.toFloat()
                            hasWalked = true
                            UnityPlayer.UnitySendMessage(
                                "GameObject",
                                "OnReceiveGyroData",
                                mapBasedGyroAngle.toString()
                            )
                        }
                    }
                    UnityPlayer.UnitySendMessage("Main Camera", "SetCameraYawRotation", mapBasedGyroAngle.toString())//gaeun showmyposition
                    Log.d("pdrresult", "${(pdrResult as PDR).direction}\t${(pdrResult as PDR).stepLength}\t${(pdrResult as PDR).totalStepCount}\t${statetmp}\t${statereal}")
                }
            }

            Sensor.TYPE_LIGHT -> {
                SensorManagercpp.updateLight(event.values[0], System.currentTimeMillis())
                userStateMonitor.updateLightData(event.values[0]) // 계속 화면이 어두워져서.. 임시로 일단 이문장 사용
                lastLight = event.values[0]
            }
            Sensor.TYPE_PROXIMITY -> {
                distance = event.values[0]
                userStateMonitor.updateProximityData(distance)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                var currentValues: FloatArray//0406윤동근 수정
                val tmp = event.values.clone()
                rotationMovingAveragex.newData(tmp[0])
                rotationMovingAveragey.newData(tmp[1])
                rotationMovingAveragez.newData(tmp[2])
                rotationMovingAveragew.newData(tmp[3])
                currentValues = floatArrayOf(
                    rotationMovingAveragex.getAvg().toFloat(), rotationMovingAveragey.getAvg().toFloat(),
                    rotationMovingAveragez.getAvg().toFloat(), rotationMovingAveragew.getAvg().toFloat()
                )
                lastQuat = currentValues.clone()
                var rotvalue = currentValues
                val nowMs = System.currentTimeMillis()
                SensorManagercpp.updateRotationQuaternion(currentValues, nowMs)
                rotangle = SensorManagercpp.getRotationAnglesDeg()
                rotationPatternDetector?.addSample(rotangle[0], rotangle[1])//0406윤동근 추가

                userStateMonitor.updateRotationData(rotangle)

                if (quaternionQueue.size > 5) {
                    quaternionQueue.poll()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro = event.values.clone()
            }

        }
        userStateMonitor.updateWalkingState(hasWalked)


//            csvWriter?.let { writer ->
//                try {
//                    writer.append("${System.currentTimeMillis()-appStartTime},${rotangle[0]},${rotangle[1]},${rotangle[2]}," +
//                            "${lastQuat[0]},${lastQuat[1]},${lastQuat[2]},${lastQuat[3]}," +
//                            "${fusedOrientation[0]},${fusedOrientation[1]},${fusedOrientation[2]}," +
//                            "${accMatrix[0]},${accMatrix[1]},${accMatrix[2]},${lastLinAcc[0]},${lastLinAcc[1]},${lastLinAcc[2]}," +
//                            "${lastLight},${lastPressureHpa},${distance},${mapBasedGyroAngle},${mapBasedGyroAngleCaliValue}," +
//                            "${statereal},${statetmp},${stepLength},${cumulativeStepLength},${logisstep},${stateflag},${statequeue.joinToString()}\n")
//                    writer.flush()
//                } catch (e: IOException) {
//                    Log.e("MainActivity", "Error writing sensor data", e)
//                }
//            }

        UnityPlayer.UnitySendMessage("GameObject", "OnReceiveState", statereal.toString())


        if (hasWalked) {
            //0406윤동근 추가
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
            if(stateflag && statequeue.all { it == statequeue.first() }){
                stateflag = false
                rotateCaliValue = ((angletmp - PdrManager.get_headqueue_peek()!! + mapBasedGyroAngleCaliValue)+360)%360
//                    gyroCaliValue = (toNormalizedDegree(fusedOrientation[0]) + mapBasedGyroAngleCaliValue - compassDirection + 360) % 360

//                    nativeLib.reSearchStart(30)

            }
            //0406윤동근 여기까지
            calculateCompassDirection()

            // 다른 자세였다가 기본 자세로 돌아온 순간을 확인
//                if (previousPhoneState != 0 && statereal == 0) {
//                    nativeLib.reSearchStart()
//                }772line에 통합됨

            // 250219 원준 :  위 문장들 아래 문장들로 대체
            CoroutineScope(Dispatchers.Default).launch {
                if (statequeue.all { it == statequeue.first() }) {//0406윤동근 if문 추가
                    Log.d("codetime", "OK1")
                    val location = updateLocation(stepLength, mapBasedGyroAngle,  compassDirection,  stepCount)
                    Log.d("codetime", "OK4")
                    val mapMatchingLocation = mapMatching.getMapMatchingResult(location.x, location.y, location.floor)
                    currentPosition = arrayOf(mapMatchingLocation.x, mapMatchingLocation.y)
                    withContext(Dispatchers.Main) {
                        if ((location.x > 0.0f) && (location.y > 0.0f)) {   // 250219 원준 : 중요! location.x 와 location.y 일 때에는 해당 좌표값을 사용하면 안됩니다. (x,y)가 (-1.0, -1.0)이라는 것은 비정상적인 좌표값임을 나타냅니다.
//                            printDotInWebView(location.x, location.y, location.floor) // location.x / location.y / location.floor / location.orientation 가 각각 x좌표, y좌표, 층 정보, 이동 방향 정보를 갖고 있습니다.
                            UnityPlayer.UnitySendMessage( "GameObject", "ShowMyPosition", "${Math.round(mapMatchingLocation.x)}\t${Math.round(mapMatchingLocation.y)}\t${mapMatchingLocation.floor}\t${Math.round(res_distance)}")//gaeun showmyposition
//                                writePdrLog(mapMatchingLocation.x, mapMatchingLocation.y, ((toNormalizedDegree(fusedOrientation[0]) - gyroCaliValue) + 360)%360, compassDirection, stepLength, mapMatchingLocation.floor)



                            locationDto = LocationDto(
                                userId = userId ?: "guest",
                                mapId = 109,
                                userX = mapMatchingLocation.x.toDouble(),
                                userY = mapMatchingLocation.y.toDouble(),
                                userZ = mapMatchingLocation.floor.toDouble(),
                                userDirection = mapBasedGyroAngle.toDouble(),
                                userFloor = mapMatchingLocation.floor.toDouble(),
                                userStatus = "Active",
                                background = false
                            )
                            sendLogDataToServer(locationDto, sensorDto)
                        }//0406윤동근 if문 닫기
                    }
                }
                else{
                    val location = updateLocation(stepQueue.peek(), PdrManager.get_headqueue_peek(),  compassDirection,  stepCount)
                    Log.d("result1 location", "${location.x}, ${location.y}")
                    val mapMatchingLocation = mapMatching.getMapMatchingResult(location.x, location.y, location.floor)
                    Log.d("result1 mapMatchi", "${mapMatchingLocation.x}, ${mapMatchingLocation.y}")
                    currentPosition = arrayOf(mapMatchingLocation.x, mapMatchingLocation.y)
                    withContext(Dispatchers.Main) {
                        if ((location.x > 0.0f) && (location.y > 0.0f)) {   // 250219 원준 : 중요! location.x 와 location.y 일 때에는 해당 좌표값을 사용하면 안됩니다. (x,y)가 (-1.0, -1.0)이라는 것은 비정상적인 좌표값임을 나타냅니다.
                            UnityPlayer.UnitySendMessage( "GameObject", "ShowMyPosition", "${Math.round(mapMatchingLocation.x)}\t${Math.round(mapMatchingLocation.y)}\t${mapMatchingLocation.floor}\t${Math.round(res_distance)}")//gaeun showmyposition
                            writePdrLog(mapMatchingLocation.x, mapMatchingLocation.y, ((toNormalizedDegree(fusedOrientation[0]) - gyroCaliValue) + 360)%360, compassDirection, stepLength, mapMatchingLocation.floor)

                            // LocationDto로 변경 0922 김명권 수정됨
                            locationDto = LocationDto(
                                userId = userId ?: "guest",
                                mapId = 1,
                                userX = mapMatchingLocation.x.toDouble(),
                                userY = mapMatchingLocation.y.toDouble(),
                                userZ = mapMatchingLocation.floor.toDouble(),
                                userDirection = mapBasedGyroAngle.toDouble(),
                                userFloor = mapMatchingLocation.floor.toDouble(),
                                userStatus = "Active",
                                background = false
                            )
                            sendLogDataToServer(locationDto, sensorDto)
                        }//0406윤동근 if문 닫기
                    }

                }
            }
            previousPhoneState = statereal
        }
        // 센서 타입별로 수신된 값을 수정 / 0709 송형근
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


    override fun onStart() { // 앱이 시작됨을 감지 - 0922 김명권
        super.onStart()
        isAppRunning = true
        val updatedData = locationDto.copy(userStatus = "Active")
        sendLogDataToServer(updatedData, sensorDto)
    }

    // 각 로그 데이터 서버 전송 함수로 변경 - 0709 송형근, 0922 김명권 수정됨
    private fun sendLogDataToServer(locationData: LocationDto, sensorData: SensorDto) {
        // accessToken이 null인 경우 조기 리턴
        if (accessToken == null) {
            Log.w("MainActivity", "AccessToken이 null입니다. 서버 전송 생략.")
            return
        }

        val client = OkHttpClient()

        // utils/LogDataUtils.kt
        sendLocationData(client, locationData, accessToken!!){
            // 401 인증 만료 시
            runOnUiThread {
                Toast.makeText(this@MainActivity, "인증 오류: 토큰 만료. 재로그인 해주세요.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
            }
        }

        // utils/LogDataUtils.kt
        sendSensorData(client, sensorData, accessToken!!){
            // 401 인증 만료 시
            runOnUiThread {
                Toast.makeText(this@MainActivity, "인증 오류: 토큰 만료. 재로그인 해주세요.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
            }
        }

    }

    private fun updateLocation(stepLength: Float, mapBasedGyroAngle: Float, compassDirection: Float, stepCount: Int): LocationInfo {
        floorChangeDetection.updateGyroData(mapBasedGyroAngle)
        currentFloor = floorChangeDetection.currentFloor

        if ((floorChangeDetection.getCurrentStairsState() == "계단 확정") || (floorChangeDetection.getCurrentStairsState() == "층 도착")) {
            elevationMode = 0
        }
        else if ((floorChangeDetection.getCurrentElevatorState() == "엘리베이터 확신") || (floorChangeDetection.getCurrentElevatorState() == "엘리베이터 도착 (내리지 않음)") || (floorChangeDetection.getCurrentElevatorState() == "엘리베이터 내림")) {
            elevationMode = 2
        }

        if (hasFloorChanged()) {
            searchRange = 50
            stairsHardResetFlag = false
        }
        else {
            if (!stairsHardResetFlag && floorChangeDetection.getCurrentStairsState() == "계단 확정") {
                var starisCoord = floorChangeDetection.setStairsInfo(currentPosition, currentFloor, floorChangeDetection.arrivedStairGyroValue, floorChangeDetection.elevationStatus)
                nativeLib.reSearchStartInStairs(starisCoord!![0].toInt(), starisCoord[1].toInt())
                stairsHardResetFlag = true
            }
        }
        previousFloor = currentFloor

        // 현재 좌표가 계단 영역에 있는지 확인
        var isInStairsArea = false
        val floorStairsAreas = stairsArea[currentFloor]
        if (floorStairsAreas != null) {
            for (area in floorStairsAreas) {
                val x_min = area[0]
                val y_min = area[1]
                val x_max = area[2]
                val y_max = area[3]

                if (cur_pos[0] >= x_min && cur_pos[0] <= x_max &&
                    cur_pos[1] >= y_min && cur_pos[1] <= y_max) {
                    isInStairsArea = true
                    break
                }
            }
        }

        // 계단 영역에 있다면 걸음 길이 조정
        var adjustedStepLength = stepLength
        if (isInStairsArea && (res_distance < 4.0f)) {
            adjustedStepLength = 0.3f
        }

        if (initialized) {
            var arrivedGyroValue = floorChangeDetection.arrivedStairGyroValue
            if (elevationMode == 2) {
                arrivedGyroValue = floorChangeDetection.arrivedElevatorGyroValue
            }
            val result: FloatArray? = nativeLib.processStep(mapBasedGyroAngle-mapBasedGyroAngleCaliValue, compassDirection, adjustedStepLength, stepCount, currentFloor, arrivedGyroValue, elevationMode)

            if (result != null) {
                centerX = result[0].toInt()
                centerY = result[1].toInt()
                res_distance = result[2]
                mapBasedGyroAngleCaliValue = result[3].toInt()
                cur_pos = arrayOf(centerX.toFloat(), centerY.toFloat())
                Log.d("result", "$centerX\t$centerY\t$currentFloor")
            }
        }

        return LocationInfo(cur_pos[0], cur_pos[1], currentFloor, mapBasedGyroAngle, statereal) // //동근새로 statetmp -> statereal
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
                Log.w("MainActivity", "나침반 회전행렬 계산 실패: 센서 데이터가 불안정합니다.")
                return
            }

            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            compassDirection = ((Math.toDegrees(orientationAngles[0].toDouble())+360) % 360).toFloat()
            Log.d("MainActivity", "나침반 방향: $compassDirection")
        } catch (e: Exception) {
            Log.e("MainActivity", "나침반 방향 계산 오류: ${e.message}", e)
        }
    }

    // 250206 원준 : 아래 함수 추가
    private fun multiplyQuaternions(q1: FloatArray, q2: FloatArray): FloatArray {
        val x1 = q1[0]
        val y1 = q1[1]
        val z1 = q1[2]
        val w1 = q1[3]
        val x2 = q2[0]
        val y2 = q2[1]
        val z2 = q2[2]
        val w2 = q2[3]
        return floatArrayOf(
            w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
            w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
            w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
            w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2
        )
    }

    // 250206 원준 : 아래 함수 추가
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
                (2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0) // asin 범위 제한
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

    private fun FloatArray.safeGet(index: Int): Float = if (index in indices) this[index] else 0f

    private fun buildMotionSensorCsvLine(): String {
        val timestamp = System.currentTimeMillis()
        val quaternion = lastQuat
        val orientation = getRotationFromQuaternion(quaternion)
        val gyroValues = lastGyro
        val accelValues = lastAcc
        val linearValues = lastLinAcc
        val stateQueueSnapshot = statequeue.toList()

        val builder = StringBuilder(256)
        builder.append(timestamp.toString())
        builder.append(',').append(orientation.safeGet(0))
        builder.append(',').append(orientation.safeGet(1))
        builder.append(',').append(orientation.safeGet(2))
        builder.append(',').append(quaternion.safeGet(0))
        builder.append(',').append(quaternion.safeGet(1))
        builder.append(',').append(quaternion.safeGet(2))
        builder.append(',').append(quaternion.safeGet(3))
        builder.append(',').append(gyroValues.safeGet(0))
        builder.append(',').append(gyroValues.safeGet(1))
        builder.append(',').append(gyroValues.safeGet(2))
        builder.append(',').append(accelValues.safeGet(0))
        builder.append(',').append(accelValues.safeGet(1))
        builder.append(',').append(accelValues.safeGet(2))
        builder.append(',').append(linearValues.safeGet(0))
        builder.append(',').append(linearValues.safeGet(1))
        builder.append(',').append(linearValues.safeGet(2))
        builder.append(',').append(lastLight)
        builder.append(',').append(lastPressureHpa)
        builder.append(',').append(sensorDto.proximity)
        builder.append(',').append(compassDirection)
        builder.append(',').append(mapBasedGyroAngle)
        builder.append(',').append(statereal)
        builder.append(',').append(statetmp)
        builder.append(',').append(stepLength)
        builder.append(',').append(stepCount)
        builder.append(',').append(if (logisstep) 1 else 0)
        builder.append(',').append(if (stateflag) 1 else 0)
        for (i in 0 until 5) {
            builder.append(',')
            builder.append(stateQueueSnapshot.getOrNull(i) ?: 0)
        }
        builder.append('\n')
        return builder.toString()
    }

    private fun hasFloorChanged(): Boolean {
        return currentFloor != previousFloor
    }


    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
    }

    fun toNormalizedDegree(value: Float): Float = ((Math.toDegrees(value.toDouble()) + 360) % 360).toFloat()  // radian 넣으면 0~360도 사이의 값으로 반환 (radian to degree)


    override fun onUnityPlayerUnloaded() {
        moveTaskToBack(true)
    }
    override fun onUnityPlayerQuitted() {
        // TODO: Implement this method if needed
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newStartScreen = PreferenceHelper.getStartScreen(this, testbed)
        if (!newStartScreen.isNullOrBlank() && newStartScreen != testbed) {
            testbed = newStartScreen
            PreferenceHelper.setStartScreen(this, testbed)
            unityStartScreenPending = true
            sendUnityStartScreenIfReady()
        }
        UnityHolder.player.newIntent(intent)
    }

    private fun onStrongOutdoorConfirmed() {
        if (waitingIndoorConfirm) return
        requestIndoorConfirmFromUnity()
        PreferenceHelper.setStartScreen(this, "main")

        AppSharedState.flatmapStrongIndoorConfirmed = false

        startActivity(Intent(this@MainActivity, flatmapActivity::class.java))
        finish()

    }

    private fun requestIndoorConfirmFromUnity() {
        waitingIndoorConfirm = true
        unitySend("MapWebViewBridge", "OnBuildingHint", "main")
    }


//    private fun renderDebugView() {
//        if (!::debugView.isInitialized) return
//        val parts = mutableListOf<String>()
//        if (indoorStatusText.isNotEmpty()) parts.add(indoorStatusText)
//        if (poiStatusText.isNotEmpty()) parts.add(poiStatusText)
//        if (navStatusText.isNotEmpty()) parts.add(navStatusText)
//        debugView.text = parts.joinToString(separator = "\n")
//    }

    private fun updateUI(x: Double, y: Double) {
        // UI 업데이트 로직
    }


    // ===== CSV Logging Helpers =====
//    private fun initCsvWriter() {
//        try {
//            val fmt = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault())
//            val ts = fmt.format(appStartTime)
//            val dir = filesDir
//            if (!dir.exists()) dir.mkdirs()
//            val name = "sensor_data_${ts}.csv"
//            val file = File(dir, name)
//            val newFile = !file.exists()
//            csvFile = file
//            csvWriter = BufferedWriter(FileWriter(file, /*append=*/true))
//            if (newFile) {
//                csvWriter?.apply {
//                    write("timestamp,pitch,roll,yaw,rot_x,rot_y,rot_z,rot_w,gyro_x,gyro_y,gyro_z,accel_x,accel_y,accel_z,linear_x,linear_y,linear_z,light,pressure,proximity,heading,MM,statereal,statetmp,steplength,totalstep,isstep,stateflag,statequeue0,statequeue1,statequeue2,statequeue3,statequeue4\n")
//                    flush()
//                }
//            }
//            Log.d("CSV", "CSV logging to: ${file.absolutePath}")
//        } catch (e: Exception) {
//            Log.e("CSV", "initCsvWriter failed: ${e.message}", e)
//        }
//    }

    private fun closeCsvWriter() {
        try { csvWriter?.flush() } catch (_: Exception) {}
        try { csvWriter?.close() } catch (_: Exception) {}
        csvWriter = null
    }

    private fun initPdrLogWriter() {
        try {
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val ts = fmt.format(System.currentTimeMillis())
            val dir = filesDir  // 내부 저장소로 변경 (권한 불필요)

            if (!dir.exists()) dir.mkdirs()
            val name = "pdr_log_${ts}.txt"
            val file = File(dir, name)
            pdrLogFile = file
            pdrLogWriter = BufferedWriter(FileWriter(file, true))
            Log.d("PDR_LOG", "PDR logging to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("PDR_LOG", "initPdrLogWriter failed: ${e.message}", e)
        }
    }

    private fun writePdrLog(x: Float, y: Float, gyro: Float, compass: Float, stepLen: Float, floor: Int) {
        pdrLogWriter?.let { writer ->
            try {
                writer.append("$x\t$y\t$gyro\t$compass\t$stepLen\t$floor\t-\t-\n")
                writer.flush()
            } catch (e: IOException) {
                Log.e("PDR_LOG", "Error writing PDR data", e)
            }
        }
    }

    private fun closePdrLogWriter() {
        try { pdrLogWriter?.flush() } catch (_: Exception) {}
        try { pdrLogWriter?.close() } catch (_: Exception) {}
        pdrLogWriter = null
    }

}
