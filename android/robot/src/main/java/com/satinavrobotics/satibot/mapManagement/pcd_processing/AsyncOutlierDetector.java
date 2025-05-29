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
 * Asynchronous outlier detector that processes point clouds on a background thread.
 * This prevents blocking the main rendering thread during outlier detection operations.
 */
public class AsyncOutlierDetector {
    private static final String TAG = AsyncOutlierDetector.class.getSimpleName();
    
    // Background thread for processing
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Handler mainHandler;
    
    // Callback interface for detection completion
    public interface DetectionCallback {
        void onDetectionComplete(List<float[]> filteredPoints);
    }
    
    // Processing state
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    /**
     * Creates a new AsyncOutlierDetector and starts the background thread.
     */
    public AsyncOutlierDetector() {
        // Create and start the background thread
        backgroundThread = new HandlerThread("OutlierDetectorThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());
        
        Log.d(TAG, "AsyncOutlierDetector initialized with background thread");
    }
    
    /**
     * Checks if the detector is currently processing a point cloud.
     * @return true if processing, false otherwise
     */
    public boolean isProcessing() {
        return isProcessing.get();
    }
    
    /**
     * Asynchronously detects and removes outliers from a point cloud.
     * 
     * @param points The input point cloud to filter
     * @param kNeighbors Number of nearest neighbors to consider
     * @param stddevMult Standard deviation multiplier for the threshold
     * @param callback Callback to receive the filtered point cloud
     */
    public void detectAsync(final List<float[]> points, final int kNeighbors, 
                           final float stddevMult, final DetectionCallback callback) {
        // If already processing, skip this request
        if (isProcessing.getAndSet(true)) {
            Log.d(TAG, "Skipping outlier detection request - already processing");
            return;
        }
        
        // Make a defensive copy of the points to avoid concurrent modification
        final List<float[]> pointsCopy = new ArrayList<>(points);
        
        // Process on background thread
        backgroundHandler.post(() -> {
            try {
                Log.d(TAG, "Starting outlier detection on background thread");
                long startTime = System.currentTimeMillis();
                
                // Perform the outlier detection
                final List<float[]> filteredPoints = OutlierDetector.removeOutliers(
                        pointsCopy, kNeighbors, stddevMult);
                
                long duration = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Outlier detection completed in " + duration + "ms");
                
                // Deliver result on main thread
                mainHandler.post(() -> {
                    isProcessing.set(false);
                    if (callback != null) {
                        callback.onDetectionComplete(filteredPoints);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in outlier detection: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    isProcessing.set(false);
                    // Return original points in case of error
                    if (callback != null) {
                        callback.onDetectionComplete(pointsCopy);
                    }
                });
            }
        });
    }
    
    /**
     * Shuts down the background thread.
     * Call this method when the detector is no longer needed to free resources.
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
