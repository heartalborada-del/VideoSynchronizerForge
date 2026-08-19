package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.ClientVideoState;

import java.util.function.Supplier;

public record VideoStateMessage(String sessionId, long positionMs, long durationMs, boolean live,
                                boolean playing, boolean waitingForClients,
                                boolean hardSeek, long revision, long sentAtNanos,
                                long receivedAtNanos) {
    public VideoStateMessage(String sessionId, long positionMs, long durationMs, boolean live,
                             boolean playing, boolean waitingForClients,
                             boolean hardSeek, long revision, long sentAtNanos) {
        this(sessionId, positionMs, durationMs, live, playing, waitingForClients,
                hardSeek, revision, sentAtNanos, 0L);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeLong(positionMs);
        buf.writeLong(durationMs);
        buf.writeBoolean(live);
        buf.writeBoolean(playing);
        buf.writeBoolean(waitingForClients);
        buf.writeBoolean(hardSeek);
        buf.writeLong(revision);
        buf.writeLong(sentAtNanos);
    }

    public static VideoStateMessage decode(FriendlyByteBuf buf) {
        return new VideoStateMessage(buf.readUtf(64), buf.readLong(), buf.readLong(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readLong(),
                buf.readLong(), System.nanoTime());
    }

    public static void handle(VideoStateMessage message, Supplier<NetworkEvent.Context> context) {
        ClientVideoState.acceptState(message);
    }
}
