package org.arkcraft.video_synchronizer.network;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.client.gui.ScreenPermissionsScreen;
import org.arkcraft.video_synchronizer.server.ScreenAccessMode;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public record OpenScreenPermissionsMessage(BlockPos managerPos, String screenId,
                                           String ownerName, ScreenAccessMode accessMode,
                                           List<Entry> entries) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(managerPos);
        buf.writeUtf(screenId, 32);
        buf.writeUtf(ownerName, 64);
        buf.writeEnum(accessMode);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeUUID(entry.playerId());
            buf.writeUtf(entry.playerName(), 64);
            buf.writeEnum(entry.role());
        }
    }

    public static OpenScreenPermissionsMessage decode(FriendlyByteBuf buf) {
        BlockPos managerPos = buf.readBlockPos();
        String screenId = buf.readUtf(32);
        String ownerName = buf.readUtf(64);
        ScreenAccessMode accessMode = buf.readEnum(ScreenAccessMode.class);
        int size = buf.readVarInt();
        if (size < 0 || size > 4096) {
            throw new IllegalArgumentException("Invalid screen permission count: " + size);
        }
        List<Entry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new Entry(buf.readUUID(), buf.readUtf(64),
                    buf.readEnum(ScreenAccessRole.class)));
        }
        return new OpenScreenPermissionsMessage(managerPos, screenId, ownerName,
                accessMode, entries);
    }

    public static void handle(OpenScreenPermissionsMessage message,
                              Supplier<NetworkEvent.Context> context) {
        ScreenPermissionsScreen.openOrUpdate(message);
    }

    public static void send(ServerPlayer player, BlockPos managerPos, String screenId) {
        ServerScreenRegistry.AccessSnapshot access =
                ServerScreenRegistry.accessSnapshot(player.getServer(), screenId);
        List<Entry> entries = new ArrayList<>();
        access.controllers().forEach(playerId -> entries.add(new Entry(playerId,
                playerName(player.getServer(), playerId), ScreenAccessRole.CONTROL)));
        access.editors().forEach(playerId -> entries.add(new Entry(playerId,
                playerName(player.getServer(), playerId), ScreenAccessRole.EDIT)));
        entries.sort(Comparator.comparing(Entry::playerName,
                String.CASE_INSENSITIVE_ORDER));
        String ownerName = access.ownerId() == null
                ? "-" : playerName(player.getServer(), access.ownerId());
        VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenScreenPermissionsMessage(managerPos, screenId, ownerName,
                        access.accessMode(), entries));
    }

    private static String playerName(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache().get(playerId)
                .map(GameProfile::getName).orElse(playerId.toString());
    }

    public record Entry(UUID playerId, String playerName, ScreenAccessRole role) {
    }
}
