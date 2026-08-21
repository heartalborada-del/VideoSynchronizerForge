package org.arkcraft.video_synchronizer.network;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.client.gui.PlaybackConsentScreen;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.ServerVideoSessionManager;

import java.util.UUID;
import java.util.function.Supplier;

public record OpenPlaybackConsentMessage(BlockPos sourcePos, String screenId,
                                          boolean allowed, String ownerName,
                                          String initiatedBy) {
    public static final String SERVER_INITIATOR = "#server";

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(sourcePos);
        buf.writeUtf(screenId, 32);
        buf.writeBoolean(allowed);
        buf.writeUtf(ownerName, 64);
        buf.writeUtf(initiatedBy, 64);
    }

    public static OpenPlaybackConsentMessage decode(FriendlyByteBuf buf) {
        return new OpenPlaybackConsentMessage(buf.readBlockPos(), buf.readUtf(32),
                buf.readBoolean(), buf.readUtf(64), buf.readUtf(64));
    }

    public static void handle(OpenPlaybackConsentMessage message,
                              Supplier<NetworkEvent.Context> context) {
        PlaybackConsentScreen.open(message);
    }

    public static void send(ServerPlayer player, BlockPos sourcePos, String screenId) {
        MinecraftServer server = player.getServer();
        UUID ownerId = ServerScreenRegistry.ownerId(server, screenId).orElse(null);
        String ownerName = ownerId == null ? "-" : playerName(server, ownerId);
        VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new OpenPlaybackConsentMessage(sourcePos, screenId,
                        ServerScreenRegistry.hasPlaybackConsent(player, screenId), ownerName,
                        ServerVideoSessionManager.playbackInitiator(screenId)));
    }

    private static String playerName(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache().get(playerId)
                .map(GameProfile::getName).orElse(playerId.toString());
    }
}
