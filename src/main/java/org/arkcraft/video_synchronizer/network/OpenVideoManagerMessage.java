package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.client.gui.VideoManagerScreen;
import org.arkcraft.video_synchronizer.server.ServerVideoSession;

import java.util.function.Supplier;

public record OpenVideoManagerMessage(BlockPos pos, String screenId, String url,
                                      boolean active, long positionMs, long durationMs,
                                      boolean playing) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(screenId, 32);
        buf.writeUtf(url, 2048);
        buf.writeBoolean(active);
        buf.writeLong(positionMs);
        buf.writeLong(durationMs);
        buf.writeBoolean(playing);
    }

    public static OpenVideoManagerMessage decode(FriendlyByteBuf buf) {
        return new OpenVideoManagerMessage(buf.readBlockPos(), buf.readUtf(32),
                buf.readUtf(2048), buf.readBoolean(), buf.readLong(), buf.readLong(),
                buf.readBoolean());
    }

    public static void handle(OpenVideoManagerMessage message,
                              Supplier<NetworkEvent.Context> context) {
        VideoManagerScreen.openOrUpdate(message);
    }

    public static void send(ServerPlayer player, BlockPos pos,
                            VideoManagerBlockEntity manager) {
        ServerVideoSession.ControlState state = ServerVideoSession.controlState(manager.getScreenId());
        String url = state.active() ? state.url() : manager.getMediaUrl();
        VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenVideoManagerMessage(pos, manager.getScreenId(), url,
                        state.active(), state.positionMs(), state.durationMs(), state.playing()));
    }
}
