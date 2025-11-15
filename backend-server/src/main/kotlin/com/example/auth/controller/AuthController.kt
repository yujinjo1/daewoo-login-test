package com.example.auth.controller

import com.example.auth.dto.*
import com.example.auth.service.AuthService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = ["*"])
class AuthController(
    private val authService: AuthService
) {
    
    @PostMapping("/register")
    fun register(
        @RequestBody request: RegisterRequest
    ): ResponseEntity<CommonResponse<LoginResponseData>> {
        val loginRequest = LoginRequest(request.accountId, request.password)
        val response = authService.register(loginRequest, request.nickname, request.email)
        
        return if (response.success) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.badRequest().body(response)
        }
    }
    
    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest
    ): ResponseEntity<CommonResponse<LoginResponseData>> {
        val response = authService.login(request)
        
        return if (response.success) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.badRequest().body(response)
        }
    }
    
    @PostMapping("/kakao/login")
    fun kakaoLogin(
        @RequestBody request: KakaoLoginRequest
    ): ResponseEntity<CommonResponse<LoginResponseData>> {
        val response = authService.kakaoLogin(request)
        
        return if (response.success) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.badRequest().body(response)
        }
    }

    @GetMapping("/kakao/callback")
    fun kakaoCallback(
        @RequestParam("code") authorizationCode: String,
        @RequestParam("state", required = false) state: String?
    ): ResponseEntity<CommonResponse<LoginResponseData>> {
        val response = authService.kakaoOAuthLogin(authorizationCode)
        
        return if (response.success) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.badRequest().body(response)
        }
    }

    @GetMapping("/kakao/auth-url")
    fun getKakaoAuthUrl(): ResponseEntity<Map<String, String>> {
        val authUrl = "https://kauth.kakao.com/oauth/authorize?client_id=2189407658f9f47ad5fe854db773dde2&redirect_uri=http://localhost:8080/api/auth/kakao/callback&response_type=code"
        return ResponseEntity.ok(mapOf("authUrl" to authUrl))
    }
}

data class RegisterRequest(
    val accountId: String,
    val password: String,
    val nickname: String,
    val email: String
)