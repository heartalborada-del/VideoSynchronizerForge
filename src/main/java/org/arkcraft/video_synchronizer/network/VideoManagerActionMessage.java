package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

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
        if (sender == null || !sender.hasPermissions(2)
                || sender.blockPosition().distSqr(message.pos()) > 64.0D
                || !(sender.level().getBlockEntity(message.pos())
                instanceof VideoManagerBlockEntity manager)) {
            return;
        }
        try {
            switch (message.action()) {
                case SAVE -> saveConfiguration(sender, manager, message.screenId(),
                        message.videoUrl(), message.audioUrl(), message.requestHeaders(),
                        message.cookie(), message.disableScaling(), message.videoPipeLanes(),
                        message.videoPixelFormat(), message.audioRange(),
                        message.audioPlaybackMode());
                case START -> start(sender, manager, message.screenId(),
                        message.videoUrl(), message.audioUrl(), message.requestHeaders(),
                        message.cookie(), message.disableScaling(), message.videoPipeLanes(),
                        message.videoPixelFormat(), message.audioRange(),
                        message.audioPlaybackMode());
                case PAUSE -> ServerVideoSessionManager.setPlayingForScreen(
                        sender.getServer(), manager.getScreenId(), false);
                case RESUME -> ServerVideoSessionManager.setPlayingForScreen(
                        sender.getServer(), manager.getScreenId(), true);
                case SEEK -> ServerVideoSessionManager.seekForScreen(
                        sender.getServer(), manager.getScreenId(), message.positionMs());
                case STOP -> ServerVideoSessionManager.stopForScreen(manager.getScreenId());
            }
        } catch (IllegalArgumentException exception) {
            sender.sendSystemMessage(Component.literal(exception.getMessage()));
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
        String screenId = ServerScreenRegistry.normalizeId(requestedScreenId);
        String videoUrl = requestedVideoUrl.trim();
        String audioUrl = requestedAudioUrl.trim();
        MediaRequestOptions options = new MediaRequestOptions(requestedHeaders, requestedCookie);
        int videoPipeLanes = normalizeVideoPipeLanes(requestedVideoPipeLanes);
        double audioRange = ServerVideoSessionManager.validateAudioRange(requestedAudioRange);
        ServerVideoSessionManager.startForScreen(sender.getServer(), screenId, videoUrl, audioUrl,
                options.headers(), options.cookie(), disableScaling, videoPipeLanes,
                videoPixelFormat, audioRange, audioPlaybackMode);
        manager.setConfiguration(screenId, videoUrl, audioUrl,
                options.headers(), options.cookie(), disableScaling, videoPipeLanes,
                videoPixelFormat, audioRange, audioPlaybackMode);
    }

    private static int normalizeVideoPipeLanes(int lanes) {
        if (lanes == 0 || lanes == 1 || lanes == 2 || lanes == 4
                || lanes == 8 || lanes == 16) {
            return lanes;
        }
        throw new IllegalArgumentException(
                "Video pipe lanes must be Auto, 1, 2, 4, 8, or 16");
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
