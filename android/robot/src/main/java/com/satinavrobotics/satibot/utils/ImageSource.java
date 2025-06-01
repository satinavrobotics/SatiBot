package com.satinavrobotics.satibot.utils;

/**
 * Enum representing different image sources for the logger
 */
public enum ImageSource {
    ARCORE(0, "ARCore"),
    CAMERA(1, "Camera"),
    EXTERNAL_CAMERA(2, "External Camera");

    private final int value;
    private final String displayName;

    ImageSource(int value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public int getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get ImageSource by its integer value
     *
     * @param value The integer value
     * @return The corresponding ImageSource, or ARCORE if not found
     */
    public static ImageSource getByValue(int value) {
        for (ImageSource source : values()) {
            if (source.value == value) {
                return source;
            }
        }
        return ARCORE; // Default fallback
    }

    /**
     * Get the output description for this image source
     *
     * @return String describing what this source outputs
     */
    public String getOutputDescription() {
        switch (this) {
            case ARCORE:
                return "image, pose";
            case CAMERA:
                return "image";
            case EXTERNAL_CAMERA:
                return "image, arcore pose, sync";
            default:
                return "unknown";
        }
    }
}
