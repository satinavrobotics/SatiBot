package com.satinavrobotics.satibot.mapManagement;

import com.google.firebase.firestore.DocumentId;

import java.util.ArrayList;
import java.util.List;

/**
 * Model class for a Map with AR anchors and waypoints.
 */
public class Map {
    @DocumentId
    private String id;
    private String name;
    private String creatorEmail;
    private String creatorId;
    private List<Anchor> anchors;
    private List<WaypointData> waypoints;
    private long createdAt;
    private long updatedAt;

    // Transient field to track selection state (not stored in Firestore)
    private transient boolean selected;

    // Empty constructor required for Firestore
    public Map() {
        this.anchors = new ArrayList<>();
        this.waypoints = new ArrayList<>();
    }

    public Map(String id, String name, String creatorEmail, String creatorId, List<Anchor> anchors,
               List<WaypointData> waypoints, long createdAt, long updatedAt) {
        this.id = id;
        this.name = name;
        this.creatorEmail = creatorEmail;
        this.creatorId = creatorId;
        this.anchors = anchors != null ? anchors : new ArrayList<>();
        this.waypoints = waypoints != null ? waypoints : new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Model class for an AR anchor within a map.
     */
    public static class Anchor {
        private String cloudAnchorId;
        private double latitude;
        private double longitude;
        private double altitude;
        private String name;
        private long createdAt;
        // Local coordinates relative to the first anchor
        private double localX;
        private double localY;
        private double localZ;
        // Local orientation (quaternion) relative to the first anchor
        private double localQx;
        private double localQy;
        private double localQz;
        private double localQw;

        // Empty constructor required for Firestore
        public Anchor() {
        }

        public Anchor(String cloudAnchorId, double latitude, double longitude, double altitude, String name, long createdAt) {
            this.cloudAnchorId = cloudAnchorId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.name = name;
            this.createdAt = createdAt;
            // Default local coordinates to 0
            this.localX = 0;
            this.localY = 0;
            this.localZ = 0;
            // Default local orientation to identity quaternion
            this.localQx = 0;
            this.localQy = 0;
            this.localQz = 0;
            this.localQw = 1;
        }

        public Anchor(String cloudAnchorId, double latitude, double longitude, double altitude,
                     double localX, double localY, double localZ, String name, long createdAt) {
            this.cloudAnchorId = cloudAnchorId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.name = name;
            this.createdAt = createdAt;
            // Default local orientation to identity quaternion
            this.localQx = 0;
            this.localQy = 0;
            this.localQz = 0;
            this.localQw = 1;
        }

        public Anchor(String cloudAnchorId, double latitude, double longitude, double altitude,
                     double localX, double localY, double localZ,
                     double localQx, double localQy, double localQz, double localQw,
                     String name, long createdAt) {
            this.cloudAnchorId = cloudAnchorId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.localQx = localQx;
            this.localQy = localQy;
            this.localQz = localQz;
            this.localQw = localQw;
            this.name = name;
            this.createdAt = createdAt;
        }

        public String getCloudAnchorId() {
            return cloudAnchorId;
        }

        public void setCloudAnchorId(String cloudAnchorId) {
            this.cloudAnchorId = cloudAnchorId;
        }

        public double getLatitude() {
            return latitude;
        }

        public void setLatitude(double latitude) {
            this.latitude = latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public void setLongitude(double longitude) {
            this.longitude = longitude;
        }

        public double getAltitude() {
            return altitude;
        }

        public void setAltitude(double altitude) {
            this.altitude = altitude;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }

        public double getLocalX() {
            return localX;
        }

        public void setLocalX(double localX) {
            this.localX = localX;
        }

        public double getLocalY() {
            return localY;
        }

        public void setLocalY(double localY) {
            this.localY = localY;
        }

        public double getLocalZ() {
            return localZ;
        }

        public void setLocalZ(double localZ) {
            this.localZ = localZ;
        }

        public double getLocalQx() {
            return localQx;
        }

        public void setLocalQx(double localQx) {
            this.localQx = localQx;
        }

        public double getLocalQy() {
            return localQy;
        }

        public void setLocalQy(double localQy) {
            this.localQy = localQy;
        }

        public double getLocalQz() {
            return localQz;
        }

        public void setLocalQz(double localQz) {
            this.localQz = localQz;
        }

        public double getLocalQw() {
            return localQw;
        }

        public void setLocalQw(double localQw) {
            this.localQw = localQw;
        }

        /**
         * Calculates the distance to the origin (0,0,0) in local coordinates.
         * This is useful for finding the closest anchor to the origin.
         *
         * @return The Euclidean distance to the origin
         */
        public double distanceToOrigin() {
            return Math.sqrt(localX * localX + localY * localY + localZ * localZ);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreatorEmail() {
        return creatorEmail;
    }

    public void setCreatorEmail(String creatorEmail) {
        this.creatorEmail = creatorEmail;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(String creatorId) {
        this.creatorId = creatorId;
    }

    public List<Anchor> getAnchors() {
        return anchors;
    }

    public void setAnchors(List<Anchor> anchors) {
        this.anchors = anchors;
    }

    public int getAnchorCount() {
        return anchors != null ? anchors.size() : 0;
    }

    /**
     * Setter for anchorCount required by Firestore deserialization.
     * This method is only used by Firestore and doesn't actually set anything
     * since the count is derived from the anchors list size.
     *
     * @param count The count value from Firestore (ignored)
     */
    public void setAnchorCount(int count) {
        // Ignore the input value as the count is derived from the list size
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void addAnchor(Anchor anchor) {
        if (this.anchors == null) {
            this.anchors = new ArrayList<>();
        }
        this.anchors.add(anchor);
    }

    public List<WaypointData> getWaypoints() {
        return waypoints;
    }

    public void setWaypoints(List<WaypointData> waypoints) {
        this.waypoints = waypoints;
    }

    public void addWaypoint(WaypointData waypoint) {
        if (this.waypoints == null) {
            this.waypoints = new ArrayList<>();
        }
        this.waypoints.add(waypoint);
    }

    public int getWaypointCount() {
        return waypoints != null ? waypoints.size() : 0;
    }

    /**
     * Setter for waypointCount required by Firestore deserialization.
     * This method is only used by Firestore and doesn't actually set anything
     * since the count is derived from the waypoints list size.
     *
     * @param count The count value from Firestore (ignored)
     */
    public void setWaypointCount(int count) {
        // Ignore the input value as the count is derived from the list size
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}
