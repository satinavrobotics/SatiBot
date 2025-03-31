// Required App Version: 0.7.0
// ---------------------------------------------------------------------------
// This Arduino Nano sketch accompanies the OpenBot Android application.
//
// The sketch has the following functionalities:
//  - receive control commands and sensor config from Android application (USB serial)
//  - produce low-level controls (PWM) for the vehicle
//  - toggle left and right indicator signals
//  - wheel odometry based on optical speed sensors
//  - estimate battery voltage via voltage divider
//  - estimate distance based on sonar sensor
//  - control LEDs for status and at front and back of vehicle
//  - send sensor readings to Android application (USB serial)
//  - display vehicle status on OLED
//
// Dependencies: Install via "Tools --> Manage Libraries" (type library name in the search field)
//  - Interrupts: PinChangeInterrupt by Nico Hood (read speed sensors and sonar)
//  - OLED: Adafruit_SSD1306 & Adafruit_GFX (display vehicle status)
//  - Servo: Built-In library by Michael Margolis (required for RC truck)
// Contributors:
//  - October 2020: OLED display support by Ingmar Stapel
//  - December 2021: RC truck support by Usman Fiaz
//  - March 2022: OpenBot-Lite support by William Tan
//  - May 2022: MTV support by Quentin Leboutet
//  - Jan 2023: BLE support by iTinker
// ---------------------------------------------------------------------------

// By Matthias Mueller, 2023
// ---------------------------------------------------------------------------

//------------------------------------------------------//
// DEFINITIONS - DO NOT CHANGE!
//------------------------------------------------------//
#include <Arduino.h>   // Alapvető Arduino funkciók

//MCUs
#define NANO 328 //Atmega328p
#define ESP32 32 //ESP32

//Robot bodies with Atmega328p as MCU --> Select Arduino Nano as board
#define DIY 0        // DIY without PCB
//Robot bodies with ESP32 as MCU --> Select ESP32 Dev Module as board
#define DIY_ESP32 1  // DIY without PCB

//------------------------------------------------------//
// SETUP - Choose your body
//------------------------------------------------------//

// Setup the OpenBot version (DIY, PCB_V1, PCB_V2, RTR_TT, RC_CAR, LITE, RTR_TT2, RTR_520, DIY_ESP32)
#define OPENBOT DIY_ESP32
#define SATIBOT_VERSION 0 // (0: SatiBotV0, 1: SatiBotV1)

//------------------------------------------------------//
// SETTINGS - Global settings
//------------------------------------------------------//

// Enable/Disable no phone mode (1,0)
// In no phone mode:
// - the motors will turn at 75% speed
// - the speed will be reduced if an obstacle is detected by the sonar sensor
// - the car will turn, if an obstacle is detected within TURN_DISTANCE
// WARNING: If the sonar sensor is not setup, the car will go full speed forward!
#define NO_PHONE_MODE 0

// Enable/Disable debug print (1,0)
#define DEBUG 0

//------------------------------------------------------//
// CONFIG - update if you have built the DIY version
//------------------------------------------------------//
// HAS_BUMPER                           Enable/Disable bumper (1,0)
// HAS_LEDS_STATUS                      Enable/Disable status LEDs
// HAS_BLUETOOTH                        Enable/Disable bluetooth connectivity (1,0)
// NOTE: HAS_BLUETOOTH will only work with the ESP32 board (RTR_TT2, RTR_520, MTV, DIY_ESP32)

// PIN_PWM_L1,PIN_PWM_L2                Low-level control of left DC motors via PWM
// PIN_PWM_R1,PIN_PWM_R2                Low-level control of right DC motors via PWM


// ----- Global Control Variables -----
// These variables should be updated (e.g., via USB messages)
// They represent the desired control signal. Negative values mean reverse.
volatile int ctrl_left = 0;
volatile int ctrl_right = 0;

#if SATIBOT_VERSION == 1
// ----- Acceleration Variables -----
// For each motor, we use a "current" PWM (applied to the motor)
// and a "target" PWM (absolute value of ctrl_left/right).
int currentPWM_left = 0;
int currentPWM_right = 0;

int targetPWM_left = 0;
int targetPWM_right = 0;

// Timing variables for non-blocking updates
const unsigned long accelInterval = 10; // interval (in ms) between PWM updates
unsigned long lastUpdateLeft = 0;
unsigned long lastUpdateRight = 0;
// Amount to change PWM per update (tweak for smoother/faster acceleration)
const int accelStep = 1;

#endif


//-------------------------DIY--------------------------//
#if (OPENBOT == DIY)
const String robot_type = "DIY";
#define MCU NANO6
#define HAS_SPEED_SENSORS_FRONT 0

//#if SATIBOT_VERSION == 1
//const int PIN_PWM_L = 9;
//const int PIN_PWM_R = 10;
//const int PIN_DIRECTION_L = 11;
//const int PIN_DIRECTION_R = 12;
//#else
const int PIN_PWM_L1 = 9;
const int PIN_PWM_L2 = 10;
const int PIN_PWM_R1 = 20;
const int PIN_PWM_R2 = 21;
//#endif

//-------------------------DIY_ESP32----------------------//
#elif (OPENBOT == DIY_ESP32)
const String robot_type = "DIY_ESP32";
#define MCU ESP32
#include <driver/ledc.h>  // LEDC PWM API az ESP32-höz
#include <esp_wifi.h>
#define HAS_BLUETOOTH 1
//#define analogWrite ledcWrite
#define attachPinChangeInterrupt attachInterrupt
#define detachPinChangeInterrupt detachInterrupt
#define digitalPinToPinChangeInterrupt digitalPinToInterrupt

const int PIN_PWM_L1 = 10;
const int PIN_PWM_L2 = 9;
const int PIN_PWM_R1 = 21;
const int PIN_PWM_R2 = 20;

//#define PIN_PWM_L1 CH_PWM_L1
//#define PIN_PWM_L2 CH_PWM_L2
//#define PIN_PWM_R1 CH_PWM_R1
//#define PIN_PWM_R2 CH_PWM_R2

//const int _PIN_PWM_L1 = 9;
//const int _PIN_PWM_L2 = 10;
//const int _PIN_PWM_R1 = 20;
//const int _PIN_PWM_R2 = 21;

//PWM properties
//const int FREQ = 5000;
//const int RES = 8;
//const int CH_PWM_L1 = 0;
//const int CH_PWM_L2 = 1;
//const int CH_PWM_R1 = 2;
//const int CH_PWM_R2 = 3;
//const int PIN_PWM_LF1 = _PIN_PWM_L1;
//const int PIN_PWM_LF2 = _PIN_PWM_L2;
//const int PIN_PWM_LB1 = _PIN_PWM_L1;
//const int PIN_PWM_LB2 = _PIN_PWM_L2;
//const int PIN_PWM_RF1 = _PIN_PWM_R1;
//const int PIN_PWM_RF2 = _PIN_PWM_R2;
//const int PIN_PWM_RB1 = _PIN_PWM_R1;
//const int PIN_PWM_RB2 = _PIN_PWM_R2;
#endif
//------------------------------------------------------//

#if (HAS_BLUETOOTH)
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

BLEServer *bleServer = NULL;
BLECharacteristic *pTxCharacteristic;
BLECharacteristic *pRxCharacteristic;
bool deviceConnected = false;
bool oldDeviceConnected = false;
const char *SERVICE_UUID = "61653dc3-4021-4d1e-ba83-8b4eec61d613";  // UART service UUID
const char *CHARACTERISTIC_UUID_RX = "06386c14-86ea-4d71-811c-48f97c58f8c9";
const char *CHARACTERISTIC_UUID_TX = "9bf1103b-834c-47cf-b149-c9e4bcf778a7";
#endif

enum msgParts {
  HEADER,
  BODY
};

msgParts msgPart = HEADER;
char header;
char endChar = '\n';
const char MAX_MSG_SZ = 60;
char msg_buf[MAX_MSG_SZ] = "";
int msg_idx = 0;

#if (HAS_BLUETOOTH)
void on_ble_rx(char inChar) {
  if (inChar != endChar) {
    switch (msgPart) {
      case HEADER:
        process_header(inChar);
        return;
      case BODY:
        process_body(inChar);
        return;
    }
  } else {
    msg_buf[msg_idx] = '\0';  // end of message
    parse_msg();
  }
}

//Initialization of classes for bluetooth
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *bleServer, esp_ble_gatts_cb_param_t *param) {
    deviceConnected = true;

    // // Set the preferred connection parameters
    // uint16_t minInterval = 0; // Minimum connection interval in 1.25 ms units (50 ms)
    // uint16_t maxInterval = 800; // Maximum connection interval in 1.25 ms units (1000 ms)
    // uint16_t latency = 0;       // Slave latency
    // uint16_t timeout = 5000;     // Supervision timeout in 10 ms units (50 seconds)

    // bleServer->updateConnParams(param->connect.remote_bda, minInterval, maxInterval, latency, timeout);

    Serial.println("BT Connected");
  };

  void onDisconnect(BLEServer *bleServer) {
    deviceConnected = false;
    Serial.println("BT Disconnected");
  }
};

class MyCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    std::string bleReceiver = pCharacteristic->getValue().c_str();
    if (!bleReceiver.empty()) {
      for (int i = 0; i < bleReceiver.length(); i++) {
        on_ble_rx(bleReceiver[i]);
      }
    }
  }
};
#endif


//------------------------------------------------------//
// INITIALIZATION
//------------------------------------------------------//
#if (NO_PHONE_MODE)
unsigned long turn_direction_time = 0;
unsigned long turn_direction_interval = 5000;
unsigned int turn_direction = 0;
int ctrl_max = 192;
int ctrl_slow = 96;
int ctrl_min = 30; //(int)255.0 * VOLTAGE_MIN / VOLTAGE_MAX;
#endif

const unsigned int TURN_DISTANCE = -1;  //cm
const unsigned int STOP_DISTANCE = 0;   //cm
unsigned int distance_estimate = -1;    //cm

// Bumper
#if HAS_BUMPER
bool bumper_event = 0;
bool collision_lf = 0;
bool collision_rf = 0;
bool collision_cf = 0;
bool collision_lb = 0;
bool collision_rb = 0;
unsigned long bumper_interval = 750;
unsigned long bumper_time = 0;
const int bumper_array_sz = 5;
int bumper_array[bumper_array_sz] = { 0 };
int bumper_reading = 0;
#endif

//Heartbeat
unsigned long heartbeat_interval = -1;
unsigned long heartbeat_time = 0;

#if (DEBUG)
// Display (via Serial)
unsigned long display_interval = 1000;  // How frequently vehicle data is displayed (ms).
unsigned long display_time = 0;
#endif


//------------------------------------------------------//
// SETUP
//------------------------------------------------------//
void setup() {
//pinMode(PIN_DIRECTION_L, OUTPUT);
//pinMode(PIN_DIRECTION_R, OUTPUT);
//pinMode(PIN_PWM_L, OUTPUT);
//pinMode(PIN_PWM_R, OUTPUT);

#if (HAS_LEDS_STATUS)
  pinMode(PIN_LED_Y, OUTPUT);
  pinMode(PIN_LED_G, OUTPUT);
  pinMode(PIN_LED_B, OUTPUT);
#endif
#if (HAS_BUMPER)
  pinMode(PIN_BUMPER, INPUT);
#endif

#if (MCU == ESP32)
  esp_wifi_deinit();
#endif

  Serial.begin(115200);
  // Serial.begin(115200, SERIAL_8N1);
  // SERIAL_8E1 - 8 data bits, even parity, 1 stop bit
  // SERIAL_8O1 - 8 data bits, odd parity, 1 stop bit
  // SERIAL_8N1 - 8 data bits, no parity, 1 stop bit
  // Serial.setTimeout(10);
  Serial.println('r');

#if (HAS_BLUETOOTH)
  String ble_name = "OpenBot: " + robot_type;
  BLEDevice::init(ble_name.c_str());
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = bleServer->createService(BLEUUID(SERVICE_UUID));

  pTxCharacteristic = pService->createCharacteristic(BLEUUID(CHARACTERISTIC_UUID_TX), BLECharacteristic::PROPERTY_NOTIFY);
  pTxCharacteristic->addDescriptor(new BLE2902());

  pRxCharacteristic = pService->createCharacteristic(BLEUUID(CHARACTERISTIC_UUID_RX), BLECharacteristic::PROPERTY_WRITE_NR);
  pRxCharacteristic->setCallbacks(new MyCallbacks());
  pRxCharacteristic->addDescriptor(new BLE2902());

  pService->start();

  // Start advertising
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(BLEUUID(SERVICE_UUID));
  bleServer->getAdvertising()->start();
  Serial.println("Waiting a client connection to notify...");
#endif
}

//------------------------------------------------------//
//LOOP
//------------------------------------------------------//
void loop() {

#if (HAS_BLUETOOTH)
  // disconnecting
  if (!deviceConnected && oldDeviceConnected) {
    delay(500);                     // give the bluetooth stack the chance to get things ready
    bleServer->startAdvertising();  // restart advertising
    Serial.println("Waiting a client connection to notify...");
    oldDeviceConnected = deviceConnected;
  }
  // connecting
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }
#endif

#if (NO_PHONE_MODE)
  int speed = 128;
  ctrl_left = speed;
  ctrl_right = speed;
#else  // Check for messages from the phone
  if (Serial.available() > 0) {
    on_serial_rx();
  }
  if (distance_estimate <= STOP_DISTANCE && ctrl_left > 0 && ctrl_right > 0) {
    ctrl_left = 0;
    ctrl_right = 0;
  }
  if ((millis() - heartbeat_time) >= heartbeat_interval) {
    ctrl_left = 0;
    ctrl_right = 0;
  }
#endif

#if HAS_BUMPER
  if (analogRead(PIN_BUMPER) > BUMPER_NOISE && !bumper_event) {
    delayMicroseconds(500);
    for (unsigned int i = 0; i < bumper_array_sz; i++) {
      bumper_array[i] = analogRead(PIN_BUMPER);
    }
    bumper_reading = get_median(bumper_array, bumper_array_sz);
    if (bumper_reading > BUMPER_NOISE)
      emergency_stop();
  }

  bool collison_front = collision_lf || collision_rf || collision_cf;
  bool collision_back = collision_lb || collision_rb;
  bool control_front = ctrl_left > 0 && ctrl_right > 0;
  bool control_back = ctrl_left < 0 && ctrl_right < 0;

  if (!bumper_event || (control_back && collison_front) || (control_front && collision_back)) {
    update_vehicle();
  }
#else
  update_vehicle();
#endif



#if HAS_BUMPER
  // Check bumper signal every bumper_interval
  if ((millis() - bumper_time) >= bumper_interval && bumper_event) {
    reset_bumper();
    bumper_time = millis();
  }
#endif
}

void update_vehicle() {
  update_left_motors();
  update_right_motors();
}


#if SATIBOT_VERSION == 1
unsigned long lastDirectionChangeLeft = 0;
unsigned long lastDirectionChangeRight = 0;
const unsigned long directionChangeDelay = 200;

// Updates left motor: gradually changes currentPWM_left toward abs(ctrl_left)
// and sets the direction accordingly, but only if the motor is nearly stopped.
void update_left_motors() {
  unsigned long now = millis();
  if (now - lastUpdateLeft >= accelInterval) {
    lastUpdateLeft = now;
    
    // Determine target PWM as the absolute value of ctrl_left
    int targetPWM_left = ctrl_left;
    
    // Check if direction needs to change
    bool directionChangeNeeded = (targetPWM_left < 0 && currentPWM_left >= 0) || 
                                 (targetPWM_left > 0 && currentPWM_left <= 0);
    
    if (directionChangeNeeded) {
      // Decelerate to zero before changing direction
      if (abs(currentPWM_left) > 0) {
        if (currentPWM_left > 0) {
          currentPWM_left -= accelStep;
          if (currentPWM_left < 0) currentPWM_left = 0;
        } else {
          currentPWM_left += accelStep;
          if (currentPWM_left > 0) currentPWM_left = 0;
        }
        if (currentPWM_left == 0) lastDirectionChangeLeft = now;
      } else if (now - lastDirectionChangeLeft >= directionChangeDelay) {
        // Change direction when fully stopped
        currentPWM_left = targetPWM_left > 0 ? accelStep : -accelStep;
      }
    } else {
      // Gradually adjust current PWM toward target PWM
      if (abs(currentPWM_left) < abs(targetPWM_left)) {
        currentPWM_left += (targetPWM_left > 0) ? accelStep : -accelStep;
        if (abs(currentPWM_left) > abs(targetPWM_left)) 
          currentPWM_left = targetPWM_left;
      } else if (abs(currentPWM_left) > abs(targetPWM_left)) {
        currentPWM_left -= (currentPWM_left > 0) ? accelStep : -accelStep;
        if (abs(currentPWM_left) < abs(targetPWM_left)) 
          currentPWM_left = targetPWM_left;
      }
    }
    
    // Update direction pin based on sign of currentPWM_left
    digitalWrite(PIN_DIRECTION_L, (currentPWM_left >= 0) ? HIGH : LOW);
    
    // Update PWM output on left motor
    analogWrite(PIN_PWM_L, abs(currentPWM_left));
  }
}


// Updates right motor: gradually changes currentPWM_right toward abs(ctrl_right)
// and sets the direction accordingly, but only if the motor is nearly stopped.
void update_right_motors() {
  unsigned long now = millis();
  if (now - lastUpdateRight >= accelInterval) {
    lastUpdateRight = now;
    
    // Determine target PWM as the absolute value of ctrl_right
    int targetPWM_right = ctrl_right;
    
    // Check if direction needs to change
    bool directionChangeNeeded = (targetPWM_right < 0 && currentPWM_right >= 0) || 
                                 (targetPWM_right > 0 && currentPWM_right <= 0);
    
    if (directionChangeNeeded) {
      // Decelerate to zero before changing direction
      if (abs(currentPWM_right) > 0) {
        if (currentPWM_right > 0) {
          currentPWM_right -= accelStep;
          if (currentPWM_right < 0) currentPWM_right = 0;
        } else {
          currentPWM_right += accelStep;
          if (currentPWM_right > 0) currentPWM_right = 0;
        }
        if (currentPWM_right == 0) lastDirectionChangeRight = now;
      } else if (now - lastDirectionChangeRight >= directionChangeDelay){
        // Change direction when fully stopped
        currentPWM_right = targetPWM_right > 0 ? accelStep : -accelStep;
      }
    } else {
      // Gradually adjust current PWM toward target PWM
      if (abs(currentPWM_right) < abs(targetPWM_right)) {
        currentPWM_right += (targetPWM_right > 0) ? accelStep : -accelStep;
        if (abs(currentPWM_right) > abs(targetPWM_right)) 
          currentPWM_right = targetPWM_right;
      } else if (abs(currentPWM_right) > abs(targetPWM_right)) {
        currentPWM_right -= (currentPWM_right > 0) ? accelStep : -accelStep;
        if (abs(currentPWM_right) < abs(targetPWM_right)) 
          currentPWM_right = targetPWM_right;
      }
    }
    
    // Update direction pin based on sign of currentPWM_right
    digitalWrite(PIN_DIRECTION_R, (currentPWM_right >= 0) ? LOW : HIGH);
    
    // Update PWM output on right motor
    analogWrite(PIN_PWM_R, abs(currentPWM_right));
  }
}

#else

void update_left_motors() {
  Serial.print("left");
  Serial.println(ctrl_left);
  Serial.println(PIN_PWM_L1);
  Serial.println(PIN_PWM_L2);
  if (ctrl_left < 0) {
    analogWrite(PIN_PWM_L1, -ctrl_left);
    analogWrite(PIN_PWM_L2, 0);
  } else if (ctrl_left > 0) {
    analogWrite(PIN_PWM_L1, 0);
    analogWrite(PIN_PWM_L2, ctrl_left);
  } else {
    stop_left_motors();
  }
}

void update_right_motors() {
  Serial.print("right");
  Serial.println(ctrl_left);
  Serial.println(PIN_PWM_R1);
  Serial.println(PIN_PWM_R2);
  if (ctrl_right < 0) {
    analogWrite(PIN_PWM_R1, -ctrl_right);
    analogWrite(PIN_PWM_R2, 0);
  } else if (ctrl_right > 0) {
    analogWrite(PIN_PWM_R1, 0);
    analogWrite(PIN_PWM_R2, ctrl_right);
  } else {
    stop_right_motors();
  }
}

void stop_left_motors() {
  analogWrite(PIN_PWM_L1, 0);
  analogWrite(PIN_PWM_L2, 0);
}

void stop_right_motors() {
  analogWrite(PIN_PWM_R1, 0);
  analogWrite(PIN_PWM_R2, 0);
}
#endif

boolean almost_equal(int a, int b, int eps) {
  return abs(a - b) <= eps;
}

#if HAS_BUMPER
void emergency_stop() {
  bumper_event = true;
  stop_left_motors();
  stop_right_motors();
  ctrl_left = 0;
  ctrl_right = 0;
  bumper_time = millis();
  char bumper_id[2];
  if (almost_equal(bumper_reading, BUMPER_AF, BUMPER_EPS)) {
    collision_cf = 1;
    collision_lf = 1;
    collision_rf = 1;
    strncpy(bumper_id, "af", sizeof(bumper_id));
#if DEBUG
    Serial.print("All Front: ");
#endif
  } else if (almost_equal(bumper_reading, BUMPER_BF, BUMPER_EPS)) {
    collision_lf = 1;
    collision_rf = 1;
    strncpy(bumper_id, "bf", sizeof(bumper_id));
#if DEBUG
    Serial.print("Both Front: ");
#endif
  } else if (almost_equal(bumper_reading, BUMPER_CF, BUMPER_EPS)) {
    collision_cf = 1;
    strncpy(bumper_id, "cf", sizeof(bumper_id));
#if DEBUG
    Serial.print("Camera Front: ");
#endif
  } else if (almost_equal(bumper_reading, BUMPER_LF, BUMPER_EPS)) {
    collision_lf = 1;
    strncpy(bumper_id, "lf", sizeof(bumper_id));
#if DEBUG
    Serial.print("Left Front: ");
#endif
  } else if (almost_equal(bumper_reading, BUMPER_RF, BUMPER_EPS)) {
    collision_rf = 1;
    strncpy(bumper_id, "rf", sizeof(bumper_id));
#if DEBUG
    Serial.print("Right Front: ");
#endif
  } else if (almost_equal(bumper_reading, BUMPER_BB, BUMPER_EPS)) {
    collision_lb = 1;
    collision_rb = 1;
    strncpy(bumper_id, "bb", sizeof(bumper_id));
#if DEBUG
    Serial.print("Both Back: ");
#endif
  } else if (almost_equal(bumper_reading, BUMPER_LB, BUMPER_EPS)) {
    collision_lb = 1;
    strncpy(bumper_id, "lb", sizeof(bumper_id));
#if DEBUG
    Serial.print("Left Back: ");
#endif
  } else if (almost_equal(bumper_reading, BUMPER_RB, BUMPER_EPS)) {
    collision_rb = 1;
    strncpy(bumper_id, "rb", sizeof(bumper_id));
#if DEBUG
    Serial.print("Right Back: ");
#endif
  } else {
    strncpy(bumper_id, "??", sizeof(bumper_id));
#if DEBUG
    Serial.print("Unknown: ");
#endif
  }
#if DEBUG
  Serial.println(bumper_reading);
#endif
  send_bumper_reading(bumper_id);
}

void reset_bumper() {
  collision_lf = 0;
  collision_rf = 0;
  collision_cf = 0;
  collision_lb = 0;
  collision_rb = 0;
  bumper_reading = 0;
  bumper_event = false;
}

void send_bumper_reading(char bumper_id[]) {
  sendData("b" + String(bumper_id));
}
#endif

void process_ctrl_msg() {
  char *tmp;                    // this is used by strtok() as an index
  tmp = strtok(msg_buf, ",:");  // replace delimiter with \0
  ctrl_left = atoi(tmp);        // convert to int
  tmp = strtok(NULL, ",:");     // continues where the previous call left off
  ctrl_right = atoi(tmp);       // convert to int
#if DEBUG
  Serial.print("Control: ");
  Serial.print(ctrl_left);
  Serial.print(",");
  Serial.println(ctrl_right);
#endif
}

void process_heartbeat_msg() {
  heartbeat_interval = atol(msg_buf);  // convert to long
  heartbeat_time = millis();
#if DEBUG
  Serial.print("Heartbeat Interval: ");
  Serial.println(heartbeat_interval);
#endif
}

#if HAS_BUMPER
void process_bumper_msg() {
  bumper_interval = atol(msg_buf);  // convert to long
}
#endif

void process_feature_msg() {
  String msg = "f" + robot_type + ":";
#if HAS_BUMPER
  msg += "b:";
#endif
#if HAS_LEDS_STATUS
  msg += "ls:";
#endif
  sendData(msg);
}

void on_serial_rx() {
  char inChar = Serial.read();
  if (inChar != endChar) {
    switch (msgPart) {
      case HEADER:
        process_header(inChar);
        return;
      case BODY:
        process_body(inChar);
        return;
    }
  } else {
    msg_buf[msg_idx] = '\0';  // end of message
    parse_msg();
  }
}

void process_header(char inChar) {
  header = inChar;
  msgPart = BODY;
}

void process_body(char inChar) {
  // Add the incoming byte to the buffer
  msg_buf[msg_idx] = inChar;
  msg_idx++;
}

void parse_msg() {
  switch (header) {
#if HAS_BUMPER
    case 'b':
      process_bumper_msg();
      break;
#endif
    case 'c':
      process_ctrl_msg();
      break;
    case 'f':
      process_feature_msg();
      break;
    case 'h':
      process_heartbeat_msg();
      break;
#if HAS_LEDS_STATUS
    case 'n':
      process_notification_msg();
      break;
#endif
  }
  msg_idx = 0;
  msgPart = HEADER;
  header = '\0';
}

int get_median(int a[], int sz) {
  //bubble sort
  for (int i = 0; i < (sz - 1); i++) {
    for (int j = 0; j < (sz - (i + 1)); j++) {
      if (a[j] > a[j + 1]) {
        int t = a[j];
        a[j] = a[j + 1];
        a[j + 1] = t;
      }
    }
  }
  return a[sz / 2];
}



void sendData(String data) {
Serial.print(data);
Serial.println();
#if (HAS_BLUETOOTH)
  if (deviceConnected) {
    char outData[MAX_MSG_SZ] = "";
    for (int i = 0; i < data.length(); i++) {
      outData[i] = data[i];
    }
    pTxCharacteristic->setValue(outData);
    pTxCharacteristic->notify();
  }
#endif
}
