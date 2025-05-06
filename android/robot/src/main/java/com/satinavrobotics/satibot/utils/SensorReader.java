package com.satinavrobotics.satibot.utils;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class SensorReader implements SensorEventListener {
    private static SensorReader instance;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private float[] accelerometerReading = new float[3];
    private float[] magnetometerReading = new float[3];
    private float[] rotationMatrix = new float[9];
    private float[] orientationAngles = new float[3];
    private SensorValues values = new SensorValues();

    private SensorReader() {
        // Private constructor to enforce singleton pattern
    }

    public static synchronized SensorReader getInstance() {
        if (instance == null) {
            instance = new SensorReader();
        }
        return instance;
    }

    public void init(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    public void start() {
        if (sensorManager != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    public void stop(Context context) {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
        }

        updateOrientationAngles();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed for this implementation
    }

    private void updateOrientationAngles() {
        // Update rotation matrix, which is needed to update orientation angles
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        // Convert radians to degrees
        values.azimuth = (float) Math.toDegrees(orientationAngles[0]);
        values.pitch = (float) Math.toDegrees(orientationAngles[1]);
        values.roll = (float) Math.toDegrees(orientationAngles[2]);
    }

    public SensorValues getValues() {
        return values;
    }

    public static class SensorValues {
        public float azimuth = 0f; // Z-axis rotation (around the z-axis)
        public float pitch = 0f;   // X-axis rotation (around the x-axis)
        public float roll = 0f;    // Y-axis rotation (around the y-axis)
    }
}
