package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.ClientVideoState;

import java.util.function.Supplier;

public record VideoStartMessage(String sessionId, String videoId, String url, long durationMs,
                                long positionMs, boolean playing, long revision) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeUtf(videoId, 256);
        buf.writeUtf(url, 2048);
        buf.writeLong(durationMs);
        buf.writeLong(positionMs);
        buf.writeBoolean(playing);
        buf.writeLong(revision);
    }

    public static VideoStartMessage decode(FriendlyByteBuf buf) {
        return new VideoStartMessage(buf.readUtf(64), buf.readUtf(256), buf.readUtf(2048),
                buf.readLong(), buf.readLong(), buf.readBoolean(), buf.readLong());
    }

    public static void handle(VideoStartMessage message, Supplier<NetworkEvent.Context> context) {
        ClientVideoState.acceptStart(message);
    }
}
