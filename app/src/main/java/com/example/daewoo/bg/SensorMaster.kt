package com.example.daewoo.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.daewoo.R
import com.kircherelectronics.fsensor.observer.SensorSubject
import com.kircherelectronics.fsensor.sensor.FSensor
import com.kircherelectronics.fsensor.sensor.gyroscope.GyroscopeSensor
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.min
import com.fifth.pdr_ext.PDRM
/**
 * Foreground service that owns sensor registrations and dispatches events to bound clients.
 * It also centralises the localisation readiness check so activities/services can query it.
 */
class SensorMaster : Service(), SensorEventListener {

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private val listeners = CopyOnWriteArraySet<SensorEventListener>()
    private var sensorsRegistered = false
    private lateinit var fSensor: FSensor

    private val sensorObserver = SensorSubject.SensorObserver { values ->
        values?.let { AppSharedState.fusedOrientation = it.clone() }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SensorMaster = this@SensorMaster
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        startForegroundNotification()
        registerSensors()
        startFusedSensor()
        AppSharedState.sensorMasterRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterSensors()
        stopFusedSensor()
        AppSharedState.sensorMasterRunning = false
        super.onDestroy()
    }

    fun registerListener(listener: SensorEventListener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: SensorEventListener) {
        listeners.remove(listener)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val snapshot = listeners.toList()
        snapshot.forEach { it.onSensorChanged(event) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        listeners.forEach { it.onAccuracyChanged(sensor, accuracy) }
    }

    fun isReadyLocalization(event: SensorEvent, fusedOrientation: FloatArray): Boolean {
        if (AppSharedState.isSensorStabled) {
            return true
        }

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val delta = if (
                    event.values[0] in -0.3f..0.3f &&
                    event.values[1] in -0.3f..0.3f &&
                    event.values[2] in -0.3f..0.3f
                ) -1 else 1
                AppSharedState.accStableCount += delta
                AppSharedState.accStableCount = min(AppSharedState.accStableCount, 50)
            }
            Sensor.TYPE_GYROSCOPE -> {
                AppSharedState.gyroStableCount--
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                AppSharedState.rotangle = getRotationFromQuaternion(event.values)
            }
        }

        if (AppSharedState.gyroStableCount <= 0) {
            AppSharedState.gyroCaliValue =
                ((Math.toDegrees(fusedOrientation[0].toDouble()) + 360) % 360).toFloat()
        }

        AppSharedState.isSensorStabled =
            AppSharedState.accStableCount < 0 && AppSharedState.gyroStableCount <= 0
        Log.d("SensorMaster", "isSensorStabled: ${AppSharedState.isSensorStabled}")
        return AppSharedState.isSensorStabled
    }

    private fun registerSensors() {
        if (sensorsRegistered) return
        val configs = listOf(
            Sensor.TYPE_ACCELEROMETER to SensorManager.SENSOR_DELAY_GAME,
            Sensor.TYPE_MAGNETIC_FIELD to SensorManager.SENSOR_DELAY_GAME,
            Sensor.TYPE_GYROSCOPE to SensorManager.SENSOR_DELAY_GAME,
            Sensor.TYPE_LINEAR_ACCELERATION to SensorManager.SENSOR_DELAY_GAME,
            Sensor.TYPE_PRESSURE to SensorManager.SENSOR_DELAY_FASTEST,
            Sensor.TYPE_LIGHT to SensorManager.SENSOR_DELAY_GAME,
            Sensor.TYPE_GAME_ROTATION_VECTOR to SensorManager.SENSOR_DELAY_GAME,
            Sensor.TYPE_PROXIMITY to SensorManager.SENSOR_DELAY_NORMAL,
            Sensor.TYPE_GRAVITY to SensorManager.SENSOR_DELAY_GAME
        )
        configs.forEach { (type, delay) ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(this, sensor, delay)
            }
        }
        sensorsRegistered = true
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sensorManager.unregisterListener(this)
        sensorsRegistered = false
    }

    private fun startFusedSensor() {
        fSensor = GyroscopeSensor(this)
        (fSensor as GyroscopeSensor).register(sensorObserver)
        (fSensor as GyroscopeSensor).start()
    }

    private fun stopFusedSensor() {
        if (::fSensor.isInitialized) {
            (fSensor as? GyroscopeSensor)?.stop()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "sensor_master_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sensor Master",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                enableVibration(false)
                description = "센서 데이터를 지속적으로 수집합니다."
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("센서 수집 중")
            .setContentText("기기 센서를 계속 모니터링합니다.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setVibrate(longArrayOf(0L))
            .build()

        startForeground(2, notification)
    }

    private fun getRotationFromQuaternion(quaternion: FloatArray): FloatArray {
        val x = quaternion[0]
        val y = quaternion[1]
        val z = quaternion[2]
        val w = quaternion[3]
        val pitch = Math.toDegrees(
            atan2(
                2.0 * (w * x + y * z),
                1.0 - 2.0 * (x * x + y * y)
            )
        ).toFloat()
        val roll = Math.toDegrees(
            asin(
                (2.0 * (w * y - z * x)).coerceIn(-1.0, 1.0)
            )
        ).toFloat()
        val yaw = Math.toDegrees(
            atan2(
                2.0 * (w * z + x * y),
                1.0 - 2.0 * (y * y + z * z)
            )
        ).toFloat()
        return floatArrayOf(pitch, roll, yaw)
    }
}
