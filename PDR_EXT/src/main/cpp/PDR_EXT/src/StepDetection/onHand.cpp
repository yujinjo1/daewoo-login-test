#include "StepDetection/onHand.h"
#include "Sensor/CoordinateTransform.h"
#include "Sensor/SensorManager.h"
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstdint>
#include "PDRresult.h"

#define LOG_TAG "OnHandStepDetection"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {
    static inline int64_t now_ms() {
        using namespace std::chrono;
        return duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    }
} // anonymous

extern SensorManager* getSensorManager();

OnHandStepDetection::OnHandStepDetection()
        : coordinateTransform(getSharedCoordinateTransform()),
          movingAvgAccZ(5),
          movingAvgLinAccZ(5),
          totalStepCount(0),
          lastStepTime(0),
          minStepIntervalMs(300),  // 300ms로 증가 (MetaPDR 기본값)
          stepLength(0.65),
          isUpPeak(false),
          isDownPeak(false),
          isStepFinished(false),
          maxAccZ(0.0),
          minAccZ(0.0),
          upPeakTime(0.0),
          downPeakTime(0.0),
          previousUpPeakTime(0.0),
          currentUpPeakToUpPeakTime(0.0),
          isFirstStep(true),
        // 임계값 강화
          UP_PEAK_THRESHOLD(1.2),      // 0.8 → 1.2
          DOWN_PEAK_THRESHOLD(-1.0),   // -0.8 → -1.0
          MIN_Z_DIFF_THRESHOLD(2.0),   // Z축 차이 최소값
          MIN_PEAK2PEAK_MS(250),       // 최소 peak-to-peak 시간
          MAX_PEAK2PEAK_MS(1500),
          yawFilter(0.07f, 0.8f, 20.0f),
          state(0)
{}

OnHandStepDetection::OnHandStepDetection(float alphaSlow, float alphaFast, float thresholdDeg)
        : coordinateTransform(getSharedCoordinateTransform()),
          movingAvgAccZ(5),
          movingAvgLinAccZ(5),
          totalStepCount(0),
          lastStepTime(0),
          minStepIntervalMs(300),
          stepLength(0.65),
          isUpPeak(false),
          isDownPeak(false),
          isStepFinished(false),
          maxAccZ(0.0),
          minAccZ(0.0),
          upPeakTime(0.0),
          downPeakTime(0.0),
          previousUpPeakTime(0.0),
          currentUpPeakToUpPeakTime(0.0),
          isFirstStep(true),
          UP_PEAK_THRESHOLD(1.2),
          DOWN_PEAK_THRESHOLD(-1.0),
          MIN_Z_DIFF_THRESHOLD(2.0),
          MIN_PEAK2PEAK_MS(250),
          MAX_PEAK2PEAK_MS(1500),
          filteredYaw(0),
          yawFilter(alphaSlow, alphaFast, thresholdDeg),
          state(0)
{}

bool OnHandStepDetection::isStep(const std::array<float,3>& rotangle,
                                 const std::deque<float>& stepQueue,
                                 int64_t currentTimeMillis,
                                 int statetmp)
{
    (void)rotangle;

    if (currentTimeMillis < 0) currentTimeMillis = now_ms();

    if (!stepQueue.empty()) {
        stepLength = static_cast<double>(stepQueue.front());
    }

    double currentTime = static_cast<double>(currentTimeMillis);
    state = statetmp;
    filteredYaw = yawFilter.update(rotangle[2]);

    // 센서 데이터 가져오기
    SensorManager* sm = getSensorManager();
//    LOGD("[isStep] CT=%p, SM=%p", coordinateTransform, sm);

    if (!sm) {
        LOGD("SensorManager를 가져올 수 없음");
        return false;
    }

    if (sm->isRotationVectorReady()) {
//        LOGD("[isStep] RVready=1 → updateFromSensorManager() CT=%p", coordinateTransform);
        coordinateTransform->updateFromSensorManager(); // 내부에서 isReady() 로그도 출력됨
    } else {
//        LOGD("[isStep] RVready=0 (skip update) CT=%p", coordinateTransform);
    }

    // 선형가속도 처리
    double transformedZ = 0.0;
    if (sm->isLinearAccelerometerReady()) {
        auto linAccData = sm->getLatestLinearAccelerometer();
        TransformedAcceleration globalAcc = coordinateTransform->transformToGlobal(
                linAccData.x, linAccData.y, linAccData.z, currentTime
        );
        movingAvgLinAccZ.newData(static_cast<float>(globalAcc.z));
        transformedZ = movingAvgLinAccZ.getAvg();
    } else if (sm->isAccelerometerReady()) {
        auto accData = sm->getLatestAccelerometer();
        TransformedAcceleration globalAcc = coordinateTransform->transformToGlobal(
                accData.x, accData.y, accData.z - 9.8, currentTime
        );
        movingAvgAccZ.newData(static_cast<float>(globalAcc.z));
        transformedZ = movingAvgAccZ.getAvg();
    }

//    LOGD("[StepDetection] time=%.1f, transformedZ=%.3f, state: up=%d, down=%d, finished=%d",
//         currentTime, transformedZ, isUpPeak, isDownPeak, isStepFinished);

    // === 수정된 스텝 검출 알고리즘 ===

    // 1) Up Peak 감지 (더 엄격한 조건)
    if (!isUpPeak && !isDownPeak && !isStepFinished && transformedZ > UP_PEAK_THRESHOLD) {
        if (transformedZ < maxAccZ) {
            // Up peak 확정 - 하강 시작
            isUpPeak = true;
            upPeakTime = currentTime;
            maxAccZ = 0.0;  // 다음 peak를 위해 리셋
//            LOGD("✅ Up Peak 확정 - zAcc: %.3f, time: %.1f", transformedZ, currentTime);
        } else {
            // 계속 상승 중
            maxAccZ = transformedZ;
        }
    }

        // 2) Up Peak 이후 상태 처리
    else if (isUpPeak && !isDownPeak && !isStepFinished) {
        if (transformedZ > maxAccZ) {
            // Up peak 값이 더 높아짐 - 업데이트하지만 시간은 유지
            maxAccZ = transformedZ;
        } else if (transformedZ < DOWN_PEAK_THRESHOLD) {
            if (transformedZ > minAccZ) {
                // Down peak 확정
                isDownPeak = true;
                downPeakTime = currentTime;
                LOGD("✅ Down Peak 확정 - zAcc: %.3f, time: %.1f", transformedZ, currentTime);
            } else {
                // 계속 하강 중
                minAccZ = transformedZ;
            }
        }
    }

        // 3) Down Peak 이후 Step 완료 감지
    else if (isUpPeak && isDownPeak && !isStepFinished) {
        if (transformedZ < minAccZ) {
            // Down peak 값이 더 낮아짐
            minAccZ = transformedZ;
            downPeakTime = currentTime;
        } else if (transformedZ >= 0.0) {
            // 0 이상으로 올라오면 Step 완료
            isStepFinished = true;
            LOGD("✅ Step 완료 감지 - zAcc: %.3f", transformedZ);
        }
    }

    // 4) Step 완료 처리
    if (isUpPeak && isDownPeak && isStepFinished) {
        bool validStep = false;

        // 수정된 Peak-to-Peak 시간 계산
        double timePeak2Peak;
        if (isFirstStep) {
            // 첫 번째 스텝: down-up 시간을 임시로 사용
            timePeak2Peak = downPeakTime - upPeakTime;
            isFirstStep = false;
//            LOGD("첫 번째 스텝 - peak2peak: %.1fms (down-up)", timePeak2Peak);
        } else {
            // 두 번째 스텝부터: 이전 up peak와 현재 up peak 사이의 시간
            if (previousUpPeakTime > 0.0) {
                timePeak2Peak = upPeakTime - previousUpPeakTime;
            } else {
                timePeak2Peak = downPeakTime - upPeakTime;
            }
//            LOGD("Up-to-Up 간격: %.1fms (이전: %.1f → 현재: %.1f)",
//                 timePeak2Peak, previousUpPeakTime, upPeakTime);
        }

        // Z축 차이 계산
        double zAccDifference = maxAccZ - minAccZ;

        // 유효성 검사
        bool isValidPeakToPeak = (timePeak2Peak >= MIN_PEAK2PEAK_MS && timePeak2Peak <= MAX_PEAK2PEAK_MS);
        bool isValidZDiff = (zAccDifference >= MIN_Z_DIFF_THRESHOLD);
        const int64_t timeSinceLastStep = currentTimeMillis - lastStepTime;
        bool isValidInterval = (lastStepTime == 0 || timeSinceLastStep >= minStepIntervalMs);

//        LOGD("유효성 검사 - P2P: %.1fms(%s), zDiff: %.3f(%s), interval: %lldms(%s)",
//             timePeak2Peak, isValidPeakToPeak ? "OK" : "NG",
//             zAccDifference, isValidZDiff ? "OK" : "NG",
//             timeSinceLastStep, isValidInterval ? "OK" : "NG");

        if (isValidPeakToPeak && isValidZDiff && isValidInterval) {
            validStep = true;
            ++totalStepCount;
            lastStepTime = currentTimeMillis;

//            LOGD("🚶‍♂️ 유효한 스텝 감지! maxZ: %.3f, minZ: %.3f, zDiff: %.3f, peak2peak: %.1fms, interval: %lldms, totalSteps: %d",
//                 maxAccZ, minAccZ, zAccDifference, timePeak2Peak, timeSinceLastStep, totalStepCount);
        } else {
//            LOGD("❌ 스텝 조건 불만족 - 무시");
        }

        // 이전 Up Peak 시간 업데이트 (다음 스텝 계산을 위해)
        previousUpPeakTime = upPeakTime;

        // 상태 리셋
        reset();

        return validStep;
    }

    return false;
}

PDR OnHandStepDetection::getStatus() const {
    PDR out{};

    bool rvReady = (coordinateTransform && coordinateTransform->isReady());



    if (state == 22) {
        double dir = std::fmod(-static_cast<double>(filteredYaw) + 360.0, 360.0);
        out.direction = dir;
    } else if (rvReady) {
        double yawDegrees = coordinateTransform->getYawDegrees();
        while (yawDegrees < 0) yawDegrees += 360.0;
        while (yawDegrees >= 360.0) yawDegrees -= 360.0;
        out.direction = yawDegrees;
    } else {
        // fallback
        double dir = std::fmod(-static_cast<double>(filteredYaw) + 360.0, 360.0);
        out.direction = dir;
    }

    out.totalStepCount = totalStepCount;
    out.stepLength = stepLength;
    // 누적 보폭거리 있으면 out.totalStepLength 채워 넣기
//    LOGD("[getStatus] state=%d, totalStepCount=%d, stepLength=%.3f, filteredYaw=%.2f, RVready=%d",
//         state, totalStepCount, stepLength, filteredYaw, rvReady ? 1 : 0);
    return out;
}

void OnHandStepDetection::reset() {
    isUpPeak = false;
    isDownPeak = false;
    isStepFinished = false;
    maxAccZ = 0.0;
    minAccZ = 0.0;
}

