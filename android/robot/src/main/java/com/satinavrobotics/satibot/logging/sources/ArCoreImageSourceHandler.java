package com.satinavrobotics.satibot.logging.sources;

import android.graphics.Bitmap;
import com.google.ar.core.Pose;
import com.google.ar.core.TrackingFailureReason;
import com.satinavrobotics.satibot.logging.render.BitmapRenderer;
import com.satinavrobotics.satibot.arcore.ArCoreListener;
import com.satinavrobotics.satibot.arcore.CameraIntrinsics;
import com.satinavrobotics.satibot.arcore.ImageFrame;
import com.satinavrobotics.satibot.arcore.ArCoreHandler;
import com.satinavrobotics.satibot.utils.ImageSource;

import livekit.org.webrtc.VideoFrame;

/**
 * Image source handler for ARCore
 * Provides: image, pose
 */
public class ArCoreImageSourceHandler implements ImageSourceHandler, ArCoreListener {

    private ArCoreHandler arCoreHandler;
    private ImageSourceListener listener;
    private boolean isCapturing = false;

    public ArCoreImageSourceHandler(ArCoreHandler arCoreHandler) {
        this.arCoreHandler = arCoreHandler;
    }

    @Override
    public void initialize() {
        if (arCoreHandler != null) {
            arCoreHandler.setArCoreListener(this);
        }
    }

    @Override
    public void startCapture() {
        isCapturing = true;
        if (arCoreHandler != null) {
            arCoreHandler.setArCoreListener(this);
        }
    }

    @Override
    public void stopCapture() {
        isCapturing = false;
        if (arCoreHandler != null) {
            arCoreHandler.removeArCoreListener();
        }
    }

    @Override
    public boolean isReady() {
        return arCoreHandler != null && isCapturing;
    }

    @Override
    public String getDisplayName() {
        return ImageSource.ARCORE.getDisplayName();
    }

    @Override
    public void cleanup() {
        stopCapture();
        arCoreHandler = null;
        listener = null;
    }

    @Override
    public void setImageSourceListener(ImageSourceListener listener) {
        this.listener = listener;
    }

    @Override
    public void setBitmapRenderer(BitmapRenderer renderer) {
        // ARCore handles its own rendering, so we don't need the bitmap renderer
    }

    // ArCoreListener implementation
    @Override
    public void onArCoreUpdate(Pose currentPose, ImageFrame frame, CameraIntrinsics cameraIntrinsics, long timestamp) {
        if (!isCapturing || listener == null) {
            return;
        }

        try {
            // Convert ImageFrame to Bitmap
            Bitmap bitmap = convertImageFrameToBitmap(frame);
            if (bitmap != null) {
                listener.onFrameAvailable(bitmap, currentPose, cameraIntrinsics, timestamp);
            }
        } catch (Exception e) {
            if (listener != null) {
                listener.onError("ARCore frame processing error: " + e.getMessage());
            }
        }
    }

    @Override
    public void onRenderedFrame(VideoFrame.I420Buffer frame, long timestamp) {

    }

    @Override
    public void onArCoreTrackingFailure(long timestamp, TrackingFailureReason trackingFailureReason) {

    }

    @Override
    public void onArCoreSessionPaused(long timestamp) {

    }

    /**
     * Convert ImageFrame to Bitmap
     * Converts YUV_420_888 format to RGB bitmap using existing conversion methods
     */
    private Bitmap convertImageFrameToBitmap(ImageFrame frame) {
        if (frame == null) {
            return null;
        }

        try {
            int width = frame.getWidth();
            int height = frame.getHeight();

            // Create bitmap using the existing conversion method from LoggerFragment
            Bitmap rgbFrameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] rgbBytes = new int[width * height];

            // Use the existing YUV to RGB conversion method
            convertYUV420ToARGB8888(
                frame.getYuvBytes()[0],  // Y plane
                frame.getYuvBytes()[1],  // U plane
                frame.getYuvBytes()[2],  // V plane
                width,
                height,
                frame.getYRowStride(),
                frame.getUvRowStride(),
                frame.getUvPixelStride(),
                rgbBytes);

            rgbFrameBitmap.setPixels(rgbBytes, 0, width, 0, 0, width, height);
            return rgbFrameBitmap;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Convert YUV420 to ARGB8888 - copied from LoggerFragment
     */
    private static void convertYUV420ToARGB8888(
        byte[] yData,
        byte[] uData,
        byte[] vData,
        int width,
        int height,
        int yRowStride,
        int uvRowStride,
        int uvPixelStride,
        int[] out) {
        int yp = 0;
        for (int j = 0; j < height; j++) {
            int pY = yRowStride * j;
            int pUV = uvRowStride * (j >> 1);

            for (int i = 0; i < width; i++) {
                int uv_offset = pUV + (i >> 1) * uvPixelStride;
                out[yp++] = YUV2RGB(0xff & yData[pY + i], 0xff & uData[uv_offset], 0xff & vData[uv_offset]);
            }
        }
    }

    /**
     * YUV to RGB conversion - copied from LoggerFragment
     */
    private static int YUV2RGB(int y, int u, int v) {
        // Adjust and check YUV values
        y = (y - 16) < 0 ? 0 : (y - 16);
        u -= 128;
        v -= 128;

        int y1192 = 1192 * y;
        int r = (y1192 + 1634 * v);
        int g = (y1192 - 833 * v - 400 * u);
        int b = (y1192 + 2066 * u);

        // Clipping RGB values to be inside boundaries
        final int kMaxChannelValue = 262143;
        r = r > kMaxChannelValue ? kMaxChannelValue : (r < 0 ? 0 : r);
        g = g > kMaxChannelValue ? kMaxChannelValue : (g < 0 ? 0 : g);
        b = b > kMaxChannelValue ? kMaxChannelValue : (b < 0 ? 0 : b);

        return 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
    }
}
