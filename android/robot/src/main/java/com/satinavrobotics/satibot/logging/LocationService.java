// Created by Matthias Mueller - Intel Intelligent Systems Lab - 2020

package com.satinavrobotics.satibot.logging;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.RequiresApi;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.json.JSONException;
import org.json.JSONObject;

import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.env.StatusManager;
import com.satinavrobotics.satibot.utils.Enums;

import timber.log.Timber;

public class LocationService extends Service implements SensorEventListener {

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public LocationService getService() {
            return LocationService.this;
        }
    }
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    public Location getLastLocation() {
        try {
            return fusedLocationClient.getLastLocation().getResult();
        } catch (SecurityException e) {
            throw new SecurityException("No permission to use location.", e);
        }
    }


    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private Sensor lightSensor;
    private Sensor temperatureSensor;

    private boolean trackingLocation = false;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private StatusManager statusManager;


    private SharedPreferencesManager preferencesManager;

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public final void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        temperatureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
        // Initialize the FusedLocationClient.
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        preferencesManager = new SharedPreferencesManager(this);
        statusManager = StatusManager.getInstance();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {



        int delay = (int) (preferencesManager.getDelay() * 1000);
        if (preferencesManager.getSensorStatus(Enums.SensorType.ACCELEROMETER.getSensor())
                && accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor, delay);
        }
        if (preferencesManager.getSensorStatus(Enums.SensorType.LIGHT.getSensor())
                && lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, delay);
        }
        if (preferencesManager.getSensorStatus(Enums.SensorType.TEMPERATURE.getSensor())
                && temperatureSensor != null) {
            sensorManager.registerListener(this, temperatureSensor, delay);
        }

        locationCallback =
                new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult locationResult) {
                        Location location = locationResult.getLastLocation();
                        if (location != null) {
                            // Note: added this line, might make processing slower
                            JSONObject loc;
                            try {
                                loc = new JSONObject();
                                loc.put("latitude", location.getLatitude());
                                loc.put("longitude", location.getLongitude());
                                loc.put("altitude", location.getAltitude());
                                loc.put("bearing", location.getBearing());
                                loc.put("speed", location.getSpeed());
                            } catch (JSONException e) {
                                throw new RuntimeException(e);
                            }
                            statusManager.updateLocation(loc);
                        }
                    }

                };
        startTrackingLocation();

        Timber.d("Started sensor stream.");

        return START_REDELIVER_INTENT;
    }


    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do something here if sensor accuracy changes.
    }

    @Override
    public final void onSensorChanged(SensorEvent event) {
        // The light sensor returns a single value.
        // Many sensors return 3 values, one for each axis.
        String sensorName = event.sensor.getName();
        JSONObject data = new JSONObject();
        JSONObject message = new JSONObject();
        JSONObject status = new JSONObject();
        // Do something with this sensor value.
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                // Acceleration including gravity along the X, Y and Z axis
                // Units are m/s^2
                try {
                    data.put("sensor", "accelerometer");
                    data.put("x", event.values[0]);
                    data.put("y", event.values[1]);
                    data.put("z", event.values[2]);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                break;

            case Sensor.TYPE_LIGHT:
                // Ambient light level in SI lux units
                try {
                    data.put("sensor", "light");
                    data.put("value", event.values[0]);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                break;
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
                // Ambient temperature in degrees
                try {
                    data.put("sensor", "temperature");
                    data.put("value", event.values[0]);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                break;
            default:
                // Unknown sensor
                break;
        }
        try {
            data.put("timestamp", event.timestamp);
            message.put("SENSOR", data);
            status.put("status", message);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        //statusManager.updateStatus(status);
    }


    @Override
    public void onDestroy() {
        sensorManager.unregisterListener(this);
        stopTrackingLocation();
    }

    //@Nullable
    //@Override
    //public IBinder onBind(Intent intent) {
    //    return null;
    //}


    private void startTrackingLocation() {
        try {
            fusedLocationClient.requestLocationUpdates(
                    getLocationRequest(), locationCallback, null /* Looper */);
            trackingLocation = true;
        } catch (SecurityException e) {
            trackingLocation = false;
            throw new SecurityException("No permission to use location.", e);
        }
    }

    /**
     * Method that stops tracking the device. It removes the location updates, stops the animation and
     * reset the UI.
     */
    private void stopTrackingLocation() {
        if (trackingLocation) {
            trackingLocation = false;
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
    }

    private LocationRequest getLocationRequest() {
        LocationRequest locationRequest = new LocationRequest();
        locationRequest.setInterval(1000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        return locationRequest;
    }
}


