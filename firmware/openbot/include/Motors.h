#ifndef MOTORS_H
#define MOTORS_H

#include "Config.h"

class Motors {
public:
    Motors(Config* config);

    // New method to update vehicle using normalized linear velocity and heading adjustment
    void updateVehicle(float normalizedLinearVelocity, float headingAdjustment);

    // Get current PWM values (for SatiBot V1)
    int getCurrentPwmLeft() const;
    int getCurrentPwmRight() const;

private:
    Config* config;

    // For SatiBot V1 with acceleration
    int currentPwmLeft;
    int currentPwmRight;

    // Timestamp for the last direct update
    unsigned long lastDirectUpdateTime;

    // Interval between direct updates (in milliseconds)
    const unsigned long directUpdateInterval = 20;  // 20ms = 50Hz update rate
};

#endif // MOTORS_H
