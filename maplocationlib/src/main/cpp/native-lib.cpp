#include <iostream>
#include <fstream>
#include <sstream>
#include <vector>
#include <deque>
#include <cmath>
#include <limits>
#include <algorithm>
#include <string>
#include <cstdlib>
#include <cstdint>
#include <cassert>
#include <omp.h>         // OpenMP 헤더
#include <nlohmann/json.hpp>
#include <jni.h>
#include <regex>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <queue>



#define LOG_TAG "NativeLib"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// 전역 변수로 AAssetManager 포인터를 저장합니다.
AAssetManager* g_assetManager = nullptr;

std::string nativeMessage = "";

extern "C"
JNIEXPORT jstring JNICALL
Java_com_fifth_maplocationlib_NativeLib_getStringFromNative(JNIEnv* env, jobject /* this */) {
    return env->NewStringUTF(nativeMessage.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fifth_maplocationlib_NativeLib_setAssetManager(JNIEnv *env, jobject /* this */, jobject assetManager) {
    g_assetManager = AAssetManager_fromJava(env, assetManager);
}

using json = nlohmann::json;
using namespace std;

std::map<int, std::pair<int, int>> originMap;

json loadJsonData(const char* filename) {
    AAsset* asset = AAssetManager_open(g_assetManager, filename, AASSET_MODE_BUFFER);
    if (!asset) {
        cerr << "Asset open failed: " << filename << endl;
        return json(); // 빈 json 객체 반환
    }
    off_t length = AAsset_getLength(asset);
    string content;
    content.resize(length);
    AAsset_read(asset, &content[0], length);
    AAsset_close(asset);

    try {
        return json::parse(content);
    } catch (json::parse_error& e) {
        cerr << "JSON parse error in file " << filename << ": " << e.what() << endl;
        return json(); // 빈 json 객체 반환
    }
}


// Loaded at initializeEngine() from assets (testbed/enb/...)
json stairsInfo;      // enb/floorchange_stairs_areas.json
json elevatorInfo;    // enb/floorchange_elevator_areas.json

using Point = pair<int, int>;
int gStepCount = 0;


// 전역 변수: 수렴 여부 및 마지막 매칭 결과 (PDR 업데이트에 사용)
bool didConverge = false;
pair<double, double> cur_pos_global; // (x, y)
double cur_distance; // (x, y)

// =============================================================
/// 지도 데이터 관련 구조체 및 함수
// =============================================================

struct MapPoint {
    double x, y;
    MapPoint(double x = 0, double y = 0) : x(x), y(y) {}
};

struct Node {
    string id;
    MapPoint coords;
};

struct Edge {
    string start;
    string end;
    double distance;
};

struct MapData {
    vector<Node> nodes;
    vector<Edge> edges;
};

MapPoint operator-(const MapPoint& a, const MapPoint& b) {
    return MapPoint(a.x - b.x, a.y - b.y);
}

double dot(const MapPoint& a, const MapPoint& b) {
    return a.x * b.x + a.y * b.y;
}

double magnitude(const MapPoint& p) {
    return sqrt(p.x * p.x + p.y * p.y);
}

MapData parseJsonToMapData(const string& jsonString) {
    MapData mapData;
    try {
        json j = json::parse(jsonString);
        for (const auto& node : j["nodes"]) {
            Node n;
            n.id = node["id"];
            n.coords.x = node["coords"][0];
            n.coords.y = node["coords"][1];
            mapData.nodes.push_back(n);
        }
        for (const auto& edge : j["edges"]) {
            Edge e;
            e.start = edge["start"];
            e.end = edge["end"];
            e.distance = edge["distance"];
            mapData.edges.push_back(e);
        }
    } catch (json::parse_error& e) {
        cerr << "JSON parse error: " << e.what() << endl;
    } catch (exception& e) {
        cerr << "Error parsing JSON: " << e.what() << endl;
    }
    return mapData;
}

MapPoint find_closest_point_on_edge(const MapPoint& p, const MapPoint& a, const MapPoint& b) {
    MapPoint ap = p - a;
    MapPoint ab = b - a;
    double ab2 = dot(ab, ab);
    double ap_ab = dot(ap, ab);
    double t = ap_ab / ab2;
    if(t < 0.0) return a;
    else if(t > 1.0) return b;
    else return MapPoint(a.x + ab.x * t, a.y + ab.y * t);
}

MapPoint getMapMatchingResult(const MapPoint& current_position, const MapData& data) {
    double min_distance = numeric_limits<double>::infinity();
    MapPoint closest_point;
    for (const auto& edge : data.edges) {
        auto start_node = find_if(data.nodes.begin(), data.nodes.end(),
                                  [&](const Node& node) { return node.id == edge.start; });
        auto end_node = find_if(data.nodes.begin(), data.nodes.end(),
                                [&](const Node& node) { return node.id == edge.end; });
        if (start_node != data.nodes.end() && end_node != data.nodes.end()) {
            MapPoint point = find_closest_point_on_edge(current_position, start_node->coords, end_node->coords);
            double distance = magnitude(point - current_position);
            if (distance < min_distance) {
                min_distance = distance;
                closest_point = point;
            }
        }
    }
    return closest_point;
}

// =============================================================
/// BitMatrix 클래스: 0/1 데이터를 64비트 단위로 저장
// =============================================================

class BitMatrix {
public:
    int height, width, blocksPerRow;
    vector<vector<uint64_t>> rows;
    BitMatrix(int h, int w) : height(h), width(w) {
        blocksPerRow = (w + 63) / 64;
        rows.assign(h, vector<uint64_t>(blocksPerRow, 0ULL));
    }
    void setBit(int r, int c, bool value) {
        int block = c / 64;
        int bit = c % 64;
        if (value)
            rows[r][block] |= (1ULL << bit);
        else
            rows[r][block] &= ~(1ULL << bit);
    }
    bool getBit(int r, int c) const {
        try {
            int block = c / 64;
            int bit = c % 64;
            return (rows[r][block] >> bit) & 1ULL;
        }
        catch(...) {
            return false;
        }
    }
};

// =============================================================
/// 비트 연산 함수들
// =============================================================

// 지정된 행(row)의 colStart부터 bits 길이 만큼의 비트를 추출
uint64_t extract_bits_from_row(const vector<uint64_t>& row, int colStart, int bits) {
    int blockIndex = colStart / 64;
    int offset = colStart % 64;
    if (offset + bits <= 64) {
        return (row[blockIndex] >> offset) & ((1ULL << bits) - 1);
    } else {
        int bits_in_first = 64 - offset;
        uint64_t part1 = row[blockIndex] >> offset;
        int remaining = bits - bits_in_first;
        uint64_t part2 = row[blockIndex + 1] & ((1ULL << remaining) - 1);
        return part1 | (part2 << bits_in_first);
    }
}

// 두 BitMatrix(B: 큰 지도, T: 테스트 패턴)의 겹치는 영역에 대해
// bitwise AND 후 popcount를 이용하여 유사도를 계산
int calc_similarity_at_position(const BitMatrix &B, int i, int j, const BitMatrix &T) {
    int similarity = 0;
#pragma omp parallel for reduction(+: similarity) schedule(guided)
    for (int r = 0; r < T.height; r++) {
        for (int b = 0; b < T.blocksPerRow; b++) {
            int colStart = j + b * 64;
            int bits_in_block = 64;
            if (b == T.blocksPerRow - 1) {
                int rem = T.width % 64;
                if (rem != 0) bits_in_block = rem;
            }
            uint64_t B_block = extract_bits_from_row(B.rows[i + r], colStart, bits_in_block);
            uint64_t T_block = T.rows[r][b];
            similarity += __builtin_popcountll(B_block & T_block);
        }
    }
    return similarity;
}

// =============================================================
/// ROI와 OpenMP를 활용한 병렬 슬라이딩 윈도우 매칭 함수
// =============================================================

pair<int, vector<Point>> sliding_window_similarity_optimized(
        const BitMatrix &B, const BitMatrix &T,
        int roi_center_row = -1, int roi_center_col = -1, int roi_radius = -1,
        const Point &T_anchor = {0, 0}  // 새로 추가: T의 기준점 (예: T의 첫 점)
) {
//    cout << "roi_radius : " << roi_radius << " / " << "roi_center : (" << roi_center_row << ", " << roi_center_col << ")" << endl;
//    cout << "bitMatrix : " << T.height << " x " << T.width << " / " << B.height << " x " << B.width << endl;
    int start_row = 0, end_row = B.height - T.height;
    int start_col = 0, end_col = B.width - T.width;
    if (roi_center_row >= 0 && roi_center_col >= 0 && roi_radius >= 0) {
        start_row = max(0, roi_center_row - roi_radius - T_anchor.first);
        end_row = min(B.height - T.height, roi_center_row + roi_radius - T_anchor.first);
        start_col = max(0, roi_center_col - roi_radius - T_anchor.second);
        end_col = min(B.width - T.width, roi_center_col + roi_radius - T_anchor.second);
    }

    int global_max_similarity = -1;
    vector<Point> global_positions;

    int skip_num = 3;
#pragma omp parallel
    {
        int local_max = -1;
        vector<Point> local_positions;
#pragma omp for nowait schedule(dynamic, 16)
        for (int i = start_row; i <= end_row; i++) {
            if (i % skip_num != 0) continue;
            for (int j = start_col; j <= end_col; j++) {
                if (j % skip_num != 0) continue;
                if (!B.getBit(i + T_anchor.first, j + T_anchor.second)) continue;

                int sim = calc_similarity_at_position(B, i, j, T);
                if (sim > local_max) {
                    local_max = sim;
                    local_positions.clear();
                    local_positions.push_back({i, j});
                } else if (sim == local_max) {
                    local_positions.push_back({i, j});
                }
            }
        }
#pragma omp critical
        {
            if (local_max > global_max_similarity) {
                global_max_similarity = local_max;
                global_positions = local_positions;
            } else if (local_max == global_max_similarity) {
                global_positions.insert(global_positions.end(), local_positions.begin(), local_positions.end());
            }
        }
    }
    return {global_max_similarity, global_positions};
}


// =============================================================
/// BitMatrix 생성: 좌표 목록으로부터 BitMatrix 생성
// =============================================================

BitMatrix create_bitmatrix_from_coordinates(const vector<Point>& coordinates) {
    int max_x = 0, max_y = 0;
    for (const auto& coord : coordinates) {
        max_x = max(max_x, coord.first);
        max_y = max(max_y, coord.second);
    }
    BitMatrix bm(max_x + 1, max_y + 1);
    for (const auto& coord : coordinates) {
        bm.setBit(coord.first, coord.second, true);
    }
    return bm;
}

pair<double, Point> analyzeCoordinates(const vector<Point>& coordinates) {
    if (coordinates.empty()) return {0.0, {0, 0}};
    long long sum_x = 0, sum_y = 0;
    for (const auto& coord : coordinates) {
        sum_x += coord.first;
        sum_y += coord.second;
    }
    double mean_x = static_cast<double>(sum_x) / coordinates.size();
    double mean_y = static_cast<double>(sum_y) / coordinates.size();
    double total_distance = 0.0;
    for (const auto& coord : coordinates) {
        double dx = coord.first - mean_x;
        double dy = coord.second - mean_y;
        total_distance += sqrt(dx * dx + dy * dy);
    }
    double mean_distance = (total_distance / coordinates.size()) * 0.1;
    return {mean_distance, {static_cast<int>(mean_x), static_cast<int>(mean_y)}};
}

// =============================================================
/// GyroBuffer 클래스
// =============================================================

class GyroBuffer {
private:
    deque<double> buffer;
    const size_t maxSize = 6;
public:
    void add(double gyroValue) {
        if (buffer.size() == maxSize) {
            buffer.pop_front();
        }
        buffer.push_back(gyroValue);
    }
    bool didBigRotation() {
        if (buffer.size() < 6) return false;
        double diff = fabs(buffer.front() - buffer.back());
        diff = fmod(diff, 360.0);
        if (diff > 180) diff = 360 - diff;
        return diff > 70;
    }
};

// =============================================================
/// PositionTracker 클래스 (BitMatrix, ROI, OpenMP 최적화 적용)
// =============================================================
struct ProcessResult {
    double x;
    double y;
    double distance;
    bool valid;
    int gyroCaliValue;
};

// 텍스트 파일로부터 2D int 벡터 파싱
vector<vector<int>> parseMatrixFromText(const char* data, size_t length) {
    vector<vector<int>> matrix;
    string text(data, length);
    istringstream stream(text);
    string line;
    while (getline(stream, line)) {
        vector<int> row;
        istringstream lineStream(line);
        int value;
        while (lineStream >> value) {
            row.push_back(value);
        }
        if (!row.empty()) {
            matrix.push_back(row);
        }
    }
    return matrix;
}

// 2D int 벡터를 BitMatrix로 변환
BitMatrix convertToBitMatrix(const vector<vector<int>>& mat2D) {
    if (mat2D.empty()) return BitMatrix(0,0);
    int rows = mat2D.size();
    int cols = 0;
    for (const auto& row : mat2D) {
        cols = max(cols, (int)row.size());
    }
    BitMatrix bm(rows, cols);
    for (int i = 0; i < rows; i++) {
        for (int j = 0; j < mat2D[i].size(); j++) {
            if (mat2D[i][j] == 1)
                bm.setBit(i, j, true);
        }
    }
    return bm;
}

BitMatrix readBitMatrixFromTextFile(const char* filename) {
    // assets에서 파일 열기
    AAsset* asset = AAssetManager_open(g_assetManager, filename, AASSET_MODE_UNKNOWN);
    if (!asset) {
        cerr << "Asset open failed: " << filename << endl;
        return BitMatrix(0, 0);
    }
    off_t length = AAsset_getLength(asset);
    string content;
    content.resize(length);
    AAsset_read(asset, &content[0], length);
    AAsset_close(asset);

    // 기존 코드와 동일하게 텍스트 내용을 파싱합니다.
    vector<vector<int>> tempMatrix;
    istringstream fileStream(content);
    string line;
    while (getline(fileStream, line)) {
        istringstream iss(line);
        vector<int> row;
        int value;
        while (iss >> value) {
            row.push_back(value);
        }
        if (!row.empty())
            tempMatrix.push_back(row);
    }
    int height = tempMatrix.size();
    int width = 0;
    for (const auto& row : tempMatrix) {
        width = max(width, (int)row.size());
    }
    BitMatrix bm(height, width);
    for (int i = 0; i < height; i++) {
        for (int j = 0; j < tempMatrix[i].size(); j++) {
            if (tempMatrix[i][j] == 1)
                bm.setBit(i, j, true);
        }
    }
    return bm;
}

// assets에서 JSON 파일을 읽어 MapData 생성 (기존 ifstream 코드와 유사)
MapData loadMapData(const char* filename) {
    AAsset* asset = AAssetManager_open(g_assetManager, filename, AASSET_MODE_UNKNOWN);
    if (!asset) {
        cerr << "Asset open failed: " << filename << endl;
        return MapData();
    }
    off_t length = AAsset_getLength(asset);
    string content;
    content.resize(length);
    AAsset_read(asset, &content[0], length);
    AAsset_close(asset);

    json j;
    try {
        j = json::parse(content);
    } catch (json::parse_error& e) {
        cerr << "JSON parse error: " << e.what() << endl;
        return MapData();
    }
    MapData data;
    for (const auto& node : j["nodes"]) {
        data.nodes.push_back({
                                     node["id"],
                                     MapPoint(node["coords"][0], node["coords"][1])
                             });
    }
    for (const auto& edge : j["edges"]) {
        data.edges.push_back({
                                     edge["start"],
                                     edge["end"],
                                     edge["distance"]
                             });
    }
    return data;
}

// =============================================================
/// PositionTracker 클래스 (BitMatrix, ROI, OpenMP 최적화 적용)
// =============================================================

class PositionTracker {
private:
    // 가장 좋은 각도 추적용 변수
    int consecutive_best_count = 0;  // 연속으로 best가 된 횟수
    int last_best_idx = -1;         // 이전 best 인덱스
    bool fine_tuning_mode = false;  // 미세 조정 모드 활성화 여부
    bool fine_tuning_complete_mode = false;  // 미세 조정 모드 활성화 여부
    double fine_tuning_base_angle = 0.0; // 미세 조정 기준 각도

    vector<vector<Point>> multiple_history_paths;
    vector<Point> multiple_cur_pos;
    vector<bool> active_paths; // 활성화된 경로 추적

    // PositionTracker 클래스 내부 (private 멤버 변수 부분)
    double prevDiffCompassGyro = -1;     // 이전 step의 나침반-자이로 차이 (없으면 -1)
    int consecutiveStableCount = 0;      // diffCompassGyroStepByStep 값이 20 미만인 연속 횟수

    int cur_max_similarity = 0;
    int very_max_similarity = 0;
    bool reSearchFlag = false;
    int reSearchRoi = 100;
    BitMatrix binary_map;
    Point cur_pos;
    vector<Point> history_of_pos;
    GyroBuffer gyroBuffer;
    MapData map_data;

    bool has_last_matched;
    int roi_radius =  3000; // ROI 반경 (셀 단위)

    // 현재 층 관리 변수 (생성자에서 start_floor로 초기화)
    int currentFloor;

    // 계단 관련 상태
    bool onStairs = false;
    float lastGyro = 0.0f;
    Point roi_center; // 새 층 도착 시 설정된 ROI center

    vector<Point> storedStairCoords; // 계단 좌표들을 저장
    vector<Point> storedElevatorCoords; // 계단 좌표들을 저장

    bool newFloorMode = false;          // 새 층 모드 활성화 여부
    Point newFloorArrivalCoord = {-1, -1}; // 도착한 층의 계단 도착 좌표
    string prevFloorElevation = "-";

    int previousFloor = -9;

    bool gyroCalibrationDone = false;
    int finalGyroCaliValue = 0;  // 최종 보정값 (0, 90, 180, 270 중 하나)
    vector<int> gyroCaliRankHistory;  // 최근 10회의 1등 후보 기록
    vector<double> prevDiffs;         // 이전 스텝의 각 후보별 차이 값 (크기 4)

    // 후보 각도 배열 (상수로 두어도 됨)
    const vector<int> gyroCaliCandidate = {0, 45, 90, 135, 180, 225, 270, 315};
//    const vector<int> gyroCaliCandidate = {0, 0, 90, 90, 180, 180, 270, 270};
//    const vector<int> gyroCaliCandidate = {0, 0, 0, 0, 0, 0, 0, 0};

    // 캘리브레이션 진행 동안의 센서값 누적 (나중에 보정 적용)
    vector<float> pendingGyros;
    vector<float> pendingStepLengths;

    // 추가: testbed 경로 저장
    std::string testbedPath;
public:
    // 생성자: start_floor를 추가로 받아, 해당 층의 지도 데이터를 사용하도록 함
    PositionTracker(const BitMatrix& binMap, const MapData& mapData, int start_floor, const std::string& testbedDir)
            : binary_map(binMap), map_data(mapData), cur_pos({0, 0}),
              has_last_matched(false), currentFloor(start_floor), testbedPath(testbedDir)
    {
        cout << "Initialized tracker at floor " << currentFloor << endl;
    }

    // Public accessor (map_data 접근용)
    const MapData& getMapData() const {
        return map_data;
    }

    int getCurrentFloor() const {
        return currentFloor;
    }

    void setCurrentFloor(int floor) {
        currentFloor = floor;
    }

    void updateMaps(const BitMatrix &newBinaryMap, const MapData &newMapData) {
        binary_map = newBinaryMap;
        map_data = newMapData;
    }

    void resetHistory() {
        // 기본 경로 초기화
        history_of_pos.clear();
        cur_pos = {0, 0};


        multiple_history_paths.clear();
        multiple_cur_pos.clear();
        pendingGyros.clear();
        pendingStepLengths.clear();

        // 미세 조정 모드 초기화
//        fine_tuning_mode = false;
        consecutive_best_count = 0;
        last_best_idx = -1;

        // 모든 경로 활성화
        fill(active_paths.begin(), active_paths.end(), true);
    }

    void reSearchStart(pair<double, double> roi_center_pos, int research_roi) {
        if (cur_distance < 10.0) {
            reSearchFlag = true;
            if (roi_center_pos != make_pair(-1.0, -1.0)) {
                roi_center = roi_center_pos;
                reSearchRoi = research_roi;
            }
            resetHistory();
            __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "reSearchStart! roi_center : (%d, %d)", roi_center.first, roi_center.second);
        }
    }

    void resetGyroCalibration() {

        gyroCalibrationDone = false;
        finalGyroCaliValue = 0;
        gyroCaliRankHistory.clear();
        prevDiffs.clear();
        pendingGyros.clear();
        pendingStepLengths.clear();
        consecutive_best_count = 0;
        last_best_idx = -1;
        fine_tuning_mode = false;
        fine_tuning_complete_mode = false;

    }


    // floorElevation 센서값만으로 계단/층 전환을 관리하도록 수정
    void setArrivedInfo(float gyro, int elevationMode) {
        string stairElevation = "";
        if (currentFloor != previousFloor) {
            storedStairCoords.clear();
            storedElevatorCoords.clear();

            if (elevationMode == 2) {  // elevationMode 2는 엘리베이터
                // elevatorInfo에서 값을 가져오는 부분 수정
                int roundedGyro = static_cast<int>(round(gyro));
                json coords = elevatorInfo[to_string(roundedGyro)];
                if (!coords.empty()) {
                    for (auto& pt : coords) {
                        storedElevatorCoords.push_back({ pt[0], pt[1] });
                    }
                }
            }
            else {  // elevationMode 0은 계단
                stairElevation = (currentFloor > previousFloor) ? "상승" : "하강";
                // 4층 이상일 때는 "basic"을 사용, 그 외에는 해당 층수를 사용
                string floorKey = (previousFloor >= 4) ? "basic" : to_string(previousFloor);
                if (stairsInfo.contains(floorKey) &&
                    stairsInfo[floorKey].contains(stairElevation)) {
                    json modeInfo = stairsInfo[floorKey][stairElevation];
                    vector<int> directions;
                    for (auto& el : modeInfo.items()) {
                        int d = stoi(el.key());
                        directions.push_back(d);
                    }
                    int bestDir = directions[0];
                    double minDiff = fabs(gyro - bestDir);
                    for (int d : directions) {
                        double diff = fabs(gyro - d);
                        if (diff > 180) diff = 360 - diff;
                        if (diff < minDiff) {
                            minDiff = diff;
                            bestDir = d;
                        }
                    }
                    json coords = modeInfo[to_string(bestDir)];
                    if (!coords.empty()) {
                        for (auto& pt : coords) {
                            storedStairCoords.push_back({ pt[0], pt[1] });
                        }
                    }

                    if (storedStairCoords.size() >= 2) {
                        {
                            // cur_pos_global은 현재 위치 좌표 (Point 타입: std::pair<int, int> 등)라고 가정합니다.
                            double minDist = std::numeric_limits<double>::max();
                            Point closestStairPt;
                            bool foundOne = false;

                            // storedStairCoords에 있는 각 계단 좌표와 cur_pos_global 간의 거리를 계산
                            for (const auto &stairPt : storedStairCoords) {
                                int dx = (stairPt.first - originMap[currentFloor].first) - cur_pos_global.first;
                                int dy = (stairPt.second - originMap[currentFloor].second) - cur_pos_global.second;
                                double dist = sqrt(dx * dx + dy * dy) * 0.1; // 1 좌표 단위 당 0.1미터
                                if (dist < minDist) {
                                    minDist = dist;
                                    closestStairPt = {stairPt.first, stairPt.second};
                                    foundOne = true;
                                }
                            }

                            if (foundOne) {
                                // 가장 가까운 계단 좌표 하나만 담아 업데이트
                                std::vector<Point> updatedStairCoords;
                                updatedStairCoords.push_back(closestStairPt);
                                storedStairCoords = updatedStairCoords;
                            }
                        }
                    }
                }
//                ss << "계단 도착! newFloorMode, roi_center On!" << "\n";
            }
            string floorText;

            if (currentFloor >= 4) {
                floorText = "basic";
            } else if (currentFloor < 0) {
                floorText = "B" + to_string(abs(currentFloor)) + "F";
            } else {
                floorText = to_string(currentFloor) + "F";
            }

            string binaryMapFile = testbedPath + "/zeromap_" + floorText + "-origin(" + to_string(originMap[currentFloor].first) + "," + to_string(originMap[currentFloor].second) + ").txt";
            string indoorMapFile = testbedPath + "/indoor_map_" + floorText + ".json";
            __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "[FloorChange] binaryMapFile: %s, indoorMapFile: %s", binaryMapFile.c_str(), indoorMapFile.c_str());
            BitMatrix newBinaryMap = readBitMatrixFromTextFile(binaryMapFile.c_str());
            MapData newMapData = loadMapData(indoorMapFile.c_str());
            updateMaps(newBinaryMap, newMapData);
            resetHistory();

            std::vector<std::pair<int, int>>& coords = !storedElevatorCoords.empty() ? storedElevatorCoords : storedStairCoords;
            Point arrival = {
                    coords.empty() ? 0 : coords[0].first - originMap[currentFloor].first,
                    coords.empty() ? 0 : coords[0].second - originMap[currentFloor].second
            };
            newFloorMode = true;
            roi_center = arrival;
            cur_pos = arrival;
            reSearchFlag = false;
        }

//        ss << "roi_center : (" << roi_center.first << ", " << roi_center.second << ")" << std::endl;
//        nativeMessage = ss.str();
    }



    void updatePosition(double gyro, double stepLength, vector<Point>& target_history, Point& target_pos) {
        double rad = gyro * M_PI / 180.0;
        double new_x = target_pos.first - sin(rad) * stepLength * 10;
        double new_y = target_pos.second + cos(rad) * stepLength * 10;

        // stepLength를 기반으로 분할 수 결정
        int divisions = round(stepLength * 10);
//        divisions = max(divisions, 1);
        divisions = max(1, 1);

        // 보간: 중간 경로 기록
        for (int i = 1; i <= divisions; i++) {
            int interp_x = round(target_pos.first + (new_x - target_pos.first) / divisions * i);
            int interp_y = round(target_pos.second + (new_y - target_pos.second) / divisions * i);
            target_history.push_back({interp_x, interp_y});
        }

        target_pos = {static_cast<int>(round(new_x)), static_cast<int>(round(new_y))};
    }

    // 기존 함수를 래핑하는 버전 추가
    void updatePosition(double gyro, double stepLength) {
        updatePosition(gyro, stepLength, history_of_pos, cur_pos);
    }

    MapPoint getMapMatchingResult(const MapPoint& current_position, const MapData& data) const {
        double min_distance = numeric_limits<double>::infinity();
        MapPoint closest_point;
        for (const auto& edge : data.edges) {
            auto start_node = find_if(data.nodes.begin(), data.nodes.end(), [&](const Node& node) {
                return node.id == edge.start;
            });
            auto end_node = find_if(data.nodes.begin(), data.nodes.end(), [&](const Node& node) {
                return node.id == edge.end;
            });
            if (start_node != data.nodes.end() && end_node != data.nodes.end()) {
                MapPoint point = find_closest_point_on_edge(current_position, start_node->coords, end_node->coords);
                double distance = magnitude(point - current_position);
                if (distance < min_distance) {
                    min_distance = distance;
                    closest_point = point;
                }
            }
        }
        return closest_point;
    }

    // calculatePosition 함수 수정 - 특정 경로를 지정할 수 있게 함
    pair<double, Point> calculatePosition(const vector<Point>& path_to_use, int& out_max_similarity,
                                          int& out_total_similarity, vector<Point>& out_candidate_positions) {
        if (path_to_use.size() < 0) {
            out_max_similarity = -1;
            out_total_similarity = 0;
            out_candidate_positions = {{-1, -1}};
            return {99999, {0, 0}};
        }

        int min_x = numeric_limits<int>::max(), min_y = numeric_limits<int>::max();
        for (const auto& pos : path_to_use) {
            min_x = min(min_x, pos.first);
            min_y = min(min_y, pos.second);
        }

        vector<Point> normalized_pos;
        for (const auto& pos : path_to_use) {
            normalized_pos.push_back({pos.first - min_x, pos.second - min_y});
        }

        int total_similarity = normalized_pos.size();
        BitMatrix test_matrix = create_bitmatrix_from_coordinates(normalized_pos);

        int roi_center_row = -1, roi_center_col = -1;
        int effective_roi = newFloorMode ? 50 : roi_radius;

        if (reSearchFlag) {
            roi_center_row = roi_center.first;
            roi_center_col = roi_center.second;
            effective_roi = reSearchRoi;
        }
        else if (newFloorMode) {
            roi_center_row = roi_center.first;
            roi_center_col = roi_center.second;
        } else if (has_last_matched) {
            roi_center_row = 234;
            roi_center_col = 214;
        }

        Point T_anchor = normalized_pos.front();
        auto [max_similarity, max_positions] = sliding_window_similarity_optimized(
                binary_map, test_matrix,
                roi_center_row, roi_center_col, effective_roi,
                T_anchor
        );

        out_max_similarity = max_similarity;
        out_total_similarity = total_similarity;

        if (max_similarity > 0 && !max_positions.empty()) {
            vector<Point> result_positions;
            for (const auto& pos : max_positions) {
                result_positions.push_back({
                                                   pos.first + normalized_pos.back().first,
                                                   pos.second + normalized_pos.back().second
                                           });
            }

            // 후보 위치 설정
            out_candidate_positions = result_positions;

            auto [mean_distance, mean_coord] = analyzeCoordinates(result_positions);
            MapPoint current_position(static_cast<double>(mean_coord.first), static_cast<double>(mean_coord.second));
            has_last_matched = true;

            return {mean_distance, {static_cast<int>(current_position.x + originMap[currentFloor].first), static_cast<int>(current_position.y + originMap[currentFloor].second)}};
        } else {
            out_candidate_positions = {{-1, -1}};
            return {-1, {-1, -1}};
        }
    }

    // 원래 calculatePosition 함수를 래핑
    pair<double, Point> calculatePosition() {
        int max_sim, total_sim;
        vector<Point> candidates;
        auto result = calculatePosition(history_of_pos, max_sim, total_sim, candidates);
        cur_max_similarity = max_sim;
        very_max_similarity = total_sim;
        return result;
    }

    // ---------------------------------------------------------------
    // processStep 멤버 함수 (floor 인자는 제거됨)
    ProcessResult processStep(float gyro, float compass, float stepLength, int stepCount, int floor, float arrivedGyroValue, int elevationMode) {
        currentFloor = floor;
        if (previousFloor == -9) {
            previousFloor = currentFloor;
        }
        double adjustedCompass = fmod((compass - 339.38 + 360), 360);  // 신공학관
        if(this->testbedPath == "109"){
            adjustedCompass = fmod((compass - 135 + 360), 360);
        }else{
            adjustedCompass = fmod((compass - 339.38 + 360), 360);
        }

        // 캘리브레이션 진행 중인 경우
        if (!gyroCalibrationDone) {
            pendingGyros.push_back(gyro);
            pendingStepLengths.push_back(stepLength);
            vector<Point> invalid_position = {{-1, -1}};
            // 현재 각 후보별 차이 계산
            vector<double> currentDiffs;
            for (int cand : gyroCaliCandidate) {
                double temp = fmod(gyro + cand + 360, 360);
                double diff = min(fabs(temp - adjustedCompass), 360 - fabs(temp - adjustedCompass));
                currentDiffs.push_back(diff);
            }

            // 이전 차이값이 존재하면 안정성 검사 후 기록
            if (!prevDiffs.empty()) {
                // 1등(최소 diff) 후보 결정
                int bestIdx = 0;
                for (int i = 1; i < currentDiffs.size(); i++) {
                    if (currentDiffs[i] < currentDiffs[bestIdx])
                        bestIdx = i;
                }

                // 스텝별 출력: 1등 후보와 그 차이값, 이전 차이값도 출력
                cout << "Step " << stepCount
                     << ": Best candidate = " << gyroCaliCandidate[bestIdx]
                     << " (current diff = " << currentDiffs[bestIdx]
                     << ", previous diff = " << prevDiffs[bestIdx] << ")" << endl;

                // 안정성 검사: 변화량이 20 미만일 때만 기록
                if (fabs(currentDiffs[bestIdx] - prevDiffs[bestIdx]) < 20) {
                    // 해당 스텝의 1등 후보 각도 기록
                    gyroCaliRankHistory.push_back(gyroCaliCandidate[bestIdx]);
                    // 최근 10회로 유지 (필요시 앞쪽 값 제거)
                    if (gyroCaliRankHistory.size() > 10)
                        gyroCaliRankHistory.erase(gyroCaliRankHistory.begin());

                    // 만약 최근 10회의 기록 중 9회 이상 같은 후보가 나오면 캘리브레이션 완료
                    map<int, int> count;
                    for (int val : gyroCaliRankHistory)
                        count[val]++;
                    for (auto &entry : count) {
                        if (entry.second >= 9) {
                            finalGyroCaliValue = entry.first;
                            gyroCalibrationDone = true;
                            cout << "Calibration completed: gyroCaliValue = " << finalGyroCaliValue << endl;
                            // 캘리브레이션이 완료된 순간, 누적된 센서값들에 보정값을 적용하여 PDR 경로를 생성합니다.
                            multiple_history_paths.resize(9);
                            multiple_cur_pos.resize(9, Point{0, 0});
                            active_paths.resize(9, true); // 모든 경로 활성화

#pragma omp parallel for
                            for (int path_idx = 0; path_idx < 9; path_idx++) {
                                // 각도 조정값 계산 (-20, -15, -10, -5, 0, 5, 10, 15, 20)
                                double angle_adjustment = finalGyroCaliValue + ((path_idx - 4) * 5.0);

                                // 모든 대기 중인 단계에 대해 경로 생성
                                for (size_t i = 0; i < pendingGyros.size(); i++) {
                                    double adjustedPendingGyro = pendingGyros[i] + angle_adjustment;
                                    updatePosition(adjustedPendingGyro, pendingStepLengths[i],
                                                   multiple_history_paths[path_idx], multiple_cur_pos[path_idx]);
                                }
                            }

                            // 중간 경로(인덱스 4, 보정값 그대로)를 기본 경로로 사용
                            history_of_pos = multiple_history_paths[4];
                            cur_pos = multiple_cur_pos[4];

                            break;
                        }
                    }
                }
                else {
                    cout << " -> Difference >= 20, candidate update skipped." << endl;
                }
            }
            // 현재 차이값을 이전 값으로 저장
            prevDiffs = currentDiffs;

            // 캘리브레이션이 완료될 때까지 위치 계산은 수행하지 않음
            return {0, 0, 0, false, finalGyroCaliValue};
        }

        gStepCount = stepCount;

        // 현재 모드에 맞는 각도 범위 계산
        vector<double> angle_adjustments(9);
        vector<vector<Point>> temp_history_paths(9);
        vector<Point> temp_cur_positions(9);


        // 기존 경로 복사 및 각도 설정
        for (int i = 0; i < 9; i++) {
            if (fine_tuning_mode && !fine_tuning_complete_mode) {
                // 미세 조정 모드: -4도 ~ +4도, 1도 간격
                angle_adjustments[i] = fine_tuning_base_angle + ((i - 4) * 5.0);
            }
            else if (fine_tuning_complete_mode) {
                angle_adjustments[i] = fine_tuning_base_angle + ((i - 4) * 5.0);
            }
            else {
                // 일반 모드: -20도 ~ +20도, 5도 간격
                angle_adjustments[i] = finalGyroCaliValue + ((i - 4) * 10.0);
            }
        }

//        this->setArrivedInfo(gyro + angle_adjustments[4], 0);
        this->setArrivedInfo(arrivedGyroValue, elevationMode);

#pragma omp parallel for
        for (int i = 0; i < 9; i++) {
            double adjustedGyro = gyro + angle_adjustments[i];
            cout << "gyro : " << gyro << " / angle_adjustments : " << angle_adjustments[i]  << " / adjustedGyro : " << adjustedGyro << endl;
            temp_history_paths[i] = multiple_history_paths[i];
            temp_cur_positions[i] = multiple_cur_pos[i];
            // 비활성화된 경로는 건너뜀
            if (!active_paths[i]) {
                continue;
            }
            // 현재 위치 업데이트
            updatePosition(adjustedGyro, stepLength, temp_history_paths[i], temp_cur_positions[i]);
        }

        previousFloor = floor;

        // 모든 경로에 대해 위치 계산
        vector<pair<double, Point>> results(9);
        vector<int> max_similarities(9);
        vector<int> total_similarities(9);
        vector<double> matching_rates(9);
        vector<vector<Point>> temp_candidate_history(9);

        __android_log_print(ANDROID_LOG_INFO, "codetime", "OK2 / 1");
#pragma omp parallel for
        for (int i = 0; i < 9; i++) {
            // 비활성화된 경로는 건너뜀
            if (!active_paths[i]) {
                max_similarities[i] = -1;
                total_similarities[i] = 0;
                matching_rates[i] = 0.0;
                continue;
            }

            int max_sim = 0, total_sim = 0;
            vector<Point> candidates;
            results[i] = calculatePosition(temp_history_paths[i], max_sim, total_sim, candidates);
            max_similarities[i] = max_sim;
            total_similarities[i] = total_sim;
            matching_rates[i] = (total_sim > 0) ? static_cast<double>(max_sim) / total_sim : 0.0;
            temp_candidate_history[i] = candidates;

            // 결과가 유효하지 않으면 경로 비활성화
            if (matching_rates[i] <= 0.8) {
                active_paths[i] = false;
            }

            cout << angle_adjustments[i] - finalGyroCaliValue << "° : "
                 << matching_rates[i] << " (active: " << active_paths[i] << ") / max_sim : " << max_sim << "\n";
        }
        __android_log_print(ANDROID_LOG_INFO, "codetime", "OK2 / 2");

        // 최적 경로와 두 번째 최적 경로 선택
        int best_idx = 4;    // 기본값은 중간 경로
        int second_idx = -1; // 두 번째 최적 경로
        double best_rate = 0.0;
        double second_rate = 0.0;

// 한 번의 탐색으로 최적 및 두 번째 최적 경로 찾기
        for (int i = 0; i < 9; i++) {
            // 비활성화된 경로는 건너뜀
            if (!active_paths[i] || results[i].first < 0) continue;

            // 현재 경로의 매칭률
            double current_rate = matching_rates[i];
            int distance_to_center = abs(i - 4);

            // 최적 경로보다 더 좋은 경우
            if (current_rate > best_rate) {
                // 기존 최적 경로를 두 번째로 밀기
                second_idx = best_idx;
                second_rate = best_rate;

                // 새 최적 경로 설정
                best_idx = i;
                best_rate = current_rate;
            }
                // 매칭률이 최적 경로와 같은 경우, 중앙에 더 가까운 경로 선택
            else if (current_rate == best_rate) {
                if (distance_to_center < abs(best_idx - 4)) {
                    // 기존 최적 경로를 두 번째로 밀기
                    second_idx = best_idx;
                    second_rate = best_rate;

                    // 새 최적 경로 설정
                    best_idx = i;
                    best_rate = current_rate;
                } else {
                    // 현재 경로가 두 번째 최적 후보가 될 수 있음
                    if (second_idx == -1 || current_rate > second_rate ||
                        (current_rate == second_rate && distance_to_center < abs(second_idx - 4))) {
                        second_idx = i;
                        second_rate = current_rate;
                    }
                }
            }
                // 두 번째 최적 경로보다 좋지만 최적 경로보다는 나쁜 경우
            else if (second_idx == -1 || current_rate > second_rate ||
                     (current_rate == second_rate && distance_to_center < abs(second_idx - 4))) {
                second_idx = i;
                second_rate = current_rate;
            }
        }

        double rate_difference = 0.0;

// 매칭률 차이 계산 및 출력
        if (second_idx != -1) {
            rate_difference = best_rate - second_rate;
            printf("최적 경로 인덱스: %d (매칭률: %.6f), 두 번째 최적 경로 인덱스: %d (매칭률: %.6f)\n",
                   best_idx, best_rate, second_idx, second_rate);
            printf("두 경로 간의 매칭률 차이: %.6f\n", rate_difference);
        } else {
            printf("최적 경로 인덱스: %d (매칭률: %.6f), 두 번째 최적 경로가 없습니다.\n",
                   best_idx, best_rate);
        }

        // 만약 모든 경로가 비활성화되었다면 중간 경로 강제 활성화
        if (best_rate == 0.0) {
            cout << "모든 경로가 비활성화됨. 중간 경로(0°) 재활성화" << endl;
            best_idx = 4;
            active_paths[4] = true;
        }


        // best 경로 추적 및 미세 조정 모드 전환
        if (best_idx == last_best_idx) {
            consecutive_best_count++;
            cout << "연속 best 각도: " << angle_adjustments[best_idx] - finalGyroCaliValue
                 << "° (연속 " << consecutive_best_count << "회)" << endl;


            // 5도 단위 search를 하다가 대강의 각도가 나왔을 때! 그 때는 1도 단위 search모드로 들어가서 더 자세한 각도를 찾는다.
            if (consecutive_best_count >= 40 && ((abs(best_idx-second_idx) == 1) && (rate_difference >= 0.01)) && !fine_tuning_mode) {
                consecutive_best_count = 0;
                fine_tuning_mode = true;
                fine_tuning_base_angle = angle_adjustments[best_idx];

                cout << "미세 조정 모드 활성화: 기준 각도 " << fine_tuning_base_angle - finalGyroCaliValue << "°" << endl;

                multiple_history_paths.clear();
                multiple_cur_pos.clear();

                // 완전히 새로운 각도 범위로 교체 (-4도 ~ +4도, 1도 간격)
                angle_adjustments.clear();
                angle_adjustments.resize(9);
                for (int i = 0; i < 9; i++) {
                    angle_adjustments[i] = fine_tuning_base_angle + ((i - 4)*5.0);
                }

                // 캘리브레이션이 완료된 순간, 누적된 센서값들에 보정값을 적용하여 PDR 경로를 생성합니다.
                multiple_history_paths.resize(9);
                multiple_cur_pos.resize(9, Point{0, 0});
                active_paths.resize(9, true); // 모든 경로 활성화

#pragma omp parallel for
                for (int path_idx = 0; path_idx < 9; path_idx++) {
                    // 각도 조정값 계산 (-20, -15, -10, -5, 0, 5, 10, 15, 20)
                    double angle_adjustment = fine_tuning_base_angle + ((path_idx - 4)*5.0);

                    // 모든 대기 중인 단계에 대해 경로 생성
                    for (size_t i = 0; i < pendingGyros.size(); i++) {
                        double adjustedPendingGyro = pendingGyros[i] + angle_adjustment;
                        updatePosition(adjustedPendingGyro, pendingStepLengths[i],
                                       multiple_history_paths[path_idx], multiple_cur_pos[path_idx]);
                    }
                }

                pendingGyros.clear();
                pendingStepLengths.clear();
                // 모든 경로 활성화
                active_paths.clear();
                active_paths.resize(9, true);
            }
            else if (fine_tuning_mode && !fine_tuning_complete_mode && consecutive_best_count >= 5) {
                fine_tuning_complete_mode = true;
                fine_tuning_base_angle = angle_adjustments[best_idx];
                vector<Point> best_path = multiple_history_paths[best_idx];
                Point best_pos = multiple_cur_pos[best_idx];

                // 경로 리셋 및 새 기준으로 복사
                multiple_history_paths.clear();
                multiple_cur_pos.clear();
                multiple_history_paths.resize(9, best_path);
                multiple_cur_pos.resize(9, best_pos);
            }
            else {
                multiple_history_paths = temp_history_paths;
                multiple_cur_pos = temp_cur_positions;
            }
        } else {
            multiple_history_paths = temp_history_paths;
            multiple_cur_pos = temp_cur_positions;
            consecutive_best_count = 0;
            last_best_idx = best_idx;
        }

        if (fine_tuning_complete_mode) {
            vector<Point> best_path = multiple_history_paths[best_idx];
            cout << "best_path size : " << best_path.size() << endl;
            Point best_pos = multiple_cur_pos[best_idx];

            // 경로 리셋 및 새 기준으로 복사
            multiple_history_paths.clear();
            multiple_cur_pos.clear();
            multiple_history_paths.resize(9, best_path);
            multiple_cur_pos.resize(9, best_pos);

            // 모든 경로 활성화
            active_paths.clear();
            active_paths.resize(9, true);
        }

        // 최적 경로로 현재 상태 업데이트
        history_of_pos = multiple_history_paths[best_idx];
        cur_pos = multiple_cur_pos[best_idx];

        // 결과 설정
        auto [mean_distance, final_coord] = results[best_idx];


        // --- 수정 코드 시작 ---
        // final_coord를 로컬 좌표계로 변환 (BitMatrix indexing)
//        try {
//            int bm_row = final_coord.first - originMap[currentFloor].second;
//            int bm_col = final_coord.second - originMap[currentFloor].first;
//            if (!binary_map.getBit(bm_row, bm_col)) {
//                // final_coord가 이동 불가능 영역(0)인 경우, 인접한 이동 가능 영역(1)을 찾기 위한 BFS 실시.
//                std::queue<std::pair<int, int>> bfsQueue;
//                std::vector<std::vector<bool>> visited(binary_map.height, std::vector<bool>(binary_map.width, false));
//                bfsQueue.push({bm_row, bm_col});
//                visited[bm_row][bm_col] = true;
//                std::pair<int, int> nearest = {bm_row, bm_col};
//                bool found = false;
//
//                while (!bfsQueue.empty() && !found) {
//                    auto [r, c] = bfsQueue.front();
//                    bfsQueue.pop();
//                    // 4방향 (상, 하, 좌, 우) 이웃 확인
//                    std::vector<std::pair<int, int>> neighbors = { {r - 1, c}, {r + 1, c}, {r, c - 1}, {r, c + 1} };
//                    for (auto& n : neighbors) {
//                        int nr = n.first;
//                        int nc = n.second;
//                        if (nr < 0 || nr >= binary_map.height || nc < 0 || nc >= binary_map.width)
//                            continue;
//                        if (!visited[nr][nc]) {
//                            visited[nr][nc] = true;
//                            if (binary_map.getBit(nr, nc)) {
//                                nearest = {nr, nc};
//                                found = true;
//                                break;
//                            }
//                            bfsQueue.push({nr, nc});
//                        }
//                    }
//                }
//                // 찾은 로컬 좌표(nearest)를 원래 좌표계로 복원하여 final_coord 업데이트
//                final_coord.first = nearest.first + originMap[currentFloor].second;
//                final_coord.second = nearest.second + originMap[currentFloor].first;
//            }
//        }
//        catch (...) {
//        }
        // --- 수정 코드 종료 ---

        cur_max_similarity = max_similarities[best_idx];
        very_max_similarity = total_similarities[best_idx];

        // 최종 위치 업데이트
        cur_pos_global = {static_cast<double>(final_coord.first - originMap[currentFloor].second), static_cast<double>(final_coord.second - originMap[currentFloor].first)};
        cur_distance = mean_distance;

        // 결과 반환
        ProcessResult result {
                static_cast<double>(final_coord.first),
                static_cast<double>(final_coord.second),
                mean_distance,
                true,
                static_cast<int>(angle_adjustments[best_idx])
        };


        // 재탐색 여부 판단
        if ((cur_max_similarity != -1) && (very_max_similarity > 30) &&
            (static_cast<double>(cur_max_similarity)/very_max_similarity < 0.9)) {
            this->reSearchStart(cur_pos_global, 100);

            // 재탐색 시 모든 경로 다시 활성화
            std::fill(active_paths.begin(), active_paths.end(), true);

            // 재탐색 시작 시 미세 조정 모드 초기화
//            fine_tuning_mode = false;
            consecutive_best_count = 0;
            last_best_idx = -1;
        }

        // 디버그 출력
        cout << "Selected path idx: " << best_idx
             << " (angle: " << angle_adjustments[best_idx] - finalGyroCaliValue << "°) "
             << "with matching rate: " << best_rate << endl;

        return result;
    }
    // ---------------------------------------------------------------
};

std::vector<std::string> listAssetFiles(const std::string& directory) {
    std::vector<std::string> result;

    AAssetDir* assetDir = AAssetManager_openDir(g_assetManager, directory.c_str());
    if (assetDir == nullptr) {
        return result;
    }

    const char* filename;
    while ((filename = AAssetDir_getNextFileName(assetDir)) != nullptr) {
        result.push_back(std::string(filename));
    }

    AAssetDir_close(assetDir);
    return result;
}

// assets에서 파일 내용을 읽는 함수
std::string readAssetFile(const std::string& filename) {
    AAsset* asset = AAssetManager_open(g_assetManager, filename.c_str(), AASSET_MODE_BUFFER);
    if (asset == nullptr) {
        return "";
    }

    off_t length = AAsset_getLength(asset);
    std::string content;
    content.resize(length);

    AAsset_read(asset, &content[0], length);
    AAsset_close(asset);

    return content;
}

// 원점 정보를 로드하는 함수
void loadOriginOfMap(std::map<int, std::pair<int,int>>& originMap, const std::string& directory) {
    // 기존 숫자층용
    std::regex numPat(R"(zeromap_((B)?(\d+))F-origin\((\d+),(\d+)\)\.txt)");

    // ✅ basic용 추가
    std::regex basicPat(R"(zeromap_basic-origin\((\d+),(\d+)\)\.txt)");

    std::vector<std::string> fileList = listAssetFiles(directory);

    std::pair<int,int> basicOrigin = {0,0};
    for (const auto& filename : fileList) {
        std::smatch m;
        if (std::regex_match(filename, m, numPat)) {
            int floorNumber = m[2].matched ? -std::stoi(m[3].str()) : std::stoi(m[3].str());
            originMap[floorNumber] = { std::stoi(m[4].str()), std::stoi(m[5].str()) };
        } else if (std::regex_match(filename, m, basicPat)) {
            basicOrigin = { std::stoi(m[1].str()), std::stoi(m[2].str()) };
        }
    }

    // ✅ 4층 이상은 모두 basicOrigin으로 채움 (필요 범위만 채우세요)
    if (basicOrigin != std::pair<int,int>{0,0}) {
        for (int f = 4; f <= 50; ++f) originMap[f] = basicOrigin;
    }
}




// ====================================================================
// JNI 인터페이스 구현: Java_com_example_fifth_maplocationlib_NativeLib_processStep
// ====================================================================
static PositionTracker* gTracker = nullptr;
extern "C"
JNIEXPORT void JNICALL
Java_com_fifth_maplocationlib_NativeLib_initializeEngine(JNIEnv *env, jobject, jint floor, jstring testbed) {
    // jstring -> std::string 변환
    const char* tb = env->GetStringUTFChars(testbed, nullptr);
    std::string testbedStr = tb ? tb : "";
    env->ReleaseStringUTFChars(testbed, tb);

    // 원점 정보 로드 (testbed 디렉토리 내에서 검색)
    loadOriginOfMap(originMap, testbedStr);

    __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "===== Origin Map Contents =====");
    __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "Total entries: %d, %s", originMap.size(), testbedStr.c_str());
    for (const auto& entry : originMap) {
        __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "Floor %d: Origin at (%d, %d)",
                            entry.first, entry.second.first, entry.second.second);
    }
    __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "==============================");

    // Load floor-change areas (stairs & elevator) from assets: <testbed>/enb/... json files
    {
        std::string stairsPath = testbedStr + "/floorchange_stairs_areas.json";
        std::string elevatorPath = testbedStr + "/floorchange_elevator_areas.json";

        json s = loadJsonData(stairsPath.c_str());
        if (!s.is_discarded() && !s.is_null()) {
            stairsInfo = s;
            __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "Loaded stairs areas: %s", stairsPath.c_str());
        } else {
            __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "Failed to load stairs areas: %s", stairsPath.c_str());
        }

        json e = loadJsonData(elevatorPath.c_str());
        if (!e.is_discarded() && !e.is_null()) {
            elevatorInfo = e;
            __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "Loaded elevator areas: %s", elevatorPath.c_str());
        } else {
            __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "Failed to load elevator areas: %s", elevatorPath.c_str());
        }
    }

    int start_floor = floor;
    string floorText = (start_floor < 0 ? "B" + to_string(abs(start_floor)) : to_string(start_floor)) + "F";
    if (start_floor >= 4) {
        floorText = "basic";
    } else if (start_floor < 0) {
        floorText = "B" + to_string(abs(start_floor)) + "F";
    } else {
        floorText = to_string(start_floor) + "F";
    }
    string binaryMapFile = testbedStr + "/zeromap_" + floorText + "-origin(" + to_string(originMap[start_floor].first) + "," + to_string(originMap[start_floor].second) + ").txt";
    string indoorMapFile = testbedStr + "/indoor_map_" + floorText + ".json";
    __android_log_print(ANDROID_LOG_INFO, "LOG_CHECK", "binaryMapFile: %s", binaryMapFile.c_str());


    BitMatrix binaryMap = readBitMatrixFromTextFile(binaryMapFile.c_str());
    MapData mapData = loadMapData(indoorMapFile.c_str());
    // 기존과 같이 PositionTracker 객체 생성 후 전역 변수에 저장
    if (gTracker) {
        delete gTracker;
    }
    gTracker = new PositionTracker(binaryMap, mapData, start_floor, testbedStr);
}


// 2. processStep: 센서 데이터(자이로, 보폭, 걸음 수 등)를 받아 위치 계산 결과를 float 배열로 반환
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_fifth_maplocationlib_NativeLib_processStep(JNIEnv *env, jobject /* this */,
                                                    jfloat gyro,
                                                    jfloat compass,
                                                    jfloat stepLength,
                                                    jint stepCount,
                                                    jint floor,
                                                    jfloat arrivedGyroValue,
                                                    jint elevationMode) {

    if (!gTracker) {
        // 엔진이 초기화되지 않은 경우 null 반환 또는 에러 처리
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, "codetime", "OK2");

    // enginePtr를 PositionTracker 포인터로 변환
    PositionTracker* tracker = gTracker;

    // 센서 입력을 토대로 processStep() 호출
    ProcessResult res = gTracker->processStep(gyro, compass, stepLength, stepCount, floor, arrivedGyroValue, elevationMode);

    __android_log_print(ANDROID_LOG_INFO, "codetime", "OK3");
    // 결과를 jfloatArray로 준비합니다.
// 반환 배열은 [x, y, currentFloor, orientation]로 구성합니다.
// 여기서는 orientation 값으로 gyro를 그대로 사용합니다.


    jfloatArray resultArray = env->NewFloatArray(4);
    if (resultArray == nullptr) return nullptr;
    jfloat out[4];
    out[0] = static_cast<jfloat>(res.x);
    out[1] = static_cast<jfloat>(res.y);
    out[2] = static_cast<jfloat>(res.distance);
    out[3] = static_cast<jfloat>(res.gyroCaliValue);;

    if (res.distance > 10000.0f) {
        out[0] = -1.0f;
        out[1] = -1.0f;
    }
    __android_log_print(ANDROID_LOG_INFO, "result2", "(%f, %f) / %f", out[0], out[1], res.distance);

    env->SetFloatArrayRegion(resultArray, 0, 4, out);
    return resultArray;
}

// 3. 엔진 해제 함수
extern "C"
JNIEXPORT void JNICALL
Java_com_fifth_maplocationlib_NativeLib_destroyEngine(JNIEnv *env, jobject) {
    if (gTracker) {
        delete gTracker;
        gTracker = nullptr;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fifth_maplocationlib_NativeLib_reSearchStartInStairs(JNIEnv *env, jobject, jint stairsCoords_x, jint stairsCoords_y) {
    jint new_stairsCoords_x = stairsCoords_x - originMap[gTracker->getCurrentFloor()].first;
    jint new_stairsCoords_y = stairsCoords_y - originMap[gTracker->getCurrentFloor()].second;
    gTracker->reSearchStart({(double)new_stairsCoords_x, (double)new_stairsCoords_y}, 2);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_fifth_maplocationlib_NativeLib_resetGyroCalibration(JNIEnv *env, jobject /* this */) {
    if (gTracker) {
        gTracker->resetGyroCalibration();
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_fifth_maplocationlib_NativeLib_reSearchStart(JNIEnv *env, jobject, jint search_range) {
    gTracker->reSearchStart(cur_pos_global, 50);
}