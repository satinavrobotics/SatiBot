#include "include/Config.h"
#include "include/Motors.h"
#include "include/Communication.h"
#include "include/Sensors.h"
#include "include/VelocityController.h"
#include <Wire.h>  // 🔼 Enables ESP32 I2C slave

// ESP32 specific includes
#if defined(ESP32)
#include <esp_wifi.h>     // ESP32 WiFi functionality
#include "esp_timer.h"    // ESP32 Timer functionality
#endif

// Setup the OpenBot version (DIY, DIY_ESP32)
#define OPENBOT_TYPE DIY_ESP32

// Global objects
Config* config;
Motors* motors;
Communication* communication;
Sensors* sensors;
VelocityController* velocityController;

// Make velocityController accessible to other files
extern VelocityController* velocityController;

/* - Cimbi -
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
*/

void setup() {
  // Initialize configuration
  config = new Config(OPENBOT_TYPE);

  // Cimbi - for I²C 
  Wire.begin(0x10);  // 🔼 ESP32 acts as I²C slave at address 0x10
  Wire.onReceive(Communication::receiveHandler);   // 🔼 Callback: data received from Jetson
  Wire.onRequest(Communication::requestHandler);   // 🔼 Callback: respond to odom requests

  // Initialize motors
  motors = new Motors(config);

  // Initialize sensors
  sensors = new Sensors(config);
  sensors->begin();

  // Initialize Velocity Controller first
  velocityController = new VelocityController(config, motors, sensors);
  velocityController->begin();

  //velocityController->setKp(35.0f);
  velocityController->setKp(7.0f);
  velocityController->setKi(0.0f);  // Not used in cseled_test
  velocityController->setKd(0.8f);

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

  /* - Cimbi - 
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
  */
}

void loop() {

  // Normal robot operation
  #if defined(ESP32)
  if (config->hasBluetoothSupport()) {
    // Update Bluetooth connection status
    communication->updateBluetoothConnection();
  }
  #endif


  // Process incoming messages from communication
  communication->processIncomingMessages();

  // Check if heartbeat has expired
  if (communication->isHeartbeatExpired()) {
    // Stop the robot by setting zero velocities
    velocityController->setTargetLinearVelocity(0.0f);
    velocityController->setTargetAngularVelocity(0.0f);
  }

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
  // Get PWM values from VelocityController and pass to Motors for timing control
  PWMControlValues pwmValues = velocityController->computeMotorPWM();
  motors->updateVehicleWithAdjustments(pwmValues);

  // Update Kalman filter
  sensors->updateKalmanFilter();

  // Update battery status
  sensors->updateBatteryStatus();
}
