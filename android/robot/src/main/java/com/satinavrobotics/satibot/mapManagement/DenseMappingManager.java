package com.satinavrobotics.satibot.mapManagement;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.opengl.Matrix;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.google.ar.core.Frame;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;

import com.satinavrobotics.satibot.env.ImageUtils;
import com.satinavrobotics.satibot.googleServices.GoogleServices;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.zeroturnaround.zip.ZipUtil;
import org.zeroturnaround.zip.commons.FileUtils;

import timber.log.Timber;

/**
 * Manages the dense mapping process, including recording and saving depth, color, pointcloud, and pose data.
 * All data is saved in the local coordinate system relative to the origin pose.
 * The coordinate system convention is still ARCore (OpenGL):
 * - +X points to the right
 * - +Y points upward
 * - −Z points forward (in the view direction)
 */
public class DenseMappingManager {
    private static final String TAG = "DenseMappingManager";
    private static final int FRAME_SKIP_COUNT = 5; // Process every Nth frame to reduce computational load
    private static final float DEFAULT_CONFIDENCE_THRESHOLD = 0.5f; // Default confidence threshold for depth points

    // Using ARCore (OpenGL) coordinate system
    // +X points to the right
    // +Y points upward
    // −Z points forward (in the view direction)

    // Recording state
    private boolean isRecording = false;
    private boolean isPaused = false;
    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);
    private int frameCounter = 0;
    private int savedFrameCount = 0;

    private String recordingFolder;
    private String imagesFile;
    private String camerasFile;
    private List<PoseData> poseDataList = new ArrayList<>();

    // Point cloud storage
    private final List<List<float[]>> allPointClouds = new ArrayList<>();
    private String pointCloudFile;

    // Store the latest frame for camera intrinsics
    private Frame latestFrame;

    // Origin pose for local coordinate system
    private Pose originPose;

    // Threading
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Google Drive integration
    private GoogleServices googleServices;

    // Callback interface
    public interface DenseMappingCallback {
        void onFrameProcessed(int frameCount);
        void onRecordingFinished(String zipFilePath);
        void onError(String errorMessage);
    }

    private DenseMappingCallback callback;

    /**
     * Constructor for DenseMappingManager.
     *
     * @param context Application context
     * @param mapId ID of the map being densely mapped
     * @param mapName Name of the map being densely mapped
     * @param googleServices Google services for Drive upload
     * @param callback Callback for mapping events
     * @param originPose The origin pose for local coordinate system (can be null)
     */
    public DenseMappingManager(Context context, String mapId, String mapName,
                               GoogleServices googleServices, DenseMappingCallback callback,
                               Pose originPose) {
        // Data storage
        this.googleServices = googleServices;
        this.callback = callback;
        this.originPose = originPose;

        if (originPose != null) {
            Timber.d("Using origin pose for local coordinate system: %s", originPose);
        } else {
            Timber.d("No origin pose provided, local coordinates will be in world space");
        }

        // Create base recording folder
        String baseFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                .getAbsolutePath() + File.separator + "OpenBot" + File.separator + "DenseMaps";

        // Create timestamp for this recording session
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());

        // Create folder for this recording session
        recordingFolder = baseFolder + File.separator + mapName + "_" + timestamp;

        // Create subfolders
        createSubfolders();

        imagesFile = recordingFolder + File.separator + "images.txt";
        camerasFile = recordingFolder + File.separator + "cameras.txt";
        pointCloudFile = recordingFolder + File.separator + "points3D.txt";
        poseDataList = new ArrayList<>();
    }

    /**
     * Creates the necessary subfolders for storing mapping data.
     * Note: Data will be stored in the local coordinate system relative to the origin pose,
     * using ARCore's coordinate system convention.
     */
    private void createSubfolders() {
        File baseDir = new File(recordingFolder);
        if (!baseDir.exists()) {
            if (!baseDir.mkdirs()) {
                Timber.e("Failed to create directory: %s", recordingFolder);
                if (callback != null) {
                    callback.onError("Failed to create recording directory");
                }
                return;
            }
        }

        // Create images folder for storing image files
        new File(recordingFolder + File.separator + "images").mkdirs();
    }

    /**
     * Starts recording dense mapping data.
     */
    public void startRecording() {
        if (isRecording && !isPaused) {
            return; // Already recording
        }

        isRecording = true;
        isPaused = false;
        frameCounter = 0;
        savedFrameCount = 0;
        allPointClouds.clear(); // Clear any existing point clouds

        Timber.d("Started dense mapping recording");
    }

    /**
     * Pauses the recording process.
     */
    public void pauseRecording() {
        if (!isRecording || isPaused) {
            return; // Not recording or already paused
        }

        isPaused = true;
        Timber.d("Paused dense mapping recording");
    }

    /**
     * Resumes the recording process after a pause.
     */
    public void resumeRecording() {
        if (!isRecording || !isPaused) {
            return; // Not recording or not paused
        }

        isPaused = false;
        Timber.d("Resumed dense mapping recording");
    }

    /**
     * Stops recording and finalizes the data.
     */
    public void stopRecording() {
        if (!isRecording) {
            return; // Not recording
        }

        isRecording = false;
        isPaused = false;

        // Save pose data in COLMAP format with camera intrinsics from the latest frame
        savePoseData(latestFrame);

        // Create zip file and upload to Google Drive
        executor.execute(() -> {
            try {
                // Create zip file
                File folder = new File(recordingFolder);
                String zipFileName = recordingFolder + ".zip";
                File zipFile = new File(zipFileName);

                ZipUtil.pack(folder, zipFile);
                Timber.d("Created zip file: %s", zipFileName);

                // Notify on main thread
                final String finalZipPath = zipFileName;
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onRecordingFinished(finalZipPath);
                    }
                });

                // Upload to Google Drive - modified to match FreeRoamFragment approach
                if (googleServices != null) {
                    googleServices.uploadLogData(zipFile);
                    TimeUnit.MILLISECONDS.sleep(500);
                    FileUtils.deleteQuietly(folder);
                    Timber.d("Uploaded zip file to Google Drive");
                } else {
                    Timber.d("GOOGLE SERVICES IS NULL");
                }
            } catch (Exception e) {
                Timber.e(e, "Error creating or uploading zip file");
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onError("Error finalizing recording: " + e.getMessage());
                    }
                });
            }
        });
    }

    /**
     * Processes a frame from ARCore, extracting and saving color and point cloud data.
     *
     * @param frame The ARCore frame to process
     */
    public void processFrame(Frame frame) {
        // Store the latest frame for camera intrinsics (even if not recording)
        latestFrame = frame;

        // Skip if not recording or paused
        if (!isRecording || isPaused) {
            return;
        }

        // Skip if already processing a frame
        if (isProcessingFrame.getAndSet(true)) {
            return;
        }

        // Increment frame counter
        frameCounter++;

        // Only process every Nth frame to reduce computational load
        if (frameCounter % FRAME_SKIP_COUNT != 0) {
            isProcessingFrame.set(false);
            return;
        }

        // Acquire images immediately while we have the latest frame
        Image cameraImage = null;
        PointCloud pointCloud = null;

        try {
            // Try to acquire camera image from the frame
            try {
                cameraImage = frame.acquireCameraImage();
            } catch (Exception e) {
                Timber.w("Failed to acquire camera image: %s", e.getMessage());
            }

            try {
                pointCloud = frame.acquirePointCloud();
            } catch (Exception e) {
                Timber.w("Failed to acquire point cloud: %s", e.getMessage());
            }

            // If we couldn't get any images, skip this frame
            if (cameraImage == null) {
                Timber.w("Could not acquire any images from frame, skipping");
                if (pointCloud != null) pointCloud.release();
                isProcessingFrame.set(false);
                return;
            }

            // Get camera pose
            Pose cameraPose = frame.getCamera().getPose();

            // Convert image to bitmap immediately while we have the frame
            Bitmap bitmap = null;
            try {
                bitmap = ImageUtils.imageToBitmap(cameraImage);
                // Close camera image as soon as we've converted it
                cameraImage.close();
                cameraImage = null;
            } catch (Exception e) {
                Timber.w("Failed to convert image to bitmap: %s", e.getMessage());
                if (cameraImage != null) {
                    cameraImage.close();
                    cameraImage = null;
                }
            }

            // Store final references to be used in the executor
            final Bitmap finalBitmap = bitmap;
            final Pose finalCameraPose = cameraPose;
            final PointCloud finalPointCloud = pointCloud;

            // Process frame in background
            executor.execute(() -> {
                try {
                    // Get the current frame number for file naming
                    final int currentFrameNumber = savedFrameCount++;

                    // Save pose data
                    PoseData poseData = new PoseData(currentFrameNumber, finalCameraPose);
                    poseDataList.add(poseData);

                    // Save color image if available
                    if (finalBitmap != null) {
                        saveColorImage(finalBitmap, currentFrameNumber);
                    }

                    // Save point cloud data directly from frame
                    if (finalPointCloud != null) {
                        savePointCloudData(finalPointCloud, finalCameraPose, currentFrameNumber);
                    }

                    // Notify on main thread
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onFrameProcessed(savedFrameCount);
                        }
                    });

                } catch (Exception e) {
                    Timber.e(e, "Error processing frame");
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onError("Error processing frame: " + e.getMessage());
                        }
                    });
                } finally {
                    // Clean up resources
                    try {
                        if (finalBitmap != null) finalBitmap.recycle();
                        if (finalPointCloud != null) finalPointCloud.release();
                    } catch (Exception e) {
                        Timber.e(e, "Error closing resources");
                    }

                    isProcessingFrame.set(false);
                }
            });
        } catch (Exception e) {
            // Clean up resources if an exception occurs
            if (cameraImage != null) cameraImage.close();
            if (pointCloud != null) pointCloud.release();

            Timber.e(e, "Error acquiring images from frame");
            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onError("Error acquiring images: " + e.getMessage());
                }
            });
            isProcessingFrame.set(false);
        }
    }

    /**
     * Saves the color image as JPEG.
     *
     * @param bitmap The bitmap to save
     * @param frameNumber The current frame number
     */
    private void saveColorImage(Bitmap bitmap, int frameNumber) {
        try {
            if (bitmap == null) {
                Timber.w("No bitmap available");
                return;
            }

            // Save as JPEG with frame_X naming convention
            String imageFilePath = recordingFolder + File.separator + "images" +
                    File.separator + "frame_" + frameNumber + ".jpg";

            try (FileOutputStream fos = new FileOutputStream(imageFilePath)) {
                // Compress to JPEG with 90% quality
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.flush();
            }

        } catch (Exception e) {
            Timber.e(e, "Error saving color image: %s", e.getMessage());
        }
    }

    /**
     * Saves the point cloud data directly from the ARCore frame.
     * If an origin pose is available, saves points in the local coordinate system.
     * Otherwise, saves points in the world coordinate system.
     *
     * @param pointCloud The ARCore point cloud
     * @param cameraPose The camera pose
     * @param frameNumber The current frame number
     */
    private void savePointCloudData(PointCloud pointCloud, Pose cameraPose, int frameNumber) {
        try {
            // Get point cloud data
            FloatBuffer pointBuffer = pointCloud.getPoints();
            int numPoints = pointBuffer.remaining() / 4; // Each point has x,y,z,confidence

            if (numPoints == 0) {
                Timber.w("Point cloud is empty");
                return;
            }

            boolean hasLocalCoordinates = (originPose != null);

            // If we don't have an origin pose, log a warning and return
            if (!hasLocalCoordinates) {
                Timber.w("No origin pose available for local coordinate transformation, skipping point cloud save");
                return;
            }

            // Create list to store transformed points
            List<float[]> localPoints = new ArrayList<>();

            // Camera-to-world transform (column-major OpenGL)
            float[] cameraToWorld = new float[16];
            cameraPose.toMatrix(cameraToWorld, 0);

            // Origin-to-camera transform for local coordinates
            float[] originToCamera = new float[16];
            // Calculate the relative pose (transform from origin to camera)
            Pose relativePose = originPose.inverse().compose(cameraPose);
            relativePose.toMatrix(originToCamera, 0);

            // Process each point
            float[] point = new float[4];
            float[] localPoint = new float[4];

            for (int i = 0; i < numPoints; i++) {
                // Read point (x, y, z, confidence)
                point[0] = pointBuffer.get(i * 4);
                point[1] = pointBuffer.get(i * 4 + 1);
                point[2] = pointBuffer.get(i * 4 + 2);
                point[3] = 1.0f; // Homogeneous coordinate

                float confidence = pointBuffer.get(i * 4 + 3);

                // Skip points with very low confidence
                if (confidence < 0.1f) {
                    continue;
                }

                // Transform directly from camera space to local space
                Matrix.multiplyMV(localPoint, 0, originToCamera, 0, point, 0);

                // Create the local point with the same confidence
                float[] localPointWithConfidence = new float[] {
                    localPoint[0],  // x
                    localPoint[1],  // y
                    localPoint[2],  // z
                    confidence      // confidence
                };

                // Add to local points list
                localPoints.add(localPointWithConfidence);
            }

            // Store local points in memory (for compatibility with existing code)
            allPointClouds.add(localPoints);

            // Make sure the parent directory exists
            File file = new File(pointCloudFile);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Append to the point cloud file (now containing local coordinates)
            appendToPointCloudFile(localPoints, frameNumber);

        } catch (Exception e) {
            Timber.e(e, "Error saving point cloud data: %s", e.getMessage());
        }
    }

    /**
     * Appends point cloud data to the single text file using COLMAP format.
     * COLMAP format: POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)
     *
     * Note: Points are in the local coordinate system relative to the origin pose.
     * The coordinate system convention is still ARCore (OpenGL):
     * - +X points to the right
     * - +Y points upward
     * - −Z points forward (in the view direction)
     *
     * @param points List of points to append (in local coordinate system)
     * @param frameNumber The current frame number
     */
    private void appendToPointCloudFile(List<float[]> points, int frameNumber) {
        try {
            File file = new File(pointCloudFile);
            boolean fileExists = file.exists();

            // Create parent directory if it doesn't exist
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            try (FileWriter writer = new FileWriter(file, true)) {
                // Write header if file is new
                if (!fileExists) {
                    writer.write("# 3D point list with one line of data per point:\n");
                    writer.write("# Points are in local coordinate system relative to the origin pose\n");
                    writer.write("# POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)\n");
                }

                // Generate a base point ID for this frame to avoid ID collisions
                int basePointId = frameNumber * 1000000;

                // Write each point in COLMAP format
                for (int i = 0; i < points.size(); i++) {
                    float[] point = points.get(i);
                    int pointId = basePointId + i;

                    // Extract position and confidence
                    float x = point[0];
                    float y = point[1];
                    float z = point[2];
                    float confidence = point[3];

                    // Default RGB values (white)
                    int r = 255;
                    int g = 255;
                    int b = 255;

                    // If point has color information (length > 4), use it
                    if (point.length > 6) {
                        r = Math.round(point[3] * 255);
                        g = Math.round(point[4] * 255);
                        b = Math.round(point[5] * 255);
                    }

                    // Use confidence as error (lower is better, so invert)
                    float error = Math.max(0.1f, 1.0f - confidence);

                    // Write in COLMAP format: POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[]
                    // For simplicity, we'll use a minimal track with just the current frame
                    writer.write(String.format(Locale.US,
                        "%d %.6f %.6f %.6f %d %d %d %.6f %d %d\n",
                        pointId,     // POINT3D_ID
                        x, y, z,     // X, Y, Z
                        r, g, b,     // R, G, B
                        error,       // ERROR
                        frameNumber, // IMAGE_ID in TRACK
                        i            // POINT2D_IDX in TRACK
                    ));
                }
            }

            Timber.d("Saved %d points to %s in local coordinate system", points.size(), pointCloudFile);
        } catch (IOException e) {
            Timber.e(e, "Error appending to point cloud file: %s", e.getMessage());
        }
    }





    /**
     * Saves the pose data to a COLMAP format images.txt file.
     * Format: IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME
     *         POINTS2D[] as (X, Y, POINT3D_ID)
     *
     * Note: Poses are saved in the local coordinate system relative to the origin pose.
     * The coordinate system convention is still ARCore (OpenGL):
     * - +X points to the right
     * - +Y points upward
     * - −Z points forward (in the view direction)
     *
     * @param frame The ARCore frame to extract camera intrinsics from (can be null)
     */
    private void savePoseData(Frame frame) {
        try {
            // First, save the camera intrinsics
            saveCameraData(frame);

            // Check if we have an origin pose
            if (originPose == null) {
                Timber.w("No origin pose available for local coordinate transformation, skipping pose data save");
                return;
            }

            // Then save the image poses
            File file = new File(imagesFile);
            boolean fileExists = file.exists();

            // Create parent directory if it doesn't exist
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Count how many poses have local coordinates
            int validPoseCount = 0;
            for (PoseData poseData : poseDataList) {
                if (poseData.hasLocalCoordinates) {
                    validPoseCount++;
                }
            }

            try (FileWriter writer = new FileWriter(file)) {
                // Write header
                writer.write("# Image list with two lines of data per image:\n");
                writer.write("# Poses are in local coordinate system relative to the origin pose\n");
                writer.write("#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n");
                writer.write("#   POINTS2D[] as (X, Y, POINT3D_ID)\n");
                writer.write(String.format("# Number of images: %d\n", validPoseCount));

                // Write each local pose
                for (PoseData poseData : poseDataList) {
                    if (poseData.hasLocalCoordinates) {
                        int imageId = poseData.frameNumber;
                        float[] localRotation = poseData.localRotation;
                        float[] localTranslation = poseData.localTranslation;

                        // COLMAP format: IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME
                        // Note: ARCore quaternion is [x, y, z, w] but COLMAP expects [w, x, y, z]
                        writer.write(String.format(Locale.US,
                            "%d %.9f %.9f %.9f %.9f %.9f %.9f %.9f 1 frame_%d.jpg\n",
                            imageId,
                            localRotation[3],  // QW (ARCore stores as [x,y,z,w])
                            localRotation[0],  // QX
                            localRotation[1],  // QY
                            localRotation[2],  // QZ
                            localTranslation[0], // TX
                            localTranslation[1], // TY
                            localTranslation[2], // TZ
                            imageId
                        ));

                        // Add placeholder keypoints with "0 0 -1" as requested
                        // This indicates a keypoint at (0,0) with no associated 3D point
                        writer.write("0 0 -1\n");
                    }
                }
            }

            Timber.d("Saved %d local pose data entries to %s in COLMAP format", validPoseCount, imagesFile);

        } catch (IOException e) {
            Timber.e(e, "Error saving pose data in COLMAP format: %s", e.getMessage());
        }
    }

    /**
     * Saves the camera intrinsics to a COLMAP format cameras.txt file.
     * Format: CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]
     *
     * Note: Camera intrinsics are from ARCore and are in the standard computer vision convention:
     * - Origin at top-left of image
     * - X axis points right
     * - Y axis points down
     *
     * @param frame The ARCore frame to extract camera intrinsics from (can be null)
     */
    private void saveCameraData(Frame frame) {
        try {
            File file = new File(camerasFile);

            // Create parent directory if it doesn't exist
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Default camera parameters in case we can't get them from ARCore
            int width = 480;  // Standard HD width
            int height = 640; // Standard HD height
            float fx = 480; // Approximate focal length (in pixels)
            float fy = 480; // Approximate focal length (in pixels)
            float cx = width / 2.0f;  // Principal point x (center of image)
            float cy = height / 2.0f; // Principal point y (center of image)

            // Try to get actual camera intrinsics from ARCore
            if (frame != null) {
                try {
                    com.google.ar.core.Camera camera = frame.getCamera();
                    com.google.ar.core.CameraIntrinsics intrinsics = camera.getImageIntrinsics();

                    // Get image dimensions
                    Timber.d("Image dimensions: " + Arrays.toString(intrinsics.getImageDimensions()));
                    int[] dimensions = intrinsics.getImageDimensions();
                    width = dimensions[1];
                    height = dimensions[0];

                    // Get focal length
                    float[] focalLength = intrinsics.getFocalLength();
                    fx = focalLength[0];
                    fy = focalLength[1];

                    // Get principal point
                    float[] principalPoint = intrinsics.getPrincipalPoint();
                    cx = principalPoint[0];
                    cy = principalPoint[1];

                    Timber.d("Using actual camera intrinsics: width=%d, height=%d, fx=%.2f, fy=%.2f, cx=%.2f, cy=%.2f",
                            width, height, fx, fy, cx, cy);
                } catch (Exception e) {
                    Timber.w("Failed to get camera intrinsics from ARCore, using defaults: %s", e.getMessage());
                }
            } else {
                Timber.w("No ARCore frame available, using default camera intrinsics");
            }

            try (FileWriter writer = new FileWriter(file)) {
                // Write header
                writer.write("# Camera list with one line of data per camera:\n");
                writer.write("#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n");
                writer.write("# Number of cameras: 1\n");

                // COLMAP's PINHOLE model has 4 parameters: fx, fy, cx, cy
                writer.write(String.format(Locale.US,
                    "1 PINHOLE %d %d %.9f %.9f %.9f %.9f\n",
                    width, height, fx, fy, cx, cy
                ));
            }

            Timber.d("Saved camera data to %s in COLMAP format", camerasFile);

        } catch (IOException e) {
            Timber.e(e, "Error saving camera data in COLMAP format: %s", e.getMessage());
        }
    }


    /**
     * Class to store pose data for each frame.
     */
    private class PoseData {
        private final int frameNumber;
        private final float[] translation;
        private final float[] rotation;
        private final float[] localTranslation;
        private final float[] localRotation;
        private final boolean hasLocalCoordinates;

        public PoseData(int frameNumber, Pose pose) {
            this.frameNumber = frameNumber;

            // Get translation (x, y, z) directly from ARCore pose
            this.translation = new float[3];
            pose.getTranslation(this.translation, 0);

            // Get rotation quaternion (x, y, z, w) directly from ARCore pose
            this.rotation = new float[4];
            pose.getRotationQuaternion(this.rotation, 0);

            // Calculate local coordinates if we have an origin pose
            if (originPose != null) {
                // Calculate the relative pose (transform from origin to camera)
                Pose relativePose = originPose.inverse().compose(pose);

                // Extract local translation
                this.localTranslation = new float[3];
                relativePose.getTranslation(this.localTranslation, 0);

                // Extract local rotation
                this.localRotation = new float[4];
                relativePose.getRotationQuaternion(this.localRotation, 0);

                this.hasLocalCoordinates = true;

                Timber.d("Frame %d local coordinates: translation=[%.3f, %.3f, %.3f], rotation=[%.3f, %.3f, %.3f, %.3f]",
                        frameNumber,
                        localTranslation[0], localTranslation[1], localTranslation[2],
                        localRotation[0], localRotation[1], localRotation[2], localRotation[3]);
            } else {
                // No local coordinates available
                this.localTranslation = new float[3];
                this.localRotation = new float[4];
                this.hasLocalCoordinates = false;
            }
        }
    }

    /**
     * Cleans up resources used by the manager.
     */
    public void cleanup() {
        executor.shutdown();
    }



    /**
     * @return True if currently recording, false otherwise
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * @return True if recording is paused, false otherwise
     */
    public boolean isPaused() {
        return isPaused;
    }

}
