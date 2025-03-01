package org.openbot.pointGoalNavigation;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.opengl.GLES20;
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

import org.openbot.pointGoalNavigation.rendering.BackgroundRenderer;
import org.openbot.pointGoalNavigation.rendering.DisplayRotationHelper;
import org.openbot.pointGoalNavigation.rendering.TwoDRenderer;
import org.webrtc.CapturerObserver;
import org.webrtc.JavaI420Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import timber.log.Timber;

import java.io.IOException;
import java.nio.ByteBuffer;
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
public class ArCore implements VideoCapturer, GLSurfaceView.Renderer {

  // ===== ARCore Fields =====
  private Session session;
  private DisplayRotationHelper displayRotationHelper;
  private Context context;
  private GLSurfaceView glSurfaceView;
  private BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private TwoDRenderer twoDRenderer = new TwoDRenderer();
  private float gpuTextureAspectRatio = 16.0f / 9.0f;
  private boolean renderFrame = true;
  private Handler handlerMain;
  private ArCoreListener arCoreListener = null;

  // Anchor and pose fields for navigation.
  private Anchor startAnchor, targetAnchor;
  private Pose currentPose;
  private Pose startPose, targetPose;
  private final float[] anchorMatrix = new float[16];

  // ===== Video Capturer Field =====
  private CapturerObserver capturerObserver;
  private boolean isCapturing = false;
  private final ExecutorService conversionExecutor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean conversionInProgress = new AtomicBoolean(false);

  // ===== GL Texture (used by background renderer) =====
  private int cameraTextureId;

  // ===== Constructor =====
  public ArCore(Context context, GLSurfaceView glSurfaceView, Handler handlerMain) {
    this.context = context;
    this.glSurfaceView = glSurfaceView;
    this.handlerMain = handlerMain;
    displayRotationHelper = new DisplayRotationHelper(context);

    // Set up the GLSurfaceView (single renderer for ARCore & capture).
    glSurfaceView.setPreserveEGLContextOnPause(true);
    glSurfaceView.setEGLContextClientVersion(2);
    // Use an EGL config with alpha for plane blending.
    glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    glSurfaceView.setRenderer(this);
    glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    glSurfaceView.setWillNotDraw(false);
  }

  // ===== VideoCapturer Interface Methods =====
  @Override
  public void initialize(SurfaceTextureHelper surfaceTexture, Context applicationContext,
                         CapturerObserver capturerObserver) {
    // SurfaceTexture is not used in this implementation.
    this.context = applicationContext;
    this.capturerObserver = capturerObserver;
  }

  @Override
  public void startCapture(final int width, final int height, final int framerate) {
    try {
      session = new Session(context);
      setConfig();
      setCameraConfig();
      session.resume();
      glSurfaceView.onResume();
      displayRotationHelper.onResume();
      isCapturing = true;
      capturerObserver.onCapturerStarted(true);
    } catch (UnavailableSdkTooOldException | UnavailableDeviceNotCompatibleException |
             UnavailableArcoreNotInstalledException | UnavailableApkTooOldException |
             CameraNotAvailableException e) {
      capturerObserver.onCapturerStarted(false);
      e.printStackTrace();
    }
  }

  @Override
  public void stopCapture() throws InterruptedException {
    isCapturing = false;
    if (session != null) {
      session.pause();
    }
    glSurfaceView.onPause();
    displayRotationHelper.onPause();
    capturerObserver.onCapturerStopped();
  }

  @Override
  public void changeCaptureFormat(int width, int height, int framerate) {
    // ARCore’s capture format is determined by its session configuration.
  }

  @Override
  public void dispose() {
    if (session != null) {
      session.close();
      session = null;
    }
    conversionExecutor.shutdownNow();
  }

  @Override
  public boolean isScreencast() {
    return false; // This is a camera capturer.
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

  public Pose getStartPose() {
    return (startAnchor == null) ? null : startAnchor.getPose();
  }

  public void setStartAnchorAtCurrentPose() {
    if (currentPose != null && session != null) {
      startAnchor = session.createAnchor(currentPose);
    }
  }

  public Pose getTargetPose() {
    return (targetAnchor != null) ? targetAnchor.getPose() : null;
  }

  public void setTargetAnchor(Pose pose) {
    if (session != null) {
      targetAnchor = session.createAnchor(pose);
    }
  }

  public void setTargetAnchorAtCurrentPose() {
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

  public void removeArCoreListener() {
    this.arCoreListener = null;
  }

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
        HandlerThread handlerThread = new HandlerThread("ArCore Session Close Handler");
        handlerThread.start();
        Handler sessionCloseHandler = new Handler(handlerThread.getLooper());
        sessionCloseHandler.post(() -> session.close());
      });
    }
  }

  // ===== GLSurfaceView.Renderer Methods =====
  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Initialize ARCore background rendering.
    try {
      backgroundRenderer.createOnGlThread(context);
    } catch (IOException e) {
      Timber.e(e, "Failed to create background renderer");
    }
    twoDRenderer.createOnGlThread(context, "render/gmap_marker.png");

    // Generate a GL texture that ARCore uses for the camera image.
    int[] textures = new int[1];
    GLES20.glGenTextures(1, textures, 0);
    cameraTextureId = textures[0];
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);

    // Adjust the GLSurfaceView layout to maintain the aspect ratio.
    ViewGroup.LayoutParams lp = glSurfaceView.getLayoutParams();
    lp.height = height;
    lp.width = (int) (lp.height * gpuTextureAspectRatio);
    runOnMainThread(() -> glSurfaceView.setLayoutParams(lp));
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    if (session == null || !isCapturing) {
      return;
    }

    // === ARCore Session Update ===
    displayRotationHelper.updateSessionIfNeeded(session);
    session.setCameraTextureName(backgroundRenderer.getTextureId());
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

    Camera camera = frame.getCamera();
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



      Image image = null;
      try {
        image = frame.acquireCameraImage();
      } catch (NotYetAvailableException e) {
        Timber.d(e, "ARCore image not available.");
      }

      if (image != null) {
        // --- ARCore Navigation: Notify listener with pose, image, and intrinsics ---
        ImageFrame imageFrame = new ImageFrame(image);
        NavigationPoses navPoses = new NavigationPoses(currentPose, targetPose, startPose);
        CameraIntrinsics camIntrinsics = new CameraIntrinsics(camera.getImageIntrinsics());

        runOnMainThread(() -> {
          if (arCoreListener != null) {
            arCoreListener.onArCoreUpdate(navPoses, imageFrame, camIntrinsics, timestamp);
          }
        });

        // Offload conversion to I420 if not already in progress
        if (conversionInProgress.compareAndSet(false, true)) {
          final Image imageForConversion = image;
          final long frameTimestamp = timestamp;

          conversionExecutor.submit(() -> {
            try {
              VideoFrame.I420Buffer i420Buffer = convertImageToI420(imageForConversion);
              if (i420Buffer != null) {
                VideoFrame videoFrame = new VideoFrame(i420Buffer, 0, frameTimestamp);
                capturerObserver.onFrameCaptured(videoFrame);
                videoFrame.release();
              }
            } finally {
              imageForConversion.close();
              conversionInProgress.set(false);
            }
          });
        } else {
          // If conversion is already in progress, discard this frame
          image.close();
        }
      }
    }

    // === Rendering (Preview & Overlays) ===
    if (renderFrame) {
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
    }
  }

  // ===== Helper Methods =====

  /**
   * Converts an ARCore Image (YUV_420_888) into an I420Buffer.
   */
  private VideoFrame.I420Buffer convertImageToI420(Image image) {
    // Original image dimensions.
    int width = image.getWidth();
    int height = image.getHeight();
    // For a 90° clockwise rotation, the output dimensions are swapped.
    int rotatedWidth = height;
    int rotatedHeight = width;

    // Allocate the I420 buffer with swapped dimensions.
    JavaI420Buffer i420Buffer = JavaI420Buffer.allocate(rotatedWidth, rotatedHeight);
    Image.Plane[] planes = image.getPlanes();

    // --- Rotate and copy Y plane ---
    {
      ByteBuffer yBuffer = planes[0].getBuffer();
      int yRowStride = planes[0].getRowStride();
      int yPixelStride = planes[0].getPixelStride();
      ByteBuffer i420Y = i420Buffer.getDataY();
      int i420YStride = i420Buffer.getStrideY();

      // Loop over the original image dimensions.
      for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
          // Calculate the destination coordinates after 90° clockwise rotation.
          int destX = height - 1 - row; // new column index in rotated image.
          int destY = col;              // new row index in rotated image.
          int destIndex = destY * i420YStride + destX;
          int srcIndex = row * yRowStride + col * yPixelStride;
          i420Y.put(destIndex, yBuffer.get(srcIndex));
        }
      }
    }

    // --- Rotate and copy U plane ---
    {
      int srcChromaWidth = (width + 1) / 2;
      int srcChromaHeight = (height + 1) / 2;
      // After rotation, the chroma dimensions swap.
      int rotatedChromaWidth = srcChromaHeight;
      int rotatedChromaHeight = srcChromaWidth;

      ByteBuffer uBuffer = planes[1].getBuffer();
      int uRowStride = planes[1].getRowStride();
      int uPixelStride = planes[1].getPixelStride();
      ByteBuffer i420U = i420Buffer.getDataU();
      int i420UStride = i420Buffer.getStrideU();

      for (int row = 0; row < srcChromaHeight; row++) {
        for (int col = 0; col < srcChromaWidth; col++) {
          int destX = srcChromaHeight - 1 - row;
          int destY = col;
          int destIndex = destY * i420UStride + destX;
          int srcIndex = row * uRowStride + col * uPixelStride;
          i420U.put(destIndex, uBuffer.get(srcIndex));
        }
      }
    }

    // --- Rotate and copy V plane ---
    {
      int srcChromaWidth = (width + 1) / 2;
      int srcChromaHeight = (height + 1) / 2;
      // After rotation, the chroma dimensions swap.
      int rotatedChromaWidth = srcChromaHeight;
      int rotatedChromaHeight = srcChromaWidth;

      ByteBuffer vBuffer = planes[2].getBuffer();
      int vRowStride = planes[2].getRowStride();
      int vPixelStride = planes[2].getPixelStride();
      ByteBuffer i420V = i420Buffer.getDataV();
      int i420VStride = i420Buffer.getStrideV();

      for (int row = 0; row < srcChromaHeight; row++) {
        for (int col = 0; col < srcChromaWidth; col++) {
          int destX = srcChromaHeight - 1 - row;
          int destY = col;
          int destIndex = destY * i420VStride + destX;
          int srcIndex = row * vRowStride + col * vPixelStride;
          i420V.put(destIndex, vBuffer.get(srcIndex));
        }
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

  // In this example, a specific resolution (1920x1080) is used to decide the CPU stream.
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
  //protected synchronized void runOnMainThread(final Runnable r) {
  //  new Thread(() -> {
  //    if (handlerMain != null) {
  //      handlerMain.post(r);
  //    }
  //  }).start();
  //}

  protected void runOnMainThread(final Runnable r) {
    if (handlerMain != null) {
      handlerMain.post(r);
    }
  }
}
