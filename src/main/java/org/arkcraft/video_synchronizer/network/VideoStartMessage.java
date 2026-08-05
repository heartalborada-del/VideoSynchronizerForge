package org.arkcraft.video_synchronizer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.ClientVideoState;

import java.util.function.Supplier;

public record VideoStartMessage(String sessionId, String videoId, String videoUrl, String audioUrl,
                                String requestHeaders, String cookie, boolean disableScaling,
                                int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                                long durationMs,
                                long positionMs, boolean playing,
                                boolean waitingForClients, long revision,
                                long sentAtNanos, long receivedAtNanos) {
    public VideoStartMessage(String sessionId, String videoId, String videoUrl, String audioUrl,
                             String requestHeaders, String cookie, boolean disableScaling,
                             int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                             long durationMs, long positionMs, boolean playing,
                             boolean waitingForClients, long revision, long sentAtNanos) {
        this(sessionId, videoId, videoUrl, audioUrl, requestHeaders, cookie, disableScaling,
                videoPipeLanes, videoPixelFormat, durationMs, positionMs, playing,
                waitingForClients, revision, sentAtNanos, 0L);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeUtf(videoId, 256);
        buf.writeUtf(videoUrl, 2048);
        buf.writeUtf(audioUrl, 2048);
        buf.writeUtf(requestHeaders, MediaRequestOptions.MAX_HEADERS_LENGTH);
        buf.writeUtf(cookie, MediaRequestOptions.MAX_COOKIE_LENGTH);
        buf.writeBoolean(disableScaling);
        buf.writeVarInt(videoPipeLanes);
        buf.writeEnum(videoPixelFormat);
        buf.writeLong(durationMs);
        buf.writeLong(positionMs);
        buf.writeBoolean(playing);
        buf.writeBoolean(waitingForClients);
        buf.writeLong(revision);
        buf.writeLong(sentAtNanos);
    }

    public static VideoStartMessage decode(FriendlyByteBuf buf) {
        return new VideoStartMessage(buf.readUtf(64), buf.readUtf(256), buf.readUtf(2048),
                buf.readUtf(2048), buf.readUtf(MediaRequestOptions.MAX_HEADERS_LENGTH),
                buf.readUtf(MediaRequestOptions.MAX_COOKIE_LENGTH), buf.readBoolean(),
                buf.readVarInt(),
                buf.readEnum(VideoPixelFormat.class),
                buf.readLong(),
                buf.readLong(), buf.readBoolean(), buf.readBoolean(), buf.readLong(),
                buf.readLong(), System.nanoTime());
    }

    public static void handle(VideoStartMessage message, Supplier<NetworkEvent.Context> context) {
        ClientVideoState.acceptStart(message);
    }
}
