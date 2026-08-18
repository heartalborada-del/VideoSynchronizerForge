package org.arkcraft.video_synchronizer.client;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.arkcraft.video_synchronizer.Main;

@Mod.EventBusSubscriber(modid = Main.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientVideoCommands {
    private ClientVideoCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("video")
                .then(Commands.literal("sync").executes(context -> {
                    int sessionCount = ClientVideoState.forceResync();
                    if (sessionCount == 0) {
                        context.getSource().sendFailure(Component.translatable(
                                "command.video_synchronizer.sync.none"));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.translatable(
                            "command.video_synchronizer.sync.requested", sessionCount), false);
                    return sessionCount;
                })));
    }
}
