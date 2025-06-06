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
    private static final String MODEL_PATH = "networks/depth_anything_v2_vits.tflite";
    private static final int INPUT_HEIGHT = 252; // Default input size, will be updated based on model
    private static final int INPUT_WIDTH = 336;

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
    private int modelOutputWidth;
    private int modelOutputHeight;
    private org.tensorflow.lite.DataType inputDataType;

    // Reusable buffers for YUV conversion (like ONNX implementation)
    private byte[] yuvBuffer;
    private int[] rgbBuffer;

    /**
     * Creates a new TFLiteDepthImageGenerator.
     */
    public TFLiteDepthImageGenerator() {
        // Initialize with default values
        width = 0;
        height = 0;
        modelInputWidth = INPUT_WIDTH;
        modelInputHeight = INPUT_HEIGHT;
        modelOutputWidth = INPUT_WIDTH;
        modelOutputHeight = INPUT_HEIGHT;

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

                        // Log tensor shape safely
                        StringBuilder shapeStr = new StringBuilder("[");
                        for (int i = 0; i < inputShape.length; i++) {
                            if (i > 0) shapeStr.append(", ");
                            shapeStr.append(inputShape[i]);
                        }
                        shapeStr.append("]");
                        Timber.d("Input tensor shape: %s (length: %d)", shapeStr.toString(), inputShape.length);

                        // Handle different tensor shape formats with bounds checking
                        if (inputShape.length == 4) {
                            // Standard NHWC format: [batch, height, width, channels]
                            if (inputShape[3] == 3) {
                                modelInputHeight = inputShape[1];
                                modelInputWidth = inputShape[2];
                            } else if (inputShape[1] == 3) {
                                // NCHW format: [batch, channels, height, width]
                                modelInputHeight = inputShape[2];
                                modelInputWidth = inputShape[3];
                            } else {
                                // Fallback: assume the two largest dimensions are height and width
                                modelInputHeight = Math.max(inputShape[1], inputShape[2]);
                                modelInputWidth = Math.min(inputShape[1], inputShape[2]);
                                if (modelInputHeight == modelInputWidth) {
                                    // If they're equal, use the expected dimensions
                                    modelInputHeight = INPUT_HEIGHT;
                                    modelInputWidth = INPUT_WIDTH;
                                }
                            }
                        } else if (inputShape.length == 3) {
                            // 3D tensor: [height, width, channels] or [channels, height, width]
                            if (inputShape[2] == 3) {
                                // [height, width, channels]
                                modelInputHeight = inputShape[0];
                                modelInputWidth = inputShape[1];
                            } else if (inputShape[0] == 3) {
                                // [channels, height, width]
                                modelInputHeight = inputShape[1];
                                modelInputWidth = inputShape[2];
                            } else {
                                // Fallback: use the two largest dimensions
                                modelInputHeight = Math.max(inputShape[0], inputShape[1]);
                                modelInputWidth = Math.min(inputShape[0], inputShape[1]);
                            }
                        } else {
                            // Fallback to default dimensions for any other shape
                            Timber.w("Unexpected tensor shape length: %d, using default dimensions", inputShape.length);
                            modelInputHeight = INPUT_HEIGHT;
                            modelInputWidth = INPUT_WIDTH;
                        }

                        // Validate dimensions and use known correct values if needed
                        if (modelInputWidth < 100 || modelInputHeight < 100) {
                            Timber.w("Detected suspicious dimensions %dx%d, using known correct dimensions %dx%d",
                                    modelInputWidth, modelInputHeight, INPUT_WIDTH, INPUT_HEIGHT);
                            modelInputWidth = INPUT_WIDTH;   // 336
                            modelInputHeight = INPUT_HEIGHT; // 252
                        }

                        Timber.d("Final model input dimensions: %dx%d", modelInputWidth, modelInputHeight);

                        // Get output tensor shape
                        int[] outputShape = interpreter.getOutputTensor(0).shape();

                        // Log output tensor shape safely
                        StringBuilder outputShapeStr = new StringBuilder("[");
                        for (int i = 0; i < outputShape.length; i++) {
                            if (i > 0) outputShapeStr.append(", ");
                            outputShapeStr.append(outputShape[i]);
                        }
                        outputShapeStr.append("]");
                        Timber.d("Output tensor shape: %s (length: %d)", outputShapeStr.toString(), outputShape.length);

                        // Handle output tensor shape with bounds checking
                        if (outputShape.length >= 2) {
                            if (outputShape.length == 4) {
                                // 4D tensor: [batch, height, width, channels] or similar
                                modelOutputHeight = outputShape[1];
                                modelOutputWidth = outputShape[2];
                            } else if (outputShape.length == 3) {
                                // 3D tensor: could be [height, width, channels] or [batch, height, width]
                                if (outputShape[2] == 1) {
                                    // Likely [batch, height, width] with batch=1
                                    modelOutputHeight = outputShape[1];
                                    modelOutputWidth = outputShape[2];
                                } else {
                                    // Likely [height, width, channels]
                                    modelOutputHeight = outputShape[0];
                                    modelOutputWidth = outputShape[1];
                                }
                            } else if (outputShape.length == 2) {
                                // 2D tensor: [height, width]
                                modelOutputHeight = outputShape[0];
                                modelOutputWidth = outputShape[1];
                            }
                        } else {
                            // Fallback: assume output dimensions match input dimensions
                            modelOutputHeight = modelInputHeight;
                            modelOutputWidth = modelInputWidth;
                        }

                        // Validate output dimensions using tensor byte size
                        int outputTensorBytes = interpreter.getOutputTensor(0).numBytes();
                        org.tensorflow.lite.DataType outputDataType = interpreter.getOutputTensor(0).dataType();
                        int bytesPerValue = (outputDataType == org.tensorflow.lite.DataType.FLOAT32) ? 4 : 1;
                        int expectedPixels = outputTensorBytes / bytesPerValue;

                        Timber.d("Output tensor: %d bytes, %d bytes per value, %d expected pixels",
                                outputTensorBytes, bytesPerValue, expectedPixels);
                        Timber.d("Parsed output dimensions: %dx%d = %d pixels",
                                modelOutputWidth, modelOutputHeight, modelOutputWidth * modelOutputHeight);

                        // If our parsed dimensions don't match the expected pixel count, try to correct them
                        if (modelOutputWidth * modelOutputHeight != expectedPixels) {
                            Timber.w("Output dimension mismatch! Expected %d pixels, got %dx%d = %d pixels",
                                    expectedPixels, modelOutputWidth, modelOutputHeight, modelOutputWidth * modelOutputHeight);

                            // Try to use input dimensions as fallback
                            if (modelInputWidth * modelInputHeight == expectedPixels) {
                                Timber.d("Using input dimensions for output: %dx%d", modelInputWidth, modelInputHeight);
                                modelOutputWidth = modelInputWidth;
                                modelOutputHeight = modelInputHeight;
                            } else {
                                // Try common aspect ratios
                                int sqrtPixels = (int) Math.sqrt(expectedPixels);
                                if (sqrtPixels * sqrtPixels == expectedPixels) {
                                    // Square image
                                    modelOutputWidth = modelOutputHeight = sqrtPixels;
                                } else {
                                    // Try 4:3 aspect ratio (336:252)
                                    if (expectedPixels == 336 * 252) {
                                        modelOutputWidth = 336;
                                        modelOutputHeight = 252;
                                    } else {
                                        // Last resort: use input dimensions
                                        modelOutputWidth = modelInputWidth;
                                        modelOutputHeight = modelInputHeight;
                                    }
                                }
                                Timber.d("Corrected output dimensions to: %dx%d", modelOutputWidth, modelOutputHeight);
                            }
                        }

                        // Check input tensor type to determine buffer format
                        inputDataType = interpreter.getInputTensor(0).dataType();
                        Timber.d("Input tensor data type: %s", inputDataType);

                        // Calculate expected buffer size
                        int expectedBufferSize = modelInputHeight * modelInputWidth * 3 * (inputDataType == org.tensorflow.lite.DataType.FLOAT32 ? 4 : 1);
                        Timber.d("Calculated buffer size: %d bytes (H:%d × W:%d × 3 × %d)",
                                expectedBufferSize, modelInputHeight, modelInputWidth,
                                (inputDataType == org.tensorflow.lite.DataType.FLOAT32 ? 4 : 1));

                        // Get actual tensor byte size for comparison
                        int actualTensorSize = interpreter.getInputTensor(0).numBytes();
                        Timber.d("Actual tensor expects: %d bytes", actualTensorSize);

                        // If our calculated size doesn't match the actual tensor size, use the actual size
                        int bufferSize;
                        if (expectedBufferSize != actualTensorSize) {
                            Timber.w("Buffer size mismatch! Using actual tensor size: %d instead of calculated: %d",
                                    actualTensorSize, expectedBufferSize);
                            bufferSize = actualTensorSize;

                            // Try to correct the dimensions based on actual tensor size
                            if (inputDataType == org.tensorflow.lite.DataType.FLOAT32) {
                                int totalPixels = actualTensorSize / (3 * 4); // 3 channels, 4 bytes per float
                                // Assume it's 252×336 based on the expected size
                                if (totalPixels == 252 * 336) {
                                    modelInputHeight = 252;
                                    modelInputWidth = 336;
                                    Timber.d("Corrected dimensions to: %dx%d", modelInputWidth, modelInputHeight);
                                }
                            }
                        } else {
                            bufferSize = expectedBufferSize;
                        }

                        // Allocate input buffer using the correct size
                        inputBuffer = ByteBuffer.allocateDirect(bufferSize);
                        inputBuffer.order(ByteOrder.nativeOrder());

                        if (inputDataType == org.tensorflow.lite.DataType.FLOAT32) {
                            Timber.d("Using FLOAT32 input format, buffer size: %d bytes", bufferSize);
                        } else {
                            Timber.d("Using UINT8 input format, buffer size: %d bytes", bufferSize);
                        }

                        // Set initialized flag
                        isInitialized = true;
                        Timber.d("Model loaded successfully with input dimensions: %d x %d, output dimensions: %d x %d",
                                modelInputWidth, modelInputHeight, modelOutputWidth, modelOutputHeight);

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

            // Convert camera image to bitmap using fast method (like ONNX)
            Timber.d("Camera image size: %dx%d", cameraImage.getWidth(), cameraImage.getHeight());
            Bitmap inputBitmap = convertYUVImageToBitmapFast(cameraImage);
            cameraImage.close();

            if (inputBitmap == null) {
                Timber.e("Failed to convert camera image to bitmap");
                isProcessing.set(false);
                return false;
            }

            Timber.d("Input bitmap size: %dx%d", inputBitmap.getWidth(), inputBitmap.getHeight());
            Timber.d("Target model input size: %dx%d", modelInputWidth, modelInputHeight);

            // Resize bitmap to model input size
            Bitmap resizedBitmap = Bitmap.createScaledBitmap(inputBitmap, modelInputWidth, modelInputHeight, true);
            inputBitmap.recycle();

            Timber.d("Resized bitmap size: %dx%d", resizedBitmap.getWidth(), resizedBitmap.getHeight());

            // Convert bitmap to input buffer using the correct format based on tensor type
            if (inputDataType == org.tensorflow.lite.DataType.FLOAT32) {
                convertBitmapToBufferFloat(resizedBitmap, inputBuffer);
            } else {
                convertBitmapToBufferUint8(resizedBitmap, inputBuffer);
            }
            resizedBitmap.recycle();

            // Set width and height to output dimensions (not input dimensions)
            width = modelOutputWidth;
            height = modelOutputHeight;

            // Validate output dimensions and fix if needed
            if (width <= 1 || height <= 1) {
                Timber.w("Invalid output dimensions %dx%d, using input dimensions %dx%d",
                        width, height, modelInputWidth, modelInputHeight);
                width = modelInputWidth;
                height = modelInputHeight;
            }

            Timber.d("Using output dimensions: %dx%d", width, height);

            // Create output buffers if needed
            if (depthImageBuffer == null || depthImageBuffer.capacity() != width * height * 2) {
                depthImageBuffer = ByteBuffer.allocateDirect(width * height * 2);
                depthImageBuffer.order(ByteOrder.nativeOrder());
            }

            if (confidenceImageBuffer == null || confidenceImageBuffer.capacity() != width * height) {
                confidenceImageBuffer = ByteBuffer.allocateDirect(width * height);
                confidenceImageBuffer.order(ByteOrder.nativeOrder());
            }

            // Create output buffer for model - use actual output tensor size
            int actualOutputTensorSize = interpreter.getOutputTensor(0).numBytes();
            int calculatedOutputSize = width * height * 4; // 1 channel, 4 bytes per float

            Timber.d("Output tensor size: actual=%d, calculated=%d", actualOutputTensorSize, calculatedOutputSize);

            // Use the larger of the two sizes to be safe
            int outputBufferSize = Math.max(actualOutputTensorSize, calculatedOutputSize);
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(outputBufferSize);
            outputBuffer.order(ByteOrder.nativeOrder());

            Timber.d("Allocated output buffer size: %d bytes", outputBufferSize);

            try {
                // Run inference
                Timber.d("Running inference on image of size %dx%d", width, height);
                Timber.d("Input buffer - capacity: %d, position: %d, limit: %d, remaining: %d",
                        inputBuffer.capacity(), inputBuffer.position(), inputBuffer.limit(), inputBuffer.remaining());
                Timber.d("Expected tensor size: %d bytes", modelInputHeight * modelInputWidth * 3 * (inputDataType == org.tensorflow.lite.DataType.FLOAT32 ? 4 : 1));

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
     * Optimized YUV to RGB conversion that directly accesses image planes and avoids JPEG compression.
     * This is much faster than the previous implementation and matches the ONNX version.
     */
    private Bitmap convertYUVImageToBitmapFast(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            Timber.e("Unsupported image format: %d", image.getFormat());
            return null;
        }

        final int imageWidth = image.getWidth();
        final int imageHeight = image.getHeight();

        Timber.d("Converting YUV image %dx%d to bitmap (fast method)", imageWidth, imageHeight);

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

        Timber.d("YUV conversion result: %dx%d bitmap", bitmap.getWidth(), bitmap.getHeight());
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
     * Converts a bitmap to a float buffer for model input.
     */
    private void convertBitmapToBufferFloat(Bitmap bitmap, ByteBuffer buffer) {
        buffer.clear(); // Clear position and limit

        int expectedPixels = bitmap.getWidth() * bitmap.getHeight();
        int expectedBytes = expectedPixels * 3 * 4; // 3 channels, 4 bytes per float

        Timber.d("Converting bitmap %dx%d (%d pixels) to float buffer, expected bytes: %d, buffer capacity: %d",
                bitmap.getWidth(), bitmap.getHeight(), expectedPixels, expectedBytes, buffer.capacity());

        int[] intValues = new int[expectedPixels];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        // Convert the image to floating point (normalized 0.0-1.0)
        for (final int val : intValues) {
            // Extract RGB values and normalize to 0.0-1.0
            buffer.putFloat(((val >> 16) & 0xFF) / 255.0f); // Red
            buffer.putFloat(((val >> 8) & 0xFF) / 255.0f);  // Green
            buffer.putFloat((val & 0xFF) / 255.0f);         // Blue
        }

        buffer.flip(); // Set limit to current position, position to 0
        Timber.d("Buffer after conversion - position: %d, limit: %d, remaining: %d",
                buffer.position(), buffer.limit(), buffer.remaining());
    }

    /**
     * Converts a bitmap to a uint8 buffer for model input (like ONNX implementation).
     */
    private void convertBitmapToBufferUint8(Bitmap bitmap, ByteBuffer buffer) {
        buffer.clear(); // Clear position and limit

        int expectedPixels = bitmap.getWidth() * bitmap.getHeight();
        int expectedBytes = expectedPixels * 3; // 3 channels, 1 byte per channel

        Timber.d("Converting bitmap %dx%d (%d pixels) to uint8 buffer, expected bytes: %d, buffer capacity: %d",
                bitmap.getWidth(), bitmap.getHeight(), expectedPixels, expectedBytes, buffer.capacity());

        int[] intValues = new int[expectedPixels];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        // Convert the image to uint8 format (RGB order)
        for (final int val : intValues) {
            // Extract RGB values and store as bytes (0-255)
            buffer.put((byte) ((val >> 16) & 0xFF)); // Red
            buffer.put((byte) ((val >> 8) & 0xFF));  // Green
            buffer.put((byte) (val & 0xFF));         // Blue
        }

        buffer.flip(); // Set limit to current position, position to 0
        Timber.d("Buffer after conversion - position: %d, limit: %d, remaining: %d",
                buffer.position(), buffer.limit(), buffer.remaining());
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
