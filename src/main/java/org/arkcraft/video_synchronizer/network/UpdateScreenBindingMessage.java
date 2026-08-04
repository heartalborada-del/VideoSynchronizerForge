package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.ServerVideoSession;

import java.util.function.Supplier;

public record UpdateScreenBindingMessage(BlockPos pos, String screenId, boolean bind) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(screenId, 32);
        buf.writeBoolean(bind);
    }

    public static UpdateScreenBindingMessage decode(FriendlyByteBuf buf) {
        return new UpdateScreenBindingMessage(buf.readBlockPos(), buf.readUtf(32), buf.readBoolean());
    }

    public static void handle(UpdateScreenBindingMessage message, Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null || !sender.hasPermissions(2)
                || sender.blockPosition().distSqr(message.pos()) > 64.0D) {
            return;
        }
        try {
            if (message.bind()) {
                String id = ServerScreenRegistry.assignGroup(sender.serverLevel(), message.pos(), message.screenId());
                ServerVideoSession.bindScreen(sender.getServer(), sender.serverLevel(), message.pos());
                sender.sendSystemMessage(Component.literal("Bound video screen: " + id));
            } else {
                ServerVideoSession.unbindScreen(sender.getServer());
                sender.sendSystemMessage(Component.literal("Video screen unbound"));
            }
        } catch (IllegalArgumentException exception) {
            sender.sendSystemMessage(Component.literal(exception.getMessage()));
        }
    }
}
