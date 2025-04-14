# OpenBot Testing Module

This document explains how to use the integrated testing functionality in the OpenBot firmware.

## Overview

The OpenBot firmware now includes a built-in testing module that allows you to test individual components of the robot without uploading separate test sketches. The testing functionality is integrated into the main `openbot.ino` sketch and can be activated at any time.

## How to Enter Test Mode

There are two ways to enter test mode:

1. **During startup**: Press 't' or 'T' within the first second after the robot powers up.
2. **During normal operation**: Press 't' or 'T' at any time while the robot is running.

## Available Tests

Once in test mode, you'll see a menu of available tests:

```
OpenBot Test Mode
----------------
Available tests:
1 - Motors Test
2 - Sensors Test
3 - Communication Test
4 - Config Test
5 - Pins Test
0 - Exit Test Mode

Enter test number:
```

### 1. Motors Test

Tests the motor functionality by sending different control signals to the motors.

Submodes:
1. Test left motors forward
2. Test left motors backward
3. Test right motors forward
4. Test right motors backward
5. Test both motors forward
6. Test both motors backward
7. Test turn left
8. Test turn right
9. Run test sequence
0. Back to main menu

### 2. Sensors Test

Tests the sensors by continuously reading and displaying sensor values.

- Displays distance sensor readings
- Shows bumper events (if available)
- Press any key to toggle sensor readings on/off
- Enter 0 to return to main menu

### 3. Communication Test

Tests the communication functionality by sending test messages.

Submodes:
1. Test serial communication
2. Test Bluetooth communication (if available)
0. Back to main menu

### 4. Config Test

Displays configuration information for different robot types.

- Shows configuration details for DIY with SATIBOT_V0
- Shows configuration details for DIY with SATIBOT_V1
- Shows configuration details for DIY_ESP32
- Press any key to cycle through configurations
- Enter 0 to return to main menu

### 5. Pins Test

Tests individual pins on the microcontroller.

Submodes:
1. Test digital pins HIGH
2. Test digital pins LOW
3. Test digital pins BLINK
4. Test analog (PWM) pins with ramping value
5. Run test sequence
0. Back to main menu

## How to Exit Test Mode

To exit test mode and return to normal operation, press 'x' or 'X' at any time.

## Customizing the Tests

The pin configurations for the pin test are automatically set based on your robot's configuration, but you can modify them in the `Testing.cpp` file if needed:

```cpp
// In the Testing constructor
numDigitalOutputPins = 4;
digitalOutputPins[0] = config->getPinPwmL1();
digitalOutputPins[1] = config->getPinPwmL2();
digitalOutputPins[2] = config->getPinPwmR1();
digitalOutputPins[3] = config->getPinPwmR2();

numAnalogOutputPins = 2;
analogOutputPins[0] = config->getPinPwmL1();
analogOutputPins[1] = config->getPinPwmR1();
```

You can also adjust other test parameters like motor speed, test durations, etc. in the `Testing.cpp` file.

## Troubleshooting

If a test doesn't work as expected:

1. Check your robot's configuration (type, version, pins)
2. Verify hardware connections
3. Check the Serial Monitor for error messages
4. Try testing individual pins with the pin test to isolate issues
