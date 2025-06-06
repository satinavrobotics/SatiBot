package com.satinavrobotics.satibot.mapManagement;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

/**
 * A manager class to handle cloud anchor operations for map scanning.
 * This class supports hosting multiple cloud anchors and tracking their states.
 * Updated to use the latest ARCore Cloud Anchors API (ARCore API).
 */
public class MapScanningManager {
    private static final String TAG = "MapScanningManager";
    private static final int MAX_ANCHORS = 30;
    private static final long HOSTING_TIMEOUT_MS = 60000; // 1 minute timeout for hosting

    /** Listener for the results of a host operation. */
    public interface CloudAnchorHostListener {
        /** This method is invoked when the results of a Cloud Anchor operation are available. */
        void onCloudTaskComplete(@Nullable String cloudAnchorId, CloudAnchorState cloudAnchorState, Anchor anchor);
    }

    @Nullable private Session session = null;
    private final Object anchorLock = new Object();
    @GuardedBy("anchorLock") private final List<AnchorWithCloudId> hostedAnchors = new ArrayList<>();
    @GuardedBy("anchorLock") private final Map<Anchor, Pose> anchorPoses = new HashMap<>();
    @GuardedBy("anchorLock") private final Map<Anchor, Long> anchorStartTimes = new HashMap<>();

    // Track the origin anchor (first anchor placed) for local coordinate system
    @GuardedBy("anchorLock") @Nullable private AnchorWithCloudId originAnchor = null;

    // We're using direct callbacks from hostCloudAnchorAsync, so we don't need to track tasks

    /**
     * Class to store an anchor with its cloud ID.
     */
    public static class AnchorWithCloudId {
        private final Anchor anchor;
        private final String cloudAnchorId;
        private final Pose pose;

        public AnchorWithCloudId(Anchor anchor, String cloudAnchorId, Pose pose) {
            this.anchor = anchor;
            this.cloudAnchorId = cloudAnchorId;
            this.pose = pose;
        }

        public Anchor getAnchor() {
            return anchor;
        }

        public String getCloudAnchorId() {
            return cloudAnchorId;
        }

        public Pose getPose() {
            return pose;
        }
    }

    /**
     * Sets the ARCore session.
     */
    public void setSession(Session session) {
        this.session = session;
        Timber.d("Session set in MapScanningManager. Current hosted anchors: %d", hostedAnchors.size());
    }

    /**
     * Adds a hosted anchor to the list of tracked anchors.
     *
     * @param anchor The anchor to add
     * @param cloudAnchorId The cloud anchor ID associated with the anchor
     */
    public void addHostedAnchor(Anchor anchor, String cloudAnchorId) {
        synchronized (anchorLock) {
            if (hostedAnchors.size() >= MAX_ANCHORS) {
                Timber.w("Maximum number of anchors reached (%d)", MAX_ANCHORS);
                return;
            }

            // Check if the anchor is already in the list
            if (containsAnchor(anchor)) {
                Timber.d("Anchor already in the list, not adding again");
                return;
            }

            Pose pose = anchor.getPose();
            AnchorWithCloudId newAnchorWithCloudId = new AnchorWithCloudId(anchor, cloudAnchorId, pose);
            hostedAnchors.add(newAnchorWithCloudId);

            // If this is the first anchor, set it as the origin for local coordinates
            if (originAnchor == null) {
                originAnchor = newAnchorWithCloudId;
                Timber.d("Set first anchor as origin for local coordinate system: %s", cloudAnchorId);
            }

            Timber.d("Added hosted anchor with cloud ID: %s, total anchors: %d", cloudAnchorId, hostedAnchors.size());
        }
    }

    /**
     * Gets the list of successfully hosted anchors.
     */
    public List<AnchorWithCloudId> getHostedAnchors() {
        synchronized (anchorLock) {
            return new ArrayList<>(hostedAnchors); // Return a copy to avoid concurrent modification
        }
    }

    /**
     * Gets the number of anchors currently being hosted.
     */
    public int getAnchorCount() {
        synchronized (anchorLock) {
            return hostedAnchors.size();
        }
    }

    /**
     * Clears all hosted anchors and tasks.
     */
    public void clear() {
        synchronized (anchorLock) {
            for (AnchorWithCloudId anchorWithCloudId : hostedAnchors) {
                anchorWithCloudId.getAnchor().detach();
            }
            hostedAnchors.clear();
            anchorPoses.clear();
            anchorStartTimes.clear();
            originAnchor = null; // Reset the origin anchor
            Timber.d("Cleared all anchors and reset origin anchor");
        }
    }

    /**
     * Returns whether the maximum number of anchors has been reached.
     */
    public boolean isMaxAnchorsReached() {
        synchronized (anchorLock) {
            return hostedAnchors.size() >= MAX_ANCHORS;
        }
    }

    /**
     * Returns the maximum number of anchors allowed.
     */
    public int getMaxAnchors() {
        return MAX_ANCHORS;
    }

    /**
     * Checks if the given anchor is already in the hosted anchors list.
     *
     * @param anchor The anchor to check
     * @return true if the anchor is already in the list, false otherwise
     */
    public boolean containsAnchor(Anchor anchor) {
        synchronized (anchorLock) {
            for (AnchorWithCloudId anchorWithCloudId : hostedAnchors) {
                if (anchorWithCloudId.getAnchor().equals(anchor)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Removes an anchor from the hosted anchors list.
     *
     * @param anchor The anchor to remove
     */
    public void removeAnchor(Anchor anchor) {
        synchronized (anchorLock) {
            AnchorWithCloudId anchorToRemove = null;
            for (AnchorWithCloudId anchorWithCloudId : hostedAnchors) {
                if (anchorWithCloudId.getAnchor().equals(anchor)) {
                    anchorToRemove = anchorWithCloudId;
                    break;
                }
            }

            if (anchorToRemove != null) {
                hostedAnchors.remove(anchorToRemove);
                anchorToRemove.getAnchor().detach();
                Timber.d("Removed anchor, remaining: %d", hostedAnchors.size());
            }
        }
    }

    /**
     * Hosts a cloud anchor using the latest ARCore Cloud Anchors API.
     * This method uses the asynchronous API introduced in ARCore 1.33.0.
     *
     * @param anchor The anchor to host
     * @param listener The listener to call when the hosting operation completes
     */
    public void hostCloudAnchor(Anchor anchor, CloudAnchorHostListener listener) {
        if (session == null) {
            Timber.e("Cannot host cloud anchor, session is null");
            return;
        }

        synchronized (anchorLock) {
            // Use the new async API for hosting cloud anchors
            session.hostCloudAnchorAsync(anchor, 365, (cloudAnchorId, cloudAnchorState) -> {
                // This is the direct callback from the API
                Timber.d("Cloud anchor hosting callback: id=%s, state=%s", cloudAnchorId, cloudAnchorState);

                // Directly notify the listener with the result
                if (listener != null) {
                    listener.onCloudTaskComplete(cloudAnchorId, cloudAnchorState, anchor);
                }

                // If successful, add the anchor to our hosted anchors list
                if (cloudAnchorState == CloudAnchorState.SUCCESS && cloudAnchorId != null) {
                    addHostedAnchor(anchor, cloudAnchorId);
                }
            });
            Timber.d("Started hosting cloud anchor with TTL of 365 days");
        }
    }

    /**
     * Updates the state of all cloud anchor tasks.
     * This should be called after each Session.update() to process any completed tasks.
     *
     * Note: With the new implementation using direct callbacks from hostCloudAnchorAsync,
     * this method is no longer needed for processing host tasks, but is kept for compatibility
     * and potential future use.
     */
    public void onUpdate() {
        // No implementation needed as we're using direct callbacks from hostCloudAnchorAsync
    }

    /**
     * Gets the origin anchor (first anchor placed) for the local coordinate system.
     *
     * @return The origin anchor, or null if no anchors have been placed
     */
    @Nullable
    public AnchorWithCloudId getOriginAnchor() {
        synchronized (anchorLock) {
            return originAnchor;
        }
    }

    /**
     * Calculates the local coordinates of an anchor relative to the origin anchor.
     *
     * @param anchor The anchor to calculate local coordinates for
     * @return A float array containing the local [x, y, z] coordinates, or null if there's no origin anchor
     */
    @Nullable
    public float[] calculateLocalCoordinates(AnchorWithCloudId anchor) {
        synchronized (anchorLock) {
            if (originAnchor == null) {
                Timber.w("Cannot calculate local coordinates: no origin anchor set");
                return null;
            }

            // Get the poses
            Pose originPose = originAnchor.getPose();
            Pose anchorPose = anchor.getPose();

            // Calculate the relative pose (transform from origin to anchor)
            Pose relativePose = originPose.inverse().compose(anchorPose);

            // Extract the translation component
            float[] translation = new float[3];
            relativePose.getTranslation(translation, 0);

            Timber.d("Calculated local coordinates for anchor %s: [%f, %f, %f]",
                    anchor.getCloudAnchorId(), translation[0], translation[1], translation[2]);

            return translation;
        }
    }

    /**
     * Calculates the local orientation (quaternion) of an anchor relative to the origin anchor.
     *
     * @param anchor The anchor to calculate local orientation for
     * @return A float array containing the local quaternion [x, y, z, w], or null if there's no origin anchor
     */
    @Nullable
    public float[] calculateLocalOrientation(AnchorWithCloudId anchor) {
        synchronized (anchorLock) {
            if (originAnchor == null) {
                Timber.w("Cannot calculate local orientation: no origin anchor set");
                return null;
            }

            // Get the poses
            Pose originPose = originAnchor.getPose();
            Pose anchorPose = anchor.getPose();

            // Calculate the relative pose (transform from origin to anchor)
            Pose relativePose = originPose.inverse().compose(anchorPose);

            // Extract the rotation component as quaternion
            float[] rotation = new float[4];
            relativePose.getRotationQuaternion(rotation, 0);

            Timber.d("Calculated local orientation for anchor %s: [%f, %f, %f, %f]",
                    anchor.getCloudAnchorId(), rotation[0], rotation[1], rotation[2], rotation[3]);

            return rotation;
        }
    }

    /**
     * Calculates both local coordinates and orientation of an anchor relative to the origin anchor.
     *
     * @param anchor The anchor to calculate local pose for
     * @return A Pose object representing the local pose, or null if there's no origin anchor
     */
    @Nullable
    public Pose calculateLocalPose(AnchorWithCloudId anchor) {
        synchronized (anchorLock) {
            if (originAnchor == null) {
                Timber.w("Cannot calculate local pose: no origin anchor set");
                return null;
            }

            // Get the poses
            Pose originPose = originAnchor.getPose();
            Pose anchorPose = anchor.getPose();

            // Calculate the relative pose (transform from origin to anchor)
            return originPose.inverse().compose(anchorPose);
        }
    }
}
