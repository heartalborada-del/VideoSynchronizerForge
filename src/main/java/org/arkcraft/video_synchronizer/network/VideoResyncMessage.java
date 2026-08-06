package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

import java.util.function.Supplier;

public record VideoResyncMessage(String sessionId) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
    }

    public static VideoResyncMessage decode(FriendlyByteBuf buf) {
        return new VideoResyncMessage(buf.readUtf(64));
    }

    public static void handle(VideoResyncMessage message, Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender != null) {
            ServerVideoSessionManager.sendCurrent(sender);
        }
    }
}
