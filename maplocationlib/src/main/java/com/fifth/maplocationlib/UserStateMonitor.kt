package com.fifth.maplocationlib

import android.content.Context
import android.hardware.SensorEvent
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.LinkedList
import java.util.ArrayDeque
import kotlin.math.min

class UserStateMonitor(private val context: Context) {

    // -------------------------------
    // 기존 센서 관련 변수들
    // -------------------------------
    private var lastStepTime: Long = 0
    private val stationaryThreshold: Long = 5000 // 5 seconds
    private val impactThreshold: Float = 25.0f   // 충격 임계값
    private val sensorErrorThreshold: Long = 1000 // 센서 에러 체크 주기 (1초)

    private val tablethreshold : Float = 0.03f

    private var isMoving: Boolean = false
    private var stopstate: Boolean = false
    private var hasImpact: Boolean = false
    private var hasSensorError: Boolean = false
    private var isFirstStep: Boolean = true // 첫 걸음 여부 체크
    private var isOntable:Boolean = false

    private var accelerometerValues: FloatArray = floatArrayOf(0f,0f,0f)
    private var pressureValue: Float = 0f
    // rotationValue: 0번 인덱스: x축, 1번 인덱스: y축, 등으로 가정
    private var rotationValue: FloatArray = floatArrayOf(0f, 0f, 0f, 0f)
    private var lightSensorValue: Float = 100f
    private var distanceSensorValue: Float = 5f
    private var pressureSensorValue: Float = 1000.0f
    private var lastSensorUpdateTime: Long = System.currentTimeMillis()

    // 보조 회전 변수
    private var lastYUpPeek = 0f
    private var lastYDownPeek = 0f

    // 밝기 임계값 관련
    private var prebrightness = 0.0f
    private val lightqueue: LinkedList<Float> = LinkedList(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))

    /**
     * 전화 상태 코드:
     * 0: normal
     * 1: handheld swing
     * 2: in pocket
     * 3: other ~ phone call
     */
    private var phoneState: Int = 0

    // -------------------------------
    // ★ 추가: 센서 레코드를 1초간 큐잉하는 구조
    // -------------------------------
    private data class SensorRecord(
        val timestamp: Long,
        val rotation: FloatArray, // 현재 rotationValue의 복사본
        val light: Float,         // 현재 lightSensorValue
        val distance: Float,       // 현재 distanceSensorValue
        val pressure: Float,
        val accel: FloatArray,
        val accelMagnitude: Float
    )

    // 1초간의 데이터를 저장하는 큐
    private val sensorRecords = ArrayDeque<SensorRecord>()

    // Deques for O(1) max/min queries over the 1‑second window
    private val dequeMaxRot0 = ArrayDeque<Float>()
    private val dequeMinRot0 = ArrayDeque<Float>()
    private val dequeMaxRot1 = ArrayDeque<Float>()
    private val dequeMinRot1 = ArrayDeque<Float>()
    private val dequeMaxLight = ArrayDeque<Float>()
    private val dequeMinLight = ArrayDeque<Float>()
    private val dequeMaxPressure = ArrayDeque<Float>()
    private val dequeMinPressure = ArrayDeque<Float>()
    private val dequeMaxAccel = ArrayDeque<Float>()
    private val dequeMinAccel = ArrayDeque<Float>()

    private val motionAccelThreshold = 1.0f
    private val motionRotationThreshold = 15f
    private val motionWithoutStepTimeoutMs = 2000L


    // 센서 업데이트 후 현재 상태를 하나의 레코드로 큐에 추가
    private fun recordSensorData() {
        val record = SensorRecord(
            timestamp = System.currentTimeMillis(),
            rotation = rotationValue.copyOf(),
            light = lightSensorValue,
            distance = distanceSensorValue,
            pressure = pressureSensorValue,
            accel = accelerometerValues.copyOf(),
            accelMagnitude = accelerometerValues.let {
                sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
            }
        )
        sensorRecords.add(record)
        // --- update deques with the new sample ---
        val rot0 = record.rotation[0]
        while (dequeMaxRot0.isNotEmpty() && dequeMaxRot0.last() < rot0) dequeMaxRot0.removeLast()
        while (dequeMinRot0.isNotEmpty() && dequeMinRot0.last() > rot0) dequeMinRot0.removeLast()
        dequeMaxRot0.addLast(rot0)
        dequeMinRot0.addLast(rot0)

        val rot1 = record.rotation[1]
        while (dequeMaxRot1.isNotEmpty() && dequeMaxRot1.last() < rot1) dequeMaxRot1.removeLast()
        while (dequeMinRot1.isNotEmpty() && dequeMinRot1.last() > rot1) dequeMinRot1.removeLast()
        dequeMaxRot1.addLast(rot1)
        dequeMinRot1.addLast(rot1)

        val light = record.light
        while (dequeMaxLight.isNotEmpty() && dequeMaxLight.last() < light) dequeMaxLight.removeLast()
        while (dequeMinLight.isNotEmpty() && dequeMinLight.last() > light) dequeMinLight.removeLast()
        dequeMaxLight.addLast(light)
        dequeMinLight.addLast(light)

        val pressure = record.pressure
        while (dequeMaxPressure.isNotEmpty() && dequeMaxPressure.last() < pressure) dequeMaxPressure.removeLast()
        while (dequeMinPressure.isNotEmpty() && dequeMinPressure.last() > pressure) dequeMinPressure.removeLast()
        dequeMaxPressure.addLast(pressure)
        dequeMinPressure.addLast(pressure)

        val accelMag = record.accelMagnitude
        while (dequeMaxAccel.isNotEmpty() && dequeMaxAccel.last() < accelMag) dequeMaxAccel.removeLast()
        while (dequeMinAccel.isNotEmpty() && dequeMinAccel.last() > accelMag) dequeMinAccel.removeLast()
        dequeMaxAccel.addLast(accelMag)
        dequeMinAccel.addLast(accelMag)

        // Remove records older than 1 second
        val cutoff = System.currentTimeMillis() - 1000
        while (sensorRecords.isNotEmpty() && sensorRecords.first().timestamp < cutoff) {
            val old = sensorRecords.removeFirst()

            val oRot0 = old.rotation[0]
            if (dequeMaxRot0.isNotEmpty() && dequeMaxRot0.first() == oRot0) dequeMaxRot0.removeFirst()
            if (dequeMinRot0.isNotEmpty() && dequeMinRot0.first() == oRot0) dequeMinRot0.removeFirst()

            val oRot1 = old.rotation[1]
            if (dequeMaxRot1.isNotEmpty() && dequeMaxRot1.first() == oRot1) dequeMaxRot1.removeFirst()
            if (dequeMinRot1.isNotEmpty() && dequeMinRot1.first() == oRot1) dequeMinRot1.removeFirst()

            val oLight = old.light
            if (dequeMaxLight.isNotEmpty() && dequeMaxLight.first() == oLight) dequeMaxLight.removeFirst()
            if (dequeMinLight.isNotEmpty() && dequeMinLight.first() == oLight) dequeMinLight.removeFirst()

            val oPressure = old.pressure
            if (dequeMaxPressure.isNotEmpty() && dequeMaxPressure.first() == oPressure) dequeMaxPressure.removeFirst()
            if (dequeMinPressure.isNotEmpty() && dequeMinPressure.first() == oPressure) dequeMinPressure.removeFirst()

            val oAccel = old.accelMagnitude
            if (dequeMaxAccel.isNotEmpty() && dequeMaxAccel.first() == oAccel) dequeMaxAccel.removeFirst()
            if (dequeMinAccel.isNotEmpty() && dequeMinAccel.first() == oAccel) dequeMinAccel.removeFirst()
        }
    }

    /**
     * getStatus() 호출 시, 최근 1초 동안의 센서 데이터를 분석하여 최종 상태를 결정합니다.
     *
     * 반환 상태 코드:
     * 0: normal
     * 1: handheld swing
     * 2: in pocket
     * 3: other (예: 전화중)
     */
    fun getStatus(paststatus:Int): Int {

        if (sensorRecords.isEmpty()) return getPhoneState()

        val maxRot0 = dequeMaxRot0.first()
        val minRot0 = dequeMinRot0.first()
        val maxRot1 = dequeMaxRot1.first()
        val minRot1 = dequeMinRot1.first()
        val maxLight = dequeMaxLight.first()
        val minLight = dequeMinLight.first()
        val maxPressure = dequeMaxPressure.first()
        val minPressure = dequeMinPressure.first()
        val lastDistance = sensorRecords.last().distance
        val maxAccelMag = dequeMaxAccel.first()
        val minAccelMag = dequeMinAccel.first()


        val diffRot0 = maxRot0 - minRot0
        val diffRot1 = maxRot1 - minRot1
        val diffLight = maxLight - minLight
        val diffAccel = maxAccelMag - minAccelMag

        var resultstate: Int = when {//1의 자리로 상태 보면됨
            lastDistance < 1f && rotationValue[0]>50 -> 3 //전화
            diffRot0 < 15f && diffRot1 < 10f && maxLight < 5 && diffLight < 10f && lightSensorValue <= 2   -> 22 //자켓 주머니
            diffRot0 < 15f && diffRot1 < 15f && maxLight < 5 && diffLight < 10f && diffRot1 <= 5  && (rotationValue[0] > 35 || rotationValue[0] < -35)-> 32 //바지 뒷주머니
            diffRot0 < 5f && diffRot1 < 5f  -> 0 //일반 파지

            diffRot1 < 50f && diffLight < 10f && maxLight < 5  && (rotationValue[0] > 35 || rotationValue[0] < -35)-> 2 //바지 앞주머니

            diffRot0 > 15f && diffRot1 > 50 -> 1 //손에 들고 흔들기


            else -> 0
        }
        // Prevent direct transitions between pants pockets (front/back) and jacket pocket
        if ((paststatus % 10 == 2) && (resultstate % 10 == 2 )) {
            resultstate = paststatus
        }
//        Log.d("userstatemonitor","${diffRot0}\t${diffRot1}\t${minRot0}:${maxRot0};${maxLight};${diffLight};$resultstate")

        return resultstate
    }

    // -------------------------------
    // 기존 센서 업데이트 메서드들 (필요 시 recordSensorData() 호출 추가)
    // -------------------------------
    fun updateLightData(lightValue: Float) {
        prebrightness = lightSensorValue
        lightSensorValue = lightValue
        lightqueue.add(lightSensorValue)
        lightqueue.poll()
        updateSensorTimestamp()
        recordSensorData() // 큐에 현재 상태 기록
    }

    fun updateProximityData(distance: Float) {
        distanceSensorValue = distance
        updateSensorTimestamp()
        recordSensorData()
    }

    fun updateRotationData(rotAngle: FloatArray) {
        val previousX = rotationValue[0]
        rotationValue = rotAngle.copyOf()
        updateSensorTimestamp()

        // 회전 변화에 따른 보조 변수 업데이트
        if (rotationValue[1] < previousX) {
            lastYDownPeek = rotationValue[1]
        } else if (rotationValue[1] > previousX) {
            lastYUpPeek = rotationValue[1]
        }
        recordSensorData()
    }

    fun updateAccelData(event: SensorEvent) {
        accelerometerValues = event.values.clone()
        checkImpact()
        updateSensorTimestamp()
        recordSensorData()
    }

    fun updatePressureData(event: SensorEvent) {
        pressureValue = event.values[0]
        updateSensorTimestamp()
        recordSensorData()
    }

    fun updateWalkingState(walked: Boolean) {
        if (walked) {
            if (isFirstStep) {
                isFirstStep = false
            }
            updateStepDetection()
            recordSensorData()
        }
    }

    private fun updateStepDetection() {
        lastStepTime = System.currentTimeMillis()
        isMoving = true
    }

    private fun checkMovementState(threshold: Long) {
        if (isFirstStep) {
            isMoving = false
            return
        }
        val currentTime = System.currentTimeMillis()
//        isMoving = (currentTime - lastStepTime) < min((threshold+50*10), 5000)
        isMoving = (currentTime - lastStepTime) < 8000L

    }

    private fun checkImpact() {
        accelerometerValues?.let {
            val magnitude = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
            hasImpact = magnitude > impactThreshold
        }
    }

    private fun checkSensorError() {
        val currentTime = System.currentTimeMillis()
        hasSensorError = (currentTime - lastSensorUpdateTime) > sensorErrorThreshold
    }

    private fun updateSensorTimestamp() {
        lastSensorUpdateTime = System.currentTimeMillis()
    }

    /**
     * 기존 getPhoneState() 로직 (큐에 데이터가 없을 때의 fallback)
     */
    fun getPhoneState(): Int {
        return when {
//            distanceSensorValue <= 1f -> 3

            phoneState == 0 && ((rotationValue[1] in -30f..20f)) && (abs(lightqueue.max() - lightqueue.min()) < 15) -> 2
            phoneState != 0 && rotationValue[0] in -100f..-75f && (abs(lightqueue.max() - lightqueue.min()) < 15) -> 2
            lastYDownPeek < -50 -> 1
            else -> 0
        }
    }

    // 사용자 상태 조회 메서드들
    fun getStates(threshold: Long = 5000L): Map<String, Boolean> {
        checkMovementState(threshold)
        checkSensorError()
        val motionWithoutSteps = detectMotionWithoutSteps()
        return mapOf(
            "isMoving" to isMoving,
            "hasImpact" to hasImpact,
            "hasSensorError" to hasSensorError,
            "motionWithoutSteps" to motionWithoutSteps
        )
    }

    fun onTablecheck():Boolean{
        accelerometerValues?.let {
            val magnitude = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
            isOntable = magnitude > tablethreshold
        }
        return isOntable
    }

//    fun isMoving(): Boolean {
//        checkMovementState()
//        return isMoving
//    }

    fun hasImpact(): Boolean = hasImpact

    fun hasSensorError(): Boolean {
        checkSensorError()
        return hasSensorError
    }

    fun getAccelerometerValues(): FloatArray? = accelerometerValues
    fun getPressureValue(): Float = pressureValue

    /**
     * 단순 예시로 조도 값이 15 미만이면 주머니 안에 있다고 판단
     */
    fun inPocketDetect(brightness: Float): Boolean {
        return brightness < 15f
    }

    fun detectMotionWithoutSteps(): Boolean {
        if (sensorRecords.isEmpty()) return false
        val accelSwing = if (dequeMaxAccel.isNotEmpty() && dequeMinAccel.isNotEmpty()) {
            dequeMaxAccel.first() - dequeMinAccel.first()
        } else {
            0f
        }
        val rotSwing = maxOf(
            if (dequeMaxRot0.isNotEmpty() && dequeMinRot0.isNotEmpty()) dequeMaxRot0.first() - dequeMinRot0.first() else 0f,
            if (dequeMaxRot1.isNotEmpty() && dequeMinRot1.isNotEmpty()) dequeMaxRot1.first() - dequeMinRot1.first() else 0f
        )
        val noRecentStep = (System.currentTimeMillis() - lastStepTime) > motionWithoutStepTimeoutMs
        return noRecentStep && accelSwing > motionAccelThreshold && rotSwing > motionRotationThreshold
    }
}
