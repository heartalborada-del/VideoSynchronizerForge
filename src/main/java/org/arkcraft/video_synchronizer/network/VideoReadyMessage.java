package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

import java.util.function.Supplier;

/** Client -> server readiness and media classification report for a newly loaded decoder. */
public record VideoReadyMessage(String sessionId, long durationMs, boolean live) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeLong(durationMs);
        buf.writeBoolean(live);
    }

    public static VideoReadyMessage decode(FriendlyByteBuf buf) {
        return new VideoReadyMessage(buf.readUtf(64), buf.readLong(), buf.readBoolean());
    }

    public static void handle(VideoReadyMessage message, Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null) {
            return;
        }
        var server = sender.getServer();
        if (server != null) {
            ServerVideoSessionManager.acceptReady(server, sender, message);
        }
    }
}
