package com.example.auth.dto

data class KakaoLoginRequest(
    val kakaoId: String,
    val email: String,
    val nickname: String
)