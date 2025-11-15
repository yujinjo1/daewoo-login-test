package com.example.auth.service

import com.example.auth.dto.*
import com.example.auth.entity.User
import com.example.auth.repository.UserRepository
import com.example.auth.util.JwtUtil
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class AuthService(
    private val userRepository: UserRepository,
    private val jwtUtil: JwtUtil,
    private val kakaoOAuthService: KakaoOAuthService
) {
    
    fun register(request: LoginRequest, nickname: String, email: String): CommonResponse<LoginResponseData> {
        // Check if user already exists
        if (userRepository.existsByAccountId(request.accountId)) {
            return CommonResponse.error("Account ID already exists")
        }
        
        if (userRepository.existsByEmail(email)) {
            return CommonResponse.error("Email already exists")
        }
        
        // Create new user
        val user = User(
            accountId = request.accountId,
            password = request.password, // In production, hash this password
            nickname = nickname,
            email = email
        )
        
        val savedUser = userRepository.save(user)
        val token = jwtUtil.generateToken(savedUser.id!!, savedUser.accountId)
        
        val responseData = LoginResponseData(
            token = token,
            userId = savedUser.id,
            accountId = savedUser.accountId,
            nickname = savedUser.nickname,
            email = savedUser.email
        )
        
        return CommonResponse.success(responseData, "User registered successfully")
    }
    
    fun login(request: LoginRequest): CommonResponse<LoginResponseData> {
        val user = userRepository.findByAccountId(request.accountId)
            ?: return CommonResponse.error("Invalid account ID or password")
        
        // In production, verify hashed password
        if (user.password != request.password) {
            return CommonResponse.error("Invalid account ID or password")
        }
        
        val token = jwtUtil.generateToken(user.id!!, user.accountId)
        
        val responseData = LoginResponseData(
            token = token,
            userId = user.id,
            accountId = user.accountId,
            nickname = user.nickname,
            email = user.email
        )
        
        return CommonResponse.success(responseData, "Login successful")
    }
    
    fun kakaoLogin(request: KakaoLoginRequest): CommonResponse<LoginResponseData> {
        // Check if user exists with this Kakao ID
        val existingUser = userRepository.findByKakaoId(request.kakaoId)
        
        if (existingUser != null) {
            // User exists, generate token and return login response
            val token = jwtUtil.generateToken(existingUser.id!!, existingUser.accountId)
            
            val responseData = LoginResponseData(
                token = token,
                userId = existingUser.id,
                accountId = existingUser.accountId,
                nickname = existingUser.nickname,
                email = existingUser.email
            )
            
            return CommonResponse.success(responseData, "카카오 로그인 성공")
        } else {
            // New user, create account
            val accountId = "kakao_${request.kakaoId}"
            
            // Check if email already exists
            if (userRepository.existsByEmail(request.email)) {
                return CommonResponse.error("이미 존재하는 이메일입니다")
            }
            
            val newUser = User(
                kakaoId = request.kakaoId,
                accountId = accountId,
                password = "kakao_oauth", // Placeholder password for OAuth users
                nickname = request.nickname,
                email = request.email
            )
            
            val savedUser = userRepository.save(newUser)
            val token = jwtUtil.generateToken(savedUser.id!!, savedUser.accountId)
            
            val responseData = LoginResponseData(
                token = token,
                userId = savedUser.id,
                accountId = savedUser.accountId,
                nickname = savedUser.nickname,
                email = savedUser.email
            )
            
            return CommonResponse.success(responseData, "카카오 계정으로 회원가입 및 로그인 성공")
        }
    }

    fun kakaoOAuthLogin(authorizationCode: String): CommonResponse<LoginResponseData> {
        return try {
            // 1. 인가 코드로 액세스 토큰 받기
            val tokenResponse = kakaoOAuthService.getAccessToken(authorizationCode).block()
                ?: return CommonResponse.error("카카오 토큰을 받는데 실패했습니다")

            // 2. 액세스 토큰으로 사용자 정보 받기
            val userInfo = kakaoOAuthService.getUserInfo(tokenResponse.accessToken).block()
                ?: return CommonResponse.error("카카오 사용자 정보를 받는데 실패했습니다")

            // 3. 카카오 사용자 정보로 로그인/회원가입 처리
            val kakaoId = userInfo.id.toString()
            val email = userInfo.kakaoAccount?.email ?: "no-email@kakao.com"
            val nickname = userInfo.kakaoAccount?.profile?.nickname 
                ?: userInfo.properties?.nickname 
                ?: "카카오사용자"

            val kakaoLoginRequest = KakaoLoginRequest(
                kakaoId = kakaoId,
                email = email,
                nickname = nickname
            )

            kakaoLogin(kakaoLoginRequest)
        } catch (e: Exception) {
            CommonResponse.error("카카오 로그인 처리 중 오류가 발생했습니다: ${e.message}")
        }
    }
}