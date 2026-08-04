package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.ClientVideoState;

import java.util.function.Supplier;

public record VideoStopMessage(String sessionId) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
    }

    public static VideoStopMessage decode(FriendlyByteBuf buf) {
        return new VideoStopMessage(buf.readUtf(64));
    }

    public static void handle(VideoStopMessage message, Supplier<NetworkEvent.Context> context) {
        ClientVideoState.acceptStop(message);
    }
}
