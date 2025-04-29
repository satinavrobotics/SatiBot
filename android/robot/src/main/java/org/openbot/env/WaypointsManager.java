package org.openbot.env;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.LinkedList;
import java.util.Queue;

import timber.log.Timber;

public class WaypointsManager {
    private static WaypointsManager instance;
    private Queue<JSONObject> waypointQueue;

    private WaypointsManager() {
        waypointQueue = new LinkedList<>();
    }

    public static synchronized WaypointsManager getInstance() {
        if (instance == null) {
            instance = new WaypointsManager();
        }
        return instance;
    }

    public synchronized void setWaypoints(JSONArray waypoints) {
        waypointQueue.clear();
        for (int i = 0; i < waypoints.length(); i++) {
            try {
                waypointQueue.add(waypoints.getJSONObject(i));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public synchronized JSONObject getNextWaypoint() {
        return waypointQueue.poll();
    }

    public synchronized boolean hasNextWaypoint() {
        return !waypointQueue.isEmpty();
    }

    public synchronized JSONObject getNextWaypointInLocalCoordinates() {
        JSONObject globalWaypoint = getNextWaypoint();
        if (globalWaypoint == null) return null;

        JSONObject currentLocation = StatusManager.getInstance().getStatus().optJSONObject("location");
        if (currentLocation == null) return globalWaypoint;

        try {
            double lat1 = currentLocation.getDouble("latitude");
            double lon1 = currentLocation.getDouble("longitude");
            double lat2 = globalWaypoint.getDouble("lat");
            double lon2 = globalWaypoint.getDouble("lng");
            double bearing = currentLocation.getDouble("bearing"); // Current heading in degrees

            JSONObject localCoords = convertToLocalCoordinates(lat1, lon1, lat2, lon2, bearing);
            return localCoords;
        } catch (JSONException e) {
            e.printStackTrace();
            return globalWaypoint;
        }
    }

    private JSONObject convertToLocalCoordinates(double lat1, double lon1, double lat2, double lon2, double bearing) {
        final double EARTH_RADIUS_METERS = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        // Compute the displacement in local East-North coordinates
        double east = dLon * Math.cos(Math.toRadians((lat1 + lat2) / 2)) * EARTH_RADIUS_METERS;
        double north = dLat * EARTH_RADIUS_METERS;

        // Convert from East-North to Left-Forward using bearing
        double theta = Math.toRadians(bearing);
        double x = -east * Math.sin(theta) + north * Math.cos(theta); // Left direction
        double z = east * Math.cos(theta) + north * Math.sin(theta);  // Forward direction

        JSONObject localCoords = new JSONObject();
        try {
            localCoords.put("x", x); // Left
            localCoords.put("z", z); // Forward
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return localCoords;
    }
}

