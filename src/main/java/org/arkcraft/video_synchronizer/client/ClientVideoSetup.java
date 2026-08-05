package org.arkcraft.video_synchronizer.client;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.client.player.FfmpegPlaybackAdapter;
import org.arkcraft.video_synchronizer.client.render.ScreenBlockEntityRenderer;

@Mod.EventBusSubscriber(modid = Main.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientVideoSetup {
    private ClientVideoSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            BlockEntityRenderers.register(Main.SCREEN_BLOCK_ENTITY.get(), ScreenBlockEntityRenderer::new);
            FfmpegPlaybackAdapter.prepareExecutables();
            ClientVideoState.setPlaybackAdapter(FfmpegPlaybackAdapter.INSTANCE);
        });
    }
}
