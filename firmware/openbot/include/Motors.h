#ifndef MOTORS_H
#define MOTORS_H

#include "Config.h"

class Motors {
public:
    Motors(Config* config);

    // Method to update vehicle using normalized linear velocity, heading adjustment, and noControl flag
    void updateVehicle(float normalizedLinearVelocity, float headingAdjustment, bool noControl = false);

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
