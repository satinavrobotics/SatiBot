#include "../include/KalmanFilter.h"

KalmanFilter::KalmanFilter()
    : dt(0.01),          // Default time step: 10ms
      w_est(0.0f),       // Initial angular velocity estimate
      P_w(1.0f),         // Initial error covariance for angular velocity
      Q_w(0.1f),         // Process noise for angular velocity
      R_wheel_w(0.5f),   // Measurement noise for wheel angular velocity
      R_imu_w(1.0f),     // Measurement noise for IMU angular velocity
      v_est(0.0f),       // Initial linear velocity estimate
      P_v(1.0f),         // Initial error covariance for linear velocity
      Q_v(0.1f),         // Process noise for linear velocity
      R_wheel_v(0.5f),   // Measurement noise for wheel linear velocity
      R_imu_v(1.0f),     // Measurement noise for IMU linear velocity
      Q_w_default(0.1f), // Default process noise for angular velocity
      Q_v_default(0.1f), // Default process noise for linear velocity
      highUncertaintyMode(false) { // Start in normal mode
}

void KalmanFilter::begin() {
    // Reset the filter to initial values
    reset();
}

void KalmanFilter::setDt(float dt) {
    this->dt = dt;
}

void KalmanFilter::reset() {
    // Reset angular velocity filter
    w_est = 0.0f;
    P_w = 1.0f;
    Q_w = Q_w_default;

    // Reset linear velocity filter
    v_est = 0.0f;
    P_v = 1.0f;
    Q_v = Q_v_default;

    // Reset uncertainty mode
    highUncertaintyMode = false;
}

// Angular velocity Kalman filter methods

void KalmanFilter::predictAngular(float alpha_cmd) {
    // Predict the next state based on the commanded angular acceleration
    w_est = w_est + dt * alpha_cmd;
    P_w = P_w + Q_w;
}

void KalmanFilter::updateAngular(float z, float R) {
    // Calculate Kalman gain
    float K = P_w / (P_w + R);

    // Update the estimate with the measurement
    w_est = w_est + K * (z - w_est);

    // Update the error covariance
    P_w = (1 - K) * P_w;
}

void KalmanFilter::updateAngularFromWheel(float w_wheel) {
    updateAngular(w_wheel, R_wheel_w);
}

void KalmanFilter::updateAngularFromIMU(float w_imu) {
    updateAngular(w_imu, R_imu_w);
}

float KalmanFilter::getAngularVelocity() const {
    return w_est;
}

// Linear velocity Kalman filter methods

void KalmanFilter::predictLinear(float a_cmd) {
    // Predict the next state based on the commanded linear acceleration
    v_est = v_est + dt * a_cmd;
    P_v = P_v + Q_v;
}

void KalmanFilter::updateLinear(float z, float R) {
    // Calculate Kalman gain
    float K = P_v / (P_v + R);

    // Update the estimate with the measurement
    v_est = v_est + K * (z - v_est);

    // Update the error covariance
    P_v = (1 - K) * P_v;
}

void KalmanFilter::updateLinearFromWheel(float v_wheel) {
    updateLinear(v_wheel, R_wheel_v);
}

void KalmanFilter::updateLinearFromIMU(float v_imu) {
    updateLinear(v_imu, R_imu_v);
}

float KalmanFilter::getLinearVelocity() const {
    return v_est;
}

// Set high uncertainty mode
void KalmanFilter::setHighUncertainty(bool highUncertainty) {
    // Only take action if the mode is changing
    if (highUncertainty != highUncertaintyMode) {
        highUncertaintyMode = highUncertainty;

        if (highUncertainty) {
            // Increase process noise to indicate higher uncertainty
            Q_w = Q_w_default * 10.0f;
            Q_v = Q_v_default * 10.0f;

            // Also increase error covariance to give more weight to measurements
            // when they become available again
            P_w *= 2.0f;
            P_v *= 2.0f;
        } else {
            // Restore default values
            Q_w = Q_w_default;
            Q_v = Q_v_default;
        }
    }
}
