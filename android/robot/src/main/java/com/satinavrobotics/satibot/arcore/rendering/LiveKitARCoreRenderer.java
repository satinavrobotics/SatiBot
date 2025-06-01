package com.satinavrobotics.satibot.arcore.rendering;

import android.content.Context;
import android.opengl.GLES30;

import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingState;
import com.satinavrobotics.satibot.mapManagement.MapResolvingManager;
import com.satinavrobotics.satibot.arcore.processor.ArCoreProcessor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

/**
 * Default implementation of ARCoreRenderer interface.
 * This class handles rendering of AR content using ARCore.
 */
public class LiveKitARCoreRenderer implements ARCoreRenderer {
    private static final String TAG = LiveKitARCoreRenderer.class.getSimpleName();

    // Rendering components
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final TwoDRenderer twoDRenderer = new TwoDRenderer();
    private final float[] anchorMatrix = new float[16];

    // Offscreen rendering
    private final int[] offscreenFramebuffer = new int[1];
    private final int[] offscreenTexture = new int[1];
    private int width, height;

    // Pixel buffer objects for asynchronous pixel reading
    private final int[] pboIds = new int[2];
    private int currentPboIndex = 0;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config, Context context) {
        GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        setupOffscreenRendering(640, 480);
        setupPBOs();

        // Initialize ARCore background rendering
        try {
            backgroundRenderer.createOnGlThread(context);
        } catch (IOException e) {
            Timber.e(e, "Failed to create background renderer");
        }

        twoDRenderer.createOnGlThread(context, "render/gmap_marker.png");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES30.glViewport(0, 0, width, height);
    }

    @Override
    public void drawFrame(ArCoreProcessor.ProcessedFrameData frameData) {
        if (frameData == null || frameData.getFrame() == null) {
            Timber.w("Null frame data passed to drawFrame");
            return;
        }

        Frame frame = frameData.getFrame();
        TrackingState trackingState = frameData.getTrackingState();
        float[] viewMatrix = frameData.getViewMatrix();
        float[] projectionMatrix = frameData.getProjectionMatrix();
        Pose currentPose = frameData.getCurrentPose();
        List<MapResolvingManager.ResolvedAnchor> resolvedAnchors = frameData.getResolvedAnchors();

        // Bind to offscreen framebuffer
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, offscreenFramebuffer[0]);
        GLES30.glViewport(0, 0, width, height);

        // Clear the FBO
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);

        // Draw background
        backgroundRenderer.draw(frame);

        // If tracking and we have matrices, render anchor markers
        if (trackingState == TrackingState.TRACKING && viewMatrix != null && projectionMatrix != null) {
            // Render all resolved cloud anchors
            if (resolvedAnchors != null && !resolvedAnchors.isEmpty()) {
                for (MapResolvingManager.ResolvedAnchor resolvedAnchor : resolvedAnchors) {
                    renderAnchorMarker(resolvedAnchor.getAnchor().getPose(), viewMatrix, projectionMatrix, currentPose);
                }
            }
        }
    }

    private void renderAnchorMarker(Pose pose, float[] viewMatrix, float[] projectionMatrix, Pose currentPose) {
        float[] translation = new float[3];
        float[] rotation = new float[4];
        pose.getTranslation(translation, 0);
        currentPose.getRotationQuaternion(rotation, 0);
        Pose rotatedPose = new Pose(translation, rotation);
        rotatedPose.toMatrix(anchorMatrix, 0);

        float scaleFactor = 1.0f;
        twoDRenderer.updateModelMatrix(anchorMatrix, scaleFactor);
        twoDRenderer.draw(viewMatrix, projectionMatrix);
    }

    @Override
    public ByteBuffer readPixels() {
        // Read pixels using PBO
        ByteBuffer rgbaBuffer = readPixelsInternal();

        // Unbind FBO to revert to default framebuffer
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        return rgbaBuffer;
    }

    @Override
    public int getBackgroundTextureId() {
        return backgroundRenderer.getTextureId();
    }

    @Override
    public void cleanup() {
        // Delete framebuffer and texture
        GLES30.glDeleteFramebuffers(1, offscreenFramebuffer, 0);
        GLES30.glDeleteTextures(1, offscreenTexture, 0);

        // Delete PBOs
        GLES30.glDeleteBuffers(2, pboIds, 0);
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    // Initialize FBO and texture
    private void setupOffscreenRendering(int width, int height) {
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
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Timber.e("Framebuffer not complete, status: %d", status);
        }

        // Unbind FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    // Initialize PBOs
    private void setupPBOs() {
        GLES30.glGenBuffers(2, pboIds, 0);
        int bufferSize = width * height * 4; // RGBA
        for (int i = 0; i < 2; i++) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bufferSize, null, GLES30.GL_STREAM_READ);
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
    }

    // Read pixels asynchronously
    private ByteBuffer readPixelsInternal() {
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
}
