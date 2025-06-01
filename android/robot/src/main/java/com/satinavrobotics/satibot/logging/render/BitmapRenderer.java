package com.satinavrobotics.satibot.logging.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import timber.log.Timber;

/**
 * Simple bitmap renderer for displaying camera feeds on a SurfaceView
 */
public class BitmapRenderer {

    private final SurfaceHolder surfaceHolder;
    private final Paint paint;
    private final Matrix matrix;
    private boolean isRendering = false;

    // Simple frame rate limiting
    private long lastRenderTime = 0;
    private static final long MIN_RENDER_INTERVAL_MS = 50; // ~20 FPS max for better performance

    public BitmapRenderer(SurfaceView surfaceView) {
        this.surfaceHolder = surfaceView.getHolder();
        this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.matrix = new Matrix();

        // Set up surface holder callback
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // Surface is now ready for rendering
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                // Surface dimensions changed
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                isRendering = false;
            }
        });
    }

    /**
     * Start rendering
     */
    public void startRendering() {
        isRendering = true;
    }

    /**
     * Stop rendering
     */
    public void stopRendering() {
        isRendering = false;
    }

    /**
     * Render a bitmap to the surface view
     *
     * @param bitmap The bitmap to render
     */
    public void renderBitmap(Bitmap bitmap) {
        if (!isRendering || bitmap == null || surfaceHolder == null) {
            return;
        }

        // Frame rate limiting
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRenderTime < MIN_RENDER_INTERVAL_MS) {
            return; // Skip this frame to maintain target FPS
        }
        lastRenderTime = currentTime;

        // Check if surface is valid
        if (!surfaceHolder.getSurface().isValid()) {
            return;
        }

        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockCanvas();
            if (canvas != null) {
                // Clear the canvas with black background
                canvas.drawColor(0xFF000000);

                // Simple scaling and centering
                int surfaceWidth = canvas.getWidth();
                int surfaceHeight = canvas.getHeight();
                int bitmapWidth = bitmap.getWidth();
                int bitmapHeight = bitmap.getHeight();

                if (surfaceWidth > 0 && surfaceHeight > 0 && bitmapWidth > 0 && bitmapHeight > 0) {
                    // Calculate scale to fit bitmap in surface while maintaining aspect ratio
                    float scaleX = (float) surfaceWidth / bitmapWidth;
                    float scaleY = (float) surfaceHeight / bitmapHeight;
                    float scale = Math.min(scaleX, scaleY);

                    // Calculate position to center the bitmap
                    float scaledWidth = bitmapWidth * scale;
                    float scaledHeight = bitmapHeight * scale;
                    float left = (surfaceWidth - scaledWidth) / 2;
                    float top = (surfaceHeight - scaledHeight) / 2;

                    // Set up matrix for scaling and positioning
                    matrix.reset();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(left, top);

                    // Draw the bitmap
                    canvas.drawBitmap(bitmap, matrix, paint);
                }
            }
        } catch (Exception e) {
            Timber.e(e, "Error rendering bitmap to surface");
        } finally {
            if (canvas != null) {
                try {
                    surfaceHolder.unlockCanvasAndPost(canvas);
                } catch (Exception e) {
                    Timber.e(e, "Error unlocking canvas");
                }
            }
        }
    }

    /**
     * Clear the surface with black background
     */
    public void clearSurface() {
        if (surfaceHolder == null) {
            return;
        }

        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(0xFF000000);
            }
        } catch (Exception e) {
            Timber.e(e, "Error clearing surface");
        } finally {
            if (canvas != null) {
                try {
                    surfaceHolder.unlockCanvasAndPost(canvas);
                } catch (Exception e) {
                    Timber.e(e, "Error unlocking canvas");
                }
            }
        }
    }

    /**
     * Check if currently rendering
     *
     * @return true if rendering is active
     */
    public boolean isRendering() {
        return isRendering;
    }
}
