package com.example.daewoo.constants

/*
    250709 송형근
    API 사용 시 공통으로 사용될 BASE URL 상수
 */
object ApiConstants {
    const val SPRING_BASE_URL = "http://10.0.2.2:8080/api"  // Android Emulator에서 localhost 접근용
    const val SPRING_BASE_URL_DEVICE = "http://192.168.1.100:8080/api"  // 실제 기기에서 사용할 IP (네트워크에 맞게 수정)
    const val EXPRESS_BASE_URL = "https://rnn.korea.ac.kr/express"
}

