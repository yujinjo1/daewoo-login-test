package com.example.auth.repository

import com.example.auth.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByAccountId(accountId: String): User?
    fun findByEmail(email: String): User?
    fun findByKakaoId(kakaoId: String): User?
    fun existsByAccountId(accountId: String): Boolean
    fun existsByEmail(email: String): Boolean
}