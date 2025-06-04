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
    private int verticalFartherTextureParam;
    private int horizontalGradientTextureParam;
    private int tooCloseTextureParam;
    private int gradientDepthColorModeParam;
    private int gradientConfidenceThresholdParam;
    private int showVerticalCloserParam;
    private int showVerticalFartherParam;
    private int showTooCloseParam;

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
    private int verticalFartherTextureId;
    private int horizontalGradientTextureId;
    private int tooCloseTextureId;

    // Reusable buffers for texture data
    private ByteBuffer depthByteBuffer;
    private ByteBuffer confidenceRGBABuffer;
    private ByteBuffer gradientRGBABuffer;
    private ByteBuffer closerNextRGBABuffer;
    private ByteBuffer verticalFartherRGBABuffer;
    private ByteBuffer horizontalGradientRGBABuffer;
    private ByteBuffer tooCloseRGBABuffer;

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

    // Visualization control flags
    private boolean showVerticalCloser = true;
    private boolean showVerticalFarther = true;
    private boolean showTooClose = true;

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
                 frameData.getCloserNextPixelInfo(), frameData.getVerticalFartherPixelInfo(),
                 frameData.getHorizontalGradientInfo(), frameData.getTooClosePixelInfo());
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
                                    closerNextTextureId, verticalFartherTextureId, horizontalGradientTextureId, tooCloseTextureId };
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
        verticalFartherTextureParam = GLES20.glGetUniformLocation(gradientProgram, "u_VerticalFartherTexture");
        horizontalGradientTextureParam = GLES20.glGetUniformLocation(gradientProgram, "u_HorizontalGradientTexture");
        tooCloseTextureParam = GLES20.glGetUniformLocation(gradientProgram, "u_TooCloseTexture");
        gradientDepthColorModeParam = GLES20.glGetUniformLocation(gradientProgram, "u_DepthColorMode");
        gradientConfidenceThresholdParam = GLES20.glGetUniformLocation(gradientProgram, "u_ConfidenceThreshold");
        showVerticalCloserParam = GLES20.glGetUniformLocation(gradientProgram, "u_ShowVerticalCloser");
        showVerticalFartherParam = GLES20.glGetUniformLocation(gradientProgram, "u_ShowVerticalFarther");
        showTooCloseParam = GLES20.glGetUniformLocation(gradientProgram, "u_ShowTooClose");

        // Generate the textures.
        int[] textures = new int[7];
        GLES20.glGenTextures(7, textures, 0);
        depthTextureId = textures[0];
        confidenceTextureId = textures[1];
        gradientTextureId = textures[2];
        closerNextTextureId = textures[3];
        verticalFartherTextureId = textures[4];
        horizontalGradientTextureId = textures[5];
        tooCloseTextureId = textures[6];

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
        draw(null, null, 0, 0, null, null, null, null);
    }

    /**
     * Renders the depth map with gradient visualization.
     *
     * @param depthBuffer The depth image data buffer, or null to use existing textures
     * @param confidenceBuffer The confidence image data buffer, or null to use existing textures
     * @param width The width of the depth image
     * @param height The height of the depth image
     * @param closerNextInfo The closer next pixel information, or null if not available
     * @param verticalFartherInfo The vertical farther pixel information, or null if not available
     * @param horizontalGradientInfo The horizontal gradient information, or null if not available
     * @param tooCloseInfo The too close pixel information, or null if not available
     */
    public void draw(ByteBuffer depthBuffer, ByteBuffer confidenceBuffer, int width, int height,
                    boolean[][] closerNextInfo, boolean[][] verticalFartherInfo, boolean[][] horizontalGradientInfo, boolean[][] tooCloseInfo) {
        long startTime = System.currentTimeMillis();

        // Process depth data if provided
        if (depthBuffer != null && confidenceBuffer != null && width > 0 && height > 0) {
            processDepthData(depthBuffer, confidenceBuffer, width, height, closerNextInfo, verticalFartherInfo, horizontalGradientInfo, tooCloseInfo);
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

        drawDepthMap();

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

        // Set the vertical farther texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, verticalFartherTextureId);
        GLES20.glUniform1i(verticalFartherTextureParam, 4);

        // Set the horizontal gradient texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE5);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, horizontalGradientTextureId);
        GLES20.glUniform1i(horizontalGradientTextureParam, 5);

        // Set the too close texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE6);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tooCloseTextureId);
        GLES20.glUniform1i(tooCloseTextureParam, 6);



        // Set the depth color mode.
        GLES20.glUniform1i(gradientDepthColorModeParam, depthColorMode);

        // Set the confidence threshold.
        GLES20.glUniform1f(gradientConfidenceThresholdParam, confidenceThreshold);

        // Set the visualization flags.
        GLES20.glUniform1i(showVerticalCloserParam, showVerticalCloser ? 1 : 0);
        GLES20.glUniform1i(showVerticalFartherParam, showVerticalFarther ? 1 : 0);
        GLES20.glUniform1i(showTooCloseParam, showTooClose ? 1 : 0);

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
     * Sets whether to show vertical closer pixels.
     * @param show true to show, false to hide
     */
    public void setShowVerticalCloser(boolean show) {
        this.showVerticalCloser = show;
    }

    /**
     * Sets whether to show vertical farther pixels.
     * @param show true to show, false to hide
     */
    public void setShowVerticalFarther(boolean show) {
        this.showVerticalFarther = show;
    }

    /**
     * Sets whether to show too close pixels.
     * @param show true to show, false to hide
     */
    public void setShowTooClose(boolean show) {
        this.showTooClose = show;
    }

    /**
     * Process depth data and update textures.
     *
     * @param depthBuffer The depth image data buffer
     * @param confidenceBuffer The confidence image data buffer
     * @param width The width of the depth image
     * @param height The height of the depth image
     * @param closerNextInfo The closer next pixel information, or null if not available
     * @param verticalFartherInfo The vertical farther pixel information, or null if not available
     * @param horizontalGradientInfo The horizontal gradient information, or null if not available
     */
    private void processDepthData(ByteBuffer depthBuffer, ByteBuffer confidenceBuffer, int width, int height,
                                 boolean[][] closerNextInfo, boolean[][] verticalFartherInfo, boolean[][] horizontalGradientInfo, boolean[][] tooCloseInfo) {

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

            if (depthByteBuffer != null && depthByteBuffer.capacity() >= width * height * 4) {
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

            processVisualizationTexturesUnified(width, height, internalFormat,
                    closerNextInfo, verticalFartherInfo, horizontalGradientInfo, tooCloseInfo);





            ShaderUtil.checkGLError(TAG, "After updating textures");
        } catch (Exception e) {
            // Silently handle errors to avoid log spam
        }
    }

    /**
     * Processes all visualization textures in a unified manner for maximum performance.
     * This method combines all texture processing into a single loop to minimize redundant operations.
     *
     * @param width Image width
     * @param height Image height
     * @param internalFormat OpenGL internal format for textures
     * @param closerNextInfo Vertical closer pixel information
     * @param verticalFartherInfo Vertical farther pixel information
     * @param horizontalGradientInfo Horizontal gradient pixel information
     * @param tooCloseInfo Too close pixel information
     */
    private void processVisualizationTexturesUnified(int width, int height, int internalFormat,
                                                   boolean[][] closerNextInfo, boolean[][] verticalFartherInfo,
                                                   boolean[][] horizontalGradientInfo, boolean[][] tooCloseInfo) {

        int pixelCount = width * height;
        int bufferSize = pixelCount * 4; // 4 bytes per pixel (RGBA)

        // Ensure all buffers are allocated and sized correctly
        ensureBufferCapacity(bufferSize);

        // Clear all buffers efficiently using bulk operations
        clearAllBuffersUnified(bufferSize);

        // Process all visualization data in a single unified loop
        processVisualizationDataUnified(width, height, closerNextInfo, verticalFartherInfo,
                                      horizontalGradientInfo, tooCloseInfo);

        // Upload all textures with optimized parameters
        uploadAllTexturesUnified(width, height, internalFormat);
    }

    /**
     * Ensures all visualization buffers have sufficient capacity.
     */
    private void ensureBufferCapacity(int requiredSize) {
        // Gradient buffer (unused but kept for compatibility)
        if (gradientRGBABuffer == null || gradientRGBABuffer.capacity() < requiredSize) {
            gradientRGBABuffer = ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder());
        }

        // Closer next buffer
        if (closerNextRGBABuffer == null || closerNextRGBABuffer.capacity() < requiredSize) {
            closerNextRGBABuffer = ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder());
        }

        // Vertical farther buffer
        if (verticalFartherRGBABuffer == null || verticalFartherRGBABuffer.capacity() < requiredSize) {
            verticalFartherRGBABuffer = ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder());
        }

        // Horizontal gradient buffer
        if (horizontalGradientRGBABuffer == null || horizontalGradientRGBABuffer.capacity() < requiredSize) {
            horizontalGradientRGBABuffer = ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder());
        }

        // Too close buffer
        if (tooCloseRGBABuffer == null || tooCloseRGBABuffer.capacity() < requiredSize) {
            tooCloseRGBABuffer = ByteBuffer.allocateDirect(requiredSize).order(ByteOrder.nativeOrder());
        }
    }

    /**
     * Clears all visualization buffers efficiently using bulk operations.
     */
    private void clearAllBuffersUnified(int bufferSize) {
        // Create a reusable zero array for efficient clearing
        byte[] zeroArray = new byte[bufferSize];
        // Alpha channel should be 255 for proper blending
        for (int i = 3; i < bufferSize; i += 4) {
            zeroArray[i] = (byte) 255;
        }

        // Clear all buffers efficiently
        gradientRGBABuffer.clear();
        gradientRGBABuffer.put(zeroArray);
        gradientRGBABuffer.rewind();

        closerNextRGBABuffer.clear();
        closerNextRGBABuffer.put(zeroArray);
        closerNextRGBABuffer.rewind();

        verticalFartherRGBABuffer.clear();
        verticalFartherRGBABuffer.put(zeroArray);
        verticalFartherRGBABuffer.rewind();

        horizontalGradientRGBABuffer.clear();
        horizontalGradientRGBABuffer.put(zeroArray);
        horizontalGradientRGBABuffer.rewind();

        tooCloseRGBABuffer.clear();
        tooCloseRGBABuffer.put(zeroArray);
        tooCloseRGBABuffer.rewind();
    }

    /**
     * Processes all visualization data in a single unified loop for maximum performance.
     * This eliminates redundant iterations over the same pixel data.
     */
    private void processVisualizationDataUnified(int width, int height,
                                               boolean[][] closerNextInfo, boolean[][] verticalFartherInfo,
                                               boolean[][] horizontalGradientInfo, boolean[][] tooCloseInfo) {

        // Single loop through all pixels - maximum efficiency
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pos = (y * width + x) * 4;

                // Process closer next pixels
                if (closerNextInfo != null && y < closerNextInfo.length && x < closerNextInfo[y].length && closerNextInfo[y][x]) {
                    closerNextRGBABuffer.put(pos, (byte) 255);
                }

                // Process vertical farther pixels
                if (verticalFartherInfo != null && y < verticalFartherInfo.length && x < verticalFartherInfo[y].length && verticalFartherInfo[y][x]) {
                    verticalFartherRGBABuffer.put(pos + 2, (byte) 255);
                }

                // Process horizontal gradient pixels
                if (horizontalGradientInfo != null && y < horizontalGradientInfo.length && x < horizontalGradientInfo[y].length && horizontalGradientInfo[y][x]) {
                    horizontalGradientRGBABuffer.put(pos, (byte) 255);
                }

                // Process too close pixels
                if (tooCloseInfo != null && y < tooCloseInfo.length && x < tooCloseInfo[y].length && tooCloseInfo[y][x]) {
                    tooCloseRGBABuffer.put(pos, (byte) 255);
                    tooCloseRGBABuffer.put(pos + 1, (byte) 0);
                    tooCloseRGBABuffer.put(pos + 2, (byte) 0);
                    tooCloseRGBABuffer.put(pos + 3, (byte) 255);
                }
            }
        }

        // Rewind all buffers once after processing
        closerNextRGBABuffer.rewind();
        verticalFartherRGBABuffer.rewind();
        horizontalGradientRGBABuffer.rewind();
        tooCloseRGBABuffer.rewind();
    }

    /**
     * Uploads all textures with optimized parameters in a single batch.
     * This reduces OpenGL state changes and improves performance.
     */
    private void uploadAllTexturesUnified(int width, int height, int internalFormat) {
        // Define texture parameters once
        int[] textureParams = {
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        };

        // Upload gradient texture
        uploadSingleTexture(gradientTextureId, gradientRGBABuffer, width, height, internalFormat, textureParams);

        // Upload closer next texture
        uploadSingleTexture(closerNextTextureId, closerNextRGBABuffer, width, height, internalFormat, textureParams);

        // Upload vertical farther texture
        uploadSingleTexture(verticalFartherTextureId, verticalFartherRGBABuffer, width, height, internalFormat, textureParams);

        // Upload horizontal gradient texture
        uploadSingleTexture(horizontalGradientTextureId, horizontalGradientRGBABuffer, width, height, internalFormat, textureParams);

        // Upload too close texture
        uploadSingleTexture(tooCloseTextureId, tooCloseRGBABuffer, width, height, internalFormat, textureParams);
    }

    /**
     * Helper method to upload a single texture with optimized parameters.
     */
    private void uploadSingleTexture(int textureId, ByteBuffer buffer, int width, int height,
                                   int internalFormat, int[] textureParams) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        // Set texture parameters efficiently
        for (int i = 0; i < textureParams.length; i += 2) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, textureParams[i], textureParams[i + 1]);
        }

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
            buffer);
    }
}
