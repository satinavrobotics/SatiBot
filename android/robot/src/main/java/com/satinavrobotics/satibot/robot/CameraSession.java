package com.satinavrobotics.satibot.robot;

//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

import android.content.Context;
import android.graphics.Matrix;
import android.view.WindowManager;

import livekit.org.webrtc.TextureBufferImpl;
import livekit.org.webrtc.VideoFrame;

public interface CameraSession {
    void stop();

    interface Events {
        void onCameraOpening();

        void onCameraError(CameraSession var1, String var2);

        void onCameraDisconnected(CameraSession var1);

        void onCameraClosed(CameraSession var1);

        void onFrameCaptured(CameraSession var1, VideoFrame var2);
    }

    interface CreateSessionCallback {
        void onDone(CameraSession var1);

        void onFailure(FailureType var1, String var2);
    }

    enum FailureType {
        ERROR,
        DISCONNECTED;

        FailureType() {}
    }

    static int getDeviceOrientation(Context context) {
        WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        switch (wm.getDefaultDisplay().getRotation()) {
            case 0:
            default:
                return 0;
            case 1:
                return 90;
            case 2:
                return 180;
            case 3:
                return 270;
        }
    }

    static VideoFrame.TextureBuffer createTextureBufferWithModifiedTransformMatrix(TextureBufferImpl buffer, boolean mirror, int rotation) {
        Matrix transformMatrix = new Matrix();
        transformMatrix.preTranslate(0.5F, 0.5F);
        if (mirror) {
            transformMatrix.preScale(-1.0F, 1.0F);
        }

        transformMatrix.preRotate((float)rotation);
        transformMatrix.preTranslate(-0.5F, -0.5F);
        return buffer.applyTransformMatrix(transformMatrix, buffer.getWidth(), buffer.getHeight());
    }
}

