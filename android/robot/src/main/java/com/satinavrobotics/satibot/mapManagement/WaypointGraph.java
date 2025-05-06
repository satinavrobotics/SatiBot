package com.satinavrobotics.satibot.mapManagement;

import com.google.ar.core.Anchor;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

/**
 * Manages a collection of waypoints and their connections.
 * This class handles the creation, connection, and retrieval of waypoints.
 */
public class WaypointGraph {
    private final Map<String, Waypoint> waypoints = new HashMap<>();
    private String selectedWaypointId = null;
    private final Object waypointLock = new Object();

    /**
     * Creates a new waypoint at the specified pose.
     *
     * @param pose The world pose of the waypoint
     * @param referenceAnchor The anchor this waypoint is relative to
     * @param referenceAnchorId The cloud ID of the reference anchor
     * @return The created waypoint
     */
    public Waypoint createWaypoint(Pose pose, Anchor referenceAnchor, String referenceAnchorId) {
        synchronized (waypointLock) {
            Waypoint waypoint = new Waypoint(pose, referenceAnchor, referenceAnchorId);
            waypoints.put(waypoint.getId(), waypoint);
            Timber.d("Created waypoint %s at pose %s", waypoint.getId(), pose.toString());
            return waypoint;
        }
    }

    /**
     * Creates a connection between two waypoints.
     *
     * @param waypointId1 The ID of the first waypoint
     * @param waypointId2 The ID of the second waypoint
     * @return true if the connection was created, false otherwise
     */
    public boolean connectWaypoints(String waypointId1, String waypointId2) {
        synchronized (waypointLock) {
            Waypoint waypoint1 = waypoints.get(waypointId1);
            Waypoint waypoint2 = waypoints.get(waypointId2);

            if (waypoint1 == null || waypoint2 == null) {
                Timber.e("Cannot connect waypoints: one or both waypoints not found");
                return false;
            }

            // Add bidirectional connection
            boolean added1 = waypoint1.addConnection(waypointId2);
            boolean added2 = waypoint2.addConnection(waypointId1);

            Timber.d("Connected waypoints %s and %s", waypointId1, waypointId2);
            return added1 || added2;
        }
    }

    /**
     * Adds a waypoint to the graph.
     *
     * @param waypoint The waypoint to add
     */
    public void addWaypoint(Waypoint waypoint) {
        synchronized (waypointLock) {
            waypoints.put(waypoint.getId(), waypoint);
        }
    }

    /**
     * Gets all waypoints in the graph.
     *
     * @return List of all waypoints
     */
    public List<Waypoint> getAllWaypoints() {
        synchronized (waypointLock) {
            return new ArrayList<>(waypoints.values());
        }
    }

    /**
     * Gets a waypoint by its ID.
     *
     * @param waypointId The ID of the waypoint to get
     * @return The waypoint, or null if not found
     */
    public Waypoint getWaypoint(String waypointId) {
        synchronized (waypointLock) {
            return waypoints.get(waypointId);
        }
    }

    /**
     * Gets the number of waypoints in the graph.
     *
     * @return The number of waypoints
     */
    public int getWaypointCount() {
        synchronized (waypointLock) {
            return waypoints.size();
        }
    }

    /**
     * Finds the closest waypoint to the given pose within the specified distance.
     *
     * @param pose The pose to find the closest waypoint to
     * @param maxDistance The maximum distance to consider
     * @return The closest waypoint, or null if none are within the specified distance
     */
    public Waypoint findClosestWaypoint(Pose pose, float maxDistance) {
        synchronized (waypointLock) {
            Waypoint closest = null;
            float minDistance = maxDistance;

            for (Waypoint waypoint : waypoints.values()) {
                float distance = calculateDistance(pose, waypoint.getPose());
                if (distance < minDistance) {
                    minDistance = distance;
                    closest = waypoint;
                }
            }

            return closest;
        }
    }

    /**
     * Sets the currently selected waypoint.
     *
     * @param waypointId The ID of the waypoint to select, or null to clear selection
     */
    public void setSelectedWaypoint(String waypointId) {
        synchronized (waypointLock) {
            this.selectedWaypointId = waypointId;
        }
    }

    /**
     * Gets the currently selected waypoint.
     *
     * @return The selected waypoint, or null if none is selected
     */
    public Waypoint getSelectedWaypoint() {
        synchronized (waypointLock) {
            return waypoints.get(selectedWaypointId);
        }
    }

    /**
     * Gets the ID of the currently selected waypoint.
     *
     * @return The ID of the selected waypoint, or null if none is selected
     */
    public String getSelectedWaypointId() {
        synchronized (waypointLock) {
            return selectedWaypointId;
        }
    }

    /**
     * Clears all waypoints and connections.
     */
    public void clear() {
        synchronized (waypointLock) {
            waypoints.clear();
            selectedWaypointId = null;
        }
    }

    /**
     * Creates AR anchors for all waypoints using the provided session.
     *
     * @param session The AR session to create anchors with
     */
    public void createAnchorsForWaypoints(Session session) {
        synchronized (waypointLock) {
            for (Waypoint waypoint : waypoints.values()) {
                if (waypoint.getAnchor() == null) {
                    Anchor anchor = session.createAnchor(waypoint.getPose());
                    waypoint.setAnchor(anchor);
                }
            }
        }
    }

    /**
     * Calculates the distance between two poses.
     *
     * @param pose1 The first pose
     * @param pose2 The second pose
     * @return The distance between the poses
     */
    private float calculateDistance(Pose pose1, Pose pose2) {
        float dx = pose1.tx() - pose2.tx();
        float dy = pose1.ty() - pose2.ty();
        float dz = pose1.tz() - pose2.tz();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
