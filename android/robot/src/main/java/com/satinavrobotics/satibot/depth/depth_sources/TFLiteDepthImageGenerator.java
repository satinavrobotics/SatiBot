package com.satinavrobotics.satibot.depth.depth_sources;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tflite.gpu.support.TfLiteGpu;
import com.google.ar.core.Frame;
import com.google.ar.core.exceptions.NotYetAvailableException;

// Play Services TFLite imports
import com.google.android.gms.tflite.java.TfLite;
import com.google.android.gms.tflite.client.TfLiteInitializationOptions;

import org.tensorflow.lite.InterpreterApi;
import org.tensorflow.lite.InterpreterApi.Options;
import org.tensorflow.lite.InterpreterApi.Options.TfLiteRuntime;
import org.tensorflow.lite.gpu.GpuDelegateFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Implementation of DepthImageGenerator that uses TensorFlow Lite for depth estimation.
 */
public class TFLiteDepthImageGenerator implements DepthImageGenerator {
    private static final String TAG = TFLiteDepthImageGenerator.class.getSimpleName();

    // Model parameters
    private static final String MODEL_PATH = "networks/depth_anything_v2_metric_indoors_vitb.tflite";
    private static final int INPUT_SIZE = 224; // Default input size, will be updated based on model

    // Static TFLite initialization state
    private static boolean tfLiteInitialized = false;
    private static final Object tfLiteInitLock = new Object();

    // TFLite objects
    private InterpreterApi interpreter;
    private boolean isInitialized = false;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    // Image buffers
    private ByteBuffer inputBuffer;
    private ByteBuffer depthImageBuffer;
    private ByteBuffer confidenceImageBuffer;
    private int width;
    private int height;
    private int modelInputWidth;
    private int modelInputHeight;

    /**
     * Creates a new TFLiteDepthImageGenerator.
     */
    public TFLiteDepthImageGenerator() {
        // Initialize with default values
        width = 0;
        height = 0;
        modelInputWidth = INPUT_SIZE;
        modelInputHeight = INPUT_SIZE;

        // Add a test log to verify Timber is working
        Timber.i("TFLiteDepthImageGenerator initialized");
    }

    @Override
    public void initialize(Context context) throws IOException {
        Timber.d("Initializing TFLiteDepthImageGenerator");

        // Use the static method to initialize TensorFlow Lite
        initializeTFLite(context, () -> {
            // This callback will be called when TFLite initialization completes (success or failure)
            if (isTFLiteInitialized()) {
                Timber.d("TFLite initialization successful, loading model");
                try {
                    loadModel(context);
                } catch (IOException e) {
                    Timber.e(e, "Failed to load model: %s", e.getMessage());
                } catch (IllegalArgumentException e) {
                    Timber.e(e, "Failed to initialize model: %s", e.getMessage());
                }
            } else {
                Timber.e("TFLite initialization failed, cannot load model");
            }
        });
    }

    /**
     * Loads the TFLite model.
     */
    private void loadModel(Context context) throws IOException {
        // Check if GPU delegate is available
        Timber.d("GPU task init");
        Task<Boolean> useGpuTask = TfLiteGpu.isGpuDelegateAvailable(context);
        useGpuTask.addOnSuccessListener(new OnSuccessListener<Boolean>() {
            @Override
            public void onSuccess(Boolean isGpuAvailable) {
                try {
                    Timber.d("GPU answer found");
                    // Create interpreter options first
                    Options interpreterOptions = new Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY);

                    // Add GPU delegate if available
                    if (isGpuAvailable) {
                        Timber.d("GPU delegate is available, using GPU acceleration");
                        // Use the Play Services GPU delegate
                        interpreterOptions.addDelegateFactory(new GpuDelegateFactory());
                    } else {
                        Timber.d("GPU delegate is not available, using CPU");
                    }

                    // Load model in chunks to avoid OOM
                    System.gc(); // Request garbage collection before loading

                    try {
                        // Load model from assets
                        Timber.d("Loading model from assets");
                        ByteBuffer modelBuffer = loadModelFileInChunks(context, MODEL_PATH);

                        // Create interpreter with custom configuration
                        try {
                            interpreter = InterpreterApi.create(modelBuffer, interpreterOptions);
                        } catch (IllegalArgumentException e) {
                            Options newOptions = new Options().setRuntime(TfLiteRuntime.FROM_SYSTEM_ONLY);
                            interpreter = InterpreterApi.create(modelBuffer, newOptions);
                        }

                        // Get input and output tensor shapes
                        int[] inputShape = interpreter.getInputTensor(0).shape();
                        modelInputHeight = inputShape[1];
                        modelInputWidth = inputShape[2];

                        // Allocate input buffer
                        inputBuffer = ByteBuffer.allocateDirect(modelInputHeight * modelInputWidth * 3 * 4); // 3 channels, 4 bytes per float
                        inputBuffer.order(ByteOrder.nativeOrder());

                        // Set initialized flag
                        isInitialized = true;
                        Timber.d("Model loaded successfully with input dimensions: %d x %d", modelInputWidth, modelInputHeight);

                        // Force a garbage collection to free up memory
                        System.gc();
                    } catch (OutOfMemoryError e) {
                        Timber.e(e, "Out of memory while loading model: %s", e.getMessage());
                        // Try to recover
                        System.gc();
                        isInitialized = false;
                    }
                } catch (IOException e) {
                    Timber.e(e, "Failed to load model: %s", e.getMessage());
                }
            }
        });
    }

    /**
     * Loads a model file from assets.
     */
    private ByteBuffer loadModelFile(Context context, String modelPath) throws IOException {
        java.io.InputStream inputStream = context.getAssets().open(modelPath);
        byte[] buffer = new byte[inputStream.available()];
        inputStream.read(buffer);
        inputStream.close();

        ByteBuffer modelBuffer = ByteBuffer.allocateDirect(buffer.length);
        modelBuffer.order(ByteOrder.nativeOrder());
        modelBuffer.put(buffer);
        modelBuffer.rewind();

        return modelBuffer;
    }

    /**
     * Loads a model file from assets in chunks to avoid OOM errors.
     */
    private ByteBuffer loadModelFileInChunks(Context context, String modelPath) throws IOException {
        java.io.InputStream inputStream = context.getAssets().open(modelPath);

        // Get the file size
        int fileSize = inputStream.available();
        Timber.d("Model file size: %d bytes", fileSize);

        // Create a direct ByteBuffer for the entire model
        ByteBuffer modelBuffer = ByteBuffer.allocateDirect(fileSize);
        modelBuffer.order(ByteOrder.nativeOrder());

        // Read the file in chunks
        byte[] chunk = new byte[1024 * 1024]; // 1MB chunks
        int bytesRead;
        int totalBytesRead = 0;

        while ((bytesRead = inputStream.read(chunk, 0, chunk.length)) != -1) {
            modelBuffer.put(chunk, 0, bytesRead);
            totalBytesRead += bytesRead;

            // Log progress
            if (totalBytesRead % (10 * 1024 * 1024) == 0) { // Log every 10MB
                Timber.d("Loaded %d MB of %d MB", totalBytesRead / (1024 * 1024), fileSize / (1024 * 1024));
                // Force garbage collection to free up memory
                System.gc();
            }
        }

        inputStream.close();
        modelBuffer.rewind();
        Timber.d("Model loaded successfully: %d bytes", totalBytesRead);

        return modelBuffer;
    }

    @Override
    public boolean update(Frame frame) {

        Timber.d("Update");
        if (!isInitialized) {
            Timber.w("TFLite model not initialized yet");
            return false;
        }

        if (isProcessing.get()) {
            Timber.d("Already processing a frame, skipping");
            return false;
        }

        if (frame == null) {
            Timber.w("Frame is null, cannot update depth image");
            return false;
        }

        try {
            isProcessing.set(true);

            // Get the camera image from ARCore
            Image cameraImage = frame.acquireCameraImage();

            // Convert camera image to bitmap
            Bitmap inputBitmap = convertYUVImageToBitmap(cameraImage);
            cameraImage.close();

            if (inputBitmap == null) {
                Timber.e("Failed to convert camera image to bitmap");
                isProcessing.set(false);
                return false;
            }

            // Resize bitmap to model input size
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(inputBitmap, modelInputWidth, modelInputHeight, true);
            inputBitmap.recycle();

            // Convert bitmap to input buffer
            convertBitmapToBuffer(resizedBitmap, inputBuffer);
            resizedBitmap.recycle();

            // Set width and height
            width = modelInputWidth;
            height = modelInputHeight;

            // Create output buffers if needed
            if (depthImageBuffer == null || depthImageBuffer.capacity() != width * height * 2) {
                depthImageBuffer = ByteBuffer.allocateDirect(width * height * 2);
                depthImageBuffer.order(ByteOrder.nativeOrder());
            }

            if (confidenceImageBuffer == null || confidenceImageBuffer.capacity() != width * height) {
                confidenceImageBuffer = ByteBuffer.allocateDirect(width * height);
                confidenceImageBuffer.order(ByteOrder.nativeOrder());
            }

            // Create output buffer for model
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(width * height * 4); // 1 channel, 4 bytes per float
            outputBuffer.order(ByteOrder.nativeOrder());

            try {
                // Run inference
                Timber.d("Running inference on image of size %dx%d", width, height);
                interpreter.run(inputBuffer, outputBuffer);

                // Process output to create depth and confidence buffers
                processModelOutput(outputBuffer, depthImageBuffer, confidenceImageBuffer);
            } catch (OutOfMemoryError e) {
                Timber.e(e, "Out of memory during inference: %s", e.getMessage());
                // Try to recover
                System.gc();
                isProcessing.set(false);
                return false;
            } finally {
                // Ensure we clean up resources
                outputBuffer = null;
                System.gc();
            }

            isProcessing.set(false);
            return true;

        } catch (NotYetAvailableException e) {
            Timber.d("Camera image not yet available: %s", e.getMessage());
            isProcessing.set(false);
            return false;
        } catch (Exception e) {
            Timber.e(e, "Error updating depth image: %s", e.getMessage());
            isProcessing.set(false);
            return false;
        }
    }

    /**
     * Converts a YUV image to a bitmap.
     */
    private Bitmap convertYUVImageToBitmap(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            Timber.e("Unsupported image format: %d", image.getFormat());
            return null;
        }

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // U and V are swapped
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 100, out);

        byte[] imageBytes = out.toByteArray();
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    /**
     * Converts a bitmap to a float buffer for model input.
     */
    private void convertBitmapToBuffer(Bitmap bitmap, ByteBuffer buffer) {
        buffer.rewind();

        int[] intValues = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        // Convert the image to floating point
        for (final int val : intValues) {
            // Extract RGB values
            buffer.putFloat(((val >> 16) & 0xFF) / 255.0f);
            buffer.putFloat(((val >> 8) & 0xFF) / 255.0f);
            buffer.putFloat((val & 0xFF) / 255.0f);
        }
    }

    /**
     * Processes model output to create depth and confidence buffers.
     */
    private void processModelOutput(ByteBuffer modelOutput, ByteBuffer depthBuffer, ByteBuffer confidenceBuffer) {
        modelOutput.rewind();
        depthBuffer.rewind();
        confidenceBuffer.rewind();

        // Process each pixel
        for (int i = 0; i < width * height; i++) {
            float depthValue = modelOutput.getFloat();

            // Convert depth to 16-bit format (similar to ARCore depth format)
            short depth16bit = (short)(depthValue * 1000); // Convert to millimeters

            // Write to depth buffer
            depthBuffer.putShort(depth16bit);

            // Calculate confidence based on depth value
            // This is a simple heuristic - you may want to adjust based on your model
            byte confidence = (byte)(Math.min(1.0f, Math.max(0.0f, 1.0f - Math.abs(depthValue - 0.5f) * 2)) * 255);
            confidenceBuffer.put(confidence);
        }

        depthBuffer.rewind();
        confidenceBuffer.rewind();
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
        if (interpreter != null) {
            try {
                interpreter.close();
            } catch (Exception e) {
                Timber.e(e, "Error closing interpreter: %s", e.getMessage());
            }
            interpreter = null;
        }

        // Release all buffers
        inputBuffer = null;
        depthImageBuffer = null;
        confidenceImageBuffer = null;

        isInitialized = false;

        // Force garbage collection
        System.gc();
        Timber.d("Resources released");
    }


    @Override
    public boolean isInitialized() {
        return isInitialized;
    }


    /**
     * Checks if TensorFlow Lite is initialized.
     *
     * @return true if TensorFlow Lite is initialized, false otherwise
     */
    public static boolean isTFLiteInitialized() {
        synchronized (tfLiteInitLock) {
            return tfLiteInitialized;
        }
    }

    /**
     * Initializes TensorFlow Lite using the Play Services API.
     * This is a static method that initializes TensorFlow Lite for the entire app.
     *
     * @param context The application context
     * @param onInitializationComplete Callback to run when initialization completes (success or failure)
     */
    public static void initializeTFLite(Context context, Runnable onInitializationComplete) {
        synchronized (tfLiteInitLock) {
            // If already initialized, just run the callback
            if (tfLiteInitialized) {
                Timber.d("TensorFlow Lite already initialized");
                if (onInitializationComplete != null) {
                    onInitializationComplete.run();
                }
                return;
            }

            Timber.d("Initializing TensorFlow Lite...");

            // Build initialization options with GPU support enabled
            TfLiteInitializationOptions options = TfLiteInitializationOptions.builder()
                    .setEnableGpuDelegateSupport(true)
                    .build();

            // Initialize the TFLite runtime asynchronously
            Task<Void> initializeTask = TfLite.initialize(context, options);

            initializeTask.addOnSuccessListener(aVoid -> {
                synchronized (tfLiteInitLock) {
                    tfLiteInitialized = true;
                }
                Timber.d("TensorFlow Lite initialized successfully");

                // Run the callback
                if (onInitializationComplete != null) {
                    onInitializationComplete.run();
                }
            }).addOnFailureListener(e -> {
                synchronized (tfLiteInitLock) {
                    tfLiteInitialized = false;
                }
                Timber.e(e, "Failed to initialize TensorFlow Lite: %s", e.getMessage());

                // Run the callback even on failure
                if (onInitializationComplete != null) {
                    onInitializationComplete.run();
                }
            });
        }
    }
}
