package org.arkcraft.video_synchronizer.server;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class VideoServerConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_SCREENS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue MAX_PANELS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_SESSIONS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue CREATE_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue PLAYBACK_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PLAYER_REQUEST_METADATA;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_MEDIA_HOSTS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("survival");
        MAX_SCREENS_PER_PLAYER = builder.comment(
                        "Maximum logical screens owned by one non-admin player.")
                .defineInRange("maxScreensPerPlayer", 8, 1, 1024);
        MAX_PANELS_PER_PLAYER = builder.comment(
                        "Maximum total panels across a non-admin player's logical screens.")
                .defineInRange("maxPanelsPerPlayer", 4096, 1, 1_048_576);
        MAX_ACTIVE_SESSIONS_PER_PLAYER = builder.comment(
                        "Maximum simultaneous sessions started by one non-admin player.")
                .defineInRange("maxActiveSessionsPerPlayer", 2, 1, 128);
        CREATE_COOLDOWN_SECONDS = builder.comment(
                        "Cooldown between logical screen creation actions.")
                .defineInRange("createCooldownSeconds", 2, 0, 3600);
        PLAYBACK_COOLDOWN_SECONDS = builder.comment(
                        "Cooldown between media start or replacement actions.")
                .defineInRange("playbackCooldownSeconds", 3, 0, 3600);
        ALLOW_PLAYER_REQUEST_METADATA = builder.comment(
                        "Allow non-admin editors to set custom HTTP headers or Cookies.")
                .define("allowPlayerRequestMetadata", false);
        ALLOWED_MEDIA_HOSTS = builder.comment(
                        "Optional exact hosts or *.example.com rules. Empty permits public hosts.")
                .defineListAllowEmpty("allowedMediaHosts", List.of(),
                        value -> value instanceof String);
        builder.pop();
        SPEC = builder.build();
    }

    private VideoServerConfig() {
    }
}
