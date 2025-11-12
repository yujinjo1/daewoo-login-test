// UnityHolder.kt
package com.example.daewoo

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import com.unity3d.player.IUnityPlayerLifecycleEvents
import com.unity3d.player.UnityPlayer

object UnityHolder : IUnityPlayerLifecycleEvents {
    @Volatile private var inited = false
    lateinit var player: UnityPlayer
        private set

    fun initOnce(context: Context) {
        if (inited) return
        // ApplicationContext로 생성하여 액티비티 교체에도 생존
        player = UnityPlayer(context.applicationContext, this)
        val glesMode = player.settings.getInt("gles_mode", 1)
        val trueColor8888 = false
        player.init(glesMode, trueColor8888)
        inited = true
        Log.d("UnityHolder", "UnityPlayer initialized once")
    }

    fun attachTo(container: FrameLayout) {
        // 이미 다른 부모에 붙어있으면 떼고 새 컨테이너에 부착
        (player.view.parent as? FrameLayout)?.removeView(player.view)
        container.addView(
            player.view,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        player.requestFocus()
    }

    // 필요 시 Unity 메시지 보낼 때 사용
    fun sendMessage(obj: String, method: String, param: String) {
        UnityPlayer.UnitySendMessage(obj, method, param)
    }

    fun isReady(): Boolean = inited

    // 액티비티 라이프사이클 연동용
    fun onResume() = player.resume()
    fun onPause()  = player.pause()
    fun onDestroy() { /* 앱 완전 종료 시에만 player.quit()/destroy 고려 */ }

    // IUnityPlayerLifecycleEvents
    override fun onUnityPlayerUnloaded() {
        Log.d("UnityHolder", "onUnityPlayerUnloaded")
    }

    override fun onUnityPlayerQuitted() {
        Log.d("UnityHolder", "onUnityPlayerQuitted")
    }
}
