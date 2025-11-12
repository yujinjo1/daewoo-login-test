# 헤더 루트 (현재 CMakeLists.txt는 src/에 있으므로 상위의 include/를 가리킴)
cmake_minimum_required(VERSION 3.22.1)


project("PDR_EXT")


set(JSON_INCLUDE_DIR ../include)

# C++ 표준 지정
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
        PDR_EXT  # 라이브러리 이름을 모듈 이름과 일치시킴
        SHARED
        PDRStateManager.cpp
        Filter/AdaptiveFilter.cpp
        Filter/MovingAverage.cpp
        Sensor/CoordinateTransform.cpp
        Sensor/SensorManager.cpp
        StepDetection/handSwing.cpp
        StepDetection/inPocket.cpp
        StepDetection/onhand.cpp
        StepLength/StepLength.cpp
        StepLength/SLRequire.cpp
)

# 타겟에 헤더 경로 지정 (PUBLIC: 외부에서도 이 타겟의 헤더를 사용할 수 있음)
target_include_directories(PDR_EXT PUBLIC ${JSON_INCLUDE_DIR})

target_link_libraries(
        PDR_EXT
        android
        log
)