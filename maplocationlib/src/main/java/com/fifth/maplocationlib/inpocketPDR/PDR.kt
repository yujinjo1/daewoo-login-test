package com.fifth.maplocationlib.inpocketPDR

data class PDR(
    val devicePosture: Int,
    val movementMode: Int,
    val userAttitude: Int,
    val stepLength: Double,
    val direction: Double,
    val totalStepCount: Int,
    val directionReliability: Int
)
