#include "../include/Config.h"

Config::Config(uint8_t robotType)
    : robotType(robotType),
      pidControllerMode(true) {

    initializeConfig();
}

void Config::initializeConfig() {
    // Set global settings from defines
    pidControllerMode = PID_CONTROLLER_MODE;

    // Set MCU type based on robot type
    if (robotType == DIY) {
        mcuType = NANO;
        robotTypeString = "Arduino";
        bluetoothSupport = false;
        speedSensorsFront = false;
        statusLeds = false;

        // Pin configurations for DIY (V1 only)
        pinPwmL1 = 9;
        pinPwmL2 = 0; // Not used in V1
        pinPwmR1 = 10;
        pinPwmR2 = 0; // Not used in V1
        pinDirectionL = 11;
        pinDirectionR = 12;

        // Sensor pins
        pinHallL = 0; // Hall effect sensor for left wheel
        pinHallR = 1; // Hall effect sensor for right wheel
        pinSdaIMU = 8; // SDA pin for I2C communication with IMU
        pinSclIMU = 9; // SCL pin for I2C communication with IMU
        pinVoltageDivider = 2; // Analog pin for battery voltage divider
        pinStopLeft = 5;
        pinStopRight = 21;
    }
    else if (robotType == DIY_ESP32) {
        mcuType = ESP32;
        robotTypeString = "ESP32";
        bluetoothSupport = true;
        speedSensorsFront = false;
        statusLeds = false;

        // Pin configurations for DIY_ESP32 (V1 only)
        pinPwmL1 = 6;
        pinPwmL2 = 0; // Not used in V1
        pinPwmR1 = 7;
        pinPwmR2 = 0; // Not used in V1
        pinDirectionL = 10;
        pinDirectionR = 20;

        // Sensor pins
        pinHallL = 0; // Hall effect sensor for left wheel
        pinHallR = 1; // Hall effect sensor for right wheel
        pinSdaIMU = 8; // SDA pin for I2C communication with IMU
        pinSclIMU = 9; // SCL pin for I2C communication with IMU
        pinVoltageDivider = 2; // Analog pin for battery voltage divider (ESP32)
        pinStopLeft = 5;
        pinStopRight = 21;
    }

    pinMode(pinPwmL1, OUTPUT);
    pinMode(pinPwmL2, OUTPUT);
    pinMode(pinPwmR1, OUTPUT);
    pinMode(pinPwmR2, OUTPUT);
    pinMode(pinDirectionL, OUTPUT);
    pinMode(pinDirectionR, OUTPUT);
    pinMode(pinStopLeft, OUTPUT);
    pinMode(pinStopRight, OUTPUT);

    pinMode(pinHallL, INPUT_PULLUP);
    pinMode(pinHallR, INPUT_PULLUP);
    pinMode(pinVoltageDivider, INPUT);
}


uint8_t Config::getRobotType() const {
    return robotType;
}

uint8_t Config::getMcuType() const {
    return mcuType;
}

String Config::getRobotTypeString() const {
    return robotTypeString;
}

bool Config::hasBluetoothSupport() const {
    return bluetoothSupport;
}

bool Config::hasSpeedSensorsFront() const {
    return speedSensorsFront;
}

bool Config::hasStatusLeds() const {
    return statusLeds;
}

int Config::getPinPwmL1() const {
    return pinPwmL1;
}

int Config::getPinPwmL2() const {
    return pinPwmL2;
}

int Config::getPinPwmR1() const {
    return pinPwmR1;
}

int Config::getPinPwmR2() const {
    return pinPwmR2;
}

int Config::getPinDirectionL() const {
    return pinDirectionL;
}

int Config::getPinDirectionR() const {
    return pinDirectionR;
}

int Config::getPinHallL() const {
    return pinHallL;
}

int Config::getPinHallR() const {
    return pinHallR;
}

int Config::getPinSdaIMU() const {
    return pinSdaIMU;
}

int Config::getPinSclIMU() const {
    return pinSclIMU;
}

int Config::getPinVoltageDivider() const {
    return pinVoltageDivider;
}

bool Config::isPidControllerMode() const {
    return pidControllerMode;
}

int Config::getPinStopLeft() const {
    return pinStopLeft;
}

int Config::getPinStopRight() const {
    return pinStopRight;
}
