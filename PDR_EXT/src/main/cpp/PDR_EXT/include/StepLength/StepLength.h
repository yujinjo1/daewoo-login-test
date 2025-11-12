//
// Created by 이도훈 on 2025. 8. 14..
//

#ifndef NEXT_PDR_STEPLENGTH_H
#define NEXT_PDR_STEPLENGTH_H

#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <deque>
#include <optional>

void StepLength_onSensorSample(double linAccZ, int64_t tsMs);
void StepLength_onStepDetected(int64_t tsMs);
double StepLength_getLastStepLength();
double StepLength_getLastAmplitudeZ();
double StepLength_getLastFrequencyHz();
double StepLength_getLastPeakZ();
double StepLength_getLastValleyZ();
double StepLength_getLastDurationMs();
void StepLength_setScale(double s);
void StepLength_setClamp(double minL, double maxL);
void StepLength_setPocketMode(bool enable);

#endif //NEXT_PDR_STEPLENGTH_H
