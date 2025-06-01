#ifndef MOTORS_H
#define MOTORS_H

#include "Config.h"

struct PWMControlValues {
    int leftPwm;
    int rightPwm;
};

class Motors {
public:
    Motors(Config* config);

    // Method to update vehicle using pre-calculated motor adjustments with timing control
    void updateVehicleWithAdjustments(PWMControlValues pwmValues);

    // Get current PWM values (for SatiBot V1)
    int getCurrentPwmLeft() const;
    int getCurrentPwmRight() const;

    // Emergency stop control
    void enableStop();
    void disableStop();
    bool isStopEnabled() const;

private:
    Config* config;

    // For SatiBot V1 with acceleration
    int currentPwmLeft;
    int currentPwmRight;

    // Emergency stop state
    bool stopEnabled;

    // Motor update timing
    unsigned long lastMotorUpdateTime;
    static constexpr unsigned long motorUpdateInterval = 20;  // 20ms = 50Hz update rate
};

#endif // MOTORS_H
