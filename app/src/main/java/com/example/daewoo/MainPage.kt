package com.example.daewoo

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import com.example.daewoo.bg.AppSharedState
import com.example.daewoo.bg.FlatmapBackgroundService
import com.example.daewoo.bg.LocationService
import com.example.daewoo.bg.SensorMaster
import com.example.daewoo.utils.PreferenceHelper
import kotlin.math.roundToInt

class MainPage : AppCompatActivity(), SensorEventListener {

    private lateinit var locationText: TextView
    private lateinit var toMapButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sensorMaster: SensorMaster? = null
    private var sensorMasterBound = false
    private var sensorListenerRegistered = false
    private var shouldBindSensorMaster = false

    private val fusedOrientation: FloatArray
        get() = AppSharedState.fusedOrientation

    private val sensorServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SensorMaster.LocalBinder ?: return
            sensorMaster = binder.getService()
            sensorMasterBound = true
            registerWithSensorMaster()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            unregisterFromSensorMaster()
            sensorMasterBound = false
            sensorMaster = null
            if (shouldBindSensorMaster) {
                ensureSensorMasterBinding()
            }
        }
    }

    private val locationUpdateRunnable = object : Runnable {
        override fun run() {
            updateLocationText()
            mainHandler.postDelayed(this, LOCATION_UPDATE_INTERVAL_MS)
        }
    }

    private enum class Environment {
        Indoor, Outdoor, Unknown
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.mainpage)

        locationText = findViewById(R.id.location_text)
        toMapButton = findViewById(R.id.to_map_btn)

        toMapButton.setOnClickListener { handleNavigationTap() }

        ensureSensorMaster()
        requestLocationPermissionsIfNeeded()
        startFlatmapServiceIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        shouldBindSensorMaster = true
        ensureSensorMasterBinding()
    }

    override fun onResume() {
        super.onResume()
        updateLocationText()
        mainHandler.post(locationUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(locationUpdateRunnable)
    }

    override fun onStop() {
        super.onStop()
        shouldBindSensorMaster = false
        unregisterFromSensorMaster()
        if (sensorMasterBound) {
            unbindService(sensorServiceConnection)
            sensorMasterBound = false
            sensorMaster = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(locationUpdateRunnable)
    }

    private fun handleNavigationTap() {
        when (determineEnvironment()) {
            Environment.Indoor -> {
                PreferenceHelper.setStartScreen(this, PreferenceHelper.getStartScreen(this, "main"))
                PreferenceHelper.setIndoorPoiName(this, null)
                startActivity(Intent(this, MainActivity::class.java))
            }
            Environment.Outdoor -> {
                val currentTestbed = PreferenceHelper.getStartScreen(this, "")
                PreferenceHelper.setStartScreen(this, currentTestbed)
                PreferenceHelper.setIndoorPoiName(this, null)
                startActivity(Intent(this, flatmapActivity::class.java))
            }
            Environment.Unknown -> {
                Toast.makeText(this, "환경 감지 중입니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensureSensorMaster() {
        if (!AppSharedState.sensorMasterRunning) {
            val intent = Intent(applicationContext, SensorMaster::class.java)
            ContextCompat.startForegroundService(applicationContext, intent)
        }
    }

    private fun ensureSensorMasterBinding() {
        if (!shouldBindSensorMaster) return
        if (!AppSharedState.sensorMasterRunning) {
            val serviceIntent = Intent(applicationContext, SensorMaster::class.java)
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        }
        if (!sensorMasterBound) {
            val bindIntent = Intent(this, SensorMaster::class.java)
            bindService(bindIntent, sensorServiceConnection, Context.BIND_AUTO_CREATE)
        } else {
            registerWithSensorMaster()
        }
    }

    private fun registerWithSensorMaster() {
        if (!sensorMasterBound || sensorListenerRegistered) return
        sensorMaster?.registerListener(this)
        sensorListenerRegistered = true
    }

    private fun unregisterFromSensorMaster() {
        if (!sensorListenerRegistered) return
        sensorMaster?.unregisterListener(this)
        sensorListenerRegistered = false
    }

    private fun requestLocationPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERMISSION_REQ)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQ &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startFlatmapServiceIfNeeded()
        }
    }

    private fun startFlatmapServiceIfNeeded() {
        if (!hasBackgroundLocationPermission()) {
            Toast.makeText(
                this,
                "백그라운드 위치 권한이 필요합니다.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!isFlatmapServiceRunning()) {
            PreferenceHelper.setStartScreen(this, "")
            PreferenceHelper.setIndoorPoiName(this, null)
            val intent = Intent(this, FlatmapBackgroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == LocationService::class.java.name }
    }

    private fun isFlatmapServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == FlatmapBackgroundService::class.java.name }
    }

    private fun updateLocationText() {
        val environment = determineEnvironment()
        val text = when (environment) {
            Environment.Indoor -> formatIndoorLocation()
            Environment.Outdoor -> formatOutdoorLocation()
            Environment.Unknown -> "환경 감지 중..."
        }
        locationText.text = text
    }

    private fun determineEnvironment(): Environment {
        return when {
            isLocationServiceRunning() -> Environment.Indoor
            isFlatmapServiceRunning() -> Environment.Outdoor
            AppSharedState.flatmapStrongIndoorConfirmed -> Environment.Indoor
            else -> Environment.Unknown
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        sensorMaster?.isReadyLocalization(event, fusedOrientation)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    private fun formatIndoorLocation(): String {
        val dto = AppSharedState.locationDto
        val x = dto.userX.formatDecimal(2)
        val y = dto.userY.formatDecimal(2)
        val floor = dto.userFloor.roundToInt()
        return "x:$x y:$y floor:$floor"
    }

    private fun formatOutdoorLocation(): String {
        val payload = AppSharedState.flatmapLastGpsPayload
        if (!payload.isNullOrBlank()) {
            val parts = payload.split(",")
            if (parts.size >= 2) {
                return "위도:${parts[0]} 경도:${parts[1]}"
            }
        }
        val fallback = getLastKnownLocation()
        return if (fallback != null) {
            "위도:${fallback.latitude.formatDecimal(6)} 경도:${fallback.longitude.formatDecimal(6)}"
        } else {
            "GPS 위치 획득 중..."
        }
    }

    private fun getLastKnownLocation(): Location? {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (provider in providers) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            manager.getLastKnownLocation(provider)?.let { return it }
        }
        return null
    }

    private fun Double.formatDecimal(digits: Int) = "%.${digits}f".format(this)

    companion object {
        private const val LOCATION_PERMISSION_REQ = 1051
        private const val LOCATION_UPDATE_INTERVAL_MS = 1_000L
    }
}
