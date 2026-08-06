package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSession;

import java.util.function.Supplier;

/** Client -> server result of the local FFmpeg and ffprobe executable check. */
public record VideoClientCapabilityMessage(boolean playbackAvailable) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(playbackAvailable);
    }

    public static VideoClientCapabilityMessage decode(FriendlyByteBuf buf) {
        return new VideoClientCapabilityMessage(buf.readBoolean());
    }

    public static void handle(VideoClientCapabilityMessage message,
                              Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null) {
            return;
        }
        var server = sender.getServer();
        if (server != null) {
            ServerVideoSession.acceptClientCapability(server, sender,
                    message.playbackAvailable());
        }
    }
}
