#include "../include/VelocityController.h"

VelocityController::VelocityController(Config* config, Motors* motors, Sensors* sensors)
    : config(config),
      motors(motors),
      sensors(sensors),
      kp(20.0f),          // Default proportional gain (from cseled_test)
      ki(0.0f),           // Default integral gain (not used in cseled_test)
      kd(4.0f),           // Default derivative gain (from cseled_test)
      targetAngularVelocity(0.0f),
      targetLinearVelocity(0.0f),
      currentLinearVelocity(0.0f),
      rampedLinearVelocity(0.0f),
      normalizedLinearVelocity(0.0f),
      lastError(0.0f),
      integralError(0.0f),
      lastOutput(0.0f),
      headingAdjustment(0.0f),
      heading(0.0f),
      targetHeading(0.0f),
      noControl(false),
      lastUpdateTime(0),
      previousUpdateTime(0),
      updateInterval(100),  // 50ms update interval (more frequent than before)
      measuredDt(0.05f) {   // Default to 50ms in seconds
}

void VelocityController::begin() {
    reset();
    unsigned long currentTime = millis();
    lastUpdateTime = currentTime;
    previousUpdateTime = currentTime; // Initialize both timestamps to the same value
}

void VelocityController::setKp(float kp) {
    this->kp = kp;
}

void VelocityController::setKi(float ki) {
    this->ki = ki;
}

void VelocityController::setKd(float kd) {
    this->kd = kd;
}

float VelocityController::getKp() const {
    return kp;
}

float VelocityController::getKi() const {
    return ki;
}

float VelocityController::getKd() const {
    return kd;
}

void VelocityController::setTargetAngularVelocity(float targetVelocity) {
    //targetAngularVelocity = targetVelocity / 255.0f / 10.0f;
    targetAngularVelocity = targetVelocity / 255.0f / 2.0f;

    float targetLinear = getTargetLinearVelocity();
    if (abs(targetLinear) > 0.01f && abs(targetAngularVelocity) > 0.001f) {
        float scale = 1.0f - abs(targetLinear);
        targetAngularVelocity *= scale;
    }

    //if (targetLinear < 0) {
    //    targetAngularVelocity *= -1.0f;
    //}

    // With direct integration, we don't need to update target heading immediately
    // It will be updated in the next update() call

    // If angular velocity is zero, set heading adjustment to zero
    // but still allow linear velocity to ramp normally
    //if (abs(targetAngularVelocity) < 0.001f) {
    //    headingAdjustment = 0.0f;
    //}
}

float VelocityController::getTargetAngularVelocity() const {
    return targetAngularVelocity;
}

void VelocityController::setTargetLinearVelocity(float targetVelocity) {
    targetLinearVelocity = targetVelocity / 255.0f;
    // With ramping, we don't immediately update the normalized linear velocity
    // It will be updated gradually through the updateRampedLinearVelocity method

    // Linear velocity will be ramped in updateRampedLinearVelocity
    // No need to do anything special here
}

float VelocityController::getTargetLinearVelocity() const {
    return targetLinearVelocity;
}

float VelocityController::getNormalizedLinearVelocity() const {
    return normalizedLinearVelocity;
}

float VelocityController::getHeadingAdjustment() const {
    return headingAdjustment;
}

float VelocityController::getHeading() const {
    return heading;
}

float VelocityController::getTargetHeading() const {
    return targetHeading;
}

bool VelocityController::getNoControl() const {
    return noControl;
}


void VelocityController::update() {
    if (sensors == nullptr || motors == nullptr) {
        headingAdjustment = 0.0f;
        return;
    }

    unsigned long currentTime = millis();
    if (currentTime - lastUpdateTime < updateInterval) {
        // Don't update if not time yet, keep the current headingAdjustment
        return;
    }

    // Calculate actual dt from measured time difference
    previousUpdateTime = lastUpdateTime;
    measuredDt = (currentTime - previousUpdateTime) / 1000.0f; // Convert to seconds

    // Ensure dt is not zero or negative (could happen if millis() overflows)
    if (measuredDt <= 0.0f) {
        measuredDt = updateInterval / 1000.0f; // Fallback to default
    }

    // Get filtered yaw rate from IMU
    float omega = -getFilteredYawRate();

    // Update heading based on angular velocity
    updateHeading(omega);

    // Update ramped linear velocity (always do this to ensure smooth ramping)
    updateRampedLinearVelocity();
    //normalizedLinearVelocity = targetLinearVelocity / 255.0f;

    // Check if both target angular velocity and linear velocity are near zero
    // Set noControl flag when the robot has no target velocities
    if (abs(targetAngularVelocity) < 0.001f && abs(targetLinearVelocity) < 0.001f) {
        // Set noControl flag to true
        // noControl = true;
        // Continue with heading adjustment calculation instead of returning
        // This will allow the robot to adjust its heading using only the forward wheel
        if (!noControl) {
            heading = targetHeading;
            lastError = 0.0f;
        }
        noControl = true;
    } else {
        // Normal control mode
        noControl = false;
    }

    // Update target heading based on target angular velocity
    updateTargetHeading();

    // Calculate error between target heading and current heading
    float error = targetHeading - heading;

    // Wrap the error to the range [-PI, PI]
    float threshold1 = 2.0f;//0.2f;
    float threshold2 = 3.0f;//0.4f;
    if (error > threshold1) {
        if (error > threshold2) {
            reset();
        } else {
            error = threshold1;
        }
    }
    else if (error < -threshold1) {
        if (error < threshold2) {
            reset();
        } else {
            error = -threshold1;
        }
    }

    // Calculate derivative term using measured dt
    float derivative = (error - lastError) / measuredDt;

    // Calculate PD output (same as in cseled_test.ino)
    // Note: We're not using the integral term as it's not used in cseled_test
    headingAdjustment = kp * error + kd * derivative;

    // Store current error for next iteration
    lastError = error;

    // Store the output for debugging
    lastOutput = headingAdjustment;
    lastUpdateTime = currentTime;
}

// Controller is always enabled

void VelocityController::reset() {
    lastError = 0.0f;
    integralError = 0.0f;
    lastOutput = 0.0f;
    headingAdjustment = 0.0f;
    targetAngularVelocity = 0.0f;
    targetLinearVelocity = 0.0f;
    currentLinearVelocity = 0.0f;
    rampedLinearVelocity = 0.0f;
    normalizedLinearVelocity = 0.0f;
    heading = 0.0f;
    targetHeading = heading; // Reset target heading to match current heading
    noControl = false; // Reset noControl flag
    measuredDt = updateInterval / 1000.0f; // Reset to default dt value

    // Set heading adjustment to zero when reset is called
    // Linear velocity will still ramp normally
}

float VelocityController::getFilteredYawRate() {
    // Get the filtered yaw rate directly from the Sensors class
    if (sensors == nullptr) return 0.0f;
    return sensors->getAngularVelocityFromIMU();
}

void VelocityController::updateHeading(float omega) {
    // Use the measured dt value from the update method
    // Update heading by integrating angular velocity
    heading += omega * measuredDt;

    // Normalize heading to keep it within a reasonable range
    // This prevents potential issues with very large heading values over time
    // 2*PI radians = 360 degrees = one full rotation
    //while (heading > TWO_PI)
    //{
    //    heading -= TWO_PI;
    //    targetHeading -= TWO_PI;
    //}
    //while (heading < -TWO_PI)
    //{
    //    heading += TWO_PI ;
    //    targetHeading += TWO_PI;
    //}
}

void VelocityController::updateTargetHeading() {
    // Use the measured dt value from the update method
    // Update target heading by integrating target angular velocity
    // This is direct integration: targetHeading += targetAngularVelocity * measuredDt
    targetHeading += targetAngularVelocity * measuredDt;

    // Normalize target heading to keep it within a reasonable range
    // Using the predefined TWO_PI constant from Arduino.h
    //while (targetHeading > TWO_PI)
    //{
    //    targetHeading -= TWO_PI;
    //    heading -= TWO_PI;
    //}

    //while (targetHeading < -TWO_PI)
    //{
    //    targetHeading += TWO_PI;
    //    heading += TWO_PI;
    //}

}

void VelocityController::updateCurrentLinearVelocity() {
    if (motors != nullptr) {
        // Get current PWM values from motors
        int leftPWM = motors->getCurrentPwmLeft();
        int rightPWM = motors->getCurrentPwmRight();

        // Calculate current linear velocity as average of left and right PWM
        // Normalize to range [-1, 1]
        currentLinearVelocity = (leftPWM + rightPWM) / (2.0f * 30.0f);
    }
}

void VelocityController::updateRampedLinearVelocity() {
    // Update current linear velocity first
    // updateCurrentLinearVelocity();

    // If we're already at the target, no need to ramp
    if (rampedLinearVelocity == targetLinearVelocity) {
        normalizedLinearVelocity = rampedLinearVelocity;
        return;
    }

    // Calculate the change needed
    float change = targetLinearVelocity - rampedLinearVelocity;

    // Determine if we're speeding up or slowing down
    bool isSpeedingUp = false;

    // If both values have the same sign (both positive or both negative)
    if ((rampedLinearVelocity >= 0 && targetLinearVelocity >= 0) ||
        (rampedLinearVelocity <= 0 && targetLinearVelocity <= 0)) {
        // We're speeding up if the absolute value is increasing
        isSpeedingUp = abs(targetLinearVelocity) > abs(rampedLinearVelocity);
    } else {
        // If signs are different, we're always speeding up when moving away from zero
        isSpeedingUp = false;
    }

    // Choose appropriate rate based on whether we're speeding up or slowing down
    float rampRate;
    if (isSpeedingUp) {
        // Moving away from zero (speeding up) - use acceleration rate
        rampRate = LINEAR_ACCELERATION_RATE;
    } else {
        // Moving towards zero (slowing down) - use deceleration rate
        rampRate = LINEAR_DECELERATION_RATE;
    }

    // Calculate increment based on selected rate
    float increment = rampRate * measuredDt;

    // Apply the increment in the correct direction
    if (change > 0) {
        rampedLinearVelocity += increment;
        // Ensure we don't overshoot
        if (rampedLinearVelocity > targetLinearVelocity) {
            rampedLinearVelocity = targetLinearVelocity;
        }
    } else {
        rampedLinearVelocity -= increment;
        // Ensure we don't undershoot
        if (rampedLinearVelocity < targetLinearVelocity) {
            rampedLinearVelocity = targetLinearVelocity;
        }
    }

    // Update normalized linear velocity based on ramped value
    normalizedLinearVelocity = rampedLinearVelocity;
}

