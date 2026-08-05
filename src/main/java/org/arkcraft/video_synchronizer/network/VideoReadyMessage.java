package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSession;

import java.util.function.Supplier;

/** Client -> server notification sent after the input preload window completes. */
public record VideoReadyMessage(String sessionId, long durationMs) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeLong(durationMs);
    }

    public static VideoReadyMessage decode(FriendlyByteBuf buf) {
        return new VideoReadyMessage(buf.readUtf(64), buf.readLong());
    }

    public static void handle(VideoReadyMessage message, Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null) {
            return;
        }
        var server = sender.getServer();
        if (server != null) {
            ServerVideoSession.acceptReady(server, sender, message);
        }
    }
}
