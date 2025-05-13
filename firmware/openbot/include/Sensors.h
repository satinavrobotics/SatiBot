#ifndef SENSORS_H
#define SENSORS_H

#include "Config.h"
#include <Wire.h>
#include <MPU6050_light.h>
#include "KalmanFilter.h"

// Forward declarations to avoid circular dependencies
class Communication;
class Motors;

class Sensors {
public:
    Sensors(Config* config);

    // Initialize sensors
    void begin();

    // Set communication object for sending data
    void setCommunication(Communication* communication);

    // Set motors object for accessing motor control values
    void setMotors(Motors* motors);

    // IMU methods
    void readIMU();
    float getAngularVelocityFromIMU();
    void calibrateIMU();
    void updateIMUReading();  // Called by timer every 2ms

    // Wheel encoder methods
    void updateWheelCounts();
    float getAngularVelocityFromOdometry();
    float getLinearVelocity();
    float getLeftWheelVelocity();
    float getRightWheelVelocity();

    // Kalman filter methods
    void updateKalmanFilter();
    float getFusedAngularVelocity();
    float getFusedLinearVelocity();

    // Battery monitoring methods
    void updateBatteryStatus();
    float getBatteryVoltage();
    int getBatteryPercentage();

    // Getters for sensor data
    int16_t* getGyroData() { return gyroData; }
    float getGx() { return gx; }
    float getGy() { return gy; }
    float getGz() { return gz; }
    unsigned int getLeftWheelCount() { return pulseCountLeft; }
    unsigned int getRightWheelCount() { return pulseCountRight; }
    float getVelocity() { return linearVelocity; } // Alias for getLinearVelocity()

    // Sensor status methods
    bool isIMUInitialized() { return imuInitialized; }
    unsigned long getLastRPMCalcTime() { return lastRPMCalcTime; }

    // Static interrupt handlers
    static void countLeftStatic();
    static void countRightStatic();

private:
    Config* config;
    Communication* communication; // Pointer to communication object
    Motors* motors; // Pointer to motors object

    // IMU sensor
    MPU6050* mpu;
    bool imuInitialized;

    // IMU data
    //int16_t accelData[3];
    int16_t gyroData[3];
    //float ax, ay, az,
    float gx, gy, gz; // acceleration (m/s²), gyro (rad/s)
    //float ax_bias, ay_bias, az_bias; // Accelerometer bias values
    float gx_bias, gy_bias, gz_bias; // Gyroscope bias values

    // Odometry data
    volatile static unsigned int pulseCountLeft;
    volatile static unsigned int pulseCountRight;
    static const unsigned int pulsesPerRevolution = 30; // 15 pulses * 2 edges
    unsigned long lastRPMCalcTime;
    float leftWheelVelocity;  // Linear velocity of left wheel in m/s
    float rightWheelVelocity; // Linear velocity of right wheel in m/s
    float linearVelocity;     // Linear velocity of robot in m/s

    // Robot parameters
    static const float wheelDiameter;
    static const float wheelCircumference;
    static const float wheelBase;

    // IMU sampling parameters
    float filteredYawRate;            // Filtered yaw rate using simple average
    static const int IMU_BUFFER_SIZE = 10;  // Size of the IMU buffer for averaging
    float imuBuffer[IMU_BUFFER_SIZE];      // Buffer to store IMU readings
    int imuBufferIndex;                   // Current index in the buffer
    int imuBufferCount;                   // Number of samples in the buffer
    bool newIMUDataAvailable;             // Flag to indicate new IMU data is available
    unsigned long lastIMUSampleTime;      // Last IMU sample time

    // New IMU sampling parameters
    unsigned long lastUpdateTime;         // Last time the average was calculated
    static const unsigned long imuSampleInterval = 2;  // Sample interval in ms
    static const unsigned long updateInterval = 50;    // Update interval in ms
    float gxSamples[IMU_BUFFER_SIZE];     // Buffer to store gx samples
    int sampleIndex;                      // Current index in the sample buffer

    // Helper methods
    void countLeft();
    void countRight();

    // Kalman filter
    KalmanFilter kalmanFilter;
    unsigned long lastKalmanUpdateTime;

    // Battery monitoring
    float batteryVoltage;
    int batteryPercentage;
    unsigned long lastBatteryUpdateTime;
};

// Declare static instance pointer for interrupt handlers
extern Sensors* sensorsInstance;

#endif // SENSORS_H
