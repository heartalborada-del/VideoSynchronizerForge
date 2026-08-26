package org.arkcraft.video_synchronizer.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.network.packet.clientbound.OpenPlaybackConsentMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.OpenScreenBindingMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.OpenScreenPermissionsMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.OpenVideoManagerMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.VideoPlaybackNoticeMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.VideoScreenTargetMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.VideoStartMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.VideoStateMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.VideoStopMessage;
import org.arkcraft.video_synchronizer.network.packet.clientbound.VideoTimeSyncResponseMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.ScreenPermissionActionMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.UpdatePlaybackConsentMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.UpdateScreenBindingMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.VideoClientCapabilityMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.VideoLocalPauseMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.VideoManagerActionMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.VideoPlaybackErrorMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.VideoProgressMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.VideoReadyMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.VideoResyncMessage;
import org.arkcraft.video_synchronizer.network.packet.serverbound.VideoTimeSyncRequestMessage;

/** The single protocol used by both sides of a video session. */
public final class VideoNetwork {
    private static final String PROTOCOL = "24";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(Main.MODID, "sync"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private static int nextId;
    private static boolean registered;

    private VideoNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        CHANNEL.messageBuilder(VideoStartMessage.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VideoStartMessage::encode).decoder(VideoStartMessage::decode)
                .consumerMainThread(VideoStartMessage::handle).add();
        CHANNEL.messageBuilder(VideoStateMessage.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VideoStateMessage::encode).decoder(VideoStateMessage::decode)
                .consumerMainThread(VideoStateMessage::handle).add();
        CHANNEL.messageBuilder(VideoProgressMessage.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VideoProgressMessage::encode).decoder(VideoProgressMessage::decode)
                .consumerMainThread(VideoProgressMessage::handle).add();
        CHANNEL.messageBuilder(VideoReadyMessage.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VideoReadyMessage::encode).decoder(VideoReadyMessage::decode)
                .consumerMainThread(VideoReadyMessage::handle).add();
        CHANNEL.messageBuilder(VideoClientCapabilityMessage.class, nextId++,
                        NetworkDirection.PLAY_TO_SERVER)
                .encoder(VideoClientCapabilityMessage::encode)
                .decoder(VideoClientCapabilityMessage::decode)
                .consumerMainThread(VideoClientCapabilityMessage::handle).add();
        CHANNEL.messageBuilder(VideoPlaybackErrorMessage.class, nextId++,
                        NetworkDirection.PLAY_TO_SERVER)
                .encoder(VideoPlaybackErrorMessage::encode)
                .decoder(VideoPlaybackErrorMessage::decode)
                .consumerMainThread(VideoPlaybackErrorMessage::handle).add();
        CHANNEL.messageBuilder(VideoPlaybackNoticeMessage.class, nextId++,
                        NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VideoPlaybackNoticeMessage::encode)
                .decoder(VideoPlaybackNoticeMessage::decode)
                .consumerMainThread(VideoPlaybackNoticeMessage::handle).add();
        CHANNEL.messageBuilder(VideoLocalPauseMessage.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VideoLocalPauseMessage::encode).decoder(VideoLocalPauseMessage::decode)
                .consumerMainThread(VideoLocalPauseMessage::handle).add();
        CHANNEL.messageBuilder(VideoResyncMessage.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VideoResyncMessage::encode).decoder(VideoResyncMessage::decode)
                .consumerMainThread(VideoResyncMessage::handle).add();
        CHANNEL.messageBuilder(VideoStopMessage.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VideoStopMessage::encode).decoder(VideoStopMessage::decode)
                .consumerMainThread(VideoStopMessage::handle).add();
        CHANNEL.messageBuilder(VideoScreenTargetMessage.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VideoScreenTargetMessage::encode).decoder(VideoScreenTargetMessage::decode)
                .consumerMainThread(VideoScreenTargetMessage::handle).add();
        CHANNEL.messageBuilder(OpenScreenBindingMessage.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenScreenBindingMessage::encode).decoder(OpenScreenBindingMessage::decode)
                .consumerMainThread(OpenScreenBindingMessage::handle).add();
        CHANNEL.messageBuilder(UpdateScreenBindingMessage.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UpdateScreenBindingMessage::encode).decoder(UpdateScreenBindingMessage::decode)
                .consumerMainThread(UpdateScreenBindingMessage::handle).add();
        CHANNEL.messageBuilder(OpenVideoManagerMessage.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenVideoManagerMessage::encode).decoder(OpenVideoManagerMessage::decode)
                .consumerMainThread(OpenVideoManagerMessage::handle).add();
        CHANNEL.messageBuilder(VideoManagerActionMessage.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VideoManagerActionMessage::encode).decoder(VideoManagerActionMessage::decode)
                .consumerMainThread(VideoManagerActionMessage::handle).add();
        CHANNEL.messageBuilder(ScreenPermissionActionMessage.class, nextId++,
                        NetworkDirection.PLAY_TO_SERVER)
                .encoder(ScreenPermissionActionMessage::encode)
                .decoder(ScreenPermissionActionMessage::decode)
                .consumerMainThread(ScreenPermissionActionMessage::handle).add();
        CHANNEL.messageBuilder(OpenScreenPermissionsMessage.class, nextId++,
                        NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenScreenPermissionsMessage::encode)
                .decoder(OpenScreenPermissionsMessage::decode)
                .consumerMainThread(OpenScreenPermissionsMessage::handle).add();
        CHANNEL.messageBuilder(OpenPlaybackConsentMessage.class, nextId++,
                        NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenPlaybackConsentMessage::encode)
                .decoder(OpenPlaybackConsentMessage::decode)
                .consumerMainThread(OpenPlaybackConsentMessage::handle).add();
        CHANNEL.messageBuilder(UpdatePlaybackConsentMessage.class, nextId++,
                        NetworkDirection.PLAY_TO_SERVER)
                .encoder(UpdatePlaybackConsentMessage::encode)
                .decoder(UpdatePlaybackConsentMessage::decode)
                .consumerMainThread(UpdatePlaybackConsentMessage::handle).add();
        CHANNEL.messageBuilder(VideoTimeSyncRequestMessage.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(VideoTimeSyncRequestMessage::encode).decoder(VideoTimeSyncRequestMessage::decode)
                .consumerMainThread(VideoTimeSyncRequestMessage::handle).add();
        CHANNEL.messageBuilder(VideoTimeSyncResponseMessage.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VideoTimeSyncResponseMessage::encode).decoder(VideoTimeSyncResponseMessage::decode)
                .consumerMainThread(VideoTimeSyncResponseMessage::handle).add();
    }
}
