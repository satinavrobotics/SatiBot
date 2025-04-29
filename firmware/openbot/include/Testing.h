#ifndef TESTING_H
#define TESTING_H

#include "Config.h"
#include "Motors.h"
#include "Sensors.h"
#include "Communication.h"
#include "VelocityController.h"

// Test modes
#define TEST_NONE 0
#define TEST_MOTORS 1
#define TEST_SENSORS 2
#define TEST_COMMUNICATION 3
#define TEST_CONFIG 4
#define TEST_PINS 5
#define TEST_KALMAN 6
#define TEST_ANGULAR_VELOCITY 7

// Motor test submodes
#define MOTOR_TEST_LEFT_FORWARD 1
#define MOTOR_TEST_LEFT_BACKWARD 2
#define MOTOR_TEST_RIGHT_FORWARD 3
#define MOTOR_TEST_RIGHT_BACKWARD 4
#define MOTOR_TEST_BOTH_FORWARD 5
#define MOTOR_TEST_BOTH_BACKWARD 6
#define MOTOR_TEST_TURN_LEFT 7
#define MOTOR_TEST_TURN_RIGHT 8
#define MOTOR_TEST_SEQUENCE 9

// Communication test submodes
#define COMM_TEST_SERIAL 1
#define COMM_TEST_BLUETOOTH 2

// Pin test submodes
#define PIN_TEST_DIGITAL_HIGH 1
#define PIN_TEST_DIGITAL_LOW 2
#define PIN_TEST_DIGITAL_BLINK 3
#define PIN_TEST_ANALOG_RAMP 4
#define PIN_TEST_SEQUENCE 5

class Testing {
public:
    Testing(Config* config, Motors* motors, Sensors* sensors, Communication* communication, VelocityController* velocityController);

    // Initialize testing
    void begin();

    // Process commands from serial
    void processCommands();

    // Update test state
    void update();

    // Print test menu
    void printMenu();

    // Test functions
    void runMotorsTest(int submode);
    void runSensorsTest();
    void runCommunicationTest(int submode);
    void runConfigTest();
    void runPinsTest(int submode);
    void runKalmanTest();
    void runAngularVelocityTest();

private:
    Config* config;
    Motors* motors;
    Sensors* sensors;
    Communication* communication;
    VelocityController* velocityController;

    int currentTest;
    int currentSubmode;
    unsigned long lastUpdateTime;
    unsigned long testStartTime;

    // Motor test variables
    int motorSpeed;

    // Sensor test variables
    bool sensorsEnabled;

    // Communication test variables
    unsigned long lastMessageTime;

    // Config test variables
    int currentConfig;

    // Pin test variables
    static const int MAX_PINS = 10;
    int digitalOutputPins[MAX_PINS];
    int numDigitalOutputPins;
    int analogOutputPins[MAX_PINS];
    int numAnalogOutputPins;
    int currentPin;
    int analogValue;
    bool blinkState;

    // Helper functions
    void displayConfig(Config* config, const char* configName);
    void resetAllPins();
    void setupPins();
};

#endif // TESTING_H
