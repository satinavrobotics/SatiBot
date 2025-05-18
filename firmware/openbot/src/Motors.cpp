#include "../include/Motors.h"

Motors::Motors(Config* config)
    : config(config),
      currentPwmLeft(0),
      currentPwmRight(0),
      lastDirectUpdateTime(0) {
}

int Motors::getCurrentPwmLeft() const {
    return currentPwmLeft;
}

int Motors::getCurrentPwmRight() const {
    return currentPwmRight;
}

void Motors::updateVehicle(float normalizedLinearVelocity, float headingAdjustment, bool noControl) {
    // Get current time
    unsigned long now = millis();

    // Only update if enough time has passed since the last direct update
    // Using a dedicated interval for direct updates
    if (now - lastDirectUpdateTime >= directUpdateInterval) {
        // Update the timestamp
        lastDirectUpdateTime = now;

        int MAX_PWM = 100;

        // Scale the normalized linear velocity to the PWM range [-255, 255]
        float scaledLinearVelocity = normalizedLinearVelocity * MAX_PWM;

        // Calculate left and right PWM values by adding/subtracting the heading adjustment
        // Left wheel: subtract heading adjustment (negative correction for left wheel)
        // Right wheel: add heading adjustment (positive correction for right wheel)
        int leftPwm, rightPwm;

        if (noControl) {
            // Special control mode when there's no target velocity
            // Only apply adjustment using the forward wheel, don't go backwards with the "other" wheel
            float scale = 2.0f * (abs(normalizedLinearVelocity) + 0.75f);
            float adjustment = headingAdjustment * scale;

            //if (headingAdjustment > epsilon) {
                // Need to turn right, so apply power to left wheel only
            //    leftPwm = 0;
            //    rightPwm = (int)(headingAdjustment * 9);
            //} else if (headingAdjustment < -epsilon) {
                // Need to turn left, so apply power to right wheel only
            //   leftPwm = (int)(headingAdjustment * 9);
            //    rightPwm = 0;
            //} else {
                // No adjustment needed
            //    leftPwm = 0;
            //    rightPwm = 0;
            //}
            // Ensure PWM values are within valid range and only positive (forward)
            //leftPwm = constrain(leftPwm, 0, 255);
            //rightPwm = constrain(rightPwm, 0, 255);
            leftPwm = (int)(scaledLinearVelocity - adjustment);
            rightPwm = (int)(scaledLinearVelocity + adjustment);
            if (scaledLinearVelocity > 0) {
                leftPwm = constrain(leftPwm, 0, 255);
                rightPwm = constrain(rightPwm, 0, 255);
            } else {
                leftPwm = constrain(leftPwm, -255, 0);
                rightPwm = constrain(rightPwm, -255, 0);
            }
        } else if (abs(scaledLinearVelocity) > 0.01f) {
            // Normal control mode with linear velocity
            // norm : 0.1 -> 0.75
            // 1-norm: 0.9 -> 0.25
            // 2.0 is handpicked value
            float scale = 6.5f * (abs(normalizedLinearVelocity) + 0.75f);
            float adjustment = headingAdjustment * scale;
            leftPwm = (int)(scaledLinearVelocity - adjustment);
            rightPwm = (int)(scaledLinearVelocity + adjustment);
            if (scaledLinearVelocity > 0) {
                leftPwm = constrain(leftPwm, 0, 255);
                rightPwm = constrain(rightPwm, 0, 255);
            } else {
                leftPwm = constrain(leftPwm, -255, 0);
                rightPwm = constrain(rightPwm, -255, 0);
            }
        } else {
            // Pure rotation mode (no linear velocity)
            leftPwm = (int)(-6 * headingAdjustment);
            rightPwm = (int)(+6 * headingAdjustment);
            // Ensure PWM values are within valid range
            leftPwm = constrain(leftPwm, -255, 255);
            rightPwm = constrain(rightPwm, -255, 255);
        }




        // Save the computed PWM values
        currentPwmLeft = leftPwm;
        currentPwmRight = rightPwm;


        // Left motor
        digitalWrite(config->getPinDirectionL(), (leftPwm >= 0) ? LOW : HIGH);
        analogWrite(config->getPinPwmL1(), abs(leftPwm));

        // Right motor
        digitalWrite(config->getPinDirectionR(), (rightPwm >= 0) ? HIGH : LOW);
        analogWrite(config->getPinPwmR1(), abs(rightPwm));
    }
}
