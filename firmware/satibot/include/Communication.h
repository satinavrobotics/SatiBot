#ifndef COMMUNICATION_H
#define COMMUNICATION_H

#include "Config.h"
#include "Sensors.h"
#include "VelocityController.h"

#if defined(ESP32)
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#endif

enum MsgParts {
    HEADER,
    BODY
};

// Forward declarations for Bluetooth callback classes
#if defined(ESP32)
class MyServerCallbacks;
class MyCallbacks;
#endif

class Communication {
public:
    Communication(Config* config, VelocityController* velocityController, Sensors* sensors);

    // Initialize communication
    void begin();

    // Process incoming messages
    void processIncomingMessages();

    // Send data
    void sendData(String data);
    void sendData(char cmd, const char* value);

    // Heartbeat management
    void updateHeartbeat();
    bool isHeartbeatExpired();

#if defined(ESP32)
    // Bluetooth management
    void updateBluetoothConnection();
    bool isDeviceConnected() const;
    void setDeviceConnected(bool connected);
    void resetBluetoothConnection();

    // Make deviceConnected public for direct access
    bool deviceConnected;
    bool oldDeviceConnected;
    bool needsAdvertisingRestart;

    // Make callback classes friends of Communication
    friend class MyServerCallbacks;
    friend class MyCallbacks;
#endif

private:
    Config* config;
    VelocityController* velocityController;
    Sensors* sensors;

    // Message parsing
    MsgParts msgPart;
    char header;
    char endChar;
    static const char MAX_MSG_SZ = 60;
    char msgBuf[MAX_MSG_SZ];
    int msgIdx;

    // Heartbeat
    unsigned long heartbeatInterval;
    unsigned long heartbeatTime;

#if defined(ESP32)
    // Bluetooth
    BLEServer* bleServer;
    BLECharacteristic* pTxCharacteristic;
    BLECharacteristic* pRxCharacteristic;
    const char* SERVICE_UUID;
    const char* CHARACTERISTIC_UUID_RX;
    const char* CHARACTERISTIC_UUID_TX;
#endif

    // Message processing methods
    void processHeader(char inChar);
    void processBody(char inChar);
    void parseMsg();

    // Message handlers
    void processCtrlMsg();
    void processHeartbeatMsg();
    void processFeatureMsg();
    void processMotorControlMsg();
    void processStopMsg();

#if defined(ESP32)
    void onBleRx(char inChar);
    void initializeBluetooth();
#endif
};

#endif // COMMUNICATION_H
