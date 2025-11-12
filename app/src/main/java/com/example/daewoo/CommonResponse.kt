package com.example.daewoo

data class CommonResponse<T>(
    val statusCode: Int,
    val data: T,
    val message: String
)
