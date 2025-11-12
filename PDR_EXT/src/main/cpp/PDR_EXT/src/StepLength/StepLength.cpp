#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <deque>
#include <optional>
#include <algorithm>
#include "StepLength/StepLength.h"
#include "StepLength/SLRequire.h"

#define LOG_TAG "NativeStepLength"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ----------------------------------------------
// StepLength: collects per-step Z lin-acc peaks/valleys
// and derives amplitude and frequency between steps.
// Integrate by calling:
//   onSensorSample(linAccZ, ts_ms)  // each sensor tick
//   onStepDetected(ts_ms)           // when PDRStateManager confirms a step
// Then read metrics with getters.
// ----------------------------------------------

namespace {
    static inline double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static inline double safePow(double x, double p) {
        return (x > 0.0) ? std::pow(x, p) : 0.0;
    }

    // Frequency → stride gain (sigmoid in sqrt(f))
    // k(f) = 0.317 + 0.440 / (1 + exp(-4.78 * (sqrt(f) - 1.564)))
    static inline double kFromFreq(double fHz) {
        if (fHz <= 0.0) return 0.317; // minimal cadence
        const double rootf = std::sqrt(fHz);
        return 0.317 + (0.440 / (1.0 + std::exp(-4.78 * (rootf - 1.564))));
    }

    // In-pocket stride scale based on frequency
    // S(f) = 0.0886 f^2 - 0.1336 f + 0.6813
    static inline double pocketScaleFromFreq(double fHz) {
        const double s = 0.0886 * fHz * fHz - 0.1336 * fHz + 0.6813;
        // Clamp to avoid extreme scaling; adjust if your data suggests otherwise
        return clamp(s, 0.50, 1.30);
    }
}

struct StepSegment {
    int64_t startMs = 0;           // inclusive
    int64_t endMs   = 0;           // inclusive
    double  peakZ   = -1e9;        // max Z lin-acc between steps
    double  valleyZ =  1e9;        // min Z lin-acc between steps
    double  amplitudeZ = 0.0;      // peakZ - valleyZ
    double  durationMs = 0.0;      // endMs - startMs
    double  frequencyHz = 0.0;     // 1000 / durationMs
    double  stepLength = 0.0;      // computed from frequency/amplitude (model below)
};

class MovingAverage {
public:
    explicit MovingAverage(int n = 5) : N(n) {}
    double push(double x) {
        sum += x; q.push_back(x);
        if ((int)q.size() > N) { sum -= q.front(); q.pop_front(); }
        return sum / (double)q.size();
    }
    void reset() { q.clear(); sum = 0.0; }
private:
    int N; std::deque<double> q; double sum = 0.0;
};

class StepLength {
public:
    enum class Profile { OnHand, InPocket };

    StepLength() : maZ(5) {}

    // Feed each linear-acceleration Z sample with timestamp in milliseconds
    void onSensorSample(double linAccZ, int64_t tsMs) {
        // light smoothing to stabilize peak/valley
        double z = maZ.push(linAccZ);
        if (!segmentOpen) {
            // first sample for a new segment starts when previous step closed
            current.startMs = tsMs;
            current.peakZ = z; current.valleyZ = z; segmentOpen = true;
        } else {
            if (z > current.peakZ)   current.peakZ   = z;
            if (z < current.valleyZ) current.valleyZ = z;
        }
        lastSampleMs = tsMs;
    }

    // Call this exactly when a step is confirmed by PDRStateManager
    void onStepDetected(int64_t tsMs) {
        if (!segmentOpen) {
            // No samples yet; open and close a degenerate segment
            current.startMs = tsMs; current.peakZ = current.valleyZ = 0.0; segmentOpen = true;
        }

        // Close the segment at "now" and compute metrics
        current.endMs = (lastSampleMs > 0) ? lastSampleMs : tsMs;
        current.durationMs = (lastStepMs > 0) ? (double)(tsMs - lastStepMs) : 0.0;
        if (current.durationMs > 0.0) {
            current.frequencyHz = 1000.0 / current.durationMs;
        } else {
            current.frequencyHz = 0.0;
        }
        current.amplitudeZ = current.peakZ - current.valleyZ;

        // Override amplitude/peak/valley with shared PV detector (onHand logic) if valid
        {
            SLRequire& pv = SLRequire_Instance();
            const double pvPeak   = (double)pv.peak();
            const double pvValley = (double)pv.valley();
            const double pvAmp    = std::max(0.0, pvPeak - pvValley);
            if (pvAmp > 0.0) {
                current.peakZ = pvPeak;
                current.valleyZ = pvValley;
                current.amplitudeZ = pvAmp;
                LOGD("[StepLength] override by SLRequire: peak=%.3f valley=%.3f ampZ=%.3f", current.peakZ, current.valleyZ, current.amplitudeZ);
            } else {
                LOGD("[StepLength] SLRequire amp=0 → keeping local amplitude=%.3f", current.amplitudeZ);
            }
        }

        // Compute step length using a tunable model (frequency + amplitude)
        current.stepLength = computeStepLength(current.amplitudeZ, current.frequencyHz);

        // ---- Stride smoothing with initial pass-through ----
        // Always push raw value into the ring buffer
        const double rawL = current.stepLength;
        const int W = (smWin < 1 ? 1 : (smWin > 16 ? 16 : smWin));
        double evict = 0.0;
        if (smSeen >= W) {
            evict = smBuf[smIdx];
        }
        smSum -= evict;
        smBuf[smIdx] = rawL;
        smSum += rawL;
        smIdx = (smIdx + 1) % W;
        smSeen++;

        double maVal;
        if (smSeen <= 2) {
            // first two steps: use raw value directly
            maVal = rawL;
        } else {
            const int denom = (smSeen < W ? smSeen : W);
            maVal = smSum / (double)denom; // denom is >=1 here
        }
        lastSmoothedStepLen = maVal;

        // Commit to last and reset for the next segment
        last = current; hasLast = true;
        lastStepMs = tsMs;
        resetCurrent(tsMs);

        const double dbgA4 = safePow(std::max(last.amplitudeZ, 0.0), 0.25);
        const double dbgK  = kFromFreq(last.frequencyHz);
        LOGD("[StepLength] step finalize: ampZ=%.3f (A^1/4=%.3f), peak=%.3f, valley=%.3f, dt=%.1f ms, f=%.2f Hz, k(f)=%.3f, Lraw=%.3f, L=%.3f",
             last.amplitudeZ, dbgA4, last.peakZ, last.valleyZ, last.durationMs, last.frequencyHz, dbgK, last.stepLength, lastSmoothedStepLen);
    }

    // -------- Accessors for the most recent finalized step --------
    double lastStepLength() const { return hasLast ? last.stepLength : defaultStepLen; }
    double lastSmoothedStepLength() const { return hasLast ? lastSmoothedStepLen : defaultStepLen; }

    // Tunables for the step-length model
    void setBaseLength(double L) { defaultStepLen = L; }
    void setScale(double s) { scaleFactor = s; }
    void setClamp(double minL, double maxL) { minStride = minL; maxStride = maxL; }
    void setProfile(Profile p) { profile = p; }

private:
    // Simple, robust model: L = clamp(K0 + Kamp * sqrt(max(ampZ,0)) + Kfreq * f, 0.25, 1.2)
    // You can later swap this with your sigmoid/frequency-based model.
    double computeStepLength(double ampZ, double fHz) const {
        // Amplitude from Z-linAcc peak-to-valley; use 4th-root to compress range
        const double A4 = safePow(std::max(ampZ, 0.0), 0.25);
        const double kf = kFromFreq(fHz);
        const double L0 = kf * A4 * scaleFactor; // base length before pocket scaling

        // pocket mode scale
        double S = 1.0;
        if (profile == Profile::InPocket) {
            S = pocketScaleFromFreq(fHz);
        }
        const double Lscaled = L0 * S;
        const double Lfinal = clamp(Lscaled, minStride, maxStride);

        // Verbose debug: helps diagnose constant 0.25m results
        LOGD("[StepLength:model] ampZ=%.4f A1/4=%.4f f=%.3fHz kf=%.4f S=%.4f L0=%.4f Lscaled=%.4f clamp=[%.2f,%.2f] => L=%.4f (profile=%s)",
             ampZ, A4, fHz, kf, S, L0, Lscaled, minStride, maxStride, Lfinal,
             (profile == Profile::InPocket ? "InPocket" : "OnHand"));

        return Lfinal;
    }

    void resetCurrent(int64_t tsStart) {
        current = StepSegment{}; segmentOpen = false; maZ.reset();
        // next onSensorSample will open a new segment at the first sample
        // but keep start hint for debugging
        hintNextStart = tsStart;
    }

    // Tunables
    double scaleFactor   = 1.00;  // overall gain on k(f)*A^1/4
    double minStride     = 0.25;  // m
    double maxStride     = 1.40;  // m
    double defaultStepLen = 0.65; // fallback when no finalized metrics yet

    // ---- Stride smoothing (runs only on step finalize) ----
    int    smWin   = 5;           // window size (>=1)
    double smSum   = 0.0;         // rolling sum
    double smBuf[16] = {0.0};     // ring buffer (supports up to 16 window)
    int    smIdx   = 0;           // write index in ring buffer
    int    smSeen  = 0;           // how many steps have been seen (monotonic)
    double lastSmoothedStepLen = 0.0; // published smoothed stride

    // state
    MovingAverage maZ;
    StepSegment current{};
    StepSegment last{};
    bool segmentOpen = false;
    bool hasLast = false;
    int64_t lastSampleMs = 0;
    int64_t lastStepMs = 0;
    int64_t hintNextStart = 0;
    Profile profile = Profile::OnHand;
};

// ----------------------------------------------
// Global singleton (simple integration option)
// ----------------------------------------------
static StepLength gStepLength;

// Feed samples from SensorManager (linear acceleration Z, timestamp in ms)
void StepLength_onSensorSample(double linAccZ, int64_t tsMs) {
    gStepLength.onSensorSample(linAccZ, tsMs);
    // Debug at a low rate: only when |z| is large enough (reduces spam)
    if (std::fabs(linAccZ) > 0.5) {
        LOGD("[StepLength:sample] z=%.3f ts=%lld", linAccZ, (long long)tsMs);
    }
}
// Notify when PDRStateManager confirms a step
void StepLength_onStepDetected(int64_t tsMs) {
    gStepLength.onStepDetected(tsMs);
}


double StepLength_getLastStepLength() { return gStepLength.lastSmoothedStepLength(); }

double StepLength_getLastRawStepLength() { return gStepLength.lastStepLength(); }

void StepLength_setScale(double s) {
    gStepLength.setScale(s);
}
void StepLength_setClamp(double minL, double maxL) {
    gStepLength.setClamp(minL, maxL);
}
void StepLength_setPocketMode(bool enable) {
    gStepLength.setProfile(enable ? StepLength::Profile::InPocket
                                  : StepLength::Profile::OnHand);
}
