package org.arkcraft.video_synchronizer.server;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.authlib.GameProfile;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
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
import org.arkcraft.video_synchronizer.LocalizedArgumentException;
import org.arkcraft.video_synchronizer.block.ScreenLayout;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.VideoManagerBlock;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.network.AudioPlaybackMode;
import org.arkcraft.video_synchronizer.network.ScreenAccessRole;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;

import java.util.Locale;
import java.util.UUID;

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
        ServerScreenRegistry.clearPendingSelection(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ServerVideoSessionManager.reset();
        ServerScreenRegistry.clear();
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (ServerVideoSessionManager.isPlaybackProtected(level, event.getPos())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.translatable(
                    "message.video_synchronizer.playback_protected"), false);
            return;
        }
        if (event.getState().getBlock() instanceof ScreenBlock) {
            var screen = ServerScreenRegistry.find(level, event.getPos()).orElse(null);
            if (screen != null && !VideoPermissionService.canRemove(player, screen)) {
                VideoUsagePolicy.audit(player, screen.displayId(), "remove", false);
                event.setCanceled(true);
                player.displayClientMessage(Component.translatable(
                        "message.video_synchronizer.remove.denied"), false);
                return;
            }
            if (screen != null) {
                VideoUsagePolicy.audit(player, screen.displayId(), "remove", true);
                ServerScreenRegistry.disband(level, event.getPos());
            }
        } else if (event.getState().getBlock() instanceof VideoManagerBlock
                && level.getBlockEntity(event.getPos())
                instanceof VideoManagerBlockEntity manager) {
            boolean allowed = VideoPermissionService.canRemove(player, manager);
            VideoUsagePolicy.audit(player, manager.getScreenId(), "remove_manager", allowed);
            if (!allowed) {
                event.setCanceled(true);
                player.displayClientMessage(Component.translatable(
                        "message.video_synchronizer.remove.denied"), false);
            }
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level) {
            event.getAffectedBlocks().removeIf(
                    pos -> level.getBlockState(pos).getBlock() instanceof ScreenBlock
                            || level.getBlockState(pos).getBlock() instanceof VideoManagerBlock
                            || ServerVideoSessionManager.isPlaybackProtected(level, pos));
        }
    }

    @SubscribeEvent
    public static void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level
                && (level.getBlockState(event.getPos()).getBlock() instanceof ScreenBlock
                || level.getBlockState(event.getPos()).getBlock() instanceof VideoManagerBlock
                || ServerVideoSessionManager.isPlaybackProtected(level, event.getPos()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("video");

        root.then(Commands.literal("start")
                .then(Commands.argument("screen_or_video_id", StringArgumentType.word())
                        .then(Commands.argument("url", StringArgumentType.string())
                                .executes(context -> startCommand(context.getSource(),
                                        StringArgumentType.getString(context,
                                                "screen_or_video_id"),
                                        StringArgumentType.getString(context, "url"))))));

        root.then(Commands.literal("create").requires(ServerVideoEvents::isAdmin)
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .then(Commands.argument("width", IntegerArgumentType.integer(
                                        1, ScreenLayout.MAX_DIMENSION))
                                .then(Commands.argument("height", IntegerArgumentType.integer(
                                                1, ScreenLayout.MAX_DIMENSION))
                                        .executes(context -> createAdminScreen(
                                                context.getSource(),
                                                StringArgumentType.getString(context,
                                                        "screen_id"),
                                                IntegerArgumentType.getInteger(context, "width"),
                                                IntegerArgumentType.getInteger(context,
                                                        "height")))))));

        root.then(Commands.literal("pause")
                .executes(context -> globalPause(context.getSource(), false))
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .executes(context -> setScreenPlaying(context.getSource(),
                                StringArgumentType.getString(context, "screen_id"), false))));
        root.then(Commands.literal("resume")
                .executes(context -> globalPause(context.getSource(), true))
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .executes(context -> setScreenPlaying(context.getSource(),
                                StringArgumentType.getString(context, "screen_id"), true))));
        root.then(Commands.literal("seek")
                .then(Commands.argument("milliseconds", LongArgumentType.longArg(0L))
                        .executes(context -> globalSeek(context.getSource(),
                                LongArgumentType.getLong(context, "milliseconds"))))
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .then(Commands.argument("milliseconds", LongArgumentType.longArg(0L))
                                .executes(context -> screenSeek(context.getSource(),
                                        StringArgumentType.getString(context, "screen_id"),
                                        LongArgumentType.getLong(context,
                                                "milliseconds"))))));
        root.then(Commands.literal("stop")
                .executes(context -> globalStop(context.getSource()))
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .executes(context -> screenStop(context.getSource(),
                                StringArgumentType.getString(context, "screen_id")))));

        root.then(Commands.literal("trust")
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.literal("editor")
                                        .executes(context -> setTrustedPlayer(
                                                context.getSource(),
                                                StringArgumentType.getString(context,
                                                        "screen_id"),
                                                StringArgumentType.getString(context, "player"),
                                                ScreenAccessRole.EDIT)))
                                .then(Commands.literal("controller")
                                        .executes(context -> setTrustedPlayer(
                                                context.getSource(),
                                                StringArgumentType.getString(context,
                                                        "screen_id"),
                                                StringArgumentType.getString(context, "player"),
                                                ScreenAccessRole.CONTROL))))));
        root.then(Commands.literal("untrust")
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> removeTrustedPlayer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "screen_id"),
                                        StringArgumentType.getString(context, "player"))))));
        root.then(Commands.literal("access")
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .executes(context -> setAccessMode(context.getSource(),
                                        StringArgumentType.getString(context, "screen_id"),
                                        StringArgumentType.getString(context, "mode"))))));
        root.then(Commands.literal("transfer").requires(ServerVideoEvents::isAdmin)
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> transferScreen(context.getSource(),
                                        StringArgumentType.getString(context, "screen_id"),
                                        StringArgumentType.getString(context, "player"))))));
        root.then(Commands.literal("info")
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .executes(context -> screenInfo(context.getSource(),
                                StringArgumentType.getString(context, "screen_id")))));

        root.then(Commands.literal("weight").requires(ServerVideoEvents::isAdmin)
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("value",
                                        DoubleArgumentType.doubleArg(0.01D, 100.0D))
                                .executes(context -> {
                                    ServerPlayer player = EntityArgument.getPlayer(
                                            context, "player");
                                    double weight = DoubleArgumentType.getDouble(
                                            context, "value");
                                    ServerVideoSessionManager.setPlayerWeight(
                                            player.getUUID(), weight);
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "command.video_synchronizer.weight.success",
                                            player.getGameProfile().getName(), weight), true);
                                    return 1;
                                }))));
        root.then(Commands.literal("bind").requires(ServerVideoEvents::isAdmin)
                .then(Commands.argument("screen_id", StringArgumentType.word())
                        .executes(context -> run(context.getSource(),
                                StringArgumentType.getString(context, "screen_id"),
                                "bind_global", () -> {
                                    String id = StringArgumentType.getString(
                                            context, "screen_id");
                                    ServerVideoSessionManager.bindScreen(
                                            context.getSource().getServer(), id);
                                    context.getSource().sendSuccess(() -> Component.translatable(
                                            "command.video_synchronizer.bind.success", id), true);
                                    return 1;
                                }))));
        root.then(Commands.literal("unbind").requires(ServerVideoEvents::isAdmin)
                .executes(context -> run(context.getSource(), "#global", "unbind_global",
                        () -> {
                            ServerVideoSessionManager.unbindScreen(
                                    context.getSource().getServer());
                            context.getSource().sendSuccess(() -> Component.translatable(
                                    "command.video_synchronizer.unbind.success"), true);
                            return 1;
                        })));
        root.then(Commands.literal("status")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> ServerVideoSessionManager.describe(
                            context.getSource().getServer(),
                            context.getSource().getPlayer()), false);
                    return 1;
                })
                .then(Commands.literal("bossbar")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .executes(context -> {
                            ServerPlayer player = requirePlayer(context.getSource());
                            boolean enabled = ServerVideoSessionManager
                                    .toggleStatusBossBar(player);
                            context.getSource().sendSuccess(() -> Component.translatable(enabled
                                    ? "command.video_synchronizer.bossbar.enabled"
                                    : "command.video_synchronizer.bossbar.disabled"), false);
                            return 1;
                        })));

        event.getDispatcher().register(root);
    }

    private static int startCommand(CommandSourceStack source, String requestedId, String url) {
        var screen = ServerScreenRegistry.find(source.getServer(), requestedId).orElse(null);
        if (screen == null) {
            if (!isAdmin(source)) {
                source.sendFailure(Component.translatable(
                        "command.video_synchronizer.permission.denied"));
                return 0;
            }
            return run(source, "#global", "start", () -> {
                ServerPlayer player = source.getPlayer();
                ServerVideoSessionManager.start(source.getServer(), requestedId, url,
                        source.getTextName(), player == null ? null : player.getUUID());
                source.sendSuccess(() -> Component.translatable(
                        "command.video_synchronizer.started", requestedId), true);
                return 1;
            });
        }
        String screenId = screen.displayId();
        return run(source, screenId, "start", () -> {
            ServerPlayer player = requirePlayer(source);
            if (!VideoPermissionService.canEditSource(player, screen)) {
                throw new LocalizedArgumentException(
                        "message.video_synchronizer.error.edit_permission");
            }
            ServerVideoSessionManager.startForScreen(source.getServer(), screenId,
                    url.trim(), "", "", "", false, 0, VideoPixelFormat.RGB24,
                    VideoManagerBlockEntity.DEFAULT_AUDIO_RANGE,
                    AudioPlaybackMode.POSITIONAL, player.getGameProfile().getName(),
                    player.getUUID());
            source.sendSuccess(() -> Component.translatable(
                    "command.video_synchronizer.started_screen", screenId), true);
            return 1;
        });
    }

    private static int createAdminScreen(CommandSourceStack source, String requestedId,
                                         int width, int height) {
        return run(source, requestedId, "create_admin", () -> {
            ServerPlayer player = requirePlayer(source);
            String id = ServerScreenRegistry.requireUnused(source.getServer(), requestedId);
            var anchor = ScreenCreator.create(player, width, height);
            id = ServerScreenRegistry.assignGroup(player, anchor, id);
            String resultId = id;
            source.sendSuccess(() -> Component.translatable(
                    "command.video_synchronizer.create.success", resultId, width, height,
                    anchor.toShortString()), true);
            return width * height;
        });
    }

    private static int globalPause(CommandSourceStack source, boolean playing) {
        if (!isAdmin(source)) {
            source.sendFailure(Component.translatable(
                    "command.video_synchronizer.permission.denied"));
            return 0;
        }
        return run(source, "#global", playing ? "resume" : "pause", () -> {
            ServerVideoSessionManager.setPlaying(source.getServer(), playing);
            return 1;
        });
    }

    private static int setScreenPlaying(CommandSourceStack source, String requestedId,
                                        boolean playing) {
        return run(source, requestedId, playing ? "resume" : "pause", () -> {
            requireControl(source, requestedId);
            if (playing) {
                requireGlobalAudioControl(source, requestedId);
            }
            ServerVideoSessionManager.setPlayingForScreen(source.getServer(), requestedId,
                    playing);
            return 1;
        });
    }

    private static int globalSeek(CommandSourceStack source, long positionMs) {
        if (!isAdmin(source)) {
            source.sendFailure(Component.translatable(
                    "command.video_synchronizer.permission.denied"));
            return 0;
        }
        return run(source, "#global", "seek", () -> {
            if (!ServerVideoSessionManager.seek(source.getServer(), positionMs)) {
                throw new LocalizedArgumentException(
                        "message.video_synchronizer.error.seek_unavailable");
            }
            return 1;
        });
    }

    private static int screenSeek(CommandSourceStack source, String requestedId,
                                  long positionMs) {
        return run(source, requestedId, "seek", () -> {
            requireControl(source, requestedId);
            requireGlobalAudioControl(source, requestedId);
            if (!ServerVideoSessionManager.seekForScreen(
                    source.getServer(), requestedId, positionMs)) {
                throw new LocalizedArgumentException(
                        "message.video_synchronizer.error.seek_unavailable");
            }
            return 1;
        });
    }

    private static int globalStop(CommandSourceStack source) {
        if (!isAdmin(source)) {
            source.sendFailure(Component.translatable(
                    "command.video_synchronizer.permission.denied"));
            return 0;
        }
        return run(source, "#all", "stop", () -> {
            ServerVideoSessionManager.stop();
            return 1;
        });
    }

    private static int screenStop(CommandSourceStack source, String requestedId) {
        return run(source, requestedId, "stop", () -> {
            requireControl(source, requestedId);
            ServerVideoSessionManager.stopForScreen(requestedId);
            return 1;
        });
    }

    private static int setTrustedPlayer(CommandSourceStack source, String requestedId,
                                        String playerName, ScreenAccessRole role) {
        return run(source, requestedId, "trust_" + role.name().toLowerCase(Locale.ROOT),
                () -> {
                    requireManager(source, requestedId);
                    GameProfile profile = resolveProfile(source, playerName);
                    ServerScreenRegistry.setAccess(source.getServer(), requestedId,
                            profile.getId(), role);
                    source.sendSuccess(() -> Component.translatable(
                            "command.video_synchronizer.trust.success", profile.getName(),
                            requestedId, Component.translatable(role == ScreenAccessRole.EDIT
                                    ? "gui.video_synchronizer.permissions.edit"
                                    : "gui.video_synchronizer.permissions.control")), true);
                    return 1;
                });
    }

    private static int removeTrustedPlayer(CommandSourceStack source, String requestedId,
                                           String playerName) {
        return run(source, requestedId, "untrust", () -> {
            requireManager(source, requestedId);
            GameProfile profile = resolveProfile(source, playerName);
            ServerScreenRegistry.removeAccess(source.getServer(), requestedId,
                    profile.getId());
            source.sendSuccess(() -> Component.translatable(
                    "command.video_synchronizer.untrust.success", profile.getName(),
                    requestedId), true);
            return 1;
        });
    }

    private static int setAccessMode(CommandSourceStack source, String requestedId,
                                     String requestedMode) {
        return run(source, requestedId, "access", () -> {
            requireManager(source, requestedId);
            ScreenAccessMode mode = ScreenAccessMode.require(requestedMode);
            ServerScreenRegistry.setAccessMode(source.getServer(), requestedId, mode);
            source.sendSuccess(() -> Component.translatable(
                    "command.video_synchronizer.access.success", requestedId,
                    accessModeComponent(mode)), true);
            return 1;
        });
    }

    private static int transferScreen(CommandSourceStack source, String requestedId,
                                      String playerName) {
        return run(source, requestedId, "transfer", () -> {
            GameProfile profile = resolveProfile(source, playerName);
            ServerScreenRegistry.transfer(source.getServer(), requestedId, profile.getId());
            ServerVideoSessionManager.playbackConsentChanged(source.getServer(),
                    profile.getId(), requestedId, true);
            source.sendSuccess(() -> Component.translatable(
                    "command.video_synchronizer.transfer.success", requestedId,
                    profile.getName()), true);
            return 1;
        });
    }

    private static int screenInfo(CommandSourceStack source, String requestedId) {
        return run(source, requestedId, "info", () -> {
            ServerPlayer player = source.getPlayer();
            var screen = ServerScreenRegistry.find(source.getServer(), requestedId)
                    .orElseThrow(() -> new LocalizedArgumentException(
                            "message.video_synchronizer.error.unknown_screen", requestedId));
            if (player != null && !VideoPermissionService.canViewStatus(player, screen)) {
                throw new LocalizedArgumentException(
                        "message.video_synchronizer.error.view_permission");
            }
            String ownerName = screen.ownerId() == null ? "-"
                    : profileName(source, screen.ownerId());
            source.sendSuccess(() -> Component.translatable(
                    "command.video_synchronizer.info", screen.displayId(), ownerName,
                    accessModeComponent(screen.accessMode()),
                    screen.width(), screen.height(), screen.editors().size(),
                    screen.controllers().size(), screen.dimension(),
                    screen.origin().toShortString()), false);
            return 1;
        });
    }

    private static ServerPlayer requireControl(CommandSourceStack source, String requestedId) {
        ServerPlayer player = requirePlayer(source);
        if (!ServerScreenRegistry.canControl(player, requestedId)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.playback_permission");
        }
        return player;
    }

    private static void requireGlobalAudioControl(CommandSourceStack source,
                                                  String requestedId) {
        if (ServerVideoSessionManager.controlState(requestedId).audioPlaybackMode()
                == AudioPlaybackMode.GLOBAL && !isAdmin(source)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.global_audio_control");
        }
    }

    private static void requireManager(CommandSourceStack source, String requestedId) {
        ServerPlayer player = requirePlayer(source);
        if (!ServerScreenRegistry.canManage(player, requestedId)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.not_owner");
        }
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.player_required");
        }
        return player;
    }

    private static boolean isAdmin(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null ? source.hasPermission(2)
                : VideoPermissionService.isAdmin(player);
    }

    private static GameProfile resolveProfile(CommandSourceStack source, String requestedName) {
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isBlank()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.enter_player_name");
        }
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getGameProfile();
        }
        return source.getServer().getProfileCache().get(name)
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_player", name));
    }

    private static String profileName(CommandSourceStack source, UUID playerId) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return source.getServer().getProfileCache().get(playerId)
                .map(GameProfile::getName).orElse(playerId.toString());
    }

    private static Component accessModeComponent(ScreenAccessMode mode) {
        return Component.translatable("gui.video_synchronizer.permissions.access_mode."
                + mode.name().toLowerCase(Locale.ROOT));
    }

    private static int run(CommandSourceStack source, String screenId, String action,
                           CommandAction command) {
        boolean success = false;
        try {
            int result = command.run();
            success = result > 0;
            return result;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(LocalizedArgumentException.component(exception));
            return 0;
        } finally {
            if (source.getPlayer() != null) {
                VideoUsagePolicy.audit(source.getPlayer(), screenId, action, success);
            }
        }
    }

    @FunctionalInterface
    private interface CommandAction {
        int run();
    }
}
