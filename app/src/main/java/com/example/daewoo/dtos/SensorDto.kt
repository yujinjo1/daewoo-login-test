package com.example.daewoo.dtos

/*
    250709 송형근
    Sensor 전송용 데이터
 */
data class SensorDto(
//    val userId: String,
    val mapId: Int,
    val acceleration: SensorXYZ,
    val magnetic: SensorXYZ,
    val gyro: SensorXYZ,
    val linearAcceleration: SensorXYZ,
    val rotation: SensorXYZ,
    val pressure: Float,
    val light: Float,
    val proximity: Float,
    val rf: Float,
    val userStateReal: Int,
    val stepLength: Float
)

data class SensorXYZ(
    val x: Float,
    val y: Float,
    val z: Float
)