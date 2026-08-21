package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.client.gui.VideoManagerScreen;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.VideoPermissionService;
import org.arkcraft.video_synchronizer.server.VideoUsagePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record OpenVideoManagerMessage(BlockPos pos, String screenId, String videoUrl,
                                      String audioUrl, String requestHeaders, String cookie,
                                      boolean disableScaling, int videoPipeLanes,
                                      VideoPixelFormat videoPixelFormat,
                                      double audioRange, AudioPlaybackMode audioPlaybackMode,
                                      boolean active, long positionMs, long durationMs,
                                      boolean live, boolean playing, boolean waitingForClients,
                                      boolean canControl, boolean canEdit, boolean canManage,
                                      boolean requestMetadataAllowed,
                                      boolean globalAudioAllowed, List<ScreenOption> screens) {
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
        buf.writeBoolean(canControl);
        buf.writeBoolean(canEdit);
        buf.writeBoolean(canManage);
        buf.writeBoolean(requestMetadataAllowed);
        buf.writeBoolean(globalAudioAllowed);
        buf.writeVarInt(screens.size());
        for (ScreenOption screen : screens) {
            buf.writeUtf(screen.screenId(), 32);
            buf.writeVarInt(screen.distance());
            buf.writeBoolean(screen.otherDimension());
            buf.writeBoolean(screen.manageable());
        }
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
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), decodeScreens(buf));
    }

    public static void handle(OpenVideoManagerMessage message,
                              Supplier<NetworkEvent.Context> context) {
        VideoManagerScreen.openOrUpdate(message);
    }

    public static void send(ServerPlayer player, BlockPos pos,
                            VideoManagerBlockEntity manager) {
        boolean canControl = ServerScreenRegistry.canControl(player, manager.getScreenId());
        boolean canEdit = ServerScreenRegistry.canEdit(player, manager.getScreenId());
        boolean canManage = ServerScreenRegistry.canManage(player, manager.getScreenId());
        boolean requestMetadataAllowed = VideoUsagePolicy.canUseRequestMetadata(player);
        List<ServerScreenRegistry.ScreenOption> screens = manager.isOwner(player.getUUID())
                || VideoPermissionService.isAdmin(player)
                ? ServerScreenRegistry.editableScreens(player, pos) : List.of();
        boolean canRebind = manager.isOwner(player.getUUID()) && !screens.isEmpty();
        if (!canControl && !canRebind && !VideoPermissionService.isAdmin(player)) {
            if (manager.isOwner(player.getUUID()) && screens.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "message.video_synchronizer.manager.no_screens"));
            }
            return;
        }
        ServerVideoSessionManager.ControlState state =
                ServerVideoSessionManager.controlState(manager.getScreenId());
        String videoUrl = canEdit
                ? (state.active() ? state.videoUrl() : manager.getVideoUrl()) : "";
        String audioUrl = canEdit
                ? (state.active() ? state.audioUrl() : manager.getAudioUrl()) : "";
        String requestHeaders = canEdit && requestMetadataAllowed
                ? (state.active() ? state.requestHeaders() : manager.getRequestHeaders()) : "";
        String cookie = canEdit && requestMetadataAllowed
                ? (state.active() ? state.cookie() : manager.getCookie()) : "";
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
                        state.playing(), state.waitingForClients(), canControl, canEdit,
                        canManage, requestMetadataAllowed,
                        VideoPermissionService.isAdmin(player),
                        screens.stream()
                                .map(screen -> new ScreenOption(screen.screenId(),
                                        screen.distance(), screen.otherDimension(),
                                        screen.manageable()))
                                .toList()));
    }

    private static List<ScreenOption> decodeScreens(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > 4096) {
            throw new IllegalArgumentException("Invalid screen option count: " + size);
        }
        List<ScreenOption> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(new ScreenOption(buf.readUtf(32), buf.readVarInt(), buf.readBoolean(),
                    buf.readBoolean()));
        }
        return result;
    }

    public record ScreenOption(String screenId, int distance, boolean otherDimension,
                               boolean manageable) {
    }
}
