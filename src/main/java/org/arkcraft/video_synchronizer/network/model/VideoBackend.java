package org.arkcraft.video_synchronizer.network.model;

import java.util.Locale;

/** Decoder backend selected for a screen playback session. */
public enum VideoBackend {
    FFMPEG("ffmpeg"),
    VLCJ("vlcj");

    private final String id;

    VideoBackend(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public VideoBackend next() {
        return this == FFMPEG ? VLCJ : FFMPEG;
    }

    public static VideoBackend fromName(String name) {
        if (name != null && !name.isBlank()) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return FFMPEG;
    }
}
