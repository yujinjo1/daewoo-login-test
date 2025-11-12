package com.fifth.maplocationlib.inpocketPDR

data class PoseManager(
    var dir: Double = 0.0,
    var reliableDir: Int = 0,
    var smoothDir: Int = 0,
    var referenceDir: Int = 0,
    var light: Float = 0f,
    var lightThreshold: Float = 10.0f,
    var reliableCount: Int = 5,
    var directionReliability: Int = 0,
    var poseChanged: Boolean = false,
    var devicePosture: Int = 0
) {
    fun smoothing(angle: Double): Int = when {
        angle in 0.0..15.0 || angle in 345.1..360.0 -> 0
        angle in 15.1..45.0 -> 30
        angle in 45.1..75.0 -> 60
        angle in 75.1..105.0 -> 90
        angle in 105.1..135.0 -> 120
        angle in 135.1..165.0 -> 150
        angle in 165.1..195.0 -> 180
        angle in 195.1..225.0 -> 210
        angle in 225.1..255.0 -> 240
        angle in 255.1..285.0 -> 270
        angle in 285.1..315.0 -> 300
        angle in 315.1..345.0 -> 330
        else -> angle.toInt()
    }

    fun smoothing2(angle: Double): Int = when {
        angle in 0.0..22.5 || angle in 337.6..360.0 -> 0
        angle in 22.6..67.5 -> 45
        angle in 67.6..112.5 -> 90
        angle in 112.6..157.5 -> 135
        angle in 157.6..202.5 -> 180
        angle in 202.6..247.5 -> 225
        angle in 247.6..292.5 -> 270
        angle in 292.6..337.5 -> 315
        else -> angle.toInt()
    }

    fun setDirection(gyroX: Double, gyroY: Double, gyroZ: Double) {
        dir = gyroZ
        smoothDir = smoothing(dir)
        directionReliability = 2
    }


    fun getDirection(): Int = when (devicePosture) {
        0 -> smoothing(dir)
        1 -> reliableDir + (dir.toInt() - referenceDir)
        2 -> smoothDir
        else -> smoothing(dir)
    }

    fun getDirection2(): Int = smoothing2(dir)

    fun getDevicePosture(maxMagnitudeOfAcc: Double, minMaxAcc: Array<Double>, gravityAcc: Array<Double>): Int {
        if (light < lightThreshold) {
            if (!poseChanged) {
                poseChanged = true
                referenceDir = dir.toInt()
                devicePosture = 1
            }
            reliableCount = 0
        } else {
            devicePosture = if (maxMagnitudeOfAcc < 6.0) 0 else 2
            reliableCount = if (devicePosture == 0 && gravityAcc[2] > 9.0) reliableCount + 1 else 0
        }
        reliableDir = if (reliableCount >= 5) dir.toInt() else reliableDir
        return devicePosture
    }
}
