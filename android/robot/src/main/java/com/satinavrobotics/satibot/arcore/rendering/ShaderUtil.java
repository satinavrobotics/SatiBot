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
package com.satinavrobotics.satibot.arcore.rendering;

import android.content.Context;
import android.opengl.GLES30;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Shader helper functions. From:
 * https://chromium.googlesource.com/external/github.com/google-ar/arcore-android-sdk/+/refs/tags/v1.2.0/samples/cloud_anchor_java/app/src/main/java/com/google/ar/core/examples/java/common/rendering/ShaderUtil.java
 */
public class ShaderUtil {
  /**
   * Converts a raw text file, saved as a resource, into an OpenGL ES shader.
   *
   * @param type The type of shader we will be creating.
   * @param filename The filename of the asset file about to be turned into a shader.
   * @return The shader object handler.
   */
  public static int loadGLShader(String tag, Context context, int type, String filename)
      throws IOException {
    String code = readShaderFileFromAssets(context, filename);
    int shader = GLES30.glCreateShader(type);
    GLES30.glShaderSource(shader, code);
    GLES30.glCompileShader(shader);

    // Get the compilation status.
    final int[] compileStatus = new int[1];
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0);

    // If the compilation failed, delete the shader.
    if (compileStatus[0] == 0) {
      Log.e(tag, "Error compiling shader: " + GLES30.glGetShaderInfoLog(shader));
      GLES30.glDeleteShader(shader);
      shader = 0;
    }

    if (shader == 0) {
      throw new RuntimeException("Error creating shader.");
    }

    return shader;
  }

  /**
   * Checks if we've had an error inside of OpenGL ES, and if so what that error is.
   *
   * @param label Label to report in case of error.
   * @throws RuntimeException If an OpenGL error is detected.
   */
  public static void checkGLError(String tag, String label) {
    int lastError = GLES30.GL_NO_ERROR;
    // Drain the queue of all errors.
    int error;
    while ((error = GLES30.glGetError()) != GLES30.GL_NO_ERROR) {
      Log.e(tag, label + ": glError " + error);
      lastError = error;
    }
    if (lastError != GLES30.GL_NO_ERROR) {
      throw new RuntimeException(label + ": glError " + lastError);
    }
  }

  /**
   * Converts a raw shader file into a string.
   *
   * @param filename The filename of the shader file about to be turned into a shader.
   * @return The context of the text file, or null in case of error.
   */
  private static String readShaderFileFromAssets(Context context, String filename)
      throws IOException {
    try (InputStream inputStream = context.getAssets().open(filename);
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        String[] tokens = line.split(" ", -1);
        if (tokens[0].equals("#include")) {
          String includeFilename = tokens[1];
          includeFilename = includeFilename.replace("\"", "");
          if (includeFilename.equals(filename)) {
            throw new IOException("Do not include the calling file.");
          }
          sb.append(readShaderFileFromAssets(context, includeFilename));
        } else {
          sb.append(line).append("\n");
        }
      }
      return sb.toString();
    }
  }
}
