#include "../include/Communication.h"

#if defined(ESP32)
// Bluetooth callback classes implementation
class MyServerCallbacks : public BLEServerCallbacks {
private:
    Communication* comm;

public:
    MyServerCallbacks(Communication* comm) : comm(comm) {}

    void onConnect(BLEServer* bleServer, esp_ble_gatts_cb_param_t* param) {
        comm->deviceConnected = true;

        uint16_t minInterval = 0x06;
        uint16_t maxInterval = 0x0C;
        uint16_t latency = 0;
        uint16_t timeout = 500;

        bleServer->updateConnParams(param->connect.remote_bda, minInterval, maxInterval, latency, timeout);
    }

    void onDisconnect(BLEServer* bleServer) {
        comm->deviceConnected = false;
        bleServer->getAdvertising()->stop();
        delay(100);
        comm->needsAdvertisingRestart = true;
    }
};

class MyCallbacks : public BLECharacteristicCallbacks {
private:
    Communication* comm;

public:
    MyCallbacks(Communication* comm) : comm(comm) {}

    void onWrite(BLECharacteristic* pCharacteristic) {
        std::string rxValue = pCharacteristic->getValue().c_str();

        if (rxValue.length() > 0) {
            // Process each character
            for (int i = 0; i < rxValue.length(); i++) {
                comm->onBleRx(rxValue[i]);
            }
        }
    }
};
#endif

Communication::Communication(Config* config, VelocityController* velocityController, Sensors* sensors)
    : config(config),
      velocityController(velocityController),
      sensors(sensors),
      msgPart(HEADER),
      header('\0'),
      endChar('\n'),
      msgIdx(0),
      heartbeatInterval(-1),
      heartbeatTime(0)
#if defined(ESP32)
      ,deviceConnected(false),
      oldDeviceConnected(false),
      needsAdvertisingRestart(false)
#endif
{

#if defined(ESP32)
    SERVICE_UUID = "61653dc3-4021-4d1e-ba83-8b4eec61d613";
    CHARACTERISTIC_UUID_RX = "06386c14-86ea-4d71-811c-48f97c58f8c9";
    CHARACTERISTIC_UUID_TX = "9bf1103b-834c-47cf-b149-c9e4bcf778a7";
#endif
}

void Communication::begin() {
#if defined(ESP32)
    if (config->hasBluetoothSupport()) {
        initializeBluetooth();
    }
#endif
}

void Communication::processIncomingMessages() {
    // Bluetooth messages are handled through callbacks, no need to check here
    // The onBleRx method is called directly from the BLE callback
}



void Communication::processHeader(char inChar) {
    header = inChar;
    msgPart = BODY;
}

void Communication::processBody(char inChar) {
    // Add the incoming byte to the buffer
    msgBuf[msgIdx] = inChar;
    msgIdx++;
}

void Communication::parseMsg() {
    switch (header) {
        case 'c':
            processCtrlMsg();
            break;
        case 'f':
            processFeatureMsg();
            break;
        case 'h':
            processHeartbeatMsg();
            break;
        case 'm':
            processMotorControlMsg();
            break;
        case 's':
            processStopMsg();
            break;

        default:
            break;
    }
    msgIdx = 0;
    msgPart = HEADER;
    header = '\0';
}

void Communication::processCtrlMsg() {
    char *tmp;                    // this is used by strtok() as an index
    tmp = strtok(msgBuf, ",:");   // replace delimiter with \0
    float linearVelocity = atof(tmp);  // convert to float
    tmp = strtok(NULL, ",:");     // continues where the previous call left off
    float angularVelocity = atof(tmp); // convert to float

    // Set velocity directly using the VelocityController
    velocityController->setTargetLinearVelocity(linearVelocity);
    velocityController->setTargetAngularVelocity(angularVelocity);

    // Controller is always enabled
}

void Communication::processHeartbeatMsg() {
    heartbeatInterval = atol(msgBuf);  // convert to long
    heartbeatTime = millis();
}

void Communication::processFeatureMsg() {
    String msg = "f" + config->getRobotTypeString() + ":";

    if (config->hasStatusLeds()) {
        msg += "ls:";
    }

    sendData(msg);
}

void Communication::sendData(String data) {
#if defined(ESP32)
    if (config->hasBluetoothSupport() && deviceConnected) {
        char outData[MAX_MSG_SZ] = "";
        for (int i = 0; i < data.length(); i++) {
            outData[i] = data[i];
        }
        pTxCharacteristic->setValue(outData);
        pTxCharacteristic->notify();
    }
#endif
}

// New method to handle sending data with a command prefix and value
void Communication::sendData(char cmd, const char* value) {
    String data = String(cmd) + String(value);
    sendData(data);
}

bool Communication::isHeartbeatExpired() {
    return (millis() - heartbeatTime) >= heartbeatInterval;
}

void Communication::updateHeartbeat() {
    heartbeatTime = millis();
}

#if defined(ESP32)
void Communication::setDeviceConnected(bool connected) {
    deviceConnected = connected;
}

void Communication::initializeBluetooth() {
    String bleName = "SatiBot: " + config->getRobotTypeString();

    BLEDevice::init(bleName.c_str());
    bleServer = BLEDevice::createServer();
    bleServer->setCallbacks(new MyServerCallbacks(this));

    BLEService *pService = bleServer->createService(BLEUUID(SERVICE_UUID));

    pTxCharacteristic = pService->createCharacteristic(
        BLEUUID(CHARACTERISTIC_UUID_TX),
        BLECharacteristic::PROPERTY_NOTIFY
    );
    pTxCharacteristic->addDescriptor(new BLE2902());

    pRxCharacteristic = pService->createCharacteristic(
        BLEUUID(CHARACTERISTIC_UUID_RX),
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    pRxCharacteristic->setCallbacks(new MyCallbacks(this));
    pRxCharacteristic->addDescriptor(new BLE2902());

    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(BLEUUID(SERVICE_UUID));
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    bleServer->getAdvertising()->start();
}

void Communication::updateBluetoothConnection() {
    if (needsAdvertisingRestart) {
        BLEDevice::deinit(false);
        delay(1000);
        initializeBluetooth();
        needsAdvertisingRestart = false;
        oldDeviceConnected = false;
        return;
    }

    if (!deviceConnected && oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
    }

    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
        needsAdvertisingRestart = false;
    }
}

bool Communication::isDeviceConnected() const {
    return deviceConnected;
}

void Communication::resetBluetoothConnection() {
    BLEDevice::deinit(false);
    deviceConnected = false;
    oldDeviceConnected = false;
    needsAdvertisingRestart = false;
    delay(1000);

    if (config->hasBluetoothSupport()) {
        initializeBluetooth();
    }
}

void Communication::onBleRx(char inChar) {
    if (inChar != endChar) {
        switch (msgPart) {
            case HEADER:
                processHeader(inChar);
                return;
            case BODY:
                processBody(inChar);
                return;
        }
    } else {
        msgBuf[msgIdx] = '\0';  // end of message
        parseMsg();
    }
}
#endif


void Communication::processMotorControlMsg() {
    // Check if message is empty - if so, send current configuration
    if (msgBuf[0] == '\0' || strlen(msgBuf) == 0) {
        // Send current motor control parameters
        String response = "m" +
                         String(velocityController->getKp(), 2) + "," +
                         String(velocityController->getKd(), 2) + "," +
                         String(velocityController->getNoControlScaleFactor(), 2) + "," +
                         String(velocityController->getNormalControlScaleFactor(), 2) + "," +
                         String(velocityController->getRotationScaleFactor(), 2) + "," +
                         String(velocityController->getVelocityBias(), 2) + "," +
                         String(velocityController->getRotationBias(), 2);
        sendData(response);
        return;
    }

    // Parse message format: m<kp>,<kd>,<noControlScale>,<normalControlScale>,<rotationScale>,<velocityBias>,<rotationBias>
    char *tmp;
    tmp = strtok(msgBuf, ",:");
    float kp = atof(tmp);
    tmp = strtok(NULL, ",:");
    float kd = atof(tmp);
    tmp = strtok(NULL, ",:");
    float noControlScale = atof(tmp);
    tmp = strtok(NULL, ",:");
    float normalControlScale = atof(tmp);
    tmp = strtok(NULL, ",:");
    float rotationScale = atof(tmp);
    tmp = strtok(NULL, ",:");
    float velocityBias = atof(tmp);
    tmp = strtok(NULL, ",:");
    float rotationBias = atof(tmp);

    // Update all control parameters using the combined setter
    velocityController->setControlParameters(kp, kd, noControlScale, normalControlScale,
                                           rotationScale, velocityBias, rotationBias);
}

void Communication::processStopMsg() {
    // Parse stop command - expect "1" to enable stop, "0" to disable stop
    bool enableStop = (msgBuf[0] == '1');

    // Immediately stop velocities without ramping
    velocityController->setTargetLinearVelocity(0.0f);
    velocityController->setTargetAngularVelocity(0.0f);

    // Control the stop pins through the velocity controller
    velocityController->setEmergencyStop(enableStop);
}
