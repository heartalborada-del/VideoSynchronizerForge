package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.LocalizedArgumentException;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.VideoPermissionService;
import org.arkcraft.video_synchronizer.server.VideoUsagePolicy;

import java.util.function.Supplier;

public record UpdateScreenBindingMessage(BlockPos pos, BlockPos selectionEnd,
                                         String screenId, boolean bind) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBlockPos(selectionEnd);
        buf.writeUtf(screenId, 32);
        buf.writeBoolean(bind);
    }

    public static UpdateScreenBindingMessage decode(FriendlyByteBuf buf) {
        return new UpdateScreenBindingMessage(buf.readBlockPos(), buf.readBlockPos(),
                buf.readUtf(32), buf.readBoolean());
    }

    public static void handle(UpdateScreenBindingMessage message, Supplier<NetworkEvent.Context> context) {
        var sender = context.get().getSender();
        if (sender == null || sender.blockPosition().distSqr(message.selectionEnd()) > 64.0D) {
            return;
        }
        String auditScreenId = message.screenId();
        String action = message.bind() ? "create" : "unbind_global";
        boolean success = false;
        try {
            if (message.bind()) {
                if (!message.pos().equals(message.selectionEnd())
                        && !ServerScreenRegistry.consumeAuthorizedSelection(
                        sender, message.pos(), message.selectionEnd())) {
                    throw new LocalizedArgumentException(
                            "message.video_synchronizer.error.selection_expired");
                }
                String id = message.pos().equals(message.selectionEnd())
                        ? ServerScreenRegistry.assignGroup(sender, message.pos(), message.screenId())
                        : ServerScreenRegistry.assignSelection(sender, message.pos(),
                        message.selectionEnd(), message.screenId());
                auditScreenId = id;
                sender.sendSystemMessage(Component.translatable(
                        "message.video_synchronizer.binding.created", id));
            } else {
                if (!VideoPermissionService.isAdmin(sender)) {
                    throw new LocalizedArgumentException(
                            "message.video_synchronizer.error.admin_unbind_required");
                }
                org.arkcraft.video_synchronizer.server.ServerVideoSessionManager
                        .unbindScreen(sender.getServer());
                sender.sendSystemMessage(Component.translatable(
                        "message.video_synchronizer.binding.unbound"));
            }
            success = true;
        } catch (IllegalArgumentException exception) {
            sender.sendSystemMessage(LocalizedArgumentException.component(exception));
        } finally {
            VideoUsagePolicy.audit(sender, auditScreenId, action, success);
        }
    }
}
