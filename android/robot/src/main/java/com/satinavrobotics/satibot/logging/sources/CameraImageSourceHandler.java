package com.satinavrobotics.satibot.logging.sources;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.satinavrobotics.satibot.logging.render.BitmapRenderer;
import com.satinavrobotics.satibot.utils.ImageSource;
import com.satinavrobotics.satibot.utils.YuvToRgbConverter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Image source handler for Camera using CameraX Preview + ImageAnalysis
 * Uses hardware-accelerated preview for smooth UI and background processing for logging
 * Provides: image only
 */
public class CameraImageSourceHandler implements ImageSourceHandler {

    private Context context;
    private LifecycleOwner lifecycleOwner;
    private ImageSourceListener listener;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private Preview preview;
    private PreviewView previewView;
    private boolean isCapturing = false;
    private Executor cameraExecutor;
    private YuvToRgbConverter converter;
    private Bitmap bitmapBuffer;

    public CameraImageSourceHandler(Context context, LifecycleOwner lifecycleOwner) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        // Use background thread executor for image processing only
        this.cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void initialize() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
            ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                setupCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                if (listener != null) {
                    listener.onError("Camera initialization failed: " + e.getMessage());
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void setupCameraUseCases() {
        // Initialize converter like CameraFragment
        converter = new YuvToRgbConverter(context);
        bitmapBuffer = null;

        // Setup Preview for hardware-accelerated rendering
        preview = new Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build();

        // Connect preview to PreviewView for smooth hardware rendering
        if (previewView != null) {
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
        }

        // Setup ImageAnalysis for background processing (logging only)
        imageAnalysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9) // Use same aspect ratio as preview
            .build();

        // Image processing happens on background thread - no UI operations here
        imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(ImageProxy image) {
                if (isCapturing && listener != null) {
                    try {
                        // Use efficient YUV conversion like CameraFragment
                        if (bitmapBuffer == null) {
                            bitmapBuffer = Bitmap.createBitmap(
                                image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
                        }

                        converter.yuvToRgb(image.getImage(), bitmapBuffer);

                        // Send to listener for logging (background processing)
                        listener.onFrameAvailable(bitmapBuffer, null, null, System.currentTimeMillis());

                    } catch (Exception e) {
                        if (listener != null) {
                            listener.onError("Camera frame processing error: " + e.getMessage());
                        }
                    }
                }
                image.close();
            }
        });
    }

    @Override
    public void startCapture() {
        if (cameraProvider == null) {
            if (listener != null) {
                listener.onError("Camera not initialized");
            }
            return;
        }

        isCapturing = true;

        // Camera binding must happen on main thread
        ContextCompat.getMainExecutor(context).execute(() -> {
            try {
                // Select back camera
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // Ensure all use cases are unbound first to prevent conflicts
                cameraProvider.unbindAll();

                // Use Handler to add delay without blocking main thread
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        // Bind both Preview and ImageAnalysis use cases
                        // Preview handles UI rendering, ImageAnalysis handles logging
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis);
                    } catch (Exception e) {
                        if (listener != null) {
                            listener.onError("Camera binding failed: " + e.getMessage());
                        }
                        // Try to recover by stopping capture
                        isCapturing = false;
                    }
                }, 100); // 100ms delay for resource release

            } catch (Exception e) {
                if (listener != null) {
                    listener.onError("Camera setup failed: " + e.getMessage());
                }
                // Try to recover by stopping capture
                isCapturing = false;
            }
        });
    }

    @Override
    public void stopCapture() {
        isCapturing = false;

        // Camera unbinding must happen on main thread
        if (cameraProvider != null) {
            ContextCompat.getMainExecutor(context).execute(() -> {
                try {
                    cameraProvider.unbindAll();
                } catch (Exception e) {
                    // Log error but continue cleanup
                    if (listener != null) {
                        listener.onError("Error stopping camera: " + e.getMessage());
                    }
                }
            });
        }
    }

    @Override
    public boolean isReady() {
        return cameraProvider != null && isCapturing;
    }

    @Override
    public String getDisplayName() {
        return ImageSource.CAMERA.getDisplayName();
    }

    @Override
    public void cleanup() {
        stopCapture();
        cameraProvider = null;
        listener = null;

        // Shutdown executor like CameraFragment
        if (cameraExecutor != null) {
            ((java.util.concurrent.ExecutorService) cameraExecutor).shutdown();
        }
    }

    @Override
    public void setImageSourceListener(ImageSourceListener listener) {
        this.listener = listener;
    }

    @Override
    public void setBitmapRenderer(BitmapRenderer renderer) {
        // BitmapRenderer no longer needed - using PreviewView for hardware-accelerated rendering
        // Keep method for interface compatibility but don't store the renderer
    }

    /**
     * Set the PreviewView for hardware-accelerated camera preview
     * This should be called before startCapture()
     */
    public void setPreviewView(PreviewView previewView) {
        this.previewView = previewView;
        // If camera use cases are already set up, reconnect preview
        if (preview != null) {
            preview.setSurfaceProvider(previewView.getSurfaceProvider());
        }
    }
}
