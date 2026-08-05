package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.ClientVideoState;

import java.util.function.Supplier;

/** Server timestamp response; clientReceiveNanos is captured locally during decode. */
public record VideoTimeSyncResponseMessage(long clientSendNanos, long serverReceiveNanos,
                                           long serverSendNanos, long clientReceiveNanos) {
    public VideoTimeSyncResponseMessage(long clientSendNanos, long serverReceiveNanos,
                                        long serverSendNanos) {
        this(clientSendNanos, serverReceiveNanos, serverSendNanos, 0L);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(clientSendNanos);
        buf.writeLong(serverReceiveNanos);
        buf.writeLong(serverSendNanos);
    }

    public static VideoTimeSyncResponseMessage decode(FriendlyByteBuf buf) {
        return new VideoTimeSyncResponseMessage(buf.readLong(), buf.readLong(), buf.readLong(),
                System.nanoTime());
    }

    public static void handle(VideoTimeSyncResponseMessage message,
                              Supplier<NetworkEvent.Context> context) {
        ClientVideoState.acceptTimeSync(message);
    }
}
