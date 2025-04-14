#include "../include/Sensors.h"

// Initialize static variables
volatile unsigned int Sensors::pulseCountLeft = 0;
volatile unsigned int Sensors::pulseCountRight = 0;
const float Sensors::wheelDiameter = 0.16; // 16 cm = 0.16 m
const float Sensors::wheelCircumference = PI * Sensors::wheelDiameter; // ~0.50265 m
const float Sensors::wheelBase = 0.43; // 43 cm = 0.43 m

// Global pointer to the Sensors instance for use in static methods
Sensors* sensorsInstance = nullptr;

Sensors::Sensors(Config* config)
    : config(config),
      imuInitialized(false),
      lastRPMCalcTime(0),
      leftWheelVelocity(0.0f),
      rightWheelVelocity(0.0f),
      linearVelocity(0.0f),
      sampleIndex(0),
      lastIMUSampleTime(0),
      lastKalmanUpdateTime(0) {

    // Initialize arrays
    for (int i = 0; i < 3; i++) {
        accelData[i] = 0;
        gyroData[i] = 0;
    }

    ax = ay = az = 0.0f;
    gx = gy = gz = 0.0f;

    for (int i = 0; i < numSamples; i++) {
        gzSamples[i] = 0.0f;
    }

    // Set the global instance pointer
    sensorsInstance = this;
}

void Sensors::begin() {
    // Initialize hall effect sensors (wheel encoders)
    pinMode(config->getPinHallL(), INPUT_PULLUP); // Left hall effect sensor
    pinMode(config->getPinHallR(), INPUT_PULLUP); // Right hall effect sensor

    // Attach interrupts for wheel encoders
    attachInterrupt(digitalPinToInterrupt(config->getPinHallL()), countLeftStatic, CHANGE);
    attachInterrupt(digitalPinToInterrupt(config->getPinHallR()), countRightStatic, CHANGE);

    // Initialize I2C with custom SDA and SCL pins
    Wire.begin(config->getPinSdaIMU(), config->getPinSclIMU()); // SDA and SCL pins for IMU

    // Give sensor time to power up
    delay(100);

    // Initialize MPU6050
    if (mpu.begin(0x68, &Wire)) {
        // Configure MPU6050
        mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
        mpu.setGyroRange(MPU6050_RANGE_250_DEG);
        mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
        imuInitialized = true;
    } else {
        // Failed to initialize MPU6050
        imuInitialized = false;
    }

    // Initialize Kalman filter
    kalmanFilter.begin();
    kalmanFilter.setDt(0.01); // 10ms time step
}

// Static interrupt handlers
void Sensors::countLeftStatic() {
    if (sensorsInstance) {
        sensorsInstance->countLeft();
    }
}

void Sensors::countRightStatic() {
    if (sensorsInstance) {
        sensorsInstance->countRight();
    }
}

// Instance methods for interrupt handling
void Sensors::countLeft() {
    pulseCountLeft++;
}

void Sensors::countRight() {
    pulseCountRight++;
}

void Sensors::updateWheelCounts() {
    // This method can be called periodically to update wheel counts
    // Currently, the counts are updated directly by interrupts
}

// Read and convert IMU data
void Sensors::readIMU() {
    if (!imuInitialized) {
        return;
    }

    // Read raw IMU data
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);

    // Store raw data (for compatibility)
    accelData[0] = a.acceleration.x * 16384.0 / 9.81;
    accelData[1] = a.acceleration.y * 16384.0 / 9.81;
    accelData[2] = a.acceleration.z * 16384.0 / 9.81;
    gyroData[0] = g.gyro.x * 131.0 / (PI / 180.0);
    gyroData[1] = g.gyro.y * 131.0 / (PI / 180.0);
    gyroData[2] = g.gyro.z * 131.0 / (PI / 180.0);

    // Convert accel to m/s²
    ax = a.acceleration.x;
    ay = a.acceleration.y;
    az = a.acceleration.z;

    // Convert gyro to rad/s
    gx = g.gyro.x;
    gy = g.gyro.y;
    gz = g.gyro.z;

    // Store gz sample for averaging
    unsigned long currentTime = millis();
    if (currentTime - lastIMUSampleTime >= imuSampleInterval) {
        gzSamples[sampleIndex] = gz; // Store gz (yaw rate in rad/s)
        sampleIndex = (sampleIndex + 1) % numSamples; // Increment and wrap around
        lastIMUSampleTime = currentTime;
    }
}

// Calculate robot's angular velocity from IMU (rad/s)
float Sensors::getAngularVelocityFromIMU() {
    if (!imuInitialized) {
        return 0.0f;
    }

    // Average the stored gz samples
    float sumGz = 0.0f;
    for (int i = 0; i < numSamples; i++) {
        sumGz += gzSamples[i];
    }
    return sumGz / numSamples;
}

// Calculate robot's angular velocity from odometry (rad/s)
float Sensors::getAngularVelocityFromOdometry() {
    unsigned long currentTime = millis();
    static float lastOmega = 0.0f; // Store last calculated angular velocity

    if (currentTime - lastRPMCalcTime >= 250) { // 250ms interval
        noInterrupts();
        unsigned int leftCount = pulseCountLeft;
        unsigned int rightCount = pulseCountRight;
        pulseCountLeft = 0;
        pulseCountRight = 0;
        interrupts();

        // Calculate RPM
        float rpmLeft = (leftCount / (float)pulsesPerRevolution) * (60000.0f / 250.0f);
        float rpmRight = (rightCount / (float)pulsesPerRevolution) * (60000.0f / 250.0f);

        // Convert RPM to linear velocity (m/s)
        leftWheelVelocity = (rpmLeft * wheelCircumference) / 60.0f;
        rightWheelVelocity = (rpmRight * wheelCircumference) / 60.0f;

        // Calculate linear velocity as average of both wheels
        linearVelocity = (leftWheelVelocity + rightWheelVelocity) / 2.0f;

        // Calculate angular velocity (rad/s)
        float omega = (rightWheelVelocity - leftWheelVelocity) / wheelBase;

        lastRPMCalcTime = currentTime;
        lastOmega = omega; // Update stored value

        return omega;
    }

    return lastOmega; // Return last calculated value if not time to update
}

// Get the linear velocity of the robot (m/s)
float Sensors::getLinearVelocity() {
    // Make sure we have the latest velocity calculation
    unsigned long currentTime = millis();
    if (currentTime - lastRPMCalcTime >= 250) {
        getAngularVelocityFromOdometry(); // This will update linearVelocity
    }
    return linearVelocity;
}

// Get the linear velocity of the left wheel (m/s)
float Sensors::getLeftWheelVelocity() {
    // Make sure we have the latest velocity calculation
    unsigned long currentTime = millis();
    if (currentTime - lastRPMCalcTime >= 250) {
        getAngularVelocityFromOdometry(); // This will update leftWheelVelocity
    }
    return leftWheelVelocity;
}

// Get the linear velocity of the right wheel (m/s)
float Sensors::getRightWheelVelocity() {
    // Make sure we have the latest velocity calculation
    unsigned long currentTime = millis();
    if (currentTime - lastRPMCalcTime >= 250) {
        getAngularVelocityFromOdometry(); // This will update rightWheelVelocity
    }
    return rightWheelVelocity;
}

// Update the Kalman filter with the latest sensor readings
void Sensors::updateKalmanFilter() {
    unsigned long currentTime = millis();

    // Update every 10ms
    if (currentTime - lastKalmanUpdateTime >= 10) {
        // Get the latest sensor readings
        float w_wheel = getAngularVelocityFromOdometry();
        float w_imu = getAngularVelocityFromIMU();
        float v_wheel = getLinearVelocity();

        // Calculate linear velocity from IMU (simple integration of acceleration)
        // This is a simplified approach and might need improvement
        static float v_imu = 0.0f;
        static unsigned long lastAccelTime = 0;

        // Only integrate IMU data if the IMU is initialized
        if (imuInitialized && lastAccelTime > 0) {
            float dt = (currentTime - lastAccelTime) / 1000.0f; // Convert to seconds
            v_imu += ax * dt; // Simple integration of x-axis acceleration
        }
        lastAccelTime = currentTime;

        // Dummy values for commanded accelerations
        // In a real implementation, these would come from the motor control commands
        float alpha_cmd = 0.05f; // Angular acceleration command [rad/s²]
        float a_cmd = 0.2f;      // Linear acceleration command [m/s²]

        // Update Kalman filters
        kalmanFilter.predictAngular(alpha_cmd);

        // Check if wheel encoders are providing valid data
        // We consider them valid if we've received pulses in the last second
        bool wheelsValid = (currentTime - lastRPMCalcTime < 1000);

        // Only update with wheel data if valid
        if (wheelsValid) {
            kalmanFilter.updateAngularFromWheel(w_wheel);
            kalmanFilter.updateLinearFromWheel(v_wheel);
        }

        // Only update with IMU data if initialized
        if (imuInitialized) {
            kalmanFilter.updateAngularFromIMU(w_imu);
            kalmanFilter.updateLinearFromIMU(v_imu);
        }

        kalmanFilter.predictLinear(a_cmd);

        // If neither sensor is available, increase process noise to indicate uncertainty
        if (!wheelsValid && !imuInitialized) {
            kalmanFilter.setHighUncertainty(true);
        } else {
            kalmanFilter.setHighUncertainty(false);
        }

        lastKalmanUpdateTime = currentTime;
    }
}

// Get the fused angular velocity from the Kalman filter
float Sensors::getFusedAngularVelocity() {
    updateKalmanFilter(); // Make sure we have the latest estimate
    return kalmanFilter.getAngularVelocity();
}

// Get the fused linear velocity from the Kalman filter
float Sensors::getFusedLinearVelocity() {
    updateKalmanFilter(); // Make sure we have the latest estimate
    return kalmanFilter.getLinearVelocity();
}
