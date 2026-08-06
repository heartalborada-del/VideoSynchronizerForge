package org.arkcraft.video_synchronizer.network;

import java.util.Locale;

public enum AudioPlaybackMode {
    POSITIONAL,
    FIXED_RANGE,
    GLOBAL;

    public AudioPlaybackMode next() {
        AudioPlaybackMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }

    public static AudioPlaybackMode fromName(String name) {
        if (name == null) {
            return POSITIONAL;
        }
        try {
            return valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return POSITIONAL;
        }
    }
}
