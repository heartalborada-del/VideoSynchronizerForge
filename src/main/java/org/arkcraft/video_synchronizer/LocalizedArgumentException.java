package org.arkcraft.video_synchronizer;

import net.minecraft.network.chat.Component;

public final class LocalizedArgumentException extends IllegalArgumentException {
    private final String translationKey;
    private final Object[] arguments;

    public LocalizedArgumentException(String translationKey, Object... arguments) {
        super(translationKey);
        this.translationKey = translationKey;
        this.arguments = arguments;
    }

    public Component component() {
        return Component.translatable(translationKey, arguments);
    }

    public static Component component(IllegalArgumentException exception) {
        return exception instanceof LocalizedArgumentException localized
                ? localized.component()
                : Component.literal(exception.getMessage());
    }
}
