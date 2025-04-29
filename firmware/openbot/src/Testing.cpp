#include "../include/Testing.h"

Testing::Testing(Config* config, Motors* motors, Sensors* sensors, Communication* communication, VelocityController* velocityController)
    : config(config),
      motors(motors),
      sensors(sensors),
      communication(communication),
      velocityController(velocityController),
      currentTest(TEST_NONE),
      currentSubmode(0),
      lastUpdateTime(0),
      testStartTime(0),
      motorSpeed(192),
      sensorsEnabled(true),
      lastMessageTime(0),
      currentConfig(0),
      currentPin(0),
      analogValue(0),
      blinkState(false) {

    // Initialize pin arrays with default values
    // These should be customized based on the robot configuration
    numDigitalOutputPins = 4;
    digitalOutputPins[0] = config->getPinPwmL1();
    digitalOutputPins[1] = config->getPinPwmL2();
    digitalOutputPins[2] = config->getPinPwmR1();
    digitalOutputPins[3] = config->getPinPwmR2();

    numAnalogOutputPins = 2;
    analogOutputPins[0] = config->getPinPwmL1();
    analogOutputPins[1] = config->getPinPwmR1();
}
