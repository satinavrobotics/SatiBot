#include "../include/Config.h"

Config::Config(uint8_t robotType, uint8_t satibotVersion)
    : robotType(robotType),
      satibotVersion(satibotVersion),
      noPhoneMode(false),
      debugMode(false),
      ctrlMax(192),
      ctrlSlow(96),
      ctrlMin(30) {

    initializeConfig();
}

void Config::initializeConfig() {
    // Set global settings from defines
    noPhoneMode = NO_PHONE_MODE;
    debugMode = DEBUG_MODE;

    // Set MCU type based on robot type
    if (robotType == DIY) {
        mcuType = NANO;
        robotTypeString = "DIY";
        bluetoothSupport = false;
        speedSensorsFront = false;
        statusLeds = false;

        // Pin configurations for DIY
        if (satibotVersion == SATIBOT_V1) {
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
        } else {
            pinPwmL1 = 9;
            pinPwmL2 = 10;
            pinPwmR1 = 20;
            pinPwmR2 = 21;
            pinDirectionL = 0; // Not used in V0
            pinDirectionR = 0; // Not used in V0

            // Sensor pins
            pinHallL = 0; // Hall effect sensor for left wheel
            pinHallR = 1; // Hall effect sensor for right wheel
            pinSdaIMU = 8; // SDA pin for I2C communication with IMU
            pinSclIMU = 9; // SCL pin for I2C communication with IMU
        }
    }
    else if (robotType == DIY_ESP32) {
        mcuType = ESP32;
        robotTypeString = "DIY_ESP32";
        bluetoothSupport = true;
        speedSensorsFront = false;
        statusLeds = false;

        // Pin configurations for DIY_ESP32
        pinPwmL1 = 10;
        pinPwmL2 = 9;
        pinPwmR1 = 21;
        pinPwmR2 = 20;
        pinDirectionL = 0; // Not used
        pinDirectionR = 0; // Not used

        // Sensor pins
        pinHallL = 0; // Hall effect sensor for left wheel
        pinHallR = 1; // Hall effect sensor for right wheel
        pinSdaIMU = 8; // SDA pin for I2C communication with IMU
        pinSclIMU = 9; // SCL pin for I2C communication with IMU
    }
}

uint8_t Config::getRobotType() const {
    return robotType;
}

uint8_t Config::getMcuType() const {
    return mcuType;
}

uint8_t Config::getSatibotVersion() const {
    return satibotVersion;
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

bool Config::isNoPhoneMode() const {
    return noPhoneMode;
}

bool Config::isDebugMode() const {
    return debugMode;
}

int Config::getCtrlMax() const {
    return ctrlMax;
}

int Config::getCtrlSlow() const {
    return ctrlSlow;
}

int Config::getCtrlMin() const {
    return ctrlMin;
}
