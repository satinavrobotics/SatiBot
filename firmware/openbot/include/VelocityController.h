#ifndef VELOCITY_CONTROLLER_H
#define VELOCITY_CONTROLLER_H

#include "Config.h"
#include "Motors.h"
#include "Sensors.h"

class VelocityController {
public:
    VelocityController(Config* config, Motors* motors, Sensors* sensors);

    // Initialize the controller
    void begin();

    // Set PID parameters
    void setKp(float kp);
    void setKi(float ki);
    void setKd(float kd);

    // Get current PID parameters
    float getKp() const;
    float getKi() const;
    float getKd() const;

    // Set target angular velocity directly
    void setTargetAngularVelocity(float targetVelocity);

    // Set target linear velocity directly
    void setTargetLinearVelocity(float targetVelocity);



    // Get the current target angular velocity
    float getTargetAngularVelocity() const;

    // Get the current target linear velocity
    float getTargetLinearVelocity() const;

    // Get the current normalized linear velocity (0.0 to 1.0)
    float getNormalizedLinearVelocity() const;

    // Update the controller with current sensor readings and apply control
    void update();

    // Get the current heading adjustment value
    float getHeadingAdjustment() const;

    // Get the current heading value
    float getHeading() const;

    // Get the target heading value
    float getTargetHeading() const;

    // Controller is always enabled

    // Reset the controller (clear accumulated error)
    void reset();

private:
    Config* config;
    Motors* motors;
    Sensors* sensors;

    // PID parameters
    float kp;  // Proportional gain
    float ki;  // Integral gain
    float kd;  // Derivative gain

    // Controller state
    float targetAngularVelocity;  // Target angular velocity (rad/s)
    float targetLinearVelocity;   // Target linear velocity (m/s)
    float currentLinearVelocity;  // Current linear velocity (m/s)
    float rampedLinearVelocity;   // Ramped linear velocity (m/s)
    float normalizedLinearVelocity; // Normalized linear velocity (0.0 to 1.0)
    float lastError;              // Last error for derivative term
    float integralError;          // Accumulated error for integral term
    float lastOutput;             // Last controller output
    float headingAdjustment;      // Current heading adjustment value
    float heading;                // Current heading in radians
    float targetHeading;          // Target heading in radians

    // No longer needed as IMU sampling is handled by Sensors class

    // Timing
    unsigned long lastUpdateTime;
    unsigned long previousUpdateTime;  // Previous update timestamp for dt calculation
    unsigned long updateInterval;
    float measuredDt;                 // Measured time delta between updates in seconds

    // Controller is always enabled

    // Constants
    static constexpr float MAX_INTEGRAL_ERROR = 10.0f;  // Prevent integral windup
    static constexpr float MAX_OUTPUT = 255.0f;         // Maximum output value
    static constexpr float LINEAR_ACCELERATION_RATE = 10.0f; // Acceleration rate for linear velocity (units/second)

    // Helper methods
    float getFilteredYawRate();                         // Get filtered yaw rate from IMU via Sensors
    void updateHeading(float omega);                    // Update heading based on angular velocity
    void updateTargetHeading();                         // Update target heading based on target angular velocity
    void updateCurrentLinearVelocity();                 // Update current linear velocity from motors
    void updateRampedLinearVelocity();                  // Update ramped linear velocity based on target
};

#endif // VELOCITY_CONTROLLER_H
