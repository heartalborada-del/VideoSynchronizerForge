package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/** Client timestamp probe used to estimate the server monotonic-clock offset. */
public record VideoTimeSyncRequestMessage(long clientSendNanos, long serverReceiveNanos) {
    public VideoTimeSyncRequestMessage(long clientSendNanos) {
        this(clientSendNanos, 0L);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(clientSendNanos);
    }

    public static VideoTimeSyncRequestMessage decode(FriendlyByteBuf buf) {
        return new VideoTimeSyncRequestMessage(buf.readLong(), System.nanoTime());
    }

    public static void handle(VideoTimeSyncRequestMessage message,
                              Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null) {
            return;
        }
        long serverSendNanos = System.nanoTime();
        VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                new VideoTimeSyncResponseMessage(message.clientSendNanos(),
                        message.serverReceiveNanos(), serverSendNanos));
    }
}
