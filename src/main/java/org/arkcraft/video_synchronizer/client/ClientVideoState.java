package org.arkcraft.video_synchronizer.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.client.gui.VideoManagerScreen;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.network.VideoLocalPauseMessage;
import org.arkcraft.video_synchronizer.network.VideoHttpErrorMessage;
import org.arkcraft.video_synchronizer.network.VideoProgressMessage;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;
import org.arkcraft.video_synchronizer.network.VideoReadyMessage;
import org.arkcraft.video_synchronizer.network.VideoResyncMessage;
import org.arkcraft.video_synchronizer.network.VideoStartMessage;
import org.arkcraft.video_synchronizer.network.VideoStateMessage;
import org.arkcraft.video_synchronizer.network.VideoStopMessage;
import org.arkcraft.video_synchronizer.network.VideoTimeSyncRequestMessage;
import org.arkcraft.video_synchronizer.network.VideoTimeSyncResponseMessage;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Client-side bridge between the network and whichever video player the mod uses. */
public final class ClientVideoState {
    public static final long HARD_SEEK_THRESHOLD_MS = 750L;
    private static final int REPORT_INTERVAL_TICKS = 20;
    private static final int ROUTINE_CORRECTION_CONFIRMATIONS = 2;
    private static final long REPORT_DEBOUNCE_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long TIME_SYNC_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long TIME_SYNC_SAMPLE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long MAX_TIME_SYNC_ROUND_TRIP_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final long MAX_PACKET_AGE_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static final long PROGRESS_SWITCH_DISPLAY_NANOS = TimeUnit.SECONDS.toNanos(2L);

    private static PlaybackAdapter adapter;
    private static String sessionId;
    private static String videoId;
    private static String videoUrl;
    private static String audioUrl;
    private static String requestHeaders;
    private static String cookie;
    private static boolean disableScaling;
    private static int videoPipeLanes;
    private static VideoPixelFormat videoPixelFormat = VideoPixelFormat.RGB24;
    private static long durationMs;
    private static long positionMs;
    private static boolean playing;
    private static boolean waitingForClients;
    private static boolean readinessReported;
    private static boolean clientPaused;
    private static long clientPauseStartedNanos;
    private static long clientPauseSequence;
    private static String clientPauseSessionId;
    private static long revision;
    private static int ticksSinceReport;
    private static long lastReportNanos;
    private static long lastTimeSyncRequestNanos;
    private static long timeSyncWindowStartedNanos;
    private static long bestTimeSyncRoundTripNanos = Long.MAX_VALUE;
    private static long serverClockOffsetNanos;
    private static boolean serverClockSynchronized;
    private static int pendingCorrectionDirection;
    private static int pendingCorrectionCount;
    private static boolean progressOverlayVisible;
    private static long progressSwitchFromMs;
    private static long progressSwitchToMs;
    private static long progressSwitchUntilNanos;

    private ClientVideoState() {
    }

    public static void setPlaybackAdapter(PlaybackAdapter playbackAdapter) {
        adapter = playbackAdapter;
        Main.LOGGER.debug("Client playback adapter set to {} (activeSession={})",
                adapter == null ? "none" : adapter.getClass().getSimpleName(), sessionId != null);
        if (adapter != null && sessionId != null) {
            adapter.open(videoId, videoUrl, audioUrl, requestHeaders, cookie, disableScaling,
                    videoPipeLanes, videoPixelFormat, durationMs);
            adapter.applyServerState(positionMs, playing, waitingForClients, true);
            adapter.setClientPaused(clientPaused);
        }
    }

    public static void acceptStart(VideoStartMessage message) {
        if (sessionId != null && sessionId.equals(message.sessionId()) && message.revision() < revision) {
            return;
        }
        boolean sameSession = message.sessionId().equals(sessionId);
        if (!sameSession) {
            progressSwitchUntilNanos = 0L;
        }
        ClientScreenTarget.resetForSession(message.sessionId());
        sessionId = message.sessionId();
        videoId = message.videoId();
        videoUrl = message.videoUrl();
        audioUrl = message.audioUrl();
        requestHeaders = message.requestHeaders();
        cookie = message.cookie();
        disableScaling = message.disableScaling();
        videoPipeLanes = message.videoPipeLanes();
        videoPixelFormat = message.videoPixelFormat();
        durationMs = message.durationMs();
        if (!sameSession) {
            resetTimeSync();
        }
        positionMs = compensatedServerPosition(message.positionMs(), message.playing(),
                message.waitingForClients(), message.sentAtNanos(), message.receivedAtNanos());
        playing = message.playing();
        waitingForClients = message.waitingForClients();
        if (!sameSession || waitingForClients) {
            readinessReported = false;
        }
        revision = message.revision();
        ticksSinceReport = REPORT_INTERVAL_TICKS;
        clearPendingCorrection();
        if (!sameSession) {
            lastReportNanos = 0L;
        }
        maybeRequestTimeSync();
        if (!sameSession && clientPaused) {
            clientPauseStartedNanos = System.nanoTime();
            clientPauseSessionId = sessionId;
        }
        Main.LOGGER.debug("Accepted video start: session={}, videoId={}, position={} ms, "
                        + "duration={} ms, playing={}, waitingForClients={}, revision={}, "
                        + "sameSession={}",
                sessionId, videoId, positionMs, durationMs, playing, waitingForClients,
                revision, sameSession);
        if (adapter != null) {
            if (!sameSession) {
                adapter.open(videoId, videoUrl, audioUrl, requestHeaders, cookie, disableScaling,
                        videoPipeLanes, videoPixelFormat, durationMs);
            }
            adapter.applyServerState(positionMs, playing, waitingForClients, true);
            adapter.setClientPaused(clientPaused);
        }
    }

    public static void acceptState(VideoStateMessage message) {
        if (sessionId == null || !sessionId.equals(message.sessionId())) {
            VideoNetwork.CHANNEL.sendToServer(new VideoResyncMessage(message.sessionId()));
            return;
        }
        if (message.revision() < revision) {
            return;
        }
        if (message.durationMs() > 0L) {
            durationMs = message.durationMs();
        }
        long serverPosition = compensatedServerPosition(message.positionMs(), message.playing(),
                message.waitingForClients(), message.sentAtNanos(), message.receivedAtNanos());
        long clientPosition = adapter == null ? positionMs : clampToDuration(adapter.positionMs());
        // Routine snapshots can reflect transient network delay. Require the same
        // correction direction twice; explicit hard seeks bypass this debounce.
        boolean driftRequiresSeek = adapter != null && adapter.isPlaybackClockStarted()
                && Math.abs(serverPosition - clientPosition) >= HARD_SEEK_THRESHOLD_MS;
        boolean hardSeek = message.hardSeek() || confirmRoutineCorrection(
                serverPosition, clientPosition, driftRequiresSeek);
        if (message.hardSeek()) {
            clearPendingCorrection();
        }
        if (hardSeek && adapter != null && adapter.isPlaybackClockStarted()
                && clientPosition != serverPosition) {
            showProgressSwitch(clientPosition, serverPosition);
        }
        positionMs = serverPosition;
        playing = message.playing();
        waitingForClients = message.waitingForClients();
        if (waitingForClients && message.hardSeek()) {
            readinessReported = false;
        }
        revision = message.revision();
        if (adapter != null) {
            adapter.applyServerState(positionMs, playing, waitingForClients, hardSeek);
        }
        VideoManagerScreen.acceptPlaybackState(
                positionMs, durationMs, playing, waitingForClients);
    }

    public static void acceptStop(VideoStopMessage message) {
        if (sessionId == null || !sessionId.equals(message.sessionId())) {
            return;
        }
        if (adapter != null) {
            adapter.close();
        }
        Main.LOGGER.debug("Accepted video stop: session={}", message.sessionId());
        sessionId = null;
        videoId = null;
        videoUrl = null;
        audioUrl = null;
        requestHeaders = null;
        cookie = null;
        disableScaling = false;
        videoPipeLanes = 0;
        videoPixelFormat = VideoPixelFormat.RGB24;
        durationMs = 0L;
        positionMs = 0L;
        playing = false;
        waitingForClients = false;
        readinessReported = false;
        clientPauseSessionId = null;
        revision = 0L;
        ticksSinceReport = 0;
        lastReportNanos = 0L;
        resetTimeSync();
        clearPendingCorrection();
        VideoManagerScreen.acceptStop();
        clearProgressOverlay();
        ClientScreenTarget.clear();
    }

    public static void clientTick() {
        if (sessionId == null) {
            return;
        }
        maybeRequestTimeSync();
        updateProgressOverlay();
        if (adapter != null) {
            adapter.clientTick();
        }
        if (clientPaused) {
            return;
        }
        if (waitingForClients) {
            if (adapter != null) {
                long adapterDuration = adapter.durationMs();
                if (adapterDuration > 0L) {
                    durationMs = adapterDuration;
                }
                if (!readinessReported && adapter.isPlaybackReady()) {
                    VideoNetwork.CHANNEL.sendToServer(
                            new VideoReadyMessage(sessionId, durationMs));
                    readinessReported = true;
                    Main.LOGGER.debug("Reported completed video preload: session={}, duration={} ms",
                            sessionId, durationMs);
                }
            }
            return;
        }
        if (++ticksSinceReport < REPORT_INTERVAL_TICKS) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (lastReportNanos != 0L
                && nowNanos - lastReportNanos < REPORT_DEBOUNCE_NANOS) {
            return;
        }
        long currentPosition = positionMs;
        boolean currentPlaying = playing;
        long currentDuration = durationMs;
        if (adapter != null) {
            if (adapter.isPreparingSeek()) {
                return;
            }
            currentDuration = adapter.durationMs();
            if (currentDuration > 0L) {
                durationMs = currentDuration;
            }
            currentPosition = clampToDuration(adapter.positionMs());
            currentPlaying = adapter.isPlaying();
        }
        positionMs = currentPosition;
        playing = currentPlaying;
        ticksSinceReport = 0;
        lastReportNanos = nowNanos;
        VideoNetwork.CHANNEL.sendToServer(new VideoProgressMessage(
                sessionId, currentPosition, currentDuration, currentPlaying));
    }

    public static void setClientPaused(boolean paused) {
        if (clientPaused == paused) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (paused) {
            clientPauseStartedNanos = nowNanos;
            clientPauseSessionId = sessionId;
        } else if (sessionId != null && sessionId.equals(clientPauseSessionId)) {
            long pausedDurationMs = TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, nowNanos - clientPauseStartedNanos));
            VideoNetwork.CHANNEL.sendToServer(new VideoLocalPauseMessage(
                    sessionId, ++clientPauseSequence, pausedDurationMs));
        }
        clientPaused = paused;
        clientPauseStartedNanos = paused ? nowNanos : 0L;
        clientPauseSessionId = paused ? sessionId : null;
        if (adapter != null) {
            adapter.setClientPaused(paused);
        }
    }

    public static void onFrameRendered(long positionMs) {
        if (adapter != null) {
            adapter.onFrameRendered(positionMs);
        }
    }

    public static void requestResync() {
        if (sessionId != null) {
            VideoNetwork.CHANNEL.sendToServer(new VideoResyncMessage(sessionId));
        }
    }

    public static void reportHttpError(int statusCode) {
        if (sessionId != null) {
            VideoNetwork.CHANNEL.sendToServer(
                    new VideoHttpErrorMessage(sessionId, statusCode));
        }
    }

    public static void acceptTimeSync(VideoTimeSyncResponseMessage message) {
        if (sessionId == null) {
            return;
        }
        long clientElapsedNanos = message.clientReceiveNanos() - message.clientSendNanos();
        long serverProcessingNanos = message.serverSendNanos() - message.serverReceiveNanos();
        long roundTripNanos = clientElapsedNanos - serverProcessingNanos;
        if (clientElapsedNanos < 0L || serverProcessingNanos < 0L || roundTripNanos < 0L
                || roundTripNanos > MAX_TIME_SYNC_ROUND_TRIP_NANOS) {
            return;
        }
        long sampleNanos = message.clientReceiveNanos();
        if (timeSyncWindowStartedNanos == 0L
                || sampleNanos - timeSyncWindowStartedNanos >= TIME_SYNC_SAMPLE_WINDOW_NANOS) {
            timeSyncWindowStartedNanos = sampleNanos;
            bestTimeSyncRoundTripNanos = Long.MAX_VALUE;
        }
        if (roundTripNanos >= bestTimeSyncRoundTripNanos) {
            return;
        }
        long clientToServerOffset = message.serverReceiveNanos() - message.clientSendNanos();
        long serverToClientOffset = message.serverSendNanos() - message.clientReceiveNanos();
        serverClockOffsetNanos = clientToServerOffset / 2L + serverToClientOffset / 2L;
        bestTimeSyncRoundTripNanos = roundTripNanos;
        serverClockSynchronized = true;
        Main.LOGGER.debug("Updated video clock synchronization: roundTrip={} ms, offset={} ms",
                TimeUnit.NANOSECONDS.toMillis(roundTripNanos),
                TimeUnit.NANOSECONDS.toMillis(serverClockOffsetNanos));
    }

    public static void reset() {
        Main.LOGGER.debug("Resetting client video state (session={})", sessionId);
        if (adapter != null) {
            adapter.close();
        }
        sessionId = null;
        videoId = null;
        videoUrl = null;
        audioUrl = null;
        requestHeaders = null;
        cookie = null;
        disableScaling = false;
        videoPipeLanes = 0;
        videoPixelFormat = VideoPixelFormat.RGB24;
        durationMs = 0L;
        positionMs = 0L;
        playing = false;
        waitingForClients = false;
        readinessReported = false;
        clientPaused = false;
        clientPauseStartedNanos = 0L;
        clientPauseSequence = 0L;
        clientPauseSessionId = null;
        revision = 0L;
        ticksSinceReport = 0;
        lastReportNanos = 0L;
        resetTimeSync();
        clearPendingCorrection();
        clearProgressOverlay();
        ClientScreenTarget.clear();
    }

    private static void updateProgressOverlay() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (adapter != null && adapter.isReconnecting()) {
            Component overlay = Component.empty()
                    .append(Component.translatable("overlay.video_synchronizer.progress_reconnecting")
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(" -> ")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD))
                    .append(Component.literal(formatTime(clampToDuration(adapter.positionMs())))
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            minecraft.gui.setOverlayMessage(overlay, false);
            progressOverlayVisible = true;
            return;
        }
        if (System.nanoTime() < progressSwitchUntilNanos) {
            Component overlay = Component.empty()
                    .append(Component.translatable("overlay.video_synchronizer.progress_syncing")
                            .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
                    .append(Component.literal("  "))
                    .append(Component.literal(formatTime(progressSwitchFromMs))
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(" -> ")
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD))
                    .append(Component.literal(formatTime(progressSwitchToMs))
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            minecraft.gui.setOverlayMessage(overlay, false);
            progressOverlayVisible = true;
            return;
        }
        progressSwitchUntilNanos = 0L;
        long currentPosition = positionMs;
        long currentDuration = durationMs;
        boolean currentPlaying = playing;
        if (adapter != null) {
            currentPosition = clampToDuration(adapter.positionMs());
            long adapterDuration = adapter.durationMs();
            if (adapterDuration > 0L) {
                currentDuration = adapterDuration;
            }
            currentPlaying = adapter.isPlaying();
        }
        ChatFormatting stateColor = waitingForClients ? ChatFormatting.LIGHT_PURPLE
                : (currentPlaying ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        Component overlay = Component.empty()
                .append(Component.translatable(waitingForClients
                                ? "overlay.video_synchronizer.progress_buffering"
                                : (currentPlaying
                                ? "overlay.video_synchronizer.progress_playing"
                                : "overlay.video_synchronizer.progress_paused"))
                        .withStyle(stateColor, ChatFormatting.BOLD))
                .append(Component.literal("  "))
                .append(Component.literal(formatTime(currentPosition))
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .append(Component.literal(" / ")
                        .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.BOLD))
                .append(Component.literal(currentDuration > 0L
                                ? formatTime(currentDuration) : "--:--:--")
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        minecraft.gui.setOverlayMessage(overlay, false);
        progressOverlayVisible = true;
    }

    private static void showProgressSwitch(long fromPositionMs, long toPositionMs) {
        progressSwitchFromMs = fromPositionMs;
        progressSwitchToMs = toPositionMs;
        progressSwitchUntilNanos = System.nanoTime() + PROGRESS_SWITCH_DISPLAY_NANOS;
    }

    private static void clearProgressOverlay() {
        progressSwitchUntilNanos = 0L;
        if (!progressOverlayVisible) {
            return;
        }
        Minecraft.getInstance().gui.setOverlayMessage(Component.empty(), false);
        progressOverlayVisible = false;
    }

    private static boolean confirmRoutineCorrection(long serverPosition, long clientPosition,
                                                     boolean driftRequiresSeek) {
        if (!driftRequiresSeek) {
            clearPendingCorrection();
            return false;
        }
        long direction = Long.signum(serverPosition - clientPosition);
        if (pendingCorrectionCount == 0 || direction != pendingCorrectionDirection) {
            pendingCorrectionDirection = (int) direction;
            pendingCorrectionCount = 1;
            return false;
        }
        pendingCorrectionCount++;
        if (pendingCorrectionCount < ROUTINE_CORRECTION_CONFIRMATIONS) {
            return false;
        }
        clearPendingCorrection();
        return true;
    }

    private static long compensatedServerPosition(long originalPositionMs, boolean isPlaying,
                                                  boolean isWaitingForClients,
                                                  long serverSentNanos,
                                                  long clientReceivedNanos) {
        long position = clampToDuration(originalPositionMs);
        if (!isPlaying || isWaitingForClients || serverSentNanos == 0L) {
            return position;
        }
        long nowNanos = System.nanoTime();
        long elapsedNanos;
        if (serverClockSynchronized) {
            elapsedNanos = nowNanos + serverClockOffsetNanos - serverSentNanos;
        } else {
            long handlerDelayNanos = clientReceivedNanos == 0L
                    ? 0L : Math.max(0L, nowNanos - clientReceivedNanos);
            elapsedNanos = estimatedOneWayLatencyNanos() + handlerDelayNanos;
        }
        long boundedElapsedNanos = clamp(elapsedNanos, 0L, MAX_PACKET_AGE_NANOS);
        return clampToDuration(position + TimeUnit.NANOSECONDS.toMillis(boundedElapsedNanos));
    }

    private static long estimatedOneWayLatencyNanos() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return 0L;
        }
        var playerInfo = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        if (playerInfo == null) {
            return 0L;
        }
        return TimeUnit.MILLISECONDS.toNanos(Math.max(0, playerInfo.getLatency()) / 2L);
    }

    private static void maybeRequestTimeSync() {
        long nowNanos = System.nanoTime();
        if (lastTimeSyncRequestNanos != 0L
                && nowNanos - lastTimeSyncRequestNanos < TIME_SYNC_INTERVAL_NANOS) {
            return;
        }
        lastTimeSyncRequestNanos = nowNanos;
        VideoNetwork.CHANNEL.sendToServer(new VideoTimeSyncRequestMessage(nowNanos));
    }

    private static void resetTimeSync() {
        lastTimeSyncRequestNanos = 0L;
        timeSyncWindowStartedNanos = 0L;
        bestTimeSyncRoundTripNanos = Long.MAX_VALUE;
        serverClockOffsetNanos = 0L;
        serverClockSynchronized = false;
    }

    private static void clearPendingCorrection() {
        pendingCorrectionDirection = 0;
        pendingCorrectionCount = 0;
    }

    private static String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampToDuration(long value) {
        return durationMs > 0L ? clamp(value, 0L, durationMs) : Math.max(0L, value);
    }

    /**
     * Implement this interface in the actual VLC/JavaFX/etc. player integration.
     * All callbacks run on the Minecraft client thread.
     */
    public interface PlaybackAdapter {
        void open(String videoId, String videoUrl, String audioUrl, String requestHeaders,
                  String cookie, boolean disableScaling, int videoPipeLanes,
                  VideoPixelFormat videoPixelFormat, long durationMs);

        void applyServerState(long positionMs, boolean playing, boolean waitingForClients,
                              boolean hardSeek);

        long positionMs();

        long durationMs();

        boolean isPlaying();

        /** Local Minecraft pause state; this must not be reported as a server pause intent. */
        default void setClientPaused(boolean paused) {
        }

        /** Called on the render thread after a decoded frame has been uploaded. */
        default void onFrameRendered(long positionMs) {
        }

        default boolean isPlaybackClockStarted() {
            return true;
        }

        default boolean isPlaybackReady() {
            return isPlaybackClockStarted();
        }

        /** True while an old stream remains visible during a prepared hard seek. */
        default boolean isPreparingSeek() {
            return false;
        }

        default boolean isReconnecting() {
            return false;
        }

        /** Performs non-blocking playback health checks on the client thread. */
        default void clientTick() {
        }

        void close();
    }
}
