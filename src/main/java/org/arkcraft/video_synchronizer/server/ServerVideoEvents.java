package org.arkcraft.video_synchronizer.server;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.block.ScreenLayout;

@Mod.EventBusSubscriber(modid = Main.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerVideoEvents {
    private ServerVideoEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ServerVideoSessionManager.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerVideoSessionManager.sendCurrent(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerVideoSessionManager.playerDisconnected(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ServerVideoSessionManager.reset();
        ServerScreenRegistry.clear();
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && ServerVideoSessionManager.isPlaybackProtected(level, event.getPos())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.translatable(
                    "message.video_synchronizer.playback_protected"), false);
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            event.getAffectedBlocks().removeIf(
                    pos -> ServerVideoSessionManager.isPlaybackProtected(level, pos));
        }
    }

    @SubscribeEvent
    public static void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level
                && ServerVideoSessionManager.isPlaybackProtected(level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("video")
                .then(Commands.literal("start")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("video_id", StringArgumentType.word())
                                .then(Commands.argument("url", StringArgumentType.string())
                                                .executes(context -> {
                                                    try {
                                                        String id = StringArgumentType.getString(context, "video_id");
                                                        String url = StringArgumentType.getString(context, "url");
                                                        ServerVideoSessionManager.start(
                                                                context.getSource().getServer(), id, url);
                                                        context.getSource().sendSuccess(
                                                                () -> Component.literal("Started video " + id), true);
                                                        return 1;
                                                    } catch (IllegalArgumentException exception) {
                                                        context.getSource().sendFailure(Component.literal(exception.getMessage()));
                                                        return 0;
                                                    }
                                                }))))
                .then(Commands.literal("create")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("screen_id", StringArgumentType.word())
                                .then(Commands.argument("width", IntegerArgumentType.integer(
                                                1, ScreenLayout.MAX_DIMENSION))
                                        .then(Commands.argument("height", IntegerArgumentType.integer(
                                                        1, ScreenLayout.MAX_DIMENSION))
                                                .executes(context -> {
                                                    try {
                                                        ServerPlayer player = context.getSource().getPlayerOrException();
                                                        String id = ServerScreenRegistry.requireUnused(
                                                                context.getSource().getServer(),
                                                                StringArgumentType.getString(context, "screen_id"));
                                                        int width = IntegerArgumentType.getInteger(context, "width");
                                                        int height = IntegerArgumentType.getInteger(context, "height");
                                                        var anchor = ScreenCreator.create(player, width, height);
                                                        id = ServerScreenRegistry.assignGroup(
                                                                player.serverLevel(), anchor, id);
                                                        ServerVideoSessionManager.bindScreen(
                                                                context.getSource().getServer(),
                                                                player.serverLevel(), anchor);
                                                        String resultId = id;
                                                        context.getSource().sendSuccess(() -> Component.literal(
                                                                "Created and bound screen " + resultId + " ("
                                                                        + width + "x" + height + ") at "
                                                                        + anchor.toShortString()), true);
                                                        return width * height;
                                                    } catch (IllegalArgumentException exception) {
                                                        context.getSource().sendFailure(
                                                                Component.literal(exception.getMessage()));
                                                        return 0;
                                                    }
                                                })))))
                .then(Commands.literal("pause")
                        .requires(source -> source.hasPermission(2)).executes(context -> {
                    ServerVideoSessionManager.setPlaying(context.getSource().getServer(), false);
                    return 1;
                }))
                .then(Commands.literal("resume")
                        .requires(source -> source.hasPermission(2)).executes(context -> {
                    ServerVideoSessionManager.setPlaying(context.getSource().getServer(), true);
                    return 1;
                }))
                .then(Commands.literal("seek")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("milliseconds", LongArgumentType.longArg(0L))
                                .executes(context -> {
                                    ServerVideoSessionManager.seek(context.getSource().getServer(),
                                            LongArgumentType.getLong(context, "milliseconds"));
                                    return 1;
                                })))
                .then(Commands.literal("weight")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.01D, 100.0D))
                                        .executes(context -> {
                                            ServerPlayer player = EntityArgument.getPlayer(context, "player");
                                            double weight = DoubleArgumentType.getDouble(context, "value");
                                            ServerVideoSessionManager.setPlayerWeight(player.getUUID(), weight);
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "Set " + player.getGameProfile().getName()
                                                            + " weight to " + weight), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("bind")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("screen_id", StringArgumentType.word())
                                .executes(context -> {
                                    try {
                                        String id = StringArgumentType.getString(context, "screen_id");
                                        ServerVideoSessionManager.bindScreen(context.getSource().getServer(), id);
                                        context.getSource().sendSuccess(() -> Component.literal(
                                                "Bound video playback to screen " + id), true);
                                        return 1;
                                    } catch (IllegalArgumentException exception) {
                                        context.getSource().sendFailure(Component.literal(exception.getMessage()));
                                        return 0;
                                    }
                                })))
                .then(Commands.literal("unbind")
                        .requires(source -> source.hasPermission(2)).executes(context -> {
                    ServerVideoSessionManager.unbindScreen(context.getSource().getServer());
                    context.getSource().sendSuccess(() -> Component.literal("Video screen binding cleared"), true);
                    return 1;
                }))
                .then(Commands.literal("stop")
                        .requires(source -> source.hasPermission(2)).executes(context -> {
                    ServerVideoSessionManager.stop();
                    return 1;
                }))
                .then(Commands.literal("status")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> ServerVideoSessionManager.describe(
                                    context.getSource().getServer(),
                                    context.getSource().getPlayer()), false);
                            return 1;
                        })
                        .then(Commands.literal("bossbar")
                                .requires(source -> source.getEntity() instanceof ServerPlayer)
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    boolean enabled = ServerVideoSessionManager.toggleStatusBossBar(player);
                                    context.getSource().sendSuccess(() -> Component.translatable(enabled
                                            ? "command.video_synchronizer.bossbar.enabled"
                                            : "command.video_synchronizer.bossbar.disabled"), false);
                                    return 1;
                                }))));
    }
}
