#include "include/Config.h"
#include "include/Motors.h"
#include "include/Communication.h"
#include "include/Sensors.h"
#include "include/Testing.h"
#include "include/VelocityController.h"

// ESP32 specific includes
#if defined(ESP32)
#include <esp_wifi.h>     // ESP32 WiFi functionality
#include "esp_timer.h"    // ESP32 Timer functionality
#endif

// Setup the OpenBot version (DIY, DIY_ESP32)
#define OPENBOT_TYPE DIY_ESP32

// Override settings from Config.h if needed
// #define NO_PHONE_MODE true  // Uncomment to enable autonomous mode
// #define DEBUG_MODE true     // Uncomment to enable debug output

// Global objects
Config* config;
Motors* motors;
Communication* communication;
Sensors* sensors;
Testing* testing;
VelocityController* velocityController;

// Make velocityController accessible to other files
extern VelocityController* velocityController;

// No phone mode variables
unsigned long turn_direction_time = 0;
unsigned long turn_direction_interval = 5000;
unsigned int turn_direction = 0;

// Test mode flag
bool testMode = false;

// Timer for IMU readings
#if defined(ESP32)
esp_timer_handle_t imu_timer;

// Timer callback function
void IRAM_ATTR imu_timer_callback(void* arg) {
  // Call the Sensors' IMU update method
  if (sensorsInstance != nullptr) {
    sensorsInstance->updateIMUReading();
  }
}
#endif

void setup() {
  // Initialize configuration
  config = new Config(OPENBOT_TYPE);

  // Initialize motors
  motors = new Motors(config);

  // Initialize sensors
  sensors = new Sensors(config);
  sensors->begin();

  // Initialize Velocity Controller first
  velocityController = new VelocityController(config, motors, sensors);
  velocityController->begin();

  // Set PID parameters based on cseled_test.ino
  // p:2.5, d:0.1 // akadozva megy jó irányba
  // p:1.5, d:0.5 // oda-vissza oszcillál

  //velocityController->setKp(35.0f);
  velocityController->setKp(7.0f);
  velocityController->setKi(0.0f);  // Not used in cseled_test
  //velocityController->setKd(2.0f);
  velocityController->setKd(2.0f);

  // Initialize communication with VelocityController
  communication = new Communication(config, velocityController, sensors);
  communication->begin();

  // Set references in sensors
  sensors->setCommunication(communication);
  sensors->setMotors(motors);



  // Initialize pins
  if (config->hasStatusLeds()) {
    // Initialize status LEDs if available
  }

  // Initialize testing module
  //testing = new Testing(config, motors, sensors, communication, velocityController);

  // Check if we should enter test mode (press 't' during startup)
  //delay(1000); // Wait for serial connection
  //while (Serial.available() > 0) {
  //  char c = Serial.read();
  //  if (c == 't' || c == 'T') {
  //    testMode = true;
  //    testing->begin();
  //    break;
  //  }
  //}

  // Set up the IMU timer on ESP32
  #if defined(ESP32)
    // Disable WiFi to save power
    esp_wifi_deinit();

    // Configure the timer for 2ms interval (500Hz)
    const esp_timer_create_args_t imu_timer_args = {
      .callback = &imu_timer_callback,
      .arg = NULL,
      .name = "imu_timer"
    };

    // Create and start the timer
    ESP_ERROR_CHECK(esp_timer_create(&imu_timer_args, &imu_timer));
    ESP_ERROR_CHECK(esp_timer_start_periodic(imu_timer, 2000)); // 2000 microseconds = 2ms
  #endif
}

void loop() {

  //digitalWrite(config->getPinStopLeft(), LOW);
  //digitalWrite(config->getPinStopRight(), LOW);

  // Test mode handling
  ///////////////////////////////////////////////////////////////
  // Check if we're in test mode
  //if (testMode) {
    // Process test commands and update tests
  //  testing->processCommands();
  //  testing->update();

    // Check for exit command (press 'x' to exit test mode)
  //  if (Serial.available() > 0) {
  //    char c = Serial.read();
  //    if (c == 'x' || c == 'X') {
  //      testMode = false;
  //      Serial.println("Exiting test mode");

        // Reset motors by setting zero velocities
  //      velocityController->setTargetLinearVelocity(0.0f);
  //      velocityController->setTargetAngularVelocity(0.0f);
  //      // Controller is always enabled
  //    }
  //  }

    // In test mode, we don't run the normal robot code
  //  return;
  //}
  // Check for test mode activation
  //if (Serial.available() > 0) {
  //  char c = Serial.read();
  //  if (c == 't' || c == 'T') {
  //    testMode = true;
  //    testing->begin();
  //    return;
  //  }
  //}
  ///////////////////////////////////////////////////////////////

  // Normal robot operation
  #if defined(ESP32)
  if (config->hasBluetoothSupport()) {
    // Update Bluetooth connection status
    communication->updateBluetoothConnection();
  }
  #endif


  // Check for messages from the phone
  //if (!config->isNoPhoneMode()) {
  //  communication->processIncomingMessages();
  //  // Check if heartbeat has expired
  //  if (communication->isHeartbeatExpired()) {
  //    // Stop the robot by setting zero velocities
  //    velocityController->setTargetLinearVelocity(0.0f);
  //    velocityController->setTargetAngularVelocity(0.0f);
  //  }
  //} else {
  //  // In no-phone mode, set a constant forward velocity
  //  velocityController->setTargetLinearVelocity(1.0f);  // Full forward
  //  velocityController->setTargetAngularVelocity(0.0f);  // No turning
  //}

  ////////////////////////////////////////////////////////////////

  // Note: IMU readings are now handled by the timer callback every 2ms

  // Always use PID controller mode
  // Update the controller
  velocityController->update();

  // Get the normalized linear velocity and heading adjustment
  float normalizedLinearVelocity = velocityController->getNormalizedLinearVelocity();
  float headingAdjustment = velocityController->getHeadingAdjustment();
  float currentHeading = velocityController->getHeading();
  float targetHeading = velocityController->getTargetHeading();

  // Send the heading adjustment value to the controller
  communication->sendData("h" + String(headingAdjustment, 6));

  // Send the current heading and target heading values
  communication->sendData("ch" + String(currentHeading, 6));
  communication->sendData("th" + String(targetHeading, 6));

  // Send the normalized linear velocity to the phone
  communication->sendData("n" + String(normalizedLinearVelocity, 6));

  // Send the target angular velocity to the phone
  communication->sendData("a" + String(velocityController->getTargetAngularVelocity(), 6));

  // Apply the normalized linear velocity and heading adjustment to the motors
  // The VelocityController sets heading adjustment to zero when angular velocity is zero
  // Linear velocity will still ramp smoothly
  motors->updateVehicle(normalizedLinearVelocity, headingAdjustment);

  // Update Kalman filter
  //sensors->updateKalmanFilter();

  // Update battery status
  // sensors->updateBatteryStatus();

  // Get the fused velocity estimates (this will trigger sending the data via communication)
  //float fusedAngularVelocity = sensors->getFusedAngularVelocity();
  //float fusedLinearVelocity = sensors->getFusedLinearVelocity();

  // Optional: Print debug information if in debug mode
  if (config->isDebugMode()) {
    static unsigned long lastDebugTime = 0;
    unsigned long currentTime = millis();

    // Print debug info every 500ms to avoid flooding the serial port
    if (currentTime - lastDebugTime >= 500) {
      //Serial.print("Fused Angular Velocity: ");
      //Serial.print(fusedAngularVelocity);
      //Serial.print(" rad/s, Linear Velocity: ");
      //Serial.print(fusedLinearVelocity);
      //Serial.print(" m/s, Battery: ");
      //Serial.print(sensors->getBatteryVoltage());
      //Serial.print("V (");
      //Serial.print(sensors->getBatteryPercentage());
      //Serial.println("%)");

      // Print PID controller information (always enabled)
      //Serial.print("PID Controller: Target Angular Velocity: ");
      //Serial.print(velocityController->getTargetAngularVelocity());
      //Serial.print(" rad/s, Error: ");
      //Serial.print(velocityController->getTargetAngularVelocity() - fusedAngularVelocity);
      //Serial.print(" rad/s, Normalized Linear Velocity: ");
      //Serial.print(velocityController->getNormalizedLinearVelocity());
      Serial.print(", Left PWM: ");
      Serial.print(motors->getCurrentPwmLeft());
      Serial.print(", Right PWM: ");
      Serial.println(motors->getCurrentPwmRight());

      lastDebugTime = currentTime;
    }
  }
}
