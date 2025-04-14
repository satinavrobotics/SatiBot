#include "include/Config.h"
#include "include/Motors.h"
#include "include/Communication.h"
#include "include/Sensors.h"
#include "include/Testing.h"

// ESP32 specific includes
#if defined(ESP32)
#include <esp_wifi.h>     // ESP32 WiFi functionality
#endif

// Setup the OpenBot version (DIY, DIY_ESP32)
#define OPENBOT_TYPE DIY_ESP32
#define SATIBOT_VERSION 0 // (0: SatiBotV0, 1: SatiBotV1)

// Override settings from Config.h if needed
// #define NO_PHONE_MODE true  // Uncomment to enable autonomous mode
// #define DEBUG_MODE true     // Uncomment to enable debug output

// Global objects
Config* config;
Motors* motors;
Communication* communication;
Sensors* sensors;
Testing* testing;

// No phone mode variables
unsigned long turn_direction_time = 0;
unsigned long turn_direction_interval = 5000;
unsigned int turn_direction = 0;

// Test mode flag
bool testMode = false;

void setup() {
  // Initialize configuration
  config = new Config(OPENBOT_TYPE, SATIBOT_VERSION);

  // Initialize motors
  motors = new Motors(config);

  // Initialize sensors
  sensors = new Sensors(config);
  sensors->begin();

  // Initialize communication
  communication = new Communication(config, motors, sensors);
  communication->begin();

  // Initialize pins
  if (config->hasStatusLeds()) {
    // Initialize status LEDs if available
  }

  // Initialize testing module
  testing = new Testing(config, motors, sensors, communication);

  // Check if we should enter test mode (press 't' during startup)
  delay(1000); // Wait for serial connection
  while (Serial.available() > 0) {
    char c = Serial.read();
    if (c == 't' || c == 'T') {
      testMode = true;
      testing->begin();
      break;
    }
  }

  // Disable WiFi on ESP32 to save power
  #if defined(ESP32)
    esp_wifi_deinit();
  #endif
}

void loop() {
  // Check if we're in test mode
  if (testMode) {
    // Process test commands and update tests
    testing->processCommands();
    testing->update();

    // Check for exit command (press 'x' to exit test mode)
    if (Serial.available() > 0) {
      char c = Serial.read();
      if (c == 'x' || c == 'X') {
        testMode = false;
        Serial.println("Exiting test mode");

        // Reset motors
        motors->setLeftControl(0);
        motors->setRightControl(0);
        motors->updateVehicle(0, 0);
      }
    }

    // In test mode, we don't run the normal robot code
    return;
  }

  // Normal robot operation
  #if defined(ESP32)
  if (config->hasBluetoothSupport()) {
    // Update Bluetooth connection status
    communication->updateBluetoothConnection();
  }
  #endif

  // Check for test mode activation
  if (Serial.available() > 0) {
    char c = Serial.read();
    if (c == 't' || c == 'T') {
      testMode = true;
      testing->begin();
      return;
    }
  }

  // Check for messages from the phone
  if (!config->isNoPhoneMode()) {
    communication->processIncomingMessages();
    // Check if heartbeat has expired
    if (communication->isHeartbeatExpired()) {
      motors->setLeftControl(0);
      motors->setRightControl(0);
    }
  } else {
    motors->setLeftControl(config->getCtrlMax());
    motors->setRightControl(config->getCtrlMax());
  }


  motors->updateVehicle(motors->getLeftControl(), motors->getRightControl());
}
