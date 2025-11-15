package com.example.auth.dto

data class CommonResponse<T>(
    val success: Boolean,
    val message: String,
    val data: T? = null
) {
    companion object {
        fun <T> success(data: T, message: String = "Success"): CommonResponse<T> {
            return CommonResponse(true, message, data)
        }

        fun <T> error(message: String): CommonResponse<T> {
            return CommonResponse(false, message, null)
        }
    }
}