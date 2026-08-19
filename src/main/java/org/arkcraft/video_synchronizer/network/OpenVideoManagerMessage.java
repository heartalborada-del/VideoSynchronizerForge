package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.client.gui.VideoManagerScreen;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

import java.util.function.Supplier;

public record OpenVideoManagerMessage(BlockPos pos, String screenId, String videoUrl,
                                      String audioUrl, String requestHeaders, String cookie,
                                      boolean disableScaling, int videoPipeLanes,
                                      VideoPixelFormat videoPixelFormat,
                                      double audioRange, AudioPlaybackMode audioPlaybackMode,
                                      boolean active, long positionMs, long durationMs,
                                      boolean live, boolean playing, boolean waitingForClients) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
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
        buf.writeBoolean(active);
        buf.writeLong(positionMs);
        buf.writeLong(durationMs);
        buf.writeBoolean(live);
        buf.writeBoolean(playing);
        buf.writeBoolean(waitingForClients);
    }

    public static OpenVideoManagerMessage decode(FriendlyByteBuf buf) {
        return new OpenVideoManagerMessage(buf.readBlockPos(), buf.readUtf(32),
                buf.readUtf(2048), buf.readUtf(2048),
                buf.readUtf(MediaRequestOptions.MAX_HEADERS_LENGTH),
                buf.readUtf(MediaRequestOptions.MAX_COOKIE_LENGTH), buf.readBoolean(),
                buf.readVarInt(),
                buf.readEnum(VideoPixelFormat.class),
                buf.readDouble(),
                buf.readEnum(AudioPlaybackMode.class),
                buf.readBoolean(),
                buf.readLong(), buf.readLong(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean());
    }

    public static void handle(OpenVideoManagerMessage message,
                              Supplier<NetworkEvent.Context> context) {
        VideoManagerScreen.openOrUpdate(message);
    }

    public static void send(ServerPlayer player, BlockPos pos,
                            VideoManagerBlockEntity manager) {
        ServerVideoSessionManager.ControlState state =
                ServerVideoSessionManager.controlState(manager.getScreenId());
        String videoUrl = state.active() ? state.videoUrl() : manager.getVideoUrl();
        String audioUrl = state.active() ? state.audioUrl() : manager.getAudioUrl();
        String requestHeaders = state.active()
                ? state.requestHeaders() : manager.getRequestHeaders();
        String cookie = state.active() ? state.cookie() : manager.getCookie();
        boolean disableScaling = state.active()
                ? state.disableScaling() : manager.isScalingDisabled();
        int videoPipeLanes = state.active()
                ? state.videoPipeLanes() : manager.getVideoPipeLanes();
        VideoPixelFormat videoPixelFormat = state.active()
                ? state.videoPixelFormat() : manager.getVideoPixelFormat();
        double audioRange = state.active() ? state.audioRange() : manager.getAudioRange();
        AudioPlaybackMode audioPlaybackMode = state.active()
                ? state.audioPlaybackMode() : manager.getAudioPlaybackMode();
        VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenVideoManagerMessage(pos, manager.getScreenId(), videoUrl, audioUrl,
                        requestHeaders, cookie, disableScaling, videoPipeLanes, videoPixelFormat,
                        audioRange, audioPlaybackMode,
                        state.active(), state.positionMs(), state.durationMs(), state.live(),
                        state.playing(), state.waitingForClients()));
    }
}
