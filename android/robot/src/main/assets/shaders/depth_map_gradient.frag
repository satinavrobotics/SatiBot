precision mediump float;

uniform sampler2D u_DepthTexture;
uniform sampler2D u_ConfidenceTexture;
uniform sampler2D u_GradientTexture;
uniform sampler2D u_CloserNextTexture;
uniform sampler2D u_VerticalFartherTexture;
uniform sampler2D u_HorizontalGradientTexture;
uniform sampler2D u_TooCloseTexture;
uniform int u_DepthColorMode;
uniform float u_ConfidenceThreshold;
uniform int u_ShowVerticalCloser;
uniform int u_ShowVerticalFarther;
uniform int u_ShowTooClose;

varying vec2 v_TexCoord;

// Convert depth value to color using rainbow visualization
vec3 depthToRainbow(float depth) {
    // Clip depth at 10000mm (10m) and map to 0-1
    float normalizedDepth = clamp(depth / 10000.0, 0.0, 1.0);

    // Simple rainbow color mapping
    if (normalizedDepth < 0.25) {
        // Red to Yellow (0.0-0.25)
        float t = normalizedDepth / 0.25;
        return vec3(1.0, t, 0.0);
    } else if (normalizedDepth < 0.5) {
        // Yellow to Green (0.25-0.5)
        float t = (normalizedDepth - 0.25) / 0.25;
        return vec3(1.0 - t, 1.0, 0.0);
    } else if (normalizedDepth < 0.75) {
        // Green to Cyan (0.5-0.75)
        float t = (normalizedDepth - 0.5) / 0.25;
        return vec3(0.0, 1.0, t);
    } else {
        // Cyan to Blue (0.75-1.0)
        float t = (normalizedDepth - 0.75) / 0.25;
        return vec3(0.0, 1.0 - t, 1.0);
    }
}

// Convert depth value to grayscale
vec3 depthToGrayscale(float depth) {
    // Clip depth at 10000mm (10m) and map to 0-1
    float normalizedDepth = clamp(depth / 10000.0, 0.0, 1.0);
    return vec3(normalizedDepth); // Closer is darker, farther is brighter
}

void main() {
    // Flip texture coordinates for correct orientation
    vec2 flippedCoords = vec2(v_TexCoord.x, 1.0 - v_TexCoord.y);

    // Read depth value directly - no filtering to reduce complexity
    vec4 depthSample = texture2D(u_DepthTexture, flippedCoords);
    float lowByte = depthSample.r * 255.0;
    float highByte = depthSample.g * 255.0;
    float depth = lowByte + (highByte * 256.0); // Reconstruct 16-bit depth value

    // Read confidence value from R channel
    vec4 confidenceSample = texture2D(u_ConfidenceTexture, flippedCoords);
    float normalizedConfidence = confidenceSample.r;

    // Read gradient value from R channel
    vec4 gradientSample = texture2D(u_GradientTexture, flippedCoords);
    float isGradient = gradientSample.r;

    // Read closer next value from R channel
    vec4 closerNextSample = texture2D(u_CloserNextTexture, flippedCoords);
    float isCloserNext = closerNextSample.r;

    // Read vertical farther value from B channel
    vec4 verticalFartherSample = texture2D(u_VerticalFartherTexture, flippedCoords);
    float isVerticalFarther = verticalFartherSample.b;

    // Read horizontal gradient value from R channel
    vec4 horizontalGradientSample = texture2D(u_HorizontalGradientTexture, flippedCoords);
    float isHorizontalGradient = horizontalGradientSample.r;

    // Read too close value from R channel (orange color)
    vec4 tooCloseSample = texture2D(u_TooCloseTexture, flippedCoords);
    float isTooClose = tooCloseSample.r;

    // Apply confidence threshold
    if (normalizedConfidence < u_ConfidenceThreshold) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); // Black for low confidence
        return;
    }

    // Validate depth value - if it's 0 or very large, it's likely invalid
    if (depth < 1.0 || depth > 10000.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); // Black for invalid depth
        return;
    }

    // Choose color based on mode
    vec3 color;
    if (u_DepthColorMode == 0) {
        color = depthToRainbow(depth);
    } else {
        color = depthToGrayscale(depth);
    }

    // Highlight gradient pixels in magenta
    if (isGradient > 0.5) {
        color = mix(color, vec3(1.0, 0.0, 1.0), 0.7);
    }

    // Highlight "vertical closer" pixels in red (potential drop-offs) - only if enabled
    if (u_ShowVerticalCloser == 1 && isCloserNext > 0.5) {
        color = mix(color, vec3(1.0, 0.0, 0.0), 0.7); // Red overlay
    }

    // Highlight "vertical farther" pixels in blue (potential step-ups) - only if enabled
    if (u_ShowVerticalFarther == 1 && isVerticalFarther > 0.5) {
        color = mix(color, vec3(0.0, 0.0, 1.0), 0.7); // Blue overlay
    }

    // Highlight horizontal gradient pixels in green
    if (isHorizontalGradient > 0.5) {
        color = mix(color, vec3(0.0, 1.0, 0.0), 0.7); // Green overlay
    }

    // Highlight "too close" pixels in orange (safety concern) - HIGHEST PRIORITY
    if (u_ShowTooClose == 1 && isTooClose > 0.5) {
        color = mix(color, vec3(1.0, 0.65, 0.0), 0.8); // Orange overlay with higher opacity
    }

    gl_FragColor = vec4(color, 1.0);
}
