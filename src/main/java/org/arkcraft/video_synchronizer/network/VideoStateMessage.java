package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.ClientVideoState;

import java.util.function.Supplier;

public record VideoStateMessage(String sessionId, long positionMs, long durationMs,
                                boolean playing, boolean hardSeek, long revision) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeLong(positionMs);
        buf.writeLong(durationMs);
        buf.writeBoolean(playing);
        buf.writeBoolean(hardSeek);
        buf.writeLong(revision);
    }

    public static VideoStateMessage decode(FriendlyByteBuf buf) {
        return new VideoStateMessage(buf.readUtf(64), buf.readLong(), buf.readLong(),
                buf.readBoolean(), buf.readBoolean(), buf.readLong());
    }

    public static void handle(VideoStateMessage message, Supplier<NetworkEvent.Context> context) {
        ClientVideoState.acceptState(message);
    }
}
