package org.arkcraft.video_synchronizer.server;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenBlockEntity;
import org.arkcraft.video_synchronizer.block.ScreenLayout;
import org.arkcraft.video_synchronizer.block.ScreenOrientation;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;
import org.arkcraft.video_synchronizer.network.MediaRequestOptions;
import org.arkcraft.video_synchronizer.network.AudioPlaybackMode;
import org.arkcraft.video_synchronizer.network.HttpStatusDescriptions;
import org.arkcraft.video_synchronizer.network.VideoLocalPauseMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;
import org.arkcraft.video_synchronizer.network.VideoPlaybackErrorMessage;
import org.arkcraft.video_synchronizer.network.VideoPlaybackNoticeMessage;
import org.arkcraft.video_synchronizer.network.VideoProgressMessage;
import org.arkcraft.video_synchronizer.network.VideoReadyMessage;
import org.arkcraft.video_synchronizer.network.VideoScreenTargetMessage;
import org.arkcraft.video_synchronizer.network.VideoStartMessage;
import org.arkcraft.video_synchronizer.network.VideoStateMessage;
import org.arkcraft.video_synchronizer.network.VideoStopMessage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Authoritative coordinator for independent playback sessions. */
public final class ServerVideoSessionManager {
    private static final String DEFAULT_SESSION_KEY = "#global";
    private static final long BROADCAST_DEBOUNCE_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final int READY_THRESHOLD_NUMERATOR = 4;
    private static final int READY_THRESHOLD_DENOMINATOR = 5;
    private static final double MIN_WEIGHT = 0.01D;
    private static final double MAX_WEIGHT = 100.0D;
    private static final long MAX_REPORT_JUMP_MS = 120_000L;
    private static final long MAX_AUTHORITATIVE_DEVIATION_MS = 15_000L;
    private static final long MIN_OUTLIER_TOLERANCE_MS = 10_000L;
    private static final long JOIN_REPORT_TOLERANCE_MS = 750L;
    private static final int JOIN_REPORT_CONFIRMATIONS = 2;
    private static final long MAX_VIDEO_DURATION_MS = 24L * 60L * 60L * 1000L;
    public static final double MIN_AUDIO_RANGE = 1.0D;
    public static final double MAX_AUDIO_RANGE = 1024.0D;

    private static final Map<String, Session> SESSIONS = new HashMap<>();
    private static final Set<UUID> PLAYBACK_CAPABLE_PLAYERS = new HashSet<>();
    private static final Map<UUID, Double> PLAYER_WEIGHTS = new HashMap<>();
    private static final Map<UUID, ServerBossEvent> STATUS_BOSS_BARS = new HashMap<>();
    private static Target pendingDefaultTarget;
    private static long serverTick;

    private ServerVideoSessionManager() {
    }

    public static void start(MinecraftServer server, String videoId, String url) {
        validateVideoId(videoId);
        validateMediaUrl(url);
        if (pendingDefaultTarget != null && pendingDefaultTarget.screenId != null) {
            Session existing = findSession(pendingDefaultTarget.screenId);
            if (existing != null && existing != SESSIONS.get(DEFAULT_SESSION_KEY)) {
                throw new IllegalArgumentException("Screen already has an active video: "
                        + pendingDefaultTarget.screenId);
            }
        }
        startValidated(server, DEFAULT_SESSION_KEY, videoId, url, "",
                MediaRequestOptions.EMPTY, false, 0, VideoPixelFormat.RGB24,
                VideoManagerBlockEntity.DEFAULT_AUDIO_RANGE, AudioPlaybackMode.POSITIONAL,
                pendingDefaultTarget);
    }

    public static void startForScreen(MinecraftServer server, String requestedScreenId,
                                      String videoUrl, String audioUrl, String requestHeaders,
                                      String cookie, boolean disableScaling, int videoPipeLanes,
                                      VideoPixelFormat videoPixelFormat, double audioRange,
                                      AudioPlaybackMode audioPlaybackMode) {
        String screenId = ServerScreenRegistry.normalizeId(requestedScreenId);
        validateVideoId(screenId);
        validateMediaUrl(videoUrl);
        if (!audioUrl.isBlank()) {
            validateMediaUrl(audioUrl);
        }
        ServerScreenRegistry.ScreenReference reference = ServerScreenRegistry.require(server, screenId);
        ServerLevel level = server.getLevel(reference.dimension());
        if (level == null) {
            throw new IllegalArgumentException("Screen dimension is not loaded: " + screenId);
        }
        Session existing = findSession(screenId);
        if (existing != null) {
            stopSessionKey(sessionKey(existing));
        }
        Target target = target(level, reference.pos(), screenId);
        startValidated(server, screenId, screenId, videoUrl, audioUrl,
                new MediaRequestOptions(requestHeaders, cookie), disableScaling,
                videoPipeLanes, videoPixelFormat, validateAudioRange(audioRange),
                requireAudioPlaybackMode(audioPlaybackMode), target);
    }

    private static void startValidated(MinecraftServer server, String key, String videoId,
                                       String videoUrl, String audioUrl,
                                       MediaRequestOptions options, boolean disableScaling,
                                       int videoPipeLanes, VideoPixelFormat pixelFormat,
                                       double audioRange, AudioPlaybackMode audioPlaybackMode,
                                       Target target) {
        stopSession(key);
        Session session = new Session(UUID.randomUUID().toString(), videoId, videoUrl, audioUrl,
                options, disableScaling, videoPipeLanes,
                pixelFormat == null ? VideoPixelFormat.RGB24 : pixelFormat,
                audioRange, audioPlaybackMode,
                false, false, true, 1L, System.nanoTime(), target);
        SESSIONS.put(key, session);
        refreshEligiblePlayers(server, session);
        session.waitingForClients = !session.eligiblePlayers.isEmpty();
        session.playing = !session.waitingForClients;
        broadcastStart(session);
    }

    public static void setPlaying(MinecraftServer server, boolean playing) {
        Session session = SESSIONS.get(DEFAULT_SESSION_KEY);
        if (session != null) {
            setPlaying(server, session, playing);
        }
    }

    public static void setPlayingForScreen(MinecraftServer server, String screenId,
                                           boolean playing) {
        setPlaying(server, requireSession(screenId), playing);
    }

    private static void setPlaying(MinecraftServer server, Session session, boolean playing) {
        if (session.waitingForClients) {
            session.playWhenReady = playing;
            session.revision++;
            broadcastState(session, false);
            return;
        }
        long nowNanos = System.nanoTime();
        session.positionMs = positionAt(session, nowNanos);
        session.positionNanos = nowNanos;
        session.playing = playing;
        session.revision++;
        session.reports.clear();
        session.joinConfirmations.replaceAll((playerId, ignored) -> 0);
        broadcastState(session, false);
    }

    public static void seek(MinecraftServer server, long positionMs) {
        Session session = SESSIONS.get(DEFAULT_SESSION_KEY);
        if (session != null) {
            seek(server, session, positionMs);
        }
    }

    public static void seekForScreen(MinecraftServer server, String screenId, long positionMs) {
        seek(server, requireSession(screenId), positionMs);
    }

    private static void seek(MinecraftServer server, Session session, long positionMs) {
        boolean playWhenReady = session.waitingForClients ? session.playWhenReady : session.playing;
        refreshEligiblePlayers(server, session);
        boolean waiting = !session.eligiblePlayers.isEmpty();
        session.positionMs = clampToDuration(session, positionMs);
        session.positionNanos = System.nanoTime();
        session.playWhenReady = playWhenReady;
        session.waitingForClients = waiting;
        session.playing = waiting ? false : playWhenReady;
        session.revision++;
        session.reports.clear();
        session.joinConfirmations.clear();
        session.readyDurations.clear();
        broadcastState(session, true);
    }

    public static void stop() {
        if (SESSIONS.isEmpty()) {
            return;
        }
        new ArrayList<>(SESSIONS.keySet()).forEach(ServerVideoSessionManager::stopSession);
    }

    public static void stopForScreen(String screenId) {
        stopSession(requireSessionKey(screenId));
    }

    private static void stopSession(String key) {
        Session session = SESSIONS.remove(key);
        if (session != null) {
            VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                    new VideoStopMessage(session.sessionId));
        }
    }

    public static void bindScreen(MinecraftServer server, ServerLevel level, BlockPos pos) {
        Target target = target(level, pos, screenId(level, pos));
        Session session = SESSIONS.get(DEFAULT_SESSION_KEY);
        if (session == null) {
            pendingDefaultTarget = target;
            return;
        }
        session.target = target;
        pendingDefaultTarget = target;
        broadcastTarget(session);
    }

    public static void bindScreen(MinecraftServer server, String requestedId) {
        String screenId = ServerScreenRegistry.normalizeId(requestedId);
        Session existing = findSession(screenId);
        if (existing != null && existing != SESSIONS.get(DEFAULT_SESSION_KEY)) {
            throw new IllegalArgumentException("Screen already has an active video: " + screenId);
        }
        ServerScreenRegistry.ScreenReference reference = ServerScreenRegistry.require(server, screenId);
        ServerLevel level = server.getLevel(reference.dimension());
        if (level == null) {
            throw new IllegalArgumentException("Screen dimension is not loaded: " + screenId);
        }
        Session session = SESSIONS.get(DEFAULT_SESSION_KEY);
        if (session == null) {
            pendingDefaultTarget = target(level, reference.pos(), screenId);
            return;
        }
        session.target = target(level, reference.pos(), screenId);
        pendingDefaultTarget = session.target;
        broadcastTarget(session);
    }

    public static void unbindScreen(MinecraftServer server) {
        pendingDefaultTarget = null;
        Session session = SESSIONS.get(DEFAULT_SESSION_KEY);
        if (session != null) {
            session.target = null;
            broadcastTarget(session);
        }
    }

    public static ControlState controlState(String screenId) {
        Session session = findSession(screenId);
        if (session == null) {
            return new ControlState(false, "", "", "", "", false, 0,
                    VideoPixelFormat.RGB24, VideoManagerBlockEntity.DEFAULT_AUDIO_RANGE,
                    AudioPlaybackMode.POSITIONAL,
                    0L, 0L, false, false);
        }
        return new ControlState(true, session.videoUrl, session.audioUrl,
                session.requestOptions.headers(), session.requestOptions.cookie(),
                session.disableScaling, session.videoPipeLanes, session.videoPixelFormat,
                session.audioRange, session.audioPlaybackMode,
                positionAt(session, System.nanoTime()), session.durationMs,
                session.playing, session.waitingForClients);
    }

    public static boolean isPlaybackProtected(ServerLevel level, BlockPos pos) {
        for (Session session : SESSIONS.values()) {
            if (session.target == null
                    || !session.target.dimension.equals(level.dimension().location().toString())) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ScreenBlockEntity && isTargetPosition(session, pos)) {
                return true;
            }
            if (blockEntity instanceof VideoManagerBlockEntity manager
                    && session.target.screenId != null
                    && session.target.screenId.equals(manager.getScreenId())) {
                return true;
            }
        }
        return false;
    }

    public static void acceptReport(ServerPlayer player, VideoProgressMessage message) {
        Session session = sessionById(message.sessionId());
        if (session == null) {
            sendCurrent(player);
            return;
        }
        MinecraftServer server = player.getServer();
        if (server != null && !session.eligiblePlayers.contains(player.getUUID())) {
            refreshEligiblePlayers(server, session);
        }
        if (!session.eligiblePlayers.contains(player.getUUID()) || session.waitingForClients) {
            return;
        }
        long reportedDuration = message.durationMs();
        if (reportedDuration < 0L || (reportedDuration > 0L
                && (reportedDuration < 1_000L || reportedDuration > MAX_VIDEO_DURATION_MS))) {
            session.rejectedReports++;
            return;
        }
        long maximumPosition = reportedDuration > 0L ? reportedDuration
                : (session.durationMs > 0L ? session.durationMs : MAX_VIDEO_DURATION_MS);
        if (message.positionMs() < 0L || message.positionMs() > maximumPosition) {
            session.rejectedReports++;
            return;
        }
        long nowNanos = System.nanoTime();
        long expectedPosition = positionAt(session, nowNanos);
        if (Math.abs(message.positionMs() - expectedPosition) > MAX_AUTHORITATIVE_DEVIATION_MS) {
            session.rejectedReports++;
            return;
        }
        UUID playerId = player.getUUID();
        Integer confirmations = session.joinConfirmations.get(playerId);
        if (confirmations != null) {
            if (Math.abs(message.positionMs() - expectedPosition) > JOIN_REPORT_TOLERANCE_MS
                    || message.playing() != session.playing) {
                session.joinConfirmations.put(playerId, 0);
                return;
            }
            if (++confirmations < JOIN_REPORT_CONFIRMATIONS) {
                session.joinConfirmations.put(playerId, confirmations);
                return;
            }
            session.joinConfirmations.remove(playerId);
        }
        PlayerReport previous = session.reports.get(playerId);
        if (previous != null) {
            long elapsedMs = Math.max(50L, elapsedMillis(previous.receivedNanos, nowNanos));
            long allowedJump = Math.min(MAX_REPORT_JUMP_MS, elapsedMs * 4L + 10_000L);
            if (Math.abs(message.positionMs() - previous.positionMs) > allowedJump) {
                session.rejectedReports++;
                return;
            }
        }
        session.reports.put(playerId, new PlayerReport(message.positionMs(), reportedDuration,
                message.playing(), PLAYER_WEIGHTS.getOrDefault(playerId, 1.0D),
                serverTick, nowNanos));
    }

    public static void acceptReady(MinecraftServer server, ServerPlayer player,
                                   VideoReadyMessage message) {
        Session session = sessionById(message.sessionId());
        if (session == null) {
            sendCurrent(player);
            return;
        }
        if (!session.eligiblePlayers.contains(player.getUUID())) {
            refreshEligiblePlayers(server, session);
        }
        if (!session.waitingForClients
                || !session.eligiblePlayers.contains(player.getUUID())) {
            return;
        }
        long duration = message.durationMs();
        if (duration < 0L || (duration > 0L
                && (duration < 1_000L || duration > MAX_VIDEO_DURATION_MS))) {
            session.rejectedReports++;
            return;
        }
        session.readyDurations.put(player.getUUID(), duration);
        tryBeginPlayback(server, session);
    }

    public static void acceptClientCapability(MinecraftServer server, ServerPlayer player,
                                              boolean available) {
        UUID playerId = player.getUUID();
        if (available) {
            PLAYBACK_CAPABLE_PLAYERS.add(playerId);
        } else {
            PLAYBACK_CAPABLE_PLAYERS.remove(playerId);
        }
        for (Session session : SESSIONS.values()) {
            session.reports.remove(playerId);
            session.readyDurations.remove(playerId);
            session.joinConfirmations.remove(playerId);
            refreshEligiblePlayers(server, session);
            if (session.waitingForClients) {
                tryBeginPlayback(server, session);
            } else if (available && session.eligiblePlayers.contains(playerId)) {
                session.joinConfirmations.put(playerId, 0);
            }
        }
    }

    public static void acceptLocalPause(ServerPlayer player, VideoLocalPauseMessage message) {
        MinecraftServer server = player.getServer();
        Session session = sessionById(message.sessionId());
        if (server == null || session == null
                || !session.eligiblePlayers.contains(player.getUUID())
                || server.isDedicatedServer() || server.isPublished()
                || server.getPlayerList().getPlayerCount() != 1
                || !server.isSingleplayerOwner(player.getGameProfile())
                || message.sequence() <= session.lastLocalPauseSequence
                || message.durationMs() < 0L || message.durationMs() > MAX_VIDEO_DURATION_MS
                || !session.playing || message.durationMs() == 0L) {
            return;
        }
        session.lastLocalPauseSequence = message.sequence();
        session.positionNanos += TimeUnit.MILLISECONDS.toNanos(message.durationMs());
        session.revision++;
        session.reports.clear();
        broadcastState(session, false);
    }

    public static void acceptPlaybackError(MinecraftServer server, ServerPlayer player,
                                           VideoPlaybackErrorMessage message) {
        Session session = sessionById(message.sessionId());
        if (session == null || !PLAYBACK_CAPABLE_PLAYERS.contains(player.getUUID())) {
            return;
        }
        Component notice;
        if (message.reason() == VideoPlaybackErrorMessage.Reason.HTTP_ERROR) {
            if (message.statusCode() < 100 || message.statusCode() > 999
                    || message.statusCode() == 200 || message.statusCode() == 206) {
                return;
            }
            notice = Component.translatable("message.video_synchronizer.http_error",
                    session.videoId, message.statusCode(),
                    HttpStatusDescriptions.describe(message.statusCode()));
        } else if (message.reason() == VideoPlaybackErrorMessage.Reason.AUDIO_UNPLAYABLE) {
            notice = Component.translatable("message.video_synchronizer.audio_unplayable",
                    session.videoId);
        } else {
            notice = Component.translatable("message.video_synchronizer.video_unplayable",
                    session.videoId);
        }
        Main.LOGGER.warn("Stopping video session {} after client media failure: reason={}, status={}",
                session.sessionId, message.reason(), message.statusCode());
        stopSessionKey(sessionKey(session));
        if (message.reason() == VideoPlaybackErrorMessage.Reason.HTTP_ERROR) {
            server.getPlayerList().getPlayers().forEach(onlinePlayer ->
                    onlinePlayer.sendSystemMessage(notice));
        } else {
            VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                    new VideoPlaybackNoticeMessage(session.videoId, message.reason()));
        }
    }

    public static void tick(MinecraftServer server) {
        serverTick++;
        if (serverTick % 5L == 0L) {
            updateStatusBossBars(server);
        }
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            tickSession(server, session);
        }
    }

    private static void tickSession(MinecraftServer server, Session session) {
        if (serverTick % 5L == 0L) {
            refreshEligiblePlayers(server, session);
        }
        if (session.waitingForClients) {
            session.readyDurations.keySet().removeIf(
                    id -> !session.eligiblePlayers.contains(id));
            if (tryBeginPlayback(server, session)) {
                return;
            }
            if (periodicBroadcastReady(session)) {
                broadcastState(session, false);
            }
            return;
        }
        session.reports.entrySet().removeIf(entry ->
                server.getPlayerList().getPlayer(entry.getKey()) == null
                        || serverTick - entry.getValue().receivedTick > 100L);
        if (periodicBroadcastReady(session)) {
            recomputeAuthoritativeState(session);
            if (playbackReachedEnd(session)) {
                stopSessionKey(sessionKey(session));
                return;
            }
            broadcastState(session, false);
        }
    }

    public static void playerDisconnected(UUID playerId) {
        PLAYBACK_CAPABLE_PLAYERS.remove(playerId);
        for (Session session : SESSIONS.values()) {
            session.reports.remove(playerId);
            session.readyDurations.remove(playerId);
            session.joinConfirmations.remove(playerId);
            session.eligiblePlayers.remove(playerId);
        }
        ServerBossEvent bossBar = STATUS_BOSS_BARS.remove(playerId);
        if (bossBar != null) {
            bossBar.removeAllPlayers();
        }
    }

    public static void setPlayerWeight(UUID playerId, double weight) {
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
        PLAYER_WEIGHTS.put(playerId, Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight)));
    }

    public static void sendCurrent(ServerPlayer player) {
        for (Session session : SESSIONS.values()) {
            session.reports.remove(player.getUUID());
            if (session.eligiblePlayers.contains(player.getUUID())) {
                session.joinConfirmations.put(player.getUUID(), 0);
            } else {
                session.joinConfirmations.remove(player.getUUID());
            }
            long nowNanos = System.nanoTime();
            VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    startMessage(session, nowNanos));
            VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    targetMessage(session));
        }
    }

    public static void reset() {
        SESSIONS.clear();
        PLAYBACK_CAPABLE_PLAYERS.clear();
        PLAYER_WEIGHTS.clear();
        STATUS_BOSS_BARS.values().forEach(ServerBossEvent::removeAllPlayers);
        STATUS_BOSS_BARS.clear();
        pendingDefaultTarget = null;
        serverTick = 0L;
    }

    public static boolean toggleStatusBossBar(ServerPlayer player) {
        ServerBossEvent existing = STATUS_BOSS_BARS.remove(player.getUUID());
        if (existing != null) {
            existing.removeAllPlayers();
            return false;
        }
        ServerBossEvent bossBar = new ServerBossEvent(
                Component.translatable("command.video_synchronizer.bossbar.idle"),
                BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS);
        bossBar.setDarkenScreen(false);
        bossBar.setPlayBossMusic(false);
        bossBar.setCreateWorldFog(false);
        bossBar.addPlayer(player);
        STATUS_BOSS_BARS.put(player.getUUID(), bossBar);
        return true;
    }

    public static Component describe(MinecraftServer server, ServerPlayer viewer) {
        Component result = Component.translatable("command.video_synchronizer.status.header")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        if (SESSIONS.isEmpty()) {
            return result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.none").withStyle(ChatFormatting.GRAY));
        }
        long nowNanos = System.nanoTime();
        for (Session session : SESSIONS.values()) {
            refreshEligiblePlayers(server, session);
            long serverPosition = positionAt(session, nowNanos);
            result = result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.session", session.videoId,
                    session.sessionId, playbackStateComponent(session.playing,
                            session.waitingForClients)));
            result = result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.time",
                    formatTime(serverPosition), formatTime(session.durationMs)));
            result = result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.target", targetDescription(session)));
            result = result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.clients", session.reports.size(),
                    session.eligiblePlayers.size(), session.rejectedReports));

            if (session.waitingForClients) {
                int required = requiredReadyCount(session.eligiblePlayers.size());
                Component localReadiness;
                if (viewer == null) {
                    localReadiness = Component.translatable(
                            "command.video_synchronizer.status.not_applicable");
                } else if (!isEligibleForSession(viewer, session)) {
                    localReadiness = Component.translatable(
                            "command.video_synchronizer.status.unloaded");
                } else if (!PLAYBACK_CAPABLE_PLAYERS.contains(viewer.getUUID())) {
                    localReadiness = Component.translatable(
                            "command.video_synchronizer.status.unavailable");
                } else {
                    localReadiness = Component.translatable(
                            session.readyDurations.containsKey(viewer.getUUID())
                                    ? "command.video_synchronizer.status.ready"
                                    : "command.video_synchronizer.status.not_ready");
                }
                result = result.copy().append("\n").append(Component.translatable(
                        "command.video_synchronizer.status.readiness",
                        session.readyDurations.size(), required, localReadiness));
                continue;
            }
            if (viewer == null) {
                continue;
            }
            if (!isEligibleForSession(viewer, session)) {
                result = result.copy().append("\n").append(Component.translatable(
                        "command.video_synchronizer.status.local_unloaded")
                        .withStyle(ChatFormatting.GRAY));
                continue;
            }
            if (!PLAYBACK_CAPABLE_PLAYERS.contains(viewer.getUUID())) {
                result = result.copy().append("\n").append(Component.translatable(
                        "command.video_synchronizer.status.local_unavailable")
                        .withStyle(ChatFormatting.RED));
                continue;
            }
            PlayerReport report = session.reports.get(viewer.getUUID());
            if (report == null) {
                result = result.copy().append("\n").append(Component.translatable(
                        "command.video_synchronizer.status.local_missing")
                        .withStyle(ChatFormatting.RED));
                continue;
            }
            long localPosition = report.playing
                    ? report.positionMs + elapsedMillis(report.receivedNanos, nowNanos)
                    : report.positionMs;
            localPosition = clampToDuration(session, localPosition);
            long drift = localPosition - serverPosition;
            long reportAge = elapsedMillis(report.receivedNanos, nowNanos);
            result = result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.local", formatTime(localPosition),
                    drift, reportAge, playbackStateComponent(report.playing, false)));
        }
        return result;
    }

    private static void updateStatusBossBars(MinecraftServer server) {
        STATUS_BOSS_BARS.forEach((playerId, bossBar) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                bossBar.removeAllPlayers();
                return;
            }
            Session session = nearestSession(player);
            if (session == null) {
                bossBar.setName(Component.translatable(
                        "command.video_synchronizer.bossbar.idle"));
                bossBar.setColor(BossEvent.BossBarColor.WHITE);
                bossBar.setProgress(0.0F);
                return;
            }
            if (!PLAYBACK_CAPABLE_PLAYERS.contains(playerId)) {
                bossBar.setName(Component.translatable(
                        "command.video_synchronizer.bossbar.unavailable"));
                bossBar.setColor(BossEvent.BossBarColor.RED);
                bossBar.setProgress(0.0F);
                return;
            }
            if (session.waitingForClients) {
                int required = requiredReadyCount(session.eligiblePlayers.size());
                boolean ready = session.readyDurations.containsKey(playerId);
                bossBar.setName(Component.translatable(
                        "command.video_synchronizer.bossbar.waiting",
                        session.readyDurations.size(), required,
                        Component.translatable(ready
                                ? "command.video_synchronizer.status.ready"
                                : "command.video_synchronizer.status.not_ready")));
                bossBar.setColor(BossEvent.BossBarColor.PURPLE);
                bossBar.setProgress(required == 0 ? 1.0F
                        : Math.min(1.0F, session.readyDurations.size() / (float) required));
                return;
            }
            long serverPosition = positionAt(session, System.nanoTime());
            PlayerReport report = session.reports.get(playerId);
            if (report == null) {
                bossBar.setName(Component.translatable(
                        "command.video_synchronizer.bossbar.no_report",
                        formatTime(serverPosition), formatTime(session.durationMs)));
                bossBar.setColor(BossEvent.BossBarColor.RED);
                bossBar.setProgress(progress(serverPosition, session.durationMs));
                return;
            }
            long localPosition = report.playing
                    ? report.positionMs + elapsedMillis(report.receivedNanos, System.nanoTime())
                    : report.positionMs;
            long drift = localPosition - serverPosition;
            long reportAge = elapsedMillis(report.receivedNanos, System.nanoTime());
            bossBar.setName(Component.translatable(
                    "command.video_synchronizer.bossbar.playback",
                    formatTime(localPosition), formatTime(report.durationMs > 0L
                            ? report.durationMs : session.durationMs), drift,
                    playbackStateComponent(report.playing, false), reportAge));
            bossBar.setProgress(progress(localPosition,
                    report.durationMs > 0L ? report.durationMs : session.durationMs));
            bossBar.setColor(Math.abs(drift) > 750L || reportAge > 2_000L
                    ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.GREEN);
        });
    }

    private static Session nearestSession(ServerPlayer player) {
        Session nearest = null;
        Session fallback = null;
        double nearestDistance = Double.MAX_VALUE;
        String dimension = player.level().dimension().location().toString();
        for (Session session : SESSIONS.values()) {
            if (!isEligibleForSession(player, session)) {
                continue;
            }
            if (session.target == null || !dimension.equals(session.target.dimension)) {
                if (fallback == null) {
                    fallback = session;
                }
                continue;
            }
            double distance = session.target.sourcePosition().distanceToSqr(
                    player.getEyePosition());
            if (distance < nearestDistance) {
                nearest = session;
                nearestDistance = distance;
            }
        }
        return nearest != null ? nearest : fallback;
    }

    private static float progress(long positionMs, long durationMs) {
        return durationMs <= 0L ? 0.0F
                : (float) Math.max(0.0D, Math.min(1.0D, positionMs / (double) durationMs));
    }

    private static String formatTime(long milliseconds) {
        if (milliseconds <= 0L) {
            return "00:00:00";
        }
        long totalSeconds = milliseconds / 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                totalSeconds / 3_600L, totalSeconds % 3_600L / 60L,
                totalSeconds % 60L);
    }

    private static boolean tryBeginPlayback(MinecraftServer server, Session session) {
        if (!session.waitingForClients) {
            return false;
        }
        int required = requiredReadyCount(session.eligiblePlayers.size());
        if (session.readyDurations.size() < required) {
            return false;
        }
        List<Long> durations = session.readyDurations.values().stream()
                .filter(duration -> duration > 0L).sorted().toList();
        long medianDuration = durations.isEmpty() ? 0L
                : durations.get((durations.size() - 1) / 2);
        if (medianDuration > 0L) {
            session.durationMs = medianDuration;
        }
        long nowNanos = System.nanoTime();
        session.waitingForClients = false;
        session.playing = session.playWhenReady;
        session.positionNanos = nowNanos;
        session.revision++;
        Set<UUID> readyPlayers = new HashSet<>(session.readyDurations.keySet());
        session.readyDurations.clear();
        readyPlayers.forEach(playerId -> session.joinConfirmations.put(playerId, 0));
        broadcastState(session, true);
        return true;
    }

    private static void recomputeAuthoritativeState(Session session) {
        long nowNanos = System.nanoTime();
        if (session.reports.isEmpty()) {
            session.positionMs = positionAt(session, nowNanos);
            session.positionNanos = nowNanos;
            session.revision++;
            return;
        }
        List<PlayerReport> reports = new ArrayList<>(session.reports.values());
        List<Long> durations = reports.stream().map(report -> report.durationMs)
                .filter(duration -> duration > 0L).sorted().toList();
        if (!durations.isEmpty()) {
            session.durationMs = durations.get((durations.size() - 1) / 2);
        }
        long median = reports.stream().mapToLong(report -> report.positionMs).sorted()
                .skip((reports.size() - 1L) / 2L).findFirst().orElse(session.positionMs);
        double tolerance = Math.min(MAX_REPORT_JUMP_MS,
                Math.max(MIN_OUTLIER_TOLERANCE_MS, session.durationMs / 20.0D));
        double weighted = 0.0D;
        double totalWeight = 0.0D;
        for (PlayerReport report : reports) {
            if (Math.abs(report.positionMs - median) <= tolerance) {
                weighted += report.positionMs * report.weight;
                totalWeight += report.weight;
            }
        }
        if (totalWeight > 0.0D) {
            session.positionMs = Math.max(positionAt(session, nowNanos),
                    clampToDuration(session, Math.round(weighted / totalWeight)));
            session.positionNanos = nowNanos;
        }
        session.revision++;
    }

    private static boolean playbackReachedEnd(Session session) {
        return !session.waitingForClients && session.durationMs > 0L
                && positionAt(session, System.nanoTime()) >= session.durationMs;
    }

    private static void broadcastStart(Session session) {
        long nowNanos = System.nanoTime();
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), startMessage(session, nowNanos));
        broadcastTarget(session);
    }

    private static VideoStartMessage startMessage(Session session, long nowNanos) {
        return new VideoStartMessage(session.sessionId, session.videoId, session.videoUrl,
                session.audioUrl, session.requestOptions.headers(), session.requestOptions.cookie(),
                session.disableScaling, session.videoPipeLanes, session.videoPixelFormat,
                session.audioRange, session.audioPlaybackMode,
                session.durationMs, positionAt(session, nowNanos), session.playing,
                session.waitingForClients, session.revision, nowNanos);
    }

    private static void broadcastTarget(Session session) {
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), targetMessage(session));
    }

    private static VideoScreenTargetMessage targetMessage(Session session) {
        if (session.target == null) {
            return new VideoScreenTargetMessage(session.sessionId, "", false, "", 0, 0, 0,
                    Direction.NORTH, Direction.NORTH, 0, 0);
        }
        return new VideoScreenTargetMessage(session.sessionId,
                session.target.screenId == null ? "" : session.target.screenId,
                true, session.target.dimension,
                session.target.origin.getX(), session.target.origin.getY(), session.target.origin.getZ(),
                session.target.facing, session.target.screenUp, session.target.layout.width(),
                session.target.layout.height());
    }

    private static void broadcastState(Session session, boolean hardSeek) {
        long nowNanos = System.nanoTime();
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new VideoStateMessage(
                session.sessionId, positionAt(session, nowNanos), session.durationMs,
                session.playing, session.waitingForClients, hardSeek, session.revision, nowNanos));
    }

    private static boolean periodicBroadcastReady(Session session) {
        long nowNanos = System.nanoTime();
        if (session.lastBroadcastNanos == 0L
                || nowNanos - session.lastBroadcastNanos >= BROADCAST_DEBOUNCE_NANOS) {
            session.lastBroadcastNanos = nowNanos;
            return true;
        }
        return false;
    }

    private static Session requireSession(String requestedScreenId) {
        String key = requireSessionKey(requestedScreenId);
        Session session = SESSIONS.get(key);
        if (session == null) {
            throw new IllegalArgumentException("There is no active video for screen " + requestedScreenId);
        }
        return session;
    }

    private static String requireSessionKey(String requestedScreenId) {
        String id = ServerScreenRegistry.normalizeId(requestedScreenId);
        if (SESSIONS.containsKey(id)) {
            return id;
        }
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            if (entry.getValue().target != null && id.equals(entry.getValue().target.screenId)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("There is no active video for screen " + id);
    }

    private static Session findSession(String screenId) {
        if (screenId == null || screenId.isBlank()) {
            return null;
        }
        String id = ServerScreenRegistry.normalizeId(screenId);
        Session direct = SESSIONS.get(id);
        if (direct != null) {
            return direct;
        }
        for (Session session : SESSIONS.values()) {
            if (session.target != null && id.equals(session.target.screenId)) {
                return session;
            }
        }
        return null;
    }

    private static Session sessionById(String sessionId) {
        for (Session session : SESSIONS.values()) {
            if (session.sessionId.equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    private static String sessionKey(Session session) {
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            if (entry.getValue() == session) {
                return entry.getKey();
            }
        }
        return DEFAULT_SESSION_KEY;
    }

    private static void stopSessionKey(String key) {
        Session session = SESSIONS.remove(key);
        if (session != null) {
            VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                    new VideoStopMessage(session.sessionId));
        }
    }

    private static int requiredReadyCount(int capablePlayers) {
        return (capablePlayers * READY_THRESHOLD_NUMERATOR
                + READY_THRESHOLD_DENOMINATOR - 1) / READY_THRESHOLD_DENOMINATOR;
    }

    private static void refreshEligiblePlayers(MinecraftServer server, Session session) {
        Set<UUID> eligible = new HashSet<>();
        for (UUID playerId : PLAYBACK_CAPABLE_PLAYERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && isEligibleForSession(player, session)) {
                eligible.add(playerId);
            }
        }
        for (UUID playerId : new HashSet<>(session.eligiblePlayers)) {
            if (!eligible.contains(playerId)) {
                session.reports.remove(playerId);
                session.readyDurations.remove(playerId);
                session.joinConfirmations.remove(playerId);
            }
        }
        if (!session.waitingForClients) {
            for (UUID playerId : eligible) {
                if (!session.eligiblePlayers.contains(playerId)) {
                    session.joinConfirmations.put(playerId, 0);
                }
            }
        }
        session.eligiblePlayers.clear();
        session.eligiblePlayers.addAll(eligible);
    }

    private static boolean isEligibleForSession(ServerPlayer player, Session session) {
        if (session.audioPlaybackMode != AudioPlaybackMode.POSITIONAL
                || session.target == null) {
            return true;
        }
        if (!session.target.dimension.equals(
                player.level().dimension().location().toString())) {
            return false;
        }
        Vec3 source = session.target.sourcePosition();
        return source.distanceToSqr(player.getEyePosition())
                < session.audioRange * session.audioRange;
    }

    private static long positionAt(Session session, long nowNanos) {
        if (!session.playing || session.waitingForClients) {
            return clampToDuration(session, session.positionMs);
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(Math.max(0L,
                nowNanos - session.positionNanos));
        return clampToDuration(session, session.positionMs + elapsed);
    }

    private static long clampToDuration(Session session, long positionMs) {
        return session.durationMs > 0L ? Math.max(0L, Math.min(session.durationMs, positionMs))
                : Math.max(0L, positionMs);
    }

    private static long elapsedMillis(long fromNanos, long toNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, toNanos - fromNanos));
    }

    private static boolean isTargetPosition(Session session, BlockPos pos) {
        if (session.target == null) {
            return false;
        }
        int dx = pos.getX() - session.target.origin.getX();
        int dy = pos.getY() - session.target.origin.getY();
        int dz = pos.getZ() - session.target.origin.getZ();
        int depth = dot(dx, dy, dz, session.target.facing);
        int column = dot(dx, dy, dz, session.target.orientation().right());
        int row = dot(dx, dy, dz, session.target.orientation().up());
        return depth == 0 && column >= 0 && column < session.target.layout.width()
                && row >= 0 && row < session.target.layout.height();
    }

    private static Target target(ServerLevel level, BlockPos pos, String screenId) {
        if (!(level.getBlockState(pos).getBlock() instanceof ScreenBlock)) {
            throw new IllegalArgumentException("The target position is not a video screen");
        }
        ScreenLayout layout = ScreenLayout.SINGLE;
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
            layout = screen.getLayout();
        }
        Direction facing = level.getBlockState(pos).getValue(ScreenBlock.FACING);
        Direction screenUp = level.getBlockState(pos).getValue(ScreenBlock.SCREEN_UP);
        ScreenOrientation orientation = ScreenOrientation.of(facing, screenUp);
        BlockPos origin = pos.relative(orientation.right(), -layout.column())
                .relative(orientation.up(), -layout.row()).immutable();
        return new Target(level.dimension().location().toString(), screenId, origin,
                layout, facing, screenUp);
    }

    private static String screenId(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ScreenBlockEntity screen
                ? screen.getScreenId() : null;
    }

    private static int dot(int dx, int dy, int dz, Direction direction) {
        return dx * direction.getStepX() + dy * direction.getStepY()
                + dz * direction.getStepZ();
    }

    private static void validateVideoId(String videoId) {
        if (videoId == null || videoId.isBlank() || videoId.length() > 256) {
            throw new IllegalArgumentException("Video id must be 1-256 characters");
        }
    }

    public static void validateMediaUrl(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid media URL");
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || value.length() > 2048) {
                throw new IllegalArgumentException(
                        "Only absolute HTTP(S) media URLs are supported");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid media URL", exception);
        }
    }

    public static double validateAudioRange(double value) {
        if (!Double.isFinite(value) || value < MIN_AUDIO_RANGE || value > MAX_AUDIO_RANGE) {
            throw new IllegalArgumentException("Audio range must be between 1 and 1024 blocks");
        }
        return value;
    }

    private static AudioPlaybackMode requireAudioPlaybackMode(AudioPlaybackMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Audio playback mode is required");
        }
        return mode;
    }

    private static Component playbackStateComponent(boolean playing, boolean waiting) {
        return Component.translatable(waiting ? "command.video_synchronizer.status.buffering"
                : (playing ? "command.video_synchronizer.status.playing"
                : "command.video_synchronizer.status.paused"));
    }

    private static Component targetDescription(Session session) {
        if (session.target == null) {
            return Component.translatable("command.video_synchronizer.status.unbound");
        }
        return Component.translatable("command.video_synchronizer.status.target_bound",
                session.target.screenId == null ? "-" : session.target.screenId,
                session.target.dimension, session.target.origin.toShortString(),
                session.target.layout.width(), session.target.layout.height());
    }

    private record PlayerReport(long positionMs, long durationMs, boolean playing,
                                double weight, long receivedTick, long receivedNanos) {
    }

    private record Target(String dimension, String screenId, BlockPos origin, ScreenLayout layout,
                          Direction facing, Direction screenUp) {
        private ScreenOrientation orientation() {
            return ScreenOrientation.of(facing, screenUp);
        }

        private Vec3 sourcePosition() {
            Direction right = orientation().right();
            Direction up = orientation().up();
            return new Vec3(origin.getX() + 0.5D
                    + right.getStepX() * (layout.width() - 1) / 2.0D
                    + up.getStepX() * (layout.height() - 1) / 2.0D,
                    origin.getY() + 0.5D
                    + right.getStepY() * (layout.width() - 1) / 2.0D
                    + up.getStepY() * (layout.height() - 1) / 2.0D,
                    origin.getZ() + 0.5D
                    + right.getStepZ() * (layout.width() - 1) / 2.0D
                    + up.getStepZ() * (layout.height() - 1) / 2.0D);
        }
    }

    public record ControlState(boolean active, String videoUrl, String audioUrl,
                               String requestHeaders, String cookie, boolean disableScaling,
                               int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                               double audioRange, AudioPlaybackMode audioPlaybackMode,
                               long positionMs, long durationMs, boolean playing,
                               boolean waitingForClients) {
    }

    private static final class Session {
        private final String sessionId;
        private final String videoId;
        private final String videoUrl;
        private final String audioUrl;
        private final MediaRequestOptions requestOptions;
        private final boolean disableScaling;
        private final int videoPipeLanes;
        private final VideoPixelFormat videoPixelFormat;
        private final double audioRange;
        private final AudioPlaybackMode audioPlaybackMode;
        private long durationMs;
        private long positionMs;
        private boolean playing;
        private boolean waitingForClients;
        private boolean playWhenReady;
        private long revision;
        private long positionNanos;
        private long lastBroadcastNanos;
        private long rejectedReports;
        private long lastLocalPauseSequence = Long.MIN_VALUE;
        private Target target;
        private final Map<UUID, PlayerReport> reports = new HashMap<>();
        private final Map<UUID, Integer> joinConfirmations = new HashMap<>();
        private final Map<UUID, Long> readyDurations = new HashMap<>();
        private final Set<UUID> eligiblePlayers = new HashSet<>();

        private Session(String sessionId, String videoId, String videoUrl, String audioUrl,
                        MediaRequestOptions requestOptions, boolean disableScaling,
                        int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                        double audioRange, AudioPlaybackMode audioPlaybackMode,
                        boolean playing, boolean waitingForClients, boolean playWhenReady,
                        long revision, long positionNanos, Target target) {
            this.sessionId = sessionId;
            this.videoId = videoId;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.requestOptions = requestOptions;
            this.disableScaling = disableScaling;
            this.videoPipeLanes = videoPipeLanes;
            this.videoPixelFormat = videoPixelFormat;
            this.audioRange = audioRange;
            this.audioPlaybackMode = audioPlaybackMode;
            this.playing = playing;
            this.waitingForClients = waitingForClients;
            this.playWhenReady = playWhenReady;
            this.revision = revision;
            this.positionNanos = positionNanos;
            this.target = target;
        }
    }
}
