package com.satinavrobotics.satibot.mapManagement;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.Session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

/**
 * A manager class to handle cloud anchor resolving operations.
 * This class supports resolving multiple cloud anchors and tracking their states.
 * Uses the latest ARCore Cloud Anchors API (ARCore API).
 */
public class MapResolvingManager {
    private static final String TAG = "MapResolvingManager";

    /** Listener for the results of a resolve operation. */
    public interface CloudAnchorResolveListener {
        /** This method is invoked when the results of a Cloud Anchor resolve operation are available. */
        void onCloudTaskComplete(@Nullable String cloudAnchorId, @Nullable Anchor anchor, CloudAnchorState cloudAnchorState);
    }

    @Nullable private Session session = null;
    private final Object anchorLock = new Object();
    @GuardedBy("anchorLock") private final List<ResolvedAnchor> resolvedAnchors = new ArrayList<>();
    @GuardedBy("anchorLock") private final Map<String, CloudAnchorResolveListener> pendingResolveRequests = new HashMap<>();

    /**
     * Class to store a resolved anchor with its cloud ID.
     */
    public static class ResolvedAnchor {
        private final Anchor anchor;
        private final String cloudAnchorId;

        public ResolvedAnchor(Anchor anchor, String cloudAnchorId) {
            this.anchor = anchor;
            this.cloudAnchorId = cloudAnchorId;
        }

        public Anchor getAnchor() {
            return anchor;
        }

        public String getCloudAnchorId() {
            return cloudAnchorId;
        }
    }

    /**
     * Sets the ARCore session.
     */
    public void setSession(Session session) {
        this.session = session;
    }

    /**
     * Gets the list of successfully resolved anchors.
     */
    public List<ResolvedAnchor> getResolvedAnchors() {
        synchronized (anchorLock) {
            return new ArrayList<>(resolvedAnchors); // Return a copy to avoid concurrent modification
        }
    }

    /**
     * Gets the number of anchors currently resolved.
     */
    public int getResolvedAnchorCount() {
        synchronized (anchorLock) {
            return resolvedAnchors.size();
        }
    }

    /**
     * Clears all resolved anchors and pending requests.
     */
    public void clear() {
        synchronized (anchorLock) {
            for (ResolvedAnchor resolvedAnchor : resolvedAnchors) {
                resolvedAnchor.getAnchor().detach();
            }
            resolvedAnchors.clear();
            pendingResolveRequests.clear();
            Timber.d("Cleared all resolved anchors and pending requests");
        }
    }

    /**
     * Resolves a cloud anchor using the latest ARCore Cloud Anchors API.
     * This method uses the asynchronous API introduced in ARCore 1.33.0.
     *
     * @param cloudAnchorId The cloud anchor ID to resolve
     * @param listener The listener to call when the resolving operation completes
     */
    public void resolveCloudAnchor(String cloudAnchorId, CloudAnchorResolveListener listener) {
        if (session == null) {
            Timber.e("Cannot resolve cloud anchor, session is null");
            if (listener != null) {
                listener.onCloudTaskComplete(cloudAnchorId, null, CloudAnchorState.ERROR_INTERNAL);
            }
            return;
        }

        synchronized (anchorLock) {
            // Store the listener for this cloud anchor ID
            pendingResolveRequests.put(cloudAnchorId, listener);

            // Use the new async API for resolving cloud anchors
            session.resolveCloudAnchorAsync(cloudAnchorId, (anchor, cloudAnchorState) -> {
                // This is the direct callback from the API
                Timber.d("Cloud anchor resolving callback: id=%s, state=%s", cloudAnchorId, cloudAnchorState);

                synchronized (anchorLock) {
                    // Remove the pending request
                    CloudAnchorResolveListener requestListener = pendingResolveRequests.remove(cloudAnchorId);

                    // Notify the listener with the result
                    if (requestListener != null) {
                        requestListener.onCloudTaskComplete(cloudAnchorId, anchor, cloudAnchorState);
                    }

                    // If successful, add the anchor to our resolved anchors list
                    if (cloudAnchorState == CloudAnchorState.SUCCESS && anchor != null) {
                        resolvedAnchors.add(new ResolvedAnchor(anchor, cloudAnchorId));
                        Timber.d("Added resolved anchor with cloud ID: %s, total resolved: %d", 
                                cloudAnchorId, resolvedAnchors.size());
                    }
                }
            });
            Timber.d("Started resolving cloud anchor with ID: %s", cloudAnchorId);
        }
    }

    /**
     * Resolves multiple cloud anchors in sequence.
     *
     * @param cloudAnchorIds List of cloud anchor IDs to resolve
     * @param listener The listener to call for each resolving operation
     */
    public void resolveCloudAnchors(List<String> cloudAnchorIds, CloudAnchorResolveListener listener) {
        if (cloudAnchorIds == null || cloudAnchorIds.isEmpty()) {
            Timber.w("No cloud anchor IDs provided for resolving");
            return;
        }

        for (String cloudAnchorId : cloudAnchorIds) {
            resolveCloudAnchor(cloudAnchorId, listener);
        }
    }

    /**
     * Removes a resolved anchor.
     *
     * @param cloudAnchorId The cloud anchor ID to remove
     */
    public void removeResolvedAnchor(String cloudAnchorId) {
        synchronized (anchorLock) {
            ResolvedAnchor anchorToRemove = null;
            for (ResolvedAnchor resolvedAnchor : resolvedAnchors) {
                if (resolvedAnchor.getCloudAnchorId().equals(cloudAnchorId)) {
                    anchorToRemove = resolvedAnchor;
                    break;
                }
            }

            if (anchorToRemove != null) {
                resolvedAnchors.remove(anchorToRemove);
                anchorToRemove.getAnchor().detach();
                Timber.d("Removed resolved anchor with ID: %s, remaining: %d", 
                        cloudAnchorId, resolvedAnchors.size());
            }
        }
    }

    /**
     * Checks if a cloud anchor ID has already been resolved.
     *
     * @param cloudAnchorId The cloud anchor ID to check
     * @return true if the cloud anchor ID has been resolved, false otherwise
     */
    public boolean isAnchorResolved(String cloudAnchorId) {
        synchronized (anchorLock) {
            for (ResolvedAnchor resolvedAnchor : resolvedAnchors) {
                if (resolvedAnchor.getCloudAnchorId().equals(cloudAnchorId)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Gets a resolved anchor by its cloud anchor ID.
     *
     * @param cloudAnchorId The cloud anchor ID to look for
     * @return The resolved anchor, or null if not found
     */
    @Nullable
    public ResolvedAnchor getResolvedAnchor(String cloudAnchorId) {
        synchronized (anchorLock) {
            for (ResolvedAnchor resolvedAnchor : resolvedAnchors) {
                if (resolvedAnchor.getCloudAnchorId().equals(cloudAnchorId)) {
                    return resolvedAnchor;
                }
            }
            return null;
        }
    }
}
