#include "../include/Motors.h"

Motors::Motors(Config* config)
    : config(config),
      ctrlLeft(0),
      ctrlRight(0),
      currentPwmLeft(0),
      currentPwmRight(0),
      lastUpdateLeft(0),
      lastUpdateRight(0),
      lastDirectionChangeLeft(0),
      lastDirectionChangeRight(0) {
}

void Motors::updateVehicle(int leftControl, int rightControl) {
    setLeftControl(leftControl);
    setRightControl(rightControl);
    updateLeftMotors(ctrlLeft);
    updateRightMotors(ctrlRight);
}

void Motors::updateLeftMotors(int controlValue) {
    if (config->getSatibotVersion() == SATIBOT_V1) {
        updateLeftMotorsV1();
    } else {
        updateLeftMotorsV0();
    }
}

void Motors::updateRightMotors(int controlValue) {
    if (config->getSatibotVersion() == SATIBOT_V1) {
        updateRightMotorsV1();
    } else {
        updateRightMotorsV0();
    }
}

void Motors::updateLeftMotorsV0() {
    if (ctrlLeft < 0) {
        analogWrite(config->getPinPwmL1(), -ctrlLeft);
        analogWrite(config->getPinPwmL2(), 0);
    } else if (ctrlLeft > 0) {
        analogWrite(config->getPinPwmL1(), 0);
        analogWrite(config->getPinPwmL2(), ctrlLeft);
    } else {
        stopLeftMotors();
    }
}

void Motors::updateRightMotorsV0() {
    if (ctrlRight < 0) {
        analogWrite(config->getPinPwmR1(), -ctrlRight);
        analogWrite(config->getPinPwmR2(), 0);
    } else if (ctrlRight > 0) {
        analogWrite(config->getPinPwmR1(), 0);
        analogWrite(config->getPinPwmR2(), ctrlRight);
    } else {
        stopRightMotors();
    }
}

void Motors::updateLeftMotorsV1() {
    unsigned long now = millis();
    if (now - lastUpdateLeft >= accelInterval) {
        lastUpdateLeft = now;

        // Determine target PWM as the absolute value of ctrlLeft
        int targetPwmLeft = ctrlLeft;

        // Check if direction needs to change
        bool directionChangeNeeded = (targetPwmLeft < 0 && currentPwmLeft >= 0) ||
                                     (targetPwmLeft > 0 && currentPwmLeft <= 0);

        if (directionChangeNeeded) {
            // Decelerate to zero before changing direction
            if (abs(currentPwmLeft) > 0) {
                if (currentPwmLeft > 0) {
                    currentPwmLeft -= accelStep;
                    if (currentPwmLeft < 0) currentPwmLeft = 0;
                } else {
                    currentPwmLeft += accelStep;
                    if (currentPwmLeft > 0) currentPwmLeft = 0;
                }
                if (currentPwmLeft == 0) lastDirectionChangeLeft = now;
            } else if (now - lastDirectionChangeLeft >= directionChangeDelay) {
                // Change direction when fully stopped
                currentPwmLeft = targetPwmLeft > 0 ? accelStep : -accelStep;
            }
        } else {
            // Gradually adjust current PWM toward target PWM
            if (abs(currentPwmLeft) < abs(targetPwmLeft)) {
                currentPwmLeft += (targetPwmLeft > 0) ? accelStep : -accelStep;
                if (abs(currentPwmLeft) > abs(targetPwmLeft))
                    currentPwmLeft = targetPwmLeft;
            } else if (abs(currentPwmLeft) > abs(targetPwmLeft)) {
                currentPwmLeft -= (currentPwmLeft > 0) ? accelStep : -accelStep;
                if (abs(currentPwmLeft) < abs(targetPwmLeft))
                    currentPwmLeft = targetPwmLeft;
            }
        }

        // Update direction pin based on sign of currentPwmLeft
        digitalWrite(config->getPinDirectionL(), (currentPwmLeft >= 0) ? HIGH : LOW);

        // Update PWM output on left motor
        analogWrite(config->getPinPwmL1(), abs(currentPwmLeft));
    }
}

void Motors::updateRightMotorsV1() {
    unsigned long now = millis();
    if (now - lastUpdateRight >= accelInterval) {
        lastUpdateRight = now;

        // Determine target PWM as the absolute value of ctrlRight
        int targetPwmRight = ctrlRight;

        // Check if direction needs to change
        bool directionChangeNeeded = (targetPwmRight < 0 && currentPwmRight >= 0) ||
                                     (targetPwmRight > 0 && currentPwmRight <= 0);

        if (directionChangeNeeded) {
            // Decelerate to zero before changing direction
            if (abs(currentPwmRight) > 0) {
                if (currentPwmRight > 0) {
                    currentPwmRight -= accelStep;
                    if (currentPwmRight < 0) currentPwmRight = 0;
                } else {
                    currentPwmRight += accelStep;
                    if (currentPwmRight > 0) currentPwmRight = 0;
                }
                if (currentPwmRight == 0) lastDirectionChangeRight = now;
            } else if (now - lastDirectionChangeRight >= directionChangeDelay) {
                // Change direction when fully stopped
                currentPwmRight = targetPwmRight > 0 ? accelStep : -accelStep;
            }
        } else {
            // Gradually adjust current PWM toward target PWM
            if (abs(currentPwmRight) < abs(targetPwmRight)) {
                currentPwmRight += (targetPwmRight > 0) ? accelStep : -accelStep;
                if (abs(currentPwmRight) > abs(targetPwmRight))
                    currentPwmRight = targetPwmRight;
            } else if (abs(currentPwmRight) > abs(targetPwmRight)) {
                currentPwmRight -= (currentPwmRight > 0) ? accelStep : -accelStep;
                if (abs(currentPwmRight) < abs(targetPwmRight))
                    currentPwmRight = targetPwmRight;
            }
        }

        // Update direction pin based on sign of currentPwmRight
        digitalWrite(config->getPinDirectionR(), (currentPwmRight >= 0) ? LOW : HIGH);

        // Update PWM output on right motor
        analogWrite(config->getPinPwmR1(), abs(currentPwmRight));
    }
}

void Motors::stopLeftMotors() {
    if (config->getSatibotVersion() == SATIBOT_V1) {
        analogWrite(config->getPinPwmL1(), 0);
    } else {
        analogWrite(config->getPinPwmL1(), 0);
        analogWrite(config->getPinPwmL2(), 0);
    }
}

void Motors::stopRightMotors() {
    if (config->getSatibotVersion() == SATIBOT_V1) {
        analogWrite(config->getPinPwmR1(), 0);
    } else {
        analogWrite(config->getPinPwmR1(), 0);
        analogWrite(config->getPinPwmR2(), 0);
    }
}

int Motors::getLeftControl() const {
    return ctrlLeft;
}

int Motors::getRightControl() const {
    return ctrlRight;
}

void Motors::setLeftControl(int value) {
    ctrlLeft = value;
}

void Motors::setRightControl(int value) {
    ctrlRight = value;
}
