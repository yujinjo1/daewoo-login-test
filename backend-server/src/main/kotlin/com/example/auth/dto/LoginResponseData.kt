package com.example.auth.dto

data class LoginResponseData(
    val token: String,
    val userId: Long,
    val accountId: String,
    val nickname: String,
    val email: String
)