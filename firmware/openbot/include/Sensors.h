#ifndef SENSORS_H
#define SENSORS_H

#include "Config.h"
#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include "KalmanFilter.h"

class Sensors {
public:
    Sensors(Config* config);

    // Initialize sensors
    void begin();

    // IMU methods
    void readIMU();
    float getAngularVelocityFromIMU();

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

    // Getters for sensor data
    int16_t* getAccelData() { return accelData; }
    int16_t* getGyroData() { return gyroData; }
    float getAx() { return ax; }
    float getAy() { return ay; }
    float getAz() { return az; }
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

    // IMU sensor
    Adafruit_MPU6050 mpu;
    bool imuInitialized;

    // IMU data
    int16_t accelData[3];
    int16_t gyroData[3];
    float ax, ay, az, gx, gy, gz; // acceleration (m/s²), gyro (rad/s)

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
    static const unsigned long imuSampleInterval = 10; // 10ms
    static const int numSamples = 25; // 25 samples over 250ms
    float gzSamples[25]; // Array to store gz samples
    int sampleIndex; // Current sample index
    unsigned long lastIMUSampleTime; // Last IMU sample time

    // Helper methods
    void countLeft();
    void countRight();

    // Kalman filter
    KalmanFilter kalmanFilter;
    unsigned long lastKalmanUpdateTime;
};

// Declare static instance pointer for interrupt handlers
extern Sensors* sensorsInstance;

#endif // SENSORS_H
