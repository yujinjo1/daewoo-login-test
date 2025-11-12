package com.fifth.maplocationlib.inpocketPDR

import android.util.Log
import com.fifth.maplocationlib.sensors.MovingAverage
import java.util.LinkedList
import kotlin.math.abs
import com.fifth.maplocationlib.FloorChangeDetection

class InPocketStep(private val floorChangeDetection: FloorChangeDetection) {

    // --- 필요한 매니저들 (실제 구현체는 별도) ---

    private val rotMovingAveragePitch = MovingAverage(10)
    private val adaptiveFilterYaw = AdaptiveFilter(alphaSlow = 0.1f, alphaFast = 0.8f, threshold = 10f)
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
    private var left_D = 0.0f
    private var right_D = 0.0f

    // --- 피크 검출 보조 ---
    private var previusrotX = 0.0
    private var lastrisepeek = 0.0
    private var lastfallpeek = 0.0
    private var isRising: Int = 0
    private  var lastrising = 0 // 20250307추가

    // --- 계단 카운팅 ---
    private var staircount = 0
    private var laststaircount = 0
    private var suspendStepcount = 0 // 20250307추가

    // --- 도메인 파라미터 --- // 20250307 추가
    private var lastrisepeek_thresshold = -88.0 //한산도 -90 신공학 -80
    private var peek_diff_thresshold = 35 //한산도 신공학 둘다 35
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
        rotMovingAveragePitch.newData(rotangle[0])
        filteredYaw = adaptiveFilterYaw.update(rotangle[2])
        stepLength = stepQueue.peek().toDouble() *1.02
        val currentrotx = rotMovingAveragePitch.getAvg()

        var stepDetected = false

        val timeSinceLastStep = currentTimeMillis - lastStepTime

        // 상승/하강 판정
        val wasRising = isRising
        if(currentrotx > previusrotX+0.1){//가만히 서있을때 스템수가 늘어나는걸 방지하는 0.1
            isRising = 1//걷는중
            // 20250307 추가 아래 조건문들 상수 입력을 변수로 바꿈
            if(lastrisepeek < lastrisepeek_thresshold){
                isRising = 3//내려가는중
                if(abs(lastrisepeek-lastfallpeek)>peek_diff_thresshold){
                    isRising = 2//올라가는중
                }
            }

        }else if(currentrotx < previusrotX-0.1){
            isRising = -1//걷는중
            if(lastrisepeek < lastrisepeek_thresshold){
                isRising = -3//내려가는중
                if(abs(lastrisepeek-lastfallpeek)>peek_diff_thresshold){
                    isRising = -2//올라가는중
                }
            }
        }


        // isRising 값이 변할 때마다 스텝 검출 (즉, 피크 및 바닥을 모두 인식)
        if ( wasRising != isRising ) {
            if (timeSinceLastStep > minStepIntervalMs) {
//                Log.d("tttestiii","$currentrotx  $currentrotx  ${isRising}   $staircount")

                //Log.d("tttestii","${abs(lastrisepeek-lastfallpeek)}   ${lastrisepeek}    ${lastfallpeek}")
                when (isRising) {
                    1 -> {
                        stepDetected = true
                        totalStepCount++
                        lastStepTime = currentTimeMillis
                        // 20250307 아래 문장 추가
                        if (lastrising != -1){
                            suspendStepcount = totalStepCount
                        }

                        if (totalStepCount - suspendStepcount > 8){
                            staircount = 0
                            suspendStepcount = totalStepCount
                        }
                        lastrising = isRising
                        //여기까지
                        movementMode = 0
                        lastfallpeek = currentrotx

                    }
                    -1 -> {
                        stepDetected = true
                        totalStepCount++
                        lastStepTime = currentTimeMillis
                        // 20250307 아래문장 추가
                        if (lastrising != 1){
                            suspendStepcount = totalStepCount
                        }

                        if (totalStepCount - suspendStepcount > 8){
                            staircount = 0
                            suspendStepcount = totalStepCount
                        }
                        lastrising = isRising
                        //여기까지
                        movementMode = 0
                        lastrisepeek = currentrotx

                    }
                    2 -> {
                        stepDetected = true
                        totalStepCount++
                        lastStepTime = currentTimeMillis
                        movementMode = 5
                        staircount++
                        lastfallpeek = currentrotx
                        // 20250307 두줄 추가
                        suspendStepcount = totalStepCount
                        lastrising = isRising
                    }
                    -2 -> {
                        stepDetected = true
                        totalStepCount++
                        lastStepTime = currentTimeMillis

                        movementMode = 5
                        staircount++
                        lastrisepeek = currentrotx
                        // 20250307 두줄 추가
                        suspendStepcount = totalStepCount
                        lastrising = isRising
                    }

                    3 -> {
                        stepDetected = true
                        totalStepCount++
                        lastStepTime = currentTimeMillis

                        lastfallpeek = currentrotx
                        movementMode = 5
                        staircount--
                        // 20250307 두줄 추가
                        suspendStepcount = totalStepCount
                        lastrising = isRising
                    }
                    -3 ->{
                        stepDetected = true
                        totalStepCount++
                        lastStepTime = currentTimeMillis
                        lastrisepeek = currentrotx

                        movementMode = 5
                        staircount--
                        // 20250307 두줄 추가
                        suspendStepcount = totalStepCount
                        lastrising = isRising
                    }
                }
//                Log.d("ttteststep", "staircount: ${staircount}, laststaircount: ${laststaircount}\n" +
//                        ", totalStepCount - suspendStepcount: ${totalStepCount - suspendStepcount}\n" +
//                        "${suspendStepcount}")
            }
        }
        // 20250307 여기서 부터 아래 여기까지 복사후 붙여넣기로 기존 내용 대체
        if((staircount-laststaircount)>20){
            //층 내려주면됨 lower
            movementMode=7
            floorChangeDetection.lowerFloor()
        }
        if((staircount-laststaircount)<-20){
            //층 올려주면됨 upper
            movementMode=6
            floorChangeDetection.upperFloor()
        }
        // 여기까지
        laststaircount=staircount
        previusrotX = currentrotx

        return stepDetected
    }

    fun getStatus(): PDR {
        if(movementMode !=5) {
            return PDR(
                devicePosture = devicePosture,
                movementMode = movementMode, //5 계단 올라가는중, 내려가는중 6계단 올라가는거 끝남 7내려가는거끝남
                userAttitude = userAttitude,
                stepLength = stepLength,
                direction = (-filteredYaw+360)%360.toDouble(),
                totalStepCount = totalStepCount,
                directionReliability = 0
            )
        }else{
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
