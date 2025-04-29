package org.openbot.env;

import org.json.JSONException;
import org.json.JSONObject;

import timber.log.Timber;

public class StatusManager {
    private static StatusManager instance;
    private JSONObject status;
    private JSONObject lastLocation;

    private StatusManager() {
        status = new JSONObject();
        lastLocation = new JSONObject();
    }

    public static synchronized StatusManager getInstance() {
        if (instance == null) {
            instance = new StatusManager();
        }
        return instance;
    }

    public synchronized void updateStatus(JSONObject newStatus) {
        this.status = newStatus;
    }

    public synchronized void updateLocation(JSONObject location) {
        this.lastLocation = location;
    }

    public synchronized JSONObject getStatus() {
        try {
            JSONObject combinedStatus = new JSONObject(status.toString());
            if (lastLocation != null) {
                combinedStatus.put("location", lastLocation);
            }
            Timber.d(combinedStatus.toString());
            return combinedStatus;
        } catch (JSONException e) {
            e.printStackTrace();
            return new JSONObject();
        }
    }
}
