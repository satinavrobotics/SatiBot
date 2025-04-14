#ifndef KALMAN_FILTER_H
#define KALMAN_FILTER_H

#include <Arduino.h>

class KalmanFilter {
public:
    KalmanFilter();

    // Initialize the filter with default values
    void begin();

    // Set the time step for the filter
    void setDt(float dt);

    // Angular velocity fusion methods
    void predictAngular(float alpha_cmd);
    void updateAngularFromWheel(float w_wheel);
    void updateAngularFromIMU(float w_imu);
    float getAngularVelocity() const;

    // Linear velocity fusion methods
    void predictLinear(float a_cmd);
    void updateLinearFromWheel(float v_wheel);
    void updateLinearFromIMU(float v_imu);
    float getLinearVelocity() const;

    // Reset the filter
    void reset();

    // Set high uncertainty mode (used when sensors are unavailable)
    void setHighUncertainty(bool highUncertainty);

private:
    // Time step
    float dt;

    // Angular velocity Kalman filter variables
    float w_est;         // Estimated angular velocity [rad/s]
    float P_w;           // Error covariance for angular velocity
    float Q_w;           // Process noise for angular velocity
    float R_wheel_w;     // Measurement noise for wheel angular velocity
    float R_imu_w;       // Measurement noise for IMU angular velocity

    // Linear velocity Kalman filter variables
    float v_est;         // Estimated linear velocity [m/s]
    float P_v;           // Error covariance for linear velocity
    float Q_v;           // Process noise for linear velocity
    float R_wheel_v;     // Measurement noise for wheel linear velocity
    float R_imu_v;       // Measurement noise for IMU linear velocity

    // Default noise values (used to restore after high uncertainty)
    float Q_w_default;   // Default process noise for angular velocity
    float Q_v_default;   // Default process noise for linear velocity

    // High uncertainty mode
    bool highUncertaintyMode;

    // Helper methods
    void updateAngular(float z, float R);
    void updateLinear(float z, float R);
};

#endif // KALMAN_FILTER_H
