#include "../include/Motors.h"

Motors::Motors(Config* config)
    : config(config),
      currentPwmLeft(0),
      currentPwmRight(0),
      stopEnabled(false),
      lastMotorUpdateTime(0) {
}

int Motors::getCurrentPwmLeft() const {
    return currentPwmLeft;
}

int Motors::getCurrentPwmRight() const {
    return currentPwmRight;
}

void Motors::updateVehicleWithAdjustments(PWMControlValues pwmValues) {
    // Get current time
    unsigned long now = millis();

    // Only update if enough time has passed since the last motor update
    if (now - lastMotorUpdateTime >= motorUpdateInterval) {
        // Update the timestamp
        lastMotorUpdateTime = now;

        // If stop is enabled, don't update motors and keep them at zero
        if (stopEnabled) {
            currentPwmLeft = 0;
            currentPwmRight = 0;
            analogWrite(config->getPinPwmL1(), 0);
            analogWrite(config->getPinPwmR1(), 0);
            return;
        }

        // Save the computed PWM values
        currentPwmLeft = pwmValues.leftPwm;
        currentPwmRight = pwmValues.rightPwm;

        // Left motor
        digitalWrite(config->getPinDirectionL(), (currentPwmLeft >= 0) ? LOW : HIGH);
        analogWrite(config->getPinPwmL1(), abs(currentPwmLeft));

        // Right motor
        digitalWrite(config->getPinDirectionR(), (currentPwmRight >= 0) ? HIGH : LOW);
        analogWrite(config->getPinPwmR1(), abs(currentPwmRight));
    }
}

void Motors::enableStop() {
    stopEnabled = true;

    // Set stop pins HIGH to enable emergency stop
    digitalWrite(config->getPinStopLeft(), HIGH);
    digitalWrite(config->getPinStopRight(), HIGH);

    // Immediately set PWM to 0 when stop is enabled
    analogWrite(config->getPinPwmL1(), 0);
    analogWrite(config->getPinPwmR1(), 0);

    // Reset current PWM values
    currentPwmLeft = 0;
    currentPwmRight = 0;
}

void Motors::disableStop() {
    stopEnabled = false;
    // Set stop pins LOW to disable emergency stop
    digitalWrite(config->getPinStopLeft(), LOW);
    digitalWrite(config->getPinStopRight(), LOW);
}

bool Motors::isStopEnabled() const {
    return stopEnabled;
}
