package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

import java.util.function.Supplier;

/** Local single-player pause duration used to freeze the integrated server clock. */
public record VideoLocalPauseMessage(String sessionId, long sequence, long durationMs) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeLong(sequence);
        buf.writeLong(durationMs);
    }

    public static VideoLocalPauseMessage decode(FriendlyByteBuf buf) {
        return new VideoLocalPauseMessage(buf.readUtf(64), buf.readLong(), buf.readLong());
    }

    public static void handle(VideoLocalPauseMessage message,
                              Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender != null) {
            ServerVideoSessionManager.acceptLocalPause(sender, message);
        }
    }
}
