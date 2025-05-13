#ifndef CONFIG_H
#define CONFIG_H

#include <Arduino.h>

// Robot types
#define DIY 0        // DIY without PCB
#define DIY_ESP32 1  // DIY with ESP32

// MCU types
#define NANO 328     // Atmega328p
#define ESP32 32     // ESP32

// ESP32 specific defines
#if defined(ESP32)
#define attachPinChangeInterrupt attachInterrupt
#define detachPinChangeInterrupt detachInterrupt
#define digitalPinToPinChangeInterrupt digitalPinToInterrupt
#endif

// SatiBot is always V1 now

// Enable/Disable no phone mode (true/false)
// In no phone mode:
// - the motors will turn at 75% speed
// - the speed will be reduced if an obstacle is detected by the sonar sensor
// - the car will turn, if an obstacle is detected within TURN_DISTANCE
// WARNING: If the sonar sensor is not setup, the car will go full speed forward!
#define NO_PHONE_MODE false

// Enable/Disable debug print (true/false)
#define DEBUG_MODE false

// Enable/Disable PID controller for angular velocity (true/false)
#define PID_CONTROLLER_MODE true

class Config {
public:
    Config(uint8_t robotType);

    // Getters for configuration
    uint8_t getRobotType() const;
    uint8_t getMcuType() const;
    String getRobotTypeString() const;

    // Feature flags
    bool hasBluetoothSupport() const;
    bool hasSpeedSensorsFront() const;
    bool hasStatusLeds() const;

    // Pin configurations
    int getPinPwmL1() const;
    int getPinPwmL2() const;
    int getPinPwmR1() const;
    int getPinPwmR2() const;
    int getPinDirectionL() const;
    int getPinDirectionR() const;

    int getPinStopLeft() const;
    int getPinStopRight() const;

    // Sensor pins
    int getPinHallL() const;
    int getPinHallR() const;
    int getPinSdaIMU() const;
    int getPinSclIMU() const;
    int getPinVoltageDivider() const;

    // Global settings
    bool isNoPhoneMode() const;
    bool isDebugMode() const;
    bool isPidControllerMode() const;

    // No phone mode settings
    int getCtrlMax() const;
    int getCtrlSlow() const;
    int getCtrlMin() const;

    // Constants
    static const unsigned int TURN_DISTANCE = -1;  // cm
    static const unsigned int STOP_DISTANCE = 0;   // cm

private:
    uint8_t robotType;
    uint8_t mcuType;
    String robotTypeString;

    // Feature flags
    bool bluetoothSupport;
    bool speedSensorsFront;
    bool statusLeds;

    // Pin configurations
    int pinPwmL1;
    int pinPwmL2;
    int pinPwmR1;
    int pinPwmR2;
    int pinDirectionL;
    int pinDirectionR;
    int pinStopLeft;
    int pinStopRight;

    // Sensor pins
    int pinHallL;
    int pinHallR;
    int pinSdaIMU;
    int pinSclIMU;
    int pinVoltageDivider;


    // Global settings
    bool noPhoneMode;
    bool debugMode;
    bool pidControllerMode;

    // No phone mode settings
    int ctrlMax;
    int ctrlSlow;
    int ctrlMin;

    void initializeConfig();
};

extern Config* config;

#endif // CONFIG_H
