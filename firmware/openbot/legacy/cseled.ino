#include <Wire.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>

// IMU
Adafruit_MPU6050 mpu;

// Pin assignment
const int pwm_1 = 6;  
const int pwm_2 = 7;
const int dir_1 = 10;
const int dir_2 = 20;
const int stop_1 = 5;
const int stop_2 = 21;
const int v_div = 2;

// IMU data
float gx;
float gx_bias = 0.0;

// IMU sampling parameters
const unsigned long imuSampleInterval = 10; // 10ms
const int numSamples = 25; // 25 samples over 250ms
float gxSamples[numSamples];
int sampleIndex = 0;
unsigned long lastIMUSampleTime = 0;
const unsigned long updateInterval = 250; // 250ms
unsigned long lastUpdateTime = 0;

// Heading
float heading = 0.0;
const float targetHeading = 0.0;
const float dt = 0.25; // 250ms

// PD parameters
const float Kp = 20.0;
const float Kd = 2.0;
float lastError = 0.0;

// Battery monitoring
int bat_perc = 0;

// Motor PWM
int pwm_left = 0;
int pwm_right = 0;
const int base_pwm = 30;

// Calibrate IMU
void calibrateIMU() {
  const int calibSamples = 600; // ~3s at 5ms
  float gxSum = 0.0;
  for (int i = 0; i < calibSamples; i++) {
    sensors_event_t a, g, temp;
    mpu.getEvent(&a, &g, &temp);
    gxSum += g.gyro.x;
    delay(5);
  }
  gx_bias = gxSum / calibSamples;
}

// Monitor battery
void monitorBattery() {
  int vRaw = analogRead(v_div);
  float voltage = vRaw * (3.3 / 4095.0);
  bat_perc = map(constrain(voltage, 2.77, 3.23) * 100, 277, 323, 0, 100);
}

void setup() {
  pinMode(pwm_1, OUTPUT);
  pinMode(pwm_2, OUTPUT);
  pinMode(dir_1, OUTPUT);
  pinMode(dir_2, OUTPUT);
  pinMode(stop_1, OUTPUT);
  pinMode(stop_2, OUTPUT);
  pinMode(v_div, INPUT);

  digitalWrite(dir_1, LOW); // Forward
  digitalWrite(dir_2, HIGH);
  digitalWrite(stop_1, LOW);
  digitalWrite(stop_2, LOW);
  analogWrite(pwm_1, 0);
  analogWrite(pwm_2, 0);

  Wire.begin(8, 9);
  delay(100);

  if (!mpu.begin(0x68, &Wire)) {
    while (1) delay(10);
  }

  mpu.setAccelerometerRange(MPU6050_RANGE_2_G);
  mpu.setGyroRange(MPU6050_RANGE_250_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);

  calibrateIMU();

  for (int i = 0; i < numSamples; i++) {
    gxSamples[i] = 0.0;
  }
}

// Read IMU (x-axis gyro only)
void imu_conversion() {
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);
  gx = g.gyro.x - gx_bias; // Yaw rate
}

// Get yaw rate
float getYawRate() {
  static float lastAvgGx = 0.0;

  if (millis() - lastIMUSampleTime >= imuSampleInterval) {
    imu_conversion();
    gxSamples[sampleIndex] = gx;
    sampleIndex = (sampleIndex + 1) % numSamples;
    lastIMUSampleTime = millis();
  }

  if (millis() - lastUpdateTime >= updateInterval) {
    float sumGx = 0.0;
    for (int i = 0; i < numSamples; i++) {
      sumGx += gxSamples[i];
    }
    float avgGx = sumGx / numSamples;
    lastAvgGx = avgGx;
    return avgGx;
  }

  return lastAvgGx;
}

// Update heading
void updateHeading(float omega) {
  heading += omega * dt; // theta += omega * dt
}

// PD controller
void controlHeading(float omega) {
  if (millis() - lastUpdateTime >= updateInterval) {
    updateHeading(omega);

    float error = targetHeading - heading;
    float derivative = (error - lastError) / dt;
    float pwm_adjust = Kp * error + Kd * derivative;

    lastError = error;

    pwm_left = base_pwm - pwm_adjust;
    pwm_right = base_pwm + pwm_adjust;

    pwm_left = constrain(pwm_left, 0, 255);
    pwm_right = constrain(pwm_right, 0, 255);

    analogWrite(pwm_1, pwm_left);
    analogWrite(pwm_2, pwm_right);

    lastUpdateTime = millis();
  }
}

// Move forward
void moveForward() {
  digitalWrite(dir_1, LOW);
  digitalWrite(dir_2, HIGH);
  digitalWrite(stop_1, LOW);
  digitalWrite(stop_2, LOW);

  float omega = getYawRate();
  controlHeading(omega);
}

// Move backward
void moveBackward() {
  digitalWrite(dir_1, HIGH);
  digitalWrite(dir_2, LOW);
  digitalWrite(stop_1, LOW);
  digitalWrite(stop_2, LOW);

  float omega = getYawRate();
  controlHeading(omega);
}

// Stop
void stopRobot() {
  digitalWrite(stop_1, HIGH);
  digitalWrite(stop_2, HIGH);
  analogWrite(pwm_1, 0);
  analogWrite(pwm_2, 0);
}

void loop() {
  monitorBattery();
  if (bat_perc < 10) {
    stopRobot();
    return;
  }
  moveForward();
}