package org.arkcraft.video_synchronizer.network;

import java.util.Locale;

public enum VideoPixelFormat {
    RGBA("rgba", 4),
    RGB24("rgb24", 3);

    private final String ffmpegName;
    private final int bytesPerPixel;

    VideoPixelFormat(String ffmpegName, int bytesPerPixel) {
        this.ffmpegName = ffmpegName;
        this.bytesPerPixel = bytesPerPixel;
    }

    public String ffmpegName() {
        return ffmpegName;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }

    public VideoPixelFormat next() {
        return this == RGB24 ? RGBA : RGB24;
    }

    public static VideoPixelFormat fromName(String name) {
        if (name != null && !name.isBlank()) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return RGB24;
    }
}
