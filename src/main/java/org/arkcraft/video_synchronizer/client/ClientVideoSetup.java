package org.arkcraft.video_synchronizer.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.client.player.FfmpegPlaybackAdapter;
import org.arkcraft.video_synchronizer.client.player.VlcjPlaybackAdapter;
import org.arkcraft.video_synchronizer.client.render.ScreenBlockEntityRenderer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = Main.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientVideoSetup {
    private static final long BACKEND_CHECK_TIMEOUT_SECONDS = 30L;
    private static final ExecutorService BACKEND_CHECK_EXECUTOR =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "VideoSynchronizer-Backend-Check");
                thread.setDaemon(true);
                return thread;
            });

    public static final KeyMapping OPENGL_TEST_KEY = new KeyMapping(
            "key.video_synchronizer.opengl_test", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.video_synchronizer");

    private ClientVideoSetup() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        if (debugUiEnabled()) {
            event.register(OPENGL_TEST_KEY);
        }
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            BlockEntityRenderers.register(Main.SCREEN_BLOCK_ENTITY.get(), ScreenBlockEntityRenderer::new);
            CompletableFuture<Boolean> ffmpegCheck = checkBackend(
                    "FFmpeg", FfmpegPlaybackAdapter::prepareExecutables);
            CompletableFuture<Boolean> vlcjCheck = checkBackend(
                    "VLCJ/LibVLC", VlcjPlaybackAdapter::prepareExecutables);
            ffmpegCheck.thenCombine(vlcjCheck, (ffmpegAvailable, vlcjAvailable) ->
                            new boolean[] {ffmpegAvailable, vlcjAvailable})
                    .thenAccept(availability -> Minecraft.getInstance().execute(() ->
                            ClientVideoState.setPlaybackAvailability(availability[0], availability[1])));
        });
    }

    private static CompletableFuture<Boolean> checkBackend(String backendName,
                                                           Supplier<Boolean> check) {
        CompletableFuture<Boolean> checkFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return check.get();
            } catch (Throwable exception) {
                Main.LOGGER.error("{} backend availability check failed", backendName,
                        exception);
                return false;
            }
        }, BACKEND_CHECK_EXECUTOR);
        return checkFuture.orTimeout(BACKEND_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(exception -> {
                    Main.LOGGER.error("{} backend availability check timed out", backendName,
                            exception);
                    return false;
                });
    }

    public static boolean debugUiEnabled() {
        return !FMLEnvironment.production;
    }
}
