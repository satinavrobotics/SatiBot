#include "../include/Communication.h"

#if defined(ESP32)
// Bluetooth callback classes implementation
class MyServerCallbacks : public BLEServerCallbacks {
private:
    Communication* comm;
    
public:
    MyServerCallbacks(Communication* comm) : comm(comm) {}
    
    void onConnect(BLEServer* bleServer, esp_ble_gatts_cb_param_t* param) {
        // Direct access to the deviceConnected flag
        comm->deviceConnected = true;
        Serial.println("BT Connected");
        
        // Set connection parameters to improve stability
        uint16_t minInterval = 0x06;  // 7.5ms (6 * 1.25ms)
        uint16_t maxInterval = 0x0C;  // 15ms (12 * 1.25ms)
        uint16_t latency = 0;         // Number of connection events
        uint16_t timeout = 500;       // 5s (500 * 10ms)
        
        bleServer->updateConnParams(param->connect.remote_bda, minInterval, maxInterval, latency, timeout);
    }

    void onDisconnect(BLEServer* bleServer) {
        // Direct access to the deviceConnected flag
        comm->deviceConnected = false;
        Serial.println("BT Disconnected");
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

Communication::Communication(Config* config, Motors* motors, Sensors* sensors)
    : config(config),
      motors(motors),
      sensors(sensors),
      msgPart(HEADER),
      header('\0'),
      endChar('\n'),
      msgIdx(0),
      heartbeatInterval(-1),
      heartbeatTime(0)
#if defined(ESP32)
      ,deviceConnected(false),
      oldDeviceConnected(false)
#endif
{
      
#if defined(ESP32)
    SERVICE_UUID = "61653dc3-4021-4d1e-ba83-8b4eec61d613";
    CHARACTERISTIC_UUID_RX = "06386c14-86ea-4d71-811c-48f97c58f8c9";
    CHARACTERISTIC_UUID_TX = "9bf1103b-834c-47cf-b149-c9e4bcf778a7";
#endif
}

void Communication::begin() {
    Serial.begin(115200);
    Serial.println('r');
    
#if defined(ESP32)
    if (config->hasBluetoothSupport()) {
        initializeBluetooth();
    }
#endif
}

void Communication::processIncomingMessages() {
    if (Serial.available() > 0) {
        onSerialRx();
    }
    
    // Bluetooth messages are handled through callbacks, no need to check here
    // The onBleRx method is called directly from the BLE callback
}

void Communication::onSerialRx() {
    char inChar = Serial.read();
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
    if (config->isDebugMode()) {
        Serial.print("Parsing message with header: ");
        Serial.println(header);
        Serial.print("Message body: ");
        Serial.println(msgBuf);
    }
    
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
        default:
            if (config->isDebugMode()) {
                Serial.print("Unknown header: ");
                Serial.println(header);
            }
            break;
    }
    msgIdx = 0;
    msgPart = HEADER;
    header = '\0';
}

void Communication::processCtrlMsg() {
    char *tmp;                    // this is used by strtok() as an index
    tmp = strtok(msgBuf, ",:");   // replace delimiter with \0
    int leftControl = atoi(tmp);  // convert to int
    tmp = strtok(NULL, ",:");     // continues where the previous call left off
    int rightControl = atoi(tmp); // convert to int
    
    motors->setLeftControl(leftControl);
    motors->setRightControl(rightControl);
    
    Serial.print("Control: ");
    Serial.print(leftControl);
    Serial.print(",");
    Serial.println(rightControl);
}

void Communication::processHeartbeatMsg() {
    heartbeatInterval = atol(msgBuf);  // convert to long
    heartbeatTime = millis();
    
    if (config->isDebugMode()) {
        Serial.print("Heartbeat Interval: ");
        Serial.println(heartbeatInterval);
    }
}

void Communication::processFeatureMsg() {
    String msg = "f" + config->getRobotTypeString() + ":";
    
    if (config->hasStatusLeds()) {
        msg += "ls:";
    }
    
    sendData(msg);
}

void Communication::sendData(String data) {
    Serial.print(data);
    Serial.println();
    
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


bool Communication::isHeartbeatExpired() {
    return (millis() - heartbeatTime) >= heartbeatInterval;
}

#if defined(ESP32)
void Communication::setDeviceConnected(bool connected) {
    deviceConnected = connected;
}

void Communication::initializeBluetooth() {
    Serial.println("Initializing Bluetooth...");
    String bleName = "OpenBot: " + config->getRobotTypeString();
    
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

    // Start advertising
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(BLEUUID(SERVICE_UUID));
    bleServer->getAdvertising()->start();
    Serial.println("Waiting for Bluetooth connection...");
}

void Communication::updateBluetoothConnection() {
    // disconnecting
    if (!deviceConnected && oldDeviceConnected) {
        delay(500);                     // give the bluetooth stack the chance to get things ready
        bleServer->startAdvertising();  // restart advertising
        Serial.println("Waiting for Bluetooth connection...");
        oldDeviceConnected = deviceConnected;
    }
    // connecting
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
    }
}

bool Communication::isDeviceConnected() const {
    return deviceConnected;
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
