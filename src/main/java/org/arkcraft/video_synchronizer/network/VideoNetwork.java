package org.arkcraft.video_synchronizer.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;
import org.arkcraft.video_synchronizer.Main;

/** The single protocol used by both sides of a video session. */
public final class VideoNetwork {
    private static final String PROTOCOL = "6";
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
    }
}
