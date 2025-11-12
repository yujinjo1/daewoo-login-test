package com.fifth.maplocationlib.wifiengine

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.http.HttpEntity
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.DefaultHttpClient
import java.io.BufferedReader
import java.io.InputStreamReader

private const val SERVER_URL = "http://163.152.52.241:5000/rf/"

class RfApiClient {
    fun serverRequestFloor(data: String, route: String = "get_floor/"): Map<String, Any> {
        val httpClient: HttpClient = DefaultHttpClient()
        var httpPost = HttpPost(SERVER_URL)
        try {
            val stringEntity = StringEntity(data)
            stringEntity.setContentType("application/json")
            httpPost = HttpPost(SERVER_URL + route)
            httpPost.entity = stringEntity

            val response = httpClient.execute(httpPost)

            val entity: HttpEntity = response.entity


            // 응답 데이터 읽기
            val inputStream = entity.content
            val reader = BufferedReader(InputStreamReader(inputStream))
            val responseStringBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                responseStringBuilder.append(line)
            }
            responseStringBuilder.toString()

            val responseData = Json.decodeFromString<ResponseDataFloor>(responseStringBuilder.toString())


            return hashMapOf("status" to responseData.status, "floor" to responseData.floor)

        } catch (e: Exception) {
            Log.d("network", "request failed $e")
            e.printStackTrace()
            return hashMapOf("status" to 401, "floor" to "0")
        } finally {
            httpClient.connectionManager.shutdown()
        }
    }

    fun serverRequestRange(data: String, route: String = "get_range/"): Map<String, Any> {
        val httpClient: HttpClient = DefaultHttpClient()
        var httpPost = HttpPost(SERVER_URL)
        try {
            val stringEntity = StringEntity(data)
            stringEntity.setContentType("application/json")
            httpPost = HttpPost(SERVER_URL + route)
            httpPost.entity = stringEntity

            val response = httpClient.execute(httpPost)

            val entity: HttpEntity = response.entity

            // 응답 데이터 읽기
            val inputStream = entity.content
            val reader = BufferedReader(InputStreamReader(inputStream))
            val responseStringBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                responseStringBuilder.append(line)
            }

            val responseData = Json.decodeFromString<ResponseDataRange>(responseStringBuilder.toString())


            return hashMapOf("status" to responseData.status, "range" to responseData.range)

        } catch (e: Exception) {
            Log.d("network", "request failed $e")
            e.printStackTrace()
            return hashMapOf("status" to 401, "range" to arrayListOf(0.0,0.0,0.0,0.0))
        } finally {
            httpClient.connectionManager.shutdown()
        }
    }
}

@Serializable
data class ResponseDataFloor(
    val status: Int,
    @SerialName("floor")
    val floor: String
)
@Serializable
data class ResponseDataRange(
    val status: Int,
    @SerialName("range")
    val range: ArrayList<Double>
)

