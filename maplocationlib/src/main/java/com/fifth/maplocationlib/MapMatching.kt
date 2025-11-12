package com.fifth.maplocationlib

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 맵 매칭 기능을 제공하는 클래스
 * 정의된 영역 내의 좌표를 보정하여 정확한 실내 위치를 제공
 */
class MapMatching(private val context: Context, val testbed: String) {
    private val TAG = "MapMatching"
    private var isInitialized = false
    private var correctionAreas: MutableMap<Int, List<CorrectionArea>> = mutableMapOf()

    /**
     * MapMatching 클래스 초기화
     * @param jsonFileName 보정 영역 정보가 담긴 JSON 파일명 (assets 폴더 내의 파일)
     * @return 초기화 성공 여부
     */
    fun initialize(jsonFileName: String = if (testbed.isNotBlank()) "$testbed/map_correction_areas.json" else "map_correction_areas.json"): Boolean {

        return try {
            // JSON 파일 로드
            val jsonString = loadJSONFromAsset(jsonFileName)
            parseJSON(jsonString)
            isInitialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "MapMatching initialization failed", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * 현재 위치에 맵 매칭 알고리즘 적용
     * @param x X 좌표
     * @param y Y 좌표
     * @param floor 층 정보
     * @return 보정된 위치 정보 (x, y, floor 포함)
     */
    fun getMapMatchingResult(x: Float, y: Float, floor: Int): LocationResult {

        if (!isInitialized) {
            Log.w(TAG, "MapMatching not initialized, returning original location")
            return LocationResult(x, y, floor)
        }

        // 현재 층에 대한 보정 영역 가져오기
        val areasForFloor = correctionAreas[floor]
        if (areasForFloor == null) {
            Log.d(TAG, "No correction areas found for floor $floor")
            return LocationResult(x, y, floor)
        }

        // 현재 위치가 포함된 보정 영역 찾기
        val matchedArea = areasForFloor.find { area ->
            val isInArea = x >= area.minX.toFloat() && x <= area.maxX.toFloat() &&
                    y >= area.minY.toFloat() && y <= area.maxY.toFloat()
            if (isInArea) {
                Log.d(TAG, "Location is within area: ${area.id}")
            }
            isInArea
        }

        if (matchedArea == null) {
            Log.d(TAG, "Location is not within any correction area")
            return LocationResult(x, y, floor)
        }

        // 보정 유형에 따라 좌표 값 보정
        val result = when (matchedArea.correctionType) {
            "x" -> {
                val correctedX = matchedArea.correctionValue.toFloat()
                Log.d(TAG, "Applying X correction: $x -> $correctedX")
                LocationResult(correctedX, y, floor)
            }
            "y" -> {
                val correctedY = matchedArea.correctionValue.toFloat()
                Log.d(TAG, "Applying Y correction: $y -> $correctedY")
                LocationResult(x, correctedY, floor)
            }
            else -> {
                Log.w(TAG, "Unknown correction type: ${matchedArea.correctionType}")
                LocationResult(x, y, floor)
            }
        }

        Log.d(TAG, "Final location: x=${result.x}, y=${result.y}, floor=${result.floor}")
        return result
    }

    /**
     * assets 폴더에서 JSON 파일 로드
     */
    private fun loadJSONFromAsset(fileName: String): String {
        Log.d(TAG, "Loading JSON file from assets: $fileName")
        val inputStream = context.assets.open(fileName)
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()
        var line: String?

        while (bufferedReader.readLine().also { line = it } != null) {
            stringBuilder.append(line)
        }

        bufferedReader.close()
        return stringBuilder.toString()
    }

    /**
     * JSON 문자열 파싱하여 보정 영역 정보 추출
     */
    private fun parseJSON(jsonString: String) {
        val jsonObject = JSONObject(jsonString)
        val correctionAreasArray = jsonObject.getJSONArray("correctionAreas")

        Log.d(TAG, "Parsing JSON with ${correctionAreasArray.length()} floor entries")
        correctionAreas.clear()

        // 각 층별 보정 영역 정보 파싱
        for (i in 0 until correctionAreasArray.length()) {
            val floorObj = correctionAreasArray.getJSONObject(i)
            val floor = floorObj.getInt("floor")
            val areasArray = floorObj.getJSONArray("areas")
            val areasList = mutableListOf<CorrectionArea>()

            Log.d(TAG, "Floor $floor has ${areasArray.length()} correction areas")

            // 해당 층의 각 보정 영역 정보 파싱
            for (j in 0 until areasArray.length()) {
                val areaObj = areasArray.getJSONObject(j)
                val area = CorrectionArea(
                    id = areaObj.getString("id"),
                    minX = areaObj.getDouble("minX"),
                    maxX = areaObj.getDouble("maxX"),
                    minY = areaObj.getDouble("minY"),
                    maxY = areaObj.getDouble("maxY"),
                    correctionType = areaObj.getString("correctionType"),
                    correctionValue = areaObj.getDouble("correctionValue")
                )
                areasList.add(area)
                Log.d(TAG, "Added area: ${area.id}")
            }

            correctionAreas[floor] = areasList
            Log.d(TAG, "Total ${areasList.size} areas added for floor $floor")
        }
    }

    /**
     * 보정 영역 정보를 담는 데이터 클래스
     */
    data class CorrectionArea(
        val id: String,
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
        val correctionType: String,  // "x" 또는 "y"
        val correctionValue: Double
    )

    /**
     * 위치 정보 결과를 담는 데이터 클래스
     */
    data class LocationResult(
        val x: Float,
        val y: Float,
        val floor: Int
    )
}