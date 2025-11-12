package com.example.daewoo.utils

import android.util.Log
import com.example.daewoo.constants.ApiConstants.SPRING_BASE_URL
import com.example.daewoo.dtos.FCMTokenDto
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/*
    250711 송형근
    FCM 토큰 API 함수 파일
 */

fun saveFCMToken(
    client: OkHttpClient,
    fcmTokenDto: FCMTokenDto,
    accessToken: String,
    onUnauthorized: () -> Unit
) {
    val jsonBody = JSONObject().apply {
        put("token", fcmTokenDto.token)
        put("platform", fcmTokenDto.platform)
    }
    val body = jsonBody.toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("$SPRING_BASE_URL/fcm-token")
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer $accessToken")
        .post(body)
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.e("FCM_TOKEN_UTILS", "FCM Token 저장 실패: ${e.message}")
        }
        override fun onResponse(call: Call, response: Response) {
            val responseBody = response.body?.string()
            Log.d("FCM_TOKEN_UTILS", "FCM Token 저장 응답 바디: $responseBody")
            response.close()
        }
    })
}