#include "../include/Testing.h"

Testing::Testing(Config* config, Motors* motors, Sensors* sensors, Communication* communication)
    : config(config),
      motors(motors),
      sensors(sensors),
      communication(communication),
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

void Testing::begin() {
    Serial.println("OpenBot Test Mode");
    Serial.println("----------------");
    printMenu();
}

void Testing::printMenu() {
    Serial.println("\nAvailable tests:");
    Serial.println("1 - Motors Test");
    Serial.println("2 - Sensors Test");
    Serial.println("3 - Communication Test");
    Serial.println("4 - Config Test");
    Serial.println("5 - Pins Test");
    Serial.println("6 - Kalman Filter Test");
    Serial.println("0 - Exit Test Mode");
    Serial.println("\nEnter test number:");
}

void Testing::processCommands() {
    if (Serial.available() > 0) {
        int command = Serial.parseInt();

        // If we're not in a test yet, select a test
        if (currentTest == TEST_NONE) {
            if (command >= TEST_MOTORS && command <= TEST_KALMAN) {
                currentTest = command;
                currentSubmode = 0;

                // Handle each test type separately to avoid switch statement issues
                if (currentTest == TEST_MOTORS) {
                    Serial.println("\nMotors Test");
                    Serial.println("----------");
                    Serial.println("1 - Test left motors forward");
                    Serial.println("2 - Test left motors backward");
                    Serial.println("3 - Test right motors forward");
                    Serial.println("4 - Test right motors backward");
                    Serial.println("5 - Test both motors forward");
                    Serial.println("6 - Test both motors backward");
                    Serial.println("7 - Test turn left");
                    Serial.println("8 - Test turn right");
                    Serial.println("9 - Run test sequence");
                    Serial.println("0 - Back to main menu");
                    Serial.println("\nEnter submode:");
                }
                else if (currentTest == TEST_SENSORS) {
                    Serial.println("\nSensors Test");
                    Serial.println("-----------");
                    Serial.println("Continuously reading sensor values.");
                    Serial.println("Move objects in front of distance sensor to see readings change.");
                    Serial.println("Press any key to stop/start readings.");
                    Serial.println("Enter 0 to return to main menu.");
                    sensorsEnabled = true;
                }
                else if (currentTest == TEST_COMMUNICATION) {
                    Serial.println("\nCommunication Test");
                    Serial.println("-----------------");
                    Serial.println("1 - Test serial communication");
                    Serial.println("2 - Test Bluetooth communication (if available)");
                    Serial.println("0 - Back to main menu");
                    Serial.println("\nEnter submode:");
                }
                else if (currentTest == TEST_CONFIG) {
                    Serial.println("\nConfig Test");
                    Serial.println("-----------");
                    Serial.println("This test displays configuration information for different robot types.");
                    Serial.println("Press any key to cycle through configurations.");
                    Serial.println("Enter 0 to return to main menu.");

                    // Display first configuration
                    Config configDiyV0(DIY, SATIBOT_V0);
                    displayConfig(&configDiyV0, "DIY with SATIBOT_V0");
                    currentConfig = 0;
                }
                else if (currentTest == TEST_PINS) {
                    Serial.println("\nPin Test");
                    Serial.println("--------");
                    Serial.println("1 - Test digital pins HIGH");
                    Serial.println("2 - Test digital pins LOW");
                    Serial.println("3 - Test digital pins BLINK");
                    Serial.println("4 - Test analog (PWM) pins with ramping value");
                    Serial.println("5 - Run test sequence");
                    Serial.println("0 - Back to main menu");
                    Serial.println("\nEnter submode:");

                    // Setup pins for testing
                    setupPins();
                }
                else if (currentTest == TEST_KALMAN) {
                    Serial.println("\nKalman Filter Test");
                    Serial.println("-----------------");
                    Serial.println("Continuously reading and fusing sensor values.");
                    Serial.println("Move the robot to see the effect of sensor fusion.");
                    Serial.println("Press any key to stop/start readings.");
                    Serial.println("Enter 0 to return to main menu.");
                    sensorsEnabled = true;
                }
            } else if (command == TEST_NONE) {
                Serial.println("Exiting test mode");
            }
        }
        // If we're in a test, process submode or exit
        else {
            if (command == 0) {
                // Exit current test
                currentTest = TEST_NONE;

                // Stop motors if they were running
                motors->setLeftControl(0);
                motors->setRightControl(0);
                motors->updateVehicle(0, 0);

                // Reset pins if they were being tested
                resetAllPins();

                printMenu();
            } else {
                // Process submode for current test
                if (currentTest == TEST_MOTORS) {
                    if (command >= MOTOR_TEST_LEFT_FORWARD && command <= MOTOR_TEST_SEQUENCE) {
                        currentSubmode = command;
                        runMotorsTest(currentSubmode);
                    }
                }
                else if (currentTest == TEST_SENSORS) {
                    // Any key toggles sensor readings
                    sensorsEnabled = !sensorsEnabled;
                    if (sensorsEnabled) {
                        Serial.println("Sensor readings enabled");
                    } else {
                        Serial.println("Sensor readings disabled");
                    }
                }
                else if (currentTest == TEST_COMMUNICATION) {
                    if (command == COMM_TEST_SERIAL || command == COMM_TEST_BLUETOOTH) {
                        currentSubmode = command;
                        Serial.print("Running test: ");
                        Serial.println(currentSubmode == COMM_TEST_SERIAL ? "Serial Communication" : "Bluetooth Communication");
                    }
                }
                else if (currentTest == TEST_CONFIG) {
                    // Any key cycles through configurations
                    currentConfig = (currentConfig + 1) % 3;

                    if (currentConfig == 0) {
                        Config configDiyV0(DIY, SATIBOT_V0);
                        displayConfig(&configDiyV0, "DIY with SATIBOT_V0");
                    }
                    else if (currentConfig == 1) {
                        Config configDiyV1(DIY, SATIBOT_V1);
                        displayConfig(&configDiyV1, "DIY with SATIBOT_V1");
                    }
                    else if (currentConfig == 2) {
                        Config configDiyEsp32(DIY_ESP32, SATIBOT_V0);
                        displayConfig(&configDiyEsp32, "DIY_ESP32 with SATIBOT_V0");
                    }
                }
                else if (currentTest == TEST_PINS) {
                    if (command >= PIN_TEST_DIGITAL_HIGH && command <= PIN_TEST_SEQUENCE) {
                        currentSubmode = command;
                        currentPin = 0;
                        lastUpdateTime = millis();
                        resetAllPins();

                        Serial.print("Running test: ");
                        if (currentSubmode == PIN_TEST_DIGITAL_HIGH) {
                            Serial.println("Digital pins HIGH");
                        }
                        else if (currentSubmode == PIN_TEST_DIGITAL_LOW) {
                            Serial.println("Digital pins LOW");
                        }
                        else if (currentSubmode == PIN_TEST_DIGITAL_BLINK) {
                            Serial.println("Digital pins BLINK");
                        }
                        else if (currentSubmode == PIN_TEST_ANALOG_RAMP) {
                            Serial.println("Analog pins RAMP");
                            analogValue = 0;
                        }
                        else if (currentSubmode == PIN_TEST_SEQUENCE) {
                            Serial.println("Test sequence");
                        }
                    }
                }
            }
        }
    }
}

void Testing::update() {
    unsigned long now = millis();

    // Update current test
    if (currentTest == TEST_MOTORS) {
        if (currentSubmode == MOTOR_TEST_SEQUENCE) {
            // If running sequence, change test every 3 seconds
            if (now - lastUpdateTime >= 3000) {
                lastUpdateTime = now;
                static int sequenceStep = 1;

                // Cycle through tests 1-8
                sequenceStep = (sequenceStep % 8) + 1;
                runMotorsTest(sequenceStep);

                Serial.print("Running test: ");
                Serial.println(sequenceStep);
            }
        }
    }
    else if (currentTest == TEST_SENSORS) {
        if (sensorsEnabled && (now - lastUpdateTime >= 500)) {
            lastUpdateTime = now;
            runSensorsTest();
        }
    }
    else if (currentTest == TEST_COMMUNICATION) {
        if (currentSubmode > 0) {
            runCommunicationTest(currentSubmode);
        }
    }
    else if (currentTest == TEST_PINS) {
        if (currentSubmode > 0) {
            runPinsTest(currentSubmode);
        }
    }
    else if (currentTest == TEST_KALMAN) {
        if (sensorsEnabled && (now - lastUpdateTime >= 500)) {
            lastUpdateTime = now;
            runKalmanTest();
        }
    }
}

void Testing::runMotorsTest(int submode) {
    // Stop motors before starting new test
    motors->setLeftControl(0);
    motors->setRightControl(0);
    motors->updateVehicle(0, 0);
    delay(500); // Short pause

    switch (submode) {
        case MOTOR_TEST_LEFT_FORWARD:
            Serial.println("Testing left motors forward");
            motors->setLeftControl(motorSpeed);
            motors->setRightControl(0);
            break;

        case MOTOR_TEST_LEFT_BACKWARD:
            Serial.println("Testing left motors backward");
            motors->setLeftControl(-motorSpeed);
            motors->setRightControl(0);
            break;

        case MOTOR_TEST_RIGHT_FORWARD:
            Serial.println("Testing right motors forward");
            motors->setLeftControl(0);
            motors->setRightControl(motorSpeed);
            break;

        case MOTOR_TEST_RIGHT_BACKWARD:
            Serial.println("Testing right motors backward");
            motors->setLeftControl(0);
            motors->setRightControl(-motorSpeed);
            break;

        case MOTOR_TEST_BOTH_FORWARD:
            Serial.println("Testing both motors forward");
            motors->setLeftControl(motorSpeed);
            motors->setRightControl(motorSpeed);
            break;

        case MOTOR_TEST_BOTH_BACKWARD:
            Serial.println("Testing both motors backward");
            motors->setLeftControl(-motorSpeed);
            motors->setRightControl(-motorSpeed);
            break;

        case MOTOR_TEST_TURN_LEFT:
            Serial.println("Testing turn left");
            motors->setLeftControl(-motorSpeed);
            motors->setRightControl(motorSpeed);
            break;

        case MOTOR_TEST_TURN_RIGHT:
            Serial.println("Testing turn right");
            motors->setLeftControl(motorSpeed);
            motors->setRightControl(-motorSpeed);
            break;

        case MOTOR_TEST_SEQUENCE:
            Serial.println("Starting test sequence");
            // This will cycle through all tests
            lastUpdateTime = millis();
            break;
    }

    // Apply motor controls
    motors->updateVehicle(motors->getLeftControl(), motors->getRightControl());
}

void Testing::runSensorsTest() {
    // Read IMU data
    sensors->readIMU();

    // Display sensor data
    Serial.println("-------------------");
    Serial.println("Sensor Readings:");

    // IMU data
    Serial.println("\nIMU Data:");
    Serial.print("Acceleration (m/s^2): X=");
    Serial.print(sensors->getAx());
    Serial.print(", Y=");
    Serial.print(sensors->getAy());
    Serial.print(", Z=");
    Serial.println(sensors->getAz());

    Serial.print("Gyroscope (rad/s): X=");
    Serial.print(sensors->getGx());
    Serial.print(", Y=");
    Serial.print(sensors->getGy());
    Serial.print(", Z=");
    Serial.println(sensors->getGz());

    // Wheel encoder data
    Serial.println("\nWheel Encoders:");
    Serial.print("Left wheel count: ");
    Serial.println(sensors->getLeftWheelCount());
    Serial.print("Right wheel count: ");
    Serial.println(sensors->getRightWheelCount());

    // Velocity calculations
    Serial.println("\nLinear Velocity:");
    Serial.print("Robot (m/s): ");
    Serial.println(sensors->getLinearVelocity());
    Serial.print("Left Wheel (m/s): ");
    Serial.println(sensors->getLeftWheelVelocity());
    Serial.print("Right Wheel (m/s): ");
    Serial.println(sensors->getRightWheelVelocity());

    Serial.println("\nAngular Velocity:");
    Serial.print("From IMU (rad/s): ");
    Serial.println(sensors->getAngularVelocityFromIMU());
    Serial.print("From Odometry (rad/s): ");
    Serial.println(sensors->getAngularVelocityFromOdometry());

    Serial.println("-------------------");
}

void Testing::runCommunicationTest(int submode) {
    // Process any incoming messages
    communication->processIncomingMessages();

    unsigned long now = millis();

    switch (submode) {
        case COMM_TEST_SERIAL:
            // Send a test message periodically
            if (now - lastMessageTime >= 2000) {
                lastMessageTime = now;

                // Send a test message
                String message = "t" + String(now / 1000); // 't' prefix for test message, followed by uptime in seconds
                communication->sendData(message);

                Serial.println("Sent test message via Serial. Check if your app/receiver got it.");
                Serial.println("You can also send 'c100,100' to test motor control commands.");
            }
            break;

        case COMM_TEST_BLUETOOTH:
            #if defined(ESP32)
            if (config->hasBluetoothSupport()) {
                // Update Bluetooth connection
                communication->updateBluetoothConnection();

                // Send a test message periodically
                if (now - lastMessageTime >= 2000) {
                    lastMessageTime = now;

                    // Send a test message
                    String message = "t" + String(now / 1000); // 't' prefix for test message, followed by uptime in seconds
                    communication->sendData(message);

                    Serial.println("Sent test message via Bluetooth. Check if your app/receiver got it.");
                }
            } else {
                Serial.println("Bluetooth not supported on this device.");
                currentSubmode = 0; // Reset submode
            }
            #else
            Serial.println("Bluetooth not supported on this device.");
            currentSubmode = 0; // Reset submode
            #endif
            break;
    }
}

void Testing::runConfigTest() {
    // This is handled in processCommands() since it's triggered by user input
}

void Testing::runKalmanTest() {
    // Read IMU data
    sensors->readIMU();

    // Get current time for sensor validity checks
    unsigned long currentTime = millis();

    // Check sensor availability
    bool imuAvailable = sensors->isIMUInitialized();
    bool wheelsAvailable = (currentTime - sensors->getLastRPMCalcTime() < 1000);

    // Display sensor data and Kalman filter estimates
    Serial.println("-------------------");
    Serial.println("Kalman Filter Test:");

    // Sensor availability
    Serial.println("\nSensor Status:");
    Serial.print("IMU: ");
    Serial.println(imuAvailable ? "CONNECTED" : "NOT CONNECTED");
    Serial.print("Wheel Encoders: ");
    Serial.println(wheelsAvailable ? "ACTIVE" : "INACTIVE");

    // Angular velocity measurements and estimate
    Serial.println("\nAngular Velocity (rad/s):");
    Serial.print("From IMU: ");
    if (imuAvailable) {
        Serial.println(sensors->getAngularVelocityFromIMU(), 4);
    } else {
        Serial.println("N/A");
    }

    Serial.print("From Odometry: ");
    if (wheelsAvailable) {
        Serial.println(sensors->getAngularVelocityFromOdometry(), 4);
    } else {
        Serial.println("N/A");
    }

    Serial.print("Kalman Estimate: ");
    Serial.println(sensors->getFusedAngularVelocity(), 4);

    // Linear velocity measurements and estimate
    Serial.println("\nLinear Velocity (m/s):");
    Serial.print("From Wheels: ");
    if (wheelsAvailable) {
        Serial.println(sensors->getLinearVelocity(), 4);
    } else {
        Serial.println("N/A");
    }

    Serial.print("Kalman Estimate: ");
    Serial.println(sensors->getFusedLinearVelocity(), 4);

    Serial.println("-------------------");
}

void Testing::runPinsTest(int submode) {
    unsigned long now = millis();

    if (submode == PIN_TEST_DIGITAL_HIGH) {
        if (now - lastUpdateTime >= 1000) {
            lastUpdateTime = now;

            // Reset previous pin
            if (currentPin > 0) {
                digitalWrite(digitalOutputPins[currentPin - 1], LOW);
            }

            // Set current pin HIGH
            if (currentPin < numDigitalOutputPins) {
                digitalWrite(digitalOutputPins[currentPin], HIGH);
                Serial.print("Setting digital pin ");
                Serial.print(digitalOutputPins[currentPin]);
                Serial.println(" HIGH");
                currentPin++;
            } else {
                // End of test
                Serial.println("Test completed. Enter a new submode or 0 to exit.");
                currentSubmode = 0;
                resetAllPins();
            }
        }
    }
    else if (submode == PIN_TEST_DIGITAL_LOW) {
        if (now - lastUpdateTime >= 1000) {
            lastUpdateTime = now;

            // Reset previous pin (set HIGH)
            if (currentPin > 0) {
                digitalWrite(digitalOutputPins[currentPin - 1], HIGH);
            }

            // Set current pin LOW
            if (currentPin < numDigitalOutputPins) {
                digitalWrite(digitalOutputPins[currentPin], LOW);
                Serial.print("Setting digital pin ");
                Serial.print(digitalOutputPins[currentPin]);
                Serial.println(" LOW");
                currentPin++;
            } else {
                // End of test
                Serial.println("Test completed. Enter a new submode or 0 to exit.");
                currentSubmode = 0;
                resetAllPins();
            }
        }
    }
    else if (submode == PIN_TEST_DIGITAL_BLINK) {
        if (now - lastUpdateTime >= 500) { // Blink every 500ms
            lastUpdateTime = now;
            blinkState = !blinkState;

            // Blink current pin
            if (currentPin < numDigitalOutputPins) {
                digitalWrite(digitalOutputPins[currentPin], blinkState ? HIGH : LOW);

                // First time we set the pin, print a message
                if (now - testStartTime < 10) {
                    Serial.print("Blinking digital pin ");
                    Serial.println(digitalOutputPins[currentPin]);
                    testStartTime = now;
                }
            }

            // Change to next pin after 6 blinks (3 seconds)
            if (now % 3000 < 10 && currentPin < numDigitalOutputPins) {
                digitalWrite(digitalOutputPins[currentPin], LOW);
                currentPin++;
                testStartTime = now;

                if (currentPin >= numDigitalOutputPins) {
                    // End of test
                    Serial.println("Test completed. Enter a new submode or 0 to exit.");
                    currentSubmode = 0;
                    resetAllPins();
                }
            }
        }
    }
    else if (submode == PIN_TEST_ANALOG_RAMP) {
        if (now - lastUpdateTime >= 50) { // Update PWM every 50ms
            lastUpdateTime = now;

            // Ramp PWM value up and down
            analogValue = (analogValue + 5) % 510;
            int pwmValue = analogValue < 255 ? analogValue : 510 - analogValue;

            // Apply to current pin
            if (currentPin < numAnalogOutputPins) {
                analogWrite(analogOutputPins[currentPin], pwmValue);

                // Print status every 500ms
                if (analogValue % 50 < 5) {
                    Serial.print("Pin ");
                    Serial.print(analogOutputPins[currentPin]);
                    Serial.print(" PWM: ");
                    Serial.println(pwmValue);
                }
            }

            // Change to next pin after a full cycle
            if (analogValue == 0 && currentPin < numAnalogOutputPins) {
                analogWrite(analogOutputPins[currentPin], 0);
                currentPin++;

                if (currentPin >= numAnalogOutputPins) {
                    // End of test
                    Serial.println("Test completed. Enter a new submode or 0 to exit.");
                    currentSubmode = 0;
                    resetAllPins();
                } else {
                    Serial.print("Testing analog pin ");
                    Serial.println(analogOutputPins[currentPin]);
                }
            }
        }
    }
    else if (submode == PIN_TEST_SEQUENCE) {
        // Run through all tests in sequence
        static int sequenceStep = 1;

        if (now - lastUpdateTime >= 5000) { // 5 seconds per test
            lastUpdateTime = now;
            resetAllPins();

            // Cycle through tests 1-4
            sequenceStep = (sequenceStep % 4) + 1;
            currentSubmode = sequenceStep;
            currentPin = 0;

            Serial.print("Running test: ");
            if (currentSubmode == PIN_TEST_DIGITAL_HIGH) {
                Serial.println("Digital pins HIGH");
            }
            else if (currentSubmode == PIN_TEST_DIGITAL_LOW) {
                Serial.println("Digital pins LOW");
            }
            else if (currentSubmode == PIN_TEST_DIGITAL_BLINK) {
                Serial.println("Digital pins BLINK");
            }
            else if (currentSubmode == PIN_TEST_ANALOG_RAMP) {
                Serial.println("Analog pins RAMP");
                analogValue = 0;
            }
        }
    }
}

void Testing::displayConfig(Config* config, const char* configName) {
    Serial.println("\n------------------");
    Serial.print("Configuration: ");
    Serial.println(configName);
    Serial.println("------------------");

    Serial.print("Robot Type: ");
    Serial.println(config->getRobotTypeString());

    Serial.print("MCU Type: ");
    Serial.println(config->getMcuType() == NANO ? "NANO" : "ESP32");

    Serial.print("SatiBot Version: ");
    Serial.println(config->getSatibotVersion() == SATIBOT_V0 ? "V0" : "V1");

    Serial.println("\nFeature Flags:");
    Serial.print("Bluetooth Support: ");
    Serial.println(config->hasBluetoothSupport() ? "YES" : "NO");

    Serial.print("Status LEDs: ");
    Serial.println(config->hasStatusLeds() ? "YES" : "NO");

    Serial.println("\nPin Configuration:");
    Serial.print("PWM L1: ");
    Serial.println(config->getPinPwmL1());

    Serial.print("PWM L2: ");
    Serial.println(config->getPinPwmL2());

    Serial.print("PWM R1: ");
    Serial.println(config->getPinPwmR1());

    Serial.print("PWM R2: ");
    Serial.println(config->getPinPwmR2());

    Serial.print("Direction L: ");
    Serial.println(config->getPinDirectionL());

    Serial.print("Direction R: ");
    Serial.println(config->getPinDirectionR());

    Serial.println("\nSensor Pins:");
    Serial.print("Hall Effect L: ");
    Serial.println(config->getPinHallL());
    Serial.print("Hall Effect R: ");
    Serial.println(config->getPinHallR());
    Serial.print("IMU SDA: ");
    Serial.println(config->getPinSdaIMU());
    Serial.print("IMU SCL: ");
    Serial.println(config->getPinSclIMU());

    Serial.println("\nSettings:");
    Serial.print("No Phone Mode: ");
    Serial.println(config->isNoPhoneMode() ? "YES" : "NO");

    Serial.print("Debug Mode: ");
    Serial.println(config->isDebugMode() ? "YES" : "NO");

    Serial.println("------------------");
    Serial.println("Press any key to view next configuration");
}

void Testing::resetAllPins() {
    // Reset all digital pins
    for (int i = 0; i < numDigitalOutputPins; i++) {
        digitalWrite(digitalOutputPins[i], LOW);
    }

    // Reset all analog pins
    for (int i = 0; i < numAnalogOutputPins; i++) {
        analogWrite(analogOutputPins[i], 0);
    }
}

void Testing::setupPins() {
    // Initialize all pins as outputs
    for (int i = 0; i < numDigitalOutputPins; i++) {
        pinMode(digitalOutputPins[i], OUTPUT);
        digitalWrite(digitalOutputPins[i], LOW);
    }
}
