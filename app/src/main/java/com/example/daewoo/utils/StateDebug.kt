package com.example.daewoo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.daewoo.utils.sendCsvData
import okhttp3.OkHttpClient
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.ArrayDeque

private const val SAMPLE_INTERVAL_MS = 100L
private const val PRE_WINDOW_MS = 3000L
private const val POST_WINDOW_MS = 4500L
private const val BUFFER_RETENTION_MS = PRE_WINDOW_MS + POST_WINDOW_MS

private data class SensorSample(
    val timestamp: Long,
    val line: String
)

class StateDebug(
    private val activity: Activity,
    private val context: Context,
    private val accessToken: String?,
    private val getSensorData: () -> String
) {
    private var csvlog: Boolean = false
    private var csvFile: File? = null
    private var csvWriter: BufferedWriter? = null
    private val logHandler = Handler(Looper.getMainLooper())
    private var lastSwitchChecked: Boolean = false
    private var bufferActive: Boolean = false
    private val sampleBuffer: ArrayDeque<SensorSample> = ArrayDeque()
    private val captureSamples: MutableList<SensorSample> = mutableListOf()
    private var captureActive: Boolean = false
    private var captureEndTime: Long = 0L

    fun onCreate() {
        // No-op: legacy CSV toggle wiring handled externally if needed.
    }

    fun onResume() {
        if (csvlog) {
            initCsvWriter()
            ensureSampling()
        }
    }

    fun onPause() {
        if (csvlog) {
            logHandler.removeCallbacks(logRunnable)
            closeCsvWriter()
        }
    }

    fun startBuffering() {
        if (!bufferActive) {
            bufferActive = true
            ensureSampling()
        }
    }

    fun stopBuffering() {
        bufferActive = false
        if (!csvlog) {
            logHandler.removeCallbacks(logRunnable)
        }
    }

    fun triggerMotionWithoutStepsCapture(): Boolean {
        if (captureActive) return false
        val triggerSample = captureSample()
        handleNewSample(triggerSample, writeToCsv = csvlog)
        val triggerTime = triggerSample.timestamp
        val preWindowStart = triggerTime - PRE_WINDOW_MS
        captureSamples.clear()
        sampleBuffer.filterTo(captureSamples) { it.timestamp in preWindowStart..triggerTime }
        captureActive = true
        captureEndTime = triggerTime + POST_WINDOW_MS
        return true
    }

    private fun sendAndClose() {
        logHandler.postDelayed({
            try {
                val file = csvFile
                val token = accessToken
                if (file != null && token != null) {
                    val client = OkHttpClient()
                    sendCsvData(
                        client = client,
                        csvFile = file,
                        accessToken = token,
                        onUnauthorized = {
                            activity.runOnUiThread {
                                Toast.makeText(context, "인증 오류: 토큰 만료. 재로그인 해주세요.", Toast.LENGTH_LONG).show()
                                activity.startActivity(Intent(context, LoginActivity::class.java))
                                activity.finish()
                            }
                        }
                    )
                } else {
                    Log.e("CSV", "sendCsvData skipped: csvFile or accessToken is null")
                }
            } catch (e: Exception) {
                Log.e("CSV", "sendCsvData scheduling failed: ${e.message}", e)
            }
        }, 3000)
    }

    private val logRunnable = object : Runnable {
        override fun run() {
            val shouldContinue = bufferActive || csvlog
            if (!shouldContinue) return

            val sample = captureSample()
            handleNewSample(sample, writeToCsv = true)
            logHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private fun ensureSampling() {
        val shouldRun = bufferActive || csvlog
        if (shouldRun) {
            logHandler.removeCallbacks(logRunnable)
            logHandler.post(logRunnable)
        }
    }

    private fun captureSample(): SensorSample {
        val raw = getSensorData()
        val normalized = if (raw.endsWith('\n')) raw else "$raw\n"
        return SensorSample(
            timestamp = System.currentTimeMillis(),
            line = normalized
        )
    }

    private fun handleNewSample(sample: SensorSample, writeToCsv: Boolean) {
        sampleBuffer.addLast(sample)
        trimBuffer(sample.timestamp)

        if (captureActive) {
            if (captureSamples.isEmpty() || captureSamples.last().timestamp != sample.timestamp) {
                captureSamples.add(sample)
            }
            if (sample.timestamp >= captureEndTime) {
                finalizeCapture()
            }
        }

        if (csvlog && writeToCsv) {
            csvWriter?.let { writer ->
                try {
                    writer.append(sample.line)
                    writer.flush()
                } catch (e: IOException) {
                    Log.e("StateDebug", "Error writing sensor data", e)
                }
            }
        }
    }

    private fun finalizeCapture() {
        val token = accessToken
        if (captureSamples.isEmpty() || token == null) {
            captureSamples.clear()
            captureActive = false
            return
        }
        val fmt = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.getDefault())
        val timestamp = fmt.format(System.currentTimeMillis())
        val captureFile = File(context.filesDir, "motion_capture_$timestamp.csv")
        try {
            BufferedWriter(FileWriter(captureFile)).use { writer ->
                writer.write("timestamp,pitch,roll,yaw,rot_x,rot_y,rot_z,rot_w,gyro_x,gyro_y,gyro_z,accel_x,accel_y,accel_z,linear_x,linear_y,linear_z,light,pressure,proximity,heading,MM,statereal,statetmp,steplength,totalstep,isstep,stateflag,statequeue0,statequeue1,statequeue2,statequeue3,statequeue4\n")
                captureSamples.forEach { writer.append(it.line) }
            }
            val client = OkHttpClient()
            sendCsvData(
                client = client,
                csvFile = captureFile,
                accessToken = token,
                onUnauthorized = {
                    activity.runOnUiThread {
                        Toast.makeText(context, "인증 오류: 토큰 만료. 재로그인 해주세요.", Toast.LENGTH_LONG).show()
                        activity.startActivity(Intent(context, LoginActivity::class.java))
                        activity.finish()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("StateDebug", "Failed to write motion capture CSV: ${e.message}", e)
        } finally {
            captureSamples.clear()
            captureActive = false
        }
    }

    private fun trimBuffer(currentTimestamp: Long) {
        val cutoff = currentTimestamp - BUFFER_RETENTION_MS
        while (sampleBuffer.isNotEmpty() && sampleBuffer.first().timestamp < cutoff) {
            sampleBuffer.removeFirst()
        }
    }

    private fun initCsvWriter() {
        try {
            val fmt = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault())
            val ts = fmt.format(System.currentTimeMillis())
            val dir = context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val name = "sensor_data_${ts}.csv"
            val file = File(dir, name)
            val newFile = !file.exists()
            csvFile = file
            csvWriter = BufferedWriter(FileWriter(file, true))
            if (newFile) {
                csvWriter?.apply {
                    write("timestamp,pitch,roll,yaw,rot_x,rot_y,rot_z,rot_w,gyro_x,gyro_y,gyro_z,accel_x,accel_y,accel_z,linear_x,linear_y,linear_z,light,pressure,proximity,heading,MM,statereal,statetmp,steplength,totalstep,isstep,stateflag,statequeue0,statequeue1,statequeue2,statequeue3,statequeue4\n")
                    flush()
                }
            }
            Log.d("CSV", "CSV logging to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("CSV", "initCsvWriter failed: ${e.message}", e)
        }
    }

    private fun closeCsvWriter() {
        try {
            csvWriter?.flush()
        } catch (_: Exception) {
        }
        try {
            csvWriter?.close()
        } catch (_: Exception) {
        }
        csvWriter = null
    }
}
