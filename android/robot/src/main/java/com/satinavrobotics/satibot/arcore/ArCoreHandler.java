package com.satinavrobotics.satibot.arcore;

import android.content.Context;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Size;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Config.InstantPlacementMode;
import com.google.ar.core.Config.LightEstimationMode;
import com.google.ar.core.Config.PlaneFindingMode;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.SessionPausedException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.firebase.firestore.FirebaseFirestore;

import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.mapManagement.Map;
import com.satinavrobotics.satibot.mapManagement.MapResolvingManager;

import org.json.JSONException;
import org.json.JSONObject;

import com.satinavrobotics.satibot.arcore.rendering.DisplayRotationHelper;
import com.satinavrobotics.satibot.arcore.rendering.ARCoreRenderer;
import com.satinavrobotics.satibot.arcore.processor.ArCoreProcessor;
import com.satinavrobotics.satibot.arcore.processor.DefaultArCoreProcessor;
import com.satinavrobotics.satibot.arcore.rendering.LiveKitARCoreRenderer;
import com.satinavrobotics.satibot.arcore.rendering.NullARCoreRenderer;
import com.satinavrobotics.satibot.arcore.rendering.SimpleARCoreRenderer;

import livekit.org.webrtc.JavaI420Buffer;
import livekit.org.webrtc.VideoFrame;
import timber.log.Timber;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A merged class that combines ARCore navigation and video capture.
 *
 * This class:
 * <ul>
 *   <li>Implements the same public interface as your original ArCore class:
 *       <code>resume(), pause(), closeSession(), detachAnchors(), getStartPose(), setStartAnchorAtCurrentPose(), getTargetPose(), setTargetAnchor(Pose), setTargetAnchorAtCurrentPose(), setArCoreListener(), removeArCoreListener(), isGeospatialModeAvailable(), getGeospatialPose()</code>.</li>
 *   <li>Implements VideoCapturer so that it can capture frames from ARCore for WebRTC (by converting images to I420).</li>
 *   <li>Uses a single GLSurfaceView.Renderer to drive both ARCore updates and video capture.</li>
 *   <li>Uses an ArCoreProcessor to process frames before rendering.</li>
 * </ul>
 *
 * External helper classes such as ImageFrame, NavigationPoses, CameraIntrinsics, and ArCoreListener
 * must be provided.
 */
public class ArCoreHandler implements GLSurfaceView.Renderer {

    // ===== ARCore Fields =====
    private Session session;
    private Camera camera;
    private final DisplayRotationHelper displayRotationHelper;
    private final Context context;
    private final GLSurfaceView glSurfaceView;
    private float gpuTextureAspectRatio = 16.0f / 9.0f;
    private final Handler handlerMain;
    private ArCoreListener arCoreListener = null;
    private Pose currentPose;

    // Renderer and Processor
    private ARCoreRenderer renderer;
    private ArCoreProcessor processor;
    private boolean renderingEnabled = true;

    /**
     * Constructor with default renderer.
     */
    public ArCoreHandler(Context context, GLSurfaceView glSurfaceView, Handler handlerMain) {
        this(context, glSurfaceView, handlerMain, true);
    }

    /**
     * Renderer type options
     */
    public enum RendererType {
        NULL,       // No rendering
        SIMPLE,     // Basic rendering
        LIVEKIT     // Optimized for streaming
    }

    /**
     * Constructor with option to enable/disable rendering.
     */
    public ArCoreHandler(Context context, GLSurfaceView glSurfaceView, Handler handlerMain, boolean renderingEnabled) {
        this(context, glSurfaceView, handlerMain, renderingEnabled ? RendererType.LIVEKIT : RendererType.NULL);
    }

    /**
     * Constructor with specific renderer type.
     */
    public ArCoreHandler(Context context, GLSurfaceView glSurfaceView, Handler handlerMain, RendererType rendererType) {
        this.context = context;
        this.glSurfaceView = glSurfaceView;
        this.handlerMain = handlerMain;
        this.preferencesManager = new SharedPreferencesManager(context);
        this.db = FirebaseFirestore.getInstance();
        this.renderingEnabled = rendererType != RendererType.NULL;

        // Set up the appropriate renderer
        switch (rendererType) {
            case SIMPLE:
                this.renderer = new SimpleARCoreRenderer();
                break;
            case LIVEKIT:
                this.renderer = new LiveKitARCoreRenderer();
                break;
            case NULL:
            default:
                this.renderer = new NullARCoreRenderer();
                break;
        }

        // Set up the default processor
        this.processor = new DefaultArCoreProcessor();

        // Set up GLSurfaceView
        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setEGLContextClientVersion(3);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.setRenderer(this);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glSurfaceView.setWillNotDraw(false);
        displayRotationHelper = new DisplayRotationHelper(context);
    }

    /**
     * Set a custom renderer.
     *
     * @param renderer The renderer to use
     */
    public void setRenderer(ARCoreRenderer renderer) {
        this.renderer = renderer;
        this.renderingEnabled = !(renderer instanceof NullARCoreRenderer);
    }

    /**
     * Set a custom processor.
     *
     * @param processor The processor to use
     */
    public void setProcessor(ArCoreProcessor processor) {
        this.processor = processor;
    }

    /**
     * Enable or disable rendering.
     *
     * @param enabled Whether rendering should be enabled
     */
    public void setRenderingEnabled(boolean enabled) {
        setRendererType(enabled ? RendererType.LIVEKIT : RendererType.NULL);
    }

    /**
     * Set the renderer type.
     *
     * @param rendererType The type of renderer to use
     */
    public void setRendererType(RendererType rendererType) {
        this.renderingEnabled = rendererType != RendererType.NULL;

        switch (rendererType) {
            case SIMPLE:
                this.renderer = new SimpleARCoreRenderer();
                break;
            case LIVEKIT:
                this.renderer = new LiveKitARCoreRenderer();
                break;
            case NULL:
            default:
                this.renderer = new NullARCoreRenderer();
                break;
        }
    }


    private final SharedPreferencesManager preferencesManager;
    private final FirebaseFirestore db;
    private final MapResolvingManager mapResolvingManager = new MapResolvingManager();
    private boolean isResolvingAnchors = false;
    private int resolvedAnchorsCount = 0;
    private int totalAnchorsToResolve = 0;
    private Map currentMap;

    /**
     * Interface for receiving anchor resolution updates
     */
    public interface AnchorResolutionListener {
        /**
         * Called when anchor resolution status changes
         * @param resolvedCount Number of anchors resolved so far
         * @param totalCount Total number of anchors to resolve
         */
        void onAnchorResolutionUpdate(int resolvedCount, int totalCount);
    }

    private AnchorResolutionListener anchorResolutionListener;


    // ===== ARCore Public Methods (Interface same as original ArCore class) =====

    /**
     * Resumes the ARCore session.
     */
    public void resume() throws UnavailableSdkTooOldException, UnavailableDeviceNotCompatibleException,
            UnavailableArcoreNotInstalledException, UnavailableApkTooOldException, CameraNotAvailableException {
        session = new Session(context);
        setConfig();

        setCameraConfig();
        session.resume();
        glSurfaceView.onResume();
        displayRotationHelper.onResume();

        // Load and resolve map anchors
        loadSelectedMap();
    }

    public void pause() {
        if (session != null) {
            try {
                // First pause the display rotation helper and GLSurfaceView
                displayRotationHelper.onPause();
                glSurfaceView.onPause();

                // Then pause the session with error handling
                try {
                    session.pause();
                } catch (Exception e) {
                    // Catch and log any exceptions during session pause
                    Timber.e(e, "Error pausing ARCore session");
                }

                // Clean up map resolving manager
                mapResolvingManager.clear();
                isResolvingAnchors = false;
            } catch (Exception e) {
                // Catch any other exceptions during the entire pause process
                Timber.e(e, "Exception during ARCore pause");
            }
        }
    }



    public void closeSession() {
        closeSession(null);
    }

    public void closeSession(Runnable onComplete) {
        if (session != null) {
            runOnMainThread(() -> {
                try {
                    // First pause the session
                    pause();
                    mapResolvingManager.clear();

                    // Close the session with error handling
                    if (session != null) {
                        try {
                            session.close();
                        } catch (Exception e) {
                            // Catch and log any exceptions during session close
                            Timber.e(e, "Error closing ARCore session");
                        } finally {
                            session = null;
                        }
                    }
                } catch (Exception e) {
                    // Catch any other exceptions during the entire close process
                    Timber.e(e, "Exception during ARCore session cleanup");
                    session = null;
                } finally {
                    // Always call the completion callback if provided
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            });
        } else {
            // Session is already null, call completion callback immediately
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    public void setArCoreListener(ArCoreListener listener) {
        this.arCoreListener = listener;
    }
    public void removeArCoreListener() {this.arCoreListener = null;}

    public Session getSession() {
        return session;
    }

    /**
     * Sets the anchor resolution listener
     * @param listener The listener to receive anchor resolution updates
     */
    public void setAnchorResolutionListener(AnchorResolutionListener listener) {
        this.anchorResolutionListener = listener;
    }

    /**
     * Gets the current map
     * @return The current map
     */
    public Map getCurrentMap() {
        return currentMap;
    }

    /**
     * Gets the number of resolved anchors
     * @return The number of resolved anchors
     */
    public int getResolvedAnchorsCount() {
        return resolvedAnchorsCount;
    }

    /**
     * Gets the total number of anchors to resolve
     * @return The total number of anchors to resolve
     */
    public int getTotalAnchorsToResolve() {
        return totalAnchorsToResolve;
    }

    /**
     * Gets the list of resolved anchors
     * @return The list of resolved anchors
     */
    public List<MapResolvingManager.ResolvedAnchor> getResolvedAnchors() {
        return mapResolvingManager.getResolvedAnchors();
    }


    // ===== GLSurfaceView.Renderer Methods =====
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Initialize the renderer
        renderer.onSurfaceCreated(gl, config, context);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        renderer.onSurfaceChanged(gl, width, height);

        // Don't modify the SurfaceView layout parameters to allow it to fill the screen
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        if (session == null) {
            return;
        }

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper.updateSessionIfNeeded(session);

        // Set the camera texture name from the renderer
        session.setCameraTextureName(renderer.getBackgroundTextureId());

        // Obtain the current frame from ARSession
        Frame frame;
        try {
            frame = session.update();
        } catch (CameraNotAvailableException e) {
            Timber.d(e, "ARCore camera not available.");
            runOnMainThread(() -> {
                if (arCoreListener != null) {
                    arCoreListener.onArCoreTrackingFailure(
                            SystemClock.elapsedRealtimeNanos(),
                            TrackingFailureReason.CAMERA_UNAVAILABLE);
                }
            });
            return;
        } catch (SessionPausedException e) {
            Timber.d(e, "ARCore session paused.");
            runOnMainThread(() -> {
                if (arCoreListener != null) {
                    arCoreListener.onArCoreSessionPaused(SystemClock.elapsedRealtimeNanos());
                }
            });
            return;
        }

        camera = frame.getCamera();
        long timestamp = SystemClock.elapsedRealtimeNanos();

        TrackingState trackingState = camera.getTrackingState();
        // === ARCore Navigation Updates ===
        if (trackingState != TrackingState.TRACKING) {
            Timber.d("ARCore is not tracking.");
            TrackingFailureReason failureReason = camera.getTrackingFailureReason();
            runOnMainThread(() -> {
                if (arCoreListener != null) {
                    arCoreListener.onArCoreTrackingFailure(timestamp, failureReason);
                }
            });
        } else {
            currentPose = camera.getPose();

            // Get image.
            // TODO: If needed, we could possibly implement some performance optimization here:
            // Do we really need to copy here? We could use several cycling textures instead of only
            // 1 texture. This would allow to close the image faster.
            Image image = null;
            try {
                image = frame.acquireCameraImage();
            } catch (NotYetAvailableException e) {
                Timber.d(e, "ARCore image not available.");
            }

            // Send arcore data
            if (image != null) {
                // TODO: We could use another data structure here to avoid additional copying later.
                // (Important: We need to be fast here such that `image` is closed as fast
                // as possible. Hence, we currently use ByteBuffer.get() and .put() to copy in a fast way.
                // Is there a faster way?)
                // --- ARCore Navigation: Notify listener with pose, image, and intrinsics ---
                ImageFrame imageFrame = new ImageFrame(image);
                runOnMainThread(() -> {
                            if (arCoreListener != null) {
                                arCoreListener.onArCoreUpdate(
                                        currentPose,
                                        imageFrame,
                                        new CameraIntrinsics(camera.getImageIntrinsics()),
                                        timestamp);
                            }});
                image.close();
            }
         }

        // === Rendering (Preview & Overlays) ===
        if (renderingEnabled) {
            // Process the frame before rendering
            ArCoreProcessor.ProcessedFrameData processedData = processor.update(
                frame,
                camera,
                mapResolvingManager.getResolvedAnchors()
            );

            // Draw the frame using the renderer with processed data
            renderer.drawFrame(processedData);

            // Now read pixels if needed (after all rendering is done)
            ByteBuffer rgbaBuffer = renderer.readPixels();
            // If we got a buffer, convert and send it
            if (rgbaBuffer != null) {
                runOnMainThread(() -> {
                    if (arCoreListener != null) {
                        try {
                            VideoFrame.I420Buffer i420Buffer = toI420Buffer(rgbaBuffer, renderer.getWidth(), renderer.getHeight());
                            if (i420Buffer != null) {
                                arCoreListener.onRenderedFrame(i420Buffer, timestamp);
                            } else {
                                Timber.w("Failed to convert RGBA buffer to I420 format");
                            }
                        } catch (Exception e) {
                            Timber.e(e, "Error processing rendered frame");
                        }
                    }
                });
            }
        }
    }

    /**
     * Converts the RGBA data in rgbaBuffer into an I420 buffer.
     *
     * @return a VideoFrame.I420Buffer containing the converted data.
     */
    public VideoFrame.I420Buffer toI420Buffer(ByteBuffer rgbaBuffer, int width, int height) {
        // Validate input parameters
        if (rgbaBuffer == null) {
            Timber.e("RGBA buffer is null");
            return null;
        }

        if (width <= 0 || height <= 0) {
            Timber.e("Invalid dimensions: %dx%d", width, height);
            return null;
        }

        // Make sure the buffer has enough data
        int requiredBytes = width * height * 4;
        if (rgbaBuffer.capacity() < requiredBytes) {
            Timber.e("Buffer too small: capacity=%d, required=%d", rgbaBuffer.capacity(), requiredBytes);
            return null;
        }

        // Allocate an I420 buffer of the same dimensions.
        VideoFrame.I420Buffer i420Buffer = JavaI420Buffer.allocate(width, height);

        // --- Get plane buffers and strides ---
        ByteBuffer i420Y = i420Buffer.getDataY();
        int strideY = i420Buffer.getStrideY();
        ByteBuffer i420U = i420Buffer.getDataU();
        int strideU = i420Buffer.getStrideU();
        ByteBuffer i420V = i420Buffer.getDataV();
        int strideV = i420Buffer.getStrideV();

        // Copy the RGBA data into a byte array.
        byte[] rgbaData = new byte[width * height * 4];

        // Reset position to ensure we read from the beginning
        rgbaBuffer.position(0);

        try {
            rgbaBuffer.get(rgbaData);
        } catch (Exception e) {
            Timber.e(e, "Error reading from RGBA buffer");
            return null;
        }

        // --- Convert Y plane with vertical flip ---
        // For output row r (0 is top), use source row = (height - 1 - r)
        for (int r = 0; r < height; r++) {
            int srcRow = height - 1 - r;
            for (int c = 0; c < width; c++) {
                int index = (srcRow * width + c) * 4;
                int rVal = rgbaData[index] & 0xFF;
                int gVal = rgbaData[index + 1] & 0xFF;
                int bVal = rgbaData[index + 2] & 0xFF;
                // Compute Y value using standard formula.
                int y = ((66 * rVal + 129 * gVal + 25 * bVal + 128) >> 8) + 16;
                y = Math.min(255, Math.max(0, y));
                i420Y.put(r * strideY + c, (byte) y);
            }
        }

        // --- Convert U and V planes with vertical flip ---
        // Chroma dimensions are half the Y dimensions.
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;

        // For each chroma output pixel at (r, c), average the corresponding 2x2 block
        // from the source image. For vertical flip, compute the source rows as:
        //   srcRow0 = height - 1 - (r*2)
        //   srcRow1 = srcRow0 - 1 (clamped to srcRow0 if negative)
        for (int r = 0; r < chromaHeight; r++) {
            int srcRow0 = height - 1 - (r * 2);
            int srcRow1 = srcRow0 - 1;
            if (srcRow1 < 0) {
                srcRow1 = srcRow0;
            }
            for (int c = 0; c < chromaWidth; c++) {
                int srcCol0 = c * 2;
                int srcCol1 = srcCol0 + 1;
                if (srcCol1 >= width) {
                    srcCol1 = srcCol0;
                }
                int sumU = 0;
                int sumV = 0;
                int count = 0;
                // Process the 2x2 block.
                for (int srcRow : new int[]{srcRow0, srcRow1}) {
                    for (int srcCol : new int[]{srcCol0, srcCol1}) {
                        int index = (srcRow * width + srcCol) * 4;
                        int rVal = rgbaData[index] & 0xFF;
                        int gVal = rgbaData[index + 1] & 0xFF;
                        int bVal = rgbaData[index + 2] & 0xFF;
                        // U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128.
                        int uVal = ((-38 * rVal - 74 * gVal + 112 * bVal + 128) >> 8) + 128;
                        // V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128.
                        int vVal = ((112 * rVal - 94 * gVal - 18 * bVal + 128) >> 8) + 128;
                        sumU += uVal;
                        sumV += vVal;
                        count++;
                    }
                }
                int avgU = sumU / count;
                int avgV = sumV / count;
                avgU = Math.min(255, Math.max(0, avgU));
                avgV = Math.min(255, Math.max(0, avgV));
                i420U.put(r * strideU + c, (byte) avgU);
                i420V.put(r * strideV + c, (byte) avgV);
            }
        }

        return i420Buffer;
    }


    /**
     * Configures the ARCore session.
     */
    private void setConfig() {
        Config config = new Config(session)
                .setAugmentedFaceMode(Config.AugmentedFaceMode.DISABLED)
                .setCloudAnchorMode(Config.CloudAnchorMode.ENABLED)
                .setFocusMode(Config.FocusMode.AUTO)
                .setInstantPlacementMode(InstantPlacementMode.DISABLED)
                .setLightEstimationMode(LightEstimationMode.DISABLED)
                .setPlaneFindingMode(PlaneFindingMode.DISABLED)
                .setUpdateMode(Config.UpdateMode.BLOCKING);

        // Enable depth mode if supported by the device
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
            Timber.d("Depth mode enabled (AUTOMATIC)");
        } else {
            Timber.w("Depth mode not supported on this device");
        }

        session.configure(config);
    }

    /**
     * Selects a suitable camera configuration.
     */
    private void setCameraConfig() throws CameraNotAvailableException {
        CameraConfigFilter cameraConfigFilter = new CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30));

        List<CameraConfig> cameraConfigList = session.getSupportedCameraConfigs(cameraConfigFilter);
        CameraConfig minCameraConfig = null;
        for (CameraConfig cameraConfig : cameraConfigList) {
            Timber.d("available camera config: (CameraId: %s, FacingDirection: %s, FpsRange: %s, ImageSize: %s, TextureSize: %s)",
                    cameraConfig.getCameraId(),
                    cameraConfig.getFacingDirection(),
                    cameraConfig.getFpsRange(),
                    cameraConfig.getImageSize(),
                    cameraConfig.getTextureSize());
            if (!is640x480(cameraConfig.getImageSize())) {
                continue;
            }
            if (minCameraConfig == null) {
                minCameraConfig = cameraConfig;
                continue;
            }
            if (isSmaller(cameraConfig.getTextureSize(), minCameraConfig.getTextureSize())) {
                minCameraConfig = cameraConfig;
            }
        }
        if (minCameraConfig == null) {
            Timber.w("No suitable camera config available.");
            throw new CameraNotAvailableException("No suitable camera config available.");
        }
        Timber.i("selected camera config: (CameraId: %s, FacingDirection: %s, FpsRange: %s, ImageSize: %s, TextureSize: %s)",
                minCameraConfig.getCameraId(),
                minCameraConfig.getFacingDirection(),
                minCameraConfig.getFpsRange(),
                minCameraConfig.getImageSize(),
                minCameraConfig.getTextureSize());
        session.setCameraConfig(minCameraConfig);
        setSurfaceViewAspectRatio(1.0f * minCameraConfig.getTextureSize().getWidth()
                / minCameraConfig.getTextureSize().getHeight());
    }

    private boolean isSmaller(Size size1, Size size2) {
        return (size1.getHeight() * size1.getWidth() < size2.getHeight() * size2.getWidth());
    }

    private boolean is640x480(Size size) {
        return (size.getWidth() == 640 && size.getHeight() == 480);
    }

    private void setSurfaceViewAspectRatio(float aspectRatio) {
        // Just store the aspect ratio but don't modify the SurfaceView layout
        gpuTextureAspectRatio = aspectRatio;
    }

    /**
     * Helper method to post a Runnable on the main thread.
     */
    protected synchronized void runOnMainThread(final Runnable r) {
      new Thread(() -> {
        if (handlerMain != null) {
          handlerMain.post(r);
        }
      }).start();
    }


    private void loadSelectedMap() {
        String mapId = preferencesManager.getCurrentMapId();
        if (mapId == null) {
            Timber.d("No map selected");
            return;
        }

        // Skip if it's the Earth map or No Map option
        if ("earth".equals(mapId)) {
            Timber.d("Earth map selected, skipping anchor resolution");
            return;
        }

        // Skip if it's the No Map option
        if ("no_map".equals(mapId)) {
            Timber.d("No Map option selected, using direct ARCore pose without anchor resolution");
            return;
        }

        db.collection("maps").document(mapId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        currentMap = documentSnapshot.toObject(Map.class);
                        if (currentMap != null) {
                            currentMap.setId(documentSnapshot.getId());
                            if (session != null) {
                                resolveAnchors();
                            }
                        }
                    }
                })
                .addOnFailureListener(e ->
                    Timber.e(e, "Error loading map")
                );
    }

    private void resolveAnchors() {
        if (currentMap == null || currentMap.getAnchors() == null || currentMap.getAnchors().isEmpty()) {
            Timber.d("No anchors to resolve");
            return;
        }

        if (isResolvingAnchors) {
            return;
        }

        isResolvingAnchors = true;
        resolvedAnchorsCount = 0;
        totalAnchorsToResolve = currentMap.getAnchors().size();

        // Notify listener about initial state
        if (anchorResolutionListener != null) {
            runOnMainThread(() ->
                anchorResolutionListener.onAnchorResolutionUpdate(
                    resolvedAnchorsCount, totalAnchorsToResolve));
        }

        // Set session in manager
        mapResolvingManager.setSession(session);

        // Create cloud anchor resolve listener
        MapResolvingManager.CloudAnchorResolveListener listener =
                (cloudAnchorId, anchor, cloudAnchorState) -> {
                    if (cloudAnchorState == Anchor.CloudAnchorState.SUCCESS && anchor != null) {
                        resolvedAnchorsCount++;
                        // Debug message
                        Timber.d("Resolved anchor %d/%d", resolvedAnchorsCount, totalAnchorsToResolve);

                        // Notify listener if available
                        if (anchorResolutionListener != null) {
                            runOnMainThread(() ->
                                anchorResolutionListener.onAnchorResolutionUpdate(
                                    resolvedAnchorsCount, totalAnchorsToResolve));
                        }
                    } else if (cloudAnchorState.isError()) {
                        Timber.e("Error resolving anchor %s: %s", cloudAnchorId, cloudAnchorState);
                    }
                };

        // Resolve all anchors
        List<String> cloudAnchorIds = new ArrayList<>();
        for (Map.Anchor anchor : currentMap.getAnchors()) {
            cloudAnchorIds.add(anchor.getCloudAnchorId());
        }
        mapResolvingManager.resolveCloudAnchors(cloudAnchorIds, listener);
    }

    /**
     * Check if depth mode is supported by the current session.
     *
     * @return true if depth mode is supported, false otherwise
     */
    public boolean isDepthModeSupported() {
        if (session == null) {
            return false;
        }
        return session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
    }

    /**
     * Clean up resources when the handler is no longer needed.
     */
    public void cleanup() {
        if (renderer != null) {
            renderer.cleanup();
        }
    }



    /**
     * Computes the origin pose from the closest resolved anchor.
     * This is used when the true origin anchor has not been resolved yet.
     *
     * @return The computed origin pose, or null if it couldn't be computed
     */
    public Pose computeOriginPose() {
        if (currentMap == null || currentMap.getAnchors() == null || mapResolvingManager == null) {
            return null;
        }

        List<MapResolvingManager.ResolvedAnchor> resolvedAnchors = mapResolvingManager.getResolvedAnchors();
        if (resolvedAnchors.isEmpty()) {
            return null;
        }

        // First, try to find the origin anchor (the one with local coordinates 0,0,0)
        String originAnchorId = null;
        for (Map.Anchor mapAnchor : currentMap.getAnchors()) {
            if (mapAnchor.getLocalX() == 0 && mapAnchor.getLocalY() == 0 && mapAnchor.getLocalZ() == 0) {
                originAnchorId = mapAnchor.getCloudAnchorId();
                Timber.d("Found origin anchor with ID: %s", originAnchorId);
                break;
            }
        }

        // If we found the origin anchor ID, get its resolved anchor and pose
        if (originAnchorId != null) {
            MapResolvingManager.ResolvedAnchor resolvedOriginAnchor =
                    mapResolvingManager.getResolvedAnchor(originAnchorId);

            if (resolvedOriginAnchor != null) {
                Pose originPose = resolvedOriginAnchor.getAnchor().getPose();
                Timber.d("Using origin anchor (ID: %s) for local coordinate system with pose: %s",
                        originAnchorId, originPose);
                return originPose;
            } else {
                Timber.w("Origin anchor (ID: %s) was not successfully resolved", originAnchorId);
            }
        }

        // If we couldn't find the origin anchor or it wasn't resolved, find the closest anchor to the origin
        Map.Anchor closestAnchor = null;
        double minDistance = Double.MAX_VALUE;

        for (Map.Anchor mapAnchor : currentMap.getAnchors()) {
            // Skip anchors that are at the origin (they should have been found earlier)
            if (mapAnchor.getLocalX() == 0 && mapAnchor.getLocalY() == 0 && mapAnchor.getLocalZ() == 0) {
                continue;
            }

            // Calculate distance to origin
            double distance = mapAnchor.distanceToOrigin();
            if (distance < minDistance) {
                // Find the corresponding resolved anchor
                MapResolvingManager.ResolvedAnchor resolvedAnchor =
                        mapResolvingManager.getResolvedAnchor(mapAnchor.getCloudAnchorId());

                if (resolvedAnchor != null) {
                    minDistance = distance;
                    closestAnchor = mapAnchor;
                }
            }
        }

        // If we found a closest anchor, compute the origin pose from it
        if (closestAnchor != null) {
            MapResolvingManager.ResolvedAnchor resolvedClosestAnchor =
                    mapResolvingManager.getResolvedAnchor(closestAnchor.getCloudAnchorId());

            if (resolvedClosestAnchor != null) {
                // Get the world pose of the closest anchor
                Pose closestAnchorPose = resolvedClosestAnchor.getAnchor().getPose();

                // Create a pose representing the anchor's position in local coordinates
                float[] anchorLocalTranslation = new float[] {
                        (float)closestAnchor.getLocalX(),
                        (float)closestAnchor.getLocalY(),
                        (float)closestAnchor.getLocalZ()
                };

                // Create a pose with the anchor's local orientation
                float[] anchorLocalRotation = new float[] {
                        (float)closestAnchor.getLocalQx(),
                        (float)closestAnchor.getLocalQy(),
                        (float)closestAnchor.getLocalQz(),
                        (float)closestAnchor.getLocalQw()
                };

                // Create the anchor's pose in local coordinates
                Pose anchorInLocalCoords = new Pose(anchorLocalTranslation, anchorLocalRotation);

                // To find the origin in world coordinates, we need to apply the inverse transform:
                // originWorld = anchorWorld * (anchorLocal)^-1
                Pose originPose = closestAnchorPose.compose(anchorInLocalCoords.inverse());

                Timber.d("Computed origin pose from closest anchor (ID: %s) with local coordinates (%f, %f, %f) and orientation (%f, %f, %f, %f)",
                        closestAnchor.getCloudAnchorId(),
                        closestAnchor.getLocalX(), closestAnchor.getLocalY(), closestAnchor.getLocalZ(),
                        closestAnchor.getLocalQx(), closestAnchor.getLocalQy(), closestAnchor.getLocalQz(), closestAnchor.getLocalQw());

                return originPose;
            }
        }

        // If we couldn't compute the origin pose, fall back to the first resolved anchor
        Pose fallbackPose = resolvedAnchors.get(0).getAnchor().getPose();
        Timber.w("Falling back to first resolved anchor as origin with pose: %s", fallbackPose);
        return fallbackPose;
    }

    /**
     * Computes the local coordinates of a pose relative to the origin pose
     *
     * @param pose The pose to convert to local coordinates
     * @param originPose The origin pose to use as reference
     * @return The pose in local coordinates, or null if there's no origin pose
     */
    public Pose computeLocalPose(Pose pose, Pose originPose) {
        if (originPose == null || pose == null) {
            return null;
        }

        // Calculate the relative pose (transform from origin to pose)
        return originPose.inverse().compose(pose);
    }

    /**
     * Creates a JSON object with the local coordinate pose information
     *
     * @param localPose The local pose to convert to JSON
     * @return A JSON object containing the local pose data
     */
    public JSONObject createLocalPoseJson(Pose localPose) {
        JSONObject poseJson = new JSONObject();

        try {
            // Extract translation
            float[] translation = new float[3];
            localPose.getTranslation(translation, 0);

            // Extract rotation as quaternion
            float[] rotation = new float[4];
            localPose.getRotationQuaternion(rotation, 0);

            // Add to JSON
            poseJson.put("x", translation[0]);
            poseJson.put("y", translation[1]);
            poseJson.put("z", translation[2]);
            poseJson.put("qx", rotation[0]);
            poseJson.put("qy", rotation[1]);
            poseJson.put("qz", rotation[2]);
            poseJson.put("qw", rotation[3]);

            // Add map ID for reference
            if (currentMap != null) {
                poseJson.put("mapId", currentMap.getId());
            }

        } catch (JSONException e) {
            Timber.e(e, "Error creating local pose JSON");
        }

        return poseJson;
    }
}

