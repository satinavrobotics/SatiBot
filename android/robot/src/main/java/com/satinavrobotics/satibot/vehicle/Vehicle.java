package com.satinavrobotics.satibot.vehicle;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import com.ficat.easyble.BleDevice;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import com.satinavrobotics.satibot.controller.GameController;
import com.satinavrobotics.satibot.env.SensorReading;
import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.main.CommonRecyclerViewAdapter;
import com.satinavrobotics.satibot.main.ScanDeviceAdapter;
import com.satinavrobotics.satibot.utils.Constants;

import timber.log.Timber;

public class Vehicle {

  private int indicator = 0;
  private int speedMultiplier = 192; // 128,192,255 - for linear velocity
  private int angularMultiplier = 192; // 128,192,255 - for angular velocity
  private Control control = new Control(0, 0);

  private final SensorReading batteryPercentage = new SensorReading();
  private final SensorReading leftWheelRpm = new SensorReading();
  private final SensorReading rightWheelRpm = new SensorReading();
  private final SensorReading sonarReading = new SensorReading();
  private final SensorReading wheelEncoderAngularVelocity = new SensorReading();
  private final SensorReading imuAngularVelocity = new SensorReading();
  private final SensorReading fusedAngularVelocity = new SensorReading();
  private final SensorReading leftPwm = new SensorReading();
  private final SensorReading rightPwm = new SensorReading();
  private final SensorReading leftWheelCount = new SensorReading();
  private final SensorReading rightWheelCount = new SensorReading();
  private final SensorReading headingAdjustment = new SensorReading();
  private final SensorReading currentHeading = new SensorReading();
  private final SensorReading targetHeading = new SensorReading();

  private UsbConnection usbConnection;
  protected boolean usbConnected;
  private final Context context;
  private final int baudRate;

  private String vehicleType = "";
  private boolean hasIndicators = false;
  private boolean hasSonar = false;
  private boolean hasBumpSensor = false;
  private boolean hasWheelOdometryFront = false;
  private boolean hasWheelOdometryBack = false;
  private boolean hasLedsFront = false;
  private boolean hasLedsBack = false;
  private boolean hasLedsStatus = false;
  private boolean isReady = false;
  private BluetoothManager bluetoothManager;
  SharedPreferences sharedPreferences;
  public String connectionType;



  public boolean isReady() {
    return isReady;
  }

  public void setReady(boolean ready) {
    isReady = ready;
  }



  public boolean isHasIndicators() {
    return hasIndicators;
  }

  public void setHasIndicators(boolean hasIndicators) {
    this.hasIndicators = hasIndicators;
  }

  public boolean isHasSonar() {
    return hasSonar;
  }

  public void setHasSonar(boolean hasSonar) {
    this.hasSonar = hasSonar;
  }

  public boolean isHasBumpSensor() {
    return hasBumpSensor;
  }

  public void setHasBumpSensor(boolean hasBumpSensor) {
    this.hasBumpSensor = hasBumpSensor;
  }

  public boolean isHasWheelOdometryFront() {
    return hasWheelOdometryFront;
  }

  public void setHasWheelOdometryFront(boolean hasWheelOdometryFront) {
    this.hasWheelOdometryFront = hasWheelOdometryFront;
  }

  public boolean isHasWheelOdometryBack() {
    return hasWheelOdometryBack;
  }

  public void setHasWheelOdometryBack(boolean hasWheelOdometryBack) {
    this.hasWheelOdometryBack = hasWheelOdometryBack;
  }

  public boolean isHasLedsFront() {
    return hasLedsFront;
  }

  public void setHasLedsFront(boolean hasLedsFront) {
    this.hasLedsFront = hasLedsFront;
  }

  public boolean isHasLedsBack() {
    return hasLedsBack;
  }

  public void setHasLedsBack(boolean hasLedsBack) {
    this.hasLedsBack = hasLedsBack;
  }

  public boolean isHasLedsStatus() {
    return hasLedsStatus;
  }

  public void setHasLedsStatus(boolean hasLedsStatus) {
    this.hasLedsStatus = hasLedsStatus;
  }

  public String getVehicleType() {
    return vehicleType;
  }

  public void setVehicleType(String vehicleType) {
    this.vehicleType = vehicleType;
  }

  public void requestVehicleConfig() {
    sendStringToDevice(String.format(Locale.US, "f\n"));
  }

  public void processVehicleConfig(String message) {
    setVehicleType(message.split(":")[0]);

    if (message.contains(":i:")) {
      setHasIndicators(true);
    }
    if (message.contains(":s:")) {
      setHasSonar(true);
      setSonarFrequency(100);
    }
    if (message.contains(":b:")) {
      setHasBumpSensor(true);
    }
    if (message.contains(":wf:")) {
      setHasWheelOdometryFront(true);
      setWheelOdometryFrequency(500);
    }
    if (message.contains(":wb:")) {
      setHasWheelOdometryBack(true);
      setWheelOdometryFrequency(500);
    }
    if (message.contains(":lf:")) {
      setHasLedsFront(true);
    }
    if (message.contains(":lb:")) {
      setHasLedsBack(true);
    }
    if (message.contains(":ls:")) {
      setHasLedsStatus(true);
    }
  }
  private final GameController gameController;
  private Timer heartbeatTimer;

  public Vehicle(Context context, int baudRate) {
    this.context = context;
    this.baudRate = baudRate;
    gameController = new GameController();
    sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    connectionType = getConnectionPreferences("connection_type", "USB");

    // Initialize speed multipliers from preferences
    SharedPreferencesManager preferencesManager = new SharedPreferencesManager(context);
    this.speedMultiplier = preferencesManager.getSpeedMultiplier();
    this.angularMultiplier = preferencesManager.getAngularMultiplier();
  }

  public int getBatteryPercentage() {
    return (int) batteryPercentage.getReading();
  }

  public void setBatteryPercentage(float batteryPercentage) {
    this.batteryPercentage.setReading(batteryPercentage);
  }

  public float getLeftWheelRpm() {
    return leftWheelRpm.getReading();
  }

  public void setLeftWheelRpm(float leftWheelRpm) {
    this.leftWheelRpm.setReading(leftWheelRpm);
  }

  public float getRightWheelRpm() {
    return rightWheelRpm.getReading();
  }

  public void setRightWheelRpm(float rightWheelRpm) {
    this.rightWheelRpm.setReading(rightWheelRpm);
  }

  public float getRotation() {
    float linear = getLinearVelocity();
    float angular = getAngularVelocity();

    // Calculate rotation based on angular/linear ratio
    // Scale by a factor to get a similar range as the original method
    float rotation = 0f;
    if (Math.abs(linear) > 0.01f) {
        rotation = angular / linear * 180;
    }

    // Handle edge cases
    if (Float.isNaN(rotation) || Float.isInfinite(rotation)) rotation = 0f;
    return rotation;
  }

  public int getSpeedPercent() {
    // Use absolute linear velocity as a percentage of max speed
    float linearVelocity = Math.abs(getLinearVelocity());
    return (int)(linearVelocity * 100 / speedMultiplier);
  }

  public String getDriveGear() {
    float linear = getLinearVelocity();
    if (linear > 0) return "D";
    if (linear < 0) return "R";
    return "P";
  }

  public float getSonarReading() {
    return sonarReading.getReading();
  }

  public void setSonarReading(float sonarReading) {
    this.sonarReading.setReading(sonarReading);
  }

  public float getWheelEncoderAngularVelocity() {
    return wheelEncoderAngularVelocity.getReading();
  }

  public void setWheelEncoderAngularVelocity(float wheelEncoderAngularVelocity) {
    this.wheelEncoderAngularVelocity.setReading(wheelEncoderAngularVelocity);
  }

  public float getImuAngularVelocity() {
    return imuAngularVelocity.getReading();
  }

  public void setImuAngularVelocity(float imuAngularVelocity) {
    this.imuAngularVelocity.setReading(imuAngularVelocity);
  }

  public float getFusedAngularVelocity() {
    return fusedAngularVelocity.getReading();
  }

  public void setFusedAngularVelocity(float fusedAngularVelocity) {
    this.fusedAngularVelocity.setReading(fusedAngularVelocity);
  }

  public float getLeftPwm() {
    return leftPwm.getReading();
  }

  public void setLeftPwm(float leftPwm) {
    this.leftPwm.setReading(leftPwm);
  }

  public float getRightPwm() {
    return rightPwm.getReading();
  }

  public void setRightPwm(float rightPwm) {
    this.rightPwm.setReading(rightPwm);
  }

  public float getLeftWheelCount() {
    return leftWheelCount.getReading();
  }

  public void setLeftWheelCount(float leftWheelCount) {
    this.leftWheelCount.setReading(leftWheelCount);
  }

  public float getRightWheelCount() {
    return rightWheelCount.getReading();
  }

  public void setRightWheelCount(float rightWheelCount) {
    this.rightWheelCount.setReading(rightWheelCount);
  }

  public float getHeadingAdjustment() {
    return headingAdjustment.getReading();
  }

  public void setHeadingAdjustment(float headingAdjustment) {
    this.headingAdjustment.setReading(headingAdjustment);
  }

  public float getCurrentHeading() {
    return currentHeading.getReading();
  }

  public void setCurrentHeading(float currentHeading) {
    this.currentHeading.setReading(currentHeading);
  }

  public float getTargetHeading() {
    return targetHeading.getReading();
  }

  public void setTargetHeading(float targetHeading) {
    this.targetHeading.setReading(targetHeading);
  }

  public Control getControl() {
    return control;
  }

  public void setControl(Control control) {
    this.control = control;
    sendControl();
  }

  public void setControlVelocity(float linear, float angular) {
    this.control = new Control(linear, angular);
    sendControl();
  }

  public GameController getGameController() {
    return gameController;
  }

  public int getSpeedMultiplier() {
    return speedMultiplier;
  }

  public void setSpeedMultiplier(int speedMultiplier) {
    this.speedMultiplier = speedMultiplier;
  }

  public int getAngularMultiplier() {
    return angularMultiplier;
  }

  public void setAngularMultiplier(int angularMultiplier) {
    this.angularMultiplier = angularMultiplier;
  }

  public int getIndicator() {
    return indicator;
  }

  public void setIndicator(int indicator) {
    this.indicator = indicator;
    switch (indicator) {
      case -1:
        sendStringToDevice(String.format(Locale.US, "i1,0\n"));
        break;
      case 0:
        sendStringToDevice(String.format(Locale.US, "i0,0\n"));
        break;
      case 1:
        sendStringToDevice(String.format(Locale.US, "i0,1\n"));
        break;
    }
  }

  public UsbConnection getUsbConnection() {
    return usbConnection;
  }

  public void connectUsb() {
    if (usbConnection == null) usbConnection = new UsbConnection(context, baudRate);
    usbConnected = usbConnection.startUsbConnection();
    if (usbConnected) {
      if (heartbeatTimer == null) {
        startHeartbeat();
      }
      // Broadcast USB connected event
      LocalBroadcastManager.getInstance(context).sendBroadcast(
          new Intent(Constants.DEVICE_ACTION_USB_CONNECTED));
    }
  }

  public void disconnectUsb() {
    if (usbConnection != null) {
      stopBot();
      stopHeartbeat();
      usbConnection.stopUsbConnection();
      usbConnection = null;
      usbConnected = false;

      // Broadcast USB disconnected event
      LocalBroadcastManager.getInstance(context).sendBroadcast(
          new Intent(Constants.DEVICE_ACTION_USB_DISCONNECTED));
    }
  }

  public boolean isUsbConnected() {
    return usbConnected;
  }

  private void sendStringToDevice(String message) {
    String connectionType = getConnectionType();

    // Debug: Log connection attempts
    Timber.d("Vehicle.sendStringToDevice(): message='%s', connectionType='%s'",
             message.trim(), connectionType);

    if (connectionType.equals("USB") && usbConnection != null) {
      Timber.d("Sending via USB: %s", message.trim());
      usbConnection.send(message);
    } else if (connectionType.equals("Bluetooth")
        && bluetoothManager != null
        && bluetoothManager.isBleConnected()) {
      Timber.d("Sending via Bluetooth: %s", message.trim());
      sendStringToBle(message);
    } else {
      Timber.w("Cannot send message - no valid connection. USB: %s, BT: %s",
               (usbConnection != null),
               (bluetoothManager != null && bluetoothManager.isBleConnected()));
    }
  }

  public float getLinearVelocity() {
    return control.linear() * speedMultiplier;
  }

  public float getAngularVelocity() {
    return control.angular() * angularMultiplier;
  }

  public void sendLightIntensity(float frontPercent, float backPercent) {
    int front = (int) (frontPercent * 255.f);
    int back = (int) (backPercent * 255.f);
    sendStringToDevice(String.format(Locale.US, "l%d,%d\n", front, back));
  }

  public void sendControl() {
    // Send linear and angular velocity instead of left/right wheel speeds
    int linear = (int) (getLinearVelocity());
    int angular = (int) (getAngularVelocity());
    String command = String.format(Locale.US, "c%d,%d\n", linear, angular);

    // Debug: Log the command being sent
    Timber.d("Vehicle.sendControl(): sending command '%s' (linear=%.2f, angular=%.2f)",
             command.trim(), getLinearVelocity(), getAngularVelocity());

    sendStringToDevice(command);
  }

  protected void sendHeartbeat(int timeout_ms) {
    sendStringToDevice(String.format(Locale.getDefault(), "h%d\n", timeout_ms));
  }

  protected void setSonarFrequency(int interval_ms) {
    sendStringToDevice(String.format(Locale.getDefault(), "s%d\n", interval_ms));
  }



  protected void setWheelOdometryFrequency(int interval_ms) {
    sendStringToDevice(String.format(Locale.getDefault(), "w%d\n", interval_ms));
  }

  public void sendTuningParameters(float kp, float kd, float noControlScale,
                                   float normalControlScale, float rotationScale,
                                   float velocityBias, float rotationBias) {
    sendStringToDevice(String.format(Locale.US, "m%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f\n",
        kp, kd, noControlScale, normalControlScale, rotationScale, velocityBias, rotationBias));
  }

  public void requestTuningParameters() {
    sendStringToDevice(String.format(Locale.US, "m\n"));
  }

  private class HeartBeatTask extends TimerTask {

    @Override
    public void run() {
      sendHeartbeat(750);
    }
  }

  public void startHeartbeat() {
    heartbeatTimer = new Timer();
    HeartBeatTask heartBeatTask = new HeartBeatTask();
    heartbeatTimer.schedule(heartBeatTask, 250, 250); // 250ms delay and 250ms intervals
  }

  public void stopHeartbeat() {
    if (heartbeatTimer != null) {
      heartbeatTimer.cancel();
      heartbeatTimer.purge();
      heartbeatTimer = null;
    }
  }

  public void stopBot() {
    Control control = new Control(0, 0);
    setControl(control);
  }

  public ScanDeviceAdapter getBleAdapter() {
    return bluetoothManager.adapter;
  }

  public void setBleAdapter(
      ScanDeviceAdapter adapter,
      @NonNull CommonRecyclerViewAdapter.OnItemClickListener onItemClickListener) {
    bluetoothManager.adapter = adapter;
    bluetoothManager.adapter.setOnItemClickListener(onItemClickListener);
  }

  public void startScan() {
    bluetoothManager.startScan();
  }

  public void stopScan() {
    bluetoothManager.stopScan();
  }

  public List<BleDevice> getDeviceList() {
    return bluetoothManager.deviceList;
  }

  public void setBleDevice(BleDevice device) {
    bluetoothManager.bleDevice = device;
  }

  public BleDevice getBleDevice() {
    return bluetoothManager.bleDevice;
  }

  public void toggleConnection(int position, BleDevice device) {
    bluetoothManager.toggleConnection(position, device);
  }

  public void initBle() {
    bluetoothManager = new BluetoothManager(context);
  }

  private void sendStringToBle(String message) {
    bluetoothManager.write(message);
  }

  public boolean bleConnected() {
    return bluetoothManager.isBleConnected();
  }

  private void setConnectionPreferences(String name, String value) {
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putString(name, value);
    editor.apply();
  }

  private String getConnectionPreferences(String name, String defaultValue) {
    try {
      if (sharedPreferences != null) {
        return sharedPreferences.getString(name, defaultValue);
      } else return defaultValue;
    } catch (ClassCastException e) {
      return defaultValue;
    }
  }

  public String getConnectionType() {
    return getConnectionPreferences("connection_type", "USB");
  }

  /**
   * Emergency stop: immediately stop the vehicle and send 's1' or 's0' as required.
   * @param engaged true to engage emergency stop ('s1'), false to release ('s0')
   */
  public void emergencyStop(boolean engaged) {
      if (engaged) {
          sendStringToDevice("s1\n");
      } else {
          sendStringToDevice("s0\n");
      }
  }
}
