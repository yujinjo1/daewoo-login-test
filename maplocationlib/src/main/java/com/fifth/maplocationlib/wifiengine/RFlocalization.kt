package com.fifth.maplocationlib.wifiengine

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val RSSITHRES = -75

/**
 * RFLocalization 클래스는 Wi‑Fi 스캔 데이터를 기반으로 서버에 층(floor) 검출 요청을 보내는 기능만 포함합니다.
 */
class RFlocalization(private val context: Context) {
    private var firstScan: Boolean = false
    private val testbed = "inb"
    private val wifiScanResults: MutableList<ScanResult> = mutableListOf()
    lateinit var wifiManager: WifiManager
    var wifipermitted = true
    var wifiThread = WifiThread(context)
    private var rfApiClient = RfApiClient()
    var count = 0
    private var wifiScanData: MutableMap<String, Int> = mutableMapOf("initData" to 0)
    private var prewifiScanData: MutableMap<String, Int> = mutableMapOf("initPreData" to 0)
    private var isFresh = false
    private var model = ""
    var isGetFloor = false
    var isGetRange = false
    private var statusFloor = 0
    private var floor = "0"




    init {
        rfModuleInit()

    }
    fun rfModuleInit() {
        model = Build.MODEL.toString()

        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        firstScan = true

        wifiThreadStart()

    }


    private fun getWifiInfo(context: Context){
        if (wifipermitted) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            // Wi-Fi 스캔 결과 초기화
            wifiScanResults.clear()

            // Wi-Fi 스캔 시작
            wifiManager.startScan()

            val scanResultList: List<ScanResult> = wifiManager.scanResults.filter { it.level >= RSSITHRES }

            val wifiDataMap = scanResultList.associate {
                it.BSSID to it.level
            }
            wifiScanData = wifiDataMap.toMutableMap()
//            Log.d("RFlocalization", "Wi-Fi scan results: $wifiScanData")

//            jsonData = Json.encodeToString(wifiData)
//            val jsonData1 = Json { encodeDefaults = false }.encodeToString(wifiData)
//            Log.d("wifi", jsonData.toString())
            //Log.d("tttest_scaned_wifi", wifiScanData.toString())

            if (wifiScanData != prewifiScanData){
                prewifiScanData = wifiScanData

                //dir off
                isFresh = true
            }
        }
    }

    inner class WifiThread(private val context: Context) : Thread() {
        override fun run() {
            while (!isInterrupted) {
                try {
                    sleep(1000)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if(wifipermitted) {
                    getWifiInfo(context)
                    sendFloorData()
                }
            }
        }
    }

    private fun wifiThreadStart() {
        wifiThread.isDaemon = true
        wifiThread.start()
    }


    // Wi‑Fi 스캔 결과 업데이트 수신
    val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("tttest", "receive")
            wifipermitted = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
        }
    }

    init {
        // Wi‑Fi 매니저 초기화
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        startWifiScan()
        Log.d("tttest", "startttt")
    }

    /**
     * Wi‑Fi 스캔을 시작하여 스캔 데이터를 wifiScanData에 저장합니다.
     */
    private fun startWifiScan() {
        if (wifipermitted) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e("RFlocalization", "Location permission not granted")
                return
            }
            Log.d("tttest", "startscan")
            wifiManager.startScan()
            // RSSI 값이 RSSITHRES 이상인 스캔 결과만 필터링
            val scanResults: List<ScanResult> = wifiManager.scanResults.filter { it.level >= RSSITHRES }
            wifiScanData = scanResults.associate { it.BSSID to it.level }.toMutableMap()
        }
    }

    /**
     * Wi‑Fi 스캔 데이터를 서버에 전송하여 층(floor) 검출 요청을 수행합니다.
     */
    fun sendFloorData() {
        runBlocking {
            launch(Dispatchers.IO) {
                try {
                    // 스캔 데이터 JSON 인코딩
                    val jsonData = Json.encodeToString(wifiScanData)
                    val data = Json.encodeToString(
                        mapOf("wifi_data" to jsonData, "testbed" to testbed, "model" to model)
                    )
                    // 서버의 "get_floor/" 엔드포인트로 요청 전송
                    val responseData = rfApiClient.serverRequestFloor(data, "get_floor/")
                    statusFloor = responseData["status"] as Int
                    floor = responseData["floor"] as String
                    isGetFloor = true
                    Log.d("RFlocalization", "Floor detected: $floor, status: $statusFloor")
                } catch (e: Exception) {
                    Log.e("RFlocalization", "sendFloorData failed: $e")
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 서버로부터 받은 층 정보를 반환합니다.
     * 사용 후 isGetFloor 플래그는 false로 초기화됩니다.
     */
    fun getRfFloor(): Map<String, Any> {
        isGetFloor = false
        return mapOf("status" to statusFloor, "floor" to floor)
    }

    fun getpermitted():Boolean{
        return wifipermitted
    }
}
