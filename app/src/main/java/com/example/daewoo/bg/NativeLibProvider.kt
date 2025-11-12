package com.example.daewoo.bg

import android.content.res.AssetManager
import com.fifth.maplocationlib.NativeLib

/**
 * 앱 전체에서 하나의 NativeLib 인스턴스를 공유하기 위한 프로바이더.
 */
object NativeLibProvider {
    val instance: NativeLib by lazy { NativeLib() }

    fun init(assetManager: AssetManager) {
        instance.setAssetManager(assetManager)
    }
}