package com.fifth.maplocationlib

import android.util.Log
import kotlin.math.abs

class RotationPatternDetector(
    // 한 윈도우에 수집할 샘플 개수 (필요에 따라 조정)
    private val windowSize: Int = 100,
    // 축별 피크-투-피크 변화량 차이가 임계치를 초과하면 상태 변화로 판단
    private val diffThreshold0: Float = 2.0f,
    private val diffThreshold1: Float = 2.0f
) {
    // 각 축의 센서 샘플을 저장할 리스트
    private val samplesAxis0 = mutableListOf<Float>()
    private val samplesAxis1 = mutableListOf<Float>()

    // 이전 윈도우에서 계산된 피크-투-피크 값 (최대-최소)
    private var previousDiff0: Float? = null
    private var previousDiff1: Float? = null

    // 상태 변화가 감지되었을 때 호출할 콜백
    var onStateChanged: (() -> Unit)? = null

    /**
     * 매번 센서 업데이트 시 호출합니다.
     * @param axis0 rotation 센서 0번 인덱스 값 (예: pitch)
     * @param axis1 rotation 센서 1번 인덱스 값 (예: roll)
     */
    fun addSample(axis0: Float, axis1: Float) {
        samplesAxis0.add(axis0)
        samplesAxis1.add(axis1)

        if (samplesAxis0.size >= windowSize) {
            processWindow()
            // 윈도우가 끝나면 다음 주기를 위해 샘플 초기화
            samplesAxis0.clear()
            samplesAxis1.clear()
        }
    }

    // 윈도우 내 최대/최소값을 이용해 피크-투-피크 차이를 계산하고, 이전 값과 비교합니다.
    private fun processWindow() {
        val max0 = samplesAxis0.maxOrNull() ?: return
        val min0 = samplesAxis0.minOrNull() ?: return
        val max1 = samplesAxis1.maxOrNull() ?: return
        val min1 = samplesAxis1.minOrNull() ?: return

        val diff0 = max0 - min0
        val diff1 = max1 - min1

        // 이전 윈도우가 있다면 비교
        if (previousDiff0 != null && previousDiff1 != null) {
            val delta0 = kotlin.math.abs(diff0 - previousDiff0!!)
            val delta1 = kotlin.math.abs(diff1 - previousDiff1!!)

            // 두 축 모두 임계치보다 큰 변화가 있으면 상태 변화로 인식
            if (delta0 > diffThreshold0 && delta1 > diffThreshold1) {
                onStateChanged?.invoke()
                samplesAxis0.clear()
                samplesAxis1.clear()
            }
        }

        // 이번 윈도우의 값을 저장하여 다음 비교에 사용
        previousDiff0 = diff0
        previousDiff1 = diff1
    }
}
