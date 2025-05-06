package com.satinavrobotics.satibot.mapManagement;

import android.util.Log;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Provides filtering operations for depth images to reduce noise and improve quality.
 */
public class DepthImageFilter {
    private static final String TAG = DepthImageFilter.class.getSimpleName();
    
    /**
     * Applies median filtering to a depth image.
     * Median filtering replaces each pixel with the median value of its neighborhood,
     * which effectively removes outliers and salt-and-pepper noise.
     * 
     * @param depthBuffer The original depth buffer (16-bit values in millimeters)
     * @param width Width of the depth image
     * @param height Height of the depth image
     * @param rowStride Row stride of the depth buffer (in shorts)
     * @param kernelSize Size of the median filter kernel (3 for 3x3, 5 for 5x5, etc.)
     * @return A new filtered depth buffer
     */
    public static short[] applyMedianFilter(ShortBuffer depthBuffer, int width, int height, 
                                           int rowStride, int kernelSize) {
        if (depthBuffer == null || width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid input for median filtering");
            return null;
        }
        
        // Ensure kernel size is odd
        if (kernelSize % 2 == 0) {
            kernelSize++;
        }
        
        // Limit kernel size to reasonable values
        kernelSize = Math.max(3, Math.min(7, kernelSize));
        
        int radius = kernelSize / 2;
        short[] result = new short[width * height];
        short[] neighborhood = new short[kernelSize * kernelSize];
        
        long startTime = System.currentTimeMillis();
        
        // Copy original depth data to a short array for easier manipulation
        short[] depthArray = new short[depthBuffer.capacity()];
        depthBuffer.position(0);
        depthBuffer.get(depthArray);
        depthBuffer.position(0);
        
        // Apply median filter
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * rowStride + x;
                
                // Skip if out of bounds
                if (idx >= depthArray.length) {
                    continue;
                }
                
                // Skip if no depth value
                if (depthArray[idx] == 0) {
                    result[y * width + x] = 0;
                    continue;
                }
                
                // Collect neighborhood values
                int validCount = 0;
                for (int ky = -radius; ky <= radius; ky++) {
                    int ny = y + ky;
                    if (ny < 0 || ny >= height) continue;
                    
                    for (int kx = -radius; kx <= radius; kx++) {
                        int nx = x + kx;
                        if (nx < 0 || nx >= width) continue;
                        
                        int neighborIdx = ny * rowStride + nx;
                        if (neighborIdx >= 0 && neighborIdx < depthArray.length) {
                            short val = depthArray[neighborIdx];
                            if (val > 0) {  // Only consider valid depth values
                                neighborhood[validCount++] = val;
                            }
                        }
                    }
                }
                
                // Calculate median
                if (validCount > 0) {
                    // Sort the valid values
                    Arrays.sort(neighborhood, 0, validCount);
                    
                    // Get median value
                    short medianValue;
                    if (validCount % 2 == 0) {
                        // Even number of elements - average the middle two
                        medianValue = (short)((neighborhood[validCount/2 - 1] + neighborhood[validCount/2]) / 2);
                    } else {
                        // Odd number of elements - take the middle one
                        medianValue = neighborhood[validCount/2];
                    }
                    
                    result[y * width + x] = medianValue;
                } else {
                    // No valid neighbors, keep original value
                    result[y * width + x] = depthArray[idx];
                }
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        Log.d(TAG, "Median filtering completed in " + duration + "ms (kernel size: " + kernelSize + "x" + kernelSize + ")");
        
        return result;
    }
    
    /**
     * Creates a ShortBuffer from a short array.
     * 
     * @param array The short array
     * @return A ShortBuffer containing the array data
     */
    public static ShortBuffer createShortBuffer(short[] array) {
        ShortBuffer buffer = ShortBuffer.allocate(array.length);
        buffer.put(array);
        buffer.position(0);
        return buffer;
    }
}
