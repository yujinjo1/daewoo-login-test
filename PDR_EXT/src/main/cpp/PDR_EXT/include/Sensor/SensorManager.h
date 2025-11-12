#ifndef SENSOR_MANAGER_H
#define SENSOR_MANAGER_H

#include <vector>
#include <algorithm>

// 센서 데이터 구조체들
struct AccelerometerData {
    float x, y, z;
    long timestamp;
};

struct LinearAccelerometerData {
    float x, y, z;
    long timestamp;
};

struct RotationVectorData {
    float x, y, z, w;  // quaternion
    long timestamp;
};

struct LightSensorData {
    float lux;
    long timestamp;
};

struct PressureSensorData {
    float pressure;
    long timestamp;
};

struct RotationAngle{
    float pitch;
    float roll;
    float yaw;
};

static RotationAngle g_cachedRotationAngle{0.0, 0.0, 0.0};

// 센서 데이터 콜백 인터페이스
class ISensorDataCallback {
public:
    virtual ~ISensorDataCallback() = default;
    virtual void onAccelerometerData(const AccelerometerData& data) = 0;
    virtual void onLinearAccelerometerData(const LinearAccelerometerData& data) = 0;
    virtual void onRotationVectorData(const RotationVectorData& data) = 0;
    virtual void onLightSensorData(const LightSensorData& data) = 0;
    virtual void onPressureSensorData(const PressureSensorData& data) = 0;
};

class SensorManager {
private:
    // 최신 센서 데이터 저장
    AccelerometerData latestAccelerometer{0, 0, 0, 0};
    LinearAccelerometerData latestLinearAccelerometer{0, 0, 0, 0};
    RotationVectorData latestRotationVector{0, 0, 0, 1, 0}; // w=1 for identity quaternion
    LightSensorData latestLight{0, 0};
    PressureSensorData latestPressure{0, 0};
    RotationAngle latestRotangle{0,0,0};
    // 콜백 리스너들
    std::vector<ISensorDataCallback*> callbacks;

    // 데이터 유효성 플래그
    bool hasAccelerometerData = false;
    bool hasLinearAccelerometerData = false;
    bool hasRotationVectorData = false;
    bool hasLightSensorData = false;
    bool hasPressureData = false;

    // 시간 관련
    static double getCurrentTimestamp();

public:
    SensorManager();
    ~SensorManager();

    // 콜백 등록/해제
    void registerCallback(ISensorDataCallback* callback);
    void unregisterCallback(ISensorDataCallback* callback);

    // === 센서 데이터 입력 함수들 ===
    // 단일 엔트리 포인트: Android SensorEvent 형태를 그대로 반영
    void updateFromSensorEvent(int type, const float* values, int len, long timestamp = 0);
    void updateAccelerometer(float acc[3], long timestamp = 0);
    void updateLinearAccelerometer(float linacc[3], long timestamp = 0);
    void updateRotationVector(float rotV[4], long timestamp = 0);
    void updateLightSensor(float lux, long timestamp = 0);
    void updatePressureSensor(float pressure, long timestamp = 0);

    // === 회전각 함수 ===
    static void getRotationFromQuaternion(RotationVectorData);

    // === 데이터 접근 함수들 ===
    AccelerometerData getLatestAccelerometer() const;
    LinearAccelerometerData getLatestLinearAccelerometer() const;
    RotationVectorData getLatestRotationVector() const;
    LightSensorData getLatestLight() const;
    PressureSensorData getLatestPressure() const;
    RotationAngle getRotangle() const;

    // === 상태 확인 함수들 ===
    bool isAccelerometerReady() const;
    bool isLinearAccelerometerReady() const;
    bool isRotationVectorReady() const;
    bool isLightSensorReady() const;
    bool isPressureSensorReady() const;
    bool isAllSensorsReady() const;

    // === 리셋 함수 ===
    void reset();
};

// 전역 SensorManager 인스턴스 접근 함수
SensorManager* getSensorManager();

#endif // SENSOR_MANAGER_H