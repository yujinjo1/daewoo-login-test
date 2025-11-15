package com.example.auth.service

import com.example.auth.dto.KakaoTokenResponse
import com.example.auth.dto.KakaoUserInfoResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@Service
class KakaoOAuthService {

    @Value("\${kakao.client-id}")
    private lateinit var clientId: String

    @Value("\${kakao.redirect-uri}")
    private lateinit var redirectUri: String

    @Value("\${kakao.token-url}")
    private lateinit var tokenUrl: String

    @Value("\${kakao.user-info-url}")
    private lateinit var userInfoUrl: String

    private val webClient = WebClient.builder().build()

    fun getAccessToken(authorizationCode: String): Mono<KakaoTokenResponse> {
        val formData = LinkedMultiValueMap<String, String>()
        formData.add("grant_type", "authorization_code")
        formData.add("client_id", clientId)
        formData.add("redirect_uri", redirectUri)
        formData.add("code", authorizationCode)

        return webClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .bodyToMono(KakaoTokenResponse::class.java)
    }

    fun getUserInfo(accessToken: String): Mono<KakaoUserInfoResponse> {
        return webClient.get()
            .uri(userInfoUrl)
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .bodyToMono(KakaoUserInfoResponse::class.java)
    }

    fun generateAuthUrl(state: String? = null): String {
        val baseUrl = "https://kauth.kakao.com/oauth/authorize"
        val params = mutableListOf(
            "client_id=$clientId",
            "redirect_uri=$redirectUri",
            "response_type=code"
        )
        
        state?.let { params.add("state=$it") }
        
        return "$baseUrl?${params.joinToString("&")}"
    }
}