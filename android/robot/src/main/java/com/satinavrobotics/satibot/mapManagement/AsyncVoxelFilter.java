package com.satinavrobotics.satibot.mapManagement;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous voxel grid filter that processes point clouds on a background thread.
 * This prevents blocking the main rendering thread during voxel filtering operations.
 */
public class AsyncVoxelFilter {
    private static final String TAG = AsyncVoxelFilter.class.getSimpleName();
    
    // Background thread for processing
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Handler mainHandler;
    
    // Callback interface for filtering completion
    public interface FilterCallback {
        void onFilterComplete(List<float[]> filteredPoints);
    }
    
    // Processing state
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    /**
     * Creates a new AsyncVoxelFilter and starts the background thread.
     */
    public AsyncVoxelFilter() {
        // Create and start the background thread
        backgroundThread = new HandlerThread("VoxelFilterThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());
        
        Log.d(TAG, "AsyncVoxelFilter initialized with background thread");
    }
    
    /**
     * Checks if the filter is currently processing a point cloud.
     * @return true if processing, false otherwise
     */
    public boolean isProcessing() {
        return isProcessing.get();
    }
    
    /**
     * Asynchronously filters a point cloud using voxel grid filtering.
     * 
     * @param points The input point cloud to filter
     * @param voxelSize The size of each voxel in meters
     * @param callback Callback to receive the filtered point cloud
     */
    public void filterAsync(final List<float[]> points, final float voxelSize, final FilterCallback callback) {
        // If already processing, skip this request
        if (isProcessing.getAndSet(true)) {
            Log.d(TAG, "Skipping voxel filter request - already processing");
            return;
        }
        
        // Make a defensive copy of the points to avoid concurrent modification
        final List<float[]> pointsCopy = new ArrayList<>(points);
        
        // Process on background thread
        backgroundHandler.post(() -> {
            try {
                Log.d(TAG, "Starting voxel filtering on background thread");
                long startTime = System.currentTimeMillis();
                
                // Perform the filtering
                final List<float[]> filteredPoints = VoxelGrid.filter(pointsCopy, voxelSize);
                
                long duration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Voxel filtering completed in " + duration + "ms");
                
                // Deliver result on main thread
                mainHandler.post(() -> {
                    isProcessing.set(false);
                    if (callback != null) {
                        callback.onFilterComplete(filteredPoints);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in voxel filtering: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    isProcessing.set(false);
                    // Return original points in case of error
                    if (callback != null) {
                        callback.onFilterComplete(pointsCopy);
                    }
                });
            }
        });
    }
    
    /**
     * Shuts down the background thread.
     * Call this method when the filter is no longer needed to free resources.
     */
    public void shutdown() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error shutting down background thread: " + e.getMessage(), e);
            }
        }
    }
}
