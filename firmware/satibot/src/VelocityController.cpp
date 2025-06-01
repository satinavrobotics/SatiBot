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
      noControlAdjusted(false),
      lastUpdateTime(0),
      previousUpdateTime(0),
      updateInterval(100),  // 50ms update interval (more frequent than before)
      measuredDt(0.05f),    // Default to 50ms in seconds

      // Initialize motor control parameters with default values
      noControlScaleFactor(2.0f),     // Default scale factor for noControl mode
      normalControlScaleFactor(6.5f), // Default scale factor for normal control mode
      rotationScaleFactor(6.0f),      // Default scale factor for pure rotation mode
      velocityBias(0.75f),            // Default bias added to velocity for scaling calculations
      rotationBias(0.0f) {            // Default rotation bias (set to zero as requested)
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

// Motor control parameter setters
void VelocityController::setNoControlScaleFactor(float factor) {
    this->noControlScaleFactor = factor;
}

void VelocityController::setNormalControlScaleFactor(float factor) {
    this->normalControlScaleFactor = factor;
}

void VelocityController::setRotationScaleFactor(float factor) {
    this->rotationScaleFactor = factor;
}

void VelocityController::setVelocityBias(float bias) {
    this->velocityBias = bias;
}

void VelocityController::setRotationBias(float bias) {
    this->rotationBias = bias;
}

// Motor control parameter getters
float VelocityController::getNoControlScaleFactor() const {
    return noControlScaleFactor;
}

float VelocityController::getNormalControlScaleFactor() const {
    return normalControlScaleFactor;
}

float VelocityController::getRotationScaleFactor() const {
    return rotationScaleFactor;
}

float VelocityController::getVelocityBias() const {
    return velocityBias;
}

float VelocityController::getRotationBias() const {
    return rotationBias;
}

// Combined parameter setter for Bluetooth control
void VelocityController::setControlParameters(float kp, float kd, float noControlScale, float normalControlScale,
                                             float rotationScale, float velocityBias, float rotationBias) {
    this->kp = kp;
    this->kd = kd;
    this->noControlScaleFactor = noControlScale;
    this->normalControlScaleFactor = normalControlScale;
    this->rotationScaleFactor = rotationScale;
    this->velocityBias = velocityBias;
    this->rotationBias = rotationBias;
}

void VelocityController::setTargetAngularVelocity(float targetVelocity) {
    targetAngularVelocity = targetVelocity / 255.0f;

    float targetLinear = getTargetLinearVelocity();
    if (abs(targetLinear) > 0.01f && abs(targetAngularVelocity) > 0.001f) {
        float scale = 1.0f - abs(targetLinear);
        targetAngularVelocity *= scale;

        if (targetLinear < 0) {
            targetAngularVelocity *= -1.0f;
        }
    }
}

float VelocityController::getTargetAngularVelocity() const {
    return targetAngularVelocity;
}

void VelocityController::setTargetLinearVelocity(float targetVelocity) {
    targetLinearVelocity = targetVelocity / 255.0f;
}

float VelocityController::getTargetLinearVelocity() const {
    return targetLinearVelocity;
}

void VelocityController::setEmergencyStop(bool enable) {
    if (motors != nullptr) {
        if (enable) {
            motors->enableStop();
        } else {
            motors->disableStop();
        }
    }
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

bool VelocityController::getNoControlAdjusted() const {
    return noControlAdjusted;
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
        // Normal control mode - reset both flags when we get control signals
        noControl = false;
        noControlAdjusted = false;
    }

    // Update target heading based on target angular velocity
    updateTargetHeading();

    // Handle noControlAdjusted state logic
    if (noControl && !noControlAdjusted) {
        // Check if heading is well adjusted (small error)
        float tempError = targetHeading - heading;
        if (abs(tempError) < 0.1f) { // Small error threshold (about 5.7 degrees)
            // Transition to noControlAdjusted state
            noControlAdjusted = true;
            // Set target heading to current heading to prevent corrections
            targetHeading = heading;
        }
    }

    // In noControlAdjusted state, always track current heading
    if (noControlAdjusted) {
        targetHeading = heading;
    }

    // Calculate error between target heading and current heading
    float error = targetHeading - heading;

    // Wrap the error to the range [-PI, PI]
    float threshold1 = 2.0f;
    float threshold2 = 3.0f;
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
    noControlAdjusted = false; // Reset noControlAdjusted flag
    measuredDt = updateInterval / 1000.0f; // Reset to default dt value
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
}

void VelocityController::updateTargetHeading() {
    // Use the measured dt value from the update method
    // Update target heading by integrating target angular velocity
    // This is direct integration: targetHeading += targetAngularVelocity * measuredDt
    targetHeading += targetAngularVelocity * measuredDt;
}


void VelocityController::updateRampedLinearVelocity() {
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

void VelocityController::computeMotorAdjustments(int& leftAdjustment, int& rightAdjustment, float scaledLinearVelocity) {
    if (noControlAdjusted) {
        // No control adjusted mode - heading is stabilized, don't apply corrections
        // Robot should not try to correct heading when moved by external forces
        leftAdjustment = 0;
        rightAdjustment = 0;
    } else if (noControl) {
        // Special control mode when there's no target velocity
        // Only apply adjustment using the forward wheel, don't go backwards with the "other" wheel
        float scale = noControlScaleFactor * (abs(normalizedLinearVelocity) + velocityBias);
        float adjustment = headingAdjustment * scale;

        leftAdjustment = (int)(scaledLinearVelocity - adjustment);
        rightAdjustment = (int)(scaledLinearVelocity + adjustment);
        if (scaledLinearVelocity > 0) {
            leftAdjustment = constrain(leftAdjustment, 0, 255);
            rightAdjustment = constrain(rightAdjustment, 0, 255);
        } else {
            leftAdjustment = constrain(leftAdjustment, -255, 0);
            rightAdjustment = constrain(rightAdjustment, -255, 0);
        }
    } else if (abs(scaledLinearVelocity) > 0.01f) {
        // Normal control mode with linear velocity
        float scale = normalControlScaleFactor * (abs(normalizedLinearVelocity) + velocityBias);
        float adjustment = headingAdjustment * scale;
        leftAdjustment = (int)(scaledLinearVelocity - adjustment);
        rightAdjustment = (int)(scaledLinearVelocity + adjustment);
        if (scaledLinearVelocity > 0) {
            leftAdjustment = constrain(leftAdjustment, 0, 255);
            rightAdjustment = constrain(rightAdjustment, 0, 255);
        } else {
            leftAdjustment = constrain(leftAdjustment, -255, 0);
            rightAdjustment = constrain(rightAdjustment, -255, 0);
        }
    } else {
        // Pure rotation mode (no linear velocity)
        leftAdjustment = (int)(-rotationScaleFactor * headingAdjustment + rotationBias);
        rightAdjustment = (int)(+rotationScaleFactor * headingAdjustment + rotationBias);
        // Ensure PWM values are within valid range
        leftAdjustment = constrain(leftAdjustment, -255, 255);
        rightAdjustment = constrain(rightAdjustment, -255, 255);
    }
}

PWMControlValues VelocityController::computeMotorPWM() {
    PWMControlValues pwmValues = {0, 0};

    if (motors == nullptr) {
        return pwmValues;
    }

    // Scale the normalized linear velocity to the PWM range using hardcoded MAX_PWM
    float scaledLinearVelocity = normalizedLinearVelocity * MAX_PWM;

    // Compute the motor adjustments
    int leftPwm, rightPwm;
    computeMotorAdjustments(leftPwm, rightPwm, scaledLinearVelocity);

    pwmValues.leftPwm = leftPwm;
    pwmValues.rightPwm = rightPwm;

    return pwmValues;
}


