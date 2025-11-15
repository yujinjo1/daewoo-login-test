package com.example.auth.dto

data class LoginRequest(
    val accountId: String,
    val password: String
)