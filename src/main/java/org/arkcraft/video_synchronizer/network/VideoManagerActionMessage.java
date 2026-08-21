package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.LocalizedArgumentException;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;
import org.arkcraft.video_synchronizer.server.VideoPermissionService;
import org.arkcraft.video_synchronizer.server.VideoUsagePolicy;

import java.util.function.Supplier;

public record VideoManagerActionMessage(BlockPos pos, Action action, String screenId,
                                         String videoUrl, String audioUrl, String requestHeaders,
                                         String cookie, boolean disableScaling,
                                         int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                                         double audioRange, AudioPlaybackMode audioPlaybackMode,
                                         long positionMs) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(action);
        buf.writeUtf(screenId, 32);
        buf.writeUtf(videoUrl, 2048);
        buf.writeUtf(audioUrl, 2048);
        buf.writeUtf(requestHeaders, MediaRequestOptions.MAX_HEADERS_LENGTH);
        buf.writeUtf(cookie, MediaRequestOptions.MAX_COOKIE_LENGTH);
        buf.writeBoolean(disableScaling);
        buf.writeVarInt(videoPipeLanes);
        buf.writeEnum(videoPixelFormat);
        buf.writeDouble(audioRange);
        buf.writeEnum(audioPlaybackMode);
        buf.writeLong(positionMs);
    }

    public static VideoManagerActionMessage decode(FriendlyByteBuf buf) {
        return new VideoManagerActionMessage(buf.readBlockPos(), buf.readEnum(Action.class),
                buf.readUtf(32), buf.readUtf(2048), buf.readUtf(2048),
                buf.readUtf(MediaRequestOptions.MAX_HEADERS_LENGTH),
                buf.readUtf(MediaRequestOptions.MAX_COOKIE_LENGTH), buf.readBoolean(),
                buf.readVarInt(),
                buf.readEnum(VideoPixelFormat.class),
                buf.readDouble(),
                buf.readEnum(AudioPlaybackMode.class),
                buf.readLong());
    }

    public static void handle(VideoManagerActionMessage message,
                              Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null || sender.blockPosition().distSqr(message.pos()) > 64.0D
                || !(sender.level().getBlockEntity(message.pos())
                instanceof VideoManagerBlockEntity manager)) {
            return;
        }
        String auditScreenId = switch (message.action()) {
            case SAVE, START -> message.screenId();
            default -> manager.getScreenId();
        };
        boolean success = false;
        try {
            switch (message.action()) {
                case SAVE -> {
                    requireBindIfChanged(sender, manager, message.screenId());
                    requireEdit(sender, message.screenId());
                    saveConfiguration(sender, manager, message.screenId(),
                            message.videoUrl(), message.audioUrl(), message.requestHeaders(),
                            message.cookie(), message.disableScaling(), message.videoPipeLanes(),
                            message.videoPixelFormat(), message.audioRange(),
                            message.audioPlaybackMode());
                }
                case START -> {
                    if (ServerScreenRegistry.canEdit(sender, message.screenId())) {
                        if (VideoUsagePolicy.isRequestMetadataProtected(sender,
                                manager.getRequestHeaders(), manager.getCookie())
                                && matchesSavedConfiguration(message, manager)) {
                            requireControl(sender, manager.getScreenId());
                            startSaved(sender, manager);
                        } else {
                            requireBindIfChanged(sender, manager, message.screenId());
                            start(sender, manager, message.screenId(),
                                    message.videoUrl(), message.audioUrl(),
                                    message.requestHeaders(), message.cookie(),
                                    message.disableScaling(), message.videoPipeLanes(),
                                    message.videoPixelFormat(), message.audioRange(),
                                    message.audioPlaybackMode());
                        }
                    } else {
                        requireControl(sender, manager.getScreenId());
                        startSaved(sender, manager);
                    }
                }
                case PAUSE -> {
                    requireControl(sender, manager.getScreenId());
                    ServerVideoSessionManager.setPlayingForScreen(
                            sender.getServer(), manager.getScreenId(), false);
                }
                case RESUME -> {
                    requireControl(sender, manager.getScreenId());
                    requireGlobalAudioPermission(sender,
                            ServerVideoSessionManager.controlState(
                                    manager.getScreenId()).audioPlaybackMode());
                    ServerVideoSessionManager.setPlayingForScreen(
                            sender.getServer(), manager.getScreenId(), true);
                }
                case SEEK -> {
                    requireControl(sender, manager.getScreenId());
                    requireGlobalAudioPermission(sender,
                            ServerVideoSessionManager.controlState(
                                    manager.getScreenId()).audioPlaybackMode());
                    if (!ServerVideoSessionManager.seekForScreen(
                            sender.getServer(), manager.getScreenId(), message.positionMs())) {
                        throw new LocalizedArgumentException(
                                "message.video_synchronizer.error.seek_unavailable");
                    }
                }
                case STOP -> {
                    requireControl(sender, manager.getScreenId());
                    ServerVideoSessionManager.stopForScreen(manager.getScreenId());
                }
            }
            success = true;
        } catch (IllegalArgumentException exception) {
            sender.sendSystemMessage(LocalizedArgumentException.component(exception));
        } finally {
            VideoUsagePolicy.audit(sender, auditScreenId,
                    message.action().name().toLowerCase(java.util.Locale.ROOT), success);
        }
        OpenVideoManagerMessage.send(sender, message.pos(), manager);
    }

    private static void saveConfiguration(net.minecraft.server.level.ServerPlayer sender,
                                           VideoManagerBlockEntity manager,
                                           String requestedScreenId, String requestedVideoUrl,
                                           String requestedAudioUrl, String requestedHeaders,
                                           String requestedCookie,
                                           boolean disableScaling, int requestedVideoPipeLanes,
                                           VideoPixelFormat videoPixelFormat,
                                           double requestedAudioRange,
                                           AudioPlaybackMode audioPlaybackMode) {
        requireGlobalAudioPermission(sender, audioPlaybackMode);
        String screenId = ServerScreenRegistry.normalizeId(requestedScreenId);
        ServerScreenRegistry.require(sender.getServer(), screenId);
        String videoUrl = requestedVideoUrl.trim();
        String audioUrl = requestedAudioUrl.trim();
        if (!videoUrl.isBlank()) {
            ServerVideoSessionManager.validateMediaUrl(videoUrl);
        }
        if (!audioUrl.isBlank()) {
            ServerVideoSessionManager.validateMediaUrl(audioUrl);
        }
        MediaRequestOptions options = new MediaRequestOptions(requestedHeaders, requestedCookie);
        VideoUsagePolicy.requireRequestMetadataEdit(sender,
                options.headers(), options.cookie(), manager.getRequestHeaders(),
                manager.getCookie());
        int videoPipeLanes = normalizeVideoPipeLanes(requestedVideoPipeLanes);
        double audioRange = ServerVideoSessionManager.validateAudioRange(requestedAudioRange);
        manager.setConfiguration(screenId, videoUrl, audioUrl,
                options.headers(), options.cookie(), disableScaling, videoPipeLanes,
                videoPixelFormat, audioRange, audioPlaybackMode);
    }

    private static void start(net.minecraft.server.level.ServerPlayer sender,
                               VideoManagerBlockEntity manager,
                               String requestedScreenId, String requestedVideoUrl,
                               String requestedAudioUrl, String requestedHeaders,
                               String requestedCookie, boolean disableScaling,
                               int requestedVideoPipeLanes,
                               VideoPixelFormat videoPixelFormat,
                               double requestedAudioRange,
                               AudioPlaybackMode audioPlaybackMode) {
        requireGlobalAudioPermission(sender, audioPlaybackMode);
        String screenId = ServerScreenRegistry.normalizeId(requestedScreenId);
        String videoUrl = requestedVideoUrl.trim();
        String audioUrl = requestedAudioUrl.trim();
        MediaRequestOptions options = new MediaRequestOptions(requestedHeaders, requestedCookie);
        VideoUsagePolicy.requireRequestMetadataEdit(sender,
                options.headers(), options.cookie(), manager.getRequestHeaders(),
                manager.getCookie());
        int videoPipeLanes = normalizeVideoPipeLanes(requestedVideoPipeLanes);
        double audioRange = ServerVideoSessionManager.validateAudioRange(requestedAudioRange);
        ServerVideoSessionManager.startForScreen(sender.getServer(), screenId, videoUrl, audioUrl,
                options.headers(), options.cookie(), disableScaling, videoPipeLanes,
                videoPixelFormat, audioRange, audioPlaybackMode,
                sender.getGameProfile().getName(), sender.getUUID());
        manager.setConfiguration(screenId, videoUrl, audioUrl,
                options.headers(), options.cookie(), disableScaling, videoPipeLanes,
                videoPixelFormat, audioRange, audioPlaybackMode);
    }

    private static void startSaved(net.minecraft.server.level.ServerPlayer sender,
                                   VideoManagerBlockEntity manager) {
        requireGlobalAudioPermission(sender, manager.getAudioPlaybackMode());
        ServerVideoSessionManager.startForScreen(sender.getServer(), manager.getScreenId(),
                manager.getVideoUrl(), manager.getAudioUrl(), manager.getRequestHeaders(),
                manager.getCookie(), manager.isScalingDisabled(), manager.getVideoPipeLanes(),
                manager.getVideoPixelFormat(), manager.getAudioRange(),
                manager.getAudioPlaybackMode(), sender.getGameProfile().getName(),
                sender.getUUID());
    }

    private static void requireControl(net.minecraft.server.level.ServerPlayer sender,
                                       String screenId) {
        if (!ServerScreenRegistry.canControl(sender, screenId)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.playback_permission");
        }
    }

    private static void requireEdit(net.minecraft.server.level.ServerPlayer sender,
                                    String screenId) {
        if (!ServerScreenRegistry.canEdit(sender, screenId)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.edit_permission");
        }
    }

    private static void requireBindIfChanged(net.minecraft.server.level.ServerPlayer sender,
                                             VideoManagerBlockEntity manager, String screenId) {
        String normalizedScreenId = ServerScreenRegistry.normalizeId(screenId);
        if (normalizedScreenId.equals(manager.getScreenId())) {
            return;
        }
        var screen = ServerScreenRegistry.find(sender.getServer(), screenId).orElse(null);
        if (screen == null
                || !VideoPermissionService.canBind(sender, manager, screen)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.bind_permission");
        }
    }

    private static void requireGlobalAudioPermission(
            net.minecraft.server.level.ServerPlayer sender, AudioPlaybackMode mode) {
        if (mode == AudioPlaybackMode.GLOBAL
                && !VideoPermissionService.isAdmin(sender)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.global_audio_operator");
        }
    }

    private static boolean matchesSavedConfiguration(VideoManagerActionMessage message,
                                                     VideoManagerBlockEntity manager) {
        return ServerScreenRegistry.normalizeId(message.screenId())
                .equals(manager.getScreenId())
                && message.videoUrl().trim().equals(manager.getVideoUrl())
                && message.audioUrl().trim().equals(manager.getAudioUrl())
                && message.requestHeaders().isBlank() && message.cookie().isBlank()
                && message.disableScaling() == manager.isScalingDisabled()
                && normalizeVideoPipeLanes(message.videoPipeLanes())
                == manager.getVideoPipeLanes()
                && message.videoPixelFormat() == manager.getVideoPixelFormat()
                && Double.compare(message.audioRange(), manager.getAudioRange()) == 0
                && message.audioPlaybackMode() == manager.getAudioPlaybackMode();
    }

    private static int normalizeVideoPipeLanes(int lanes) {
        if (lanes == 0 || lanes == 1 || lanes == 2 || lanes == 4
                || lanes == 8 || lanes == 16) {
            return lanes;
        }
        throw new LocalizedArgumentException(
                "message.video_synchronizer.error.pipe_lanes");
    }

    public enum Action {
        SAVE,
        START,
        PAUSE,
        RESUME,
        SEEK,
        STOP
    }
}
