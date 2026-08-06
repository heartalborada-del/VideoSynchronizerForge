package org.arkcraft.video_synchronizer.server;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.network.HttpStatusDescriptions;
import org.arkcraft.video_synchronizer.network.MediaRequestOptions;
import org.arkcraft.video_synchronizer.network.VideoHttpErrorMessage;
import org.arkcraft.video_synchronizer.network.VideoLocalPauseMessage;
import org.arkcraft.video_synchronizer.network.VideoProgressMessage;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;
import org.arkcraft.video_synchronizer.network.VideoReadyMessage;
import org.arkcraft.video_synchronizer.network.VideoStartMessage;
import org.arkcraft.video_synchronizer.network.VideoStateMessage;
import org.arkcraft.video_synchronizer.network.VideoStopMessage;
import org.arkcraft.video_synchronizer.network.VideoScreenTargetMessage;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenBlockEntity;
import org.arkcraft.video_synchronizer.block.ScreenLayout;
import org.arkcraft.video_synchronizer.block.ScreenOrientation;
import org.arkcraft.video_synchronizer.block.VideoManagerBlockEntity;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Server-authoritative video clock. All methods are called from the server thread.
 */
public final class ServerVideoSession {
    public static final int REPORT_TIMEOUT_TICKS = 100;
    private static final long BROADCAST_DEBOUNCE_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final int READY_THRESHOLD_NUMERATOR = 4;
    private static final int READY_THRESHOLD_DENOMINATOR = 5;
    private static final double MIN_WEIGHT = 0.01D;
    private static final double MAX_WEIGHT = 100.0D;
    /** A client cannot move more than this in one report, even if it claims to be playing. */
    private static final long MAX_REPORT_JUMP_MS = 120_000L;
    private static final long MAX_AUTHORITATIVE_DEVIATION_MS = 15_000L;
    private static final long MIN_OUTLIER_TOLERANCE_MS = 10_000L;
    private static final long JOIN_REPORT_TOLERANCE_MS = 750L;
    private static final int JOIN_REPORT_CONFIRMATIONS = 2;
    private static final long MAX_VIDEO_DURATION_MS = 24L * 60L * 60L * 1000L;

    // Each player's newest sample replaces the previous one; the periodic recompute
    // below therefore coalesces bursts before they can affect the authoritative clock.
    private static final Map<UUID, PlayerReport> REPORTS = new HashMap<>();
    private static final Map<UUID, Integer> JOIN_REPORT_CONFIRMATIONS_BY_PLAYER =
            new HashMap<>();
    private static final Map<UUID, Long> READY_DURATIONS = new HashMap<>();
    private static final Map<UUID, Double> PLAYER_WEIGHTS = new HashMap<>();
    private static final Set<UUID> PLAYBACK_CAPABLE_PLAYERS = new HashSet<>();
    private static final Map<UUID, ServerBossEvent> STATUS_BOSS_BARS = new HashMap<>();
    private static Session current;
    private static long serverTick;
    private static long lastBroadcastNanos;
    private static long rejectedReports;
    private static String targetDimension;
    private static BlockPos targetAnchor;
    private static BlockPos targetOrigin;
    private static String targetScreenId;
    private static ScreenLayout targetLayout;
    private static Direction targetFacing;
    private static Direction targetScreenUp;

    private ServerVideoSession() {
    }

    public static void start(MinecraftServer server, String videoId, String url) {
        validateVideoId(videoId);
        validateMediaUrl(url);
        startValidated(server, videoId, url, "", MediaRequestOptions.EMPTY, false, 0,
                VideoPixelFormat.RGB24);
    }

    public static void startForScreen(MinecraftServer server, String requestedScreenId,
                                      String videoUrl, String audioUrl, String requestHeaders,
                                      String cookie, boolean disableScaling,
                                      int videoPipeLanes, VideoPixelFormat videoPixelFormat) {
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
        invalidateScreenLayout(level, reference.pos());
        setTarget(level, reference.pos(), screenId);
        startValidated(server, screenId, videoUrl, audioUrl,
                new MediaRequestOptions(requestHeaders, cookie), disableScaling,
                normalizeVideoPipeLanes(videoPipeLanes), videoPixelFormat);
    }

    private static void startValidated(MinecraftServer server, String videoId,
                                       String videoUrl, String audioUrl,
                                       MediaRequestOptions requestOptions,
                                       boolean disableScaling, int videoPipeLanes,
                                       VideoPixelFormat videoPixelFormat) {
        boolean waitingForClients = capablePlayerCount(server) > 0;
        current = new Session(UUID.randomUUID().toString(), videoId, videoUrl, audioUrl,
                requestOptions, disableScaling, videoPipeLanes, 0L, 0L,
                videoPixelFormat == null ? VideoPixelFormat.RGB24 : videoPixelFormat,
                !waitingForClients,
                waitingForClients, true, 1L, System.nanoTime());
        REPORTS.clear();
        JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.clear();
        READY_DURATIONS.clear();
        rejectedReports = 0L;
        broadcastStart(server);
    }

    public static void bindScreen(MinecraftServer server, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof ScreenBlock)) {
            throw new IllegalArgumentException("The target position is not a video screen");
        }
        String screenId = null;
        if (level.getBlockEntity(pos) instanceof org.arkcraft.video_synchronizer.block.ScreenBlockEntity screen) {
            screenId = screen.getScreenId();
        }
        setTarget(level, pos, screenId);
        if (current != null) {
            broadcastTarget(server);
        }
    }

    public static void bindScreen(MinecraftServer server, String requestedId) {
        String screenId = ServerScreenRegistry.normalizeId(requestedId);
        ServerScreenRegistry.ScreenReference reference = ServerScreenRegistry.require(server, screenId);
        ServerLevel level = server.getLevel(reference.dimension());
        if (level == null) {
            throw new IllegalArgumentException("Screen dimension is not loaded: " + screenId);
        }
        invalidateScreenLayout(level, reference.pos());
        bindScreen(server, level, reference.pos());
        targetScreenId = screenId;
    }

    private static void invalidateScreenLayout(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
            screen.invalidateLayout();
        }
    }

    private static void setTarget(ServerLevel level, BlockPos pos, String screenId) {
        ScreenLayout layout = ScreenLayout.SINGLE;
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
            layout = screen.getLayout();
        }
        targetDimension = level.dimension().location().toString();
        targetAnchor = pos.immutable();
        targetScreenId = screenId;
        targetLayout = layout;
        targetFacing = level.getBlockState(pos).getValue(ScreenBlock.FACING);
        targetScreenUp = level.getBlockState(pos).getValue(ScreenBlock.SCREEN_UP);
        ScreenOrientation orientation = ScreenOrientation.of(targetFacing, targetScreenUp);
        targetOrigin = pos.relative(orientation.right(), -layout.column())
                .relative(orientation.up(), -layout.row()).immutable();
    }

    public static void unbindScreen(MinecraftServer server) {
        targetDimension = null;
        targetAnchor = null;
        targetOrigin = null;
        targetScreenId = null;
        targetLayout = null;
        targetFacing = null;
        targetScreenUp = null;
        if (current != null) {
            broadcastTarget(server);
        }
    }

    public static void stop() {
        if (current == null) {
            return;
        }
        String stoppedSession = current.sessionId;
        current = null;
        REPORTS.clear();
        JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.clear();
        READY_DURATIONS.clear();
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new VideoStopMessage(stoppedSession));
    }

    public static void acceptHttpError(MinecraftServer server, ServerPlayer player,
                                       VideoHttpErrorMessage message) {
        if (current == null || !current.sessionId.equals(message.sessionId())
                || message.statusCode() < 100 || message.statusCode() > 999
                || message.statusCode() == 200 || message.statusCode() == 206) {
            return;
        }
        int statusCode = message.statusCode();
        String description = HttpStatusDescriptions.describe(statusCode);
        Main.LOGGER.warn("Stopping video session after HTTP failure reported by {}: status={} {}",
                player.getGameProfile().getName(), statusCode, description);
        stop();
        server.getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.video_synchronizer.http_error", statusCode, description)
                .withStyle(ChatFormatting.RED), false);
    }

    public static void setPlaying(MinecraftServer server, boolean playing) {
        if (current == null) {
            return;
        }
        if (current.waitingForClients) {
            current.playWhenReady = playing;
            current.revision++;
            broadcastState(server, false);
            return;
        }
        long nowNanos = System.nanoTime();
        current.positionMs = positionAt(current, nowNanos);
        current.positionNanos = nowNanos;
        current.playing = playing;
        current.revision++;
        // Playback intent is command-authoritative. Reports captured before this
        // command must not immediately overwrite the new pause/resume state.
        REPORTS.clear();
        JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.replaceAll((playerId, confirmations) -> 0);
        broadcastState(server, false);
    }

    public static void seek(MinecraftServer server, long positionMs) {
        if (current == null) {
            return;
        }
        boolean playWhenReady = current.waitingForClients
                ? current.playWhenReady : current.playing;
        boolean waitingForClients = capablePlayerCount(server) > 0;
        current.positionMs = clampToDuration(positionMs);
        current.positionNanos = System.nanoTime();
        current.playWhenReady = playWhenReady;
        current.waitingForClients = waitingForClients;
        current.playing = waitingForClients ? false : playWhenReady;
        current.revision++;
        REPORTS.clear();
        JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.clear();
        READY_DURATIONS.clear();
        broadcastState(server, true);
    }

    public static void setPlayingForScreen(MinecraftServer server, String screenId, boolean playing) {
        requireControlledSession(screenId);
        setPlaying(server, playing);
    }

    public static void seekForScreen(MinecraftServer server, String screenId, long positionMs) {
        requireControlledSession(screenId);
        seek(server, positionMs);
    }

    public static void stopForScreen(String screenId) {
        requireControlledSession(screenId);
        stop();
    }

    public static ControlState controlState(String screenId) {
        boolean active = current != null && screenId != null && !screenId.isBlank()
                && screenId.equals(targetScreenId);
        if (!active) {
            return new ControlState(false, "", "", "", "", false, 0,
                    VideoPixelFormat.RGB24,
                    0L, 0L, false, false);
        }
        return new ControlState(true, current.videoUrl, current.audioUrl,
                current.requestOptions.headers(), current.requestOptions.cookie(),
                current.disableScaling, current.videoPipeLanes, current.videoPixelFormat,
                positionAt(current, System.nanoTime()), current.durationMs,
                current.playing, current.waitingForClients);
    }

    public static boolean isPlaybackProtected(ServerLevel level, BlockPos pos) {
        if (current == null || targetDimension == null
                || !targetDimension.equals(level.dimension().location().toString())) {
            return false;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof ScreenBlockEntity) {
            return isTargetScreenPosition(pos);
        }
        return blockEntity instanceof VideoManagerBlockEntity manager
                && targetScreenId != null && targetScreenId.equals(manager.getScreenId());
    }

    private static boolean isTargetScreenPosition(BlockPos pos) {
        if (targetOrigin == null || targetLayout == null
                || targetFacing == null || targetScreenUp == null) {
            return false;
        }
        ScreenOrientation orientation = ScreenOrientation.of(targetFacing, targetScreenUp);
        int dx = pos.getX() - targetOrigin.getX();
        int dy = pos.getY() - targetOrigin.getY();
        int dz = pos.getZ() - targetOrigin.getZ();
        int depth = dot(dx, dy, dz, orientation.facing());
        int column = dot(dx, dy, dz, orientation.right());
        int row = dot(dx, dy, dz, orientation.up());
        return depth == 0 && column >= 0 && column < targetLayout.width()
                && row >= 0 && row < targetLayout.height();
    }

    private static int dot(int dx, int dy, int dz, Direction direction) {
        return dx * direction.getStepX() + dy * direction.getStepY()
                + dz * direction.getStepZ();
    }

    private static void requireControlledSession(String requestedScreenId) {
        String screenId = ServerScreenRegistry.normalizeId(requestedScreenId);
        if (current == null) {
            throw new IllegalArgumentException("There is no active video");
        }
        if (!screenId.equals(targetScreenId)) {
            throw new IllegalArgumentException("The active video belongs to another screen");
        }
    }

    public static void acceptReport(ServerPlayer player, VideoProgressMessage message) {
        if (current == null || !current.sessionId.equals(message.sessionId())) {
            sendCurrent(player);
            return;
        }
        if (!PLAYBACK_CAPABLE_PLAYERS.contains(player.getUUID())) {
            return;
        }
        if (current.waitingForClients) {
            return;
        }
        long reportedDuration = message.durationMs();
        if (reportedDuration < 0L || (reportedDuration > 0L
                && (reportedDuration < 1_000L || reportedDuration > MAX_VIDEO_DURATION_MS))) {
            rejectedReports++;
            return;
        }
        long maximumPosition = reportedDuration > 0L
                ? reportedDuration
                : (current.durationMs > 0L ? current.durationMs : MAX_VIDEO_DURATION_MS);
        if (message.positionMs() < 0L || message.positionMs() > maximumPosition) {
            rejectedReports++;
            return;
        }
        long position = message.positionMs();
        double weight = PLAYER_WEIGHTS.getOrDefault(player.getUUID(), 1.0D);
        long nowNanos = System.nanoTime();
        long expectedPosition = positionAt(current, nowNanos);
        if (Math.abs(position - expectedPosition) > MAX_AUTHORITATIVE_DEVIATION_MS) {
            rejectedReports++;
            return;
        }
        UUID playerId = player.getUUID();
        Integer confirmations = JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.get(playerId);
        if (confirmations != null) {
            boolean closeToClock = Math.abs(position - expectedPosition)
                    <= JOIN_REPORT_TOLERANCE_MS;
            boolean matchingPlaybackState = message.playing() == current.playing;
            if (!closeToClock || !matchingPlaybackState) {
                JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.put(playerId, 0);
                return;
            }
            int updatedConfirmations = confirmations + 1;
            if (updatedConfirmations < JOIN_REPORT_CONFIRMATIONS) {
                JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.put(playerId, updatedConfirmations);
                return;
            }
            JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.remove(playerId);
        }
        PlayerReport previous = REPORTS.get(playerId);
        if (previous != null) {
            long elapsedMs = Math.max(50L, elapsedMillis(previous.receivedNanos, nowNanos));
            long jump = Math.abs(position - previous.positionMs);
            // A seek is allowed, but values several minutes away from the previous report
            // are almost always a broken clock, a stale packet, or malicious input.
            long allowedJump = Math.min(MAX_REPORT_JUMP_MS, elapsedMs * 4L + 10_000L);
            if (jump > allowedJump) {
                rejectedReports++;
                return;
            }
        }
        REPORTS.put(playerId, new PlayerReport(
                position, reportedDuration, message.playing(), weight, serverTick, nowNanos));
    }

    public static void acceptReady(MinecraftServer server, ServerPlayer player,
                                   VideoReadyMessage message) {
        if (current == null || !current.sessionId.equals(message.sessionId())) {
            sendCurrent(player);
            return;
        }
        if (!current.waitingForClients) {
            return;
        }
        if (!PLAYBACK_CAPABLE_PLAYERS.contains(player.getUUID())) {
            return;
        }
        long duration = message.durationMs();
        if (duration < 0L || (duration > 0L
                && (duration < 1_000L || duration > MAX_VIDEO_DURATION_MS))) {
            rejectedReports++;
            return;
        }
        READY_DURATIONS.put(player.getUUID(), duration);
        tryBeginPlayback(server);
    }

    public static void acceptClientCapability(MinecraftServer server, ServerPlayer player,
                                              boolean playbackAvailable) {
        UUID playerId = player.getUUID();
        boolean changed;
        if (playbackAvailable) {
            changed = PLAYBACK_CAPABLE_PLAYERS.add(playerId);
        } else {
            changed = PLAYBACK_CAPABLE_PLAYERS.remove(playerId);
            REPORTS.remove(playerId);
            READY_DURATIONS.remove(playerId);
            JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.remove(playerId);
        }
        if (!changed || current == null) {
            return;
        }
        Main.LOGGER.info("Client {} video playback capability: available={}",
                player.getGameProfile().getName(), playbackAvailable);
        if (current.waitingForClients) {
            tryBeginPlayback(server);
        } else if (playbackAvailable) {
            JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.put(playerId, 0);
        }
    }

    public static void acceptLocalPause(ServerPlayer player, VideoLocalPauseMessage message) {
        MinecraftServer server = player.getServer();
        if (server == null || current == null
                || !current.sessionId.equals(message.sessionId())
                || !PLAYBACK_CAPABLE_PLAYERS.contains(player.getUUID())
                || server.isDedicatedServer() || server.isPublished()
                || server.getPlayerList().getPlayerCount() != 1
                || !server.isSingleplayerOwner(player.getGameProfile())) {
            return;
        }
        if (message.sequence() <= current.lastLocalPauseSequence
                || message.durationMs() < 0L
                || message.durationMs() > MAX_VIDEO_DURATION_MS) {
            return;
        }
        current.lastLocalPauseSequence = message.sequence();
        if (!current.playing || message.durationMs() == 0L) {
            return;
        }
        current.positionNanos += TimeUnit.MILLISECONDS.toNanos(message.durationMs());
        current.revision++;
        REPORTS.clear();
        JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.replaceAll((playerId, confirmations) -> 0);
        broadcastState(server, false);
    }

    public static void tick(MinecraftServer server) {
        serverTick++;
        if (serverTick % 5L == 0L) {
            updateStatusBossBars(server);
        }
        if (current == null) {
            return;
        }

        if (current.waitingForClients) {
            READY_DURATIONS.keySet().removeIf(
                    playerId -> server.getPlayerList().getPlayer(playerId) == null);
            if (tryBeginPlayback(server)) {
                return;
            }
            if (periodicBroadcastReady()) {
                broadcastState(server, false);
            }
            return;
        }

        Iterator<Map.Entry<UUID, PlayerReport>> iterator = REPORTS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PlayerReport> entry = iterator.next();
            boolean disconnected = server.getPlayerList().getPlayer(entry.getKey()) == null;
            boolean stale = serverTick - entry.getValue().receivedTick > REPORT_TIMEOUT_TICKS;
            if (disconnected || stale) {
                iterator.remove();
            }
        }

        if (periodicBroadcastReady()) {
            recomputeAuthoritativeState();
            if (playbackReachedEnd()) {
                Main.LOGGER.info("Video session reached the end and will stop automatically");
                stop();
                return;
            }
            broadcastState(server, false);
        }
    }

    public static void playerDisconnected(UUID playerId) {
        REPORTS.remove(playerId);
        JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.remove(playerId);
        READY_DURATIONS.remove(playerId);
        PLAYBACK_CAPABLE_PLAYERS.remove(playerId);
        ServerBossEvent bossBar = STATUS_BOSS_BARS.remove(playerId);
        if (bossBar != null) {
            bossBar.removeAllPlayers();
        }
    }

    public static void setPlayerWeight(UUID playerId, double weight) {
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
        PLAYER_WEIGHTS.put(playerId, clamp(weight, MIN_WEIGHT, MAX_WEIGHT));
        PlayerReport report = REPORTS.get(playerId);
        if (report != null) {
            REPORTS.put(playerId, new PlayerReport(report.positionMs, report.durationMs,
                    report.playing, PLAYER_WEIGHTS.get(playerId), report.receivedTick,
                    report.receivedNanos));
        }
    }

    public static void sendCurrent(ServerPlayer player) {
        if (current == null) {
            return;
        }
        UUID playerId = player.getUUID();
        REPORTS.remove(playerId);
        if (current.waitingForClients) {
            JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.remove(playerId);
        } else {
            JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.put(playerId, 0);
        }
        long nowNanos = System.nanoTime();
        long position = positionAt(current, nowNanos);
        VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new VideoStartMessage(current.sessionId, current.videoId, current.videoUrl,
                        current.audioUrl, current.requestOptions.headers(),
                        current.requestOptions.cookie(), current.disableScaling,
                        current.videoPipeLanes, current.videoPixelFormat,
                        current.durationMs, position, current.playing,
                        current.waitingForClients, current.revision, nowNanos));
        sendTarget(player);
    }

    public static void reset() {
        current = null;
        REPORTS.clear();
        JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.clear();
        READY_DURATIONS.clear();
        PLAYER_WEIGHTS.clear();
        PLAYBACK_CAPABLE_PLAYERS.clear();
        STATUS_BOSS_BARS.values().forEach(ServerBossEvent::removeAllPlayers);
        STATUS_BOSS_BARS.clear();
        serverTick = 0L;
        lastBroadcastNanos = 0L;
        rejectedReports = 0L;
        targetDimension = null;
        targetAnchor = null;
        targetOrigin = null;
        targetScreenId = null;
        targetLayout = null;
        targetFacing = null;
        targetScreenUp = null;
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
        updateStatusBossBar(player, bossBar, System.nanoTime());
        return true;
    }

    public static Component describe(MinecraftServer server, ServerPlayer viewer) {
        Component result = Component.translatable("command.video_synchronizer.status.header")
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        if (current == null) {
            return result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.none").withStyle(ChatFormatting.GRAY));
        }

        long nowNanos = System.nanoTime();
        long serverPosition = positionAt(current, nowNanos);
        Component state = playbackStateComponent(current.playing, current.waitingForClients);
        result = result.copy().append("\n").append(Component.translatable(
                "command.video_synchronizer.status.session", current.videoId,
                current.sessionId, state));
        result = result.copy().append("\n").append(Component.translatable(
                "command.video_synchronizer.status.time", formatTime(serverPosition),
                formatTime(current.durationMs)));
        result = result.copy().append("\n").append(Component.translatable(
                "command.video_synchronizer.status.target", targetDescription()));
        result = result.copy().append("\n").append(Component.translatable(
                "command.video_synchronizer.status.clients", REPORTS.size(),
                capablePlayerCount(server), rejectedReports));

        if (current.waitingForClients) {
            int required = requiredReadyCount(capablePlayerCount(server));
            Component localReadiness;
            if (viewer == null) {
                localReadiness = Component.translatable(
                        "command.video_synchronizer.status.not_applicable");
            } else if (!PLAYBACK_CAPABLE_PLAYERS.contains(viewer.getUUID())) {
                localReadiness = Component.translatable(
                        "command.video_synchronizer.status.unavailable");
            } else {
                localReadiness = Component.translatable(
                        READY_DURATIONS.containsKey(viewer.getUUID())
                                ? "command.video_synchronizer.status.ready"
                                : "command.video_synchronizer.status.not_ready");
            }
            return result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.readiness", READY_DURATIONS.size(),
                    required, localReadiness));
        }
        if (viewer == null) {
            return result;
        }
        if (!PLAYBACK_CAPABLE_PLAYERS.contains(viewer.getUUID())) {
            return result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.local_unavailable")
                    .withStyle(ChatFormatting.RED));
        }
        PlayerReport report = REPORTS.get(viewer.getUUID());
        if (report == null) {
            return result.copy().append("\n").append(Component.translatable(
                    "command.video_synchronizer.status.local_missing")
                    .withStyle(ChatFormatting.RED));
        }
        long localPosition = projectedReportPosition(report, nowNanos);
        long drift = localPosition - serverPosition;
        long reportAgeMs = elapsedMillis(report.receivedNanos, nowNanos);
        return result.copy().append("\n").append(Component.translatable(
                "command.video_synchronizer.status.local", formatTime(localPosition),
                signedMillis(drift), reportAgeMs,
                playbackStateComponent(report.playing, false)));
    }

    private static void updateStatusBossBars(MinecraftServer server) {
        if (STATUS_BOSS_BARS.isEmpty()) {
            return;
        }
        long nowNanos = System.nanoTime();
        Iterator<Map.Entry<UUID, ServerBossEvent>> iterator =
                STATUS_BOSS_BARS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ServerBossEvent> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                entry.getValue().removeAllPlayers();
                iterator.remove();
                continue;
            }
            updateStatusBossBar(player, entry.getValue(), nowNanos);
        }
    }

    private static void updateStatusBossBar(ServerPlayer player, ServerBossEvent bossBar,
                                            long nowNanos) {
        if (current == null) {
            bossBar.setName(Component.translatable("command.video_synchronizer.bossbar.idle"));
            bossBar.setColor(BossEvent.BossBarColor.WHITE);
            bossBar.setProgress(0.0F);
            return;
        }
        if (current.waitingForClients) {
            int required = requiredReadyCount(capablePlayerCount(player.getServer()));
            if (!PLAYBACK_CAPABLE_PLAYERS.contains(player.getUUID())) {
                bossBar.setName(Component.translatable(
                        "command.video_synchronizer.bossbar.unavailable"));
                bossBar.setColor(BossEvent.BossBarColor.RED);
                bossBar.setProgress(0.0F);
                return;
            }
            boolean localReady = READY_DURATIONS.containsKey(player.getUUID());
            bossBar.setName(Component.translatable(
                    "command.video_synchronizer.bossbar.waiting", READY_DURATIONS.size(),
                    required, Component.translatable(localReady
                            ? "command.video_synchronizer.status.ready"
                            : "command.video_synchronizer.status.not_ready")));
            bossBar.setColor(BossEvent.BossBarColor.PURPLE);
            bossBar.setProgress(required == 0 ? 1.0F
                    : Math.min(1.0F, READY_DURATIONS.size() / (float) required));
            return;
        }
        if (!PLAYBACK_CAPABLE_PLAYERS.contains(player.getUUID())) {
            bossBar.setName(Component.translatable(
                    "command.video_synchronizer.bossbar.unavailable"));
            bossBar.setColor(BossEvent.BossBarColor.RED);
            bossBar.setProgress(0.0F);
            return;
        }
        PlayerReport report = REPORTS.get(player.getUUID());
        long serverPosition = positionAt(current, nowNanos);
        if (report == null) {
            bossBar.setName(Component.translatable(
                    "command.video_synchronizer.bossbar.no_report",
                    formatTime(serverPosition), formatTime(current.durationMs)));
            bossBar.setColor(BossEvent.BossBarColor.RED);
            bossBar.setProgress(progress(serverPosition, current.durationMs));
            return;
        }
        long localPosition = projectedReportPosition(report, nowNanos);
        long drift = localPosition - serverPosition;
        long reportAgeMs = elapsedMillis(report.receivedNanos, nowNanos);
        bossBar.setName(Component.translatable(
                "command.video_synchronizer.bossbar.playback", formatTime(localPosition),
                formatTime(report.durationMs > 0L ? report.durationMs : current.durationMs),
                signedMillis(drift), playbackStateComponent(report.playing, false), reportAgeMs));
        bossBar.setProgress(progress(localPosition,
                report.durationMs > 0L ? report.durationMs : current.durationMs));
        long absoluteDrift = Math.abs(drift);
        if (reportAgeMs > 2_000L || absoluteDrift > 750L) {
            bossBar.setColor(BossEvent.BossBarColor.RED);
        } else if (absoluteDrift > 250L) {
            bossBar.setColor(BossEvent.BossBarColor.YELLOW);
        } else if (!report.playing) {
            bossBar.setColor(BossEvent.BossBarColor.BLUE);
        } else {
            bossBar.setColor(BossEvent.BossBarColor.GREEN);
        }
    }

    private static Component playbackStateComponent(boolean playing, boolean waiting) {
        if (waiting) {
            return Component.translatable("command.video_synchronizer.status.buffering")
                    .withStyle(ChatFormatting.YELLOW);
        }
        return Component.translatable(playing
                ? "command.video_synchronizer.status.playing"
                : "command.video_synchronizer.status.paused")
                .withStyle(playing ? ChatFormatting.GREEN : ChatFormatting.AQUA);
    }

    private static Component targetDescription() {
        if (targetOrigin == null || targetDimension == null || targetLayout == null) {
            return Component.translatable("command.video_synchronizer.status.unbound");
        }
        return Component.translatable("command.video_synchronizer.status.target_bound",
                targetScreenId == null ? "-" : targetScreenId, targetDimension,
                targetOrigin.toShortString(), targetLayout.width(), targetLayout.height());
    }

    private static long projectedReportPosition(PlayerReport report, long nowNanos) {
        long projected = report.playing
                ? report.positionMs + elapsedMillis(report.receivedNanos, nowNanos)
                : report.positionMs;
        return clampToDuration(projected);
    }

    private static float progress(long positionMs, long durationMs) {
        if (durationMs <= 0L) {
            return 0.0F;
        }
        return (float) clamp(positionMs / (double) durationMs, 0.0D, 1.0D);
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

    private static String signedMillis(long milliseconds) {
        return String.format(Locale.ROOT, "%+d", milliseconds);
    }

    private static void recomputeAuthoritativeState() {
        long nowNanos = System.nanoTime();
        if (REPORTS.isEmpty()) {
            current.positionMs = positionAt(current, nowNanos);
            current.positionNanos = nowNanos;
            if (current.durationMs > 0L && current.positionMs >= current.durationMs) {
                current.playing = false;
            }
            current.revision++;
            return;
        }

        long reportedDuration = medianDuration();
        if (reportedDuration > 0L) {
            current.durationMs = reportedDuration;
        }
        long median = medianPosition(nowNanos);
        long outlierTolerance = Math.min(MAX_REPORT_JUMP_MS,
                Math.max(MIN_OUTLIER_TOLERANCE_MS, current.durationMs / 20L));
        double weightedPosition = 0.0D;
        double totalWeight = 0.0D;
        for (PlayerReport report : REPORTS.values()) {
            long projectedPosition = current.playing
                    ? clampToDuration(report.positionMs
                    + elapsedMillis(report.receivedNanos, nowNanos))
                    : report.positionMs;
            if (Math.abs(projectedPosition - median) > outlierTolerance) {
                continue;
            }
            weightedPosition += projectedPosition * report.weight;
            totalWeight += report.weight;
        }

        if (totalWeight == 0.0D) {
            // If every report is an outlier, keep the last authoritative clock instead
            // of allowing an invalid sample to move the session.
            current.positionMs = positionAt(current, nowNanos);
            current.positionNanos = nowNanos;
            current.revision++;
            return;
        }
        long consensusPosition = clampToDuration(Math.round(weightedPosition / totalWeight));
        long clockPosition = positionAt(current, nowNanos);
        current.positionMs = current.playing
                ? Math.max(clockPosition, consensusPosition) : consensusPosition;
        current.positionNanos = nowNanos;
        if (current.durationMs > 0L && current.positionMs >= current.durationMs) {
            current.playing = false;
        }
        current.revision++;
    }

    private static boolean playbackReachedEnd() {
        return current != null && !current.waitingForClients
                && current.durationMs > 0L && current.positionMs >= current.durationMs;
    }

    private static void broadcastStart(MinecraftServer server) {
        long nowNanos = System.nanoTime();
        long position = positionAt(current, nowNanos);
        lastBroadcastNanos = nowNanos;
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                new VideoStartMessage(current.sessionId, current.videoId, current.videoUrl,
                        current.audioUrl, current.requestOptions.headers(),
                        current.requestOptions.cookie(), current.disableScaling,
                        current.videoPipeLanes, current.videoPixelFormat,
                        current.durationMs, position, current.playing,
                        current.waitingForClients, current.revision, nowNanos));
        broadcastTarget(server);
    }

    private static void broadcastTarget(MinecraftServer server) {
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), targetMessage());
    }

    private static void sendTarget(ServerPlayer player) {
        VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), targetMessage());
    }

    private static VideoScreenTargetMessage targetMessage() {
        if (targetOrigin == null || targetDimension == null || targetLayout == null
                || targetFacing == null || targetScreenUp == null || current == null) {
            return new VideoScreenTargetMessage(current == null ? "" : current.sessionId,
                    false, "", 0, 0, 0, Direction.NORTH, Direction.NORTH, 0, 0);
        }
        return new VideoScreenTargetMessage(current.sessionId, true, targetDimension,
                targetOrigin.getX(), targetOrigin.getY(), targetOrigin.getZ(),
                targetFacing, targetScreenUp, targetLayout.width(), targetLayout.height());
    }

    private static void broadcastState(MinecraftServer server, boolean hardSeek) {
        long nowNanos = System.nanoTime();
        long position = positionAt(current, nowNanos);
        lastBroadcastNanos = nowNanos;
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                new VideoStateMessage(current.sessionId, position, current.durationMs,
                        current.playing, current.waitingForClients, hardSeek, current.revision,
                        nowNanos));
    }

    private static boolean periodicBroadcastReady() {
        long nowNanos = System.nanoTime();
        return lastBroadcastNanos == 0L
                || nowNanos - lastBroadcastNanos >= BROADCAST_DEBOUNCE_NANOS;
    }

    private static boolean tryBeginPlayback(MinecraftServer server) {
        if (current == null || !current.waitingForClients) {
            return false;
        }
        int requiredPlayers = requiredReadyCount(capablePlayerCount(server));
        if (READY_DURATIONS.size() < requiredPlayers) {
            return false;
        }
        long reportedDuration = medianReadyDuration();
        if (reportedDuration > 0L) {
            current.durationMs = reportedDuration;
        }
        current.waitingForClients = false;
        current.playing = current.playWhenReady;
        current.positionNanos = System.nanoTime();
        current.revision++;
        REPORTS.clear();
        JOIN_REPORT_CONFIRMATIONS_BY_PLAYER.clear();
        broadcastState(server, false);
        return true;
    }

    private static int requiredReadyCount(int capablePlayers) {
        // Round up so small player counts never start below the requested 80%.
        return (capablePlayers * READY_THRESHOLD_NUMERATOR
                + READY_THRESHOLD_DENOMINATOR - 1) / READY_THRESHOLD_DENOMINATOR;
    }

    private static int capablePlayerCount(MinecraftServer server) {
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PLAYBACK_CAPABLE_PLAYERS.contains(player.getUUID())) {
                count++;
            }
        }
        return count;
    }

    private static long medianReadyDuration() {
        List<Long> durations = new ArrayList<>(READY_DURATIONS.size());
        for (long duration : READY_DURATIONS.values()) {
            if (duration > 0L) {
                durations.add(duration);
            }
        }
        if (durations.isEmpty()) {
            return 0L;
        }
        durations.sort(Comparator.naturalOrder());
        int middle = durations.size() / 2;
        if (durations.size() % 2 == 1) {
            return durations.get(middle);
        }
        long lower = durations.get(middle - 1);
        long upper = durations.get(middle);
        return lower + (upper - lower) / 2L;
    }

    private static long medianPosition(long nowNanos) {
        List<Long> positions = new ArrayList<>(REPORTS.size());
        for (PlayerReport report : REPORTS.values()) {
            positions.add(current.playing
                    ? clampToDuration(report.positionMs
                    + elapsedMillis(report.receivedNanos, nowNanos))
                    : report.positionMs);
        }
        positions.sort(Comparator.naturalOrder());
        int middle = positions.size() / 2;
        if (positions.size() % 2 == 1) {
            return positions.get(middle);
        }
        long lower = positions.get(middle - 1);
        long upper = positions.get(middle);
        return lower + (upper - lower) / 2L;
    }

    private static long medianDuration() {
        List<Long> durations = new ArrayList<>(REPORTS.size());
        for (PlayerReport report : REPORTS.values()) {
            if (report.durationMs > 0L) {
                durations.add(report.durationMs);
            }
        }
        if (durations.isEmpty()) {
            return 0L;
        }
        durations.sort(Comparator.naturalOrder());
        int middle = durations.size() / 2;
        if (durations.size() % 2 == 1) {
            return durations.get(middle);
        }
        long lower = durations.get(middle - 1);
        long upper = durations.get(middle);
        return lower + (upper - lower) / 2L;
    }

    private static long positionAt(Session session, long nowNanos) {
        if (!session.playing) {
            return session.positionMs;
        }
        long position = session.positionMs + elapsedMillis(session.positionNanos, nowNanos);
        return session.durationMs > 0L ? clamp(position, 0L, session.durationMs) : Math.max(0L, position);
    }

    private static long elapsedMillis(long startNanos, long nowNanos) {
        return Math.max(0L, (nowNanos - startNanos) / 1_000_000L);
    }

    private static void validateVideoId(String videoId) {
        if (videoId == null || videoId.isBlank() || videoId.length() > 256) {
            throw new IllegalArgumentException("videoId must contain 1-256 characters");
        }
    }

    private static int normalizeVideoPipeLanes(int lanes) {
        return switch (lanes) {
            case 1, 2, 4, 8, 16 -> lanes;
            default -> 0;
        };
    }

    public static void validateMediaUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("Invalid media URL");
        }
        try {
            URI uri = new URI(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || url.length() > 2048) {
                throw new IllegalArgumentException("Only absolute HTTP(S) media URLs are supported");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid media URL", exception);
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampToDuration(long value) {
        if (current == null || current.durationMs <= 0L) {
            return clamp(value, 0L, MAX_VIDEO_DURATION_MS);
        }
        return clamp(value, 0L, current.durationMs);
    }

    private static final class PlayerReport {
        private final long positionMs;
        private final long durationMs;
        private final boolean playing;
        private final double weight;
        private final long receivedTick;
        private final long receivedNanos;

        private PlayerReport(long positionMs, long durationMs, boolean playing,
                             double weight, long receivedTick, long receivedNanos) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
            this.weight = weight;
            this.receivedTick = receivedTick;
            this.receivedNanos = receivedNanos;
        }
    }

    public record ControlState(boolean active, String videoUrl, String audioUrl,
                               String requestHeaders, String cookie, boolean disableScaling,
                               int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                               long positionMs,
                               long durationMs, boolean playing,
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
        private long durationMs;
        private long positionMs;
        private boolean playing;
        private boolean waitingForClients;
        private boolean playWhenReady;
        private long revision;
        private long positionNanos;
        private long lastLocalPauseSequence = Long.MIN_VALUE;

        private Session(String sessionId, String videoId, String videoUrl, String audioUrl,
                        MediaRequestOptions requestOptions, boolean disableScaling,
                        int videoPipeLanes, long durationMs, long positionMs,
                        VideoPixelFormat videoPixelFormat,
                        boolean playing,
                        boolean waitingForClients, boolean playWhenReady, long revision,
                        long positionNanos) {
            this.sessionId = sessionId;
            this.videoId = videoId;
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.requestOptions = requestOptions;
            this.disableScaling = disableScaling;
            this.videoPipeLanes = videoPipeLanes;
            this.videoPixelFormat = videoPixelFormat;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.playing = playing;
            this.waitingForClients = waitingForClients;
            this.playWhenReady = playWhenReady;
            this.revision = revision;
            this.positionNanos = positionNanos;
        }
    }
}
