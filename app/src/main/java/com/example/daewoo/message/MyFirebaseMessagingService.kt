package com.example.daewoo.message

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.daewoo.dtos.FCMTokenDto
import com.example.daewoo.utils.saveFCMToken
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.OkHttpClient

class MyFirebaseMessagingService : FirebaseMessagingService() {

    // 토큰이 갱신될 때 콜백
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Refreshed token: $token")

        // accessToken, OkHttpClient는 별도 관리 필요(싱글톤/전역/Preference 등)
        val context = applicationContext
        val client = OkHttpClient()

        // SharedPreferences 등에서 accessToken 가져오기
        val pref = context.getSharedPreferences("USER_PREF", Context.MODE_PRIVATE)
        val accessToken = pref.getString("ACCESS_TOKEN", null)

        // accessToken이 null이 아니면 서버 전송
        if (accessToken != null) {
            saveFCMToken(
                client = client,
                fcmTokenDto = FCMTokenDto(token = token),
                accessToken = accessToken,
                onUnauthorized = {
                    Log.e("FCM", "Saving FCM token failed")
                }
            )
        } else {
            Log.w("FCM", "onNewToken: accessToken 없음, 서버 전송 생략")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "From: ${remoteMessage.from}")

        val title = remoteMessage.notification?.title ?: remoteMessage.data.get("title")
        val body = remoteMessage.notification?.body ?: remoteMessage.data.get("body")


        if (isAppInForeground(this)) {
            // Notification(시스템 알림) 대신, 메시지 전달 (포그라운드용)
            val intent = Intent("com.example.daewoo.FCM_MESSAGE")
            intent.putExtra("title", title)
            intent.putExtra("body", body)
            sendBroadcast(intent)
        } else {
            // 시스템 알림 (백그라운드용
            // )
            sendNotification(title!!, body!!)
        }
    }

    // 백그라운드 알림 전송
    private fun sendNotification(title: String, message: String) {
        val channelId = "fcm_default_channel"

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.fifth.maplocationlib.R.drawable.ic_launcher_foreground) // 푸쉬 메시지 아이콘
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)

        Log.d("FCM", "Received Message Title: $title")
        Log.d("FCM", "Received Message Body: $message")
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    // App 포그라운드 / 백그라운드 판별 함수
    private fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val appProcesses = activityManager.runningAppProcesses
        val packageName = context.packageName
        if (appProcesses != null) {
            for (appProcess in appProcesses) {
                if (appProcess.processName == packageName) {
                    return appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
            }
        }
        return false
    }
}