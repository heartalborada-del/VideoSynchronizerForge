package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.ClientVideoState;

import java.util.function.Supplier;

/** Server notice for a media-source failure that stopped a session. */
public record VideoPlaybackNoticeMessage(String videoId,
                                         VideoPlaybackErrorMessage.Reason reason) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(videoId, 256);
        buf.writeEnum(reason);
    }

    public static VideoPlaybackNoticeMessage decode(FriendlyByteBuf buf) {
        return new VideoPlaybackNoticeMessage(buf.readUtf(256),
                buf.readEnum(VideoPlaybackErrorMessage.Reason.class));
    }

    public static void handle(VideoPlaybackNoticeMessage message,
                              Supplier<NetworkEvent.Context> context) {
        ClientVideoState.acceptPlaybackNotice(message);
    }
}
