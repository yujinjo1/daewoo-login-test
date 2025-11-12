package com.example.daewoo.dtos

data class FCMTokenDto(
    val token: String,
    val platform: String = "ANDROID"
)
