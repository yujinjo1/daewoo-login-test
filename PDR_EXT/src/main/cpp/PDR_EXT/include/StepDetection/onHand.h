#ifndef ONHAND_STEP_DETECTION_H
#define ONHAND_STEP_DETECTION_H

#include <array>
#include <deque>
#include <cstdint>

#include "PDRresult.h"
#include "Filter/MovingAverage.h"
#include "Filter/AdaptiveFilter.h"

// Forward declaration
class CoordinateTransform;

class OnHandStepDetection {
public:
    OnHandStepDetection();
    OnHandStepDetection(float alphaSlow, float alphaFast, float thresholdDeg);

    // inPocket과 동일한 인터페이스로 변경
    // Z축 가속도는 내부에서 SensorManager를 통해 직접 가져옴
    bool isStep(const std::array<float,3>& rotangle,
                const std::deque<float>& stepQueue,
                int64_t currentTimeMillis,
                int statetmp);

    PDR getStatus() const;

    // 디버깅용 상태 구조체
    struct DetectionState {
        bool isUpPeak;
        bool isDownPeak;
        bool isStepFinished;
        double maxAccZ;
        double minAccZ;
        double timePeak2Peak;  // peak-to-peak 시간
    };

    DetectionState getCurrentState() const;

private:
    // 좌표 변환 객체
    CoordinateTransform* coordinateTransform;

    // 이동평균 필터 (MetaPDR와 동일하게 5개 샘플)
    MovingAverage movingAvgAccZ;
    MovingAverage movingAvgLinAccZ;

    float filteredYaw;
    int state;

    // 스텝 카운트 및 시간
    int totalStepCount;
    int64_t lastStepTime;
    int64_t minStepIntervalMs;
    double stepLength;

    // OnHand 고유의 스텝 검출 상태
    bool isUpPeak;
    bool isDownPeak;
    bool isStepFinished;
    double maxAccZ;
    double minAccZ;

    // 시간 추적 변수 (MetaPDR 방식)
    double upPeakTime;
    double downPeakTime;
    double previousUpPeakTime;
    double currentUpPeakToUpPeakTime;  // Up peak 간 시간 간격
    bool isFirstStep;

    // 임계값 상수들 (const 멤버 변수로 선언)
    const double UP_PEAK_THRESHOLD;
    const double DOWN_PEAK_THRESHOLD;
    const double MIN_Z_DIFF_THRESHOLD;
    const double MIN_PEAK2PEAK_MS;
    const double MAX_PEAK2PEAK_MS;

    // 어댑티브 헤딩(또는 Z 성분) 필터 및 임계값 (state별 튜닝용)
    AdaptiveFilter yawFilter;      // alphaSlow/alphaFast 기반 필터

    void reset();
};

#endif // ONHAND_STEP_DETECTION_H