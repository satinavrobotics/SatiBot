/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openbot.pointGoalNavigation.rendering;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLES30;

import androidx.annotation.NonNull;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import timber.log.Timber;

/**
 * This class renders the AR background from camera feed. It creates and hosts the texture given to
 * ARCore to be filled with the camera image. From:
 * https://chromium.googlesource.com/external/github.com/google-ar/arcore-android-sdk/+/refs/tags/v1.2.0/samples/cloud_anchor_java/app/src/main/java/com/google/ar/core/examples/java/common/rendering/BackgroundRenderer.java
 */
public class BackgroundRenderer {
  private static final String TAG = BackgroundRenderer.class.getSimpleName();

  // Shader names.
  private static final String VERTEX_SHADER_NAME = "shaders/screenquad.vert";
  private static final String FRAGMENT_SHADER_NAME = "shaders/screenquad.frag";

  private static final int COORDS_PER_VERTEX = 2;
  private static final int TEXCOORDS_PER_VERTEX = 2;
  private static final int FLOAT_SIZE = 4;
  private static final float[] QUAD_COORDS =
      new float[] {
        -1.0f, -1.0f, -1.0f, +1.0f, +1.0f, -1.0f, +1.0f, +1.0f,
      };
  private FloatBuffer quadCoords;
  private FloatBuffer quadTexCoords;
  private int quadProgram;
  private int quadPositionParam;
  private int quadTexCoordParam;
  private int textureId = -1;
  private boolean suppressTimestampZeroRendering = true;

  public int getTextureId() {
    return textureId;
  }

  /**
   * Allocates and initializes OpenGL resources needed by the background renderer. Must be called on
   * the OpenGL thread, typically in .
   *
   * @param context Needed to access shader source.
   */
  public void createOnGlThread(Context context) throws IOException {
    // Generate the background texture.
    int[] textures = new int[1];
    GLES30.glGenTextures(1, textures, 0);
    textureId = textures[0];
    int textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
    GLES30.glBindTexture(textureTarget, textureId);
    GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);

    int numVertices = 4;
    if (numVertices != QUAD_COORDS.length / COORDS_PER_VERTEX) {
      throw new RuntimeException("Unexpected number of vertices in BackgroundRenderer.");
    }

    ByteBuffer bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.length * FLOAT_SIZE);
    bbCoords.order(ByteOrder.nativeOrder());
    quadCoords = bbCoords.asFloatBuffer();
    quadCoords.put(QUAD_COORDS);
    quadCoords.position(0);

    ByteBuffer bbTexCoordsTransformed =
        ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * FLOAT_SIZE);
    bbTexCoordsTransformed.order(ByteOrder.nativeOrder());
    quadTexCoords = bbTexCoordsTransformed.asFloatBuffer();

    int vertexShader =
        ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_NAME);
    int fragmentShader =
        ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_NAME);

    quadProgram = GLES30.glCreateProgram();
    GLES30.glAttachShader(quadProgram, vertexShader);
    GLES30.glAttachShader(quadProgram, fragmentShader);
    GLES30.glLinkProgram(quadProgram);
    GLES30.glUseProgram(quadProgram);

    ShaderUtil.checkGLError(TAG, "Program creation");

    quadPositionParam = GLES30.glGetAttribLocation(quadProgram, "a_Position");
    quadTexCoordParam = GLES30.glGetAttribLocation(quadProgram, "a_TexCoord");

    ShaderUtil.checkGLError(TAG, "Program parameters");
  }

  public void suppressTimestampZeroRendering(boolean suppressTimestampZeroRendering) {
    this.suppressTimestampZeroRendering = suppressTimestampZeroRendering;
  }

  /**
   * Draws the AR background image. The image will be drawn such that virtual content rendered with
   * the matrices provided by {@link com.google.ar.core.Camera#getViewMatrix(float[], int)} and
   * {@link com.google.ar.core.Camera#getProjectionMatrix(float[], int, float, float)} will
   * accurately follow static physical objects. This must be called <b>before</b> drawing virtual
   * content.
   *
   * @param frame The current {@code Frame} as returned by {@link Session#update()}.
   */
  public void draw(@NonNull Frame frame) {
    // If display rotation changed (also includes view size change), we need to re-query the uv
    // coordinates for the screen rect, as they may have changed as well.
    if (frame.hasDisplayGeometryChanged()) {
      frame.transformCoordinates2d(
          Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
          quadCoords,
          Coordinates2d.TEXTURE_NORMALIZED,
          quadTexCoords);
    }

    if (frame.getTimestamp() == 0 && suppressTimestampZeroRendering) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      return;
    }

    draw();
  }

  /**
   * Draws the camera background image using the currently configured {@link
   * BackgroundRenderer#quadTexCoords} image texture coordinates.
   */
  private void draw() {
    // Ensure position is rewound before use.
    quadTexCoords.position(0);

    // Debug: Check if the shader program is valid
    int[] linked = new int[1];
    GLES30.glGetProgramiv(quadProgram, GLES30.GL_LINK_STATUS, linked, 0);
    if (linked[0] == 0) {
      String log = GLES30.glGetProgramInfoLog(quadProgram);
      Timber.e(TAG, "Shader program linking failed: " + log);
      return;
    }

    // Debug: Ensure valid texture ID
    if (textureId == 0) {
      Timber.e(TAG, "Invalid texture ID!");
      return;
    }

    // No need to test or write depth, the screen quad has arbitrary depth, and is expected
    // to be drawn first.
    GLES30.glDisable(GLES30.GL_DEPTH_TEST);
    GLES30.glDepthMask(false);

    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

    GLES30.glUseProgram(quadProgram);

    quadPositionParam = GLES30.glGetAttribLocation(quadProgram, "a_Position");
    quadTexCoordParam = GLES30.glGetAttribLocation(quadProgram, "a_TexCoord");
    if (quadPositionParam < 0 || quadTexCoordParam < 0) {
      Timber.e(TAG, "Invalid attribute locations: Position = " + quadPositionParam +
              ", TexCoord = " + quadTexCoordParam);
      return;
    }

    // Debug: Ensure texture uniform is valid
    int textureUniform = GLES30.glGetUniformLocation(quadProgram, "sTexture");
    if (textureUniform == -1) {
      Timber.e(TAG, "Uniform sTexture not found in shader!");
      return;
    }
    GLES30.glUniform1i(textureUniform, 0);

    // Set the vertex positions.
    GLES30.glVertexAttribPointer(
        quadPositionParam, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadCoords);

    // Set the texture coordinates.
    GLES30.glVertexAttribPointer(
        quadTexCoordParam, TEXCOORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadTexCoords);

    // Enable vertex arrays
    GLES30.glEnableVertexAttribArray(quadPositionParam);
    GLES30.glEnableVertexAttribArray(quadTexCoordParam);

    // Debug: Ensure buffers are direct buffers
    if (!quadCoords.isDirect() || !quadTexCoords.isDirect()) {
      Timber.e(TAG, "Buffers must be direct!");
      return;
    }

    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

    // Disable vertex arrays
    GLES30.glDisableVertexAttribArray(quadPositionParam);
    GLES30.glDisableVertexAttribArray(quadTexCoordParam);

    // Restore the depth state for further drawing.
    GLES30.glDepthMask(true);
    GLES30.glEnable(GLES30.GL_DEPTH_TEST);

    ShaderUtil.checkGLError(TAG, "BackgroundRendererDraw");
  }
}
