package com.satinavrobotics.satibot.mapManagement.rendering;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.util.Log;

import com.satinavrobotics.satibot.mapManagement.Waypoint;
import com.satinavrobotics.satibot.mapManagement.WaypointGraph;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * Renders waypoints and connections between them using OpenGL ES 2.0.
 */
public class WaypointRenderer {
    private static final String TAG = WaypointRenderer.class.getSimpleName();

    // Colors for different waypoint states
    private static final float[] WAYPOINT_COLOR = new float[] {1.0f, 0.5f, 0.0f, 1.0f}; // Orange
    private static final float[] SELECTED_WAYPOINT_COLOR = new float[] {1.0f, 0.0f, 0.0f, 1.0f}; // Red
    private static final float[] CONNECTION_COLOR = new float[] {0.0f, 1.0f, 1.0f, 0.8f}; // Cyan

    // Waypoint model and rendering
    private final ObjectRenderer waypointRenderer = new ObjectRenderer();
    private final float waypointScale = 1.0f; // Scale factor for waypoint models - increased for easier selection

    // Line rendering for connections
    private int lineProgram;
    private int linePositionAttr;
    private int lineColorUniform;
    private int lineMvpMatrixUniform;
    private final float lineWidth = 10.0f; // Width of connection lines - increased for better visibility

    // Temporary matrices for rendering
    private final float[] modelMatrix = new float[16];
    private final float[] modelViewMatrix = new float[16];
    private final float[] modelViewProjectionMatrix = new float[16];

    /**
     * Creates the renderer and initializes OpenGL resources.
     *
     * @param context The Android context
     * @throws IOException If there's an error loading resources
     */
    public void createOnGlThread(Context context) throws IOException {
        // Initialize waypoint renderer with a sphere model
        try {
            waypointRenderer.createOnGlThread(context, "models/sphere.obj", "models/sphere.png");
            waypointRenderer.setMaterialProperties(0.0f, 3.0f, 0.5f, 6.0f);
            waypointRenderer.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
        } catch (IOException e) {
            Log.e(TAG, "Failed to load waypoint model, using fallback");
            try {
                waypointRenderer.createOnGlThread(context, "models/andy.obj", "models/andy.png");
                waypointRenderer.setMaterialProperties(0.0f, 3.0f, 0.5f, 6.0f);
                waypointRenderer.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
            } catch (IOException ex) {
                Log.e(TAG, "Failed to load fallback model", ex);
                throw ex;
            }
        }

        // Initialize line shader program for connections
        initLineShader();
    }

    /**
     * Initializes the shader program for rendering connection lines.
     */
    private void initLineShader() {
        // Simple vertex shader for lines
        String lineVertexShader =
                "uniform mat4 u_ModelViewProjection;\n" +
                "attribute vec4 a_Position;\n" +
                "void main() {\n" +
                "  gl_Position = u_ModelViewProjection * a_Position;\n" +
                "}\n";

        // Simple fragment shader for lines
        String lineFragmentShader =
                "precision mediump float;\n" +
                "uniform vec4 u_Color;\n" +
                "void main() {\n" +
                "  gl_FragColor = u_Color;\n" +
                "}\n";

        // Compile shaders
        int vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        GLES20.glShaderSource(vertexShader, lineVertexShader);
        GLES20.glCompileShader(vertexShader);

        int fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(fragmentShader, lineFragmentShader);
        GLES20.glCompileShader(fragmentShader);

        // Create and link program
        lineProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(lineProgram, vertexShader);
        GLES20.glAttachShader(lineProgram, fragmentShader);
        GLES20.glLinkProgram(lineProgram);

        // Get attribute and uniform locations
        linePositionAttr = GLES20.glGetAttribLocation(lineProgram, "a_Position");
        lineColorUniform = GLES20.glGetUniformLocation(lineProgram, "u_Color");
        lineMvpMatrixUniform = GLES20.glGetUniformLocation(lineProgram, "u_ModelViewProjection");

        // Clean up shaders
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
    }

    /**
     * Draws all waypoints and connections in the graph.
     *
     * @param viewMatrix The view matrix
     * @param projectionMatrix The projection matrix
     * @param colorCorrectionRgba The color correction values
     * @param waypointGraph The waypoint graph to render
     */
    public void draw(float[] viewMatrix, float[] projectionMatrix, float[] colorCorrectionRgba, WaypointGraph waypointGraph) {
        if (waypointGraph == null) return;

        List<Waypoint> waypoints = waypointGraph.getAllWaypoints();
        String selectedWaypointId = waypointGraph.getSelectedWaypointId();

        // Draw connections first (so they appear behind waypoints)
        drawConnections(viewMatrix, projectionMatrix, waypoints);

        // Draw waypoints
        for (Waypoint waypoint : waypoints) {
            boolean isSelected = waypoint.getId().equals(selectedWaypointId);
            drawWaypoint(viewMatrix, projectionMatrix, colorCorrectionRgba, waypoint, isSelected);
        }
    }

    /**
     * Draws a single waypoint.
     *
     * @param viewMatrix The view matrix
     * @param projectionMatrix The projection matrix
     * @param colorCorrectionRgba The color correction values
     * @param waypoint The waypoint to draw
     * @param isSelected Whether the waypoint is currently selected
     */
    private void drawWaypoint(float[] viewMatrix, float[] projectionMatrix, float[] colorCorrectionRgba,
                              Waypoint waypoint, boolean isSelected) {
        // Get the waypoint's pose
        waypoint.getPose().toMatrix(modelMatrix, 0);

        // Apply scale
        float[] scaleMatrix = new float[16];
        Matrix.setIdentityM(scaleMatrix, 0);
        scaleMatrix[0] = waypointScale;
        scaleMatrix[5] = waypointScale;
        scaleMatrix[10] = waypointScale;
        float[] scaledModelMatrix = new float[16];
        Matrix.multiplyMM(scaledModelMatrix, 0, modelMatrix, 0, scaleMatrix, 0);

        // Update model matrix and draw
        waypointRenderer.updateModelMatrix(scaledModelMatrix, 1.0f);
        float[] color = isSelected ? SELECTED_WAYPOINT_COLOR : WAYPOINT_COLOR;
        waypointRenderer.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, color);
    }

    /**
     * Draws connections between waypoints.
     *
     * @param viewMatrix The view matrix
     * @param projectionMatrix The projection matrix
     * @param waypoints The list of waypoints
     */
    private void drawConnections(float[] viewMatrix, float[] projectionMatrix, List<Waypoint> waypoints) {
        // Calculate the model-view-projection matrix
        float[] mvpMatrix = new float[16];
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0);

        // Set up OpenGL for line drawing
        GLES20.glUseProgram(lineProgram);
        GLES20.glUniformMatrix4fv(lineMvpMatrixUniform, 1, false, mvpMatrix, 0);
        GLES20.glUniform4fv(lineColorUniform, 1, CONNECTION_COLOR, 0);
        GLES20.glLineWidth(lineWidth);

        // Draw each connection
        for (Waypoint waypoint : waypoints) {
            for (String connectedId : waypoint.getConnectedWaypointIds()) {
                // Only draw each connection once (when the connected ID is greater than this ID)
                if (connectedId.compareTo(waypoint.getId()) > 0) {
                    Waypoint connectedWaypoint = null;
                    for (Waypoint wp : waypoints) {
                        if (wp.getId().equals(connectedId)) {
                            connectedWaypoint = wp;
                            break;
                        }
                    }

                    if (connectedWaypoint != null) {
                        drawLine(waypoint.getPose().getTranslation(), connectedWaypoint.getPose().getTranslation());
                    }
                }
            }
        }
    }

    /**
     * Draws a line between two points.
     *
     * @param start The start point
     * @param end The end point
     */
    private void drawLine(float[] start, float[] end) {
        // Create a buffer with the line vertices
        FloatBuffer lineBuffer = ByteBuffer.allocateDirect(6 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        // Add start and end points
        lineBuffer.put(start[0]);
        lineBuffer.put(start[1]);
        lineBuffer.put(start[2]);
        lineBuffer.put(end[0]);
        lineBuffer.put(end[1]);
        lineBuffer.put(end[2]);
        lineBuffer.position(0);

        // Draw the line
        GLES20.glEnableVertexAttribArray(linePositionAttr);
        GLES20.glVertexAttribPointer(linePositionAttr, 3, GLES20.GL_FLOAT, false, 0, lineBuffer);
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2);
        GLES20.glDisableVertexAttribArray(linePositionAttr);
    }

    /**
     * Draws a crosshair in the center of the screen.
     *
     * @param viewMatrix The view matrix
     * @param projectionMatrix The projection matrix
     */
    public void drawCrosshair(float[] viewMatrix, float[] projectionMatrix) {
        // This is a placeholder for crosshair rendering
        // In a real implementation, you would draw a crosshair in screen space
        // or use a 3D object positioned in front of the camera
    }
}
