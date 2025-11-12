package com.example.daewoo

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.daewoo.bg.AppSharedState
import com.example.daewoo.bg.SensorMaster
import com.kakao.sdk.common.KakaoSdk  // ✅ 추가된 import

class DaewooApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // ✅ 카카오 SDK 초기화 (UserApiClient 사용을 위해 반드시 필요)
        KakaoSdk.init(this, "9a46ffdb7c4630df2fa5a39d3aaa3d6b")  // ⚠️ 네이티브 앱 키

        // ✅ 원래 있던 로직 그대로 유지
        if (!AppSharedState.sensorMasterRunning) {
            val intent = Intent(this, SensorMaster::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }
}
