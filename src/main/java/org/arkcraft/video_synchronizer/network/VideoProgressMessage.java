package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

import java.util.function.Supplier;

/** Client -> server report. The server never trusts the client session id or range. */
public record VideoProgressMessage(String sessionId, long positionMs, long durationMs, boolean playing) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeLong(positionMs);
        buf.writeLong(durationMs);
        buf.writeBoolean(playing);
    }

    public static VideoProgressMessage decode(FriendlyByteBuf buf) {
        return new VideoProgressMessage(buf.readUtf(64), buf.readLong(), buf.readLong(), buf.readBoolean());
    }

    public static void handle(VideoProgressMessage message, Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender != null) {
            ServerVideoSessionManager.acceptReport(sender, message);
        }
    }
}
