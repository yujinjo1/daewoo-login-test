package com.example.daewoo.dtos

/*
    250709 송형근
    Location 전송용 데이터
 */
data class LocationDto(
    val userId: String,
    val mapId: Int,
    val userX: Double,
    val userY: Double,
    val userZ: Double,
    val userDirection: Double,
    val userFloor: Double,
    val userStatus: String,
    val background: Boolean
)