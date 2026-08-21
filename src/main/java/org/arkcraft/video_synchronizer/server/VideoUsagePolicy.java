package org.arkcraft.video_synchronizer.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.LocalizedArgumentException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class VideoUsagePolicy {
    private static final Map<UUID, Long> LAST_CREATE_NANOS = new HashMap<>();
    private static final Map<UUID, Long> LAST_PLAYBACK_NANOS = new HashMap<>();

    private VideoUsagePolicy() {
    }

    public static void requireScreenCapacity(ServerPlayer player, int newPanelCount) {
        if (VideoPermissionService.isAdmin(player)) {
            return;
        }
        requireCooldown(LAST_CREATE_NANOS, player.getUUID(),
                VideoServerConfig.CREATE_COOLDOWN_SECONDS.get(), "screen creation");
        int screens = ServerScreenRegistry.ownedScreenCount(
                player.getServer(), player.getUUID());
        if (screens >= VideoServerConfig.MAX_SCREENS_PER_PLAYER.get()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_limit");
        }
        int panels = ServerScreenRegistry.ownedPanelCount(
                player.getServer(), player.getUUID());
        if ((long) panels + newPanelCount > VideoServerConfig.MAX_PANELS_PER_PLAYER.get()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.panel_limit");
        }
    }

    public static void recordScreenCreated(ServerPlayer player) {
        if (!VideoPermissionService.isAdmin(player)) {
            LAST_CREATE_NANOS.put(player.getUUID(), System.nanoTime());
        }
    }

    public static void requirePlaybackStart(ServerPlayer player, String replacingSessionKey) {
        if (VideoPermissionService.isAdmin(player)) {
            return;
        }
        requireCooldown(LAST_PLAYBACK_NANOS, player.getUUID(),
                VideoServerConfig.PLAYBACK_COOLDOWN_SECONDS.get(), "playback start");
        int active = ServerVideoSessionManager.activeSessionsStartedBy(
                player.getUUID(), replacingSessionKey);
        if (active >= VideoServerConfig.MAX_ACTIVE_SESSIONS_PER_PLAYER.get()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.playback_limit");
        }
        LAST_PLAYBACK_NANOS.put(player.getUUID(), System.nanoTime());
    }

    public static void requireRequestMetadataPermission(ServerPlayer player,
                                                        String requestHeaders,
                                                        String cookie) {
        if ((!requestHeaders.isBlank() || !cookie.isBlank())
                && !canUseRequestMetadata(player)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.request_metadata_permission");
        }
    }

    public static void requireRequestMetadataEdit(ServerPlayer player,
                                                  String requestHeaders, String cookie,
                                                  String existingHeaders,
                                                  String existingCookie) {
        requireRequestMetadataPermission(player, requestHeaders, cookie);
        if (!canUseRequestMetadata(player)
                && (!existingHeaders.isBlank() || !existingCookie.isBlank())) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.request_metadata_protected");
        }
    }

    public static boolean canUseRequestMetadata(ServerPlayer player) {
        return VideoPermissionService.isAdmin(player)
                || VideoServerConfig.ALLOW_PLAYER_REQUEST_METADATA.get();
    }

    public static boolean isRequestMetadataProtected(ServerPlayer player,
                                                      String existingHeaders,
                                                      String existingCookie) {
        return !canUseRequestMetadata(player)
                && (!existingHeaders.isBlank() || !existingCookie.isBlank());
    }

    public static void validateMediaUri(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.media_host_required");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }
        while (normalizedHost.endsWith(".")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }
        String validatedHost = normalizedHost;
        List<? extends String> allowRules = VideoServerConfig.ALLOWED_MEDIA_HOSTS.get();
        if (!allowRules.isEmpty() && allowRules.stream()
                .map(rule -> rule.trim().toLowerCase(Locale.ROOT))
                .noneMatch(rule -> matchesHostRule(validatedHost, rule))) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.media_host_denied");
        }
        if (isPrivateHost(validatedHost)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.media_host_private");
        }
    }

    public static void audit(ServerPlayer player, String screenId, String action,
                             boolean success) {
        Main.LOGGER.info("Video action: player={}, screen={}, action={}, result={}",
                player.getGameProfile().getName(), screenId, action,
                success ? "allowed" : "denied");
    }

    public static void reset() {
        LAST_CREATE_NANOS.clear();
        LAST_PLAYBACK_NANOS.clear();
    }

    private static void requireCooldown(Map<UUID, Long> values, UUID playerId,
                                        int cooldownSeconds, String action) {
        if (cooldownSeconds <= 0) {
            return;
        }
        Long previous = values.get(playerId);
        if (previous == null) {
            return;
        }
        long remainingNanos = TimeUnit.SECONDS.toNanos(cooldownSeconds)
                - (System.nanoTime() - previous);
        if (remainingNanos > 0L) {
            long remainingSeconds = Math.max(1L,
                    TimeUnit.NANOSECONDS.toSeconds(remainingNanos) + 1L);
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.cooldown", remainingSeconds,
                    Component.translatable("message.video_synchronizer.action."
                            + action.replace(' ', '_')));
        }
    }

    private static boolean matchesHostRule(String host, String rule) {
        if (rule.startsWith("*.")) {
            String suffix = rule.substring(1);
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return host.equals(rule);
    }

    private static boolean isPrivateHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")
                || host.equals("::") || host.equals("::1")) {
            return true;
        }
        if (host.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(host);
                return address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress();
            } catch (UnknownHostException exception) {
                return true;
            }
        }
        if (host.matches("[0-9]+") || host.startsWith("0x")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] values = new int[4];
        for (int index = 0; index < parts.length; index++) {
            if (parts[index].length() > 1 && parts[index].startsWith("0")) {
                return true;
            }
            try {
                values[index] = Integer.parseInt(parts[index]);
            } catch (NumberFormatException exception) {
                return false;
            }
            if (values[index] < 0 || values[index] > 255) {
                return false;
            }
        }
        return values[0] == 0 || values[0] == 10 || values[0] == 127
                || values[0] == 100 && values[1] >= 64 && values[1] <= 127
                || values[0] == 169 && values[1] == 254
                || values[0] == 172 && values[1] >= 16 && values[1] <= 31
                || values[0] == 192 && values[1] == 168
                || values[0] == 198 && (values[1] == 18 || values[1] == 19)
                || values[0] >= 224;
    }
}
