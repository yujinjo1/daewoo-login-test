

#ifndef PDRRESULT_H
#define PDRRESULT_H

struct PDR {
//    int devicePosture;
//    int movementMode;
//    int userAttitude;
    double stepLength;
    double direction;           // degrees [0,360)
    int totalStepCount;
//    int directionReliability;
};

#endif // PDRRESULT_H