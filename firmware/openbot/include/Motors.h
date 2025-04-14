#ifndef MOTORS_H
#define MOTORS_H

#include "Config.h"

class Motors {
public:
    Motors(Config* config);

    // Motor control methods
    void updateLeftMotors(int controlValue);
    void updateRightMotors(int controlValue);
    void stopLeftMotors();
    void stopRightMotors();
    void updateVehicle(int leftControl, int rightControl);

    // Get current control values
    int getLeftControl() const;
    int getRightControl() const;

    // Set control values
    void setLeftControl(int value);
    void setRightControl(int value);

private:
    Config* config;

    // Control values
    volatile int ctrlLeft;
    volatile int ctrlRight;

    // For SatiBot V1 with acceleration
    int currentPwmLeft;
    int currentPwmRight;
    unsigned long lastUpdateLeft;
    unsigned long lastUpdateRight;
    unsigned long lastDirectionChangeLeft;
    unsigned long lastDirectionChangeRight;

    // Constants for acceleration
    const unsigned long accelInterval = 10;  // interval (in ms) between PWM updates
    const int accelStep = 1;                 // amount to change PWM per update
    const unsigned long directionChangeDelay = 200; // delay before changing direction

    // Helper methods
    void updateLeftMotorsV0();
    void updateRightMotorsV0();
    void updateLeftMotorsV1();
    void updateRightMotorsV1();
};

#endif // MOTORS_H
