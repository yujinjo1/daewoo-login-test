package com.fifth.maplocationlib.sensors

class GyroscopeResetManager {
    private var lastAngle: Float = -1f
    var stableAngleCount: Int = 0
    companion object {
        private const val REQUIRED_STABLE_COUNT = 10      // 필요한 연속 안정 각도 수
        private const val ANGLE_TOLERANCE = 15.0f         // 복도 방향과의 허용 각도 편차
        private const val CONSECUTIVE_DIFF_TOLERANCE = 10.0f // 연속된 각도 간 허용 편차

        // 복도 영역과 해당 방향 정보를 저장하는 데이터 클래스
        private data class CorridorInfo(
            val xRange: ClosedFloatingPointRange<Float>,
            val yRange: ClosedFloatingPointRange<Float>,
            val direction: Float
        )

        // 복도 정보 리스트
        private val CORRIDOR_INFO = listOf(
            // 나이키 복도
            CorridorInfo(99.0f..171.0f, 2960.0f..3561.0f, 0.0f),
            CorridorInfo(99.0f..171.0f, 2960.0f..3561.0f, 180.0f),

            // 잠바주스 복도
            CorridorInfo(256.0f..501.0f, 3723.0f..3789.0f, 270.0f),
            CorridorInfo(256.0f..501.0f, 3723.0f..3789.0f, 90.0f),

            // 메가박스 복도
            CorridorInfo(778.0f..848.0f, 4432.0f..5189.0f, 0.0f),
            CorridorInfo(778.0f..848.0f, 4432.0f..5189.0f, 180.0f)
        )
    }

    fun checkGyroscopeReset(
        currentPosition: Array<Float>,
        currentAngle: Float,
        currentFloor: Int
    ): Float {
        // 현재 위치가 어떤 복도 영역에 있는지 확인
        val corridorInfo = findCorridorInfo(currentPosition[0], currentPosition[1], currentAngle)

        // 복도를 벗어난 경우 카운트 리셋
        if (corridorInfo == null) {
            resetCount()
            return -1.0f
        }

        // 현재 각도가 복도 방향과 일치하는지 확인
        val corridorAngleDiff = normalizeAngleDifference(currentAngle, corridorInfo.direction)
        if (corridorAngleDiff > ANGLE_TOLERANCE) {
            resetCount()
            return -1.0f
        }

        // 이전 각도가 없는 경우 초기화
        if (lastAngle < 0) {
            lastAngle = currentAngle
            // 현재 각도 저장
            return -1.0f
        }

        // 연속된 각도 간의 변화량 확인
        val consecutiveAngleDiff = normalizeAngleDifference(currentAngle, lastAngle)

        if (consecutiveAngleDiff <= CONSECUTIVE_DIFF_TOLERANCE) {
            stableAngleCount++
            if (stableAngleCount >= REQUIRED_STABLE_COUNT) {
                val resetAngle = corridorInfo.direction
                resetCount()
                return resetAngle
            }
        } else {
            resetCount()
        }

        lastAngle = currentAngle
        return -1.0f
    }

    private fun resetCount() {
        stableAngleCount = 0
        lastAngle = -1f
    }

    // 현재 위치와 각도에 가장 적합한 복도 정보를 찾는 함수
    private fun findCorridorInfo(x: Float, y: Float, currentAngle: Float): CorridorInfo? {
        // 현재 위치에 해당하는 모든 복도 정보를 찾음
        val matchingCorridors = CORRIDOR_INFO.filter { corridor ->
            x in corridor.xRange && y in corridor.yRange
        }

        // 매칭되는 복도가 없으면 null 반환
        if (matchingCorridors.isEmpty()) {
            return null
        }

        // 매칭되는 복도가 하나면 그대로 반환
        if (matchingCorridors.size == 1) {
            return matchingCorridors[0]
        }

        // 여러 개의 복도 중 현재 각도와 가장 가까운 방향을 가진 복도 선택
        return matchingCorridors.minByOrNull { corridor ->
            normalizeAngleDifference(currentAngle, corridor.direction)
        }
    }

    private fun normalizeAngleDifference(angle1: Float, angle2: Float): Float {
        var diff = Math.abs(angle1 - angle2) % 360
        if (diff > 180) diff = 360 - diff
        return diff
    }
}