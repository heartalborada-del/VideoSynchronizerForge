package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.LocalizedArgumentException;
import org.arkcraft.video_synchronizer.block.ScreenBlockEntity;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;
import org.arkcraft.video_synchronizer.server.VideoUsagePolicy;

import java.util.function.Supplier;

public record UpdatePlaybackConsentMessage(BlockPos sourcePos, String screenId,
                                           boolean allowed) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(sourcePos);
        buf.writeUtf(screenId, 32);
        buf.writeBoolean(allowed);
    }

    public static UpdatePlaybackConsentMessage decode(FriendlyByteBuf buf) {
        return new UpdatePlaybackConsentMessage(buf.readBlockPos(), buf.readUtf(32),
                buf.readBoolean());
    }

    public static void handle(UpdatePlaybackConsentMessage message,
                              Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        if (sender == null || sender.blockPosition().distSqr(message.sourcePos()) > 64.0D) {
            return;
        }
        try {
            String screenId = ServerScreenRegistry.normalizeId(message.screenId());
            if (!screenId.equals(screenIdAt(sender, message.sourcePos()))) {
                return;
            }
            ServerScreenRegistry.require(sender.getServer(), screenId);
            ServerScreenRegistry.setPlaybackConsent(sender.getServer(), screenId,
                    sender.getUUID(), message.allowed());
            ServerVideoSessionManager.playbackConsentChanged(sender.getServer(), sender,
                    screenId, message.allowed());
            sender.sendSystemMessage(Component.translatable(message.allowed()
                    ? "message.video_synchronizer.consent.allowed"
                    : "message.video_synchronizer.consent.blocked", screenId));
            VideoUsagePolicy.audit(sender, screenId,
                    message.allowed() ? "allow_playback" : "block_playback", true);
        } catch (IllegalArgumentException exception) {
            VideoUsagePolicy.audit(sender, message.screenId(),
                    message.allowed() ? "allow_playback" : "block_playback", false);
            sender.sendSystemMessage(LocalizedArgumentException.component(exception));
        }
    }

    private static String screenIdAt(ServerPlayer player, BlockPos pos) {
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        if (blockEntity instanceof ScreenBlockEntity screen) {
            return ServerScreenRegistry.screenId(player.serverLevel(), screen);
        }
        if (blockEntity instanceof VideoManagerBlockEntity manager) {
            return manager.getScreenId();
        }
        return "";
    }
}
