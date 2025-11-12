package com.example.daewoo.bg
import android.hardware.SensorEvent
import com.example.daewoo.dtos.LocationDto
import com.example.daewoo.dtos.SensorDto
import com.example.daewoo.dtos.SensorXYZ
import com.fifth.pdr_ext.PDRM
import com.fifth.pdr_ext.SMcpp
import java.util.LinkedList
object AppSharedState {
    var logisstep: Boolean = false
    var elevationMode: Int = 0
    var cumulativeStepLength: Float = 0.0f
    var lastGyro: FloatArray = floatArrayOf(0f, 0f, 0f)
    var lastAcc: FloatArray = floatArrayOf(0f, 0f, 0f)
    var lastLinAcc: FloatArray = floatArrayOf(0f, 0f, 0f)
    var lastMag: FloatArray = floatArrayOf(0f, 0f, 0f)
    var lastQuat: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    var lastLight: Float = 99999f
    var lastPressureHpa: Float = 0f
    var magMatrix: FloatArray = FloatArray(3)
    var accMatrix: FloatArray = FloatArray(3)
    var gravMatrix: FloatArray = FloatArray(3)
    var fusedOrientation: FloatArray = FloatArray(3)
    var mapBasedGyroAngle: Float = 0.0f
    var mapBasedGyroAngleCaliValue: Int = 0
    var angletmp: Float = 0.0f
    var gyroCaliValue: Float = 0.0f
    var rotateCaliValue: Float = 0.0f
    var compassDirection: Float = 0.0f
    var stepLength: Float = 0.0f
    var stepCount: Int = 0
    var resDistance: Float = 10000.0f
    var statereal: Int = 0
    var statetmp: Int = 0
    var previousPhoneState: Int = 0
    var notMovingStartTimestamp: Long = 0L
    var wasMoving: Boolean = false
    var firststep: Boolean = true
    var initialized: Boolean = false
    var stairsHardResetFlag: Boolean = false
    var isSensorStabled: Boolean = false
    var accStableCount: Int = 50
    var gyroStableCount: Int = 200
    var pressureEvent: SensorEvent? = null
    var accelEvent: SensorEvent? = null
    var lastStatereal: Int = 0
    var searchRange: Int = 99999
    var centerX: Int = 0
    var centerY: Int = 0
    var curPos: Array<Float> = arrayOf(0.0f, 0.0f)
    var currentPosition: Array<Float> = arrayOf(0.0f, 0.0f)
    var currentFloor: Int = 0
    var previousFloor: Int = 0
    var distance: Float = 5.0f
    var rffloor: Float = 1.0f
    var pdrResult: Any? = null
    val stepQueue: LinkedList<Float> = LinkedList(listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f))
    val quaternionQueue: LinkedList<FloatArray> = LinkedList(
        listOf(
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
        )
    )
    val statequeue: LinkedList<Int> = LinkedList(listOf(0, 0, 0, 0, 0))
    val floorQueue: MutableList<Float> = mutableListOf()
    val floorqueue: LinkedList<Int> = LinkedList(List(10) { 0 })
    var rotangle: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    var locationDto: LocationDto = LocationDto(
        userId = "guest",
        mapId = 1,
        userX = 0.0,
        userY = 0.0,
        userZ = 0.0,
        userDirection = 0.0,
        userFloor = 0.0,
        userStatus = "Inactive",
        background = true
    )
    var sensorDto: SensorDto = SensorDto(
        mapId = 1,
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
    var isAppRunning: Boolean = false
    var isBackgroundServiceActive: Boolean = false
    val pdrManager: PDRM = PDRM()
    val sensorManagercpp: SMcpp = SMcpp()
    var flatmapBackgroundActive: Boolean = false
    var flatmapLastGpsPayload: String? = null
    var flatmapLastGpsTimestamp: Long = 0L
    var flatmapIndoorLikelihood: Float = 0.0f
    var flatmapStrongIndoorConfirmed: Boolean = false
    var flatmapIndoorDebugMessage: String = ""
    var flatmapMotionWithoutSteps: Boolean = false
    var flatmapIndoorLikelihoodTimestamp:Long = 0L
    var sensorMasterRunning: Boolean = false
    var nativeLibInitialized: Boolean = false
    var nativeLibOwner: String? = null
}
