package org.arkcraft.video_synchronizer.client.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.client.ClientScreenTarget;
import org.arkcraft.video_synchronizer.client.ClientVideoState;
import org.arkcraft.video_synchronizer.client.render.ScreenTexture;
import org.arkcraft.video_synchronizer.network.MediaRequestOptions;
import org.arkcraft.video_synchronizer.network.AudioPlaybackMode;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;
import org.arkcraft.video_synchronizer.network.VideoPlaybackErrorMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * FFmpeg CLI-backed synchronized media player. Video tries automatic hardware
 * acceleration first; audio is streamed as PCM to the client's default output device.
 */
public final class FfmpegPlaybackAdapter implements ClientVideoState.PlaybackAdapter {
    private static final int MAX_SOURCE_DIMENSION = 4096;
    private static final long MAX_SOURCE_PIXELS = 4096L * 2160L;
    private static final int MAX_OUTPUT_WIDTH = positiveIntegerProperty(
            "video_synchronizer.maxVideoWidth", 1920);
    private static final int MAX_OUTPUT_HEIGHT = positiveIntegerProperty(
            "video_synchronizer.maxVideoHeight", 1080);
    private static final boolean SCALE_VIDEO = Boolean.parseBoolean(
            System.getProperty("video_synchronizer.scaleVideo", "true"));
    private static final double MAX_OUTPUT_FPS = 60.0D;
    private static final int MAX_VIDEO_PIPE_LANES = 16;
    private static final int VIDEO_PIPE_LANES = Math.min(MAX_VIDEO_PIPE_LANES, positiveIntegerProperty(
            "video_synchronizer.videoPipeLanes", 2));
    private static final int VIDEO_PIPE_MIN_FRAME_BYTES = positiveIntegerProperty(
            "video_synchronizer.videoPipeMinFrameBytes", 4 * 1024 * 1024);
    private static final int VIDEO_PIPE_SOCKET_BUFFER_BYTES = positiveIntegerProperty(
            "video_synchronizer.videoPipeSocketBufferBytes", 4 * 1024 * 1024);
    private static final int VIDEO_PIPE_ACCEPT_TIMEOUT_MS = positiveIntegerProperty(
            "video_synchronizer.videoPipeAcceptTimeoutMs", 10_000);
    private static final long DEBUG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final int PROBE_TIMEOUT_SECONDS = positiveIntegerProperty(
            "video_synchronizer.probeTimeoutSeconds", 60);
    private static final int FAST_PROBE_TIMEOUT_SECONDS = Math.min(PROBE_TIMEOUT_SECONDS,
            positiveIntegerProperty("video_synchronizer.fastProbeTimeoutSeconds", 10));
    private static final int FAST_PROBE_SIZE_BYTES = positiveIntegerProperty(
            "video_synchronizer.fastProbeSizeBytes", 5 * 1024 * 1024);
    private static final int FAST_ANALYZE_DURATION_US = positiveIntegerProperty(
            "video_synchronizer.fastAnalyzeDurationUs", 5_000_000);
    private static final int INPUT_THREAD_QUEUE_PACKETS = positiveIntegerProperty(
            "video_synchronizer.inputThreadQueuePackets", 512);
    private static final String NETWORK_TIMEOUT_US = "15000000";
    private static final String HTTP_SHORT_SEEK_SIZE = "1048576";
    private static final int MAX_FFMPEG_ERROR_LENGTH = 8_192;
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 2;
    private static final double DEFAULT_AUDIO_MAX_DISTANCE = positiveDoubleProperty(
            "video_synchronizer.audioMaxDistance", 48.0D);
    private static final int AUDIO_FRAME_SIZE = AUDIO_CHANNELS * Short.BYTES;
    private static final int AUDIO_CHUNK_FRAMES = AUDIO_SAMPLE_RATE / 50;
    private static final int AUDIO_BUFFER_FRAMES = AUDIO_SAMPLE_RATE / 10;
    private static final int AUDIO_READ_CANCELLED = -1;
    private static final int AUDIO_READ_STALLED = -2;
    private static final int HTTP_FORBIDDEN_STATUS = 403;
    private static final int MAX_HTTP_FORBIDDEN_RETRY_ATTEMPTS = 5;
    private static final int AUDIO_MAX_RESTART_ATTEMPTS = 5;
    private static final long STREAM_RECONNECT_INITIAL_DELAY_MS = 250L;
    private static final long STREAM_RECONNECT_MAX_DELAY_MS = 4_000L;
    private static final long AUDIO_END_TOLERANCE_MS = 1_000L;
    private static final long AUDIO_RECONNECT_NOTICE_MS = 500L;
    private static final long AUDIO_START_TIMEOUT_MS = 20_000L;
    private static final long AUDIO_STALL_TIMEOUT_MS = 5_000L;
    private static final long AUDIO_RECOVERY_STABLE_FRAMES = AUDIO_SAMPLE_RATE * 5L;
    private static final int VIDEO_MAX_RECONNECT_ATTEMPTS = 5;
    private static final long VIDEO_RECONNECT_NOTICE_MS = 2_000L;
    private static final long VIDEO_START_TIMEOUT_MS = 60_000L;
    private static final long VIDEO_STALL_TIMEOUT_MS = 5_000L;
    private static final long VIDEO_RECOVERY_STABLE_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final long VIDEO_CATCH_UP_THRESHOLD_MS = 2_000L;
    private static final long VIDEO_CATCH_UP_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final long SEEK_PREPARE_TIMEOUT_MS = 5_000L;
    private static final long SEEK_REPLACE_THRESHOLD_MS = 2_000L;
    private static final long SOFT_FORWARD_SEEK_MAX_MS = 10_000L;
    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile(
            "(?i)(?:\\bHTTP\\s+(?:error\\s+)?|\\bserver\\s+returned\\s+)"
                    + "([1-9][0-9]{2})\\b");
    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile("(?i)https?://\\S+");

    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "VideoSynchronizer-FFmpeg");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService seekTimeoutExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "VideoSynchronizer-Seek-Timeout");
                thread.setDaemon(true);
                return thread;
            });
    private final ScheduledExecutorService decoderWatchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "VideoSynchronizer-Decoder-Watchdog");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong requestedSeekMs = new AtomicLong(-1L);
    private final AtomicLong videoDiscardUntilMs = new AtomicLong(-1L);
    private final AtomicLong seekPreparationGeneration = new AtomicLong();
    private final AtomicLong reportedPlaybackErrorGeneration = new AtomicLong(-1L);
    private final String sessionId;
    private final VideoFrameBuffer frameBuffer = new VideoFrameBuffer();
    private final ScreenTexture screenTexture;
    private final AudioPlayback audioPlayback = new AudioPlayback();

    private volatile Process process;
    private volatile boolean decoderProcess;
    private volatile Process pendingVideoProcess;
    private volatile VideoMetadata activeMetadata;
    private volatile String activeMediaUrl;
    private volatile String activeAudioUrl;
    private volatile MediaRequestOptions activeRequestOptions = MediaRequestOptions.EMPTY;
    private volatile boolean disableScaling;
    private volatile int activeVideoPipeLanes = VIDEO_PIPE_LANES;
    private volatile VideoPixelFormat activeVideoPixelFormat = VideoPixelFormat.RGB24;
    private volatile double audioMaxDistance = DEFAULT_AUDIO_MAX_DISTANCE;
    private volatile AudioPlaybackMode audioPlaybackMode = AudioPlaybackMode.POSITIONAL;
    private volatile boolean forceVideoPipeLanes;
    private volatile DecodeMode preferredDecodeMode;
    private PreparedVideoDecoder preparedVideoDecoder;
    private long pendingSeekPreparation = -1L;
    private long activatedSeekPreparation = -1L;
    private long pendingSeekPositionMs = -1L;
    private long pendingSeekRequestedNanos;
    private boolean pendingSeekPlaying;
    private boolean pendingSeekNeedsAudio;
    private boolean pendingAudioFailed;
    private volatile long durationMs;
    private volatile boolean liveStream;
    private volatile long anchorPositionMs;
    private volatile long anchorNanos;
    private volatile long decodedPositionMs;
    private volatile boolean playing;
    private volatile boolean clientPaused;
    private volatile boolean clockStarted;
    private volatile boolean preloading;
    private volatile boolean preloadFrameDecoded;
    private volatile boolean playbackReady;
    private volatile long preloadStartedNanos;
    private volatile long preloadFirstFramePositionMs = -1L;
    private volatile long preloadLastDecodedPositionMs = -1L;
    private volatile long preloadDecodedFrames;
    private volatile boolean preloadDiagnosticsLogged;
    private volatile boolean videoReconnecting;
    private volatile long lastVideoFrameNanos;
    private volatile long lastCatchUpSeekNanos;
    private volatile SpatialAudioState spatialAudioState = SpatialAudioState.FULL_VOLUME;

    public FfmpegPlaybackAdapter(String sessionId) {
        this.sessionId = sessionId;
        this.screenTexture = ScreenTexture.forSession(sessionId, frameBuffer);
    }

    public static boolean prepareExecutables() {
        try {
            EmbeddedFfmpeg.verifyExecutables();
            Main.LOGGER.info("FFmpeg and ffprobe executable checks passed");
            return true;
        } catch (Exception exception) {
            Main.LOGGER.error("FFmpeg and ffprobe are unavailable; local video playback "
                    + "will be disabled", exception);
            return false;
        }
    }

    @Override
    public synchronized void open(String videoId, String videoUrl, String audioUrl,
                                  String requestHeaders, String cookie, boolean disableScaling,
                                  int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                                  double audioRange,
                                  AudioPlaybackMode audioPlaybackMode,
                                  long durationMs, boolean live) {
        MediaRequestOptions requestOptions = new MediaRequestOptions(requestHeaders, cookie);
        int resolvedVideoPipeLanes = resolveVideoPipeLanes(videoPipeLanes);
        long sessionGeneration = generation.incrementAndGet();
        Main.LOGGER.debug("Opening video decoder: generation={}, videoId={}, duration={} ms, "
                        + "scaleVideo={}, disableScaling={}, outputLimit={}x{}, maxFps={}, "
                        + "videoPipeLanes={}->{}, pixelFormat={}, "
                        + "readiness=first-verified-frame, "
                        + "hardware={}, cudaScale={}, separateAudio={}, customHeaders={}, "
                        + "cookieConfigured={}",
                sessionGeneration, videoId, durationMs, SCALE_VIDEO, disableScaling,
                MAX_OUTPUT_WIDTH,
                MAX_OUTPUT_HEIGHT, MAX_OUTPUT_FPS, videoPipeLanes, resolvedVideoPipeLanes,
                videoPixelFormat,
                System.getProperty("video_synchronizer.ffmpegHardware", "true"),
                System.getProperty("video_synchronizer.ffmpegCudaScale", "false"),
                audioUrl != null && !audioUrl.isBlank(), !requestOptions.headers().isBlank(),
                !requestOptions.cookie().isBlank());
        destroyProcess();
        audioPlayback.close();
        frameBuffer.clear();
        screenTexture.scheduleClose();
        this.durationMs = durationMs;
        this.liveStream = live;
        this.anchorPositionMs = 0L;
        this.anchorNanos = System.nanoTime();
        this.decodedPositionMs = 0L;
        this.playing = false;
        this.clientPaused = false;
        this.clockStarted = false;
        this.preloading = false;
        this.preloadFrameDecoded = false;
        this.playbackReady = false;
        this.preloadStartedNanos = 0L;
        this.preloadFirstFramePositionMs = -1L;
        this.preloadLastDecodedPositionMs = -1L;
        this.preloadDecodedFrames = 0L;
        this.preloadDiagnosticsLogged = false;
        this.videoReconnecting = false;
        this.lastVideoFrameNanos = 0L;
        this.lastCatchUpSeekNanos = 0L;
        this.spatialAudioState = SpatialAudioState.SILENT;
        this.activeMetadata = null;
        this.activeMediaUrl = videoUrl;
        this.activeAudioUrl = audioUrl == null || audioUrl.isBlank() ? null : audioUrl;
        this.activeRequestOptions = requestOptions;
        this.disableScaling = disableScaling;
        this.activeVideoPipeLanes = resolvedVideoPipeLanes;
        this.forceVideoPipeLanes = videoPipeLanes > 0;
        this.activeVideoPixelFormat = videoPixelFormat == null
                ? VideoPixelFormat.RGB24 : videoPixelFormat;
        this.audioMaxDistance = Double.isFinite(audioRange) && audioRange > 0.0D
                ? audioRange : DEFAULT_AUDIO_MAX_DISTANCE;
        this.audioPlaybackMode = audioPlaybackMode == null
                ? AudioPlaybackMode.POSITIONAL : audioPlaybackMode;
        String sessionAudioUrl = this.activeAudioUrl;
        this.preferredDecodeMode = null;
        cancelPreparedSeek();
        this.videoDiscardUntilMs.set(-1L);
        this.requestedSeekMs.set(0L);
        executor.execute(() -> runSession(sessionGeneration, videoUrl, sessionAudioUrl));
    }

    @Override
    public synchronized void applyServerState(long positionMs, boolean playing,
                                              boolean waitingForClients, boolean hardSeek) {
        long boundedPosition = clampToDuration(positionMs);
        long currentPosition = positionMs();
        boolean playbackStateChanged = this.playing != playing;
        boolean wasPreloading = this.preloading;
        boolean resumeAtLiveEdge = liveStream && playing
                && ((playbackStateChanged && !wasPreloading)
                || (wasPreloading && !waitingForClients));
        if (liveStream) {
            boundedPosition = currentPosition;
            hardSeek = false;
        }
        if (playbackStateChanged) {
            audioPlayback.resetOutputProgress();
        }
        this.preloading = waitingForClients;
        if (waitingForClients && hardSeek) {
            preloadFrameDecoded = false;
            playbackReady = false;
            preloadStartedNanos = 0L;
            preloadFirstFramePositionMs = -1L;
            preloadLastDecodedPositionMs = -1L;
            preloadDecodedFrames = 0L;
            preloadDiagnosticsLogged = false;
        }
        if (resumeAtLiveEdge) {
            this.playing = true;
            requestImmediateSeek(0L);
            Main.LOGGER.debug("Resuming live playback at the current stream edge");
            return;
        }
        long forwardDistanceMs = boundedPosition - currentPosition;
        if (hardSeek && decoderProcess && clockStarted
                && (playing || waitingForClients)
                && forwardDistanceMs > 0L
                && forwardDistanceMs <= SOFT_FORWARD_SEEK_MAX_MS) {
            cancelPreparedSeek();
            long nowNanos = System.nanoTime();
            this.anchorPositionMs = boundedPosition;
            this.anchorNanos = nowNanos;
            this.playing = playing;
            if (waitingForClients) {
                clockStarted = false;
            }
            videoDiscardUntilMs.set(boundedPosition);
            frameBuffer.clear();
            audioPlayback.skipForward(boundedPosition);
            Main.LOGGER.debug("Soft-forwarding synchronized playback: current={} ms, "
                            + "target={} ms, distance={} ms",
                    currentPosition, boundedPosition, forwardDistanceMs);
            return;
        }
        if (hardSeek && canPrepareSeek()) {
            videoDiscardUntilMs.set(-1L);
            if (playbackStateChanged) {
                this.anchorPositionMs = currentPosition;
                this.anchorNanos = System.nanoTime();
            }
            this.playing = playing;
            prepareSeek(boundedPosition);
            return;
        }
        if (hardSeek || playbackStateChanged) {
            this.anchorPositionMs = boundedPosition;
            this.anchorNanos = System.nanoTime();
        }
        this.playing = playing;
        // Routine snapshots are delayed observations. Rebasing a running monotonic
        // clock to each one accumulates network latency and slows video over time.
        if (hardSeek) {
            Main.LOGGER.debug("Scheduling hard video seek to {} ms and terminating active decoder",
                    boundedPosition);
            clockStarted = false;
            videoDiscardUntilMs.set(-1L);
            frameBuffer.clear();
            requestedSeekMs.set(boundedPosition);
            destroyDecoderProcess();
            audioPlayback.seek(boundedPosition);
        }
    }

    @Override
    public long positionMs() {
        long position = anchorPositionMs;
        if (clockStarted && playing && !clientPaused) {
            position += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - anchorNanos);
        }
        return clampToDuration(position);
    }

    @Override
    public long durationMs() {
        return durationMs;
    }

    @Override
    public boolean isLiveStream() {
        return liveStream;
    }

    @Override
    public void setLiveStream(boolean live) {
        liveStream = liveStream || live;
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    @Override
    public synchronized void setClientPaused(boolean paused) {
        if (clientPaused == paused) {
            return;
        }
        if (liveStream && clientPaused && !paused) {
            clientPaused = false;
            requestImmediateSeek(0L);
            Main.LOGGER.debug("Local pause ended; reconnecting live playback at the current edge");
            return;
        }
        long now = System.nanoTime();
        long currentPosition = anchorPositionMs;
        if (clockStarted && playing && !clientPaused) {
            currentPosition += TimeUnit.NANOSECONDS.toMillis(now - anchorNanos);
        }
        anchorPositionMs = clampToDuration(currentPosition);
        anchorNanos = now;
        clientPaused = paused;
        audioPlayback.resetOutputProgress();
        Main.LOGGER.debug("Local synchronized playback {}", paused ? "paused" : "resumed");
    }

    @Override
    public synchronized void onFrameRendered(long positionMs) {
        long nowNanos = System.nanoTime();
        if (preloading) {
            if (preloadStartedNanos == 0L) {
                preloadStartedNanos = nowNanos;
                Main.LOGGER.debug("Client input preload started at frame {} ms; waiting for "
                                + "the first verified decoded frame",
                        positionMs);
            }
        } else {
            playbackReady = true;
        }
        if (clockStarted) {
            return;
        }
        anchorPositionMs = clampToDuration(positionMs);
        anchorNanos = nowNanos;
        clockStarted = true;
        Main.LOGGER.debug("Client playback clock started at first rendered frame {} ms",
                anchorPositionMs);
    }

    @Override
    public boolean isPlaybackClockStarted() {
        return clockStarted;
    }

    @Override
    public synchronized boolean isPlaybackReady() {
        if (!playbackReady && preloading && preloadFrameDecoded && clockStarted) {
            logPreloadDiagnostics();
            playbackReady = true;
            Main.LOGGER.debug("Client input preload completed with the verified frame cache");
        }
        return playbackReady;
    }

    private synchronized void logPreloadDiagnostics() {
        if (preloadDiagnosticsLogged) {
            return;
        }
        preloadDiagnosticsLogged = true;
        VideoFrameBuffer.Stats bufferStats = frameBuffer.stats();
        double fps = activeMetadata == null ? 0.0D : activeMetadata.framesPerSecond;
        long frameDurationMs = fps > 0.0D ? Math.max(1L, Math.round(1000.0D / fps)) : 0L;
        long verifiedPreloadMediaMs = preloadFrameDecoded ? frameDurationMs : 0L;
        long decodedSpanMs = preloadFirstFramePositionMs >= 0L
                && preloadLastDecodedPositionMs >= preloadFirstFramePositionMs
                ? preloadLastDecodedPositionMs - preloadFirstFramePositionMs + frameDurationMs : 0L;
        Main.LOGGER.debug("Video preload diagnostics: elapsed={} ms, decodedFrames={}, "
                        + "decodedSpan={} ms, verifiedPreloadMedia={} ms, pendingFrame={}, "
                        + "ffmpegInputQueue=unobservable (threadQueuePackets={})",
                preloadStartedNanos == 0L ? 0L
                        : (System.nanoTime() - preloadStartedNanos) / 1_000_000L,
                preloadDecodedFrames, decodedSpanMs, verifiedPreloadMediaMs,
                bufferStats.pendingFrame(), INPUT_THREAD_QUEUE_PACKETS);
    }

    @Override
    public synchronized boolean isPreparingSeek() {
        return pendingSeekPreparation >= 0L || activatedSeekPreparation >= 0L;
    }

    @Override
    public boolean isReconnecting() {
        return videoReconnecting || audioPlayback.isReconnecting();
    }

    @Override
    public void clientTick() {
        updateSpatialAudioState();
        audioPlayback.checkOutputHealth();
    }

    private void updateSpatialAudioState() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            spatialAudioState = SpatialAudioState.SILENT;
            return;
        }
        AudioPlaybackMode mode = audioPlaybackMode;
        if (mode == AudioPlaybackMode.GLOBAL) {
            spatialAudioState = SpatialAudioState.FULL_VOLUME;
            return;
        }
        ClientScreenTarget.SourcePosition source = ClientScreenTarget.sourcePosition(sessionId);
        if (source == null) {
            spatialAudioState = SpatialAudioState.FULL_VOLUME;
            return;
        }
        if (!source.dimension().equals(minecraft.level.dimension().location().toString())) {
            spatialAudioState = SpatialAudioState.SILENT;
            return;
        }
        Vec3 delta = source.position().subtract(minecraft.player.getEyePosition());
        double distance = delta.length();
        double maxDistance = audioMaxDistance;
        if (distance >= maxDistance) {
            spatialAudioState = SpatialAudioState.SILENT;
            return;
        }
        double attenuation = mode == AudioPlaybackMode.FIXED_RANGE ? 1.0D
                : Math.max(0.0D, 1.0D - distance / maxDistance);
        if (mode == AudioPlaybackMode.POSITIONAL) {
            attenuation *= attenuation;
        }
        Vec3 direction = distance < 0.001D ? Vec3.ZERO : delta.scale(1.0D / distance);
        Vec3 right = minecraft.player.getLookAngle().cross(new Vec3(0.0D, 1.0D, 0.0D));
        right = right.lengthSqr() < 0.0001D
                ? new Vec3(1.0D, 0.0D, 0.0D) : right.normalize();
        double pan = Math.max(-1.0D, Math.min(1.0D, direction.dot(right)));
        spatialAudioState = new SpatialAudioState(
                attenuation * (pan > 0.0D ? 1.0D - pan : 1.0D),
                attenuation * (pan < 0.0D ? 1.0D + pan : 1.0D));
    }

    @Override
    public synchronized void close() {
        long closedGeneration = generation.incrementAndGet();
        Main.LOGGER.debug("Closing synchronized playback: nextGeneration={}, position={} ms, "
                        + "decoded={} ms, duration={} ms, playing={}, paused={}, clockStarted={}",
                closedGeneration, positionMs(), decodedPositionMs, durationMs, playing,
                clientPaused, clockStarted);
        playing = false;
        clientPaused = false;
        clockStarted = false;
        preloading = false;
        preloadFrameDecoded = false;
        playbackReady = false;
        preloadStartedNanos = 0L;
        preloadFirstFramePositionMs = -1L;
        preloadLastDecodedPositionMs = -1L;
        preloadDecodedFrames = 0L;
        preloadDiagnosticsLogged = false;
        videoReconnecting = false;
        lastVideoFrameNanos = 0L;
        lastCatchUpSeekNanos = 0L;
        spatialAudioState = SpatialAudioState.SILENT;
        requestedSeekMs.set(-1L);
        videoDiscardUntilMs.set(-1L);
        cancelPreparedSeek();
        destroyProcess();
        audioPlayback.close();
        frameBuffer.clear();
        screenTexture.scheduleClose();
        activeMetadata = null;
        liveStream = false;
        activeMediaUrl = null;
        activeAudioUrl = null;
        activeRequestOptions = MediaRequestOptions.EMPTY;
        disableScaling = false;
        activeVideoPipeLanes = VIDEO_PIPE_LANES;
        activeVideoPixelFormat = VideoPixelFormat.RGB24;
        audioMaxDistance = DEFAULT_AUDIO_MAX_DISTANCE;
        audioPlaybackMode = AudioPlaybackMode.POSITIONAL;
        forceVideoPipeLanes = false;
        preferredDecodeMode = null;
    }

    @Override
    public synchronized void dispose() {
        close();
        ScreenTexture.closeSession(sessionId);
        audioPlayback.dispose();
        executor.shutdownNow();
        seekTimeoutExecutor.shutdownNow();
        decoderWatchdogExecutor.shutdownNow();
    }

    private void runSession(long sessionGeneration, String mediaUrl, String separateAudioUrl) {
        Main.LOGGER.debug("Video session worker started: generation={}", sessionGeneration);
        try {
            if (generation.get() != sessionGeneration) {
                return;
            }
            VideoMetadata metadata = probe(mediaUrl, sessionGeneration);
            if (generation.get() != sessionGeneration) {
                return;
            }
            if (separateAudioUrl != null) {
                probeAudio(separateAudioUrl, sessionGeneration);
            }
            if (metadata.durationMs > 0L) {
                durationMs = metadata.durationMs;
            }
            liveStream = liveStream || metadata.live;
            activeMetadata = metadata;
            OutputDimensions output = outputDimensions(metadata.width, metadata.height,
                    disableScaling);
            boolean needsScaling = output.width != metadata.width
                    || output.height != metadata.height;
            boolean limitsFrameRate = metadata.framesPerSecond > MAX_OUTPUT_FPS;
            Main.LOGGER.info("Streaming media decoder opened {}x{} at {} fps "
                            + "(codec {}, profile {}, pixel format {}, bitrate {} bps, "
                            + "output {}x{}, spatial scaling {}, frame rate {}, audio {}, live {})",
                    metadata.width, metadata.height, metadata.framesPerSecond,
                    metadata.codecName, metadata.profile, metadata.pixelFormat,
                    metadata.bitRate,
                    output.width, output.height, needsScaling ? "enabled" : "bypassed",
                    limitsFrameRate ? "limited to 60 fps" : "passthrough",
                    metadata.hasAudio || separateAudioUrl != null ? "enabled" : "not present",
                    liveStream);
            String audioMediaUrl = separateAudioUrl == null ? mediaUrl : separateAudioUrl;
            if (metadata.hasAudio || separateAudioUrl != null) {
                audioPlayback.open(sessionGeneration, audioMediaUrl, positionMs());
            }
            long startPosition = nextVideoStartPosition();
            boolean tryHardware = Boolean.parseBoolean(
                    System.getProperty("video_synchronizer.ffmpegHardware", "true"));
            boolean tryCudaScale = tryHardware && needsScaling && Boolean.parseBoolean(
                    System.getProperty("video_synchronizer.ffmpegCudaScale", "false"));
            PreparedVideoDecoder preparedDecoder = null;
            int reconnectAttempts = 0;
            int forbiddenRetryAttempts = 0;

            while (generation.get() == sessionGeneration) {
                DecodeResult result;
                long decodeAttemptStartedNanos = System.nanoTime();
                try {
                    if (preparedDecoder != null) {
                        DecodeMode preparedMode = preparedDecoder.mode;
                        result = decode(mediaUrl, metadata, startPosition,
                                sessionGeneration, preparedMode, preparedDecoder);
                        preparedDecoder = null;
                        if (result != DecodeResult.HARDWARE_FAILED) {
                            preferredDecodeMode = preparedMode;
                        }
                    } else if (tryCudaScale) {
                        result = decode(mediaUrl, metadata, startPosition,
                                sessionGeneration, DecodeMode.CUDA_SCALE, null);
                        if (result == DecodeResult.HARDWARE_FAILED) {
                            tryCudaScale = false;
                            Main.LOGGER.info("FFmpeg CUDA scaling is unavailable; trying generic "
                                    + "hardware decoding");
                            result = decode(mediaUrl, metadata, startPosition,
                                    sessionGeneration, DecodeMode.AUTO_HARDWARE, null);
                        }
                    } else if (tryHardware) {
                        result = decode(mediaUrl, metadata, startPosition,
                                sessionGeneration, DecodeMode.AUTO_HARDWARE, null);
                    } else {
                        result = decode(mediaUrl, metadata, startPosition,
                                sessionGeneration, DecodeMode.SOFTWARE, null);
                    }
                } catch (IOException exception) {
                    if (!shouldReconnectVideo(sessionGeneration)
                            || !prepareVideoReconnect(sessionGeneration, reconnectAttempts,
                            "decoder I/O failed")) {
                        throw exception;
                    }
                    reconnectAttempts++;
                    startPosition = nextVideoStartPosition();
                    preparedDecoder = null;
                    continue;
                }
                if (result == DecodeResult.HARDWARE_FAILED) {
                    tryHardware = false;
                    Main.LOGGER.warn("FFmpeg hardware decoding failed; falling back to software decoding");
                    try {
                        result = decode(mediaUrl, metadata, startPosition,
                                sessionGeneration, DecodeMode.SOFTWARE, null);
                    } catch (IOException exception) {
                        if (!shouldReconnectVideo(sessionGeneration)
                                || !prepareVideoReconnect(sessionGeneration, reconnectAttempts,
                                "software decoder I/O failed")) {
                            throw exception;
                        }
                        reconnectAttempts++;
                        startPosition = nextVideoStartPosition();
                        preparedDecoder = null;
                        continue;
                    }
                }
                if (result == DecodeResult.HTTP_FORBIDDEN) {
                    videoReconnecting = true;
                    if (!shouldReconnectVideo(sessionGeneration)
                            || !waitForHttpForbiddenRetry(sessionGeneration,
                            forbiddenRetryAttempts, "video decoder")) {
                        reportHttpError(sessionGeneration, HTTP_FORBIDDEN_STATUS);
                        break;
                    }
                    forbiddenRetryAttempts++;
                    startPosition = nextVideoStartPosition();
                    preparedDecoder = null;
                    continue;
                }
                if (result == DecodeResult.HTTP_ERROR) {
                    break;
                }
                Main.LOGGER.debug("Video decode attempt finished: generation={}, modeResult={}, "
                                + "requestedSeek={} ms, active={}",
                        sessionGeneration, result, requestedSeekMs.get(),
                        generation.get() == sessionGeneration);
                if (result != DecodeResult.SEEK_REQUESTED) {
                    if (lastVideoFrameNanos - decodeAttemptStartedNanos
                            >= VIDEO_RECOVERY_STABLE_NANOS) {
                        reconnectAttempts = 0;
                        forbiddenRetryAttempts = 0;
                    }
                    if (!shouldReconnectVideo(sessionGeneration)
                            || !prepareVideoReconnect(sessionGeneration, reconnectAttempts,
                            "decoder stream ended")) {
                        break;
                    }
                    reconnectAttempts++;
                    startPosition = nextVideoStartPosition();
                    preparedDecoder = null;
                    continue;
                }
                videoReconnecting = false;
                reconnectAttempts = 0;
                startPosition = nextVideoStartPosition();
                preparedDecoder = takePreparedVideoDecoder(sessionGeneration);
            }
        } catch (HttpStatusException ignored) {
            // The client has reported the status to the authoritative server.
        } catch (MediaSourceException exception) {
            reportPlaybackError(sessionGeneration, exception.reason, 0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (generation.get() == sessionGeneration) {
                Main.LOGGER.error("Unable to play synchronized video", exception);
            }
        } finally {
            if (generation.get() == sessionGeneration) {
                videoReconnecting = false;
            }
            Main.LOGGER.debug("Video session worker finished: generation={}, active={}, "
                            + "position={} ms, decoded={} ms",
                    sessionGeneration, generation.get() == sessionGeneration,
                    positionMs(), decodedPositionMs);
        }
    }

    private long nextVideoStartPosition() {
        long requested = requestedSeekMs.getAndSet(-1L);
        if (liveStream) {
            return 0L;
        }
        return requested >= 0L ? requested : positionMs();
    }

    private boolean prepareVideoReconnect(long sessionGeneration, int reconnectAttempts,
                                          String reason) throws InterruptedException {
        if (reconnectAttempts >= VIDEO_MAX_RECONNECT_ATTEMPTS) {
            videoReconnecting = false;
            Main.LOGGER.warn("Synchronized video recovery stopped after {} attempts",
                    VIDEO_MAX_RECONNECT_ATTEMPTS);
            return false;
        }
        videoReconnecting = true;
        long delayMs = Math.min(STREAM_RECONNECT_MAX_DELAY_MS,
                STREAM_RECONNECT_INITIAL_DELAY_MS << Math.min(reconnectAttempts, 4));
        Main.LOGGER.warn("Synchronized video {} unexpectedly; reconnecting at {} ms in {} ms "
                        + "(attempt {}/{})",
                reason, positionMs(), delayMs, reconnectAttempts + 1,
                VIDEO_MAX_RECONNECT_ATTEMPTS);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        while (generation.get() == sessionGeneration && requestedSeekMs.get() < 0L
                && System.nanoTime() < deadlineNanos) {
            Thread.sleep(25L);
        }
        return shouldReconnectVideo(sessionGeneration)
                || (generation.get() == sessionGeneration && requestedSeekMs.get() >= 0L);
    }

    private boolean shouldReconnectVideo(long sessionGeneration) {
        if (generation.get() != sessionGeneration) {
            return false;
        }
        long currentPosition = positionMs();
        return durationMs <= 0L
                || currentPosition + AUDIO_END_TOLERANCE_MS < durationMs;
    }

    private synchronized boolean requestCoordinatedRecovery(long sessionGeneration,
                                                            String recoveryReason) {
        if (generation.get() != sessionGeneration || !clockStarted || !playing
                || clientPaused || preloading || requestedSeekMs.get() >= 0L
                || pendingSeekPreparation >= 0L || activatedSeekPreparation >= 0L) {
            return false;
        }
        long recoveryPositionMs = liveStream ? 0L : positionMs();
        Main.LOGGER.warn("Synchronized recovery requested for {}; restarting audio and video "
                        + "together at {} ms", recoveryReason, recoveryPositionMs);
        videoReconnecting = true;
        audioPlayback.markReconnecting();
        cancelPreparedSeek();
        requestImmediateSeek(recoveryPositionMs);
        return true;
    }

    private DecodeResult decode(String mediaUrl, VideoMetadata metadata, long startPosition,
                                long sessionGeneration, DecodeMode mode,
                                PreparedVideoDecoder preparedDecoder)
            throws IOException, InterruptedException {
        return decode(mediaUrl, metadata, startPosition, sessionGeneration, mode,
                preparedDecoder, true);
    }

    private DecodeResult decode(String mediaUrl, VideoMetadata metadata, long startPosition,
                                long sessionGeneration, DecodeMode mode,
                                PreparedVideoDecoder preparedDecoder,
                                boolean allowStripedOutput)
            throws IOException, InterruptedException {
        double outputFps = Math.max(1.0D, Math.min(MAX_OUTPUT_FPS, metadata.framesPerSecond));
        double frameDurationMs = 1000.0D / outputFps;
        OutputDimensions outputDimensions = outputDimensions(metadata.width, metadata.height,
                disableScaling);
        boolean needsScaling = outputDimensions.width != metadata.width
                || outputDimensions.height != metadata.height;
        boolean limitsFrameRate = metadata.framesPerSecond > MAX_OUTPUT_FPS;
        VideoPixelFormat pixelFormat = activeVideoPixelFormat;
        int frameSize = Math.multiplyExact(
                Math.multiplyExact(outputDimensions.width, outputDimensions.height),
                pixelFormat.bytesPerPixel());
        StripedVideoOutput stripedOutput = null;
        int requestedPipeLanes = allowStripedOutput && preparedDecoder == null
                ? effectiveVideoPipeLanes(frameSize, outputDimensions.height) : 1;
        if (requestedPipeLanes > 1) {
            try {
                stripedOutput = StripedVideoOutput.create(
                        outputDimensions.width, outputDimensions.height, requestedPipeLanes,
                        pixelFormat.bytesPerPixel());
            } catch (IOException exception) {
                Main.LOGGER.warn("Unable to create {} local video pipe lanes; using stdout: {}",
                        requestedPipeLanes, exception.getMessage());
            }
        }
        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutable());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        if (mode == DecodeMode.CUDA_SCALE) {
            command.add("-hwaccel");
            command.add("cuda");
            command.add("-hwaccel_output_format");
            command.add("cuda");
        } else if (mode == DecodeMode.AUTO_HARDWARE) {
            command.add("-hwaccel");
            command.add("auto");
        }
        addBufferedInputOptions(command);
        addInputSeek(command, startPosition);
        command.add("-i");
        command.add(mediaUrl);
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        List<String> videoFilters = new ArrayList<>();
        if (mode == DecodeMode.CUDA_SCALE) {
            if (!disableScaling) {
                videoFilters.add(String.format(Locale.ROOT,
                        "scale_cuda=%d:%d:format=nv12", outputDimensions.width,
                        outputDimensions.height));
            }
            videoFilters.add("hwdownload");
            videoFilters.add("format=nv12");
            if (limitsFrameRate) {
                videoFilters.add(String.format(Locale.ROOT, "fps=%.3f", outputFps));
            }
            videoFilters.add("format=" + pixelFormat.ffmpegName());
        } else {
            if (limitsFrameRate) {
                videoFilters.add(String.format(Locale.ROOT, "fps=%.3f", outputFps));
            }
            if (needsScaling && !disableScaling) {
                videoFilters.add(String.format(Locale.ROOT,
                        "scale=%d:%d:flags=fast_bilinear", outputDimensions.width,
                        outputDimensions.height));
            }
        }
        addVideoOutputs(command, videoFilters, outputDimensions, stripedOutput, pixelFormat);
        Process decoder;
        ErrorCollector errors;
        InputStream decoderOutput;
        byte[] preparedFrame = null;
        long decoderStartPosition = startPosition;
        long processAcquireStartedNanos = System.nanoTime();
        if (preparedDecoder != null) {
            decoder = preparedDecoder.process;
            errors = preparedDecoder.errors;
            decoderOutput = preparedDecoder.output;
            preparedFrame = preparedDecoder.firstFrame;
            decoderStartPosition = preparedDecoder.positionMs;
            if (!promotePendingVideoProcess(decoder, sessionGeneration)) {
                closePreparedVideoDecoder(preparedDecoder);
                return DecodeResult.ENDED;
            }
        } else {
            try {
                decoder = EmbeddedFfmpeg.processBuilder(command).start();
            } catch (IOException exception) {
                closeQuietly(stripedOutput);
                throw exception;
            }
            if (!registerProcess(decoder, sessionGeneration, true)) {
                closeQuietly(stripedOutput);
                terminateProcessTree(decoder);
                return DecodeResult.ENDED;
            }
            errors = new ErrorCollector(decoder.getErrorStream());
            errors.start();
            decoderOutput = decoder.getInputStream();
            if (stripedOutput != null) {
                try {
                    stripedOutput.acceptAll(decoder);
                } catch (IOException exception) {
                    closeQuietly(decoderOutput);
                    closeQuietly(stripedOutput);
                    terminateProcessTree(decoder);
                    errors.await();
                    clearProcess(decoder);
                    if (generation.get() != sessionGeneration) {
                        return DecodeResult.ENDED;
                    }
                    Main.LOGGER.warn("Local multi-lane video output failed; retrying with stdout: {}",
                            exception.getMessage());
                    return decode(mediaUrl, metadata, startPosition, sessionGeneration,
                            mode, null, false);
                }
            }
        }
        long processAcquireNanos = System.nanoTime() - processAcquireStartedNanos;
        long decodeStartNanos = System.nanoTime();
        long statsStartNanos = decodeStartNanos;
        long statsDecodedFrames = 0L;
        long statsReadNanos = 0L;
        long statsMaximumReadNanos = 0L;
        long statsPacingNanos = 0L;
        long statsFirstByteDelayNanos = 0L;
        long statsReadCalls = 0L;
        long statsEmptyPolls = 0L;
        boolean streamStalled = false;
        FrameReadResult lastReadResult = null;
        Main.LOGGER.debug("Started FFmpeg video process: pid={}, generation={}, mode={}, "
                        + "target={} ms, decoderStart={} ms, output={}x{} @ {} fps, "
                        + "frameBytes={}, scaling={}, "
                        + "fpsFilter={}, pipeLanes={}, pixelFormat={}, filters={}, prepared={}, "
                        + "processAcquire={} ms",
                decoder.pid(), sessionGeneration, mode.description, startPosition,
                decoderStartPosition,
                outputDimensions.width, outputDimensions.height, outputFps, frameSize,
                needsScaling, limitsFrameRate,
                stripedOutput == null ? 1 : stripedOutput.laneCount(),
                pixelFormat,
                videoFilters.isEmpty() ? "none" : String.join(",", videoFilters),
                preparedDecoder != null, processAcquireNanos / 1_000_000.0D);
        long framePosition = decoderStartPosition;
        int decodedFrames = 0;
        boolean submittedFrame = false;
        String processErrors = "";
        StripedVideoOutput activeStripedOutput = stripedOutput;
        try (InputStream output = decoderOutput;
             StripedVideoOutput lanes = activeStripedOutput) {
            while (generation.get() == sessionGeneration) {
                if (!waitForVideoPlayback(sessionGeneration)) {
                    return requestedSeekMs.get() >= 0L
                            ? DecodeResult.SEEK_REQUESTED : DecodeResult.ENDED;
                }
                if (requestedSeekMs.get() >= 0L) {
                    return DecodeResult.SEEK_REQUESTED;
                }
                byte[] frameData = preparedFrame != null
                        ? preparedFrame : frameBuffer.acquire(frameSize);
                long readStartNanos = System.nanoTime();
                FrameReadOutcome readOutcome;
                if (preparedFrame != null) {
                    preparedFrame = null;
                    readOutcome = new FrameReadOutcome(
                            FrameReadResult.FRAME, frameSize, 0L, 1, 0L);
                } else if (lanes != null) {
                    readOutcome = readStripedFrame(lanes, frameData, decoder, sessionGeneration,
                            decodedFrames == 0 ? VIDEO_START_TIMEOUT_MS : VIDEO_STALL_TIMEOUT_MS);
                } else {
                    readOutcome = readFrame(output, frameData, decoder, sessionGeneration,
                            decodedFrames == 0 ? VIDEO_START_TIMEOUT_MS : VIDEO_STALL_TIMEOUT_MS);
                }
                FrameReadResult readResult = readOutcome.result;
                lastReadResult = readResult;
                long readNanos = System.nanoTime() - readStartNanos;
                if (readResult != FrameReadResult.FRAME) {
                    Main.LOGGER.debug("Video frame read ended: pid={}, result={}, read={} ms, "
                                    + "firstByte={} ms, bytes={}/{}, readCalls={}, emptyPolls={}, "
                                    + "framePosition={} ms, decodedFrames={}, seekRequest={} ms",
                            decoder.pid(), readResult, readNanos / 1_000_000.0D,
                            readOutcome.firstByteDelayNanos / 1_000_000.0D,
                            readOutcome.bytesRead, frameSize, readOutcome.readCalls,
                            readOutcome.emptyPolls, framePosition, decodedFrames,
                            requestedSeekMs.get());
                    frameBuffer.release(
                            new VideoFrameBuffer.DecodedFrame(
                                    outputDimensions.width, outputDimensions.height,
                                    framePosition, pixelFormat, frameData));
                    if (readResult == FrameReadResult.CANCELLED
                            || requestedSeekMs.get() >= 0L) {
                        return DecodeResult.SEEK_REQUESTED;
                    }
                    streamStalled = readResult == FrameReadResult.STALLED;
                    if (decodedFrames == 0 && !errors.text().isBlank()) {
                        Main.LOGGER.warn("FFmpeg exited before producing a video frame using {} "
                                        + "decoding (stderrChars={})",
                                mode.description, errors.text().length());
                    }
                    if (readResult == FrameReadResult.ENDED && decodedFrames > 0) {
                        Main.LOGGER.info("FFmpeg video stream reached EOF at {} ms "
                                        + "(playback clock {} ms, duration {} ms)",
                                decodedPositionMs, positionMs(), durationMs);
                    }
                    break;
                }

                if (!waitForVideoPlayback(sessionGeneration)) {
                    frameBuffer.release(
                            new VideoFrameBuffer.DecodedFrame(
                                    outputDimensions.width, outputDimensions.height,
                                    framePosition, pixelFormat, frameData));
                    return requestedSeekMs.get() >= 0L
                            ? DecodeResult.SEEK_REQUESTED : DecodeResult.ENDED;
                }

                long playbackPosition = positionMs();
                long discardUntilMs = videoDiscardUntilMs.get();
                long requiredFramePosition = discardUntilMs;
                if (playing && (discardUntilMs >= 0L
                        || (preparedDecoder != null && !clockStarted))) {
                    requiredFramePosition = Math.max(playbackPosition, requiredFramePosition);
                }
                if (requiredFramePosition >= 0L
                        && framePosition + 75L < requiredFramePosition) {
                    frameBuffer.release(
                            new VideoFrameBuffer.DecodedFrame(
                                    outputDimensions.width, outputDimensions.height,
                                    framePosition, pixelFormat, frameData));
                    decodedPositionMs = framePosition;
                    decodedFrames++;
                    lastVideoFrameNanos = System.nanoTime();
                    statsDecodedFrames++;
                    statsReadNanos += readNanos;
                    statsMaximumReadNanos = Math.max(statsMaximumReadNanos, readNanos);
                    statsFirstByteDelayNanos += readOutcome.firstByteDelayNanos;
                    statsReadCalls += readOutcome.readCalls;
                    statsEmptyPolls += readOutcome.emptyPolls;
                    framePosition = decoderStartPosition
                            + Math.round(decodedFrames * frameDurationMs);
                    continue;
                }
                if (discardUntilMs >= 0L
                        && videoDiscardUntilMs.compareAndSet(discardUntilMs, -1L)) {
                    Main.LOGGER.debug("Video soft-forward caught up: frame={} ms, target={} ms",
                            framePosition, discardUntilMs);
                }
                long catchUpNow = System.nanoTime();
                long videoBehindMs = playbackPosition - framePosition;
                if (!liveStream && clockStarted && playing && !clientPaused && !preloading
                        && videoDiscardUntilMs.get() < 0L
                        && videoBehindMs >= VIDEO_CATCH_UP_THRESHOLD_MS
                        && catchUpNow - lastCatchUpSeekNanos
                        >= VIDEO_CATCH_UP_COOLDOWN_NANOS
                        && requestCoordinatedRecovery(sessionGeneration,
                                "video decoder fell " + videoBehindMs
                                + " ms behind the synchronized clock")) {
                    lastCatchUpSeekNanos = catchUpNow;
                    frameBuffer.release(
                            new VideoFrameBuffer.DecodedFrame(
                                    outputDimensions.width, outputDimensions.height,
                                    framePosition, pixelFormat, frameData));
                    return DecodeResult.SEEK_REQUESTED;
                }

                long pacingStartNanos = System.nanoTime();
                boolean startingPreparedClock = !clockStarted
                        && (preparedDecoder != null || discardUntilMs >= 0L);
                while (generation.get() == sessionGeneration && requestedSeekMs.get() < 0L
                        && !startingPreparedClock
                        && framePosition > positionMs() + 75L) {
                    Thread.sleep(5L);
                }
                long pacingNanos = System.nanoTime() - pacingStartNanos;
                if (generation.get() != sessionGeneration || requestedSeekMs.get() >= 0L) {
                    frameBuffer.release(
                            new VideoFrameBuffer.DecodedFrame(
                                    outputDimensions.width, outputDimensions.height,
                                    framePosition, pixelFormat, frameData));
                    return requestedSeekMs.get() >= 0L ? DecodeResult.SEEK_REQUESTED : DecodeResult.ENDED;
                }
                if (!submittedFrame) {
                    preferredDecodeMode = mode;
                    Main.LOGGER.info("FFmpeg produced the first video frame using {} decoding",
                            mode.description);
                    Main.LOGGER.debug("First video frame diagnostics: pid={}, startup={} ms, "
                                    + "processAcquire={} ms, read={} ms, pacing={} ms, "
                                    + "firstByte={} ms, readCalls={}, emptyPolls={}, "
                                    + "framePosition={} ms, clock={} ms, prepared={}",
                            decoder.pid(), (System.nanoTime() - decodeStartNanos) / 1_000_000.0D,
                            processAcquireNanos / 1_000_000.0D, readNanos / 1_000_000.0D,
                            pacingNanos / 1_000_000.0D,
                            readOutcome.firstByteDelayNanos / 1_000_000.0D,
                            readOutcome.readCalls, readOutcome.emptyPolls,
                            framePosition, positionMs(), preparedDecoder != null);
                }
                decodedPositionMs = framePosition;
                decodedFrames++;
                lastVideoFrameNanos = System.nanoTime();
                if (videoReconnecting) {
                    videoReconnecting = false;
                    Main.LOGGER.info("Synchronized video connection recovered at {} ms",
                            framePosition);
                }
                VideoFrameBuffer.DecodedFrame frame = new VideoFrameBuffer.DecodedFrame(
                        outputDimensions.width, outputDimensions.height, framePosition,
                        pixelFormat, frameData);
                frameBuffer.submit(frame);
                submittedFrame = true;
                if (preloading) {
                    preloadFrameDecoded = true;
                    if (preloadFirstFramePositionMs < 0L) {
                        preloadFirstFramePositionMs = framePosition;
                    }
                    preloadLastDecodedPositionMs = framePosition;
                    preloadDecodedFrames++;
                }
                statsDecodedFrames++;
                statsReadNanos += readNanos;
                statsMaximumReadNanos = Math.max(statsMaximumReadNanos, readNanos);
                statsPacingNanos += pacingNanos;
                statsFirstByteDelayNanos += readOutcome.firstByteDelayNanos;
                statsReadCalls += readOutcome.readCalls;
                statsEmptyPolls += readOutcome.emptyPolls;
                long statsNow = System.nanoTime();
                long statsElapsedNanos = statsNow - statsStartNanos;
                if (statsElapsedNanos >= DEBUG_INTERVAL_NANOS) {
                    double statsElapsedSeconds = statsElapsedNanos / 1_000_000_000.0D;
                    double decodeFps = statsDecodedFrames / statsElapsedSeconds;
                    double rawOutputMiB = statsDecodedFrames * frameSize
                            / (1024.0D * 1024.0D) / statsElapsedSeconds;
                    double averageReadMs = statsDecodedFrames == 0L ? 0.0D
                            : statsReadNanos / 1_000_000.0D / statsDecodedFrames;
                    double averageFirstByteMs = statsDecodedFrames == 0L ? 0.0D
                            : statsFirstByteDelayNanos / 1_000_000.0D / statsDecodedFrames;
                    long clockPosition = positionMs();
                    VideoFrameBuffer.Stats bufferStats = frameBuffer.stats();
                    Main.LOGGER.debug("Video decode stats: pid={}, mode={}, frames={} ({} fps, "
                                    + "rawOutput={} MiB/s), totalFrames={}, media={} ms, clock={} ms, "
                                    + "drift={} ms, readAvg={} ms, readMax={} ms, pacing={} ms, "
                                    + "firstByteAvg={} ms, readCalls={}, emptyPolls={}, "
                                    + "bufferPending={}, submitted={}, replaced={}, taken={}",
                            decoder.pid(), mode.description, statsDecodedFrames, decodeFps,
                            rawOutputMiB, decodedFrames, framePosition, clockPosition,
                            framePosition - clockPosition, averageReadMs,
                            statsMaximumReadNanos / 1_000_000.0D,
                            statsPacingNanos / 1_000_000.0D, averageFirstByteMs,
                            statsReadCalls, statsEmptyPolls, bufferStats.pendingFrame(),
                            bufferStats.submittedFrames(), bufferStats.replacedFrames(),
                            bufferStats.takenFrames());
                    statsStartNanos = statsNow;
                    statsDecodedFrames = 0L;
                    statsReadNanos = 0L;
                    statsMaximumReadNanos = 0L;
                    statsPacingNanos = 0L;
                    statsFirstByteDelayNanos = 0L;
                    statsReadCalls = 0L;
                    statsEmptyPolls = 0L;
                }
                framePosition = decoderStartPosition + Math.round(decodedFrames * frameDurationMs);
            }
        } finally {
            terminateProcessTree(decoder);
            errors.await();
            processErrors = errors.text();
            clearProcess(decoder);
            Main.LOGGER.debug("Stopped FFmpeg video process: pid={}, mode={}, frames={}, "
                            + "elapsed={} ms, start={} ms, lastMedia={} ms, clock={} ms, "
                            + "lastRead={}, processState={}, stderrChars={}, stderrSummary={}",
                    decoder.pid(), mode.description, decodedFrames,
                    (System.nanoTime() - decodeStartNanos) / 1_000_000.0D,
                    startPosition, decodedPositionMs, positionMs(), lastReadResult,
                    processState(decoder), processErrors.length(),
                    summarizeFfmpegDiagnostics(processErrors));
        }
        int httpStatus = findHttpErrorStatus(processErrors);
        if (httpStatus >= 0 && generation.get() == sessionGeneration) {
            if (httpStatus == HTTP_FORBIDDEN_STATUS) {
                return DecodeResult.HTTP_FORBIDDEN;
            }
            reportHttpError(sessionGeneration, httpStatus);
            return DecodeResult.HTTP_ERROR;
        }
        if (!streamStalled && mode != DecodeMode.SOFTWARE && decodedFrames == 0
                && generation.get() == sessionGeneration) {
            return DecodeResult.HARDWARE_FAILED;
        }
        return DecodeResult.ENDED;
    }

    private synchronized boolean canPrepareSeek() {
        if (activeMetadata == null || activeMediaUrl == null || !decoderProcess) {
            return false;
        }
        OutputDimensions output = outputDimensions(
                activeMetadata.width, activeMetadata.height, disableScaling);
        int frameSize = Math.multiplyExact(Math.multiplyExact(output.width, output.height),
                activeVideoPixelFormat.bytesPerPixel());
        return effectiveVideoPipeLanes(frameSize, output.height) == 1;
    }

    private synchronized void prepareSeek(long positionMs) {
        if (activatedSeekPreparation >= 0L) {
            return;
        }
        if (pendingSeekPreparation >= 0L) {
            long expectedPosition = pendingSeekPositionMs;
            if (pendingSeekPlaying) {
                expectedPosition += TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - pendingSeekRequestedNanos);
            }
            if (Math.abs(expectedPosition - positionMs) < SEEK_REPLACE_THRESHOLD_MS) {
                return;
            }
        }
        cancelPreparedSeek();
        long preparation = seekPreparationGeneration.incrementAndGet();
        long sessionGeneration = generation.get();
        VideoMetadata metadata = activeMetadata;
        String mediaUrl = activeMediaUrl;
        String audioUrl = activeAudioUrl == null ? mediaUrl : activeAudioUrl;
        if (metadata == null || mediaUrl == null) {
            requestImmediateSeek(positionMs);
            return;
        }
        pendingSeekPreparation = preparation;
        pendingSeekPositionMs = positionMs;
        pendingSeekRequestedNanos = System.nanoTime();
        pendingSeekPlaying = playing;
        pendingSeekNeedsAudio = metadata.hasAudio || activeAudioUrl != null;
        pendingAudioFailed = false;
        Main.LOGGER.debug("Preparing synchronized seek: preparation={}, generation={}, "
                        + "position={} ms, audio={}",
                preparation, sessionGeneration, positionMs, pendingSeekNeedsAudio);
        if (pendingSeekNeedsAudio) {
            audioPlayback.prepareSeek(preparation, sessionGeneration, audioUrl, positionMs);
        }
        executor.execute(() -> prepareVideoDecoder(
                preparation, sessionGeneration, mediaUrl, metadata, positionMs));
        seekTimeoutExecutor.schedule(() -> seekPreparationTimedOut(preparation),
                SEEK_PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void prepareVideoDecoder(long preparation, long sessionGeneration,
                                     String mediaUrl, VideoMetadata metadata,
                                     long positionMs) {
        for (DecodeMode mode : preparationModes(metadata)) {
            if (!isSeekPreparationCurrent(preparation, sessionGeneration)) {
                return;
            }
            PreparedVideoDecoder prepared = null;
            try {
                prepared = startPreparedVideoDecoder(preparation, sessionGeneration,
                        mediaUrl, metadata, positionMs, mode);
                if (prepared != null) {
                    onVideoPrepared(preparation, sessionGeneration, prepared);
                    return;
                }
            } catch (HttpStatusException ignored) {
                return;
            } catch (Exception exception) {
                if (isSeekPreparationCurrent(preparation, sessionGeneration)) {
                    Main.LOGGER.debug("Unable to prepare {} video seek decoder: {}",
                            mode.description, exception.getMessage());
                }
            }
            if (prepared != null) {
                closePreparedVideoDecoder(prepared);
            }
        }
        synchronized (this) {
            if (isSeekPreparationCurrent(preparation, sessionGeneration)) {
                Main.LOGGER.warn("Unable to prepare video data for seek to {} ms; keeping current stream",
                        pendingSeekPositionMs);
                cancelPreparedSeek();
            }
        }
    }

    private PreparedVideoDecoder startPreparedVideoDecoder(
            long preparation, long sessionGeneration, String mediaUrl,
            VideoMetadata metadata, long positionMs, DecodeMode mode)
            throws IOException, InterruptedException {
        long preparationStartedNanos = System.nanoTime();
        VideoCommand videoCommand = createVideoCommand(mediaUrl, metadata, positionMs, mode);
        Process candidate = EmbeddedFfmpeg.processBuilder(videoCommand.command).start();
        long processSpawnNanos = System.nanoTime() - preparationStartedNanos;
        if (!registerPendingVideoProcess(candidate, preparation, sessionGeneration)) {
            terminateProcessTree(candidate);
            return null;
        }
        ErrorCollector errors = new ErrorCollector(candidate.getErrorStream());
        errors.start();
        InputStream output = candidate.getInputStream();
        byte[] frame = frameBuffer.acquire(videoCommand.frameSize);
        int offset = 0;
        long firstByteNanos = -1L;
        String failureReason = "cancelled";
        try {
            while (offset < frame.length) {
                if (!isSeekPreparationCurrent(preparation, sessionGeneration)) {
                    failureReason = "cancelled";
                    return null;
                }
                int read = output.read(frame, offset, frame.length - offset);
                if (read < 0) {
                    failureReason = "stdout-eof";
                    return null;
                }
                if (read == 0) {
                    continue;
                }
                if (firstByteNanos < 0L) {
                    firstByteNanos = System.nanoTime();
                }
                offset += read;
            }
            failureReason = "complete";
            preferredDecodeMode = mode;
            Main.LOGGER.debug("Prepared video seek decoder: preparation={}, pid={}, mode={}, "
                            + "position={} ms, frameBytes={}, processSpawn={} ms, "
                            + "firstByte={} ms, firstFrame={} ms",
                    preparation, candidate.pid(), mode.description, positionMs, frame.length,
                    processSpawnNanos / 1_000_000.0D,
                    firstByteNanos < 0L ? -1.0D
                            : (firstByteNanos - preparationStartedNanos) / 1_000_000.0D,
                    (System.nanoTime() - preparationStartedNanos) / 1_000_000.0D);
            return new PreparedVideoDecoder(preparation, positionMs, mode,
                    candidate, errors, output, frame, videoCommand.outputDimensions,
                    videoCommand.pixelFormat);
        } finally {
            if (offset < frame.length) {
                frameBuffer.release(new VideoFrameBuffer.DecodedFrame(
                        videoCommand.outputDimensions.width,
                        videoCommand.outputDimensions.height, positionMs,
                        videoCommand.pixelFormat, frame));
                output.close();
                terminateProcessTree(candidate);
                errors.await();
                clearPendingVideoProcess(candidate);
                String errorText = errors.text();
                Main.LOGGER.debug("Prepared video seek produced no complete frame: "
                                + "preparation={}, pid={}, mode={}, position={} ms, reason={}, "
                                + "bytes={}/{}, elapsed={} ms, processState={}, stderrSummary={}",
                        preparation, candidate.pid(), mode.description, positionMs, failureReason,
                        offset, frame.length,
                        (System.nanoTime() - preparationStartedNanos) / 1_000_000.0D,
                        processState(candidate), summarizeFfmpegDiagnostics(errorText));
                int httpStatus = findHttpErrorStatus(errorText);
                if (httpStatus >= 0 && generation.get() == sessionGeneration) {
                    if (httpStatus != HTTP_FORBIDDEN_STATUS) {
                        reportHttpError(sessionGeneration, httpStatus);
                    }
                    throw new HttpStatusException(httpStatus);
                }
            }
        }
    }

    private synchronized void onVideoPrepared(long preparation, long sessionGeneration,
                                               PreparedVideoDecoder prepared) {
        if (!isSeekPreparationCurrent(preparation, sessionGeneration)) {
            closePreparedVideoDecoder(prepared);
            return;
        }
        preparedVideoDecoder = prepared;
        activatePreparedSeekIfReady();
    }

    private synchronized void onAudioPrepared(long preparation, long sessionGeneration) {
        if (isSeekPreparationCurrent(preparation, sessionGeneration)) {
            activatePreparedSeekIfReady();
        }
    }

    private synchronized void onAudioPreparationFailed(long preparation,
                                                       long sessionGeneration) {
        if (!isSeekPreparationCurrent(preparation, sessionGeneration)) {
            return;
        }
        pendingAudioFailed = true;
        Main.LOGGER.warn("Audio seek preloading failed; switching video and restarting audio normally");
        activatePreparedSeekIfReady();
    }

    private void activatePreparedSeekIfReady() {
        if (preparedVideoDecoder == null
                || (pendingSeekNeedsAudio && !pendingAudioFailed
                && !audioPlayback.isPrepared(pendingSeekPreparation))) {
            return;
        }
        long nowNanos = System.nanoTime();
        long projectedTargetMs = projectedPendingSeekPosition(nowNanos);
        long currentPositionMs = positionMs();
        long remainingDistanceMs = Math.abs(projectedTargetMs - currentPositionMs);
        if (remainingDistanceMs < ClientVideoState.HARD_SEEK_THRESHOLD_MS) {
            Main.LOGGER.debug("Cancelling prepared synchronized seek: preparation={}, "
                            + "current={} ms, target={} ms, distance={} ms",
                    pendingSeekPreparation, currentPositionMs, projectedTargetMs,
                    remainingDistanceMs);
            cancelPreparedSeek();
            return;
        }
        long preparation = pendingSeekPreparation;
        long positionMs = projectedTargetMs;
        anchorPositionMs = positionMs;
        anchorNanos = nowNanos;
        clockStarted = false;
        videoDiscardUntilMs.set(-1L);
        frameBuffer.clear();
        activatedSeekPreparation = preparation;
        audioPlayback.activatePreparedSeek(preparation, positionMs,
                pendingSeekNeedsAudio && !pendingAudioFailed);
        requestedSeekMs.set(positionMs);
        destroyDecoderProcess();
        pendingSeekPreparation = -1L;
        pendingSeekPositionMs = -1L;
        pendingSeekRequestedNanos = 0L;
        pendingSeekPlaying = false;
        Main.LOGGER.debug("Activated prepared synchronized seek: preparation={}, position={} ms",
                preparation, positionMs);
    }

    private synchronized void seekPreparationTimedOut(long preparation) {
        if (pendingSeekPreparation != preparation) {
            return;
        }
        long positionMs = projectedPendingSeekPosition(System.nanoTime());
        long currentPositionMs = positionMs();
        long remainingDistanceMs = Math.abs(positionMs - currentPositionMs);
        if (remainingDistanceMs < ClientVideoState.HARD_SEEK_THRESHOLD_MS) {
            Main.LOGGER.debug("Cancelling timed-out synchronized seek: preparation={}, "
                            + "current={} ms, target={} ms, distance={} ms",
                    preparation, currentPositionMs, positionMs, remainingDistanceMs);
            cancelPreparedSeek();
            return;
        }
        Main.LOGGER.warn("Seek preparation timed out after {} ms; falling back to a hard decoder restart",
                SEEK_PREPARE_TIMEOUT_MS);
        cancelPreparedSeek();
        requestImmediateSeek(positionMs);
    }

    private long projectedPendingSeekPosition(long nowNanos) {
        long positionMs = pendingSeekPositionMs;
        if (pendingSeekPlaying && pendingSeekRequestedNanos > 0L) {
            positionMs += TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, nowNanos - pendingSeekRequestedNanos));
        }
        return clampToDuration(positionMs);
    }

    private void requestImmediateSeek(long positionMs) {
        anchorPositionMs = positionMs;
        anchorNanos = System.nanoTime();
        clockStarted = false;
        videoDiscardUntilMs.set(-1L);
        frameBuffer.clear();
        requestedSeekMs.set(positionMs);
        destroyDecoderProcess();
        audioPlayback.seek(positionMs);
    }

    private synchronized void cancelPreparedSeek() {
        seekPreparationGeneration.incrementAndGet();
        if (preparedVideoDecoder != null) {
            closePreparedVideoDecoder(preparedVideoDecoder);
            preparedVideoDecoder = null;
        }
        destroyPendingVideoProcess();
        audioPlayback.cancelPreparedSeek();
        pendingSeekPreparation = -1L;
        activatedSeekPreparation = -1L;
        pendingSeekPositionMs = -1L;
        pendingSeekRequestedNanos = 0L;
        pendingSeekPlaying = false;
        pendingSeekNeedsAudio = false;
        pendingAudioFailed = false;
    }

    private synchronized boolean isSeekPreparationCurrent(long preparation,
                                                          long sessionGeneration) {
        return generation.get() == sessionGeneration
                && pendingSeekPreparation == preparation;
    }

    private synchronized PreparedVideoDecoder takePreparedVideoDecoder(long sessionGeneration) {
        if (preparedVideoDecoder == null
                || preparedVideoDecoder.preparation != activatedSeekPreparation
                || generation.get() != sessionGeneration) {
            return null;
        }
        PreparedVideoDecoder prepared = preparedVideoDecoder;
        preparedVideoDecoder = null;
        activatedSeekPreparation = -1L;
        return prepared;
    }

    private List<DecodeMode> preparationModes(VideoMetadata metadata) {
        DecodeMode preferred = preferredDecodeMode;
        List<DecodeMode> modes = new ArrayList<>();
        if (preferred != null) {
            modes.add(preferred);
            if (preferred == DecodeMode.CUDA_SCALE) {
                modes.add(DecodeMode.AUTO_HARDWARE);
            }
        } else if (Boolean.parseBoolean(System.getProperty(
                "video_synchronizer.ffmpegHardware", "true"))) {
            if (outputDimensions(metadata.width, metadata.height, disableScaling).width
                    != metadata.width
                    && Boolean.parseBoolean(System.getProperty(
                    "video_synchronizer.ffmpegCudaScale", "false"))) {
                modes.add(DecodeMode.CUDA_SCALE);
            }
            modes.add(DecodeMode.AUTO_HARDWARE);
        }
        if (!modes.contains(DecodeMode.SOFTWARE)) {
            modes.add(DecodeMode.SOFTWARE);
        }
        return modes;
    }

    private int effectiveVideoPipeLanes(int frameSize, int frameHeight) {
        if (activeVideoPipeLanes <= 1
                || (!forceVideoPipeLanes && frameSize < VIDEO_PIPE_MIN_FRAME_BYTES)) {
            return 1;
        }
        return Math.max(1, Math.min(activeVideoPipeLanes, frameHeight));
    }

    private static int resolveVideoPipeLanes(int requestedLanes) {
        return switch (requestedLanes) {
            case 1, 2, 4, 8, 16 -> requestedLanes;
            default -> VIDEO_PIPE_LANES;
        };
    }

    private static void addVideoOutputs(List<String> command, List<String> videoFilters,
                                        OutputDimensions output,
                                        StripedVideoOutput stripedOutput,
                                        VideoPixelFormat pixelFormat) {
        if (stripedOutput == null) {
            if (!videoFilters.isEmpty()) {
                command.add("-vf");
                command.add(String.join(",", videoFilters));
            }
            command.add("-pix_fmt");
            command.add(pixelFormat.ffmpegName());
            command.add("-vsync");
            command.add("0");
            command.add("-f");
            command.add("rawvideo");
            command.add("pipe:1");
            return;
        }

        StringBuilder graph = new StringBuilder("[0:v]");
        if (!videoFilters.isEmpty()) {
            graph.append(String.join(",", videoFilters)).append(',');
        }
        graph.append("split=").append(stripedOutput.laneCount());
        for (int lane = 0; lane < stripedOutput.laneCount(); lane++) {
            graph.append("[video_pipe_source_").append(lane).append(']');
        }
        for (VideoPipeStripe stripe : stripedOutput.stripes()) {
            graph.append(";[video_pipe_source_").append(stripe.index()).append(']')
                    .append("crop=").append(output.width).append(':')
                    .append(stripe.height()).append(":0:").append(stripe.y())
                    .append(",format=").append(pixelFormat.ffmpegName())
                    .append("[video_pipe_output_")
                    .append(stripe.index()).append(']');
        }
        command.add("-filter_complex");
        command.add(graph.toString());
        for (VideoPipeStripe stripe : stripedOutput.stripes()) {
            command.add("-map");
            command.add("[video_pipe_output_" + stripe.index() + "]");
            command.add("-pix_fmt");
            command.add(pixelFormat.ffmpegName());
            command.add("-vsync");
            command.add("0");
            command.add("-f");
            command.add("rawvideo");
            command.add(stripedOutput.url(stripe.index()));
        }
    }

    private VideoCommand createVideoCommand(String mediaUrl, VideoMetadata metadata,
                                             long startPosition, DecodeMode mode) {
        VideoPixelFormat pixelFormat = activeVideoPixelFormat;
        double outputFps = Math.max(1.0D, Math.min(MAX_OUTPUT_FPS, metadata.framesPerSecond));
        OutputDimensions output = outputDimensions(metadata.width, metadata.height, disableScaling);
        boolean needsScaling = output.width != metadata.width || output.height != metadata.height;
        boolean limitsFrameRate = metadata.framesPerSecond > MAX_OUTPUT_FPS;
        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutable());
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("error");
        command.add("-nostdin");
        if (mode == DecodeMode.CUDA_SCALE) {
            command.add("-hwaccel");
            command.add("cuda");
            command.add("-hwaccel_output_format");
            command.add("cuda");
        } else if (mode == DecodeMode.AUTO_HARDWARE) {
            command.add("-hwaccel");
            command.add("auto");
        }
        addBufferedInputOptions(command);
        addInputSeek(command, startPosition);
        command.add("-i");
        command.add(mediaUrl);
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        List<String> filters = new ArrayList<>();
        if (mode == DecodeMode.CUDA_SCALE) {
            if (!disableScaling) {
                filters.add(String.format(Locale.ROOT, "scale_cuda=%d:%d:format=nv12",
                        output.width, output.height));
            }
            filters.add("hwdownload");
            filters.add("format=nv12");
            if (limitsFrameRate) {
                filters.add(String.format(Locale.ROOT, "fps=%.3f", outputFps));
            }
            filters.add("format=" + pixelFormat.ffmpegName());
        } else {
            if (limitsFrameRate) {
                filters.add(String.format(Locale.ROOT, "fps=%.3f", outputFps));
            }
            if (needsScaling && !disableScaling) {
                filters.add(String.format(Locale.ROOT,
                        "scale=%d:%d:flags=fast_bilinear", output.width, output.height));
            }
        }
        if (!filters.isEmpty()) {
            command.add("-vf");
            command.add(String.join(",", filters));
        }
        command.add("-pix_fmt");
        command.add(pixelFormat.ffmpegName());
        command.add("-vsync");
        command.add("0");
        command.add("-f");
        command.add("rawvideo");
        command.add("pipe:1");
        int frameSize = Math.multiplyExact(Math.multiplyExact(output.width, output.height),
                pixelFormat.bytesPerPixel());
        return new VideoCommand(command, output, pixelFormat, frameSize);
    }

    private boolean waitForVideoPlayback(long sessionGeneration) throws InterruptedException {
        while (generation.get() == sessionGeneration && requestedSeekMs.get() < 0L
                && (clientPaused || (!playing && !(preloading && !preloadFrameDecoded)))) {
            if (!playing && !preloading && durationMs > 0L && positionMs() >= durationMs) {
                return false;
            }
            Thread.sleep(10L);
        }
        return generation.get() == sessionGeneration && requestedSeekMs.get() < 0L;
    }

    private VideoMetadata probe(String mediaUrl, long sessionGeneration)
            throws IOException, InterruptedException {
        long probeStartNanos = System.nanoTime();
        JsonObject root;
        try {
            root = runVideoProbe(mediaUrl, sessionGeneration, true);
            if (!hasUsableVideoStream(root)) {
                throw new FastProbeException("fast probe returned incomplete video metadata");
            }
        } catch (FastProbeException exception) {
            if (generation.get() != sessionGeneration) {
                throw new IOException("Video session was cancelled during media probing");
            }
            Main.LOGGER.debug("Fast media probe was inconclusive ({}); retrying with full "
                    + "analysis", exception.getMessage());
            root = runVideoProbe(mediaUrl, sessionGeneration, false);
        }
        if (!root.has("streams") || root.getAsJsonArray("streams").size() == 0) {
            throw new MediaSourceException(VideoPlaybackErrorMessage.Reason.VIDEO_UNPLAYABLE,
                    "ffprobe did not find a video stream");
        }
        JsonObject stream = null;
        boolean hasAudio = false;
        for (var element : root.getAsJsonArray("streams")) {
            JsonObject candidate = element.getAsJsonObject();
            String type = candidate.has("codec_type")
                    ? candidate.get("codec_type").getAsString() : "";
            if (stream == null && "video".equals(type)) {
                stream = candidate;
            } else if ("audio".equals(type)) {
                hasAudio = true;
            }
        }
        if (stream == null) {
            throw new MediaSourceException(VideoPlaybackErrorMessage.Reason.VIDEO_UNPLAYABLE,
                    "ffprobe did not find a video stream");
        }
        int width = stream.get("width").getAsInt();
        int height = stream.get("height").getAsInt();
        String codecName = jsonString(stream, "codec_name", "unknown");
        String profile = jsonString(stream, "profile", "unknown");
        String pixelFormat = jsonString(stream, "pix_fmt", "unknown");
        double fps = stream.has("avg_frame_rate")
                ? parseFrameRate(stream.get("avg_frame_rate").getAsString()) : 30.0D;
        long bitRate = jsonLong(stream, "bit_rate", 0L);
        long detectedDurationMs = 0L;
        if (root.has("format") && root.getAsJsonObject("format").has("duration")) {
            try {
                detectedDurationMs = Math.max(0L, Math.round(
                        root.getAsJsonObject("format").get("duration").getAsDouble() * 1000.0D));
            } catch (RuntimeException ignored) {
                detectedDurationMs = 0L;
            }
        }
        if (bitRate <= 0L && root.has("format")) {
            bitRate = jsonLong(root.getAsJsonObject("format"), "bit_rate", 0L);
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("Unsupported video dimensions: " + width + "x" + height);
        }
        boolean scalingDisabled = !SCALE_VIDEO || disableScaling;
        if (!scalingDisabled && (width > MAX_SOURCE_DIMENSION
                || height > MAX_SOURCE_DIMENSION
                || (long) width * height > MAX_SOURCE_PIXELS)) {
            throw new IOException("Unsupported video dimensions: " + width + "x" + height
                    + " (maximum source size is 4K / 4096x2160 pixels)");
        }
        long sourcePixels = (long) width * height;
        if (sourcePixels > Integer.MAX_VALUE / activeVideoPixelFormat.bytesPerPixel()) {
            throw new IOException("Unsupported unscaled video dimensions: " + width + "x" + height
                    + " (one raw video frame exceeds the Java array limit)");
        }
        Main.LOGGER.debug("Media probe completed: generation={}, elapsed={} ms, source={}x{}, "
                        + "fps={}, codec={}, profile={}, pixelFormat={}, bitrate={} bps, "
                        + "duration={} ms, live={}, audio={}",
                sessionGeneration, (System.nanoTime() - probeStartNanos) / 1_000_000.0D,
                width, height, fps, codecName, profile, pixelFormat, bitRate,
                detectedDurationMs, detectedDurationMs <= 0L, hasAudio);
        return new VideoMetadata(width, height, fps, detectedDurationMs,
                detectedDurationMs <= 0L, hasAudio,
                codecName, profile, pixelFormat, bitRate);
    }

    private JsonObject runVideoProbe(String mediaUrl, long sessionGeneration,
                                     boolean fastProbe)
            throws IOException, InterruptedException {
        int forbiddenRetryAttempts = 0;
        while (true) {
            try {
                return runVideoProbeAttempt(mediaUrl, sessionGeneration, fastProbe);
            } catch (HttpStatusException exception) {
                if (exception.statusCode != HTTP_FORBIDDEN_STATUS
                        || !waitForHttpForbiddenRetry(sessionGeneration,
                        forbiddenRetryAttempts, fastProbe ? "fast media probe" : "media probe")) {
                    reportHttpError(sessionGeneration, exception.statusCode);
                    throw exception;
                }
                forbiddenRetryAttempts++;
            }
        }
    }

    private JsonObject runVideoProbeAttempt(String mediaUrl, long sessionGeneration,
                                            boolean fastProbe)
            throws IOException, InterruptedException {
        int timeoutSeconds = fastProbe ? FAST_PROBE_TIMEOUT_SECONDS : PROBE_TIMEOUT_SECONDS;
        Main.LOGGER.debug("Starting {} media probe: generation={}, executable={}, timeout={} s",
                fastProbe ? "fast" : "full", sessionGeneration, ffprobeExecutable(),
                timeoutSeconds);
        List<String> command = new ArrayList<>();
        command.add(ffprobeExecutable());
        command.add("-v");
        command.add("error");
        addNetworkInputOptions(command);
        if (fastProbe) {
            command.add("-probesize");
            command.add(Integer.toString(FAST_PROBE_SIZE_BYTES));
            command.add("-analyzeduration");
            command.add(Integer.toString(FAST_ANALYZE_DURATION_US));
        }
        command.add("-show_entries");
        command.add("stream=codec_type,codec_name,profile,pix_fmt,width,height,avg_frame_rate,bit_rate:"
                + "format=duration,bit_rate,format_name");
        command.add("-of");
        command.add("json");
        command.add(mediaUrl);
        Process probe = EmbeddedFfmpeg.processBuilder(command)
                .redirectErrorStream(true).start();
        Main.LOGGER.debug("Started {} ffprobe process: pid={}, generation={}",
                fastProbe ? "fast" : "full", probe.pid(), sessionGeneration);
        if (!registerProcess(probe, sessionGeneration, false)) {
            terminateProcessTree(probe);
            throw new IOException("Video session was cancelled");
        }
        try {
            long waitStartNanos = System.nanoTime();
            long timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
            long nextProgressLogNanos = waitStartNanos + TimeUnit.SECONDS.toNanos(5L);
            while (probe.isAlive()) {
                if (generation.get() != sessionGeneration) {
                    throw new IOException("Video session was cancelled during media probing");
                }
                long now = System.nanoTime();
                if (now - waitStartNanos >= timeoutNanos) {
                    if (fastProbe) {
                        throw new FastProbeException("ffprobe timed out after "
                                + timeoutSeconds + " seconds");
                    }
                    throw new IOException("ffprobe timed out after " + timeoutSeconds
                            + " seconds; increase -Dvideo_synchronizer.probeTimeoutSeconds "
                            + "if needed");
                }
                if (!fastProbe && now >= nextProgressLogNanos) {
                    Main.LOGGER.debug("Media probe still running: pid={}, generation={}, "
                                    + "elapsed={} ms, timeout={} s",
                            probe.pid(), sessionGeneration,
                            (now - waitStartNanos) / 1_000_000.0D, timeoutSeconds);
                    nextProgressLogNanos = now + TimeUnit.SECONDS.toNanos(5L);
                }
                probe.waitFor(1L, TimeUnit.SECONDS);
            }
            String probeOutput = new String(
                    probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (probe.exitValue() != 0) {
                int httpStatus = findHttpErrorStatus(probeOutput);
                if (httpStatus >= 0) {
                    throw new HttpStatusException(httpStatus);
                }
                if (fastProbe) {
                    throw new FastProbeException("ffprobe exited with status "
                            + probe.exitValue());
                }
                throw new MediaSourceException(
                        VideoPlaybackErrorMessage.Reason.VIDEO_UNPLAYABLE,
                        "ffprobe could not read video metadata");
            }
            try {
                return JsonParser.parseString(probeOutput).getAsJsonObject();
            } catch (RuntimeException exception) {
                if (fastProbe) {
                    throw new FastProbeException("ffprobe returned invalid metadata");
                }
                throw new MediaSourceException(
                        VideoPlaybackErrorMessage.Reason.VIDEO_UNPLAYABLE,
                        "ffprobe returned invalid video metadata");
            }
        } finally {
            if (probe.isAlive()) {
                terminateProcessTree(probe);
            }
            clearProcess(probe);
        }
    }

    private static boolean hasUsableVideoStream(JsonObject root) {
        if (!root.has("streams") || !root.get("streams").isJsonArray()) {
            return false;
        }
        for (var element : root.getAsJsonArray("streams")) {
            JsonObject stream = element.getAsJsonObject();
            if (stream.has("codec_type")
                    && "video".equals(stream.get("codec_type").getAsString())
                    && jsonLong(stream, "width", 0L) > 0L
                    && jsonLong(stream, "height", 0L) > 0L) {
                return true;
            }
        }
        return false;
    }

    private void probeAudio(String mediaUrl, long sessionGeneration)
            throws IOException, InterruptedException {
        int forbiddenRetryAttempts = 0;
        while (true) {
            try {
                probeAudioAttempt(mediaUrl, sessionGeneration);
                return;
            } catch (HttpStatusException exception) {
                if (exception.statusCode != HTTP_FORBIDDEN_STATUS
                        || !waitForHttpForbiddenRetry(sessionGeneration,
                        forbiddenRetryAttempts, "audio probe")) {
                    reportHttpError(sessionGeneration, exception.statusCode);
                    throw exception;
                }
                forbiddenRetryAttempts++;
            }
        }
    }

    private void probeAudioAttempt(String mediaUrl, long sessionGeneration)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(ffprobeExecutable());
        command.add("-v");
        command.add("error");
        addNetworkInputOptions(command);
        command.add("-show_entries");
        command.add("stream=codec_type");
        command.add("-of");
        command.add("json");
        command.add(mediaUrl);
        Process probe = EmbeddedFfmpeg.processBuilder(command)
                .redirectErrorStream(true).start();
        if (!registerProcess(probe, sessionGeneration, false)) {
            terminateProcessTree(probe);
            throw new IOException("Video session was cancelled");
        }
        try {
            long deadlineNanos = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS);
            while (probe.isAlive()) {
                if (generation.get() != sessionGeneration) {
                    throw new IOException("Video session was cancelled during audio probing");
                }
                if (System.nanoTime() >= deadlineNanos) {
                    throw new IOException("ffprobe timed out while checking the audio stream");
                }
                probe.waitFor(1L, TimeUnit.SECONDS);
            }
            String output = new String(probe.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (probe.exitValue() != 0) {
                int httpStatus = findHttpErrorStatus(output);
                if (httpStatus >= 0) {
                    throw new HttpStatusException(httpStatus);
                }
                throw new MediaSourceException(
                        VideoPlaybackErrorMessage.Reason.AUDIO_UNPLAYABLE,
                        "ffprobe could not read audio metadata");
            }
            JsonObject root = JsonParser.parseString(output).getAsJsonObject();
            boolean hasAudio = false;
            if (root.has("streams")) {
                for (var element : root.getAsJsonArray("streams")) {
                    JsonObject stream = element.getAsJsonObject();
                    if (stream.has("codec_type")
                            && "audio".equals(stream.get("codec_type").getAsString())) {
                        hasAudio = true;
                        break;
                    }
                }
            }
            if (!hasAudio) {
                throw new MediaSourceException(
                        VideoPlaybackErrorMessage.Reason.AUDIO_UNPLAYABLE,
                        "ffprobe did not find an audio stream");
            }
        } finally {
            if (probe.isAlive()) {
                terminateProcessTree(probe);
            }
            clearProcess(probe);
        }
    }

    private static String jsonString(JsonObject object, String name, String fallback) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long jsonLong(JsonObject object, String name, long fallback) {
        try {
            return object.has(name) && !object.get(name).isJsonNull()
                    ? Math.max(0L, object.get(name).getAsLong()) : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void addNetworkInputOptions(List<String> command) {
        command.add("-rw_timeout");
        command.add(NETWORK_TIMEOUT_US);
        command.add("-reconnect");
        command.add("1");
        command.add("-reconnect_on_network_error");
        command.add("1");
        command.add("-reconnect_streamed");
        command.add("1");
        command.add("-reconnect_delay_max");
        command.add("5");
        command.add("-multiple_requests");
        command.add("1");
        command.add("-short_seek_size");
        command.add(HTTP_SHORT_SEEK_SIZE);
        MediaRequestOptions requestOptions = activeRequestOptions;
        String headerBlock = requestOptions.ffmpegHeaderBlock();
        if (!headerBlock.isBlank()) {
            command.add("-headers");
            command.add(headerBlock);
        }
        if (!requestOptions.hasHeader("User-Agent")) {
            command.add("-user_agent");
            command.add("VideoSynchronizer/1.0");
        }
    }

    private void addBufferedInputOptions(List<String> command) {
        addNetworkInputOptions(command);
        command.add("-thread_queue_size");
        command.add(Integer.toString(INPUT_THREAD_QUEUE_PACKETS));
    }

    private void addInputSeek(List<String> command, long startPosition) {
        if (liveStream) {
            return;
        }
        command.add("-ss");
        command.add(String.format(Locale.ROOT, "%.3f", startPosition / 1000.0D));
    }

    private void reportHttpError(long sessionGeneration, int statusCode) {
        reportPlaybackError(sessionGeneration,
                VideoPlaybackErrorMessage.Reason.HTTP_ERROR, statusCode);
    }

    private boolean waitForHttpForbiddenRetry(long sessionGeneration, int retryAttempts,
                                              String operation) throws InterruptedException {
        if (retryAttempts >= MAX_HTTP_FORBIDDEN_RETRY_ATTEMPTS
                || generation.get() != sessionGeneration) {
            Main.LOGGER.warn("FFmpeg {} HTTP 403 retry stopped after {} attempts",
                    operation, retryAttempts);
            return false;
        }
        long delayMs = Math.min(STREAM_RECONNECT_MAX_DELAY_MS,
                STREAM_RECONNECT_INITIAL_DELAY_MS << Math.min(retryAttempts, 4));
        Main.LOGGER.warn("FFmpeg {} received HTTP 403; restarting with the original session URL "
                        + "in {} ms (attempt {}/{})",
                operation, delayMs, retryAttempts + 1,
                MAX_HTTP_FORBIDDEN_RETRY_ATTEMPTS);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        while (generation.get() == sessionGeneration && System.nanoTime() < deadlineNanos) {
            Thread.sleep(25L);
        }
        return generation.get() == sessionGeneration;
    }

    private void reportPlaybackError(long sessionGeneration,
                                     VideoPlaybackErrorMessage.Reason reason,
                                     int statusCode) {
        if (generation.get() != sessionGeneration
                || reportedPlaybackErrorGeneration.getAndSet(sessionGeneration)
                == sessionGeneration) {
            return;
        }
        playing = false;
        preloading = false;
        videoReconnecting = false;
        Minecraft.getInstance().execute(() -> {
            if (generation.get() != sessionGeneration) {
                return;
            }
            destroyProcess();
            audioPlayback.close();
            frameBuffer.clear();
            screenTexture.scheduleClose();
            ClientVideoState.reportPlaybackError(sessionId, reason, statusCode);
        });
    }

    private static int findHttpErrorStatus(String errorText) {
        Matcher matcher = HTTP_STATUS_PATTERN.matcher(errorText);
        while (matcher.find()) {
            int statusCode = Integer.parseInt(matcher.group(1));
            if (statusCode != 200 && statusCode != 206) {
                return statusCode;
            }
        }
        return -1;
    }

    private static String processState(Process candidate) {
        if (candidate.isAlive()) {
            return "alive";
        }
        try {
            return "exit=" + candidate.exitValue();
        } catch (IllegalThreadStateException ignored) {
            return "unknown";
        }
    }

    private static String summarizeFfmpegDiagnostics(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return "none";
        }
        String sanitized = MEDIA_URL_PATTERN.matcher(errorText).replaceAll("<media-url>");
        int maximumLength = 512;
        return sanitized.length() <= maximumLength
                ? sanitized : sanitized.substring(0, maximumLength) + "...";
    }

    private static double parseFrameRate(String value) throws IOException {
        String[] parts = value.split("/", 2);
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = parts.length == 2 ? Double.parseDouble(parts[1]) : 1.0D;
            double fps = numerator / denominator;
            if (numerator == 0.0D || denominator == 0.0D) {
                return 30.0D;
            }
            if (!Double.isFinite(fps) || fps <= 0.0D) {
                throw new NumberFormatException("non-positive frame rate");
            }
            return fps;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid ffprobe frame rate: " + value, exception);
        }
    }

    private FrameReadOutcome readStripedFrame(StripedVideoOutput stripedOutput, byte[] frame,
                                              Process decoder, long sessionGeneration,
                                              long timeoutMs)
            throws IOException, InterruptedException {
        List<Future<FrameReadOutcome>> futures = new ArrayList<>(stripedOutput.laneCount());
        for (VideoPipeStripe stripe : stripedOutput.stripes()) {
            futures.add(stripedOutput.executor().submit(() -> readFrameRange(
                    stripedOutput.input(stripe.index()), frame, stripe.byteOffset(),
                    stripe.byteLength(), decoder, sessionGeneration, timeoutMs,
                    stripe.index())));
        }

        FrameReadResult aggregateResult = FrameReadResult.FRAME;
        int aggregateBytes = 0;
        long aggregateFirstByteDelayNanos = 0L;
        int aggregateReadCalls = 0;
        long aggregateEmptyPolls = 0L;
        boolean completed = false;
        try {
            for (Future<FrameReadOutcome> future : futures) {
                FrameReadOutcome outcome;
                try {
                    outcome = future.get();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IOException("Video pipe lane reader failed", cause);
                }
                aggregateResult = combineFrameReadResult(aggregateResult, outcome.result);
                aggregateBytes += outcome.bytesRead;
                aggregateFirstByteDelayNanos = Math.max(
                        aggregateFirstByteDelayNanos, outcome.firstByteDelayNanos);
                aggregateReadCalls += outcome.readCalls;
                aggregateEmptyPolls += outcome.emptyPolls;
            }
            completed = true;
        } finally {
            if (!completed || aggregateResult != FrameReadResult.FRAME) {
                futures.forEach(future -> future.cancel(true));
            }
        }
        return new FrameReadOutcome(aggregateResult, aggregateBytes,
                aggregateFirstByteDelayNanos, aggregateReadCalls, aggregateEmptyPolls);
    }

    private static FrameReadResult combineFrameReadResult(FrameReadResult current,
                                                          FrameReadResult next) {
        if (current == FrameReadResult.CANCELLED || next == FrameReadResult.CANCELLED) {
            return FrameReadResult.CANCELLED;
        }
        if (current == FrameReadResult.STALLED || next == FrameReadResult.STALLED) {
            return FrameReadResult.STALLED;
        }
        if (current == FrameReadResult.ENDED || next == FrameReadResult.ENDED) {
            return FrameReadResult.ENDED;
        }
        return FrameReadResult.FRAME;
    }

    private FrameReadOutcome readFrame(InputStream input, byte[] frame, Process decoder,
                                       long sessionGeneration, long timeoutMs)
            throws IOException, InterruptedException {
        return readFrameRange(input, frame, 0, frame.length, decoder, sessionGeneration,
                timeoutMs, 0);
    }

    private FrameReadOutcome readFrameRange(InputStream input, byte[] frame, int frameOffset,
                                            int frameLength, Process decoder,
                                            long sessionGeneration, long timeoutMs,
                                            int lane)
            throws IOException, InterruptedException {
        int offset = frameOffset;
        int endOffset = frameOffset + frameLength;
        long readStartedNanos = System.nanoTime();
        long firstByteDelayNanos = -1L;
        int readCalls = 0;
        AtomicLong lastProgressNanos = new AtomicLong(readStartedNanos);
        AtomicLong lastProgressBytes = new AtomicLong();
        AtomicBoolean stalled = new AtomicBoolean(false);
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        ScheduledFuture<?> watchdog = decoderWatchdogExecutor.scheduleAtFixedRate(() -> {
            if (stalled.get() || generation.get() != sessionGeneration
                    || requestedSeekMs.get() >= 0L || !decoder.isAlive()) {
                return;
            }
            long stalledNanos = System.nanoTime() - lastProgressNanos.get();
            if (lastVideoFrameNanos > 0L
                    && stalledNanos >= TimeUnit.MILLISECONDS.toNanos(VIDEO_RECONNECT_NOTICE_MS)) {
                videoReconnecting = true;
            }
            if (stalledNanos < timeoutNanos || !stalled.compareAndSet(false, true)) {
                return;
            }
            Main.LOGGER.warn("FFmpeg blocking video output stalled for {} ms: pid={}, lane={}, "
                            + "frameBytes={}/{}",
                    timeoutMs, decoder.pid(), lane, lastProgressBytes.get(), frameLength);
            if (!requestCoordinatedRecovery(sessionGeneration, "video output")) {
                terminateProcessTree(decoder);
            }
        }, 100L, 100L, TimeUnit.MILLISECONDS);
        try {
            while (offset < endOffset) {
                if (generation.get() != sessionGeneration || requestedSeekMs.get() >= 0L) {
                    return frameReadOutcome(FrameReadResult.CANCELLED, offset - frameOffset,
                            firstByteDelayNanos, readStartedNanos, readCalls);
                }
                int read;
                try {
                    read = input.read(frame, offset, endOffset - offset);
                } catch (IOException exception) {
                    if (stalled.get() || generation.get() != sessionGeneration
                            || requestedSeekMs.get() >= 0L) {
                        return frameReadOutcome(stalled.get()
                                        ? FrameReadResult.STALLED : FrameReadResult.CANCELLED,
                                offset - frameOffset,
                                firstByteDelayNanos, readStartedNanos, readCalls);
                    }
                    throw exception;
                }
                if (read < 0) {
                    return frameReadOutcome(stalled.get()
                                    ? FrameReadResult.STALLED : FrameReadResult.ENDED,
                            offset - frameOffset, firstByteDelayNanos,
                            readStartedNanos, readCalls);
                }
                if (read == 0) {
                    continue;
                }
                readCalls++;
                if (firstByteDelayNanos < 0L) {
                    firstByteDelayNanos = System.nanoTime() - readStartedNanos;
                }
                offset += read;
                lastProgressBytes.set(offset - frameOffset);
                lastProgressNanos.set(System.nanoTime());
            }
            return frameReadOutcome(FrameReadResult.FRAME, frameLength,
                    firstByteDelayNanos, readStartedNanos, readCalls);
        } finally {
            watchdog.cancel(false);
        }
    }

    private static FrameReadOutcome frameReadOutcome(FrameReadResult result, int bytesRead,
                                                     long firstByteDelayNanos,
                                                     long readStartedNanos, int readCalls) {
        long normalizedDelay = firstByteDelayNanos >= 0L
                ? firstByteDelayNanos : System.nanoTime() - readStartedNanos;
        return new FrameReadOutcome(result, bytesRead, normalizedDelay, readCalls, 0L);
    }

    private static OutputDimensions outputDimensions(int sourceWidth, int sourceHeight,
                                                     boolean disableScaling) {
        if (!SCALE_VIDEO || disableScaling) {
            return new OutputDimensions(sourceWidth, sourceHeight);
        }
        double scale = Math.min(1.0D, Math.min(
                MAX_OUTPUT_WIDTH / (double) sourceWidth,
                MAX_OUTPUT_HEIGHT / (double) sourceHeight));
        int width = Math.max(2, ((int) Math.floor(sourceWidth * scale)) & ~1);
        int height = Math.max(2, ((int) Math.floor(sourceHeight * scale)) & ~1);
        return new OutputDimensions(width, height);
    }

    private static int positiveIntegerProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        return value > 0 ? value : fallback;
    }

    private static double positiveDoubleProperty(String name, double fallback) {
        try {
            double value = Double.parseDouble(
                    System.getProperty(name, Double.toString(fallback)));
            return Double.isFinite(value) && value > 0.0D ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private synchronized void destroyProcess() {
        Process running = process;
        if (running != null) {
            Main.LOGGER.debug("Destroying registered FFmpeg process: pid={}, decoder={}, alive={}",
                    running.pid(), decoderProcess, running.isAlive());
            terminateProcessTree(running);
            process = null;
            decoderProcess = false;
        }
        destroyPendingVideoProcess();
    }

    private synchronized void destroyDecoderProcess() {
        if (decoderProcess && process != null) {
            Process running = process;
            process = null;
            decoderProcess = false;
            Main.LOGGER.debug("Destroying active FFmpeg video process: pid={}, alive={}",
                    running.pid(), running.isAlive());
            terminateProcessTree(running);
        }
    }

    private synchronized boolean registerPendingVideoProcess(Process candidate,
                                                             long preparation,
                                                             long sessionGeneration) {
        if (!isSeekPreparationCurrent(preparation, sessionGeneration)) {
            return false;
        }
        destroyPendingVideoProcess();
        pendingVideoProcess = candidate;
        Main.LOGGER.debug("Registered pending FFmpeg video process: pid={}, preparation={}",
                candidate.pid(), preparation);
        return true;
    }

    private synchronized boolean promotePendingVideoProcess(Process candidate,
                                                            long sessionGeneration) {
        if (generation.get() != sessionGeneration || pendingVideoProcess != candidate) {
            return false;
        }
        pendingVideoProcess = null;
        process = candidate;
        decoderProcess = true;
        Main.LOGGER.debug("Promoted pending FFmpeg video process: pid={}, generation={}",
                candidate.pid(), sessionGeneration);
        return true;
    }

    private synchronized void clearPendingVideoProcess(Process candidate) {
        if (pendingVideoProcess == candidate) {
            pendingVideoProcess = null;
        }
    }

    private synchronized void destroyPendingVideoProcess() {
        if (pendingVideoProcess != null) {
            Process pending = pendingVideoProcess;
            pendingVideoProcess = null;
            terminateProcessTree(pending);
        }
    }

    private void closePreparedVideoDecoder(PreparedVideoDecoder prepared) {
        frameBuffer.release(new VideoFrameBuffer.DecodedFrame(
                prepared.outputDimensions.width, prepared.outputDimensions.height,
                prepared.positionMs, prepared.pixelFormat, prepared.firstFrame));
        try {
            prepared.output.close();
        } catch (IOException ignored) {
        }
        terminateProcessTree(prepared.process);
        try {
            prepared.errors.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        clearPendingVideoProcess(prepared.process);
    }

    private synchronized boolean registerProcess(Process candidate, long sessionGeneration,
                                                 boolean decoder) {
        if (generation.get() != sessionGeneration) {
            return false;
        }
        process = candidate;
        decoderProcess = decoder;
        Main.LOGGER.debug("Registered FFmpeg process: pid={}, generation={}, decoder={}",
                candidate.pid(), sessionGeneration, decoder);
        return true;
    }

    private synchronized void clearProcess(Process candidate) {
        if (process == candidate) {
            Main.LOGGER.debug("Cleared FFmpeg process registration: pid={}, decoder={}",
                    candidate.pid(), decoderProcess);
            process = null;
            decoderProcess = false;
        }
    }

    private static void terminateProcessTree(Process running) {
        long descendants = running.descendants().count();
        Main.LOGGER.debug("Terminating FFmpeg process tree: pid={}, descendants={}, alive={}",
                running.pid(), descendants, running.isAlive());
        running.descendants().forEach(ProcessHandle::destroyForcibly);
        running.destroyForcibly();
    }

    private static String ffmpegExecutable() {
        return EmbeddedFfmpeg.ffmpegExecutable();
    }

    private static String ffprobeExecutable() {
        return EmbeddedFfmpeg.ffprobeExecutable();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private long clampToDuration(long value) {
        return durationMs > 0L ? clamp(value, 0L, durationMs) : Math.max(0L, value);
    }

    private record VideoPipeStripe(int index, int y, int height,
                                   int byteOffset, int byteLength) {
    }

    private static final class StripedVideoOutput implements AutoCloseable {
        private final List<ServerSocket> listeners;
        private final List<Socket> sockets;
        private final List<InputStream> inputs;
        private final List<VideoPipeStripe> stripes;
        private final ExecutorService executor;

        private StripedVideoOutput(List<ServerSocket> listeners,
                                   List<VideoPipeStripe> stripes) {
            this.listeners = listeners;
            this.stripes = stripes;
            this.sockets = new ArrayList<>(listeners.size());
            this.inputs = new ArrayList<>(listeners.size());
            for (int lane = 0; lane < listeners.size(); lane++) {
                sockets.add(null);
                inputs.add(null);
            }
            AtomicLong threadIds = new AtomicLong();
            this.executor = Executors.newFixedThreadPool(listeners.size(), runnable -> {
                Thread thread = new Thread(runnable, "VideoSynchronizer-Video-Pipe-"
                        + threadIds.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }

        private static StripedVideoOutput create(int width, int height, int laneCount,
                                                 int bytesPerPixel)
                throws IOException {
            List<ServerSocket> listeners = new ArrayList<>(laneCount);
            List<VideoPipeStripe> stripes = new ArrayList<>(laneCount);
            try {
                int baseHeight = height / laneCount;
                int remainingRows = height % laneCount;
                int y = 0;
                for (int lane = 0; lane < laneCount; lane++) {
                    int stripeHeight = baseHeight + (lane < remainingRows ? 1 : 0);
                    int byteOffset = Math.multiplyExact(
                            Math.multiplyExact(y, width), bytesPerPixel);
                    int byteLength = Math.multiplyExact(
                            Math.multiplyExact(stripeHeight, width), bytesPerPixel);
                    ServerSocket listener = new ServerSocket();
                    listener.setReuseAddress(true);
                    listener.setReceiveBufferSize(VIDEO_PIPE_SOCKET_BUFFER_BYTES);
                    listener.bind(new InetSocketAddress("127.0.0.1", 0), 1);
                    listener.setSoTimeout(Math.min(250, VIDEO_PIPE_ACCEPT_TIMEOUT_MS));
                    listeners.add(listener);
                    stripes.add(new VideoPipeStripe(
                            lane, y, stripeHeight, byteOffset, byteLength));
                    y += stripeHeight;
                }
                return new StripedVideoOutput(listeners, stripes);
            } catch (IOException | RuntimeException exception) {
                listeners.forEach(FfmpegPlaybackAdapter::closeQuietly);
                throw exception;
            }
        }

        private void acceptAll(Process decoder) throws IOException {
            try {
                long deadlineNanos = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(VIDEO_PIPE_ACCEPT_TIMEOUT_MS);
                for (int lane = 0; lane < listeners.size(); lane++) {
                    Socket socket = null;
                    while (socket == null && System.nanoTime() < deadlineNanos) {
                        try {
                            socket = listeners.get(lane).accept();
                        } catch (SocketTimeoutException exception) {
                            if (!decoder.isAlive()) {
                                throw new IOException(
                                        "FFmpeg exited before connecting video pipe lane " + lane,
                                        exception);
                            }
                        }
                    }
                    if (socket == null) {
                        throw new IOException("Timed out connecting video pipe lane " + lane);
                    }
                    socket.setTcpNoDelay(true);
                    socket.setReceiveBufferSize(VIDEO_PIPE_SOCKET_BUFFER_BYTES);
                    sockets.set(lane, socket);
                    inputs.set(lane, socket.getInputStream());
                    closeQuietly(listeners.get(lane));
                }
                Main.LOGGER.debug("Connected {} local video pipe lanes with receiveBuffer={} bytes",
                        laneCount(), VIDEO_PIPE_SOCKET_BUFFER_BYTES);
            } catch (IOException exception) {
                close();
                throw exception;
            }
        }

        private int laneCount() {
            return stripes.size();
        }

        private List<VideoPipeStripe> stripes() {
            return stripes;
        }

        private InputStream input(int lane) {
            InputStream input = inputs.get(lane);
            if (input == null) {
                throw new IllegalStateException("Video pipe lane is not connected: " + lane);
            }
            return input;
        }

        private ExecutorService executor() {
            return executor;
        }

        private String url(int lane) {
            int port = listeners.get(lane).getLocalPort();
            return "tcp://127.0.0.1:" + port + "?tcp_nodelay=1";
        }

        @Override
        public void close() {
            listeners.forEach(FfmpegPlaybackAdapter::closeQuietly);
            sockets.forEach(FfmpegPlaybackAdapter::closeQuietly);
            executor.shutdownNow();
        }
    }

    private record VideoMetadata(int width, int height, double framesPerSecond,
                                 long durationMs, boolean live, boolean hasAudio, String codecName,
                                 String profile, String pixelFormat, long bitRate) {
    }

    private record OutputDimensions(int width, int height) {
    }

    private record VideoCommand(List<String> command, OutputDimensions outputDimensions,
                                VideoPixelFormat pixelFormat, int frameSize) {
    }

    private static final class PreparedVideoDecoder {
        private final long preparation;
        private final long positionMs;
        private final DecodeMode mode;
        private final Process process;
        private final ErrorCollector errors;
        private final InputStream output;
        private final byte[] firstFrame;
        private final OutputDimensions outputDimensions;
        private final VideoPixelFormat pixelFormat;

        private PreparedVideoDecoder(long preparation, long positionMs, DecodeMode mode,
                                     Process process, ErrorCollector errors, InputStream output,
                                     byte[] firstFrame, OutputDimensions outputDimensions,
                                     VideoPixelFormat pixelFormat) {
            this.preparation = preparation;
            this.positionMs = positionMs;
            this.mode = mode;
            this.process = process;
            this.errors = errors;
            this.output = output;
            this.firstFrame = firstFrame;
            this.outputDimensions = outputDimensions;
            this.pixelFormat = pixelFormat;
        }
    }

    private enum FrameReadResult {
        FRAME,
        ENDED,
        STALLED,
        CANCELLED
    }

    private record FrameReadOutcome(FrameReadResult result, int bytesRead,
                                    long firstByteDelayNanos, int readCalls,
                                    long emptyPolls) {
    }

    private enum DecodeResult {
        SEEK_REQUESTED,
        HARDWARE_FAILED,
        HTTP_FORBIDDEN,
        HTTP_ERROR,
        ENDED
    }

    private static final class HttpStatusException extends IOException {
        private final int statusCode;

        private HttpStatusException(int statusCode) {
            super("Media server returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    private static final class FastProbeException extends IOException {
        private FastProbeException(String message) {
            super(message);
        }
    }

    private static final class MediaSourceException extends IOException {
        private final VideoPlaybackErrorMessage.Reason reason;

        private MediaSourceException(VideoPlaybackErrorMessage.Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    private enum AudioDecodeResult {
        SEEK_REQUESTED,
        HTTP_FORBIDDEN,
        HTTP_ERROR,
        ENDED_BEFORE_AUDIO,
        ENDED_AFTER_AUDIO,
        ENDED_AFTER_STABLE_AUDIO
    }

    private record SpatialAudioState(double leftGain, double rightGain) {
        private static final SpatialAudioState FULL_VOLUME = new SpatialAudioState(1.0D, 1.0D);
        private static final SpatialAudioState SILENT = new SpatialAudioState(0.0D, 0.0D);
    }

    private enum DecodeMode {
        CUDA_SCALE("CUDA GPU-scaled"),
        AUTO_HARDWARE("hardware-assisted"),
        SOFTWARE("software");

        private final String description;

        DecodeMode(String description) {
            this.description = description;
        }
    }

    private final class AudioPlayback {
        private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "VideoSynchronizer-FFmpeg-Audio");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicLong audioRequestedSeekMs = new AtomicLong(-1L);
        private final AtomicLong forwardDiscardUntilMs = new AtomicLong(-1L);

        private Process audioProcess;
        private Process pendingAudioProcess;
        private PreparedAudioDecoder preparedAudioDecoder;
        private SourceDataLine activeLine;
        private long activatedSeekPreparation = -1L;
        private long activeGeneration = -1L;
        private String mediaUrl;
        private boolean workerRunning;
        private volatile boolean audioEstablished;
        private volatile boolean reconnecting;
        private volatile long lastOutputProgressNanos;

        private synchronized void open(long sessionGeneration, String url, long startPositionMs) {
            Main.LOGGER.debug("Opening synchronized audio: generation={}, start={} ms",
                    sessionGeneration, startPositionMs);
            activeGeneration = sessionGeneration;
            mediaUrl = url;
            audioEstablished = false;
            reconnecting = false;
            lastOutputProgressNanos = 0L;
            forwardDiscardUntilMs.set(-1L);
            audioRequestedSeekMs.set(startPositionMs);
            startWorkerLocked();
        }

        private synchronized void seek(long positionMs) {
            if (activeGeneration < 0L) {
                return;
            }
            Main.LOGGER.debug("Seeking synchronized audio: generation={}, position={} ms",
                    activeGeneration, positionMs);
            forwardDiscardUntilMs.set(-1L);
            audioRequestedSeekMs.set(positionMs);
            destroyAudioProcessLocked();
            startWorkerLocked();
        }

        private synchronized void prepareSeek(long preparation, long sessionGeneration,
                                              String sessionUrl, long positionMs) {
            cancelPreparedSeek();
            executor.execute(() -> prepareAudioDecoder(
                    preparation, sessionGeneration, sessionUrl, positionMs));
        }

        private void prepareAudioDecoder(long preparation, long sessionGeneration,
                                         String sessionUrl, long positionMs) {
            Process candidate = null;
            ErrorCollector errors = null;
            InputStream output = null;
            try {
                List<String> command = audioCommand(sessionUrl, positionMs);
                candidate = EmbeddedFfmpeg.processBuilder(command).start();
                if (!registerPendingAudioProcess(candidate, preparation, sessionGeneration)) {
                    terminateProcessTree(candidate);
                    return;
                }
                errors = new ErrorCollector(candidate.getErrorStream());
                errors.start();
                output = candidate.getInputStream();
                byte[] pcm = new byte[AUDIO_CHUNK_FRAMES * AUDIO_FRAME_SIZE];
                int offset = 0;
                while (offset < pcm.length) {
                    if (!isSeekPreparationCurrent(preparation, sessionGeneration)) {
                        return;
                    }
                    int read = output.read(pcm, offset, pcm.length - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
                int alignedBytes = offset - offset % AUDIO_FRAME_SIZE;
                if (alignedBytes <= 0
                        || !isSeekPreparationCurrent(preparation, sessionGeneration)) {
                    return;
                }
                PreparedAudioDecoder prepared = new PreparedAudioDecoder(
                        preparation, positionMs, candidate, errors, output, pcm,
                        alignedBytes);
                synchronized (this) {
                    if (pendingAudioProcess != candidate) {
                        return;
                    }
                    preparedAudioDecoder = prepared;
                    candidate = null;
                    errors = null;
                    output = null;
                }
                Main.LOGGER.debug("Prepared audio seek decoder: preparation={}, pid={}, "
                                + "position={} ms, pcmBytes={}",
                        preparation, prepared.process.pid(), positionMs, prepared.firstChunkBytes);
                onAudioPrepared(preparation, sessionGeneration);
            } catch (Exception exception) {
                if (isSeekPreparationCurrent(preparation, sessionGeneration)) {
                    Main.LOGGER.debug("Unable to prepare audio seek decoder: {}",
                            exception.getMessage());
                    onAudioPreparationFailed(preparation, sessionGeneration);
                }
            } finally {
                if (candidate != null) {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException ignored) {
                        }
                    }
                    terminateProcessTree(candidate);
                    if (errors != null) {
                        try {
                            errors.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        int httpStatus = findHttpErrorStatus(errors.text());
                        if (httpStatus >= 0 && isActive(sessionGeneration)) {
                            if (httpStatus != HTTP_FORBIDDEN_STATUS) {
                                reportHttpError(sessionGeneration, httpStatus);
                            }
                        }
                    }
                    clearPendingAudioProcess(candidate);
                }
            }
        }

        private synchronized boolean isPrepared(long preparation) {
            return preparedAudioDecoder != null
                    && preparedAudioDecoder.preparation == preparation;
        }

        private synchronized void activatePreparedSeek(long preparation, long positionMs,
                                                       boolean usePreparedAudio) {
            if (!usePreparedAudio || preparedAudioDecoder == null
                    || preparedAudioDecoder.preparation != preparation) {
                cancelPreparedSeek();
            } else {
                activatedSeekPreparation = preparation;
            }
            forwardDiscardUntilMs.set(-1L);
            audioRequestedSeekMs.set(positionMs);
            destroyAudioProcessLocked();
            startWorkerLocked();
        }

        private synchronized void cancelPreparedSeek() {
            if (preparedAudioDecoder != null) {
                closePreparedAudioDecoder(preparedAudioDecoder);
                preparedAudioDecoder = null;
            }
            destroyPendingAudioProcessLocked();
            activatedSeekPreparation = -1L;
        }

        private synchronized void close() {
            if (activeGeneration >= 0L) {
                Main.LOGGER.debug("Closing synchronized audio: generation={}, workerRunning={}, "
                                + "processPresent={}",
                        activeGeneration, workerRunning, audioProcess != null);
            }
            activeGeneration = -1L;
            mediaUrl = null;
            audioEstablished = false;
            reconnecting = false;
            lastOutputProgressNanos = 0L;
            forwardDiscardUntilMs.set(-1L);
            audioRequestedSeekMs.set(-1L);
            destroyAudioProcessLocked();
            cancelPreparedSeek();
        }

        private synchronized void dispose() {
            close();
            executor.shutdownNow();
        }

        private void startWorkerLocked() {
            if (workerRunning || mediaUrl == null) {
                Main.LOGGER.debug("Audio worker start skipped: workerRunning={}, mediaPresent={}",
                        workerRunning, mediaUrl != null);
                return;
            }
            workerRunning = true;
            long sessionGeneration = activeGeneration;
            String sessionUrl = mediaUrl;
            Main.LOGGER.debug("Scheduling audio worker: generation={}", sessionGeneration);
            executor.execute(() -> run(sessionGeneration, sessionUrl));
        }

        private void run(long sessionGeneration, String sessionUrl) {
            Main.LOGGER.debug("Audio worker started: generation={}", sessionGeneration);
            try {
                long startPosition = nextStartPosition();
                int restartAttempts = 0;
                int forbiddenRetryAttempts = 0;
                while (isActive(sessionGeneration)) {
                    AudioDecodeResult result;
                    try {
                        result = decodeAudio(sessionUrl, startPosition, sessionGeneration);
                    } catch (LineUnavailableException exception) {
                        if (isActive(sessionGeneration)) {
                            Main.LOGGER.warn("Audio output is unavailable; continuing video "
                                            + "without audio: {}",
                                    exception.getMessage());
                        }
                        break;
                    } catch (RuntimeException exception) {
                        if (!shouldRestartAudio(sessionGeneration)) {
                            if (isActive(sessionGeneration)) {
                                Main.LOGGER.warn("Audio output is unavailable; continuing video "
                                                + "without audio: {}: {}",
                                        exception.getClass().getSimpleName(), exception.getMessage());
                            }
                            break;
                        }
                        if (!prepareAudioRestart(sessionGeneration, restartAttempts,
                                "output failed")) {
                            break;
                        }
                        restartAttempts++;
                        startPosition = nextStartPosition();
                        continue;
                    } catch (IOException exception) {
                        if (!shouldRestartAudio(sessionGeneration)) {
                            if (isActive(sessionGeneration)) {
                                Main.LOGGER.error("Unable to play synchronized audio", exception);
                            }
                            break;
                        }
                        if (!prepareAudioRestart(sessionGeneration, restartAttempts,
                                "decoder I/O failed")) {
                            break;
                        }
                        restartAttempts++;
                        startPosition = nextStartPosition();
                        continue;
                    }
                    if (result == AudioDecodeResult.SEEK_REQUESTED) {
                        restartAttempts = 0;
                        startPosition = nextStartPosition();
                        continue;
                    }
                    if (result == AudioDecodeResult.HTTP_FORBIDDEN) {
                        reconnecting = true;
                        if (!shouldRestartAudio(sessionGeneration)
                                || !waitForHttpForbiddenRetry(sessionGeneration,
                                forbiddenRetryAttempts, "audio decoder")) {
                            reportHttpError(sessionGeneration, HTTP_FORBIDDEN_STATUS);
                            break;
                        }
                        forbiddenRetryAttempts++;
                        startPosition = nextStartPosition();
                        continue;
                    }
                    if (result == AudioDecodeResult.HTTP_ERROR) {
                        break;
                    }
                    if (result == AudioDecodeResult.ENDED_AFTER_STABLE_AUDIO) {
                        restartAttempts = 0;
                        forbiddenRetryAttempts = 0;
                    }
                    if (!shouldRestartAudio(sessionGeneration)
                            || !prepareAudioRestart(sessionGeneration,
                            restartAttempts, "decoder stream ended")) {
                        if (result == AudioDecodeResult.ENDED_BEFORE_AUDIO
                                && !audioEstablished && isActive(sessionGeneration)) {
                            reportPlaybackError(sessionGeneration,
                                    VideoPlaybackErrorMessage.Reason.AUDIO_UNPLAYABLE, 0);
                        }
                        break;
                    }
                    restartAttempts++;
                    startPosition = nextStartPosition();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                if (isActive(sessionGeneration)) {
                    Main.LOGGER.error("Unable to play synchronized audio", exception);
                }
            } finally {
                Main.LOGGER.debug("Audio worker finished: generation={}, active={}",
                        sessionGeneration, isActive(sessionGeneration));
                finishWorker(sessionGeneration);
            }
        }

        private boolean prepareAudioRestart(long sessionGeneration, int restartAttempts,
                                            String reason) throws InterruptedException {
            if (restartAttempts >= AUDIO_MAX_RESTART_ATTEMPTS) {
                reconnecting = false;
                Main.LOGGER.warn("Synchronized audio recovery stopped after {} attempts",
                        AUDIO_MAX_RESTART_ATTEMPTS);
                return false;
            }
            reconnecting = true;
            long delayMs = Math.min(STREAM_RECONNECT_MAX_DELAY_MS,
                    STREAM_RECONNECT_INITIAL_DELAY_MS << Math.min(restartAttempts, 4));
            long restartPosition = positionMs();
            Main.LOGGER.warn("Synchronized audio {} unexpectedly; restarting from {} ms in {} ms "
                            + "(attempt {}/{})",
                    reason, restartPosition, delayMs, restartAttempts + 1,
                    AUDIO_MAX_RESTART_ATTEMPTS);
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
            while (isActive(sessionGeneration) && audioRequestedSeekMs.get() < 0L
                    && System.nanoTime() < deadlineNanos) {
                Thread.sleep(25L);
            }
            return shouldRestartAudio(sessionGeneration)
                    || (isActive(sessionGeneration) && audioRequestedSeekMs.get() >= 0L);
        }

        private boolean shouldRestartAudio(long sessionGeneration) {
            if (!isActive(sessionGeneration)) {
                return false;
            }
            long currentPosition = positionMs();
            return durationMs <= 0L
                    || currentPosition + AUDIO_END_TOLERANCE_MS < durationMs;
        }

        private AudioDecodeResult decodeAudio(String sessionUrl, long startPosition,
                                              long sessionGeneration)
                throws IOException, InterruptedException, LineUnavailableException {
            PreparedAudioDecoder prepared = takePreparedAudioDecoder(sessionGeneration);
            SourceDataLine line;
            try {
                line = openAudioLine();
            } catch (LineUnavailableException | IllegalArgumentException exception) {
                if (prepared != null) {
                    closePreparedAudioDecoder(prepared);
                }
                throw exception;
            }
            Process decoder;
            ErrorCollector errors;
            InputStream decoderOutput;
            byte[] pcm;
            int preparedChunkBytes = 0;
            long decoderStartPosition = startPosition;
            if (prepared != null) {
                decoder = prepared.process;
                errors = prepared.errors;
                decoderOutput = prepared.output;
                pcm = prepared.firstChunk;
                preparedChunkBytes = prepared.firstChunkBytes;
                decoderStartPosition = prepared.positionMs;
                if (!promotePendingAudioProcess(decoder, sessionGeneration)) {
                    closePreparedAudioDecoder(prepared);
                    line.close();
                    return AudioDecodeResult.ENDED_BEFORE_AUDIO;
                }
            } else {
                try {
                    decoder = EmbeddedFfmpeg.processBuilder(
                            audioCommand(sessionUrl, startPosition)).start();
                } catch (IOException exception) {
                    line.close();
                    throw exception;
                }
                if (!registerAudioProcess(decoder, sessionGeneration)) {
                    terminateProcessTree(decoder);
                    line.close();
                    return AudioDecodeResult.ENDED_BEFORE_AUDIO;
                }
                errors = new ErrorCollector(decoder.getErrorStream());
                errors.start();
                decoderOutput = decoder.getInputStream();
                pcm = new byte[AUDIO_CHUNK_FRAMES * AUDIO_FRAME_SIZE];
            }
            Main.LOGGER.debug("Started FFmpeg audio process: pid={}, generation={}, target={} ms, "
                            + "decoderStart={} ms, format={} Hz/{} channel/s16le, "
                            + "chunkBytes={}, lineBufferBytes={}",
                    decoder.pid(), sessionGeneration, startPosition, decoderStartPosition,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNELS, AUDIO_CHUNK_FRAMES * AUDIO_FRAME_SIZE,
                    AUDIO_BUFFER_FRAMES * AUDIO_FRAME_SIZE);
            long decodedFrames = 0L;
            long lineBasePositionMs = decoderStartPosition;
            boolean submittedAudio = false;
            boolean lineRunning = false;
            long activeForwardTargetMs = -1L;
            long nextVolumeUpdateNanos = 0L;
            float appliedVolume = Float.NaN;
            long statsStartNanos = System.nanoTime();
            long statsBytes = 0L;
            long statsChunks = 0L;
            long statsDiscardedChunks = 0L;
            String processErrors = "";
            AudioReadWatchdog readWatchdog = new AudioReadWatchdog(decoder, sessionGeneration);
            setActiveLine(line, sessionGeneration);
            try (InputStream output = decoderOutput) {
                while (isActive(sessionGeneration)) {
                    if (audioRequestedSeekMs.get() >= 0L) {
                        return AudioDecodeResult.SEEK_REQUESTED;
                    }
                    long requestedForwardTargetMs = forwardDiscardUntilMs.getAndSet(-1L);
                    if (requestedForwardTargetMs >= 0L) {
                        if (lineRunning) {
                            line.stop();
                            lineRunning = false;
                        }
                        line.flush();
                        submittedAudio = false;
                        activeForwardTargetMs = requestedForwardTargetMs;
                        Main.LOGGER.debug("Audio soft-forward started: target={} ms",
                                activeForwardTargetMs);
                    }
                    boolean discardingForward = activeForwardTargetMs >= 0L;
                    if (clientPaused || (!playing && !discardingForward)
                            || (!clockStarted && !discardingForward)) {
                        if (lineRunning) {
                            line.stop();
                            lineRunning = false;
                            Main.LOGGER.debug("Audio output line paused: playing={}, clientPaused={}, "
                                            + "clockStarted={}",
                                    playing, clientPaused, clockStarted);
                        }
                        Thread.sleep(10L);
                        continue;
                    }
                    if (submittedAudio && (!lineRunning || !line.isRunning())) {
                        line.start();
                        lineRunning = true;
                    }

                    int bytesRead;
                    if (preparedChunkBytes > 0) {
                        bytesRead = preparedChunkBytes;
                        preparedChunkBytes = 0;
                    } else {
                        bytesRead = readAudioChunk(output, pcm, sessionGeneration, readWatchdog,
                                submittedAudio ? AUDIO_STALL_TIMEOUT_MS : AUDIO_START_TIMEOUT_MS);
                    }
                    if (bytesRead == AUDIO_READ_CANCELLED
                            || audioRequestedSeekMs.get() >= 0L) {
                        return AudioDecodeResult.SEEK_REQUESTED;
                    }
                    if (bytesRead == AUDIO_READ_STALLED) {
                        return audioEndedResult(submittedAudio, decodedFrames);
                    }
                    if (bytesRead <= 0) {
                        if (!submittedAudio && !errors.text().isBlank()) {
                            Main.LOGGER.warn("FFmpeg exited before producing audio "
                                    + "(stderrChars={})", errors.text().length());
                        }
                        break;
                    }
                    bytesRead -= bytesRead % AUDIO_FRAME_SIZE;
                    if (bytesRead == 0) {
                        continue;
                    }

                    long framesRead = bytesRead / AUDIO_FRAME_SIZE;
                    statsBytes += bytesRead;
                    statsChunks++;
                    long chunkPositionMs = decoderStartPosition
                            + decodedFrames * 1000L / AUDIO_SAMPLE_RATE;
                    decodedFrames += framesRead;
                    long chunkEndMs = decoderStartPosition
                            + decodedFrames * 1000L / AUDIO_SAMPLE_RATE;

                    if (activeForwardTargetMs >= 0L
                            && chunkEndMs <= activeForwardTargetMs) {
                        statsDiscardedChunks++;
                        continue;
                    }
                    if (activeForwardTargetMs >= 0L && (!playing || !clockStarted)) {
                        decodedFrames -= framesRead;
                        statsBytes -= bytesRead;
                        statsChunks--;
                        preparedChunkBytes = bytesRead;
                        activeForwardTargetMs = -1L;
                        continue;
                    }
                    activeForwardTargetMs = -1L;

                    // Align only before starting the output line. Once playback begins,
                    // SourceDataLine must stay fed continuously to avoid audible underruns.
                    if (!submittedAudio && chunkEndMs <= positionMs()) {
                        statsDiscardedChunks++;
                        continue;
                    }
                    while (!submittedAudio
                            && isActive(sessionGeneration)
                            && audioRequestedSeekMs.get() < 0L
                            && chunkPositionMs > positionMs()) {
                        Thread.sleep(2L);
                    }
                    if (!isActive(sessionGeneration)
                            || audioRequestedSeekMs.get() >= 0L) {
                        return AudioDecodeResult.SEEK_REQUESTED;
                    }

                    if (!submittedAudio) {
                        lineBasePositionMs = chunkPositionMs;
                    }
                    long now = System.nanoTime();
                    if (now >= nextVolumeUpdateNanos) {
                        appliedVolume = updateVolume(line, appliedVolume);
                        nextVolumeUpdateNanos = now + TimeUnit.MILLISECONDS.toNanos(250L);
                    }
                    spatializeAudio(pcm, bytesRead);
                    writeAudio(line, pcm, bytesRead, readWatchdog);
                    boolean recovered = reconnecting;
                    reconnecting = false;
                    if (!submittedAudio) {
                        submittedAudio = true;
                        audioEstablished = true;
                        line.start();
                        lineRunning = true;
                        Main.LOGGER.info("FFmpeg produced the first synchronized audio samples");
                    }
                    if (recovered) {
                        Main.LOGGER.info("Synchronized audio connection recovered at {} ms",
                                chunkPositionMs);
                    }

                    long rawPlayedPositionMs = lineBasePositionMs
                            + line.getLongFramePosition() * 1000L / AUDIO_SAMPLE_RATE;
                    long clockPositionMs = positionMs();
                    long driftMs = clockPositionMs - rawPlayedPositionMs;
                    long statsNow = System.nanoTime();
                    long statsElapsedNanos = statsNow - statsStartNanos;
                    if (statsElapsedNanos >= DEBUG_INTERVAL_NANOS) {
                        double elapsedSeconds = statsElapsedNanos / 1_000_000_000.0D;
                        int queuedBytes = line.getBufferSize() - line.available();
                        Main.LOGGER.debug("Audio playback stats: pid={}, chunks={} ({} per second), "
                                        + "PCM={} KiB/s, startupDiscarded={}, decoded={} ms, "
                                        + "played={} ms, clock={} ms, drift={} ms, lineRunning={}, "
                                        + "queuedBytes={}, volume={}",
                                decoder.pid(), statsChunks, statsChunks / elapsedSeconds,
                                statsBytes / 1024.0D / elapsedSeconds, statsDiscardedChunks,
                                chunkEndMs, rawPlayedPositionMs, clockPositionMs, driftMs, lineRunning,
                                queuedBytes, appliedVolume);
                        statsStartNanos = statsNow;
                        statsBytes = 0L;
                        statsChunks = 0L;
                        statsDiscardedChunks = 0L;
                    }
                }
                if (submittedAudio && playing && !clientPaused
                        && isActive(sessionGeneration)) {
                    line.drain();
                }
            } finally {
                readWatchdog.close();
                clearActiveLine(line);
                closeAudioLine(line);
                terminateProcessTree(decoder);
                errors.await();
                processErrors = errors.text();
                clearAudioProcess(decoder);
                Main.LOGGER.debug("Stopped FFmpeg audio process: pid={}, decodedFrames={}, "
                                + "submitted={}, stderrChars={}",
                        decoder.pid(), decodedFrames, submittedAudio, processErrors.length());
            }
            int httpStatus = findHttpErrorStatus(processErrors);
            if (httpStatus >= 0 && isActive(sessionGeneration)) {
                if (httpStatus == HTTP_FORBIDDEN_STATUS) {
                    return AudioDecodeResult.HTTP_FORBIDDEN;
                }
                reportHttpError(sessionGeneration, httpStatus);
                return AudioDecodeResult.HTTP_ERROR;
            }
            return audioEndedResult(submittedAudio, decodedFrames);
        }

        private AudioDecodeResult audioEndedResult(boolean submittedAudio, long decodedFrames) {
            if (!submittedAudio) {
                return AudioDecodeResult.ENDED_BEFORE_AUDIO;
            }
            return decodedFrames >= AUDIO_RECOVERY_STABLE_FRAMES
                    ? AudioDecodeResult.ENDED_AFTER_STABLE_AUDIO
                    : AudioDecodeResult.ENDED_AFTER_AUDIO;
        }

        private SourceDataLine openAudioLine() throws LineUnavailableException {
            AudioFormat format = new AudioFormat(
                    AUDIO_SAMPLE_RATE, 16, AUDIO_CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, AUDIO_BUFFER_FRAMES * AUDIO_FRAME_SIZE);
            return line;
        }

        private int readAudioChunk(InputStream input, byte[] buffer, long sessionGeneration,
                                   AudioReadWatchdog watchdog, long timeoutMs)
                throws IOException {
            int offset = 0;
            watchdog.begin(timeoutMs);
            try {
                while (offset < buffer.length) {
                    if (!isActive(sessionGeneration)
                            || audioRequestedSeekMs.get() >= 0L) {
                        return AUDIO_READ_CANCELLED;
                    }
                    int read;
                    try {
                        read = input.read(buffer, offset, buffer.length - offset);
                    } catch (IOException exception) {
                        if (watchdog.stalled()) {
                            return AUDIO_READ_STALLED;
                        }
                        if (!isActive(sessionGeneration)
                                || audioRequestedSeekMs.get() >= 0L) {
                            return AUDIO_READ_CANCELLED;
                        }
                        throw exception;
                    }
                    if (read < 0) {
                        return watchdog.stalled() ? AUDIO_READ_STALLED : offset;
                    }
                    if (read == 0) {
                        continue;
                    }
                    offset += read;
                    watchdog.progress(offset);
                }
                return offset;
            } finally {
                watchdog.end();
            }
        }

        private final class AudioReadWatchdog implements AutoCloseable {
            private final Process decoder;
            private final long sessionGeneration;
            private final AtomicLong lastProgressNanos = new AtomicLong();
            private final AtomicLong progressBytes = new AtomicLong();
            private final AtomicLong timeoutNanos = new AtomicLong();
            private final AtomicBoolean active = new AtomicBoolean();
            private final AtomicBoolean stalled = new AtomicBoolean();
            private final AtomicBoolean writing = new AtomicBoolean();
            private final ScheduledFuture<?> task;

            private AudioReadWatchdog(Process decoder, long sessionGeneration) {
                this.decoder = decoder;
                this.sessionGeneration = sessionGeneration;
                this.task = decoderWatchdogExecutor.scheduleAtFixedRate(
                        this::check, 100L, 100L, TimeUnit.MILLISECONDS);
            }

            private void begin(long timeoutMs) {
                progressBytes.set(0L);
                writing.set(false);
                timeoutNanos.set(TimeUnit.MILLISECONDS.toNanos(timeoutMs));
                lastProgressNanos.set(System.nanoTime());
                active.set(true);
            }

            private void beginWrite(long timeoutMs) {
                progressBytes.set(0L);
                writing.set(true);
                timeoutNanos.set(TimeUnit.MILLISECONDS.toNanos(timeoutMs));
                lastProgressNanos.set(System.nanoTime());
                active.set(true);
            }

            private void progress(int bytes) {
                progressBytes.set(bytes);
                lastProgressNanos.set(System.nanoTime());
            }

            private void end() {
                active.set(false);
            }

            private boolean stalled() {
                return stalled.get();
            }

            private void check() {
                if (!active.get() || stalled.get() || !isActive(sessionGeneration)
                        || audioRequestedSeekMs.get() >= 0L || !decoder.isAlive()) {
                    return;
                }
                long stalledNanos = System.nanoTime() - lastProgressNanos.get();
                if (audioEstablished && stalledNanos
                        >= TimeUnit.MILLISECONDS.toNanos(AUDIO_RECONNECT_NOTICE_MS)) {
                    reconnecting = true;
                }
                long currentTimeoutNanos = timeoutNanos.get();
                if (stalledNanos < currentTimeoutNanos
                        || !stalled.compareAndSet(false, true)) {
                    return;
                }
                String stalledOperation = writing.get() ? "audio device write" : "audio output";
                Main.LOGGER.warn("FFmpeg blocking {} stalled for {} ms: pid={}, "
                                + "chunkBytes={}/{}",
                        stalledOperation,
                        TimeUnit.NANOSECONDS.toMillis(currentTimeoutNanos), decoder.pid(),
                        progressBytes.get(), AUDIO_CHUNK_FRAMES * AUDIO_FRAME_SIZE);
                reconnecting = true;
                if (!requestCoordinatedRecovery(sessionGeneration, stalledOperation)) {
                    closeActiveLine();
                    terminateProcessTree(decoder);
                }
            }

            @Override
            public void close() {
                active.set(false);
                task.cancel(false);
            }
        }

        private void writeAudio(SourceDataLine line, byte[] data, int length,
                                AudioReadWatchdog watchdog) {
            int offset = 0;
            watchdog.beginWrite(AUDIO_STALL_TIMEOUT_MS);
            try {
                while (offset < length) {
                    int written = line.write(data, offset, length - offset);
                    if (written > 0) {
                        offset += written;
                        lastOutputProgressNanos = System.nanoTime();
                        watchdog.progress(offset);
                    }
                }
            } finally {
                watchdog.end();
            }
        }

        /** Applies the client-thread listener snapshot to PCM in-place. */
        private void spatializeAudio(byte[] data, int length) {
            SpatialAudioState spatial = spatialAudioState;
            if (spatial == SpatialAudioState.FULL_VOLUME) {
                return;
            }
            if (spatial == SpatialAudioState.SILENT) {
                java.util.Arrays.fill(data, 0, length, (byte) 0);
                return;
            }
            for (int offset = 0; offset + AUDIO_FRAME_SIZE <= length;
                 offset += AUDIO_FRAME_SIZE) {
                int left = (short) ((data[offset] & 0xFF) | (data[offset + 1] << 8));
                int rightSample = (short) ((data[offset + 2] & 0xFF)
                        | (data[offset + 3] << 8));
                int mono = (left + rightSample) / 2;
                int scaledLeft = (int) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(mono * spatial.leftGain())));
                int scaledRight = (int) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE,
                                Math.round(mono * spatial.rightGain())));
                data[offset] = (byte) scaledLeft;
                data[offset + 1] = (byte) (scaledLeft >> 8);
                data[offset + 2] = (byte) scaledRight;
                data[offset + 3] = (byte) (scaledRight >> 8);
            }
        }

        private synchronized void setActiveLine(SourceDataLine line, long sessionGeneration) {
            if (isActiveLocked(sessionGeneration)) {
                activeLine = line;
                lastOutputProgressNanos = System.nanoTime();
            }
        }

        private synchronized void clearActiveLine(SourceDataLine line) {
            if (activeLine == line) {
                activeLine = null;
            }
        }

        private synchronized void closeActiveLine() {
            SourceDataLine line = activeLine;
            activeLine = null;
            closeAudioLine(line);
        }

        private void checkOutputHealth() {
            long progressNanos = lastOutputProgressNanos;
            if (!audioEstablished || reconnecting || progressNanos == 0L
                    || !playing || clientPaused || preloading || !clockStarted
                    || System.nanoTime() - progressNanos
                    < TimeUnit.MILLISECONDS.toNanos(AUDIO_STALL_TIMEOUT_MS)) {
                return;
            }
            long sessionGeneration;
            synchronized (this) {
                sessionGeneration = activeGeneration;
            }
            if (!shouldRestartAudio(sessionGeneration)) {
                return;
            }
            requestCoordinatedRecovery(sessionGeneration, "audio playback");
        }

        private synchronized void markReconnecting() {
            if (activeGeneration >= 0L) {
                reconnecting = true;
            }
        }

        private void resetOutputProgress() {
            lastOutputProgressNanos = System.nanoTime();
        }

        private void closeAudioLine(SourceDataLine line) {
            if (line == null) {
                return;
            }
            try {
                line.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                line.flush();
            } catch (RuntimeException ignored) {
            }
            closeQuietly(line);
        }

        private float updateVolume(SourceDataLine line, float appliedVolume) {
            if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                return appliedVolume;
            }
            float volume = Minecraft.getInstance().options
                    .getSoundSourceVolume(SoundSource.MASTER)
                    * Minecraft.getInstance().options
                    .getSoundSourceVolume(SoundSource.RECORDS);
            if (Float.isFinite(appliedVolume)
                    && Math.abs(volume - appliedVolume) < 0.001F) {
                return appliedVolume;
            }
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float decibels = volume <= 0.0001F
                    ? gain.getMinimum() : (float) (20.0D * Math.log10(volume));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
            Main.LOGGER.debug("Applied synchronized audio volume: linear={}, gain={} dB",
                    volume, gain.getValue());
            return volume;
        }

        private List<String> audioCommand(String sessionUrl, long startPosition) {
            List<String> command = new ArrayList<>();
            command.add(ffmpegExecutable());
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("error");
            command.add("-nostdin");
            addBufferedInputOptions(command);
            addInputSeek(command, startPosition);
            command.add("-i");
            command.add(sessionUrl);
            command.add("-map");
            command.add("0:a:0");
            command.add("-vn");
            command.add("-sn");
            command.add("-dn");
            command.add("-af");
            command.add("aresample=async=1:first_pts=0");
            command.add("-ac");
            command.add(Integer.toString(AUDIO_CHANNELS));
            command.add("-ar");
            command.add(Integer.toString(AUDIO_SAMPLE_RATE));
            command.add("-f");
            command.add("s16le");
            command.add("pipe:1");
            return command;
        }

        private boolean registerPendingAudioProcess(Process candidate, long preparation,
                                                    long sessionGeneration) {
            if (!isSeekPreparationCurrent(preparation, sessionGeneration)) {
                return false;
            }
            synchronized (this) {
                if (!isActiveLocked(sessionGeneration)) {
                    return false;
                }
                destroyPendingAudioProcessLocked();
                pendingAudioProcess = candidate;
                return true;
            }
        }

        private synchronized boolean promotePendingAudioProcess(Process candidate,
                                                                long sessionGeneration) {
            if (!isActiveLocked(sessionGeneration) || pendingAudioProcess != candidate) {
                return false;
            }
            pendingAudioProcess = null;
            audioProcess = candidate;
            Main.LOGGER.debug("Promoted pending FFmpeg audio process: pid={}, generation={}",
                    candidate.pid(), sessionGeneration);
            return true;
        }

        private synchronized void clearPendingAudioProcess(Process candidate) {
            if (pendingAudioProcess == candidate) {
                pendingAudioProcess = null;
            }
        }

        private synchronized void destroyPendingAudioProcessLocked() {
            if (pendingAudioProcess != null) {
                Process pending = pendingAudioProcess;
                pendingAudioProcess = null;
                terminateProcessTree(pending);
            }
        }

        private synchronized PreparedAudioDecoder takePreparedAudioDecoder(long sessionGeneration) {
            if (!isActiveLocked(sessionGeneration) || preparedAudioDecoder == null
                    || preparedAudioDecoder.preparation != activatedSeekPreparation) {
                return null;
            }
            PreparedAudioDecoder prepared = preparedAudioDecoder;
            preparedAudioDecoder = null;
            activatedSeekPreparation = -1L;
            return prepared;
        }

        private void skipForward(long positionMs) {
            forwardDiscardUntilMs.set(positionMs);
        }

        private void closePreparedAudioDecoder(PreparedAudioDecoder prepared) {
            try {
                prepared.output.close();
            } catch (IOException ignored) {
            }
            terminateProcessTree(prepared.process);
            try {
                prepared.errors.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            clearPendingAudioProcess(prepared.process);
        }

        private synchronized boolean registerAudioProcess(Process candidate,
                                                          long sessionGeneration) {
            if (!isActiveLocked(sessionGeneration)) {
                return false;
            }
            audioProcess = candidate;
            Main.LOGGER.debug("Registered FFmpeg audio process: pid={}, generation={}",
                    candidate.pid(), sessionGeneration);
            return true;
        }

        private synchronized void clearAudioProcess(Process candidate) {
            if (audioProcess == candidate) {
                Main.LOGGER.debug("Cleared FFmpeg audio process registration: pid={}",
                        candidate.pid());
                audioProcess = null;
            }
        }

        private synchronized void destroyAudioProcessLocked() {
            closeActiveLine();
            if (audioProcess != null) {
                Main.LOGGER.debug("Destroying FFmpeg audio process: pid={}, alive={}",
                        audioProcess.pid(), audioProcess.isAlive());
                terminateProcessTree(audioProcess);
                audioProcess = null;
            }
        }

        private synchronized boolean isActive(long sessionGeneration) {
            return isActiveLocked(sessionGeneration);
        }

        private boolean isReconnecting() {
            return reconnecting;
        }

        private boolean isActiveLocked(long sessionGeneration) {
            return activeGeneration == sessionGeneration
                    && generation.get() == sessionGeneration;
        }

        private long nextStartPosition() {
            long requested = audioRequestedSeekMs.getAndSet(-1L);
            if (liveStream) {
                return 0L;
            }
            return requested >= 0L ? requested : positionMs();
        }

        private synchronized void finishWorker(long sessionGeneration) {
            workerRunning = false;
            Main.LOGGER.debug("Audio worker state cleared: generation={}, active={}, seek={} ms",
                    sessionGeneration, isActiveLocked(sessionGeneration),
                    audioRequestedSeekMs.get());
            boolean replacementSessionWaiting = activeGeneration >= 0L
                    && activeGeneration != sessionGeneration
                    && generation.get() == activeGeneration;
            if (replacementSessionWaiting || (isActiveLocked(sessionGeneration)
                    && audioRequestedSeekMs.get() >= 0L)) {
                startWorkerLocked();
            } else if (isActiveLocked(sessionGeneration)) {
                reconnecting = false;
            }
        }

        private final class PreparedAudioDecoder {
            private final long preparation;
            private final long positionMs;
            private final Process process;
            private final ErrorCollector errors;
            private final InputStream output;
            private final byte[] firstChunk;
            private final int firstChunkBytes;

            private PreparedAudioDecoder(long preparation, long positionMs,
                                         Process process, ErrorCollector errors,
                                         InputStream output, byte[] firstChunk,
                                         int firstChunkBytes) {
                this.preparation = preparation;
                this.positionMs = positionMs;
                this.process = process;
                this.errors = errors;
                this.output = output;
                this.firstChunk = firstChunk;
                this.firstChunkBytes = firstChunkBytes;
            }
        }
    }

    private static final class ErrorCollector {
        private final InputStream input;
        private final StringBuilder contents = new StringBuilder();
        private final Thread thread;

        private ErrorCollector(InputStream input) {
            this.input = input;
            this.thread = new Thread(this::read, "VideoSynchronizer-FFmpeg-stderr");
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void read() {
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[512];
                int count;
                while ((count = reader.read(buffer)) >= 0) {
                    synchronized (contents) {
                        int remaining = MAX_FFMPEG_ERROR_LENGTH - contents.length();
                        if (remaining > 0) {
                            contents.append(buffer, 0, Math.min(count, remaining));
                        }
                    }
                }
            } catch (IOException ignored) {
                // The stream normally closes when a seek terminates the decoder process.
            }
        }

        private void await() throws InterruptedException {
            thread.join(1_000L);
        }

        private String text() {
            synchronized (contents) {
                return contents.toString().strip().replace('\r', ' ').replace('\n', ' ');
            }
        }

    }
}
