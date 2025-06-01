package com.satinavrobotics.satibot.arcore.rendering;

import android.content.Context;

import com.satinavrobotics.satibot.arcore.processor.ArCoreProcessor;

import java.nio.ByteBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A null implementation of ARCoreRenderer that does nothing.
 * This is used when rendering is disabled.
 */
public class NullARCoreRenderer implements ARCoreRenderer {
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config, Context context) {
        // Do nothing
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // Do nothing
    }

    @Override
    public void drawFrame(ArCoreProcessor.ProcessedFrameData frameData) {
        // Do nothing as we're not rendering anything
    }

    @Override
    public ByteBuffer readPixels() {
        // Return null as we're not rendering anything
        return null;
    }


    @Override
    public int getBackgroundTextureId() {
        // Return an invalid texture ID
        return 0;
    }

    @Override
    public void cleanup() {
        // Do nothing
    }

    @Override
    public int getWidth() {
        return DEFAULT_WIDTH;
    }

    @Override
    public int getHeight() {
        return DEFAULT_HEIGHT;
    }
}
