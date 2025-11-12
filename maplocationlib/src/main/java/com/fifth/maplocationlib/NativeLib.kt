package com.fifth.maplocationlib

import android.content.res.AssetManager

class NativeLib {
    external fun initializeEngine(floor: Int, testbed: String)
    external fun processStep(gyro: Float, compass:Float,  stepLength: Float, stepCount: Int, floor: Int, stairGyroValue: Float, elevationMode: Int): FloatArray?
    external fun destroyEngine()
    external fun setAssetManager(assetManager: AssetManager)
    external fun getStringFromNative(): String?
    external fun reSearchStartInStairs(stairsCoords_x: Int, stairsCoords_y: Int)
    external fun reSearchStart(search_range : Int)
    companion object {
        const val version = "1.1.6"
        init {
            System.loadLibrary("maplocationlib")
        }
    }
}