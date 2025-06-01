package com.satinavrobotics.satibot.mapManagement.pcd_processing;

import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implements statistical outlier detection and removal for point clouds.
 * This helps to remove noisy points that are likely measurement errors.
 */
public class OutlierDetector {
    private static final String TAG = OutlierDetector.class.getSimpleName();
    
    /**
     * Removes statistical outliers from a point cloud.
     * 
     * The algorithm works by:
     * 1. Computing the mean distance of each point to its k nearest neighbors
     * 2. Computing the mean and standard deviation of these distances
     * 3. Removing points whose mean distance is outside a defined range (mean ± stddev_mult * stddev)
     * 
     * @param points List of points, each represented as a float array [x, y, z, r, g, b, confidence]
     * @param kNeighbors Number of nearest neighbors to consider (typically 8-20)
     * @param stddevMult Standard deviation multiplier for the threshold (typically 1.0-2.0)
     * @return Filtered point cloud with outliers removed
     */
    public static List<float[]> removeOutliers(List<float[]> points, int kNeighbors, float stddevMult) {
        if (points == null || points.size() <= kNeighbors + 1) {
            // Not enough points to perform outlier detection
            return points;
        }
        
        int numPoints = points.size();
        
        // Adjust k if necessary
        kNeighbors = Math.min(kNeighbors, numPoints - 1);
        
        // Calculate mean distance to k nearest neighbors for each point
        float[] meanDistances = new float[numPoints];
        
        for (int i = 0; i < numPoints; i++) {
            float[] point = points.get(i);
            float[] distances = new float[numPoints - 1];
            
            // Calculate distances to all other points
            int distIdx = 0;
            for (int j = 0; j < numPoints; j++) {
                if (i == j) continue; // Skip self
                
                float[] otherPoint = points.get(j);
                float dist = distance(point, otherPoint);
                distances[distIdx++] = dist;
            }
            
            // Sort distances and take mean of k smallest
            Arrays.sort(distances);
            float sum = 0;
            for (int k = 0; k < kNeighbors; k++) {
                sum += distances[k];
            }
            meanDistances[i] = sum / kNeighbors;
        }
        
        // Calculate mean and standard deviation of mean distances
        float meanOfMeans = 0;
        for (float dist : meanDistances) {
            meanOfMeans += dist;
        }
        meanOfMeans /= numPoints;
        
        float variance = 0;
        for (float dist : meanDistances) {
            float diff = dist - meanOfMeans;
            variance += diff * diff;
        }
        variance /= numPoints;
        float stdDev = (float) Math.sqrt(variance);
        
        // Define threshold
        float threshold = meanOfMeans + stddevMult * stdDev;
        
        // Filter points
        List<float[]> filteredPoints = new ArrayList<>();
        int removedCount = 0;
        
        for (int i = 0; i < numPoints; i++) {
            if (meanDistances[i] <= threshold) {
                filteredPoints.add(points.get(i));
            } else {
                removedCount++;
            }
        }
        
        Log.d(TAG, "Outlier removal: " + removedCount + " outliers removed out of " + 
              numPoints + " points (threshold: " + threshold + ")");
        
        return filteredPoints;
    }
    
    /**
     * Calculates the Euclidean distance between two 3D points.
     */
    private static float distance(float[] p1, float[] p2) {
        float dx = p1[0] - p2[0];
        float dy = p1[1] - p2[1];
        float dz = p1[2] - p2[2];
        return (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
    }
}
