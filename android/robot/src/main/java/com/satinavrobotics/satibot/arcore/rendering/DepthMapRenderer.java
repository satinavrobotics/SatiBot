package com.satinavrobotics.satibot.arcore.rendering;

import android.content.Context;
import android.opengl.GLES20;

import com.google.ar.core.Frame;
import com.google.ar.core.TrackingState;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;
import com.satinavrobotics.satibot.mapManagement.rendering.ShaderUtil;
import com.satinavrobotics.satibot.arcore.processor.ArCoreProcessor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

/**
 * Renders a depth map as a colored visualization.
 * Supports multiple depth image sources through the DepthImageGenerator interface.
 * Also supports rendering a polar histogram for robot navigation.
 * Implements ARCoreRenderer interface to be compatible with ArCoreHandler.
 */
public class DepthMapRenderer implements ARCoreRenderer {
    private static final String TAG = DepthMapRenderer.class.getSimpleName();

    // Shader names.
    private static final String VERTEX_SHADER_NAME = "shaders/depth_map.vert";
    private static final String FRAGMENT_SHADER_NAME = "shaders/depth_map.frag";
    private static final String GRADIENT_FRAGMENT_SHADER_NAME = "shaders/depth_map_gradient.frag";

    private static final int BYTES_PER_FLOAT = Float.SIZE / 8;
    private static final int COORDS_PER_VERTEX = 3; // x, y, z
    private static final int TEXCOORDS_PER_VERTEX = 2; // s, t

    private static final int VERTEX_STRIDE = COORDS_PER_VERTEX * BYTES_PER_FLOAT;
    private static final int TEXCOORD_STRIDE = TEXCOORDS_PER_VERTEX * BYTES_PER_FLOAT;

    // Display modes - now defined as constants only, state is stored in the fragment
    public static final int DISPLAY_MODE_DEPTH = 0;
    public static final int DISPLAY_MODE_NAV = 1;

    private int gradientProgram;

    // Gradient shader parameters
    private int gradientPositionParam;
    private int gradientTexCoordParam;
    private int gradientDepthTextureParam;
    private int gradientConfidenceTextureParam;
    private int gradientTextureParam;
    private int closerNextTextureParam;
    private int horizontalGradientTextureParam;
    private int gradientDepthColorModeParam;
    private int gradientConfidenceThresholdParam;

    // Buffers for vertex data for the quad.
    private final FloatBuffer quadVertices;
    private final FloatBuffer quadTexCoords;
    private int quadVerticesBufferId;
    private int quadTexCoordsBufferId;

    // Depth texture.
    private int depthTextureId;
    private int confidenceTextureId;
    private int gradientTextureId;
    private int closerNextTextureId;
    private int horizontalGradientTextureId;

    // Reusable buffers for texture data
    private ByteBuffer depthByteBuffer;
    private ByteBuffer confidenceRGBABuffer;
    private ByteBuffer gradientRGBABuffer;
    private ByteBuffer closerNextRGBABuffer;
    private ByteBuffer horizontalGradientRGBABuffer;

    private long averageRenderTimeMs = 0;
    private static final float RENDER_TIME_ALPHA = 0.3f; // For exponential moving average

    // Interface for updating the NavMapOverlay with camera intrinsics
    public interface NavMapUpdateListener {
        void onCameraIntrinsicsAvailable(CameraIntrinsics cameraIntrinsics);
    }

    // Listener for NavMapOverlay updates
    private NavMapUpdateListener navMapUpdateListener;

    // Visualization mode (0 = rainbow, 1 = grayscale)
    private int depthColorMode = 0;
    private float confidenceThreshold = 0.0f;

    // Surface dimensions
    private int width = 0;
    private int height = 0;

    /**
     * Creates and initializes the depth map renderer.
     */
    public DepthMapRenderer() {
        // Prepare quad vertices.
        quadVertices = ByteBuffer.allocateDirect(BYTES_PER_FLOAT * COORDS_PER_VERTEX * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quadVertices.put(new float[] {
                -1.0f, -1.0f, 0.0f,
                -1.0f, +1.0f, 0.0f,
                +1.0f, -1.0f, 0.0f,
                +1.0f, +1.0f, 0.0f,
        });
        quadVertices.position(0);

        // Prepare quad texture coordinates.
        // Note: We're using standard texture coordinates here, and will flip in the shader
        // This gives us more flexibility to adjust the orientation
        quadTexCoords = ByteBuffer.allocateDirect(BYTES_PER_FLOAT * TEXCOORDS_PER_VERTEX * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quadTexCoords.put(new float[] {
                0.0f, 0.0f, // Bottom-left
                0.0f, 1.0f, // Top-left
                1.0f, 0.0f, // Bottom-right
                1.0f, 1.0f, // Top-right
        });
        quadTexCoords.position(0);
    }

    /**
     * Called when the surface is created.
     * Initialize OpenGL resources here.
     *
     * @param gl     The GL interface
     * @param config The EGL configuration
     * @param context The Android context
     */
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config, Context context) {
        // Context for resource loading
        try {
            createOnGlThread(context);
        } catch (IOException e) {
            Timber.e(e, "Error creating on GL thread: %s", e.getMessage());
        }
    }

    /**
     * Called when the surface changes size.
     *
     * @param gl     The GL interface
     * @param width  The new width
     * @param height The new height
     */
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.width = width;
        this.height = height;
        GLES20.glViewport(0, 0, width, height);
    }

    /**
     * Interface for updating FPS statistics
     */
    public interface FpsUpdateListener {
        void onFrameRendered();
    }

    // Listener for FPS updates
    private FpsUpdateListener fpsUpdateListener;

    /**
     * Sets the FPS update listener.
     * @param listener The listener to be notified when a frame is rendered
     */
    public void setFpsUpdateListener(FpsUpdateListener listener) {
        this.fpsUpdateListener = listener;
    }

    /**
     * Draw the AR frame.
     *
     * @param frameData The processed frame data containing depth information
     */
    @Override
    public void drawFrame(ArCoreProcessor.ProcessedFrameData frameData) {
        // For depth visualization, we don't use the view and projection matrices
        // We just draw the depth map directly
        if (frameData.hasDepthData()) {
            // Draw with the updated depth data and gradient information
            draw(frameData.getDepthImageData(), frameData.getConfidenceImageData(),
                 frameData.getDepthWidth(), frameData.getDepthHeight(),
                 frameData.getCloserNextPixelInfo(), frameData.getHorizontalGradientInfo());
        } else {
            // No depth data available, just draw with existing textures
            draw();
        }

        // Update the NavMapOverlay if it's available
        if (frameData.getFrame() != null && frameData.getTrackingState() == TrackingState.TRACKING) {
            try {
                // Get camera intrinsics from the frame
                Frame frame = frameData.getFrame();
                if (navMapUpdateListener != null) {
                    CameraIntrinsics cameraIntrinsics = new CameraIntrinsics(frame.getCamera().getImageIntrinsics());
                    navMapUpdateListener.onCameraIntrinsicsAvailable(cameraIntrinsics);
                }
            } catch (Exception e) {
                Timber.w("Failed to get camera intrinsics: %s", e.getMessage());
            }
        }

        // Notify the FPS update listener that a frame has been rendered
        if (fpsUpdateListener != null) {
            fpsUpdateListener.onFrameRendered();
        }
    }

    /**
     * Read pixels from the current framebuffer.
     * This should be called after drawFrame if pixel data is needed.
     *
     * @return A ByteBuffer containing the rendered frame data (RGBA) or null if direct rendering is used
     */
    @Override
    public ByteBuffer readPixels() {
        // We're using direct rendering, so we don't need to read pixels
        return null;
    }

    /**
     * Get the texture ID used by the background renderer.
     *
     * @return The texture ID
     */
    @Override
    public int getBackgroundTextureId() {
        // We don't use a background texture
        return 0;
    }

    /**
     * Clean up resources when the renderer is no longer needed.
     */
    @Override
    public void cleanup() {
        // Clean up textures and buffers
        int[] textures = new int[] { depthTextureId, confidenceTextureId, gradientTextureId,
                                    closerNextTextureId, horizontalGradientTextureId };
        GLES20.glDeleteTextures(textures.length, textures, 0);

        int[] buffers = new int[] { quadVerticesBufferId, quadTexCoordsBufferId };
        GLES20.glDeleteBuffers(buffers.length, buffers, 0);

        GLES20.glDeleteProgram(gradientProgram);
    }

    /**
     * Get the current width of the rendering surface.
     *
     * @return The width in pixels
     */
    @Override
    public int getWidth() {
        return width;
    }

    /**
     * Get the current height of the rendering surface.
     *
     * @return The height in pixels
     */
    @Override
    public int getHeight() {
        return height;
    }

    /**
     * Creates and initializes OpenGL resources needed for rendering the depth map.
     */
    public void createOnGlThread(Context context) throws IOException {
        // The depth image generator is now initialized by the DepthProcessor

        // Nav map renderer is no longer used - handled by NavMapOverlay

        // Load vertex and fragment shaders for regular depth map
        int vertexShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_NAME);
        int fragmentShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_NAME);

        // Create and link the regular shader program
        // Shader program handles.
        int quadProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(quadProgram, vertexShader);
        GLES20.glAttachShader(quadProgram, fragmentShader);
        GLES20.glLinkProgram(quadProgram);

        // Check if linking succeeded
        final int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(quadProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Timber.e("Error linking shader program: %s", GLES20.glGetProgramInfoLog(quadProgram));
            GLES20.glDeleteProgram(quadProgram);
            throw new RuntimeException("Error linking shader program");
        }

        // Load gradient fragment shader
        int gradientFragmentShader = ShaderUtil.loadGLShader(TAG, context, GLES20.GL_FRAGMENT_SHADER, GRADIENT_FRAGMENT_SHADER_NAME);

        // Create and link the gradient shader program
        gradientProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(gradientProgram, vertexShader); // Reuse vertex shader
        GLES20.glAttachShader(gradientProgram, gradientFragmentShader);
        GLES20.glLinkProgram(gradientProgram);

        // Check if linking succeeded
        GLES20.glGetProgramiv(gradientProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Timber.e("Error linking gradient shader program: %s", GLES20.glGetProgramInfoLog(gradientProgram));
            GLES20.glDeleteProgram(gradientProgram);
            throw new RuntimeException("Error linking gradient shader program");
        }

        // Get shader locations for gradient program
        gradientPositionParam = GLES20.glGetAttribLocation(gradientProgram, "a_Position");
        gradientTexCoordParam = GLES20.glGetAttribLocation(gradientProgram, "a_TexCoord");
        gradientDepthTextureParam = GLES20.glGetUniformLocation(gradientProgram, "u_DepthTexture");
        gradientConfidenceTextureParam = GLES20.glGetUniformLocation(gradientProgram, "u_ConfidenceTexture");
        gradientTextureParam = GLES20.glGetUniformLocation(gradientProgram, "u_GradientTexture");
        closerNextTextureParam = GLES20.glGetUniformLocation(gradientProgram, "u_CloserNextTexture");
        horizontalGradientTextureParam = GLES20.glGetUniformLocation(gradientProgram, "u_HorizontalGradientTexture");
        gradientDepthColorModeParam = GLES20.glGetUniformLocation(gradientProgram, "u_DepthColorMode");
        gradientConfidenceThresholdParam = GLES20.glGetUniformLocation(gradientProgram, "u_ConfidenceThreshold");

        // Generate the textures.
        int[] textures = new int[5];
        GLES20.glGenTextures(5, textures, 0);
        depthTextureId = textures[0];
        confidenceTextureId = textures[1];
        gradientTextureId = textures[2];
        closerNextTextureId = textures[3];
        horizontalGradientTextureId = textures[4];

        // Create buffers for the quad vertices and texture coordinates.
        int[] buffers = new int[2];
        GLES20.glGenBuffers(2, buffers, 0);
        quadVerticesBufferId = buffers[0];
        quadTexCoordsBufferId = buffers[1];

        // Upload the vertex data to the GPU.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVerticesBufferId);
        GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER, quadVertices.capacity() * BYTES_PER_FLOAT,
                quadVertices, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        // Upload the texture coordinates to the GPU.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadTexCoordsBufferId);
        GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER, quadTexCoords.capacity() * BYTES_PER_FLOAT,
                quadTexCoords, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        ShaderUtil.checkGLError(TAG, "Shader compilation");
    }



    /**
     * Renders the depth map with gradient visualization.
     * This method uses existing textures without updating them.
     */
    public void draw() {
        draw(null, null, 0, 0, null, null);
    }

    /**
     * Renders the depth map with gradient visualization.
     *
     * @param depthBuffer The depth image data buffer, or null to use existing textures
     * @param confidenceBuffer The confidence image data buffer, or null to use existing textures
     * @param width The width of the depth image
     * @param height The height of the depth image
     * @param closerNextInfo The closer next pixel information, or null if not available
     * @param horizontalGradientInfo The horizontal gradient information, or null if not available
     */
    public void draw(ByteBuffer depthBuffer, ByteBuffer confidenceBuffer, int width, int height,
                    boolean[][] closerNextInfo, boolean[][] horizontalGradientInfo) {
        long startTime = System.currentTimeMillis();

        // Process depth data if provided
        if (depthBuffer != null && confidenceBuffer != null && width > 0 && height > 0) {
            processDepthData(depthBuffer, confidenceBuffer, width, height, closerNextInfo, horizontalGradientInfo);
        }

        // No need to test or write depth, the screen quad has arbitrary depth
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthMask(false);

        // Set the background color to black
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Enable vertex attribute arrays
        GLES20.glEnableVertexAttribArray(gradientPositionParam);
        GLES20.glEnableVertexAttribArray(gradientTexCoordParam);

        // Draw the appropriate visualization based on display mode
        try {
            // Always draw the depth map - the navigation overlay is handled separately
            drawDepthMap();
        } catch (Exception e) {
            Timber.e(e, "Error during rendering: %s", e.getMessage());
        }

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(gradientPositionParam);
        GLES20.glDisableVertexAttribArray(gradientTexCoordParam);

        // Restore the depth state
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        // Track render time for performance monitoring
        long lastRenderTimeMs = System.currentTimeMillis() - startTime;
        if (averageRenderTimeMs == 0) {
            averageRenderTimeMs = lastRenderTimeMs;
        } else {
            averageRenderTimeMs = (long)(RENDER_TIME_ALPHA * lastRenderTimeMs +
                                       (1 - RENDER_TIME_ALPHA) * averageRenderTimeMs);
        }
    }

    /**
     * Renders the depth map with gradient visualization.
     */
    private void drawDepthMap() {
        // Always use the gradient shader program for visualization
        GLES20.glUseProgram(gradientProgram);

        // Set the vertex positions.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVerticesBufferId);
        GLES20.glVertexAttribPointer(
                gradientPositionParam, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, 0);

        // Set the texture coordinates.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadTexCoordsBufferId);
        GLES20.glVertexAttribPointer(
                gradientTexCoordParam, TEXCOORDS_PER_VERTEX, GLES20.GL_FLOAT, false, TEXCOORD_STRIDE, 0);

        // Set the depth texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId);
        GLES20.glUniform1i(gradientDepthTextureParam, 0);

        // Set the confidence texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, confidenceTextureId);
        GLES20.glUniform1i(gradientConfidenceTextureParam, 1);

        // Set the gradient texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gradientTextureId);
        GLES20.glUniform1i(gradientTextureParam, 2);

        // Set the closer next texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, closerNextTextureId);
        GLES20.glUniform1i(closerNextTextureParam, 3);

        // Set the horizontal gradient texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, horizontalGradientTextureId);
        GLES20.glUniform1i(horizontalGradientTextureParam, 4);

        // Log texture binding for debugging
        Timber.d("Bound horizontal gradient texture: id=%d, param=%d",
                horizontalGradientTextureId, horizontalGradientTextureParam);

        // Set the depth color mode.
        GLES20.glUniform1i(gradientDepthColorModeParam, depthColorMode);

        // Set the confidence threshold.
        GLES20.glUniform1f(gradientConfidenceThresholdParam, confidenceThreshold);

        // Draw the quad with a single draw call
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // Robot bounds drawing has been moved to an overlay view



    /**
     * Sets the depth color mode.
     * @param mode 0 for rainbow, 1 for grayscale
     */
    public void setDepthColorMode(int mode) {
        this.depthColorMode = mode;
    }

    /**
     * Sets the confidence threshold.
     * @param threshold value between 0.0 and 1.0
     */
    public void setConfidenceThreshold(float threshold) {
        this.confidenceThreshold = threshold;
    }

    /**
     * Sets the NavMapUpdateListener.
     * @param listener The listener to be notified when camera intrinsics are available
     */
    public void setNavMapUpdateListener(NavMapUpdateListener listener) {
        this.navMapUpdateListener = listener;
    }

    /**
     * Process depth data and update textures.
     *
     * @param depthBuffer The depth image data buffer
     * @param confidenceBuffer The confidence image data buffer
     * @param width The width of the depth image
     * @param height The height of the depth image
     * @param closerNextInfo The closer next pixel information, or null if not available
     * @param horizontalGradientInfo The horizontal gradient information, or null if not available
     */
    private void processDepthData(ByteBuffer depthBuffer, ByteBuffer confidenceBuffer, int width, int height,
                                 boolean[][] closerNextInfo, boolean[][] horizontalGradientInfo) {

        try {
            // Reuse buffers for better performance - only allocate when needed
            if (depthByteBuffer == null || depthByteBuffer.capacity() < width * height * 4) {
                depthByteBuffer = ByteBuffer.allocateDirect(width * height * 4)
                        .order(ByteOrder.nativeOrder());
            } else {
                depthByteBuffer.clear();
            }

            if (confidenceRGBABuffer == null || confidenceRGBABuffer.capacity() < width * height * 4) {
                confidenceRGBABuffer = ByteBuffer.allocateDirect(width * height * 4)
                        .order(ByteOrder.nativeOrder());
            } else {
                confidenceRGBABuffer.clear();
            }

            // Use direct buffer operations for better performance
            try {
                depthBuffer.rewind();

                // Process depth and confidence in a single pass
                confidenceBuffer.rewind();

                // Use native memory operations for better performance
                int pixelCount = width * height;

                // Simplified approach: process all pixels at once with direct buffer access
                // This reduces the chance of buffer overruns or misalignment

                // Create temporary arrays for safer processing
                byte[] depthBytes = new byte[pixelCount * 2]; // 2 bytes per pixel for depth
                byte[] confidenceBytes = new byte[pixelCount]; // 1 byte per pixel for confidence

                // Read all depth data at once
                if (depthBuffer.remaining() >= pixelCount * 2) {
                    depthBuffer.get(depthBytes);
                } else {
                    // Not enough data, fill with zeros
                    Timber.w("Depth buffer too small: %d < %d", depthBuffer.remaining(), pixelCount * 2);
                    java.util.Arrays.fill(depthBytes, (byte)0);
                }

                // Read all confidence data at once
                if (confidenceBuffer.remaining() >= pixelCount) {
                    confidenceBuffer.get(confidenceBytes);
                } else {
                    // Not enough data, fill with zeros
                    Timber.w("Confidence buffer too small: %d < %d", confidenceBuffer.remaining(), pixelCount);
                    java.util.Arrays.fill(confidenceBytes, (byte)0);
                }

                // Now process the data into RGBA format for textures
                for (int i = 0; i < pixelCount; i++) {
                    // Process depth - convert from 2 bytes to RGBA
                    byte lowByte = (i*2 < depthBytes.length) ? depthBytes[i*2] : 0;
                    byte highByte = (i*2+1 < depthBytes.length) ? depthBytes[i*2+1] : 0;

                    depthByteBuffer.put(lowByte);         // Low byte in R channel
                    depthByteBuffer.put(highByte);        // High byte in G channel
                    depthByteBuffer.put((byte) 0);        // B channel unused
                    depthByteBuffer.put((byte) 255);      // Alpha channel (fully opaque)

                    // Process confidence - convert from 1 byte to RGBA
                    byte confidenceValue = (i < confidenceBytes.length) ? confidenceBytes[i] : 0;

                    confidenceRGBABuffer.put(confidenceValue);  // R channel stores confidence
                    confidenceRGBABuffer.put((byte) 0);         // G channel unused
                    confidenceRGBABuffer.put((byte) 0);         // B channel unused
                    confidenceRGBABuffer.put((byte) 255);       // Alpha channel (fully opaque)
                }

                depthByteBuffer.rewind();
                confidenceRGBABuffer.rewind();
            } catch (Exception e) {
                Timber.e(e, "Error processing depth/confidence buffers: %s", e.getMessage());
                return;
            }

            // Simplified texture handling for better stability
            int internalFormat = GLES20.GL_RGBA;

            // Bind the depth texture
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureId);

            // Use nearest filtering for depth texture to avoid interpolation artifacts
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // Check if the buffer is valid
            if (depthByteBuffer != null && depthByteBuffer.capacity() >= width * height * 4) {
                // Upload texture data
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        internalFormat,
                        width,
                        height,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        depthByteBuffer);
            } else {
                Timber.e("Invalid depth buffer for texture upload: capacity=%d, required=%d",
                        depthByteBuffer != null ? depthByteBuffer.capacity() : 0, width * height * 4);
            }

            // Bind the confidence texture
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, confidenceTextureId);

            // Use nearest filtering for confidence texture to avoid interpolation artifacts
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // Check if the buffer is valid
            if (confidenceRGBABuffer != null && confidenceRGBABuffer.capacity() >= width * height * 4) {
                // Upload texture data
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        internalFormat,
                        width,
                        height,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        confidenceRGBABuffer);
            } else {
                Timber.e("Invalid confidence buffer for texture upload: capacity=%d, required=%d",
                        confidenceRGBABuffer != null ? confidenceRGBABuffer.capacity() : 0, width * height * 4);
            }

            // Log the horizontal gradient info for debugging
            if (horizontalGradientInfo != null) {
                int count = 0;
                for (int y = 0; y < height && y < horizontalGradientInfo.length; y++) {
                    for (int x = 0; x < width && x < horizontalGradientInfo[y].length; x++) {
                        if (horizontalGradientInfo[y][x]) {
                            count++;
                        }
                    }
                }
                Timber.d("Horizontal gradient pixels: %d", count);
            } else {
                Timber.w("Horizontal gradient info is null");
            }

            // Process gradient texture (always process even if null, to clear previous data)
            {
                // Create or reuse gradient buffer
                if (gradientRGBABuffer == null || gradientRGBABuffer.capacity() < width * height * 4) {
                    gradientRGBABuffer = ByteBuffer.allocateDirect(width * height * 4)
                            .order(ByteOrder.nativeOrder());
                } else {
                    gradientRGBABuffer.clear();
                }

                // Fill gradient buffer with zeros (no gradients)
                for (int i = 0; i < width * height * 4; i += 4) {
                    gradientRGBABuffer.put((byte)0);  // R channel
                    gradientRGBABuffer.put((byte)0);  // G channel
                    gradientRGBABuffer.put((byte)0);  // B channel
                    gradientRGBABuffer.put((byte)255); // Alpha channel
                }

                gradientRGBABuffer.rewind();

                // Bind the gradient texture
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gradientTextureId);

                // Use nearest filtering
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                // Upload texture data
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        internalFormat,
                        width,
                        height,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        gradientRGBABuffer);
            }

            // Process closer next texture (always process even if null)
            {
                // Create or reuse closer next buffer
                if (closerNextRGBABuffer == null || closerNextRGBABuffer.capacity() < width * height * 4) {
                    closerNextRGBABuffer = ByteBuffer.allocateDirect(width * height * 4)
                            .order(ByteOrder.nativeOrder());
                } else {
                    closerNextRGBABuffer.clear();
                }

                // First clear the buffer
                closerNextRGBABuffer.clear();

                // Fill with zeros initially (no closer next pixels)
                for (int i = 0; i < width * height * 4; i++) {
                    closerNextRGBABuffer.put((byte)0);
                }
                closerNextRGBABuffer.rewind();

                // If we have valid closer next info, fill in the red pixels
                if (closerNextInfo != null) {
                    for (int y = 0; y < height && y < closerNextInfo.length; y++) {
                        for (int x = 0; x < width && x < closerNextInfo[y].length; x++) {
                            if (closerNextInfo[y][x]) {
                                // Calculate buffer position for this pixel
                                int pos = (y * width + x) * 4;
                                // Set R channel to 255 for closer next pixels
                                closerNextRGBABuffer.put(pos, (byte)255);
                            }
                        }
                    }
                }

                closerNextRGBABuffer.rewind();

                // Bind the closer next texture
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, closerNextTextureId);

                // Use nearest filtering
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                // Upload texture data
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        internalFormat,
                        width,
                        height,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        closerNextRGBABuffer);
            }

            // Process horizontal gradient texture (always process even if null)
            {
                // Create or reuse horizontal gradient buffer
                if (horizontalGradientRGBABuffer == null || horizontalGradientRGBABuffer.capacity() < width * height * 4) {
                    horizontalGradientRGBABuffer = ByteBuffer.allocateDirect(width * height * 4)
                            .order(ByteOrder.nativeOrder());
                } else {
                    horizontalGradientRGBABuffer.clear();
                }

                // First clear the buffer
                horizontalGradientRGBABuffer.clear();

                // Fill with zeros initially (no horizontal gradient pixels)
                for (int i = 0; i < width * height * 4; i++) {
                    horizontalGradientRGBABuffer.put((byte)0);
                }
                horizontalGradientRGBABuffer.rewind();

                // If we have valid horizontal gradient info, fill in the red pixels
                if (horizontalGradientInfo != null) {
                    int count = 0;
                    for (int y = 0; y < height && y < horizontalGradientInfo.length; y++) {
                        for (int x = 0; x < width && x < horizontalGradientInfo[y].length; x++) {
                            if (horizontalGradientInfo[y][x]) {
                                // Calculate buffer position for this pixel
                                int pos = (y * width + x) * 4;
                                // Set R channel to 255 for horizontal gradient pixels
                                horizontalGradientRGBABuffer.put(pos, (byte)255);
                                count++;
                            }
                        }
                    }
                    Timber.d("Marked %d horizontal gradient pixels in texture", count);
                }

                horizontalGradientRGBABuffer.rewind();

                // Bind the horizontal gradient texture
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, horizontalGradientTextureId);

                // Use nearest filtering
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                // Upload texture data
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        internalFormat,
                        width,
                        height,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        horizontalGradientRGBABuffer);
            }

            ShaderUtil.checkGLError(TAG, "After updating textures");
        } catch (Exception e) {
            Timber.e(e, "Error processing depth data: %s", e.getMessage());
        }
    }
}
