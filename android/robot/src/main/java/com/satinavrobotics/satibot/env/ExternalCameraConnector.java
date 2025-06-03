package com.satinavrobotics.satibot.env;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import livekit.org.webrtc.VideoFrame;
import timber.log.Timber;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

public class ExternalCameraConnector {

    private HttpURLConnection connection;
    private volatile boolean connected = false;
    private Thread streamThread;

    /**
     * Listener for receiving streaming frames as LiveKit VideoFrame objects.
     */
    public interface StreamListener {
        void onFrame(VideoFrame frame);
        void onError(Exception e);
        void onDisconnected();
    }

    /**
     * Listener for receiving a captured still image as a LiveKit VideoFrame.
     */
    public interface CaptureListener {
        void onImageCaptured(VideoFrame frame);
        void onError(Exception e);
    }

    /**
     * Listener for receiving a captured still image as an Android Bitmap.
     * This interface does not require LiveKit dependencies.
     */
    public interface BitmapCaptureListener {
        void onImageCaptured(Bitmap bitmap);
        void onError(Exception e);
    }

    /**
     * Connects to the camera stream and returns each frame as a VideoFrame.
     *
     * @param streamUrl The URL for the camera stream.
     * @param listener  Callback for streaming events.
     */
    public void connectStream(final String streamUrl, final StreamListener listener) {
        if (connected && streamThread != null && streamThread.isAlive()) {
            return;
        }

        streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(streamUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setDoInput(true);
                    connection.setRequestProperty("User-Agent", "SatiBot-Android/1.0");
                    connection.setRequestProperty("Accept", "multipart/x-mixed-replace,image/jpeg");
                    connection.setRequestProperty("Connection", "keep-alive");
                    connection.connect();

                    if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        connected = true;
                        InputStream in = connection.getInputStream();
                        BufferedReader br = new BufferedReader(new InputStreamReader(in));
                        String data;

                        while (connected && (data = br.readLine()) != null) {
                            if (data.contains("Content-Type:")) {
                                // Expect the next line to contain the image length.
                                data = br.readLine();
                                int len = Integer.parseInt(data.split(":")[1].trim());
                                byte[] buffer = new byte[len];

                                int totalRead = 0;
                                BufferedInputStream bis = new BufferedInputStream(in);
                                while (totalRead < len) {
                                    int read = bis.read(buffer, totalRead, len - totalRead);
                                    if (read == -1) {
                                        break;
                                    }
                                    totalRead += read;
                                }
                                // Decode the JPEG data into a Bitmap.
                                Bitmap bitmap = BitmapFactory.decodeByteArray(buffer, 0, len);
                                if (bitmap != null) {
                                    // Wrap the Bitmap into a VideoFrame using a custom Buffer.
                                    VideoFrame frame = new VideoFrame(new BitmapBuffer(bitmap), 0, System.nanoTime());
                                    listener.onFrame(frame);
                                }
                            }
                        }
                    } else {
                        listener.onError(new Exception("Failed to connect. Response code: " + connection.getResponseCode()));
                    }
                } catch (MalformedURLException e) {
                    listener.onError(e);
                } catch (Exception e) {
                    listener.onError(e);
                } finally {
                    disconnect();
                    listener.onDisconnected();
                }
            }
        });
        streamThread.start();
    }

    /**
     * Parses MJPEG stream and extracts individual JPEG frames
     * Uses binary parsing instead of line-based parsing for better reliability
     */
    private void parseMjpegStream(InputStream in, StreamListener listener) throws Exception {
        byte[] buffer = new byte[8192]; // Buffer for reading data
        StringBuilder headerBuilder = new StringBuilder();
        boolean inHeader = true;
        int contentLength = -1;

        while (connected) {
            try {
                int bytesRead = in.read(buffer);
                if (bytesRead == -1) {
                    break; // End of stream
                }

                if (inHeader) {
                    // Parse headers to find Content-Length
                    for (int i = 0; i < bytesRead; i++) {
                        char c = (char) buffer[i];
                        headerBuilder.append(c);

                        // Check for end of headers (double CRLF)
                        String headers = headerBuilder.toString();
                        int headerEnd = headers.indexOf("\r\n\r\n");
                        if (headerEnd == -1) {
                            headerEnd = headers.indexOf("\n\n");
                        }

                        if (headerEnd != -1) {
                            // Found end of headers, parse Content-Length
                            String headerSection = headers.substring(0, headerEnd);
                            contentLength = parseContentLength(headerSection);

                            if (contentLength > 0) {
                                // Read the JPEG data
                                byte[] imageData = new byte[contentLength];
                                int imageDataStart = i + 1;
                                int alreadyRead = bytesRead - imageDataStart;

                                // Copy any image data already in buffer
                                if (alreadyRead > 0) {
                                    System.arraycopy(buffer, imageDataStart, imageData, 0, Math.min(alreadyRead, contentLength));
                                }

                                // Read remaining image data
                                int totalImageRead = alreadyRead;
                                while (totalImageRead < contentLength && connected) {
                                    int remaining = contentLength - totalImageRead;
                                    int read = in.read(imageData, totalImageRead, remaining);
                                    if (read == -1) break;
                                    totalImageRead += read;
                                }

                                // Process the complete JPEG frame
                                if (totalImageRead == contentLength) {
                                    processJpegFrame(imageData, listener);
                                }
                            }

                            // Reset for next frame
                            headerBuilder.setLength(0);
                            inHeader = true;
                            contentLength = -1;
                            break;
                        }
                    }
                } else {
                    // Should not reach here with current logic
                    inHeader = true;
                }
            } catch (java.io.IOException e) {
                if (connected) {
                    throw e; // Only throw if we're still supposed to be connected
                }
                break;
            }
        }
    }

    private int parseContentLength(String headers) {
        String[] lines = headers.split("\r?\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private void processJpegFrame(byte[] imageData, StreamListener listener) {
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
            if (bitmap != null) {
                VideoFrame frame = new VideoFrame(new BitmapBuffer(bitmap), 0, System.nanoTime());
                listener.onFrame(frame);
            }
        } catch (Exception e) {
            // Silently ignore frame processing errors
        }
    }

    public void disconnect() {
        connected = false;

        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {}
            connection = null;
        }

        if (streamThread != null && streamThread.isAlive()) {
            streamThread.interrupt();
            streamThread = null;
        }
    }

    /**
     * Captures a still image from the camera and returns it as a Bitmap.
     * This method does not require LiveKit dependencies.
     *
     * @param stillUrl The URL to capture a still image.
     * @param listener Callback for capture events.
     */
    public void captureBitmap(final String stillUrl, final BitmapCaptureListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection huc = null;
                try {
                    URL url = new URL(stillUrl);
                    huc = (HttpURLConnection) url.openConnection();
                    huc.setRequestMethod("GET");
                    huc.setConnectTimeout(5000);
                    huc.setReadTimeout(5000);
                    huc.setDoInput(true);
                    huc.connect();

                    if (huc.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        InputStream in = huc.getInputStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int bytesRead;

                        while ((bytesRead = in.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] imageData = baos.toByteArray();
                        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                        if (bitmap != null) {
                            listener.onImageCaptured(bitmap);
                        } else {
                            listener.onError(new Exception("Failed to decode image."));
                        }
                    } else {
                        listener.onError(new Exception("Failed to capture image. Response code: " + huc.getResponseCode()));
                    }
                } catch (Exception e) {
                    listener.onError(e);
                } finally {
                    if (huc != null) {
                        huc.disconnect();
                    }
                }
            }
        }).start();
    }

    /**
     * Captures a still image from the camera and returns it as a VideoFrame.
     *
     * @param stillUrl The URL to capture a still image.
     * @param listener Callback for capture events.
     */
    public void captureImage(final String stillUrl, final CaptureListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection huc = null;
                try {
                    URL url = new URL(stillUrl);
                    huc = (HttpURLConnection) url.openConnection();
                    huc.setRequestMethod("GET");
                    huc.setConnectTimeout(5000);
                    huc.setReadTimeout(5000);
                    huc.setDoInput(true);
                    huc.connect();

                    if (huc.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        InputStream in = huc.getInputStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        int bytesRead;

                        while ((bytesRead = in.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] imageData = baos.toByteArray();
                        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                        if (bitmap != null) {
                            VideoFrame frame = new VideoFrame(new BitmapBuffer(bitmap), 0, System.nanoTime());
                            listener.onImageCaptured(frame);
                        } else {
                            listener.onError(new Exception("Failed to decode image."));
                        }
                    } else {
                        listener.onError(new Exception("Failed to capture image. Response code: " + huc.getResponseCode()));
                    }
                } catch (Exception e) {
                    listener.onError(e);
                } finally {
                    if (huc != null) {
                        huc.disconnect();
                    }
                }
            }
        }).start();
    }

    /**
     * Custom implementation of VideoFrame.Buffer that wraps an Android Bitmap.
     */
    public static class BitmapBuffer implements VideoFrame.Buffer {

        private final Bitmap bitmap;

        public BitmapBuffer(Bitmap bitmap) {
            if (bitmap == null) {
                throw new IllegalArgumentException("Bitmap cannot be null.");
            }
            this.bitmap = bitmap;
        }

        /**
         * Get the underlying bitmap
         * @return The bitmap wrapped by this buffer
         */
        public Bitmap getBitmap() {
            return bitmap;
        }

        @Override
        public int getWidth() {
            return bitmap.getWidth();
        }

        @Override
        public int getHeight() {
            return bitmap.getHeight();
        }

        @Override
        public VideoFrame.I420Buffer toI420() {
            // Convert Bitmap to I420Buffer
            return convertBitmapToI420();
        }

        private VideoFrame.I420Buffer convertBitmapToI420() {
            synchronized (this) {
                if (bitmap.isRecycled()) {
                    throw new IllegalStateException("Bitmap is recycled, cannot convert to I420");
                }

                int width = bitmap.getWidth();
                int height = bitmap.getHeight();

                // Get pixel data from bitmap
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

                // Allocate I420 buffers
                byte[] yPlane = new byte[width * height];
                byte[] uPlane = new byte[width * height / 4];
                byte[] vPlane = new byte[width * height / 4];

                // Convert RGB to YUV420
                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int pixel = pixels[i * width + j];
                        int r = (pixel >> 16) & 0xff;
                        int g = (pixel >> 8) & 0xff;
                        int b = pixel & 0xff;

                        // Convert to YUV
                        int y = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                        int u = (int) (-0.169 * r - 0.331 * g + 0.5 * b + 128);
                        int v = (int) (0.5 * r - 0.419 * g - 0.081 * b + 128);

                        // Clamp values
                        y = Math.max(0, Math.min(255, y));
                        u = Math.max(0, Math.min(255, u));
                        v = Math.max(0, Math.min(255, v));

                        yPlane[i * width + j] = (byte) y;

                        // Subsample U and V planes (4:2:0)
                        if (i % 2 == 0 && j % 2 == 0) {
                            int uvIndex = (i / 2) * (width / 2) + (j / 2);
                            uPlane[uvIndex] = (byte) u;
                            vPlane[uvIndex] = (byte) v;
                        }
                    }
                }

                // Create I420Buffer using JavaI420Buffer with ByteBuffers
                java.nio.ByteBuffer yBuffer = java.nio.ByteBuffer.allocateDirect(yPlane.length);
                java.nio.ByteBuffer uBuffer = java.nio.ByteBuffer.allocateDirect(uPlane.length);
                java.nio.ByteBuffer vBuffer = java.nio.ByteBuffer.allocateDirect(vPlane.length);

                yBuffer.put(yPlane);
                uBuffer.put(uPlane);
                vBuffer.put(vPlane);

                yBuffer.rewind();
                uBuffer.rewind();
                vBuffer.rewind();

                return livekit.org.webrtc.JavaI420Buffer.wrap(width, height, yBuffer, width, uBuffer, width / 2, vBuffer, width / 2, null);
            }
        }

        private int refCount = 1;

        @Override
        public void retain() {
            synchronized (this) {
                refCount++;
            }
        }

        @Override
        public void release() {
            synchronized (this) {
                refCount--;
                // Don't automatically recycle bitmap as it may still be in use by rendering thread
                // Let GC handle bitmap cleanup to avoid "recycled bitmap" errors
            }
        }

        @Override
        public VideoFrame.Buffer cropAndScale(int x, int y, int cropWidth, int cropHeight, int scaleWidth, int scaleHeight) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, cropWidth, cropHeight);
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, scaleWidth, scaleHeight, true);
            return new BitmapBuffer(scaled);
        }
    }
}
