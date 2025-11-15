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

        //  카카오 SDK 초기화 (UserApiClient 사용을 위해 반드시 필요)
        KakaoSdk.init(this, "288d7f4793e128640e46b6f3f16109b6")  //  네이티브 앱 키

        //  원래 있던 로직 그대로 유지
        if (!AppSharedState.sensorMasterRunning) {
            val intent = Intent(this, SensorMaster::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }
}
