package org.openbot.robot;

import android.content.Context;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.ViewGroup;

import com.google.ar.core.Anchor;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Config.InstantPlacementMode;
import com.google.ar.core.Config.LightEstimationMode;
import com.google.ar.core.Config.PlaneFindingMode;
import com.google.ar.core.Earth;
import com.google.ar.core.Frame;
import com.google.ar.core.GeospatialPose;
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

import org.openbot.pointGoalNavigation.ArCoreListener;
import org.openbot.pointGoalNavigation.CameraIntrinsics;
import org.openbot.pointGoalNavigation.ImageFrame;
import org.openbot.pointGoalNavigation.NavigationPoses;
import org.openbot.pointGoalNavigation.rendering.BackgroundRenderer;
import org.openbot.pointGoalNavigation.rendering.DisplayRotationHelper;
import org.openbot.pointGoalNavigation.rendering.TwoDRenderer;

import livekit.org.webrtc.JavaI420Buffer;
import livekit.org.webrtc.VideoFrame;
import timber.log.Timber;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final TwoDRenderer twoDRenderer = new TwoDRenderer();
    private float gpuTextureAspectRatio = 16.0f / 9.0f;
    private final Handler handlerMain;
    private ArCoreListener arCoreListener = null;

    // Anchor and pose fields for navigation.
    private Anchor startAnchor, targetAnchor;
    private Pose currentPose;
    private Pose startPose = null, targetPose = null;
    private final float[] anchorMatrix = new float[16];


    // ===== Constructor =====
    public ArCoreHandler(Context context, GLSurfaceView glSurfaceView, Handler handlerMain) {
        this.context = context;
        this.glSurfaceView = glSurfaceView;
        this.handlerMain = handlerMain;

        // Set up the GLSurfaceView (single renderer for ARCore & capture).
        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setEGLContextClientVersion(3);
        // Use an EGL config with alpha for plane blending.
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.setRenderer(this);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glSurfaceView.setWillNotDraw(false);
        displayRotationHelper = new DisplayRotationHelper(context);
    }


    // ===== ARCore Public Methods (Interface same as original ArCore class) =====

    /**
     * Resumes the ARCore session.
     *
     * @throws UnavailableSdkTooOldException
     * @throws UnavailableDeviceNotCompatibleException
     * @throws UnavailableArcoreNotInstalledException
     * @throws UnavailableApkTooOldException
     * @throws CameraNotAvailableException
     */
    public void resume() throws UnavailableSdkTooOldException, UnavailableDeviceNotCompatibleException,
            UnavailableArcoreNotInstalledException, UnavailableApkTooOldException, CameraNotAvailableException {
        session = new Session(context);
        setConfig();
        setCameraConfig();
        session.resume();
        glSurfaceView.onResume();
        displayRotationHelper.onResume();
    }
    public void pause() {
        if (session != null) {
            displayRotationHelper.onPause();
            glSurfaceView.onPause();
            session.pause();
        }
    }

    public void closeSession() {
        if (session != null) {
            runOnMainThread(() -> {
                pause();
                session.close();
            });
        }
    }

    // ===== PointGoalNavigation Methods =====

    public Pose getStartPose() {
        return (startAnchor == null) ? null : startAnchor.getPose();
    }
    public Pose getTargetPose() {
        return (targetAnchor != null) ? targetAnchor.getPose() : null;
    }
    public void setStartAnchorAtCurrentPose() {
        if (currentPose != null && session != null) {
            startAnchor = session.createAnchor(currentPose);
        }
    }
    public void setTargetAnchor(Pose pose) {
        if (session != null) {
            targetAnchor = session.createAnchor(pose);
        }
    }
    public void setTargetAnchorAtCurrentPose(Pose pose) {
        if (currentPose != null && session != null) {
            targetAnchor = session.createAnchor(currentPose);
        }
    }
    public void detachAnchors() {
        if (session != null) {
            for (Anchor anchor : session.getAllAnchors()) {
                anchor.detach();
            }
        }
        startAnchor = null;
        targetAnchor = null;
    }


    public void setArCoreListener(ArCoreListener listener) {
        this.arCoreListener = listener;
    }
    public void removeArCoreListener() {this.arCoreListener = null;}

    public boolean isGeospatialModeAvailable() {
        if (session == null) return false;
        Earth earth = session.getEarth();
        return (earth != null && earth.getTrackingState() == TrackingState.TRACKING);
    }
    public GeospatialPose getGeospatialPose() {
        if (session == null) return null;
        Earth earth = session.getEarth();
        if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
            return earth.getCameraGeospatialPose();
        }
        return null;
    }


    // ===== GLSurfaceView.Renderer Methods =====
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        setupOffscreenRendering(640, 480);
        setupPBOs();
        // Initialize ARCore background rendering.
        try {
            backgroundRenderer.createOnGlThread(context);
        } catch (IOException e) {
            Timber.e(e, "Failed to create background renderer");
        }
        twoDRenderer.createOnGlThread(context, "render/gmap_marker.png");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES30.glViewport(0, 0, width, height);

        // Adjust the GLSurfaceView layout to maintain the aspect ratio.
        ViewGroup.LayoutParams lp = glSurfaceView.getLayoutParams();
        lp.height = height;
        lp.width = (int) (lp.height * gpuTextureAspectRatio);
        runOnMainThread(() -> glSurfaceView.setLayoutParams(lp));
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

        session.setCameraTextureName(backgroundRenderer.getTextureId());

        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
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
            // Stop here since no camera is available and no rendering is possible.
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
            if (startAnchor != null) {
                startPose = startAnchor.getPose();
            }
            if (targetAnchor != null) {
                targetPose = targetAnchor.getPose();
            }

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
                                        new NavigationPoses(currentPose, targetPose, startPose),
                                        imageFrame,
                                        new CameraIntrinsics(camera.getImageIntrinsics()),
                                        timestamp);
                            }});
                image.close();
            }
         }

        // === Rendering (Preview & Overlays) ===

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, offscreenFramebuffer[0]);
        GLES30.glViewport(0, 0, width, height);

        // Clear the FBO
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        backgroundRenderer.draw(frame);

        if (targetAnchor != null && trackingState == TrackingState.TRACKING) {
            float[] projmtx = new float[16];
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);
            float[] viewmtx = new float[16];
            camera.getViewMatrix(viewmtx, 0);
            final float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            float[] translation = new float[3];
            float[] rotation = new float[4];
            targetAnchor.getPose().getTranslation(translation, 0);
            currentPose.getRotationQuaternion(rotation, 0);
            Pose rotatedPose = new Pose(translation, rotation);
            rotatedPose.toMatrix(anchorMatrix, 0);

            float scaleFactor = 1.0f;
            twoDRenderer.updateModelMatrix(anchorMatrix, scaleFactor);
            twoDRenderer.draw(viewmtx, projmtx);
        }

        // Read pixels using PBO
        ByteBuffer rgbaBuffer = readPixels();

        // Unbind FBO to revert to default framebuffer if needed
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        if (rgbaBuffer != null) {
            runOnMainThread(() -> {
                if (arCoreListener != null) {
                    VideoFrame.I420Buffer i420Buffer = toI420Buffer(rgbaBuffer, width, height);
                    arCoreListener.onRenderedFrame(i420Buffer, timestamp);
                }
            });
        }
    }

    private int[] pboIds = new int[2];
    private int currentPboIndex = 0;

    private int[] offscreenFramebuffer = new int[1];
    private int[] offscreenTexture = new int[1];
    private int width, height; // Set these to your desired resolution

    // Initialize PBOs
    public void setupPBOs() {
        GLES30.glGenBuffers(2, pboIds, 0);
        int bufferSize = width * height * 4; // RGBA
        for (int i = 0; i < 2; i++) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bufferSize, null, GLES30.GL_STREAM_READ);
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
    }

    // Read pixels asynchronously
    public ByteBuffer readPixels() {
        currentPboIndex = (currentPboIndex + 1) % 2;
        int nextPboIndex = currentPboIndex;

        // Read into the next PBO
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[nextPboIndex]);
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0);

        // Retrieve data from the current PBO
        int dataPboIndex = (currentPboIndex + 1) % 2;
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[dataPboIndex]);
        ByteBuffer pixelBuffer = (ByteBuffer) GLES30.glMapBufferRange(
                GLES30.GL_PIXEL_PACK_BUFFER, 0, width * height * 4, GLES30.GL_MAP_READ_BIT);

        ByteBuffer resultBuffer = null;
        if (pixelBuffer != null) {
            resultBuffer = ByteBuffer.allocateDirect(pixelBuffer.remaining());
            resultBuffer.put(pixelBuffer);
            resultBuffer.rewind();
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        return resultBuffer;
    }

    // Initialize FBO and texture
    public void setupOffscreenRendering(int width, int height) {
        this.width = width;
        this.height = height;

        // Create FBO
        GLES30.glGenFramebuffers(1, offscreenFramebuffer, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, offscreenFramebuffer[0]);

        // Create texture
        GLES30.glGenTextures(1, offscreenTexture, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, offscreenTexture[0]);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);

        // Attach texture to FBO
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, offscreenTexture[0], 0);

        // Check FBO status
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer not complete");
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Converts the RGBA data in rgbaBuffer into an I420 buffer.
     *
     * @return a VideoFrame.I420Buffer containing the converted data.
     */
    public VideoFrame.I420Buffer toI420Buffer(ByteBuffer rgbaBuffer, int width, int height) {
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
        rgbaBuffer.position(0);
        rgbaBuffer.get(rgbaData);

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
                .setCloudAnchorMode(Config.CloudAnchorMode.DISABLED)
                .setFocusMode(Config.FocusMode.AUTO)
                .setInstantPlacementMode(InstantPlacementMode.DISABLED)
                .setLightEstimationMode(LightEstimationMode.DISABLED)
                .setPlaneFindingMode(PlaneFindingMode.DISABLED)
                .setUpdateMode(Config.UpdateMode.BLOCKING);
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

    private boolean isLarger(Size size1, Size size2) {
        return (size1.getHeight() * size1.getWidth() > size2.getHeight() * size2.getWidth());
    }

    private boolean is640x480(Size size) {
        return (size.getWidth() == 640 && size.getHeight() == 480);
    }

    private void setSurfaceViewAspectRatio(float aspectRatio) {
        gpuTextureAspectRatio = aspectRatio;
        ViewGroup.LayoutParams lp = glSurfaceView.getLayoutParams();
        lp.width = (int) (lp.height * gpuTextureAspectRatio);
        runOnMainThread(() -> glSurfaceView.setLayoutParams(lp));
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
}
