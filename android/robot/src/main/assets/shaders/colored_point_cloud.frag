precision mediump float;

varying vec3 v_Color;
varying float v_Confidence;

void main() {
    // Apply confidence as alpha to make low-confidence points more transparent
    gl_FragColor = vec4(v_Color, v_Confidence);
    
    // Make points circular by calculating distance from center
    vec2 center = vec2(0.5, 0.5);
    float dist = distance(gl_PointCoord, center);
    if (dist > 0.5) {
        discard; // Discard fragments outside the circle
    }
}
