package com.fifth.maplocationlib.inpocketPDR

import android.hardware.SensorManager
import com.fifth.maplocationlib.FloorChangeDetection

class PhoneCalling(private val floorChangeDetection: FloorChangeDetection) {

    //사실 얘밖에 안씀
    fun transformSensorValues(gameRotationVec: FloatArray, sensorValues: FloatArray): FloatArray {
        // 회전 행렬 (3x3) 생성: 월드 -> 기기
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, gameRotationVec)

        // 회전 행렬의 전치(역행렬)를 사용하여 기기 좌표 -> 월드 좌표 변환
        // (직교 행렬이므로 전치가 역행렬 역할을 합니다.)
        val transformedValues = FloatArray(3)
        transformedValues[0] = rotationMatrix[0] * sensorValues[0] +
                rotationMatrix[3] * sensorValues[1] +
                rotationMatrix[6] * sensorValues[2]
        transformedValues[1] = rotationMatrix[1] * sensorValues[0] +
                rotationMatrix[4] * sensorValues[1] +
                rotationMatrix[7] * sensorValues[2]
        transformedValues[2] = rotationMatrix[2] * sensorValues[0] +
                rotationMatrix[5] * sensorValues[1] +
                rotationMatrix[8] * sensorValues[2]
        return transformedValues
    }

}
