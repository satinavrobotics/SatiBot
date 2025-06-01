# OpenBot Firmware

This directory contains the firmware for the OpenBot robot. The code has been reorganized into a modular, object-oriented structure to improve maintainability and extensibility.

## Code Structure

The firmware is organized into the following components:

### Main Files
- `openbot.ino`: The main Arduino sketch file that initializes and coordinates all components
- `openbot_legacy.ino`: The original monolithic implementation (kept for reference)

### Include Directory
- `Config.h`: Configuration class for robot settings, pin definitions, and feature flags
- `Motors.h`: Motor control functionality
- `Communication.h`: Bluetooth communication
- `Sensors.h`: Sensor readings and processing
- `VelocityController.h`: PID-based velocity control

### Source Directory
- `Config.cpp`: Implementation of the Config class
- `Motors.cpp`: Implementation of the Motors class
- `Communication.cpp`: Implementation of the Communication class
- `Sensors.cpp`: Implementation of the Sensors class
- `VelocityController.cpp`: Implementation of the VelocityController class

## Robot Versions

The firmware supports multiple robot versions through configuration:

1. **Robot Types**:
   - `DIY`: DIY robot with Arduino Nano (Atmega328p)
   - `DIY_ESP32`: DIY robot with ESP32

2. **SatiBot Versions**:
   - `SATIBOT_V0`: Original version with dual PWM control
   - `SATIBOT_V1`: Updated version with direction + PWM control and smooth acceleration

## How It Works

### Initialization
1. The `setup()` function in `openbot.ino` initializes all components:
   - Creates a `Config` object with the specified robot type and version
   - Initializes `Motors`, `Sensors`, and `Communication` objects
   - Sets up pins and peripherals based on the configuration

### Main Loop
1. The `loop()` function handles the main program flow:
   - Updates Bluetooth connection if available
   - Processes incoming messages from the phone
   - Checks sensor readings and updates motor controls accordingly
   - Handles bumper events if the bumper is available

### Class Responsibilities

#### Config
- Stores robot configuration (type, version, pins)
- Provides feature flags (Bluetooth, bumper, etc.)
- Centralizes pin definitions

#### Motors
- Controls motor movement
- Handles different motor control schemes based on robot version
- Implements smooth acceleration for SatiBot V1

#### Communication
- Handles Bluetooth communication with the phone
- Processes incoming messages and sends sensor data

#### Sensors
- Reads and processes sensor data
- Handles bumper events
- Provides distance estimates

#### VelocityController
- Implements PID-based velocity control
- Manages target linear and angular velocities
- Provides heading control and stabilization

## Customization

To customize the firmware for a specific robot:

1. Set the robot type and version in `openbot.ino`:
   ```cpp
   #define OPENBOT_TYPE DIY_ESP32
   #define SATIBOT_VERSION 0
   ```

2. Add new robot types by extending the `Config` class with new pin definitions and features.

3. Implement version-specific behavior in the appropriate classes.

## Dependencies

- Arduino core libraries
- For ESP32: ESP32 Arduino core (esp_wifi.h)
- For Bluetooth: BLE libraries (included with ESP32 Arduino core)

## Notes on ESP32 Support

- The code uses standard Arduino functions like `analogWrite()` for motor control across all platforms
- ESP32-specific functionality is isolated to WiFi and Bluetooth features
- Conditional compilation ensures the code works on both Arduino Nano and ESP32 platforms
