package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSession;

import java.util.function.Supplier;

/** Client report for a fatal non-success HTTP response from the media URL. */
public record VideoHttpErrorMessage(String sessionId, int statusCode) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeVarInt(statusCode);
    }

    public static VideoHttpErrorMessage decode(FriendlyByteBuf buf) {
        return new VideoHttpErrorMessage(buf.readUtf(64), buf.readVarInt());
    }

    public static void handle(VideoHttpErrorMessage message,
                              Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null) {
            return;
        }
        var server = sender.getServer();
        if (server != null) {
            ServerVideoSession.acceptHttpError(server, sender, message);
        }
    }
}
