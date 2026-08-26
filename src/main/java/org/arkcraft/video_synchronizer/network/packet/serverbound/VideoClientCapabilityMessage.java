package org.arkcraft.video_synchronizer.network.packet.serverbound;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

import java.util.function.Supplier;

/** Client -> server result of the local decoder backend checks. */
public record VideoClientCapabilityMessage(boolean ffmpegAvailable, boolean vlcjAvailable) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(ffmpegAvailable);
        buf.writeBoolean(vlcjAvailable);
    }

    public static VideoClientCapabilityMessage decode(FriendlyByteBuf buf) {
        return new VideoClientCapabilityMessage(buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(VideoClientCapabilityMessage message,
                              Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null) {
            return;
        }
        var server = sender.getServer();
        if (server != null) {
            ServerVideoSessionManager.acceptClientCapability(server, sender,
                    message.ffmpegAvailable(), message.vlcjAvailable());
        }
    }
}
