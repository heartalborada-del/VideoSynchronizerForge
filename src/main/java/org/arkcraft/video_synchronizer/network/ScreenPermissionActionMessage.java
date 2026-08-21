package org.arkcraft.video_synchronizer.network;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.LocalizedArgumentException;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.server.ScreenAccessMode;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.VideoUsagePolicy;

import java.util.UUID;
import java.util.function.Supplier;

public record ScreenPermissionActionMessage(BlockPos managerPos, Action action,
                                            String playerName, UUID playerId,
                                            ScreenAccessRole role,
                                            ScreenAccessMode accessMode) {
    private static final UUID EMPTY_ID = new UUID(0L, 0L);

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(managerPos);
        buf.writeEnum(action);
        buf.writeUtf(playerName, 64);
        buf.writeUUID(playerId == null ? EMPTY_ID : playerId);
        buf.writeEnum(role);
        buf.writeEnum(accessMode);
    }

    public static ScreenPermissionActionMessage decode(FriendlyByteBuf buf) {
        return new ScreenPermissionActionMessage(buf.readBlockPos(), buf.readEnum(Action.class),
                buf.readUtf(64), buf.readUUID(), buf.readEnum(ScreenAccessRole.class),
                buf.readEnum(ScreenAccessMode.class));
    }

    public static void handle(ScreenPermissionActionMessage message,
                              Supplier<NetworkEvent.Context> context) {
        ServerPlayer sender = context.get().getSender();
        if (sender == null || sender.blockPosition().distSqr(message.managerPos()) > 64.0D
                || !(sender.level().getBlockEntity(message.managerPos())
                instanceof VideoManagerBlockEntity manager)) {
            return;
        }
        String screenId = manager.getScreenId();
        if (!ServerScreenRegistry.canManage(sender, screenId)) {
            VideoUsagePolicy.audit(sender, screenId, "manage_access", false);
            sender.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.permission.denied"));
            return;
        }
        try {
            switch (message.action()) {
                case OPEN -> {
                }
                case SET -> ServerScreenRegistry.setAccess(sender.getServer(), screenId,
                        message.playerId().equals(EMPTY_ID)
                                ? resolvePlayer(sender, message.playerName())
                                : message.playerId(), message.role());
                case REMOVE -> ServerScreenRegistry.removeAccess(sender.getServer(), screenId,
                        message.playerId());
                case SET_MODE -> ServerScreenRegistry.setAccessMode(sender.getServer(), screenId,
                        message.accessMode());
            }
            VideoUsagePolicy.audit(sender, screenId, "manage_access", true);
            OpenScreenPermissionsMessage.send(sender, message.managerPos(), screenId);
        } catch (IllegalArgumentException exception) {
            VideoUsagePolicy.audit(sender, screenId, "manage_access", false);
            sender.sendSystemMessage(LocalizedArgumentException.component(exception));
        }
    }

    private static UUID resolvePlayer(ServerPlayer sender, String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isBlank()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.enter_player_name");
        }
        ServerPlayer online = sender.getServer().getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        return sender.getServer().getProfileCache().get(name)
                .map(GameProfile::getId)
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_player", name));
    }

    public enum Action {
        OPEN,
        SET,
        REMOVE,
        SET_MODE
    }
}
