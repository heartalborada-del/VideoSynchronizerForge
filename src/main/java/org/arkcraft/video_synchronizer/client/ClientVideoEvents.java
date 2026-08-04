package org.arkcraft.video_synchronizer.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.client.render.ScreenTexture;

@Mod.EventBusSubscriber(modid = Main.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientVideoEvents {
    private ClientVideoEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ClientVideoState.setClientPaused(shouldPauseLocalPlayback());
            ClientVideoState.clientTick();
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            ScreenTexture.INSTANCE.update();
        }
        // Render ticks continue while the single-player pause screen is open. Check
        // both phases because Minecraft updates its internal pause flag during a frame.
        ClientVideoState.setClientPaused(shouldPauseLocalPlayback());
    }

    private static boolean shouldPauseLocalPlayback() {
        Minecraft minecraft = Minecraft.getInstance();
        var integratedServer = minecraft.getSingleplayerServer();
        if (integratedServer == null || integratedServer.isPublished()) {
            return false;
        }
        boolean pauseScreenOpen = minecraft.screen != null
                && minecraft.screen.isPauseScreen();
        return minecraft.isPaused() || pauseScreenOpen;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientVideoState.reset();
    }
}
