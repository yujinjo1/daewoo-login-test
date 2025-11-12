package com.fifth.maplocationlib.inpocketPDR

import android.util.Log
import com.fifth.maplocationlib.sensors.MovingAverage
import java.util.LinkedList
import com.fifth.maplocationlib.FloorChangeDetection
import kotlin.math.abs

class HandHeldSwing(private val floorChangeDetection: FloorChangeDetection) {

    // --- 필요한 매니저들 (실제 구현체는 별도) ---

    private val rotMovingAverageY = MovingAverage(10)
    private val adaptiveFilterYaw = AdaptiveFilter(alphaSlow = 0.1f, alphaFast = 0.8f, threshold = 20f)
    private var filteredYaw = 0f
    private var devicePosture = 0
    private var userAttitude = 0

    // --- 스텝 검출을 위한 파라미터 ---
    private var totalStepCount = 0
    private var lastStepTime: Long = 0       // 최근 스텝 검출 시각 (ms)
    private val minStepIntervalMs = 300     // 최소 스텝 간격 (0.25초)

    // --- PDR 관련 ---
    private var movementMode = 0
    private var stepLength = 0.0
    private var userDirection = 0.0

    // --- 피크 검출 보조 ---
    private var previusrotY = 0.0
    private var lastrisepeek = 0.0
    private var lastfallpeek = 0.0
    private var isRising: Int = 0
    private  var lastrising = 0 // 20250307추가

    // --- 계단 카운팅 ---
    private var staircount = 0
    private var laststaircount = 0
    private var suspendStepcount = 0 // 20250307추가

    // --- head info ---
    private var headangle = 0f
    /**
     * 스텝 판단 함수 (센서 입력 받을 때마다 호출)
     * @param rotangle  회전 각도
     * @param stepQueue steplength 큐
     * @param currentTimeMillis  현재 시간 (ms)
     */
    fun isStep(
        rotangle: FloatArray,
        stepQueue: LinkedList<Float>,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): Boolean {
        require(rotangle.size >= 3)
        rotMovingAverageY.newData(rotangle[1])
        filteredYaw = adaptiveFilterYaw.update(rotangle[2])
        stepLength = stepQueue.peek().toDouble()
        val currentroty = rotMovingAverageY.getAvg()

        var stepDetected = false

        val timeSinceLastStep = currentTimeMillis - lastStepTime

        // 상승/하강 판정
        val wasRising = isRising
        if(currentroty > previusrotY+0.1){//가만히 서있을때 스템수가 늘어나는걸 방지하는 0.1
            isRising = 1//걷는중
        }else if(currentroty < previusrotY-0.1){
            isRising = -1//걷는중
        }

        // isRising 값이 변할 때마다 스텝 검출 (즉, 피크 및 바닥을 모두 인식)
        if ( wasRising != isRising ) {
            if (timeSinceLastStep > minStepIntervalMs) {
//                Log.d("tttestiii","$currentrotx  $currentrotx  ${isRising}   $staircount")


                when (isRising) {
                    1 -> {
                        stepDetected = true
                        totalStepCount++
                        lastStepTime = currentTimeMillis
                        headangle = rotangle[2]
                        movementMode = 0
                        lastrisepeek = currentroty

                    }
                    -1 -> {
                        stepDetected = true
                        totalStepCount++
                        lastStepTime = currentTimeMillis
                        movementMode = 0
                        lastrisepeek = currentroty

                    }
                }
            }
        }
        // 여기까지
        laststaircount=staircount
        previusrotY = currentroty

        return stepDetected
    }

    /**
     * 휴대폰이 앞으로 찌를 때의 head rotangle[2]
     */
    fun getHead(): Float{
        return headangle
    }

    fun getStatus(): PDR {
        return PDR(
            devicePosture = devicePosture,
            movementMode = movementMode, //5 계단 올라가는중, 내려가는중 6계단 올라가는거 끝남 7내려가는거끝남
            userAttitude = userAttitude,
            stepLength = stepLength,
            direction = (-filteredYaw+360)%360.toDouble(),
            totalStepCount = totalStepCount,
            directionReliability = 0
        )

    }

    private class AdaptiveFilter(
        private val alphaSlow: Float,  // 느린 평활 계수 (노이즈 제거 우선)
        private val alphaFast: Float,  // 빠른 평활 계수 (회전 변화 빠르게 반영)
        private val threshold: Float   // 회전 각도의 변화 임계치
    ) {
        private var filteredValue: Float? = null

        fun update(newValue: Float): Float {
            if (filteredValue == null) {
                filteredValue = newValue
            } else {
                val diff = abs(newValue - filteredValue!!)
                val alpha = if (diff > threshold) alphaFast else alphaSlow
                filteredValue = alpha * newValue + (1 - alpha) * filteredValue!!
            }
            return filteredValue!!
        }
    }
}
