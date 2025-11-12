package com.fifth.maplocationlib

import android.content.Context
import org.json.JSONObject

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class FloorChangeDetection() {
    private val appStartTime: Long = System.currentTimeMillis()

    // 기존 상태 변수 (계단 관련)
    var prevBaselinePressure: Float? = null
    var prevFloor: Int = 0
    var consecutiveSteps: Int = 0
    var stairSuspected: Boolean = false
    var stairConfirmed: Boolean = false
    var floorArrival: Boolean = false
    var baselinePressure: Float? = null
    var stairGyroValue: Float = 0.0f
    var arrivedStairGyroValue: Float = 0.0f

    // 엘리베이터 관련 추가 변수
    var arrivedElevatorGyroValue: Float = 0.0f

    var prevPressure: Float? = null
    var currentFloor: Int = 1
    var prevStairState: Int = 0
    var stairState: Int = 0
    var elevatorState: Int = 0
    var tempBaselinePressure: Float = 0.0f
    var lastFivePressures: MutableList<Float> = mutableListOf()

    // LPF 관련
    var filteredPressure: Float? = null
    val alpha: Float = 0.03f
    val pressure_history: ArrayDeque<Pair<Float, Float>> = ArrayDeque()

    var elevationStatus: String = "상승"

    // 엘리베이터 인식 알고리즘 관련 변수
    var elevator_slope_count: Int = 0
    var elevator_baseline_pressure: Float? = null
    var elevator_step_count: Int = 0
    var elevator_state5_time: Float? = null
    var prevFilteredPressure: Float? = null
    var prevTime: Float? = null

    private val scope = CoroutineScope(Dispatchers.Default)

    private var elevatorJson : Map<String, List<List<Int>>> = mapOf(
        "180" to listOf(listOf(362, 728)),
        "0" to listOf(listOf(383, 387))
    )

    // 자산(testbed/*.json)에서 로드되는 계단 좌표
    private var stairsJson: Map<String, Map<String, Map<String, List<List<Int>>>>> = emptyMap()

    // 보조 생성자: 생성 시점에 에셋에서 JSON 로드 가능
    constructor(context: Context, testbed:String) : this() {
        try {
            initFromAssets(context, testbed)
        } catch (t: Throwable) {
            Log.e("FloorChangeDetection", "Failed to init stairsJson from assets: ${t.message}")
        }
    }

    /**
     * 에셋에서 계단 JSON 로드
     * @param context Android Context (assets 접근용)
     * @param assetPath 예: "testbed/stairs_areas.json"
     */
    fun initFromAssets(context: Context, testbed:String) {
        var jsonText = context.assets.open("${testbed}/floorchange_stairs_areas.json").bufferedReader().use { it.readText() }
        stairsJson = parseStairsJson(jsonText)
        jsonText = context.assets.open("${testbed}/floorchange_elevator_areas.json").bufferedReader().use { it.readText() }
        elevatorJson = parseElevatorJson(jsonText)
        Log.d("FloorChangeDetection", "stairsJson loaded: floors=${stairsJson.keys}")
    }

    /**
     * 계단 JSON 파서 (org.json.JSONObject 사용)
     * JSON 스키마: { "층": { "상승|하강": { "각도": [[x,y], [x,y], ...] } } }
     */
    private fun parseStairsJson(json: String): Map<String, Map<String, Map<String, List<List<Int>>>>> {
        val root = JSONObject(json)
        val result = mutableMapOf<String, MutableMap<String, MutableMap<String, MutableList<List<Int>>>>>()

        val floorKeys = root.keys()
        while (floorKeys.hasNext()) {
            val floorKey = floorKeys.next()
            val elevationObj = root.getJSONObject(floorKey)
            val elevationMap = mutableMapOf<String, MutableMap<String, MutableList<List<Int>>>>()

            val elevKeys = elevationObj.keys()
            while (elevKeys.hasNext()) {
                val elevKey = elevKeys.next() // "상승" 또는 "하강"
                val angleObj = elevationObj.getJSONObject(elevKey)
                val angleMap = mutableMapOf<String, MutableList<List<Int>>>()

                val angleKeys = angleObj.keys()
                while (angleKeys.hasNext()) {
                    val angleKey = angleKeys.next() // "0", "90", ...
                    val coordsArray = angleObj.getJSONArray(angleKey)
                    val coordsList = mutableListOf<List<Int>>()
                    for (i in 0 until coordsArray.length()) {
                        val arr = coordsArray.getJSONArray(i)
                        if (arr.length() >= 2) {
                            coordsList.add(listOf(arr.getInt(0), arr.getInt(1)))
                        }
                    }
                    angleMap[angleKey] = coordsList
                }
                elevationMap[elevKey] = angleMap
            }
            result[floorKey] = elevationMap
        }

        // 불변 Map으로 변환
        return result.mapValues { (_, elev) ->
            elev.mapValues { (_, ang) -> ang.mapValues { (_, list) -> list.toList() } }
        }
    }

    /**
     * gyro가 허용 범위(0, 90, 180, 270 ±15°) 내에 있는지 판단하는 함수
     */
    fun isAllowed(angle: Float): Pair<Boolean, Float?> {
        val allowedAngles = listOf(0f, 90f, 180f, 270f)
        val tol = 30f
        for (a in allowedAngles) {
            val diff = abs(((angle - a + 180) % 360) - 180)
            if (diff <= tol) {
                return Pair(true, a)
            }
        }
        return Pair(false, null)
    }

    /**
     * 기압 센서 업데이트: newPressure, currentTime(초), gyroAngle을 인자로 받음.
     * 여기서 엘리베이터 인식 알고리즘도 함께 수행됨.
     */
    fun updatePressureData(newPressure: Float, currentTime: Float, gyroAngle: Float) {
        if (filteredPressure == null) {
            filteredPressure = newPressure
            prevFilteredPressure = newPressure
            prevTime = currentTime
        } else {
            filteredPressure = filteredPressure!! + alpha * (newPressure - filteredPressure!!)
        }
        pressure_history.add(Pair(currentTime, filteredPressure!!))


        var ref: Pair<Float, Float>? = null

        for (pair in pressure_history) {
            if (pair.first <= currentTime - 0.3f) {
                ref = pair
            } else {
                break
            }
        }
        if (ref != null) {
            val timeDiff = currentTime - ref.first
            val derivative = abs(filteredPressure!! - ref.second) / timeDiff
            Log.d("derivative", "${derivative}")

            if (derivative >= 0.05f) {
                if (elevator_slope_count == 0) {
                    elevator_baseline_pressure = filteredPressure
                    // 사용자가 바라보는 방향으로부터 [0, 180] 중 가까운 각도 계산
                    val allowedAngles = listOf(0f, 180f)
                    var diffMin = Float.MAX_VALUE
                    var nearestAngle = allowedAngles[0]
                    for (a in allowedAngles) {
                        val diff = abs(((gyroAngle - a + 180) % 360) - 180)
                        if (diff < diffMin) {
                            diffMin = diff
                            nearestAngle = a
                        }
                    }
                    arrivedElevatorGyroValue = nearestAngle
                }
                elevator_slope_count++
                if (elevator_slope_count >= 50) {
                    elevatorState = 1  // 엘리베이터 확신
                }
                Log.d("ElevatorDebug", "$currentTime / $elevator_slope_count / ElevatorState: $elevatorState")
            } else {
                if (elevator_slope_count > 0) {
                    if (elevatorState == 1) {
                        elevatorState = 2  // 엘리베이터 도착 (내리지 않음)
                        Log.d(
                            "ElevatorDebug",
                            "ElevatorState changed to 2 (도착) / ${abs(filteredPressure!! - elevator_baseline_pressure!!)} / " +
                                    "${abs(filteredPressure!! - elevator_baseline_pressure!!)/0.3f} / " +
                                    "${round(abs(filteredPressure!! - elevator_baseline_pressure!!)/0.3f)}"
                        )
                        Log.d("ElevatorDebug", "arrivedElevatorGyro : $arrivedElevatorGyroValue")
                        var floor_change = round(abs(filteredPressure!! - elevator_baseline_pressure!!) / 0.3f).toInt()
                        Log.d("ElevatorDebug", "$floor_change, ${abs(filteredPressure!! - elevator_baseline_pressure!!)/0.3f}")
                        if (floor_change > 0) {
                            if (filteredPressure!! < elevator_baseline_pressure!!) {
                                if (currentFloor == -1) {
                                    floor_change += 1
                                }
                                currentFloor += floor_change
                            } else {
                                currentFloor = max(currentFloor - floor_change, -1)
                                if (currentFloor == 0) {
                                    currentFloor = -1
                                }
                            }
                            Log.d("ElevatorDebug", "currentFloor: $currentFloor")
                        }
                    }
                }
                elevator_slope_count = 0
            }
        }

        // pressure_history에서 1초보다 오래된 데이터 제거
        while (pressure_history.isNotEmpty() && pressure_history.first().first < currentTime - 1.0f) {
            pressure_history.removeFirst()
        }

        // lastFivePressures 업데이트
        lastFivePressures.add(filteredPressure!!)
        if (lastFivePressures.size > 5) {
            lastFivePressures.removeAt(0)
        }
    }

    /**
     * 걸음 인식 시 자이로 업데이트: 계단/엘리베이터 인식 알고리즘 실행.
     */
    fun updateGyroData(gyroAngle: Float) {
        scope.launch {
            processStep(gyroAngle)
        }
    }

    /**
     * 계단 및 엘리베이터 인식 알고리즘의 핵심 로직.
     */
    fun processStep(gyroAngle: Float) {
        // 앱 시작 이후 경과된 시간을 초 단위로 계산
        val currentTime = (System.currentTimeMillis() - appStartTime) / 1000f
        val (allowed, nearestAngle) = isAllowed(gyroAngle)

        // 엘리베이터 상태 처리
        if (elevatorState != 0) {
            prevBaselinePressure = null
            if (elevatorState == 2) {
                elevator_step_count++
                if (elevator_step_count >= 5) {
                    elevatorState = 3  // 엘리베이터 내림
                    elevator_step_count = 0
                }
            } else if (elevatorState == 3) {
                elevatorState = 0
            }
            return
        }

        // 계단 인식 알고리즘 (Stair Detection)
        val currentPressure = filteredPressure ?: return

        lastFivePressures.add(currentPressure)
        if (lastFivePressures.size > 5) {
            lastFivePressures.removeAt(0)
        }

        var allowedLocal = allowed
        if (!allowedLocal) {
            if (prevStairState == 2) {
                allowedLocal = true
            }
        }
        if (allowedLocal) {
            consecutiveSteps++
        } else {
            prevFloor = currentFloor
            stairState = 0
            if (prevStairState == 1) {
                prevBaselinePressure = baselinePressure
            }
            resetDetection()
        }
        Log.d("StairDebug", "consecutiveSteps: $consecutiveSteps / stairState: $stairState")

        if (consecutiveSteps >= 5 && !stairSuspected) {
            stairSuspected = true
            if (prevBaselinePressure != null && abs(prevBaselinePressure!! - currentPressure) >= 0.10f) {
                if ((prevFloor == 1 && (prevBaselinePressure!! < currentPressure)) ||
                    (prevFloor == -1 && (prevBaselinePressure!! > currentPressure))
                ) {
                    baselinePressure = prevBaselinePressure
                } else {
                    baselinePressure = prevBaselinePressure
                }
            } else {
                baselinePressure = if (lastFivePressures.isNotEmpty()) lastFivePressures.first() else currentPressure
            }
            stairGyroValue = nearestAngle ?: gyroAngle
        } else {
            stairState = 0
        }

        if (stairSuspected && !stairConfirmed && baselinePressure != null) {
            stairState = 1
            var stair_confirmed_threshold = 0.18f
            if ((prevFloor == 1 && baselinePressure!! < currentPressure) ||
                (prevFloor == -1 && baselinePressure!! > currentPressure)
            ) {
                stair_confirmed_threshold = 0.14f
            }
            if (abs(currentPressure - baselinePressure!!) > stair_confirmed_threshold) {
                stairConfirmed = true
                elevationStatus = if (currentPressure > baselinePressure!!) "하강" else "상승"
                arrivedStairGyroValue = stairGyroValue
            }
        }
        var currentDiff: Float? = null
        if (stairConfirmed && !floorArrival) {
            var arrival = false
            stairState = 2
            if (prevPressure != null) {
                currentDiff = currentPressure - prevPressure!!
                var arrival_threshold_1 = 0.3f
                var arrival_threshold_2 = 0.01f
                if ((prevFloor == 1 && baselinePressure!! < currentPressure) ||
                    (prevFloor == -1 && baselinePressure!! > currentPressure)
                ) {
                    arrival_threshold_1 = 0.3f
                    arrival_threshold_2 = 0.001f
                }
                if (baselinePressure != null &&
                    abs(currentPressure - baselinePressure!!) > arrival_threshold_1 &&
                    (currentDiff * (prevPressure!! - baselinePressure!!) < 0 || abs(currentDiff) < arrival_threshold_2)
                ) {
                    arrival = true
                }
            }
            val diffGyro = abs(((gyroAngle - stairGyroValue + 180) % 360) - 180)
            if (diffGyro > 15f) {
                if ((prevFloor == 1 && baselinePressure!! < currentPressure) ||
                    (prevFloor == -1 && baselinePressure!! > currentPressure)
                ) {
                    if (abs(currentPressure - baselinePressure!!) > 0.3f) {
                        arrival = true
                    }
                    else {
                        arrival = false
                    }
                } else {
                    arrival = true
                }
            }
            if (arrival) {
                stairState = 3
                arrivedStairGyroValue = stairGyroValue
                floorArrival = true
                if (baselinePressure != null && currentPressure > baselinePressure!!) {
                    if (currentFloor == 1) {
                        currentFloor = -1
                    } else {
                        currentFloor = max(currentFloor - 1, -2)
                    }
                    prevBaselinePressure = null
                } else {
                    if (currentFloor == -1) {
                        currentFloor = 1
                    } else {
                        currentFloor = min(currentFloor + 1, 34)
                    }
                    prevBaselinePressure = null
                }
                resetDetection()
            }
        }
        prevPressure = currentPressure
        prevStairState = stairState

        return
    }

    fun setStairsInfo(
        currentPos: Array<Float>,
        currentFloor: Int,
        arrivedStairGyroValue: Float,
        elevation: String
    ): Array<Float>? {
        if (stairsJson.isEmpty()) return null
        // 4층 이상일 때는 "basic"을 사용, 그 외에는 해당 층수를 사용
        val floorKey = if (currentFloor >= 4) "basic" else currentFloor.toString()
        // 자이로 각도를 정수형 문자열 키로 변환 (예: 90 -> "90")
        val angleKey = arrivedStairGyroValue.toInt().toString()

        // JSON 데이터에서 해당 층의 전달받은 elevation의 좌표 리스트를 가져옴
        val coordsList = stairsJson[floorKey]?.get(elevation)?.get(angleKey)

        // 좌표 리스트가 없으면 null 반환
        if (coordsList.isNullOrEmpty()) {
            return null
        }

        // JSON 좌표 데이터를 Pair<Int, Int>로 변환
        val matchingCoords = coordsList.mapNotNull { coord ->
            if (coord.size >= 2) Pair(coord[0], coord[1]) else null
        }
        // 2개 이상이면 현재 위치와의 거리가 가장 짧은 좌표 선택 (존재 여부 확인용)
        // 2개 이상이면 현재 위치와의 거리가 가장 짧은 좌표 선택
        var minDistance = Float.MAX_VALUE
        var closestCoord: Pair<Int, Int>? = null

        for (coord in matchingCoords) {
            val dist = distance(arrayOf(currentPos[0], currentPos[1]), coord)
            Log.d("StairDebug", "Checking coord (${coord.first}, ${coord.second}) with distance: $dist")
            if (dist < minDistance) {
                minDistance = dist
                closestCoord = coord
            }
        }

        val candidate = closestCoord ?: return null


        // 추가 기능: 현재 위치를 기준으로 arrivedStairGyroValue의 반대 방향(180° 회전)으로 1.5미터 떨어진 좌표 계산
        // 좌표 1 단위는 0.1미터이므로, 1.5미터는 15 단위임.
        val adjustedAngle = (arrivedStairGyroValue + 180) % 360
        val rad = Math.toRadians(adjustedAngle.toDouble())
        val stepLength = 5  // 미터 단위
        val offset = stepLength * 10  // 좌표 단위 (0.1m 당 1 단위)
        val newX = candidate.first - (Math.sin(rad) * offset).toFloat()
        val newY = candidate.second + (Math.cos(rad) * offset).toFloat()

        return arrayOf(newX, newY)
    }

    // 두 좌표 간의 유클리드 거리 계산 함수
    private fun distance(p1: Array<Float>, p2: Pair<Int, Int>): Float {
        val dx = p1[0] - p2.first.toFloat()
        val dy = p1[1] - p2.second.toFloat()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * 상태 리셋 함수: 계단 관련 상태 변수 초기화
     */
    fun resetDetection() {
        consecutiveSteps = 0
        stairSuspected = false
        stairConfirmed = false
        floorArrival = false
        baselinePressure = null
    }

    /**
     * 현재 상태 문자열 반환 (계단 및 엘리베이터)
     */
    fun getCurrentStairsState(): String {
        return when (stairState){
            3 -> "층 도착"
            2 -> "계단 확정"
            1 -> "계단 의심"
            else -> "계단 아님"
        }
    }

    fun getCurrentElevatorState(): String {
        return when (elevatorState){
            1 -> "엘리베이터 확신"
            2 -> "엘리베이터 도착 (내리지 않음)"
            3 -> "엘리베이터 내림"
            else -> "엘리베이터 아님"
        }
    }

    /**
     * 층 올리기
     */
    fun upperFloor() {
        if (currentFloor == -1) {
            currentFloor = 1
        } else {
            currentFloor = min(currentFloor + 1, 34)
        }
    }

    /**
     * 층 내리기
     */
    fun lowerFloor() {
        if (currentFloor == 1) {
            currentFloor = -1
        } else {
            currentFloor = max(currentFloor - 1, -2)
        }
    }
}



/**
 *
 * 주머니속 필터링을 위한 클래스
 *
 */
private fun parseElevatorJson(json: String): Map<String, List<List<Int>>> {
    val root = JSONObject(json)
    val result = mutableMapOf<String, MutableList<List<Int>>>()

    val angleKeys = root.keys()
    while (angleKeys.hasNext()) {
        val angleKey = angleKeys.next() // "0", "180" 등
        val coordsArray = root.getJSONArray(angleKey)
        val coordsList = mutableListOf<List<Int>>()
        for (i in 0 until coordsArray.length()) {
            val arr = coordsArray.getJSONArray(i)
            if (arr.length() >= 2) {
                coordsList.add(listOf(arr.getInt(0), arr.getInt(1)))
            }
        }
        result[angleKey] = coordsList
    }

    return result.mapValues { (_, list) -> list.toList() }
}