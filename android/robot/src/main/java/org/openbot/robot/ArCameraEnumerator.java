package org.openbot.robot;


import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import io.livekit.android.room.track.CameraPosition;
import livekit.org.webrtc.Camera2Enumerator;
import livekit.org.webrtc.CameraEnumerationAndroid;
import livekit.org.webrtc.CameraEnumerator;
import livekit.org.webrtc.CameraVideoCapturer;
import livekit.org.webrtc.Size;

// returns ArCameraCapturer in createCapturer
public class ArCameraEnumerator extends Camera2Enumerator {

    final private Context context;
    final private String AR_DEVICE_NAME = "AR_CORE";
    final private String EXTERNAL_DEVICE_NAME = "EXTERNAL";

    public ArCameraEnumerator(Context context) {
        super(context);
        this.context = context;
    }

    public String getArDeviceName() {
        return AR_DEVICE_NAME;
    }

    public String getExternalDeviceName() {
        return EXTERNAL_DEVICE_NAME;
    }

    @Override
    public String[] getDeviceNames() {
        String[] deviceNames = super.getDeviceNames();
        String[] newDeviceNames = new String[deviceNames.length + 2];
        newDeviceNames[0] = getArDeviceName();
        newDeviceNames[1] = getExternalDeviceName();
        System.arraycopy(deviceNames, 0, newDeviceNames, 2, deviceNames.length);
        return newDeviceNames;
    }

    @Override
    public boolean isFrontFacing(String deviceName) {
        if (deviceName.equals(AR_DEVICE_NAME)) {
            return false;
        }
        if (deviceName.equals(EXTERNAL_DEVICE_NAME)) {
            return false;
        }
        return super.isFrontFacing(deviceName);
    }

    @Override
    public boolean isBackFacing(String deviceName) {
        if (deviceName.equals(AR_DEVICE_NAME)) {
            return true;
        }
        if (deviceName.equals(EXTERNAL_DEVICE_NAME)) {
            return true;
        }
        return super.isFrontFacing(deviceName);
    }

    @Override
    public List<CameraEnumerationAndroid.CaptureFormat> getSupportedFormats(String s) {
        CameraEnumerationAndroid.CaptureFormat arCaptureFormat = new CameraEnumerationAndroid.CaptureFormat(
                1920,1080,10,30
        );
        CameraEnumerationAndroid.CaptureFormat externalCaptureFormat = new CameraEnumerationAndroid.CaptureFormat(
                320,240,10,30
        );
        List<CameraEnumerationAndroid.CaptureFormat> camera2CaptureFormats = super.getSupportedFormats(s);
        assert camera2CaptureFormats != null;
        camera2CaptureFormats.add(arCaptureFormat);
        camera2CaptureFormats.add(externalCaptureFormat);
        return camera2CaptureFormats;
    }

    public CameraVideoCapturer createCapturer(ArCameraSession arCameraSession, ExternalCameraSession externalCameraSession,String deviceName, CameraVideoCapturer.CameraEventsHandler cameraEventsHandler) {
        return new ArCameraCapturer(this.context, arCameraSession, externalCameraSession, deviceName, cameraEventsHandler, this);
    }

    public String findCamera(String deviceId, CameraPosition position, boolean fallback)  {
        String targetDeviceName = null;
        // Prioritize search by deviceId first
        // Search by device ID
        if (deviceId != null) {
            if (deviceId.equals(AR_DEVICE_NAME)) {
                return AR_DEVICE_NAME;
            }
            if (deviceId.equals(EXTERNAL_DEVICE_NAME)) {
                return EXTERNAL_DEVICE_NAME;
            }

            targetDeviceName = findCamera(deviceName -> deviceName.equals(deviceId));
        }

        // Search by camera position
        if (targetDeviceName == null && position != null) {
            targetDeviceName = findCamera(deviceName -> getCameraPosition(deviceName) == position);
        }

        // Fall back by choosing the first available camera
        if (targetDeviceName == null && fallback) {
            targetDeviceName = findCamera(deviceName -> true);
        }

        return targetDeviceName;
    }

    public String findCamera(Predicate<String> predicate) {
        for (String deviceName : getDeviceNames()) {
            if (predicate.test(deviceName)) {
                return deviceName;
            }
        }
        return null;
    }

    public CameraPosition getCameraPosition(String deviceName) {
        if (deviceName == null) {
            return null;
        }
        if (isBackFacing(deviceName)) {
            return CameraPosition.BACK;
        } else if (isFrontFacing(deviceName)) {
            return CameraPosition.FRONT;
        }
        return null;
    }

    static int getFpsUnitFactor(Range<Integer>[] fpsRanges) {
        if (fpsRanges.length == 0) {
            return 1000;
        } else {
            return (Integer)fpsRanges[0].getUpper() < 1000 ? 1000 : 1;
        }
    }

    static List<Size> getSupportedSizes(CameraCharacteristics cameraCharacteristics) {
        StreamConfigurationMap streamMap = (StreamConfigurationMap)cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        int supportLevel = (Integer)cameraCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        android.util.Size[] nativeSizes = streamMap.getOutputSizes(SurfaceTexture.class);
        List<Size> sizes = convertSizes(nativeSizes);
        if (Build.VERSION.SDK_INT < 22 && supportLevel == 2) {
            Rect activeArraySize = (Rect)cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            ArrayList<Size> filteredSizes = new ArrayList();
            Iterator var7 = sizes.iterator();

            while(var7.hasNext()) {
                Size size = (Size)var7.next();
                if (activeArraySize.width() * size.height == activeArraySize.height() * size.width) {
                    filteredSizes.add(size);
                }
            }

            return filteredSizes;
        } else {
            return sizes;
        }
    }

    private static List<Size> convertSizes(android.util.Size[] cameraSizes) {
        if (cameraSizes != null && cameraSizes.length != 0) {
            List<Size> sizes = new ArrayList(cameraSizes.length);
            android.util.Size[] var2 = cameraSizes;
            int var3 = cameraSizes.length;

            for(int var4 = 0; var4 < var3; ++var4) {
                android.util.Size size = var2[var4];
                sizes.add(new Size(size.getWidth(), size.getHeight()));
            }

            return sizes;
        } else {
            return Collections.emptyList();
        }
    }

    static List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> convertFramerates(Range<Integer>[] arrayRanges, int unitFactor) {
        List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> ranges = new ArrayList();
        Range[] var3 = arrayRanges;
        int var4 = arrayRanges.length;

        for(int var5 = 0; var5 < var4; ++var5) {
            Range<Integer> range = var3[var5];
            ranges.add(new CameraEnumerationAndroid.CaptureFormat.FramerateRange((Integer)range.getLower() * unitFactor, (Integer)range.getUpper() * unitFactor));
        }

        return ranges;
    }
}
