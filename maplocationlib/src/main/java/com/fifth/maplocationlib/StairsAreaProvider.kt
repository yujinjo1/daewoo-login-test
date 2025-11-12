package com.fifth.maplocationlib.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

object StairsAreaProvider {
    private const val TAG = "StairsAreaProvider"

    /**
     * assets/stairs_area.json 파일을 로드하고 Map<Int, List<FloatArray>> 형태로 반환
     */
    fun load(context: Context, fileName: String = "stairs_area.json"): Map<Int, List<FloatArray>> {
        val jsonString = loadJSONFromAsset(context, fileName)
        return parseJSON(jsonString)
    }

    private fun loadJSONFromAsset(context: Context, fileName: String): String {
        Log.d(TAG, "Loading JSON file from assets: $fileName")
        val inputStream = context.assets.open(fileName)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val builder = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            builder.append(line)
        }

        reader.close()
        return builder.toString()
    }

    private fun parseJSON(jsonString: String): Map<Int, List<FloatArray>> {
        val result = mutableMapOf<Int, List<FloatArray>>()
        val jsonObject = JSONObject(jsonString)

        for (key in jsonObject.keys()) {
            val floor = key.toInt()
            val regionsArray = jsonObject.getJSONArray(key)
            val regionList = mutableListOf<FloatArray>()

            for (i in 0 until regionsArray.length()) {
                val coordsArray = regionsArray.getJSONArray(i)
                val coords = FloatArray(coordsArray.length()) {
                    coordsArray.getDouble(it).toFloat()
                }
                regionList.add(coords)
            }

            result[floor] = regionList
        }

        return result
    }
}
