#ifndef COORDINATE_TRANSFORM_H
#define COORDINATE_TRANSFORM_H

#include "SensorManager.h"
#include <array>
#include <atomic>
struct EulerAngles {
    double roll;    // X축 회전 (라디안)
    double pitch;   // Y축 회전 (라디안)
    double yaw;     // Z축 회전 (라디안)
    double timestamp;
};

struct TransformedAcceleration {
    double x, y, z;
    double timestamp;
};

class CoordinateTransform {
private:
    // 현재 쿼터니언 (rotation vector에서 계산)
    double quaternion[4] = {0.0, 0.0, 0.0, 1.0}; // x, y, z, w
    std::atomic<bool> isQuaternionValid{false};
    // 현재 오일러 각도
    EulerAngles currentEulerAngles = {0.0, 0.0, 0.0, 0.0};

    // 초기 yaw 오프셋 (방향 보정용)
    double yawOffset = 0.0;
    bool isYawOffsetInitialized = false;

public:
    CoordinateTransform();
    ~CoordinateTransform();

    // === Rotation Vector에서 쿼터니언 업데이트 ===
    void updateFromRotationVector(double x, double y, double z, double w, double timestamp);

    // === SensorManager에서 자동으로 rotation vector 가져와서 업데이트 ===
    void updateFromSensorManager();

    // === 쿼터니언에서 오일러 각도 계산 ===
    void calculateEulerAngles(double timestamp);

    // === 글로벌 좌표계로 가속도 변환 ===
    TransformedAcceleration transformToGlobal(double localX, double localY, double localZ, double timestamp);

    // === SensorManager에서 선형가속도 가져와서 변환 ===
    TransformedAcceleration transformLinearAccelerationFromSensorManager();

    // === 특정 축만 변환 (StepDetection에서 Z축만 필요할 때) ===
    double transformAxis(char axis, double localX, double localY, double localZ);

    // === 접근자 함수들 ===
    EulerAngles getCurrentEulerAngles() const;
    double getRoll() const;
    double getPitch() const;
    double getYaw() const;

    // 각도를 도(degree) 단위로 반환
    double getRollDegrees() const;
    double getPitchDegrees() const;
    double getYawDegrees() const;

    std::array<double, 4> getQuaternion() const;
    bool isReady() const;

    // === 리셋 함수 ===
    void reset();

    // === 수동 yaw 오프셋 설정 (캘리브레이션용) ===
    void setYawOffset(double offsetRadians);
    void calibrateYaw();
};

inline CoordinateTransform* getSharedCoordinateTransform() {
    static CoordinateTransform instance;
    return &instance;
}

#endif // COORDINATE_TRANSFORM_H