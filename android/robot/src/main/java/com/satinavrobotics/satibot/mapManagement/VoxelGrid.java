package com.satinavrobotics.satibot.mapManagement;

import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements a voxel grid filter for point cloud subsampling.
 * This reduces the density of the point cloud by representing multiple points in a voxel
 * with a single point (typically the centroid of all points in the voxel).
 */
public class VoxelGrid {
    private static final String TAG = VoxelGrid.class.getSimpleName();
    
    /**
     * Applies voxel grid filtering to a point cloud.
     * 
     * @param points List of points, each represented as a float array [x, y, z, r, g, b, confidence]
     * @param voxelSize Size of each voxel cube (in meters)
     * @return Filtered point cloud with one point per voxel (centroid)
     */
    public static List<float[]> filter(List<float[]> points, float voxelSize) {
        if (points == null || points.isEmpty() || voxelSize <= 0) {
            return points;
        }
        
        // Map to store voxels: key = voxel index, value = list of points in that voxel
        Map<String, List<float[]>> voxels = new HashMap<>();
        
        // Assign each point to a voxel
        for (float[] point : points) {
            if (point.length < 7) continue; // Skip invalid points
            
            // Calculate voxel indices for this point
            int voxelX = (int) Math.floor(point[0] / voxelSize);
            int voxelY = (int) Math.floor(point[1] / voxelSize);
            int voxelZ = (int) Math.floor(point[2] / voxelSize);
            
            // Create a unique key for this voxel
            String voxelKey = voxelX + "," + voxelY + "," + voxelZ;
            
            // Add the point to the appropriate voxel
            if (!voxels.containsKey(voxelKey)) {
                voxels.put(voxelKey, new ArrayList<>());
            }
            voxels.get(voxelKey).add(point);
        }
        
        // Create the filtered point cloud with one point per voxel (centroid)
        List<float[]> filteredPoints = new ArrayList<>(voxels.size());
        
        for (List<float[]> voxelPoints : voxels.values()) {
            // Calculate the centroid of all points in this voxel
            float[] centroid = calculateCentroid(voxelPoints);
            if (centroid != null) {
                filteredPoints.add(centroid);
            }
        }
        
        Log.d(TAG, "Voxel grid filtering: " + points.size() + " points -> " + 
              filteredPoints.size() + " points (voxel size: " + voxelSize + "m)");
        
        return filteredPoints;
    }
    
    /**
     * Calculates the centroid of a set of points.
     * The centroid is the average position of all points, with color and confidence
     * also averaged.
     * 
     * @param points List of points in a voxel
     * @return Centroid point [x, y, z, r, g, b, confidence]
     */
    private static float[] calculateCentroid(List<float[]> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        
        float sumX = 0, sumY = 0, sumZ = 0;
        float sumR = 0, sumG = 0, sumB = 0;
        float sumConfidence = 0;
        
        for (float[] point : points) {
            sumX += point[0];
            sumY += point[1];
            sumZ += point[2];
            sumR += point[3];
            sumG += point[4];
            sumB += point[5];
            sumConfidence += point[6];
        }
        
        int count = points.size();
        return new float[] {
            sumX / count,
            sumY / count,
            sumZ / count,
            sumR / count,
            sumG / count,
            sumB / count,
            sumConfidence / count
        };
    }
}
