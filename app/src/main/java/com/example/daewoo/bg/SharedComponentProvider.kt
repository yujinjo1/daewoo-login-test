package com.example.daewoo.bg

import android.content.Context
import com.fifth.maplocationlib.FloorChangeDetection
import com.fifth.maplocationlib.RotationPatternDetector
import com.fifth.maplocationlib.UserStateMonitor

object SharedComponentProvider {
    @Volatile
    private var appContext: Context? = null

    private val userStateMonitorLazy = lazy { UserStateMonitor(requireContext()) }
    private val rotationPatternDetectorLazy = lazy { RotationPatternDetector() }

    @Volatile
    private var floorChangeDetection: FloorChangeDetection? = null
    @Volatile
    private var floorChangeTestbed: String? = null

    fun init(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    appContext = context.applicationContext
                }
            }
        }
    }

    private fun requireContext(): Context {
        return appContext ?: error("SharedComponentProvider not initialized. Call init(context) first.")
    }

    fun getUserStateMonitor(context: Context): UserStateMonitor {
        init(context)
        return userStateMonitorLazy.value
    }

    val rotationPatternDetector: RotationPatternDetector
        get() = rotationPatternDetectorLazy.value

    fun getFloorChangeDetection(context: Context, testbed: String): FloorChangeDetection {
        init(context)
        val ctx = requireContext()
        val current = floorChangeDetection
        return if (current == null || floorChangeTestbed != testbed) {
            synchronized(this) {
                val inside = floorChangeDetection
                if (inside == null || floorChangeTestbed != testbed) {
                    val newInstance = FloorChangeDetection(ctx, testbed)
                    floorChangeDetection = newInstance
                    floorChangeTestbed = testbed
                    newInstance
                } else {
                    inside
                }
            }
        } else {
            current
        }
    }
}