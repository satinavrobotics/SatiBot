package org.openbot.mapManagement;

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
            hostedAnchors.add(new AnchorWithCloudId(anchor, cloudAnchorId, pose));
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
            Timber.d("Cleared all anchors");
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
}
