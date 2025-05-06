package com.satinavrobotics.satibot.utils;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Handles tilt steering for the robot in landscape mode.
 * Uses the device's rotation around the Y-axis (pitch) for steering.
 */
public class TiltSteeringHandler implements SensorEventListener {
    private static final float STEERING_SENSITIVITY = 1.5f; // Adjust sensitivity as needed
    private static final float MAX_STEERING_ANGLE = 25.0f; // Maximum angle for full steering
    
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float currentSteeringValue = 0.0f; // Range: -1.0 to 1.0
    private SteeringListener steeringListener;
    
    public interface SteeringListener {
        void onSteeringValueChanged(float steeringValue);
    }
    
    public TiltSteeringHandler(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }
    
    public void start() {
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }
    
    public void stop() {
        sensorManager.unregisterListener(this);
    }
    
    public void setSteeringListener(SteeringListener listener) {
        this.steeringListener = listener;
    }
    
    public float getCurrentSteeringValue() {
        return currentSteeringValue;
    }
    
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            // Convert rotation vector to rotation matrix
            float[] rotationMatrix = new float[9];
            float[] orientationAngles = new float[3];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationAngles);
            
            // In landscape mode, we're primarily interested in the pitch (rotation around Y-axis)
            // which corresponds to orientationAngles[1]
            float pitchRadians = orientationAngles[1];
            float pitchDegrees = (float) Math.toDegrees(pitchRadians);
            
            // Calculate steering value (-1 to 1) based on tilt angle
            // Positive values turn right, negative values turn left
            float steeringValue = (pitchDegrees / MAX_STEERING_ANGLE) * STEERING_SENSITIVITY;
            
            // Clamp to valid range
            steeringValue = Math.max(-1.0f, Math.min(1.0f, steeringValue));
            
            // Update current steering value
            currentSteeringValue = steeringValue;
            
            // Notify listener
            if (steeringListener != null) {
                steeringListener.onSteeringValueChanged(steeringValue);
            }
        }
    }
    
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed for this implementation
    }
}
