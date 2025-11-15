package com.example.auth.entity

import jakarta.persistence.*

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true)
    val kakaoId: String? = null,

    @Column(unique = true, nullable = false)
    val accountId: String,

    @Column(nullable = false)
    val password: String,

    @Column(nullable = false)
    val nickname: String,

    @Column(unique = true, nullable = false)
    val email: String
)