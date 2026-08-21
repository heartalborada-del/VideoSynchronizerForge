package org.arkcraft.video_synchronizer.server;

import org.arkcraft.video_synchronizer.LocalizedArgumentException;

import java.util.Locale;

public enum ScreenAccessMode {
    PRIVATE,
    TRUSTED,
    PUBLIC_CONTROL,
    PUBLIC_VIEW;

    public static ScreenAccessMode fromName(String value) {
        if (value == null || value.isBlank()) {
            return PRIVATE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return PRIVATE;
        }
    }

    public static ScreenAccessMode require(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.access_mode");
        }
    }
}
