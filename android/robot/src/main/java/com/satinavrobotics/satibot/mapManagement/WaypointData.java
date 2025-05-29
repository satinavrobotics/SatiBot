package com.satinavrobotics.satibot.mapManagement;

import com.google.ar.core.Pose;

import java.util.ArrayList;
import java.util.List;

/**
 * Data class for storing waypoint information in Firebase.
 * This class contains all the necessary information to recreate a waypoint
 * when anchors are resolved.
 */
public class WaypointData {
    private String id;
    private String referenceAnchorId;
    private List<Float> relativeTranslation;
    private List<Float> relativeRotation;
    private List<String> connectedWaypointIds;
    private long createdAt;

    // Local coordinates relative to the first anchor in the map
    private List<Float> localTranslation;

    // Empty constructor required for Firestore
    public WaypointData() {
        this.connectedWaypointIds = new ArrayList<>();
        this.relativeTranslation = new ArrayList<>();
        this.relativeRotation = new ArrayList<>();
        this.localTranslation = new ArrayList<>();
    }

    /**
     * Creates a new waypoint data object from a Waypoint.
     *
     * @param waypoint The waypoint to convert
     * @param originPose The origin pose for local coordinate calculation (can be null)
     */
    public WaypointData(Waypoint waypoint, Pose originPose) {
        this.id = waypoint.getId();
        this.referenceAnchorId = waypoint.getReferenceAnchorId();
        this.connectedWaypointIds = new ArrayList<>(waypoint.getConnectedWaypointIds());
        this.createdAt = System.currentTimeMillis();

        // Extract translation and rotation from the relative pose
        Pose relativePose = waypoint.getRelativeToAnchorPose();
        float[] translationArray = new float[3];
        relativePose.getTranslation(translationArray, 0);

        // Convert arrays to Lists
        this.relativeTranslation = new ArrayList<>();
        for (float value : translationArray) {
            this.relativeTranslation.add(value);
        }

        float[] rotationArray = new float[4];
        relativePose.getRotationQuaternion(rotationArray, 0);

        this.relativeRotation = new ArrayList<>();
        for (float value : rotationArray) {
            this.relativeRotation.add(value);
        }

        // Calculate and store local coordinates relative to the origin
        float[] localCoords = waypoint.calculateLocalCoordinates(originPose);
        this.localTranslation = new ArrayList<>();
        for (float value : localCoords) {
            this.localTranslation.add(value);
        }
    }

    /**
     * Creates a new waypoint data object from a Waypoint without local coordinates.
     * This constructor is provided for backward compatibility.
     *
     * @param waypoint The waypoint to convert
     */
    public WaypointData(Waypoint waypoint) {
        this(waypoint, null);
    }

    /**
     * Creates a Pose from the stored relative translation and rotation.
     *
     * @return The relative pose
     */
    public Pose createRelativePose() {
        // Convert Lists back to arrays for Pose creation
        float[] translationArray = new float[3];
        for (int i = 0; i < 3; i++) {
            translationArray[i] = relativeTranslation.get(i);
        }

        float[] rotationArray = new float[4];
        for (int i = 0; i < 4; i++) {
            rotationArray[i] = relativeRotation.get(i);
        }

        return new Pose(translationArray, rotationArray);
    }

    // Getters and setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getReferenceAnchorId() {
        return referenceAnchorId;
    }

    public void setReferenceAnchorId(String referenceAnchorId) {
        this.referenceAnchorId = referenceAnchorId;
    }

    public List<Float> getRelativeTranslation() {
        return relativeTranslation;
    }

    public void setRelativeTranslation(List<Float> relativeTranslation) {
        this.relativeTranslation = relativeTranslation;
    }

    public List<Float> getRelativeRotation() {
        return relativeRotation;
    }

    public void setRelativeRotation(List<Float> relativeRotation) {
        this.relativeRotation = relativeRotation;
    }

    public List<String> getConnectedWaypointIds() {
        return connectedWaypointIds;
    }

    public void setConnectedWaypointIds(List<String> connectedWaypointIds) {
        this.connectedWaypointIds = connectedWaypointIds;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public List<Float> getLocalTranslation() {
        return localTranslation;
    }

    public void setLocalTranslation(List<Float> localTranslation) {
        this.localTranslation = localTranslation;
    }
}
