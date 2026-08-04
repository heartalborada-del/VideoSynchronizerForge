package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.ServerVideoSession;

import java.util.function.Supplier;

public record VideoManagerActionMessage(BlockPos pos, Action action, String screenId,
                                        String url, long positionMs) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(action);
        buf.writeUtf(screenId, 32);
        buf.writeUtf(url, 2048);
        buf.writeLong(positionMs);
    }

    public static VideoManagerActionMessage decode(FriendlyByteBuf buf) {
        return new VideoManagerActionMessage(buf.readBlockPos(), buf.readEnum(Action.class),
                buf.readUtf(32), buf.readUtf(2048), buf.readLong());
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
                case SAVE -> saveConfiguration(sender, manager, message.screenId(), message.url());
                case START -> start(sender, manager, message.screenId(), message.url());
                case PAUSE -> ServerVideoSession.setPlayingForScreen(
                        sender.getServer(), manager.getScreenId(), false);
                case RESUME -> ServerVideoSession.setPlayingForScreen(
                        sender.getServer(), manager.getScreenId(), true);
                case SEEK -> ServerVideoSession.seekForScreen(
                        sender.getServer(), manager.getScreenId(), message.positionMs());
                case STOP -> ServerVideoSession.stopForScreen(manager.getScreenId());
            }
        } catch (IllegalArgumentException exception) {
            sender.sendSystemMessage(Component.literal(exception.getMessage()));
        }
        OpenVideoManagerMessage.send(sender, message.pos(), manager);
    }

    private static void saveConfiguration(net.minecraft.server.level.ServerPlayer sender,
                                          VideoManagerBlockEntity manager,
                                          String requestedScreenId, String requestedUrl) {
        String screenId = ServerScreenRegistry.normalizeId(requestedScreenId);
        ServerScreenRegistry.require(sender.getServer(), screenId);
        String url = requestedUrl.trim();
        if (!url.isBlank()) {
            ServerVideoSession.validateMediaUrl(url);
        }
        manager.setConfiguration(screenId, url);
    }

    private static void start(net.minecraft.server.level.ServerPlayer sender,
                              VideoManagerBlockEntity manager,
                              String requestedScreenId, String requestedUrl) {
        String screenId = ServerScreenRegistry.normalizeId(requestedScreenId);
        String url = requestedUrl.trim();
        ServerVideoSession.startForScreen(sender.getServer(), screenId, url);
        manager.setConfiguration(screenId, url);
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
