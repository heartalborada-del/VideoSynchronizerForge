package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

import java.util.function.Supplier;

/** Client report for a fatal media-source failure. */
public record VideoPlaybackErrorMessage(String sessionId, Reason reason, int statusCode) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeEnum(reason);
        buf.writeVarInt(statusCode);
    }

    public static VideoPlaybackErrorMessage decode(FriendlyByteBuf buf) {
        return new VideoPlaybackErrorMessage(buf.readUtf(64), buf.readEnum(Reason.class),
                buf.readVarInt());
    }

    public static void handle(VideoPlaybackErrorMessage message,
                              Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null) {
            return;
        }
        var server = sender.getServer();
        if (server != null) {
            ServerVideoSessionManager.acceptPlaybackError(server, sender, message);
        }
    }

    public enum Reason {
        HTTP_ERROR,
        VIDEO_UNPLAYABLE,
        AUDIO_UNPLAYABLE
    }
}
