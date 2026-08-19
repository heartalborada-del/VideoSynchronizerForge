package org.arkcraft.video_synchronizer.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.client.gui.VideoManagerScreen;
import org.arkcraft.video_synchronizer.client.player.FfmpegPlaybackAdapter;
import org.arkcraft.video_synchronizer.network.VideoClientCapabilityMessage;
import org.arkcraft.video_synchronizer.network.AudioPlaybackMode;
import org.arkcraft.video_synchronizer.network.VideoLocalPauseMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;
import org.arkcraft.video_synchronizer.network.VideoPlaybackErrorMessage;
import org.arkcraft.video_synchronizer.network.VideoPlaybackNoticeMessage;
import org.arkcraft.video_synchronizer.network.VideoProgressMessage;
import org.arkcraft.video_synchronizer.network.VideoReadyMessage;
import org.arkcraft.video_synchronizer.network.VideoResyncMessage;
import org.arkcraft.video_synchronizer.network.VideoStartMessage;
import org.arkcraft.video_synchronizer.network.VideoStateMessage;
import org.arkcraft.video_synchronizer.network.VideoStopMessage;
import org.arkcraft.video_synchronizer.network.VideoTimeSyncRequestMessage;
import org.arkcraft.video_synchronizer.network.VideoTimeSyncResponseMessage;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Client-side bridge managing one decoder and clock per synchronized session. */
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
    private static final long PLAYBACK_NOTICE_DISPLAY_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final double LOAD_HYSTERESIS_BLOCKS = 4.0D;

    private static final Map<String, SessionState> SESSIONS = new ConcurrentHashMap<>();
    private static boolean playbackAvailabilityKnown;
    private static boolean playbackAvailable;
    private static boolean capabilityReported;
    private static boolean availabilityNoticeShown;
    private static boolean clientPaused;
    private static long clientPauseStartedNanos;
    private static long clientPauseSequence;
    private static long lastTimeSyncRequestNanos;
    private static long timeSyncWindowStartedNanos;
    private static long bestTimeSyncRoundTripNanos = Long.MAX_VALUE;
    private static long serverClockOffsetNanos;
    private static boolean serverClockSynchronized;
    private static boolean progressOverlayVisible;
    private static Component playbackNotice;
    private static long playbackNoticeExpiresNanos;

    private ClientVideoState() {
    }

    public static void setPlaybackAvailability(boolean available) {
        playbackAvailabilityKnown = true;
        playbackAvailable = available;
        capabilityReported = false;
        availabilityNoticeShown = false;
        if (!available) {
            SESSIONS.values().forEach(session -> {
                if (session.adapter != null) {
                    session.adapter.dispose();
                    session.adapter = null;
                }
            });
        }
        maybeReportPlaybackCapability();
    }

    public static boolean isPlaybackAvailable() {
        return playbackAvailabilityKnown && playbackAvailable;
    }

    public static void acceptStart(VideoStartMessage message) {
        SessionState session = SESSIONS.computeIfAbsent(message.sessionId(), SessionState::new);
        if (message.revision() < session.revision) {
            return;
        }
        boolean sameSession = session.initialized;
        session.videoId = message.videoId();
        session.videoUrl = message.videoUrl();
        session.audioUrl = message.audioUrl();
        session.requestHeaders = message.requestHeaders();
        session.cookie = message.cookie();
        session.disableScaling = message.disableScaling();
        session.videoPipeLanes = message.videoPipeLanes();
        session.videoPixelFormat = message.videoPixelFormat();
        session.audioRange = message.audioRange();
        session.audioPlaybackMode = message.audioPlaybackMode();
        session.durationMs = message.durationMs();
        session.live = message.live() || (sameSession && session.live);
        session.mediaClassificationKnown = message.live()
                || (sameSession && session.mediaClassificationKnown);
        session.positionMs = session.live ? 0L : clampToDuration(session, compensatedServerPosition(
                message.positionMs(), message.playing(), message.waitingForClients(),
                message.sentAtNanos(), message.receivedAtNanos()));
        session.playing = message.playing();
        session.waitingForClients = message.waitingForClients();
        if (!sameSession || session.waitingForClients) {
            session.readinessReported = false;
        }
        session.revision = message.revision();
        session.ticksSinceReport = REPORT_INTERVAL_TICKS;
        session.lastReportNanos = 0L;
        session.clearPendingCorrection();
        session.initialized = true;
        session.awaitingForcedResync = false;
        Main.LOGGER.debug("Accepted video session: session={}, videoId={}, position={} ms, "
                        + "duration={} ms, playing={}, waitingForClients={}, revision={}, "
                        + "sameSession={}", session.sessionId, session.videoId, session.positionMs,
                session.durationMs, session.playing, session.waitingForClients,
                session.revision, sameSession);
        if (session.adapter != null) {
            if (!sameSession) {
                session.adapter.open(session.videoId, session.videoUrl, session.audioUrl,
                        session.requestHeaders, session.cookie, session.disableScaling,
                        session.videoPipeLanes, session.videoPixelFormat, session.audioRange,
                        session.audioPlaybackMode, session.durationMs, session.live);
            }
            session.adapter.setLiveStream(session.live);
            session.adapter.applyServerState(session.positionMs, session.playing,
                    session.waitingForClients, true);
            session.adapter.setClientPaused(clientPaused);
        }
    }

    public static void acceptState(VideoStateMessage message) {
        SessionState session = SESSIONS.get(message.sessionId());
        if (session == null || !session.initialized) {
            VideoNetwork.CHANNEL.sendToServer(new VideoResyncMessage(message.sessionId()));
            return;
        }
        if (message.revision() < session.revision) {
            return;
        }
        if (message.durationMs() > 0L) {
            session.durationMs = message.durationMs();
        }
        if (message.live()) {
            session.live = true;
            session.mediaClassificationKnown = true;
        }
        long serverPosition = session.live ? 0L : clampToDuration(session, compensatedServerPosition(
                message.positionMs(), message.playing(), message.waitingForClients(),
                message.sentAtNanos(), message.receivedAtNanos()));
        long clientPosition = session.adapter == null
                ? session.positionMs : clampToDuration(session, session.adapter.positionMs());
        boolean driftRequiresSeek = !session.live && session.adapter != null
                && session.adapter.isPlaybackClockStarted()
                && Math.abs(serverPosition - clientPosition) >= HARD_SEEK_THRESHOLD_MS;
        boolean hardSeek = !session.live && (message.hardSeek() || session.confirmRoutineCorrection(
                serverPosition, clientPosition, driftRequiresSeek));
        if (message.hardSeek()) {
            session.clearPendingCorrection();
        }
        if (hardSeek && session.adapter != null && session.adapter.isPlaybackClockStarted()
                && clientPosition != serverPosition) {
            session.showProgressSwitch(clientPosition, serverPosition);
        }
        session.positionMs = serverPosition;
        session.playing = message.playing();
        session.waitingForClients = message.waitingForClients();
        if (session.waitingForClients && message.hardSeek()) {
            session.readinessReported = false;
        }
        session.revision = message.revision();
        if (session.adapter != null) {
            session.adapter.setLiveStream(session.live);
            session.adapter.applyServerState(session.positionMs, session.playing,
                    session.waitingForClients, hardSeek);
        }
        String screenId = ClientScreenTarget.screenId(session.sessionId);
        VideoManagerScreen.acceptPlaybackState(screenId == null ? session.videoId : screenId,
                session.positionMs, session.durationMs, session.live,
                session.playing, session.waitingForClients);
    }

    public static void acceptStop(VideoStopMessage message) {
        SessionState session = SESSIONS.remove(message.sessionId());
        if (session == null) {
            return;
        }
        String screenId = ClientScreenTarget.screenId(session.sessionId);
        if (session.adapter != null) {
            session.adapter.dispose();
        }
        ClientScreenTarget.clear(message.sessionId());
        VideoManagerScreen.acceptStop(screenId == null ? session.videoId : screenId);
        Main.LOGGER.debug("Accepted video stop: session={}", message.sessionId());
        if (SESSIONS.isEmpty()) {
            clearProgressOverlay();
        }
    }

    public static void acceptPlaybackNotice(VideoPlaybackNoticeMessage message) {
        String translationKey;
        if (message.reason() == VideoPlaybackErrorMessage.Reason.AUDIO_UNPLAYABLE) {
            translationKey = "message.video_synchronizer.audio_unplayable";
        } else if (message.reason() == VideoPlaybackErrorMessage.Reason.VIDEO_UNPLAYABLE) {
            translationKey = "message.video_synchronizer.video_unplayable";
        } else {
            return;
        }
        playbackNotice = Component.translatable(translationKey, message.videoId())
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        playbackNoticeExpiresNanos = System.nanoTime() + PLAYBACK_NOTICE_DISPLAY_NANOS;
        updateProgressOverlay();
    }

    public static void clientTick() {
        maybeReportPlaybackCapability();
        maybeShowPlaybackUnavailableNotice();
        if (SESSIONS.isEmpty()) {
            updateProgressOverlay();
            return;
        }
        if (SESSIONS.values().stream().anyMatch(session ->
                session.mediaClassificationKnown && !session.live)) {
            maybeRequestTimeSync();
        }
        for (SessionState session : SESSIONS.values()) {
            updateSessionLoading(session);
            if (!playbackAvailable || session.adapter == null) {
                continue;
            }
            session.adapter.clientTick();
            if (clientPaused) {
                continue;
            }
            long adapterDuration = session.adapter.durationMs();
            if (adapterDuration > 0L) {
                session.durationMs = adapterDuration;
            }
            if (!session.readinessReported && session.adapter.isPlaybackReady()) {
                session.live = session.live || session.adapter.isLiveStream();
                session.mediaClassificationKnown = true;
                VideoNetwork.CHANNEL.sendToServer(new VideoReadyMessage(
                        session.sessionId, session.durationMs, session.live));
                session.readinessReported = true;
            }
            if (session.waitingForClients) {
                continue;
            }
            if (!session.mediaClassificationKnown) {
                continue;
            }
            if (session.live) {
                session.positionMs = session.adapter.positionMs();
                session.playing = session.adapter.isPlaying();
                continue;
            }
            if (++session.ticksSinceReport < REPORT_INTERVAL_TICKS) {
                continue;
            }
            long nowNanos = System.nanoTime();
            if (session.lastReportNanos != 0L
                    && nowNanos - session.lastReportNanos < REPORT_DEBOUNCE_NANOS) {
                continue;
            }
            if (session.adapter.isPreparingSeek()) {
                continue;
            }
            session.positionMs = clampToDuration(session, session.adapter.positionMs());
            session.playing = session.adapter.isPlaying();
            session.ticksSinceReport = 0;
            session.lastReportNanos = nowNanos;
            VideoNetwork.CHANNEL.sendToServer(new VideoProgressMessage(session.sessionId,
                    session.positionMs, session.durationMs, session.playing));
        }
        updateProgressOverlay();
    }

    private static void updateSessionLoading(SessionState session) {
        boolean loadRequired = playbackAvailable && session.initialized
                && !session.awaitingForcedResync && shouldLoad(session);
        if (!loadRequired) {
            if (session.adapter != null) {
                session.adapter.dispose();
                session.adapter = null;
                session.readinessReported = false;
                session.clearPendingCorrection();
                Main.LOGGER.debug("Unloaded distant video session: session={}",
                        session.sessionId);
            }
            return;
        }
        if (session.adapter != null) {
            return;
        }
        session.adapter = new FfmpegPlaybackAdapter(session.sessionId);
        session.adapter.open(session.videoId, session.videoUrl, session.audioUrl,
                session.requestHeaders, session.cookie, session.disableScaling,
                session.videoPipeLanes, session.videoPixelFormat, session.audioRange,
                session.audioPlaybackMode, session.durationMs, session.live);
        session.adapter.applyServerState(session.positionMs, session.playing,
                session.waitingForClients, true);
        session.adapter.setClientPaused(clientPaused);
        session.readinessReported = false;
        session.ticksSinceReport = REPORT_INTERVAL_TICKS;
        session.lastReportNanos = 0L;
        Main.LOGGER.debug("Loaded nearby video session: session={}", session.sessionId);
    }

    private static boolean shouldLoad(SessionState session) {
        if (session.audioPlaybackMode != AudioPlaybackMode.POSITIONAL) {
            return true;
        }
        if (!ClientScreenTarget.hasReceivedTarget(session.sessionId)) {
            return false;
        }
        ClientScreenTarget.SourcePosition source =
                ClientScreenTarget.sourcePosition(session.sessionId);
        if (source == null) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || !source.dimension().equals(
                minecraft.level.dimension().location().toString())) {
            return false;
        }
        double range = session.audioRange
                + (session.adapter == null ? 0.0D : LOAD_HYSTERESIS_BLOCKS);
        return source.position().distanceToSqr(minecraft.player.getEyePosition())
                < range * range;
    }

    public static void setClientPaused(boolean paused) {
        if (clientPaused == paused) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (paused) {
            clientPauseStartedNanos = nowNanos;
        } else if (playbackAvailable && clientPauseStartedNanos != 0L) {
            long pausedDurationMs = TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, nowNanos - clientPauseStartedNanos));
            SESSIONS.values().stream()
                    .filter(session -> session.mediaClassificationKnown && !session.live)
                    .map(session -> session.sessionId)
                    .forEach(sessionId ->
                            VideoNetwork.CHANNEL.sendToServer(new VideoLocalPauseMessage(
                                    sessionId, ++clientPauseSequence, pausedDurationMs)));
            clientPauseStartedNanos = 0L;
        }
        clientPaused = paused;
        SESSIONS.values().forEach(session -> {
            if (session.adapter != null) {
                session.adapter.setClientPaused(paused);
            }
        });
    }

    public static void onFrameRendered(String sessionId, long positionMs) {
        SessionState session = SESSIONS.get(sessionId);
        if (session != null && session.adapter != null) {
            session.adapter.onFrameRendered(positionMs);
        }
    }

    public static void requestResync() {
        SESSIONS.keySet().forEach(sessionId ->
                VideoNetwork.CHANNEL.sendToServer(new VideoResyncMessage(sessionId)));
    }

    public static int forceResync() {
        if (SESSIONS.isEmpty()) {
            return 0;
        }
        SESSIONS.values().forEach(session -> {
            session.awaitingForcedResync = true;
            session.readinessReported = false;
            session.clearPendingCorrection();
            if (session.adapter != null) {
                session.adapter.dispose();
                session.adapter = null;
            }
        });
        String sessionId = SESSIONS.keySet().iterator().next();
        VideoNetwork.CHANNEL.sendToServer(new VideoResyncMessage(sessionId));
        Main.LOGGER.info("Stopped local playback and requested forced synchronization for {} session(s)",
                SESSIONS.size());
        return SESSIONS.size();
    }

    public static void reportPlaybackError(String sessionId,
                                           VideoPlaybackErrorMessage.Reason reason,
                                           int statusCode) {
        VideoNetwork.CHANNEL.sendToServer(
                new VideoPlaybackErrorMessage(sessionId, reason, statusCode));
    }

    public static void acceptTimeSync(VideoTimeSyncResponseMessage message) {
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
    }

    public static void reset() {
        SESSIONS.values().forEach(session -> {
            if (session.adapter != null) {
                session.adapter.dispose();
            }
        });
        SESSIONS.clear();
        ClientScreenTarget.clear();
        playbackNotice = null;
        playbackNoticeExpiresNanos = 0L;
        clearProgressOverlay();
        capabilityReported = false;
        availabilityNoticeShown = false;
        clientPaused = false;
        clientPauseStartedNanos = 0L;
        clientPauseSequence = 0L;
        resetTimeSync();
    }

    private static void updateProgressOverlay() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (playbackNotice != null) {
            if (System.nanoTime() < playbackNoticeExpiresNanos) {
                minecraft.gui.setOverlayMessage(playbackNotice, false);
                progressOverlayVisible = true;
                return;
            }
            playbackNotice = null;
            playbackNoticeExpiresNanos = 0L;
        }
        if (SESSIONS.isEmpty()) {
            clearProgressOverlay();
            return;
        }
        SessionState session = nearestSession();
        if (session == null) {
            clearProgressOverlay();
            return;
        }
        if (!playbackAvailable) {
            String statusKey = playbackAvailabilityKnown
                    ? "overlay.video_synchronizer.ffmpeg_unavailable"
                    : "overlay.video_synchronizer.ffmpeg_checking";
            minecraft.gui.setOverlayMessage(Component.translatable(statusKey)
                    .withStyle(playbackAvailabilityKnown ? ChatFormatting.RED : ChatFormatting.YELLOW,
                            ChatFormatting.BOLD), false);
            progressOverlayVisible = true;
            return;
        }
        if (session.awaitingForcedResync) {
            minecraft.gui.setOverlayMessage(Component.translatable(
                    "overlay.video_synchronizer.progress_syncing")
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), false);
            progressOverlayVisible = true;
            return;
        }
        if (session.adapter != null && session.adapter.isReconnecting()) {
            minecraft.gui.setOverlayMessage(Component.empty()
                    .append(Component.translatable("overlay.video_synchronizer.progress_reconnecting")
                            .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Component.literal(" -> "))
                    .append((session.live
                            ? Component.translatable("overlay.video_synchronizer.progress_live")
                            : Component.literal(formatTime(session.adapter.positionMs())))
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)), false);
            progressOverlayVisible = true;
            return;
        }
        long currentPosition = session.adapter == null
                ? session.positionMs : session.adapter.positionMs();
        long currentDuration = session.adapter == null
                ? session.durationMs : session.adapter.durationMs();
        boolean currentPlaying = session.adapter == null
                ? session.playing : session.adapter.isPlaying();
        ChatFormatting stateColor = session.waitingForClients ? ChatFormatting.LIGHT_PURPLE
                : (currentPlaying ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        minecraft.gui.setOverlayMessage(Component.empty()
                .append(Component.translatable(session.waitingForClients
                                ? "overlay.video_synchronizer.progress_buffering"
                                : (currentPlaying ? "overlay.video_synchronizer.progress_playing"
                                : "overlay.video_synchronizer.progress_paused"))
                        .withStyle(stateColor, ChatFormatting.BOLD))
                .append(Component.literal("  "))
                .append(session.live
                        ? Component.translatable("overlay.video_synchronizer.progress_live")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                        : Component.literal(formatTime(currentPosition) + " / "
                        + (currentDuration > 0L
                        ? formatTime(currentDuration) : "--:--:--"))), false);
        progressOverlayVisible = true;
    }

    private static SessionState nearestSession() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return SESSIONS.values().stream()
                    .filter(ClientVideoState::isSessionRelevant).findFirst().orElse(null);
        }
        String dimension = minecraft.level.dimension().location().toString();
        SessionState nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (SessionState session : SESSIONS.values()) {
            if (!isSessionRelevant(session)) {
                continue;
            }
            ClientScreenTarget.SourcePosition source =
                    ClientScreenTarget.sourcePosition(session.sessionId);
            if (source == null || !dimension.equals(source.dimension())) {
                continue;
            }
            double distance = source.position().distanceToSqr(minecraft.player.position());
            if (distance < nearestDistance) {
                nearest = session;
                nearestDistance = distance;
            }
        }
        return nearest != null ? nearest : SESSIONS.values().stream()
                .filter(ClientVideoState::isSessionRelevant).findFirst().orElse(null);
    }

    private static boolean isSessionRelevant(SessionState session) {
        return session.adapter != null || shouldLoad(session);
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

    private static void maybeReportPlaybackCapability() {
        if (!playbackAvailabilityKnown || capabilityReported) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }
        VideoNetwork.CHANNEL.sendToServer(new VideoClientCapabilityMessage(playbackAvailable));
        capabilityReported = true;
    }

    private static void maybeShowPlaybackUnavailableNotice() {
        if (!playbackAvailabilityKnown || playbackAvailable || availabilityNoticeShown) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.displayClientMessage(Component.translatable(
                "message.video_synchronizer.ffmpeg_unavailable"), false);
        availabilityNoticeShown = true;
    }

    private static long compensatedServerPosition(long originalPositionMs, boolean isPlaying,
                                                  boolean waitingForClients,
                                                  long serverSentNanos, long clientReceivedNanos) {
        long position = Math.max(0L, originalPositionMs);
        if (!isPlaying || waitingForClients || serverSentNanos == 0L) {
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
        return position + TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, Math.min(MAX_PACKET_AGE_NANOS, elapsedNanos)));
    }

    private static long estimatedOneWayLatencyNanos() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return 0L;
        }
        var playerInfo = minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        return playerInfo == null ? 0L
                : TimeUnit.MILLISECONDS.toNanos(Math.max(0, playerInfo.getLatency()) / 2L);
    }

    private static void resetTimeSync() {
        lastTimeSyncRequestNanos = 0L;
        timeSyncWindowStartedNanos = 0L;
        bestTimeSyncRoundTripNanos = Long.MAX_VALUE;
        serverClockOffsetNanos = 0L;
        serverClockSynchronized = false;
    }

    private static void clearProgressOverlay() {
        if (progressOverlayVisible) {
            Minecraft.getInstance().gui.setOverlayMessage(Component.empty(), false);
        }
        progressOverlayVisible = false;
    }

    private static String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", totalSeconds / 3_600L,
                totalSeconds % 3_600L / 60L, totalSeconds % 60L);
    }

    private static long clampToDuration(SessionState session, long value) {
        return session.durationMs > 0L
                ? Math.max(0L, Math.min(session.durationMs, value)) : Math.max(0L, value);
    }

    private static final class SessionState {
        private final String sessionId;
        private String videoId;
        private String videoUrl;
        private String audioUrl;
        private String requestHeaders;
        private String cookie;
        private boolean disableScaling;
        private int videoPipeLanes;
        private VideoPixelFormat videoPixelFormat = VideoPixelFormat.RGB24;
        private double audioRange = 48.0D;
        private AudioPlaybackMode audioPlaybackMode = AudioPlaybackMode.POSITIONAL;
        private long durationMs;
        private boolean live;
        private boolean mediaClassificationKnown;
        private long positionMs;
        private boolean playing;
        private boolean waitingForClients;
        private boolean readinessReported;
        private long revision;
        private int ticksSinceReport;
        private long lastReportNanos;
        private boolean initialized;
        private boolean awaitingForcedResync;
        private int pendingCorrectionDirection;
        private int pendingCorrectionCount;
        private long progressSwitchUntilNanos;
        private PlaybackAdapter adapter;

        private SessionState(String sessionId) {
            this.sessionId = sessionId;
        }

        private void clearPendingCorrection() {
            pendingCorrectionDirection = 0;
            pendingCorrectionCount = 0;
        }

        private boolean confirmRoutineCorrection(long serverPosition, long clientPosition,
                                                  boolean driftRequiresSeek) {
            if (!driftRequiresSeek) {
                clearPendingCorrection();
                return false;
            }
            int direction = Long.signum(serverPosition - clientPosition);
            if (pendingCorrectionCount == 0 || direction != pendingCorrectionDirection) {
                pendingCorrectionDirection = direction;
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

        private void showProgressSwitch(long from, long to) {
            progressSwitchUntilNanos = System.nanoTime() + PROGRESS_SWITCH_DISPLAY_NANOS;
        }
    }

    public interface PlaybackAdapter {
        void open(String videoId, String videoUrl, String audioUrl, String requestHeaders,
                  String cookie, boolean disableScaling, int videoPipeLanes,
                  VideoPixelFormat videoPixelFormat, double audioRange,
                  AudioPlaybackMode audioPlaybackMode, long durationMs, boolean live);

        void applyServerState(long positionMs, boolean playing, boolean waitingForClients,
                              boolean hardSeek);

        long positionMs();

        long durationMs();

        default boolean isLiveStream() {
            return false;
        }

        default void setLiveStream(boolean live) {
        }

        boolean isPlaying();

        default void setClientPaused(boolean paused) {
        }

        default void onFrameRendered(long positionMs) {
        }

        default boolean isPlaybackClockStarted() {
            return true;
        }

        default boolean isPlaybackReady() {
            return isPlaybackClockStarted();
        }

        default boolean isPreparingSeek() {
            return false;
        }

        default boolean isReconnecting() {
            return false;
        }

        default void clientTick() {
        }

        default void dispose() {
            close();
        }

        void close();
    }
}
