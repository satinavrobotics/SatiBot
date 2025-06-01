package com.satinavrobotics.satibot.mapManagement;

import com.google.ar.core.Anchor;
import com.google.ar.core.Pose;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a waypoint in 3D space with connections to other waypoints.
 * Waypoints are stored relative to resolved anchors for persistence.
 */
public class Waypoint {
    private final String id;
    private final Pose pose;
    private final List<String> connectedWaypointIds;
    private final String referenceAnchorId;
    private final Pose relativeToAnchorPose;
    private Anchor anchor;

    /**
     * Creates a new waypoint at the specified pose with a given ID.
     *
     * @param id The ID to use for this waypoint
     * @param pose The world pose of the waypoint
     * @param referenceAnchor The anchor this waypoint is relative to
     * @param referenceAnchorId The cloud ID of the reference anchor
     */
    public Waypoint(String id, Pose pose, Anchor referenceAnchor, String referenceAnchorId) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.pose = pose;
        this.connectedWaypointIds = new ArrayList<>();
        this.referenceAnchorId = referenceAnchorId;

        // Calculate the relative pose from the reference anchor
        if (referenceAnchor != null) {
            Pose anchorPose = referenceAnchor.getPose();
            this.relativeToAnchorPose = anchorPose.inverse().compose(pose);
        } else {
            this.relativeToAnchorPose = pose;
        }
    }

    /**
     * Creates a new waypoint at the specified pose with a random ID.
     *
     * @param pose The world pose of the waypoint
     * @param referenceAnchor The anchor this waypoint is relative to
     * @param referenceAnchorId The cloud ID of the reference anchor
     */
    public Waypoint(Pose pose, Anchor referenceAnchor, String referenceAnchorId) {
        this(null, pose, referenceAnchor, referenceAnchorId);
    }

    /**
     * Gets the unique ID of this waypoint.
     *
     * @return The waypoint ID
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the world pose of this waypoint.
     *
     * @return The waypoint pose
     */
    public Pose getPose() {
        return pose;
    }

    /**
     * Gets the IDs of waypoints connected to this one.
     *
     * @return List of connected waypoint IDs
     */
    public List<String> getConnectedWaypointIds() {
        return connectedWaypointIds;
    }

    /**
     * Adds a connection to another waypoint.
     *
     * @param waypointId The ID of the waypoint to connect to
     * @return true if the connection was added, false if it already existed
     */
    public boolean addConnection(String waypointId) {
        if (!connectedWaypointIds.contains(waypointId)) {
            connectedWaypointIds.add(waypointId);
            return true;
        }
        return false;
    }

    /**
     * Removes a connection to another waypoint.
     *
     * @param waypointId The ID of the waypoint to disconnect from
     * @return true if the connection was removed, false if it didn't exist
     */
    public boolean removeConnection(String waypointId) {
        return connectedWaypointIds.remove(waypointId);
    }

    /**
     * Gets the ID of the reference anchor this waypoint is relative to.
     *
     * @return The reference anchor ID
     */
    public String getReferenceAnchorId() {
        return referenceAnchorId;
    }

    /**
     * Gets the pose of this waypoint relative to its reference anchor.
     *
     * @return The relative pose
     */
    public Pose getRelativeToAnchorPose() {
        return relativeToAnchorPose;
    }

    /**
     * Sets the anchor for this waypoint.
     *
     * @param anchor The AR anchor for this waypoint
     */
    public void setAnchor(Anchor anchor) {
        this.anchor = anchor;
    }

    /**
     * Gets the anchor for this waypoint.
     *
     * @return The AR anchor for this waypoint
     */
    public Anchor getAnchor() {
        return anchor;
    }

    /**
     * Calculates the local coordinates of this waypoint relative to an origin pose.
     * This is used to store the waypoint's position in the map's local coordinate system.
     *
     * @param originPose The origin pose (usually the first anchor's pose)
     * @return A float array containing the local [x, y, z] coordinates
     */
    public float[] calculateLocalCoordinates(Pose originPose) {
        if (originPose == null) {
            return new float[] {0, 0, 0};
        }

        // Calculate the relative pose (transform from origin to waypoint)
        Pose relativePose = originPose.inverse().compose(pose);

        // Extract the translation component
        float[] translation = new float[3];
        relativePose.getTranslation(translation, 0);

        return translation;
    }
}
