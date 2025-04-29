package org.openbot.pointGoalNavigation;

import android.graphics.ImageFormat;
import android.media.Image;
import java.nio.ByteBuffer;

import livekit.org.webrtc.JavaI420Buffer;
import livekit.org.webrtc.VideoFrame;

/* fillBytes() taken From: https://github.com/wangjiangyong/tflite_android_facedemo/blob/master/app/src/main/java/org/tensorflow/demo/CameraActivity.java */

public class ImageFrame {

  private byte[][] yuvBytes = new byte[3][];
  int width;
  int height;
  private int yRowStride;
  private int uvRowStride;
  private int uvPixelStride;

  public ImageFrame(final Image image) {
    assert (image.getFormat() == ImageFormat.YUV_420_888);
    assert (image.getPlanes().length == 3);

    Image.Plane[] planes = image.getPlanes();

    fillBytes(planes, yuvBytes);

    width = image.getWidth();
    height = image.getHeight();

    yRowStride = planes[0].getRowStride();
    uvRowStride = planes[1].getRowStride();
    uvPixelStride = planes[1].getPixelStride();
  }

  public byte[][] getYuvBytes() {
    return yuvBytes;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public int getYRowStride() {
    return yRowStride;
  }

  public int getUvRowStride() {
    return uvRowStride;
  }

  public int getUvPixelStride() {
    return uvPixelStride;
  }

  protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  /**
   * Converts the YUV_420_888 data in yuvBytes into an I420 buffer.
   * Note: This implementation does not perform any rotation.
   *
   * @return a VideoFrame.I420Buffer containing the converted data.
   */
  public VideoFrame.I420Buffer toI420Buffer() {
    // Allocate an I420 buffer of the same dimensions.
    JavaI420Buffer i420Buffer = JavaI420Buffer.allocate(width, height);

    // --- Copy Y plane ---
    ByteBuffer i420Y = i420Buffer.getDataY();
    int strideY = i420Buffer.getStrideY();
    for (int row = 0; row < height; row++) {
      for (int col = 0; col < width; col++) {
        int srcIndex = row * yRowStride + col; // assuming Y pixel stride is 1
        int destIndex = row * strideY + col;
        i420Y.put(destIndex, yuvBytes[0][srcIndex]);
      }
    }

    // The chroma planes have half the resolution in each dimension.
    int chromaWidth = (width + 1) / 2;
    int chromaHeight = (height + 1) / 2;

    // --- Copy U plane ---
    ByteBuffer i420U = i420Buffer.getDataU();
    int strideU = i420Buffer.getStrideU();
    for (int row = 0; row < chromaHeight; row++) {
      for (int col = 0; col < chromaWidth; col++) {
        int srcIndex = row * uvRowStride + col * uvPixelStride;
        int destIndex = row * strideU + col;
        i420U.put(destIndex, yuvBytes[1][srcIndex]);
      }
    }

    // --- Copy V plane ---
    ByteBuffer i420V = i420Buffer.getDataV();
    int strideV = i420Buffer.getStrideV();
    for (int row = 0; row < chromaHeight; row++) {
      for (int col = 0; col < chromaWidth; col++) {
        int srcIndex = row * uvRowStride + col * uvPixelStride;
        int destIndex = row * strideV + col;
        i420V.put(destIndex, yuvBytes[2][srcIndex]);
      }
    }

    return i420Buffer;
  }
}
