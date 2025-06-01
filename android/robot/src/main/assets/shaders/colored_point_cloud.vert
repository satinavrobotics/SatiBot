uniform mat4 u_ModelViewProjection;

attribute vec3 a_Position;
attribute vec3 a_Color;
attribute float a_Confidence;

varying vec3 v_Color;
varying float v_Confidence;

void main() {
   v_Color = a_Color;
   v_Confidence = a_Confidence;
   gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
   gl_PointSize = 5.0;
}
