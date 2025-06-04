package com.satinavrobotics.satibot.depth.depth_sources;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.Image;

import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import timber.log.Timber;

/**
 * Implementation of DepthImageGenerator that uses ONNX Runtime for depth estimation.
 * Optimized for performance using NNAPI on supported devices.
 */
public class ONNXDepthImageGenerator implements DepthImageGenerator {
    private static final String TAG = ONNXDepthImageGenerator.class.getSimpleName();

    // Model parameters
    private static final String MODEL_PATH = "networks/dav2_vits_indoor_optimized_fp16.onnx";
    private static final int DEFAULT_INPUT_SIZE_H = 252;
    private static final int DEFAULT_INPUT_SIZE_W = 336;
    // Thread management
    private static final int NUM_THREADS = 2; // Number of threads for CPU execution
    private ExecutorService preprocessExecutor;
    private ExecutorService inferenceExecutor;
    private ExecutorService postprocessExecutor;

    // Frame skipping for performance and stability
    private static final int FRAME_SKIP = 4; // Process every 4th frame for better stability
    private int frameCounter = 0;
    private long lastProcessedFrameTime = 0;

    // Performance tracking
    private long averageProcessingTimeMs = 0;
    private static final float PROCESSING_TIME_ALPHA = 0.3f; // For exponential moving average

    // ONNX Runtime objects
    private OrtEnvironment ortEnvironment;
    private OrtSession ortSession;
    private OrtSession.SessionOptions sessionOptions;
    private String inputName;
    private boolean isInitialized = false;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean hasNewData = new AtomicBoolean(false);

    // Runtime device selection
    private boolean useNNAPI = true;
    private String currentDevice = "Unknown";

    // Framerate calculation
    private final int FPS_WINDOW_SIZE = 20; // Number of frames to average
    private final long[] frameTimes = new long[FPS_WINDOW_SIZE];
    private int frameTimeIndex = 0;
    private float currentFPS = 0;
    private long lastFPSUpdateTime = 0;
    private static final long FPS_UPDATE_INTERVAL_MS = 500; // Update FPS every 500ms

    // Cached model bytes to avoid reloading
    private static byte[] cachedModelBytes;

    // Store context for loading models
    private Context appContext;

    // Image buffers - preallocated and reused
    private ByteBuffer inputBuffer;
    private ByteBuffer depthImageBuffer;
    private ByteBuffer confidenceImageBuffer;
    private ByteBuffer uint8Buffer; // Reusable buffer for inference
    private int[] pixelBuffer; // Reusable buffer for bitmap pixel data
    private FloatBuffer depthValuesBuffer; // Buffer for storing float depth values
    private int width;
    private int height;
    private int modelInputWidth;
    private int modelInputHeight;
    private int modelOutputWidth;
    private int modelOutputHeight;

    /**
     * Creates a new ONNXDepthImageGenerator with optimized settings.
     */
    public ONNXDepthImageGenerator() {
        // Initialize with default values
        width = 0;
        height = 0;
        // Use the 4:3 aspect ratio dimensions (252x336) for the model
        modelInputWidth = DEFAULT_INPUT_SIZE_W;  // 336
        modelInputHeight = DEFAULT_INPUT_SIZE_H; // 252
        modelOutputWidth = DEFAULT_INPUT_SIZE_W;
        modelOutputHeight = DEFAULT_INPUT_SIZE_H;

        // Setup rotation matrix if needed
        // Matrix for rotating the output if needed
        Matrix rotateTransform = new Matrix();
        rotateTransform.postRotate(90f);

        // Create thread pools for parallel processing
        preprocessExecutor = Executors.newSingleThreadExecutor();
        inferenceExecutor = Executors.newSingleThreadExecutor();
        postprocessExecutor = Executors.newFixedThreadPool(NUM_THREADS);

        // Initialize performance tracking
        averageProcessingTimeMs = 0;
    }

    @Override
    public void initialize(Context context) throws IOException {
        Timber.d("Initializing ONNX Runtime depth image generator with optimizations");

        try {
            // Store application context for later use
            this.appContext = context.getApplicationContext();

            // Initialize ONNX Runtime environment
            ortEnvironment = OrtEnvironment.getEnvironment();

            // Create session options with advanced optimizations
            sessionOptions = new OrtSession.SessionOptions();

            // Set optimization level to maximum
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            // Note: setGraphOptimizationLevel is not available in this version of ONNX Runtime
            // We'll rely on the optimization level setting above

            // Set thread options for CPU execution
            sessionOptions.setIntraOpNumThreads(NUM_THREADS);
            sessionOptions.setInterOpNumThreads(NUM_THREADS);

            // Create the session with the current settings
            createSession();

            // Get model information
            Timber.d("Model loaded: %s", MODEL_PATH);

            // Log input and output information
            Timber.d("Model inputs: %s", ortSession.getInputNames());
            Timber.d("Model outputs: %s", ortSession.getOutputNames());

            // Always use the 4:3 aspect ratio dimensions (252x336) for the model
            // as specified in the updated model requirements
            modelInputWidth = DEFAULT_INPUT_SIZE_W;  // 336
            modelInputHeight = DEFAULT_INPUT_SIZE_H; // 252
            modelOutputWidth = DEFAULT_INPUT_SIZE_W;
            modelOutputHeight = DEFAULT_INPUT_SIZE_H;

            Timber.d("Model dimensions - Input: %dx%d, Output: %dx%d",
                    modelInputWidth, modelInputHeight, modelOutputWidth, modelOutputHeight);

            // Pre-allocate all buffers for reuse - optimized for performance
            int inputBufferSize = modelInputWidth * modelInputHeight * 3;
            inputBuffer = ByteBuffer.allocateDirect(inputBufferSize);
            inputBuffer.order(ByteOrder.nativeOrder());

            uint8Buffer = ByteBuffer.allocateDirect(inputBufferSize);
            uint8Buffer.order(ByteOrder.nativeOrder());

            pixelBuffer = new int[modelInputWidth * modelInputHeight];

            // Allocate output buffers with extra capacity to prevent buffer overflow
            // Each pixel needs 2 bytes for depth (16-bit)
            // Add extra padding (1024 bytes) to prevent buffer overflow
            int depthBufferSize = Math.max(modelOutputWidth * modelOutputHeight * 2 + 1024, 131072);
            depthImageBuffer = ByteBuffer.allocateDirect(depthBufferSize);
            depthImageBuffer.order(ByteOrder.nativeOrder());
            Timber.d("Allocated depth buffer with capacity: %d bytes", depthBufferSize);

            // Each pixel needs 1 byte for confidence
            // Add extra padding (1024 bytes) to prevent buffer overflow
            int confidenceBufferSize = Math.max(modelOutputWidth * modelOutputHeight + 1024, 65536);
            confidenceImageBuffer = ByteBuffer.allocateDirect(confidenceBufferSize);
            confidenceImageBuffer.order(ByteOrder.nativeOrder());
            Timber.d("Allocated confidence buffer with capacity: %d bytes", confidenceBufferSize);

            // Allocate a FloatBuffer for storing the raw depth values from the model
            // Each pixel needs 4 bytes for a float value
            depthValuesBuffer = FloatBuffer.allocate(modelOutputWidth * modelOutputHeight);

            isInitialized = true;

        } catch (Exception e) {
            Timber.e(e, "Failed to initialize ONNX Runtime: %s", e.getMessage());
            throw new IOException("Failed to initialize ONNX Runtime", e);
        }
    }

    /**
     * Loads a model file from assets.
     */
    private byte[] loadModelFile(Context context) throws IOException {
        try {
            java.io.InputStream inputStream = context.getAssets().open(ONNXDepthImageGenerator.MODEL_PATH);
            byte[] buffer = new byte[inputStream.available()];
            inputStream.read(buffer);
            inputStream.close();
            Timber.d("Successfully loaded model file: %s", ONNXDepthImageGenerator.MODEL_PATH);
            return buffer;
        } catch (IOException e) {
            Timber.e(e, "Failed to load model file: %s", ONNXDepthImageGenerator.MODEL_PATH);
            throw e;
        }
    }


    @Override
    public boolean update(Frame frame) {
        if (!isInitialized) {
            Timber.w("ONNX model not initialized yet");
            return false;
        }

        // Get current time for performance tracking
        long currentTime = System.currentTimeMillis();

        // Only check if we're processing if the last frame was very recent
        // This allows for some parallel processing while preventing overload
        long timeSinceLastFrame = currentTime - lastProcessedFrameTime;
        if (timeSinceLastFrame < 50 && isProcessing.get()) {
            // Skip this frame if we're still processing the previous one and it was very recent
            return hasNewData.get();
        }

        // Update the last processed frame time
        lastProcessedFrameTime = currentTime;

        if (frame == null) {
            Timber.w("Frame is null, cannot update depth image");
            return hasNewData.get();
        }

        // Ensure we're not already processing before starting a new pipeline
        // This is a critical section to prevent race conditions
        if (!isProcessing.compareAndSet(false, true)) {
            // Another thread already started processing
            return hasNewData.get();
        }

        // Process directly in the calling thread for maximum stability
        // This eliminates thread synchronization issues completely
        try {
            long totalStartTime = System.currentTimeMillis();

            // Get the camera image from ARCore
            Image cameraImage;
            try {
                cameraImage = frame.acquireCameraImage();
            } catch (NotYetAvailableException e) {
                Timber.d("Camera image not yet available: %s", e.getMessage());
                isProcessing.set(false);
                return false;
            }

            // Convert camera image to bitmap with optimized method
            Bitmap inputBitmap = convertYUVImageToBitmapFast(cameraImage);
            cameraImage.close();

            if (inputBitmap == null) {
                Timber.e("Failed to convert camera image to bitmap");
                isProcessing.set(false);
                return false;
            }

            // Resize bitmap to model input size - use existing bitmap if possible
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(inputBitmap, modelInputWidth, modelInputHeight, true);
            inputBitmap.recycle();

            // Convert bitmap to input buffer - reuse existing buffer
            convertBitmapToBufferFast(resizedBitmap);

            // Set width and height for output with validation
            if (modelOutputWidth <= 0 || modelOutputHeight <= 0) {
                Timber.e("Invalid model output dimensions: %dx%d", modelOutputWidth, modelOutputHeight);
                isProcessing.set(false);
                return false;
            }

            // Set dimensions with safety checks
            width = modelOutputWidth;
            height = modelOutputHeight;

            // Log dimensions for debugging
            Timber.d("Processing frame with dimensions: %dx%d", width, height);

            // Run inference directly in the main thread for better stability
            try {
                // Run inference with optimized method
                long inferenceStartTime = System.currentTimeMillis();
                FloatBuffer depthValues = runInferenceOptimized();
                // Performance tracking
                long lastInferenceTime = System.currentTimeMillis() - inferenceStartTime;

                // Clean up bitmap after inference
                resizedBitmap.recycle();

                if (depthValues != null) {
                    // Process depth values directly in this thread
                    // This avoids thread synchronization issues
                    try {
                        // Process the float depth values directly to create depth and confidence buffers
                        processFloatDepthValues(depthValues, depthImageBuffer, confidenceImageBuffer);

                        // Update processing time metrics
                        long totalTime = System.currentTimeMillis() - totalStartTime;

                        // Update exponential moving average of processing time
                        if (averageProcessingTimeMs == 0) {
                            averageProcessingTimeMs = totalTime;
                        } else {
                            averageProcessingTimeMs = (long)(PROCESSING_TIME_ALPHA * totalTime +
                                                            (1 - PROCESSING_TIME_ALPHA) * averageProcessingTimeMs);
                        }

                        Timber.d("Total processing time: %d ms, Avg: %d ms, Inference: %d ms",
                                totalTime, averageProcessingTimeMs, lastInferenceTime);

                        // Mark that we have new data and processing is complete
                        hasNewData.set(true);

                        // Update framerate calculation
                        updateFrameRate();
                    } catch (Exception e) {
                        Timber.e(e, "Error processing depth values: %s", e.getMessage());
                    }
                } else {
                    Timber.e("Failed to generate depth values");
                }
            } catch (Exception e) {
                Timber.e(e, "Error during inference: %s", e.getMessage());
                try {
                    resizedBitmap.recycle();
                } catch (Exception ex) {
                    // Ignore
                }
            } finally {
                // Always mark processing as complete
                isProcessing.set(false);
            }
        } catch (Exception e) {
            Timber.e(e, "Error in preprocessing: %s", e.getMessage());
            isProcessing.set(false);
        }

        // Return true if we have data from a previous frame
        return hasNewData.get();
    }

    /**
     * Optimized version of inference that reuses buffers and minimizes allocations.
     * Performance-critical method - keep it as lean as possible.
     *
     * @return A FloatBuffer containing the raw depth values from the model
     */
    private FloatBuffer runInferenceOptimized() {
        // Comprehensive validation before proceeding
        if (!validateSessionState()) {
            return null;
        }

        // Validate input buffers
        if (!validateInputBuffers()) {
            return null;
        }

        OnnxTensor inputTensor = null;
        OrtSession.Result output = null;

        try {
            // The model expects NHWC format (batch, height, width, channels)
            long[] shape = {1, modelInputHeight, modelInputWidth, 3};

            // Validate shape dimensions
            if (shape[1] <= 0 || shape[2] <= 0 || shape[3] <= 0) {
                Timber.e("Invalid input shape: [%d, %d, %d, %d]", shape[0], shape[1], shape[2], shape[3]);
                return null;
            }

            // Reuse the preallocated buffer with safety checks
            if (uint8Buffer == null || inputBuffer == null) {
                Timber.e("Input buffers are null, cannot proceed with inference");
                return null;
            }

            uint8Buffer.clear();

            // Copy data from input buffer directly without intermediate byte array
            inputBuffer.rewind();

            // Validate buffer capacity before copying
            int requiredCapacity = modelInputHeight * modelInputWidth * 3;
            if (inputBuffer.capacity() < requiredCapacity) {
                Timber.e("Input buffer too small: %d < %d", inputBuffer.capacity(), requiredCapacity);
                return null;
            }

            if (uint8Buffer.capacity() < requiredCapacity) {
                Timber.e("UINT8 buffer too small: %d < %d", uint8Buffer.capacity(), requiredCapacity);
                return null;
            }

            uint8Buffer.put(inputBuffer);
            uint8Buffer.rewind();

            // Create tensor with UINT8 type - reuse the same buffer
            try {
                inputTensor = OnnxTensor.createTensor(ortEnvironment, uint8Buffer, shape, OnnxJavaType.UINT8);
            } catch (Exception e) {
                Timber.e(e, "Failed to create input tensor: %s", e.getMessage());
                return null;
            }

            // Validate input tensor
            if (inputTensor == null) {
                Timber.e("Failed to create input tensor");
                return null;
            }

            // Create input map - reuse the same input name
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            // Run inference with comprehensive error handling
            OrtSession currentSession = ortSession;
            if (currentSession == null) {
                Timber.e("OrtSession became null during inference");
                return null;
            }

            try {
                // Add memory check before inference
                Runtime runtime = Runtime.getRuntime();
                long freeMemory = runtime.freeMemory();
                long totalMemory = runtime.totalMemory();
                long usedMemory = totalMemory - freeMemory;

                // If we're using more than 80% of available memory, trigger GC
                if (usedMemory > totalMemory * 0.8) {
                    Timber.w("High memory usage detected (%d/%d), triggering GC", usedMemory, totalMemory);
                    System.gc();
                }

                output = currentSession.run(inputs);
            } catch (IllegalStateException e) {
                // This can happen if the session was closed while we were trying to use it
                Timber.e(e, "Session was closed during inference: %s", e.getMessage());
                return null;
            } catch (OutOfMemoryError e) {
                // Handle out of memory errors gracefully
                Timber.e(e, "Out of memory during inference: %s", e.getMessage());
                System.gc(); // Try to free memory
                return null;
            } catch (Exception e) {
                // Catch any other runtime exceptions from ONNX
                Timber.e(e, "Unexpected error during ONNX inference: %s", e.getMessage());
                return null;
            }

            // Get output tensor
            OnnxTensor outputTensor = (OnnxTensor) output.get(0);

            // Get the actual output dimensions from the tensor
            long[] outputShape = outputTensor.getInfo().getShape();
            int actualOutputHeight = (int) outputShape[1]; // Height is typically dimension 1 in NHWC format
            int actualOutputWidth = (int) outputShape[2];  // Width is typically dimension 2 in NHWC format
            Timber.d("Output tensor shape: " + actualOutputHeight + " * " + actualOutputWidth);

            // Update our model output dimensions if they don't match what we expected
            if (actualOutputWidth != modelOutputWidth || actualOutputHeight != modelOutputHeight) {
                modelOutputWidth = actualOutputWidth;
                modelOutputHeight = actualOutputHeight;
            }

            // Get the data type of the output tensor
            OnnxJavaType dataType = outputTensor.getInfo().type;
            Timber.d("Output tensor data type: %s", dataType);

            // Extract the float values directly from the tensor
            FloatBuffer depthValues;

            if (dataType == OnnxJavaType.FLOAT) {
                // If the output is already float, get it directly
                depthValues = outputTensor.getFloatBuffer();
            } else {
                // If it's not float, convert it
                ByteBuffer outputBuffer = outputTensor.getByteBuffer();
                depthValues = FloatBuffer.allocate(actualOutputWidth * actualOutputHeight);

                // Convert based on the data type
                if (dataType == OnnxJavaType.UINT8) {
                    for (int i = 0; i < actualOutputWidth * actualOutputHeight; i++) {
                        depthValues.put(i, (outputBuffer.get(i) & 0xFF) / 255.0f);
                    }
                } else {
                    Timber.e("Unsupported output tensor data type: %s", dataType);
                    return null;
                }
            }

            // Create a copy of the depth values since we're closing the tensor
            // Also apply basic filtering here to catch extreme values early
            FloatBuffer depthValuesCopy = FloatBuffer.allocate(depthValues.capacity());
            depthValues.rewind();

            // Copy values with basic filtering
            for (int i = 0; i < depthValues.capacity(); i++) {
                float value = depthValues.get(i);

                // Apply basic filtering to catch extreme values
                // Clamp to reasonable range (0.1-10 meters)
                value = Math.max(0.1f, Math.min(10.0f, value));

                // Store filtered value
                depthValuesCopy.put(i, value);
            }

            depthValuesCopy.rewind();

            // Apply rotation if needed (not implemented for FloatBuffer yet)
            // For now, we'll handle rotation in the processing step if needed

            return depthValuesCopy;

        } catch (Exception e) {
            Timber.e(e, "Unexpected error during inference: %s", e.getMessage());
            return null;
        } finally {
            // Clean up resources in finally block to ensure they're always closed
            try {
                if (inputTensor != null) {
                    inputTensor.close();
                }
                if (output != null) {
                    output.close();
                }
            } catch (Exception e) {
                Timber.w(e, "Error closing tensors: %s", e.getMessage());
            }
        }
    }

    // Reusable buffers for YUV conversion
    private byte[] yuvBuffer;
    private int[] rgbBuffer;

    /**
     * Optimized YUV to RGB conversion that directly accesses image planes and avoids JPEG compression.
     * This is much faster than the previous implementation.
     */
    private Bitmap convertYUVImageToBitmapFast(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            Timber.e("Unsupported image format: %d", image.getFormat());
            return null;
        }

        final int imageWidth = image.getWidth();
        final int imageHeight = image.getHeight();

        // Allocate buffers if needed or reuse existing ones
        if (yuvBuffer == null || yuvBuffer.length < imageWidth * imageHeight * 3 / 2) {
            yuvBuffer = new byte[imageWidth * imageHeight * 3 / 2];
        }

        if (rgbBuffer == null || rgbBuffer.length < imageWidth * imageHeight) {
            rgbBuffer = new int[imageWidth * imageHeight];
        }

        // Get image planes
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        // Get plane strides
        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        // Copy Y plane directly - this is the most efficient way
        int yPos = 0;
        for (int i = 0; i < imageHeight; i++) {
            int yOffset = i * yRowStride;
            for (int j = 0; j < imageWidth; j++) {
                yuvBuffer[yPos++] = yBuffer.get(yOffset + j);
            }
        }

        // Copy U and V planes with pixel stride consideration
        int uvPos = imageWidth * imageHeight;
        int uvHeight = imageHeight / 2;
        int uvWidth = imageWidth / 2;
        for (int i = 0; i < uvHeight; i++) {
            int uvOffset = i * uvRowStride;
            for (int j = 0; j < uvWidth; j++) {
                yuvBuffer[uvPos++] = vBuffer.get(uvOffset + j * uvPixelStride); // V plane
                yuvBuffer[uvPos++] = uBuffer.get(uvOffset + j * uvPixelStride); // U plane
            }
        }

        // Convert YUV to RGB directly
        convertYUV420ToRGB(yuvBuffer, rgbBuffer, imageWidth, imageHeight);

        // Create bitmap from RGB buffer
        Bitmap bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(rgbBuffer, 0, imageWidth, 0, 0, imageWidth, imageHeight);

        return bitmap;
    }

    /**
     * Fast YUV420 to RGB conversion using lookup tables and direct array access.
     */
    private void convertYUV420ToRGB(byte[] yuv, int[] rgb, int width, int height) {
        final int frameSize = width * height;

        for (int j = 0; j < height; j++) {
            int yp = j * width;
            int uvp = frameSize + (j >> 1) * width;
            int u = 0, v = 0;

            for (int i = 0; i < width; i++) {
                int y = (0xff & yuv[yp]) - 16;
                if ((i & 1) == 0) {
                    v = (0xff & yuv[uvp++]) - 128;
                    u = (0xff & yuv[uvp++]) - 128;
                }

                // Fast YUV to RGB conversion
                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);

                // Clipping RGB values to be inside [0-255]
                r = r > 262143 ? 255 : (r < 0 ? 0 : r >> 10);
                g = g > 262143 ? 255 : (g < 0 ? 0 : g >> 10);
                b = b > 262143 ? 255 : (b < 0 ? 0 : b >> 10);

                // Combine RGB into pixel
                rgb[yp] = 0xff000000 | (r << 16) | (g << 8) | b;
                yp++;
            }
        }
    }


    /**
     * Optimized version of bitmap to buffer conversion that reuses the pixel array.
     * Stores RGB values as unsigned bytes (0-255) as required by the model.
     */
    private void convertBitmapToBufferFast(Bitmap bitmap) {
        inputBuffer.clear();

        // Reuse the preallocated pixel buffer
        bitmap.getPixels(pixelBuffer, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        // Process pixels in batches for better cache locality
        Timber.d("Buffer length " + pixelBuffer.length + " ");
        for (int i = 0; i < pixelBuffer.length; i++) {
            int pixel = pixelBuffer[i];
            // Extract RGB values and store in buffer (RGB order)
            // Note: In Java, byte is signed (-128 to 127), but the model expects unsigned (0-255)
            // The & 0xFF operation ensures the values are treated as unsigned when read back
            inputBuffer.put((byte) (Color.red(pixel) & 0xFF));
            inputBuffer.put((byte) (Color.green(pixel) & 0xFF));
            inputBuffer.put((byte) (Color.blue(pixel) & 0xFF));
        }

        inputBuffer.rewind();
    }

    /**
     * Process float depth values from the model output.
     * Completely rewritten to be bulletproof against buffer overflow issues.
     */
    private void processFloatDepthValues(FloatBuffer depthValues, ByteBuffer depthBuffer, ByteBuffer confidenceBuffer) {
        // Log entry for debugging
        Timber.d("processFloatDepthValues called");

        try {
            // Validate all inputs
            if (depthValues == null) {
                Timber.e("Depth values buffer is null");
                return;
            }

            if (depthBuffer == null) {
                Timber.e("Depth buffer is null");
                return;
            }

            if (confidenceBuffer == null) {
                Timber.e("Confidence buffer is null");
                return;
            }

            // Validate dimensions
            if (width <= 0 || height <= 0) {
                Timber.e("Invalid dimensions: %dx%d", width, height);
                return;
            }

            // Calculate pixel count
            final int pixelCount = width * height;
            Timber.d("Processing %d pixels (%dx%d)", pixelCount, width, height);

            // Calculate required buffer sizes with extra padding
            final int requiredDepthSize = pixelCount * 2 + 2048; // Extra 2KB padding
            final int requiredConfidenceSize = pixelCount + 2048; // Extra 2KB padding

            // Reuse existing buffers if they're large enough, otherwise create new ones
            ByteBuffer newDepthBuffer;
            ByteBuffer newConfidenceBuffer;

            if (depthBuffer.capacity() >= requiredDepthSize) {
                // Reuse existing buffer
                newDepthBuffer = depthBuffer;
                newDepthBuffer.clear(); // Reset position and limit
            } else {
                // Create new buffer
                newDepthBuffer = ByteBuffer.allocateDirect(requiredDepthSize);
                newDepthBuffer.order(ByteOrder.nativeOrder());
                Timber.d("Created new depth buffer: %d bytes", newDepthBuffer.capacity());
            }

            if (confidenceBuffer.capacity() >= requiredConfidenceSize) {
                // Reuse existing buffer
                newConfidenceBuffer = confidenceBuffer;
                newConfidenceBuffer.clear(); // Reset position and limit
            } else {
                // Create new buffer
                newConfidenceBuffer = ByteBuffer.allocateDirect(requiredConfidenceSize);
                newConfidenceBuffer.order(ByteOrder.nativeOrder());
                Timber.d("Created new confidence buffer: %d bytes", newConfidenceBuffer.capacity());
            }

            // Validate depth values buffer capacity
            final int depthValuesCount = Math.min(pixelCount, depthValues.capacity());
            if (depthValuesCount < pixelCount) {
                Timber.w("Depth values buffer smaller than pixel count: %d < %d",
                        depthValuesCount, pixelCount);
            }

            // Reset depth values position
            depthValues.rewind();

            // Process depth values directly into the new buffers
            // No intermediate arrays to reduce potential issues
            for (int i = 0; i < depthValuesCount; i++) {
                // Get and filter depth value
                float depthMeters = depthValues.get(i);
                depthMeters = Math.max(0.1f, Math.min(10.0f, depthMeters));

                // Convert to millimeters
                short depthMm = (short)(depthMeters * 1000.0f);

                // Calculate confidence
                float confidenceValue = 1.0f;

                // Write to buffers with bounds checking
                if (newDepthBuffer.position() + 2 <= newDepthBuffer.capacity()) {
                    newDepthBuffer.putShort(depthMm);
                }

                if (newConfidenceBuffer.position() + 1 <= newConfidenceBuffer.capacity()) {
                    newConfidenceBuffer.put((byte)(confidenceValue * 255));
                }
            }

            // Fill remaining pixels with zeros if depth values buffer was too small
            for (int i = depthValuesCount; i < pixelCount; i++) {
                if (newDepthBuffer.position() + 2 <= newDepthBuffer.capacity()) {
                    newDepthBuffer.putShort((short)0);
                }

                if (newConfidenceBuffer.position() + 1 <= newConfidenceBuffer.capacity()) {
                    newConfidenceBuffer.put((byte)0);
                }
            }

            // Reset buffer positions for reading
            newDepthBuffer.flip();
            newConfidenceBuffer.flip();

            // If we created new buffers, update the references
            if (newDepthBuffer != depthBuffer) {
                depthImageBuffer = newDepthBuffer;
            } else {
                // We reused the existing buffer, so just update the reference
                depthImageBuffer = depthBuffer;
            }

            if (newConfidenceBuffer != confidenceBuffer) {
                confidenceImageBuffer = newConfidenceBuffer;
            } else {
                // We reused the existing buffer, so just update the reference
                confidenceImageBuffer = confidenceBuffer;
            }

            Timber.d("Successfully processed depth values");

        } catch (Exception e) {
            // Catch any possible exception
            Timber.e(e, "Error in processFloatDepthValues: %s", e.getMessage());

            // Create emergency fallback buffers
            try {
                // Calculate minimum required sizes
                final int minPixelCount = Math.max(1, width * height);

                // Create minimal buffers
                ByteBuffer emergencyDepthBuffer = ByteBuffer.allocateDirect(minPixelCount * 2 + 1024);
                emergencyDepthBuffer.order(ByteOrder.nativeOrder());

                ByteBuffer emergencyConfidenceBuffer = ByteBuffer.allocateDirect(minPixelCount + 1024);
                emergencyConfidenceBuffer.order(ByteOrder.nativeOrder());

                // Fill with zeros
                for (int i = 0; i < minPixelCount; i++) {
                    emergencyDepthBuffer.putShort((short)0);
                    emergencyConfidenceBuffer.put((byte)0);
                }

                // Reset positions
                emergencyDepthBuffer.rewind();
                emergencyConfidenceBuffer.rewind();

                // Update instance variables
                depthImageBuffer = emergencyDepthBuffer;
                confidenceImageBuffer = emergencyConfidenceBuffer;

                Timber.d("Created emergency fallback buffers");

            } catch (Exception ex) {
                // Last resort - if even this fails, set buffers to null
                Timber.e(ex, "Emergency buffer creation failed: %s", ex.getMessage());
                depthImageBuffer = null;
                confidenceImageBuffer = null;
            }
        }
    }

    @Override
    public ByteBuffer getDepthImageData() {
        return depthImageBuffer;
    }

    @Override
    public ByteBuffer getConfidenceImageData() {
        return confidenceImageBuffer;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void release() {
        if (ortSession != null) {
            try {
                ortSession.close();
                ortSession = null;
            } catch (OrtException e) {
                Timber.e(e, "Error closing ONNX session: %s", e.getMessage());
            }
        }

        if (sessionOptions != null) {
            try {
                sessionOptions.close();
                sessionOptions = null;
            } catch (Exception e) {
                Timber.e(e, "Error closing session options: %s", e.getMessage());
            }
        }

        if (ortEnvironment != null) {
            try {
                ortEnvironment.close();
                ortEnvironment = null;
            } catch (Exception e) {
                Timber.e(e, "Error closing ONNX environment: %s", e.getMessage());
            }
        }

        // Shutdown thread pools
        if (preprocessExecutor != null) {
            preprocessExecutor.shutdown();
            preprocessExecutor = null;
        }

        if (inferenceExecutor != null) {
            inferenceExecutor.shutdown();
            inferenceExecutor = null;
        }

        if (postprocessExecutor != null) {
            postprocessExecutor.shutdown();
            postprocessExecutor = null;
        }

        // Release all buffers
        inputBuffer = null;
        depthImageBuffer = null;
        confidenceImageBuffer = null;
        uint8Buffer = null;
        pixelBuffer = null;
        depthValuesBuffer = null;
        yuvBuffer = null;
        rgbBuffer = null;

        isInitialized = false;

        // Note: We don't clear cachedModelBytes to keep it for future instances

        Timber.d("ONNX resources released");
    }


    @Override
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Enable or disable NNAPI at runtime.
     * This will recreate the ONNX session with the new settings.
     *
     * @param enable Whether to enable NNAPI
     * @return True if the operation was successful, false otherwise
     */
    public boolean setUseNNAPI(boolean enable) {
        if (this.useNNAPI == enable) {
            return true; // No change needed
        }

        this.useNNAPI = enable;

        // Only recreate the session if already initialized
        if (isInitialized && appContext != null) {
            try {
                // Set a flag to prevent new processing while we're switching
                isProcessing.set(true);

                // Store the current session in a temporary variable
                OrtSession oldSession = ortSession;

                try {
                    // Create the new session first before closing the old one
                    // This ensures we always have a valid session
                    createSession();

                    // Now that we have a new session, close the old one if it exists
                    if (oldSession != null) {
                        try {
                            oldSession.close();
                        } catch (Exception e) {
                            Timber.w(e, "Error closing old session: %s", e.getMessage());
                            // Continue even if there's an error closing the old session
                        }
                    }

                    // Reset FPS counter when device is changed
                    resetFPSCounter();

                    return true;
                } finally {
                    // Always allow processing to continue
                    isProcessing.set(false);
                }
            } catch (Exception e) {
                Timber.e(e, "Failed to switch execution provider: %s", e.getMessage());
                // Make sure processing is allowed to continue even if there's an error
                isProcessing.set(false);
                return false;
            }
        }

        return true;
    }

    /**
     * Resets the FPS counter and frame times.
     * This should be called when the device is changed.
     */
    private void resetFPSCounter() {
        // Reset all frame times
        Arrays.fill(frameTimes, 0);
        frameTimeIndex = 0;
        currentFPS = 0;
        lastFPSUpdateTime = 0;

        Timber.d("FPS counter reset after device change");
    }

    /**
     * Get the current device being used for inference.
     *
     * @return A string describing the current device (e.g., "NNAPI", "CPU (2 threads)")
     */
    public String getCurrentDevice() {
        return currentDevice;
    }

    /**
     * Get the current framerate based on the last several frames.
     *
     * @return The current framerate in frames per second
     */
    public float getCurrentFPS() {
        return currentFPS;
    }

    /**
     * Update the framerate calculation with a new frame time.
     * This should be called after each frame is processed.
     */
    private void updateFrameRate() {
        long currentTime = System.currentTimeMillis();

        // Record the frame time
        frameTimes[frameTimeIndex] = currentTime;
        frameTimeIndex = (frameTimeIndex + 1) % FPS_WINDOW_SIZE;

        // Only update FPS periodically to avoid excessive calculations
        if (currentTime - lastFPSUpdateTime > FPS_UPDATE_INTERVAL_MS) {
            // Find the oldest frame in our window
            int oldestIndex = frameTimeIndex;
            long oldestTime = frameTimes[oldestIndex];

            // Find the actual oldest time in our circular buffer
            for (int i = 0; i < FPS_WINDOW_SIZE; i++) {
                if (frameTimes[i] > 0 && (oldestTime == 0 || frameTimes[i] < oldestTime)) {
                    oldestTime = frameTimes[i];
                    oldestIndex = i;
                }
            }

            // Calculate FPS if we have enough frames
            if (oldestTime > 0 && currentTime > oldestTime) {
                // Count how many valid frame times we have
                int frameCount = 0;
                for (long frameTime : frameTimes) {
                    if (frameTime > 0) {
                        frameCount++;
                    }
                }

                // Calculate FPS based on the time difference and frame count
                float timeSpanSeconds = (currentTime - oldestTime) / 1000.0f;
                if (timeSpanSeconds > 0) {
                    currentFPS = frameCount / timeSpanSeconds;
                }
            }

            lastFPSUpdateTime = currentTime;
        }
    }

    /**
     * Create a new ONNX session with the current settings.
     * This is extracted from initialize() to allow runtime switching.
     */
    private void createSession() throws IOException {
        OrtSession newSession = null;

        try {
            // Load or use cached model
            byte[] modelBytes;
            if (cachedModelBytes == null) {
                modelBytes = loadModelFile(appContext);
                cachedModelBytes = modelBytes; // Cache for future use
            } else {
                modelBytes = cachedModelBytes;
                Timber.d("Using cached model bytes");
            }

            if (useNNAPI) {
                // Try to use NNAPI
                try {
                    OrtSession.SessionOptions nnapiOptions = new OrtSession.SessionOptions();
                    nnapiOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    nnapiOptions.setIntraOpNumThreads(NUM_THREADS);
                    nnapiOptions.setInterOpNumThreads(NUM_THREADS);

                    // Create session with NNAPI but don't assign to ortSession yet
                    newSession = ortEnvironment.createSession(modelBytes, nnapiOptions);
                    sessionOptions = nnapiOptions;
                    currentDevice = "NNAPI";
                    Timber.d("Successfully created ONNX session with NNAPI provider");
                } catch (Exception e) {
                    Timber.d("NNAPI not available or failed, falling back to CPU: %s", e.getMessage());
                    useNNAPI = false; // Auto-disable if it fails
                    newSession = createCPUSessionInternal(modelBytes);
                }
            } else {
                // Use CPU
                newSession = createCPUSessionInternal(modelBytes);
            }

            // Only if we successfully created a new session, update the class variable
            // Get input name from the new session
            inputName = newSession.getInputNames().iterator().next();
            Timber.d("Using input name: %s", inputName);

            // Now it's safe to update the class variable
            ortSession = newSession;

        } catch (Exception e) {
            Timber.e(e, "Failed to create ONNX session: %s", e.getMessage());
            throw new IOException("Failed to create ONNX session", e);
        }
    }

    /**
     * Create a CPU-based ONNX session and return it without assigning to the class variable.
     * This allows us to validate the session before assigning it.
     */
    private OrtSession createCPUSessionInternal(byte[] modelBytes) throws OrtException {
        OrtSession.SessionOptions cpuOptions = new OrtSession.SessionOptions();
        cpuOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        cpuOptions.setIntraOpNumThreads(NUM_THREADS);
        cpuOptions.setInterOpNumThreads(NUM_THREADS);

        // Enable optimizations for CPU
        try {
            cpuOptions.addCPU(true); // Use all available cores
            Timber.d("Added CPU execution provider with default settings");
        } catch (NoSuchMethodError ne) {
            Timber.d("CPU optimization methods not available: %s", ne.getMessage());
        }

        // Create session with CPU
        OrtSession newSession = ortEnvironment.createSession(modelBytes, cpuOptions);
        sessionOptions = cpuOptions;
        currentDevice = "CPU (" + NUM_THREADS + " threads)";
        Timber.d("Successfully created ONNX session with CPU provider");

        return newSession;
    }

    /**
     * Validates the current session state to ensure it's safe to run inference.
     * @return true if the session is valid and ready for inference
     */
    private boolean validateSessionState() {
        if (ortSession == null) {
            Timber.e("OrtSession is null");
            return false;
        }

        if (ortEnvironment == null) {
            Timber.e("OrtEnvironment is null");
            return false;
        }

        if (inputName == null || inputName.isEmpty()) {
            Timber.e("Input name is null or empty");
            return false;
        }

        // Check if the session is still valid by trying to get input names
        try {
            Set<String> inputNames = ortSession.getInputNames();
            if (inputNames == null || inputNames.isEmpty()) {
                Timber.e("Session has no input names");
                return false;
            }
            if (!inputNames.contains(inputName)) {
                Timber.e("Session does not contain expected input name: %s", inputName);
                return false;
            }
        } catch (Exception e) {
            Timber.e(e, "Error validating session state: %s", e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * Validates the input buffers to ensure they're properly allocated and sized.
     * @return true if the input buffers are valid
     */
    private boolean validateInputBuffers() {
        if (inputBuffer == null) {
            Timber.e("Input buffer is null");
            return false;
        }

        if (uint8Buffer == null) {
            Timber.e("UINT8 buffer is null");
            return false;
        }

        int requiredCapacity = modelInputHeight * modelInputWidth * 3;

        if (inputBuffer.capacity() < requiredCapacity) {
            Timber.e("Input buffer capacity insufficient: %d < %d", inputBuffer.capacity(), requiredCapacity);
            return false;
        }

        if (uint8Buffer.capacity() < requiredCapacity) {
            Timber.e("UINT8 buffer capacity insufficient: %d < %d", uint8Buffer.capacity(), requiredCapacity);
            return false;
        }

        if (modelInputWidth <= 0 || modelInputHeight <= 0) {
            Timber.e("Invalid model input dimensions: %dx%d", modelInputWidth, modelInputHeight);
            return false;
        }

        return true;
    }

}
