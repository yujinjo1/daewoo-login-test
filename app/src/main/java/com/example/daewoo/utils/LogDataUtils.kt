package com.example.daewoo.utils

import android.util.Log
import com.example.daewoo.constants.ApiConstants
import com.example.daewoo.dtos.LocationDto
import com.example.daewoo.dtos.SensorDto
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/*
    250709 송형근
    위치, 센서 Log API 함수 파일
 */

// 센서 데이터 전송 함수
fun sendSensorData(
    client: OkHttpClient,
    sensorDto: SensorDto,
    accessToken: String,
    onUnauthorized: () -> Unit
) {
    val json = Gson().toJson(sensorDto)
    Log.d("LOG_DATA_UTILS", "센서 요청 바디: $json") // 요청 바디 로그
    val requestBody = json.toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("${ApiConstants.EXPRESS_BASE_URL}/sensors")
        .post(requestBody)
        .header("Authorization", "Bearer $accessToken")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("LOG_DATA_UTILS", "센서 전송 실패: ${e.message}")
        }
        override fun onResponse(call: Call, response: Response) {
            Log.d("LOG_DATA_UTILS", "센서 전송 응답 코드: ${response.code}")
            val responseBody = response.body?.string()
            if (responseBody != null) {
                Log.d("LOG_DATA_UTILS", "센서 전송 응답 바디: $responseBody")
            }
            if (response.code == 401) {
                onUnauthorized()
            }
        }
    })
}

// 위치 데이터 전송 함수
fun sendLocationData(
    client: OkHttpClient,
    locationDto: LocationDto,
    accessToken: String,
    onUnauthorized: () -> Unit
) {
    val json = Gson().toJson(locationDto)
    Log.d("LOG_DATA_UTILS", "위치 로그 요청 바디: $json") // 요청 바디 로그
    val requestBody = json.toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("${ApiConstants.EXPRESS_BASE_URL}/locations")
        .post(requestBody)
        .header("Authorization", "Bearer $accessToken")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("LOG_DATA_UTILS", "위치 로그 전송 실패: ${e.message}")
        }
        override fun onResponse(call: Call, response: Response) {
            Log.d("LOG_DATA_UTILS", "위치 로그 응답 코드: ${response.code}")
            val responseBody = response.body?.string()
            if (responseBody != null) {
                Log.d("LOG_DATA_UTILS", "위치 로그 응답 바디: $responseBody")
            }
            if (response.code == 401) {
                onUnauthorized()
            }
        }
    })
}

// CSV 파일 전송 함수
fun sendCsvData(
    client: OkHttpClient,
    csvFile: File,
    accessToken: String,
    onUnauthorized: () -> Unit
) {
    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            "file",
            csvFile.name,
            csvFile.asRequestBody("text/csv".toMediaType())
        )
        .build()
    Log.e("LOG_DATA_UTILS", "${csvFile.name}")

    val request = Request.Builder()
        .url("${ApiConstants.EXPRESS_BASE_URL}/csvs")
        .post(requestBody)
        .header("Authorization", "Bearer $accessToken")
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("LOG_DATA_UTILS", "CSV 전송 실패: ${e.message}")
        }

        override fun onResponse(call: Call, response: Response) {
            Log.d("LOG_DATA_UTILS", "CSV 전송 응답 코드: ${response.code}")
            val responseBody = response.body?.string()
            if (responseBody != null) {
                Log.d("LOG_DATA_UTILS", "CSV 전송 응답 바디: $responseBody")
            }
            if (response.code == 401) {
                onUnauthorized()
            }
        }
    })
}