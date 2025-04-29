package org.openbot.env;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import livekit.org.webrtc.VideoFrame;

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
     * Connects to the camera stream and returns each frame as a VideoFrame.
     *
     * @param streamUrl The URL for the camera stream.
     * @param listener  Callback for streaming events.
     */
    public void connectStream(final String streamUrl, final StreamListener listener) {
        streamThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(streamUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setDoInput(true);
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
     * Disconnects from the stream.
     */
    public void disconnect() {
        connected = false;
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
        if (streamThread != null && streamThread.isAlive()) {
            streamThread.interrupt();
            streamThread = null;
        }
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
    private static class BitmapBuffer implements VideoFrame.Buffer {

        private final Bitmap bitmap;

        public BitmapBuffer(Bitmap bitmap) {
            if (bitmap == null) {
                throw new IllegalArgumentException("Bitmap cannot be null.");
            }
            this.bitmap = bitmap;
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
            // Conversion from Bitmap to I420Buffer is not implemented.
            return null;
        }

        @Override
        public void retain() {
            // No operation for now.
        }

        @Override
        public void release() {
            // No operation for now.
        }

        @Override
        public VideoFrame.Buffer cropAndScale(int x, int y, int cropWidth, int cropHeight, int scaleWidth, int scaleHeight) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, x, y, cropWidth, cropHeight);
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, scaleWidth, scaleHeight, true);
            return new BitmapBuffer(scaled);
        }
    }
}
