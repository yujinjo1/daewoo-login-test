package com.fifth.pdr_ext
import android.hardware.SensorEvent
import android.util.Log

// JNI name-based binding expects class name: com.example.pdr_ext.NativeBridge
// We keep it file-local to avoid leaking JNI details to the rest of the app.
class PDRM {
    companion object {
        init {
            try { System.loadLibrary("PDR_EXT") } catch (e: UnsatisfiedLinkError) {
                Log.e("PDR_EXT", "Failed to load pdr_ext for PDRM", e)
            }
        }
    }
    external fun isStep(tmpState: Int, realState: Int): Boolean
    external fun getNowMs(): Long
    external fun getresult(): PDR

    // 새로 추가/수정될 메소드들
    external fun add_headqueue(headValue: Float)
    external fun get_headqueue_peek(): Float
    external fun get_headqueue(): FloatArray
}

class SMcpp{
    companion object {
        init {
            try { System.loadLibrary("PDR_EXT") } catch (e: UnsatisfiedLinkError) {
                Log.e("PDR_EXT", "Failed to load pdr_ext for SMcpp", e)
            }
        }
    }
    external fun updateAccelerometer(acc: FloatArray, tMillis: Long = 0)
    external fun updateLinearAccelerometer(linacc: FloatArray, tMillis: Long = 0)
    external fun updateRotationQuaternion(rotV: FloatArray, tMillis: Long = 0)
    external fun updateLight(lux: Float, tMillis: Long = 0)
    external fun updatePressure(hPa: Float, tMillis: Long = 0)
    external fun onSensorEvent(event: SensorEvent)

    /** Returns [roll, pitch, yaw] in degrees. */
    external fun getRotationAnglesDeg(): FloatArray

    external fun isAllSensorsReady(): Boolean
}
