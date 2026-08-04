package org.arkcraft.video_synchronizer.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.network.VideoProgressMessage;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative video clock. All methods are called from the server thread.
 */
public final class ServerVideoSession {
    public static final int BROADCAST_INTERVAL_TICKS = 10;
    public static final int REPORT_TIMEOUT_TICKS = 100;
    private static final double MIN_WEIGHT = 0.01D;
    private static final double MAX_WEIGHT = 100.0D;
    /** A client cannot move more than this in one report, even if it claims to be playing. */
    private static final long MAX_REPORT_JUMP_MS = 120_000L;
    private static final long MAX_AUTHORITATIVE_DEVIATION_MS = 15_000L;
    private static final long MIN_OUTLIER_TOLERANCE_MS = 10_000L;
    private static final long MAX_VIDEO_DURATION_MS = 24L * 60L * 60L * 1000L;

    private static final Map<UUID, PlayerReport> REPORTS = new HashMap<>();
    private static final Map<UUID, Double> PLAYER_WEIGHTS = new HashMap<>();
    private static Session current;
    private static long serverTick;
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
        startValidated(server, videoId, url);
    }

    public static void startForScreen(MinecraftServer server, String requestedScreenId, String url) {
        String screenId = ServerScreenRegistry.normalizeId(requestedScreenId);
        validateVideoId(screenId);
        validateMediaUrl(url);
        ServerScreenRegistry.ScreenReference reference = ServerScreenRegistry.require(server, screenId);
        ServerLevel level = server.getLevel(reference.dimension());
        if (level == null) {
            throw new IllegalArgumentException("Screen dimension is not loaded: " + screenId);
        }
        invalidateScreenLayout(level, reference.pos());
        setTarget(level, reference.pos(), screenId);
        startValidated(server, screenId, url);
    }

    private static void startValidated(MinecraftServer server, String videoId, String url) {
        current = new Session(UUID.randomUUID().toString(), videoId, url, 0L,
                0L, true, 1L, System.nanoTime());
        REPORTS.clear();
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
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new VideoStopMessage(stoppedSession));
    }

    public static void setPlaying(MinecraftServer server, boolean playing) {
        if (current == null) {
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
        broadcastState(server, false);
    }

    public static void seek(MinecraftServer server, long positionMs) {
        if (current == null) {
            return;
        }
        current.positionMs = clampToDuration(positionMs);
        current.positionNanos = System.nanoTime();
        current.revision++;
        REPORTS.clear();
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
            return new ControlState(false, "", 0L, 0L, false);
        }
        return new ControlState(true, current.url, positionAt(current, System.nanoTime()),
                current.durationMs, current.playing);
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
        PlayerReport previous = REPORTS.get(player.getUUID());
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
        REPORTS.put(player.getUUID(), new PlayerReport(
                position, reportedDuration, weight, serverTick, nowNanos));
    }

    public static void tick(MinecraftServer server) {
        serverTick++;
        if (current == null) {
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

        if (serverTick % BROADCAST_INTERVAL_TICKS == 0) {
            recomputeAuthoritativeState();
            broadcastState(server, false);
        }
    }

    public static void playerDisconnected(UUID playerId) {
        REPORTS.remove(playerId);
    }

    public static void setPlayerWeight(UUID playerId, double weight) {
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
        PLAYER_WEIGHTS.put(playerId, clamp(weight, MIN_WEIGHT, MAX_WEIGHT));
        PlayerReport report = REPORTS.get(playerId);
        if (report != null) {
            REPORTS.put(playerId, new PlayerReport(report.positionMs, report.durationMs,
                    PLAYER_WEIGHTS.get(playerId), report.receivedTick, report.receivedNanos));
        }
    }

    public static void sendCurrent(ServerPlayer player) {
        if (current == null) {
            return;
        }
        long position = positionAt(current, System.nanoTime());
        VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new VideoStartMessage(current.sessionId, current.videoId, current.url, current.durationMs,
                        position, current.playing, current.revision));
        sendTarget(player);
    }

    public static void reset() {
        current = null;
        REPORTS.clear();
        PLAYER_WEIGHTS.clear();
        serverTick = 0L;
        rejectedReports = 0L;
        targetDimension = null;
        targetAnchor = null;
        targetOrigin = null;
        targetScreenId = null;
        targetLayout = null;
        targetFacing = null;
        targetScreenUp = null;
    }

    public static String describe() {
        if (current == null) {
            return "no active video";
        }
        String binding = targetAnchor == null ? "unbound" : "screen " + targetScreenId + " at "
                + targetDimension + " " + targetAnchor.toShortString();
        return current.videoId + " at " + positionAt(current, System.nanoTime()) + "ms, "
                + (current.playing ? "playing" : "paused") + ", " + binding + ", "
                + REPORTS.size() + " reports, " + rejectedReports + " rejected";
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
        current.positionMs = clampToDuration(Math.round(weightedPosition / totalWeight));
        current.positionNanos = nowNanos;
        if (current.durationMs > 0L && current.positionMs >= current.durationMs) {
            current.playing = false;
        }
        current.revision++;
    }

    private static void broadcastStart(MinecraftServer server) {
        long position = positionAt(current, System.nanoTime());
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                new VideoStartMessage(current.sessionId, current.videoId, current.url, current.durationMs,
                        position, current.playing, current.revision));
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
        long position = positionAt(current, System.nanoTime());
        VideoNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(),
                new VideoStateMessage(current.sessionId, position, current.durationMs,
                        current.playing, hardSeek, current.revision));
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
        private final double weight;
        private final long receivedTick;
        private final long receivedNanos;

        private PlayerReport(long positionMs, long durationMs,
                             double weight, long receivedTick, long receivedNanos) {
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.weight = weight;
            this.receivedTick = receivedTick;
            this.receivedNanos = receivedNanos;
        }
    }

    public record ControlState(boolean active, String url, long positionMs,
                               long durationMs, boolean playing) {
    }

    private static final class Session {
        private final String sessionId;
        private final String videoId;
        private final String url;
        private long durationMs;
        private long positionMs;
        private boolean playing;
        private long revision;
        private long positionNanos;

        private Session(String sessionId, String videoId, String url, long durationMs,
                        long positionMs, boolean playing, long revision, long positionNanos) {
            this.sessionId = sessionId;
            this.videoId = videoId;
            this.url = url;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.playing = playing;
            this.revision = revision;
            this.positionNanos = positionNanos;
        }
    }
}
