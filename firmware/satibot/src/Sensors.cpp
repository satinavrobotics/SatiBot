#include "../include/Sensors.h"
#include "../include/Communication.h"
#include "../include/Motors.h"

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
      communication(nullptr),
      motors(nullptr),
      imuInitialized(false),
      lastRPMCalcTime(0),
      leftWheelVelocity(0.0f),
      rightWheelVelocity(0.0f),
      linearVelocity(0.0f),
      filteredYawRate(0.0f),
      imuBufferIndex(0),
      imuBufferCount(0),
      newIMUDataAvailable(false),
      lastIMUSampleTime(0),
      lastKalmanUpdateTime(0),
      gx_bias(0.0f),
      gy_bias(0.0f),
      gz_bias(0.0f),
      batteryVoltage(0.0f),
      batteryPercentage(0),
      lastBatteryUpdateTime(0) {

    // Initialize arrays
    for (int i = 0; i < 3; i++) {
        //accelData[i] = 0;
        gyroData[i] = 0;
    }

    // Initialize IMU buffer
    for (int i = 0; i < IMU_BUFFER_SIZE; i++) {
        gxSamples[i] = 0.0f;
    }

    //ax = ay = az = 0.0f;
    gx = gy = gz = 0.0f;
    sampleIndex = 0;
    lastUpdateTime = 0;

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
    mpu = new MPU6050(Wire);

    mpu->begin();
    // mpu->begin();
    imuInitialized = true;
    mpu->setGyroOffsets(0, 0, 0);
    calibrateIMU();

    // Initialize MPU6050
    //if (mpu.begin(0x68, &Wire)) {
    //if (mpu->begin()) {
        // Configure MPU6050
    //    imuInitialized = true;
    //    mpu->setGyroOffsets(0, 0, 0);
        // Calibrate IMU to estimate gyro bias
    //    calibrateIMU();
    //} else {
        // Failed to initialize MPU6050
    //    imuInitialized = false;
    //}

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

// Set the communication object for sending data
void Sensors::setCommunication(Communication* comm) {
    communication = comm;
}

// Set the motors object for accessing motor control values
void Sensors::setMotors(Motors* m) {
    motors = m;
}

// Update the Kalman filter with the latest sensor readings
// Update battery status
void Sensors::updateBatteryStatus() {
    unsigned long currentTime = millis();

    // Update every 1000ms (1 second)
    if (currentTime - lastBatteryUpdateTime >= 1000) {
        // Read the analog value from the voltage divider
        int vRaw = analogRead(config->getPinVoltageDivider());

        // Convert to voltage (assuming 3.3V reference and 12-bit ADC)
        float voltage = vRaw * (3.3 / 4095.0);

        // Calculate battery percentage (constrained between 2.77V and 3.23V)
        batteryPercentage = map(constrain(voltage * 100, 277, 323), 277, 323, 0, 100);

        // Store the voltage
        batteryVoltage = voltage;

        // Update timestamp
        lastBatteryUpdateTime = currentTime;

        // Send battery data to phone if communication is available
        if (communication != nullptr) {
            communication->sendData("v" + String(batteryPercentage) + "," + String(batteryVoltage, 2));
        }
    }
}

// Get battery voltage
float Sensors::getBatteryVoltage() {
    return batteryVoltage;
}

// Get battery percentage
int Sensors::getBatteryPercentage() {
    return batteryPercentage;
}

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
            //v_imu += ax * dt; // Simple integration of x-axis acceleration
            // TODO: commented out this line, as ax is not available
        }
        lastAccelTime = currentTime;

        // Calculate commanded accelerations from motor control values
        float alpha_cmd = 0.0f; // Angular acceleration command [rad/s²]
        float a_cmd = 0.0f;     // Linear acceleration command [m/s²]

        // Use motor control values if motors object is available
        if (motors != nullptr) {
            // Get current PWM values (now consistent between V0 and V1)
            int leftPwm = motors->getCurrentPwmLeft();
            int rightPwm = motors->getCurrentPwmRight();

            // Calculate linear and angular acceleration commands
            // Scale PWM values (0-255) to acceleration values
            float maxAccel = 1.0f; // Maximum acceleration in m/s²
            float maxAngularAccel = 2.0f; // Maximum angular acceleration in rad/s²

            // Linear acceleration is proportional to the average of left and right PWM
            a_cmd = ((leftPwm + rightPwm) / 2.0f) / 255.0f * maxAccel;

            // Angular acceleration is proportional to the difference between right and left PWM
            alpha_cmd = (rightPwm - leftPwm) / 255.0f * maxAngularAccel / wheelBase;
        } else {
            // Fallback to default values if motors object is not available
            alpha_cmd = 0.05f;
            a_cmd = 0.2f;
        }

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

        // Get the fused angular velocity
        float w_fused = kalmanFilter.getAngularVelocity();

        // Send the three angular velocity values if communication is available
        if (communication != nullptr) {
            // Send wheel encoder angular velocity
            communication->sendData("e" + String(w_wheel, 6));

            // Send IMU angular velocity (x-axis)
            communication->sendData("i" + String(w_imu, 6));

            // Send fused angular velocity
            communication->sendData("k" + String(w_fused, 6));

            // Send current PWM values if motors object is available
            if (motors != nullptr) {
                // Get current PWM values
                int leftPwm = motors->getCurrentPwmLeft();
                int rightPwm = motors->getCurrentPwmRight();

                // Send PWM values with prefix "p"
                communication->sendData("p" + String(leftPwm) + "," + String(rightPwm));
            }

            // Send current wheel counts with prefix "c"
            unsigned int leftCount = getLeftWheelCount();
            unsigned int rightCount = getRightWheelCount();
            communication->sendData("c" + String(leftCount) + "," + String(rightCount));
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

// Read raw IMU data
void Sensors::readIMU() {
    if (!imuInitialized) {
        return;
    }

    mpu->update();

    // Apply bias correction to gyroscope readings
    gx = radians(mpu->getGyroX() - gx_bias);
    gy = radians(mpu->getGyroY() - gy_bias);
    gz = radians(mpu->getGyroZ() - gz_bias);
}

// Update IMU reading - called by timer every 2ms
void Sensors::updateIMUReading() {
    if (!imuInitialized) {
        return;
    }

    unsigned long currentTime = millis();

    // Read the IMU data
    readIMU();

    // Store the gyro x value in the circular buffer
    gxSamples[sampleIndex] = gx;
    sampleIndex = (sampleIndex + 1) % IMU_BUFFER_SIZE;
}

// Get angular velocity from IMU (rad/s)
float Sensors::getAngularVelocityFromIMU() {
    if (!imuInitialized) {
        return 0.0f;
    }

    static float lastAvgGx = 0.0f;
    unsigned long currentTime = millis();

    // Calculate average at the update interval
    float sumGx = 0.0f;

    // Sum all samples in the buffer
    for (int i = 0; i < IMU_BUFFER_SIZE; i++) {
        sumGx += gxSamples[i];
    }

    // Calculate average
    float avgGx = sumGx / IMU_BUFFER_SIZE;

    // Store for return between updates
    lastAvgGx = avgGx;

    // Update timestamp
    lastUpdateTime = currentTime;

    // zero out the gxSamples
    for (int i = 0; i < IMU_BUFFER_SIZE; i++) {
        gxSamples[i] = 0.0f;
    }

    return avgGx;
}

// Calibrate IMU to estimate biases for all axes
void Sensors::calibrateIMU() {
    if (!imuInitialized) {
        return;
    }

    const int calibSamples = 600;
    float gxSum = 0.0f, gySum = 0.0f, gzSum = 0.0f;

    for (int i = 0; i < calibSamples; i++) {
        mpu->update();
        gxSum += mpu->getGyroX();
        gySum += mpu->getGyroY();
        gzSum += mpu->getGyroZ();
        delay(5);
    }

    gx_bias = gxSum / calibSamples;
    gy_bias = gySum / calibSamples;
    gz_bias = gzSum / calibSamples;
}
