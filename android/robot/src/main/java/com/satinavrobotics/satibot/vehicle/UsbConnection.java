// Created by Matthias Mueller - Intel Intelligent Systems Lab - 2020

package com.satinavrobotics.satibot.vehicle;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.Toast;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import java.io.UnsupportedEncodingException;
import java.util.Map;

import com.satinavrobotics.satibot.utils.Constants;

import timber.log.Timber;

public class UsbConnection {
  private static final int USB_VENDOR_ID = 6790; // 0x2341; // 9025
  private static final int USB_PRODUCT_ID = 29987; // 0x0001;

  private final UsbManager usbManager;
  // private UsbDevice usbDevice;
  PendingIntent usbPermissionIntent;
  public static final String ACTION_USB_PERMISSION = "UsbConnection.USB_PERMISSION";

  private UsbDeviceConnection connection;
  private UsbSerialDevice serialDevice;
  private final LocalBroadcastManager localBroadcastManager;
  private String buffer = "";
  private final Context context;
  private final int baudRate;
  private boolean busy;
  private int vendorId;
  private int productId;
  private String productName;
  private String deviceName;
  private String manufacturerName;

  public UsbConnection(Context context, int baudRate) {
    this.context = context;
    this.baudRate = baudRate;
    localBroadcastManager = LocalBroadcastManager.getInstance(this.context);
    usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      usbPermissionIntent =
          PendingIntent.getBroadcast(
              this.context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
    } else {
      usbPermissionIntent =
          PendingIntent.getBroadcast(this.context, 0, new Intent(ACTION_USB_PERMISSION), 0);
    }
  }

  private final UsbSerialInterface.UsbReadCallback callback =
      data -> {
        try {
          String dataUtf8 = new String(data, "UTF-8");
          buffer += dataUtf8;
          int index;
          while ((index = buffer.indexOf('\n')) != -1) {
            final String dataStr = buffer.substring(0, index).trim();
            buffer = buffer.length() == index ? "" : buffer.substring(index + 1);

            AsyncTask.execute(() -> onSerialDataReceived(dataStr));
          }
        } catch (UnsupportedEncodingException e) {
          Timber.e("Error receiving USB data");
        }
      };

  private final BroadcastReceiver usbReceiver =
      new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
          String action = intent.getAction();
          if (ACTION_USB_PERMISSION.equals(action)) {
            synchronized (this) {
              UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
              if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                if (usbDevice != null) {
                  // call method to set up device communication
                  startSerialConnection(usbDevice);
                }
              } else {
                Timber.d("Permission denied for device " + usbDevice);
                Toast.makeText(
                        UsbConnection.this.context,
                        "USB Host permission is required!",
                        Toast.LENGTH_LONG)
                    .show();
              }
            }
          } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            Timber.i("USB device detached");
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
              stopUsbConnection();
            }
          }
        }
      };

  public boolean startUsbConnection() {
    IntentFilter localIntentFilter = new IntentFilter();
    localIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    localIntentFilter.addAction(ACTION_USB_PERMISSION);
    localBroadcastManager.registerReceiver(usbReceiver, localIntentFilter);

    // Use RECEIVER_NOT_EXPORTED flag for Android 14+ (SDK 34)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.registerReceiver(usbReceiver, localIntentFilter, Context.RECEIVER_NOT_EXPORTED);
    } else {
        context.registerReceiver(usbReceiver, localIntentFilter);
    }

    Map<String, UsbDevice> connectedDevices = usbManager.getDeviceList();
    if (!connectedDevices.isEmpty()) {
      for (UsbDevice usbDevice : connectedDevices.values()) {
        // if (usbDevice.getVendorId() == USB_VENDOR_ID && usbDevice.getProductId() ==
        // USB_PRODUCT_ID) {
        Timber.i("Device found: " + usbDevice.getDeviceName());
        if (usbManager.hasPermission(usbDevice)) {
          return startSerialConnection(usbDevice);
        } else {
          usbManager.requestPermission(usbDevice, usbPermissionIntent);
          Toast.makeText(context, "Please allow USB Host connection.", Toast.LENGTH_SHORT).show();
          return false;
        }
        // }
      }
    }
    Timber.w("Could not start USB connection - No devices found");
    return false;
  }

  private boolean startSerialConnection(UsbDevice device) {
    Timber.i("Ready to open USB device connection");
    connection = usbManager.openDevice(device);
    serialDevice = UsbSerialDevice.createUsbSerialDevice(device, connection);
    boolean success = false;
    if (serialDevice != null) {
      if (serialDevice.open()) {
        vendorId = device.getVendorId();
        productId = device.getProductId();
        productName = device.getProductName();
        deviceName = device.getDeviceName();
        manufacturerName = device.getManufacturerName();
        serialDevice.setBaudRate(baudRate);
        serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
        serialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
        serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
        serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
        serialDevice.read(callback);
        Timber.i("Serial connection opened");
        success = true;
      } else {
        Timber.w("Cannot open serial connection");
      }
    } else {
      Timber.w("Could not create Usb Serial Device");
    }
    return success;
  }

  private void onSerialDataReceived(String data) {
    // Add whatever you want here
    Timber.i("Serial data received from USB: " + data);
    localBroadcastManager.sendBroadcast(
        new Intent(Constants.DEVICE_ACTION_DATA_RECEIVED)
            .putExtra("from", "usb")
            .putExtra("data", data));
  }

  public void stopUsbConnection() {
    try {
      if (serialDevice != null) {
        serialDevice.close();
      }

      if (connection != null) {
        connection.close();
      }
    } finally {
      serialDevice = null;
      connection = null;
    }
    localBroadcastManager.unregisterReceiver(usbReceiver);
    try {

      // Register or UnRegister your broadcast receiver here
      context.unregisterReceiver(usbReceiver);
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
    }
  }

  public void send(String msg) {
    if (isOpen() && !isBusy()) {
      busy = true;
      Timber.d("USB sending: %s", msg.trim());
      serialDevice.write(msg.getBytes(UTF_8));
      busy = false;
      Timber.d("USB sent successfully: %s", msg.trim());
    } else {
      Timber.w("USB busy or not open, could not send: %s (open=%s, busy=%s)",
               msg.trim(), isOpen(), isBusy());
    }
  }

  public boolean isOpen() {
    return connection != null;
  }

  public boolean isBusy() {
    return busy;
  }

  public int getBaudRate() {
    return baudRate;
  }

  public int getVendorId() {
    return vendorId;
  }

  public int getProductId() {
    return productId;
  }

  public String getProductName() {
    return productName;
  }

  public String getDeviceName() {
    return deviceName;
  }

  public String getManufacturerName() {
    return manufacturerName;
  }
}
