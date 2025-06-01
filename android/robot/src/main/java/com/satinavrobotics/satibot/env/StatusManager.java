package com.satinavrobotics.satibot.env;

import com.satinavrobotics.satibot.utils.ConnectionUtils;

import org.json.JSONException;
import org.json.JSONObject;

import timber.log.Timber;

public class StatusManager {
    private static StatusManager instance;
    private JSONObject status;
    private JSONObject lastLocation;
    private JSONObject lastARPose;
    private JSONObject nextGoalInfo;

    private StatusManager() {
        status = new JSONObject();
        lastLocation = new JSONObject();
    }

    public static synchronized StatusManager getInstance() {
        if (instance == null) {
            instance = new StatusManager();
            instance.updateStatus(ConnectionUtils.createStatus("LOGS", false));
        }
        return instance;
    }

    public synchronized void updateStatus(JSONObject newStatus) {
        this.status = newStatus;
    }

    public synchronized void updateLocation(JSONObject location) {
        this.lastLocation = location;
    }

    public synchronized void updateARCorePose(JSONObject arCorePose) {this.lastARPose = arCorePose;}

    public synchronized void updateNextGoalInfo(JSONObject goalInfo) {
        this.nextGoalInfo = goalInfo;
    }

    public synchronized JSONObject getStatus() {
        try {
            JSONObject combinedStatus = new JSONObject(status.toString());
            if (lastLocation != null) {
                combinedStatus.put("location", lastLocation);
            }
            if (lastARPose != null) {
                combinedStatus.put("pose", lastARPose);
            }
            if (nextGoalInfo != null) {
                combinedStatus.put("nextGoal", nextGoalInfo);
            }
            Timber.d(combinedStatus.toString());
            return combinedStatus;
        } catch (JSONException e) {
            e.printStackTrace();
            return new JSONObject();
        }
    }
}
