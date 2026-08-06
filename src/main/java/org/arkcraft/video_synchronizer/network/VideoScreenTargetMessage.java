package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.ClientScreenTarget;

import java.util.function.Supplier;

public record VideoScreenTargetMessage(String sessionId, String screenId,
                                       boolean bound, String dimension,
                                       int originX, int originY, int originZ,
                                       Direction facing, Direction screenUp,
                                       int width, int height) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(sessionId, 64);
        buf.writeUtf(screenId, 32);
        buf.writeBoolean(bound);
        buf.writeUtf(dimension, 256);
        buf.writeInt(originX);
        buf.writeInt(originY);
        buf.writeInt(originZ);
        buf.writeEnum(facing);
        buf.writeEnum(screenUp);
        buf.writeVarInt(width);
        buf.writeVarInt(height);
    }

    public static VideoScreenTargetMessage decode(FriendlyByteBuf buf) {
        return new VideoScreenTargetMessage(buf.readUtf(64), buf.readUtf(32),
                buf.readBoolean(), buf.readUtf(256),
                buf.readInt(), buf.readInt(), buf.readInt(), buf.readEnum(Direction.class),
                buf.readEnum(Direction.class), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(VideoScreenTargetMessage message, Supplier<NetworkEvent.Context> context) {
        ClientScreenTarget.accept(message);
    }
}
