package org.arkcraft.video_synchronizer.client.player;

import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.client.ClientVideoState;
import org.arkcraft.video_synchronizer.client.player.video.VideoFrameBuffer;
import org.arkcraft.video_synchronizer.client.render.ScreenTexture;
import org.arkcraft.video_synchronizer.network.model.AudioPlaybackMode;
import org.arkcraft.video_synchronizer.network.model.MediaRequestOptions;
import org.arkcraft.video_synchronizer.network.model.VideoPixelFormat;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.media.MediaSlaveType;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import java.io.File;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** VLCJ/LibVLC-backed playback adapter using VLC's decoded video callback. */
public final class VlcjPlaybackAdapter implements ClientVideoState.PlaybackAdapter {
    private static final long STALL_TIMEOUT_MS = positiveLongProperty(
            "video_synchronizer.vlcjStallTimeoutMs", 8_000L);
    private static final long RECOVERY_COOLDOWN_MS = positiveLongProperty(
            "video_synchronizer.vlcjRecoveryCooldownMs", 5_000L);
    private static final Object NATIVE_DISCOVERY_LOCK = new Object();
    private static volatile NativeDiscovery nativeDiscovery;
    private static volatile String nativeLibraryPath;
    private static volatile boolean directNativeLoad;
    private static final ExecutorService RELEASE_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "VideoSynchronizer-VLCJ-Release");
        thread.setDaemon(true);
        return thread;
    });

    private final String sessionId;
    private final VideoFrameBuffer frameBuffer = new VideoFrameBuffer();
    private final ScreenTexture screenTexture;
    private final ExecutorService vlcExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VideoSynchronizer-VLCJ");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean recoveryQueued = new AtomicBoolean();
    private volatile MediaPlayerFactory factory;
    private volatile EmbeddedMediaPlayer mediaPlayer;
    private volatile long lifecycleGeneration;
    private volatile long durationMs;
    private volatile long nativeDurationMs = -1L;
    private volatile long nativePositionMs = -1L;
    private volatile long nativePositionObservedNanos;
    private volatile boolean nativePlaying;
    private volatile Boolean nativePauseState;
    private volatile boolean playing;
    private volatile boolean waitingForClients;
    private volatile boolean liveStream;
    private volatile boolean clientPaused;
    private volatile boolean clockStarted;
    private volatile boolean playbackReady;
    private volatile boolean firstFrameReceived;
    private volatile long anchorPositionMs;
    private volatile long anchorNanos;
    private volatile long lastFramePositionMs = -1L;
    private volatile long lastFrameNanos;
    private volatile long lastRecoveryNanos;
    private volatile boolean nativeSeekPending;
    private volatile VideoPixelFormat pixelFormat = VideoPixelFormat.RGBA;
    private volatile String mediaVideoId;
    private volatile String mediaVideoUrl;
    private volatile String mediaAudioUrl;
    private volatile String mediaRequestHeaders;
    private volatile String mediaCookie;

    public VlcjPlaybackAdapter(String sessionId) {
        this.sessionId = sessionId;
        this.screenTexture = ScreenTexture.forSession(sessionId, frameBuffer);
    }

    public static boolean prepareExecutables() {
        synchronized (NATIVE_DISCOVERY_LOCK) {
            if (nativeLibraryPath != null) {
                Main.LOGGER.info("VLCJ/LibVLC backend is available: {}", nativeLibraryPath);
                return true;
            }

            List<Path> candidates = vlcDirectoryCandidates();
            List<String> attempted = new ArrayList<>();
            String originalJnaPath = System.getProperty("jna.library.path");
            try {
                for (Path candidatePath : candidates) {
                    String candidate = candidatePath.toString();
                    attempted.add(candidate);
                    if (!hasLibVlc(candidatePath)) {
                        continue;
                    }
                    setJnaLibraryPath(candidate, originalJnaPath);
                    try {
                        NativeDiscovery discovery = new NativeDiscovery();
                        if (!discovery.discover()) {
                            Main.LOGGER.debug("VLCJ native discovery did not find LibVLC in {}", candidate);
                            if (tryDirectNativeLoad(candidate)) {
                                nativeDiscovery = null;
                                nativeLibraryPath = candidate;
                                directNativeLoad = true;
                                Main.LOGGER.info("VLCJ/LibVLC backend is available via direct JNA load: {}",
                                        nativeLibraryPath);
                                return true;
                            }
                            continue;
                        }
                        MediaPlayerFactory factory = new MediaPlayerFactory(discovery);
                        factory.release();
                        nativeDiscovery = discovery;
                        nativeLibraryPath = discovery.discoveredPath() != null
                                ? discovery.discoveredPath() : candidate;
                        directNativeLoad = false;
                        Main.LOGGER.info("VLCJ/LibVLC backend is available: {}", nativeLibraryPath);
                        return true;
                    } catch (Throwable candidateException) {
                        Main.LOGGER.warn("VLCJ/LibVLC could not load from {}", candidate,
                                candidateException);
                    }
                }

                if (originalJnaPath != null && !originalJnaPath.isBlank()) {
                    System.setProperty("jna.library.path", originalJnaPath);
                } else {
                    System.clearProperty("jna.library.path");
                }
                NativeDiscovery discovery = new NativeDiscovery();
                if (discovery.discover()) {
                    MediaPlayerFactory factory = new MediaPlayerFactory(discovery);
                    factory.release();
                    nativeDiscovery = discovery;
                    nativeLibraryPath = discovery.discoveredPath();
                    directNativeLoad = false;
                    Main.LOGGER.info("VLCJ/LibVLC backend is available: {}", nativeLibraryPath);
                    return true;
                }
                Main.LOGGER.debug("VLCJ default native discovery did not find LibVLC");
                Main.LOGGER.error("VLCJ/LibVLC is unavailable; checked VLC directories: {}", attempted);
                return false;
            } catch (Throwable exception) {
                Main.LOGGER.error("VLCJ/LibVLC is unavailable; checked VLC directories: " + attempted,
                        exception);
                return false;
            } finally {
                if (nativeLibraryPath == null) {
                    if (originalJnaPath != null && !originalJnaPath.isBlank()) {
                        System.setProperty("jna.library.path", originalJnaPath);
                    } else {
                        System.clearProperty("jna.library.path");
                    }
                }
            }
        }
    }

    @Override
    public synchronized void open(String videoId, String videoUrl, String audioUrl,
                                   String requestHeaders, String cookie, boolean disableScaling,
                                   int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                                   double audioRange, AudioPlaybackMode audioPlaybackMode,
                                   long durationMs, boolean live) {
        close();
        this.durationMs = durationMs;
        this.liveStream = live || isLikelyLiveStreamUrl(videoUrl);
        this.mediaVideoId = videoId;
        this.mediaVideoUrl = videoUrl;
        this.mediaAudioUrl = audioUrl;
        this.mediaRequestHeaders = requestHeaders;
        this.mediaCookie = cookie;
        this.waitingForClients = false;
        this.pixelFormat = VideoPixelFormat.RGBA;
        this.anchorPositionMs = 0L;
        this.anchorNanos = System.nanoTime();
        this.playing = false;
        this.clientPaused = false;
        this.clockStarted = false;
        this.playbackReady = false;
        this.firstFrameReceived = false;
        this.nativeDurationMs = -1L;
        this.nativePositionMs = -1L;
        this.nativePositionObservedNanos = 0L;
        this.nativePlaying = false;
        this.nativePauseState = null;
        this.lastFramePositionMs = -1L;
        this.lastFrameNanos = 0L;
        this.lastRecoveryNanos = 0L;
        this.nativeSeekPending = false;
        long openGeneration = lifecycleGeneration;
        executeVlc(() -> startVlc(openGeneration, videoId, videoUrl, audioUrl,
                requestHeaders, cookie));
    }

    private void startVlc(long openGeneration, String videoId, String videoUrl, String audioUrl,
                          String requestHeaders, String cookie) {
        MediaPlayerFactory createdFactory = null;
        EmbeddedMediaPlayer createdPlayer = null;
        try {
            NativeDiscovery discovery = nativeDiscovery;
            if (discovery != null) {
                createdFactory = new MediaPlayerFactory(discovery,
                        "--no-video-title-show", "--quiet");
            } else if (directNativeLoad) {
                createdFactory = new MediaPlayerFactory((NativeDiscovery) null,
                        "--no-video-title-show", "--quiet");
            } else {
                createdFactory = new MediaPlayerFactory("--no-video-title-show", "--quiet");
            }
            createdPlayer = createdFactory.mediaPlayers().newEmbeddedMediaPlayer();
            EmbeddedMediaPlayer callbackPlayer = createdPlayer;
            createdPlayer.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override
                public void playing(MediaPlayer player) {
                    nativePlaying = true;
                    nativePauseState = false;
                    nativePositionObservedNanos = System.nanoTime();
                    Main.LOGGER.debug("VLCJ media entered playing state: session={}", sessionId);
                }

                @Override
                public void videoOutput(MediaPlayer player, int newCount) {
                    Main.LOGGER.debug("VLCJ video output became available: session={}, outputs={}",
                            sessionId, newCount);
                }

                @Override
                public void error(MediaPlayer player) {
                    nativePlaying = false;
                    playbackReady = false;
                    Main.LOGGER.error("VLCJ reported a media playback error: session={}", sessionId);
                }

                @Override
                public void paused(MediaPlayer player) {
                    nativePlaying = false;
                    nativePauseState = true;
                }

                @Override
                public void stopped(MediaPlayer player) {
                    nativePlaying = false;
                    nativePauseState = true;
                }

                @Override
                public void finished(MediaPlayer player) {
                    nativePlaying = false;
                    nativePauseState = true;
                    playbackReady = false;
                }

                @Override
                public void timeChanged(MediaPlayer player, long newTime) {
                    if (player != mediaPlayer || newTime < 0L) {
                        return;
                    }
                    long observedNanos = System.nanoTime();
                    long previous = nativePositionMs;
                    if (nativeSeekPending || previous < 0L || newTime >= previous) {
                        nativePositionMs = newTime;
                        nativeSeekPending = false;
                    }
                    nativePositionObservedNanos = observedNanos;
                }

                @Override
                public void lengthChanged(MediaPlayer player, long newLength) {
                    if (player != mediaPlayer || newLength <= 0L) {
                        return;
                    }
                    nativeDurationMs = newLength;
                    if (durationMs <= 0L) {
                        durationMs = newLength;
                    }
                }
            });
            createdPlayer.videoSurface().set(new CallbackVideoSurface(
                    new BufferFormatCallback() {
                        @Override
                        public BufferFormat getBufferFormat(int width, int height) {
                            return new RV32BufferFormat(width, height);
                        }

                        @Override
                        public void allocatedBuffers(ByteBuffer[] buffers) {
                        }
                    },
                    new RenderCallback() {
                        @Override
                public void display(MediaPlayer player, ByteBuffer[] buffers,
                                            BufferFormat format) {
                            submitFrame(player, buffers, format);
                        }
                    }, true, VideoSurfaceAdapters.getVideoSurfaceAdapter()));
            MediaRequestOptions requestOptions = new MediaRequestOptions(requestHeaders, cookie);
            List<String> mediaOptions = mediaOptions(requestOptions);
            if (!isCurrent(openGeneration)) {
                releaseAsync(createdPlayer, createdFactory);
                return;
            }
            synchronized (this) {
                if (openGeneration != lifecycleGeneration) {
                    releaseAsync(createdPlayer, createdFactory);
                    return;
                }
                factory = createdFactory;
                mediaPlayer = createdPlayer;
            }
            if (!callbackPlayer.media().play(videoUrl, mediaOptions.toArray(String[]::new))) {
                throw new IllegalStateException("LibVLC rejected media URL");
            }
            callbackPlayer.audio().setMute(false);
            callbackPlayer.audio().setVolume(100);
            if (isWindows()) {
                try {
                    if (!callbackPlayer.audio().setOutput("directsound")) {
                        Main.LOGGER.debug("VLCJ directsound output is unavailable: session={}",
                                sessionId);
                    }
                } catch (Throwable audioOutputException) {
                    Main.LOGGER.debug("VLCJ directsound output setup failed: session={}",
                            sessionId, audioOutputException);
                }
            }
            if (audioUrl != null && !audioUrl.isBlank()
                    && !callbackPlayer.media().addSlave(MediaSlaveType.AUDIO, audioUrl, true)) {
                Main.LOGGER.warn("VLCJ could not attach the external audio stream: session={}",
                        sessionId);
            }
            long restorePosition;
            boolean restoreSeek;
            boolean restorePause;
            synchronized (this) {
                restorePosition = nativePositionMs >= 0L ? nativePositionMs : anchorPositionMs;
                restoreSeek = nativeSeekPending && !liveStream;
                restorePause = clientPaused || (!playing && !waitingForClients);
            }
            applyVlcState(openGeneration, restorePosition, restoreSeek, restorePause);
            Main.LOGGER.debug("Started VLCJ playback: session={}, videoId={}", sessionId, videoId);
        } catch (Throwable exception) {
            synchronized (this) {
                if (openGeneration == lifecycleGeneration) {
                    mediaPlayer = null;
                    factory = null;
                }
            }
            releaseAsync(createdPlayer, createdFactory);
            if (isCurrent(openGeneration)) {
                Main.LOGGER.error("Unable to start VLCJ playback for session={}", sessionId, exception);
            }
        }
    }

    @Override
    public synchronized void applyServerState(long positionMs, boolean playing,
                                               boolean waitingForClients, boolean hardSeek) {
        long boundedPosition = Math.max(0L, positionMs);
        boolean stateChanged = this.playing != playing
                || this.waitingForClients != waitingForClients;
        if (!stateChanged && !hardSeek) {
            return;
        }
        long previousLivePosition = liveStream && clockStarted
                ? livePositionAt(System.nanoTime()) : boundedPosition;
        this.playing = playing;
        this.waitingForClients = waitingForClients;
        long stateGeneration = lifecycleGeneration;
        boolean pause = clientPaused || (!this.playing && !waitingForClients);
        boolean seek = hardSeek && !liveStream;
        if (hardSeek && !liveStream) {
            anchorPositionMs = boundedPosition;
            anchorNanos = System.nanoTime();
            nativePositionMs = boundedPosition;
            nativePositionObservedNanos = anchorNanos;
            lastFramePositionMs = boundedPosition;
            nativeSeekPending = true;
            clockStarted = false;
        }
        if (!clockStarted || stateChanged || hardSeek) {
            // Decode while the server is collecting ready clients. The server's playing flag
            // remains false during this preload phase, so isPlaying() still reports the
            // authoritative state and no progress report is sent before release.
            anchorPositionMs = liveStream && clockStarted && stateChanged
                    ? previousLivePosition : boundedPosition;
            anchorNanos = System.nanoTime();
        }
        executeVlc(() -> applyVlcState(stateGeneration, boundedPosition, seek, pause));
    }

    @Override
    public synchronized void openAt(String videoId, String videoUrl, String audioUrl,
                                     String requestHeaders, String cookie, boolean disableScaling,
                                     int videoPipeLanes, VideoPixelFormat videoPixelFormat,
                                     double audioRange, AudioPlaybackMode audioPlaybackMode,
                                     long durationMs, boolean live, long positionMs,
                                     boolean playing, boolean waitingForClients) {
        open(videoId, videoUrl, audioUrl, requestHeaders, cookie, disableScaling,
                videoPipeLanes, videoPixelFormat, audioRange, audioPlaybackMode,
                durationMs, live);
        applyServerState(positionMs, playing, waitingForClients, true);
    }

    @Override
    public long positionMs() {
        if (liveStream) {
            if (clockStarted && !clientPaused && (playing || waitingForClients)) {
                return anchorPositionMs + TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - anchorNanos);
            }
            return anchorPositionMs;
        }
        if (waitingForClients) {
            // LibVLC keeps decoding during the readiness barrier, but that time is
            // preload and must not advance the server-authoritative playback clock.
            return anchorPositionMs;
        }
        long time = estimatedNativePositionMs(System.nanoTime());
        if (time >= 0L) {
            return durationMs > 0L ? Math.min(durationMs, time) : time;
        }
        if (clockStarted && playing && !clientPaused) {
            return anchorPositionMs + TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - anchorNanos);
        }
        return anchorPositionMs;
    }

    @Override
    public long durationMs() {
        long length = nativeDurationMs;
        if (durationMs <= 0L && length > 0L) {
            return length;
        }
        return durationMs;
    }

    @Override
    public boolean isLiveStream() {
        return liveStream;
    }

    @Override
    public void setLiveStream(boolean live) {
        liveStream |= live;
    }

    @Override
    public boolean isPlaying() {
        // The server owns the playback state. Once a frame has been decoded, a delayed
        // LibVLC status poll must not make the UI report a false pause during startup.
        return playing && !clientPaused && (nativePlaying || playbackReady);
    }

    @Override
    public synchronized void setClientPaused(boolean paused) {
        if (clientPaused == paused) {
            return;
        }
        clientPaused = paused;
        long stateGeneration = lifecycleGeneration;
        executeVlc(() -> applyVlcState(stateGeneration, -1L, false,
                paused || (!playing && !waitingForClients)));
    }

    @Override
    public synchronized void onFrameRendered(long positionMs, long playbackGeneration) {
        if (!clockStarted) {
            anchorPositionMs = Math.max(0L, positionMs);
            anchorNanos = System.nanoTime();
            clockStarted = true;
        }
        playbackReady = true;
    }

    @Override
    public boolean isPlaybackClockStarted() {
        return clockStarted;
    }

    @Override
    public boolean isPlaybackReady() {
        return playbackReady;
    }

    @Override
    public boolean isReconnecting() {
        return recoveryQueued.get();
    }

    @Override
    public void clientTick() {
        maybeRecoverStall();
    }

    @Override
    public synchronized void close() {
        lifecycleGeneration++;
        clockStarted = false;
        playbackReady = false;
        firstFrameReceived = false;
        nativeDurationMs = -1L;
        nativePositionMs = -1L;
        nativePositionObservedNanos = 0L;
        nativePlaying = false;
        nativePauseState = null;
        lastFramePositionMs = -1L;
        lastFrameNanos = 0L;
        nativeSeekPending = false;
        playing = false;
        waitingForClients = false;
        EmbeddedMediaPlayer playerToRelease = mediaPlayer;
        MediaPlayerFactory factoryToRelease = factory;
        mediaPlayer = null;
        factory = null;
        executeVlc(frameBuffer::clear);
        releaseAsync(playerToRelease, factoryToRelease);
        screenTexture.scheduleClose();
    }

    @Override
    public synchronized void dispose() {
        close();
        vlcExecutor.shutdown();
    }

    private void applyVlcState(long stateGeneration, long seekPositionMs,
                               boolean seek, boolean pause) {
        if (!isCurrent(stateGeneration)) {
            return;
        }
        if (seek) {
            frameBuffer.clear();
            lastFramePositionMs = seekPositionMs;
            nativeSeekPending = true;
            nativePositionMs = seekPositionMs;
            nativePositionObservedNanos = System.nanoTime();
        }
        EmbeddedMediaPlayer player = mediaPlayer;
        if (player == null || !isCurrent(stateGeneration)) {
            return;
        }
        if (seek) {
            player.controls().setTime(seekPositionMs);
        }
        Boolean appliedPause = nativePauseState;
        if (appliedPause == null || appliedPause != pause) {
            player.controls().setPause(pause);
            nativePauseState = pause;
        }
    }

    private void maybeRecoverStall() {
        long now = System.nanoTime();
        long lastFrame = lastFrameNanos;
        if (!playing || clientPaused || mediaPlayer == null || !firstFrameReceived
                || lastFrame <= 0L
                || now - lastFrame < TimeUnit.MILLISECONDS.toNanos(STALL_TIMEOUT_MS)
                || now - lastRecoveryNanos < TimeUnit.MILLISECONDS.toNanos(RECOVERY_COOLDOWN_MS)) {
            return;
        }
        long duration = durationMs();
        long position = positionMs();
        if (!liveStream && duration > 0L && position >= Math.max(0L, duration - 1_500L)
                && !nativePlaying) {
            return;
        }
        if (!recoveryQueued.compareAndSet(false, true)) {
            return;
        }
        long expectedGeneration = lifecycleGeneration;
        lastRecoveryNanos = now;
        executeVlc(() -> {
            try {
                restartAfterStall(expectedGeneration);
            } finally {
                recoveryQueued.set(false);
            }
        });
    }

    private void restartAfterStall(long expectedGeneration) {
        EmbeddedMediaPlayer oldPlayer;
        MediaPlayerFactory oldFactory;
        String videoId;
        String videoUrl;
        String audioUrl;
        String requestHeaders;
        String cookie;
        long position;
        boolean shouldPlay;
        boolean shouldWait;
        synchronized (this) {
            if (expectedGeneration != lifecycleGeneration || !playing || clientPaused
                    || mediaVideoUrl == null || mediaVideoUrl.isBlank()) {
                return;
            }
            videoId = mediaVideoId;
            videoUrl = mediaVideoUrl;
            audioUrl = mediaAudioUrl;
            requestHeaders = mediaRequestHeaders;
            cookie = mediaCookie;
            position = positionMs();
            shouldPlay = playing;
            shouldWait = waitingForClients;
            oldPlayer = mediaPlayer;
            oldFactory = factory;
            mediaPlayer = null;
            factory = null;
            lifecycleGeneration++;
            frameBuffer.clear();
            playbackReady = false;
            firstFrameReceived = false;
            lastFrameNanos = 0L;
            nativePlaying = false;
            nativePositionMs = liveStream ? -1L : position;
            nativePositionObservedNanos = System.nanoTime();
            nativeSeekPending = !liveStream;
            anchorPositionMs = position;
            anchorNanos = System.nanoTime();
            clockStarted = false;
        }
        Main.LOGGER.warn("VLCJ video output stalled; restarting decoder: session={}, position={} ms",
                sessionId, position);
        releaseAsync(oldPlayer, oldFactory);
        long restartGeneration = lifecycleGeneration;
        startVlc(restartGeneration, videoId, videoUrl, audioUrl, requestHeaders, cookie);
        synchronized (this) {
            playing = shouldPlay;
            waitingForClients = shouldWait;
        }
    }

    private boolean isCurrent(long generation) {
        return lifecycleGeneration == generation;
    }

    private void executeVlc(Runnable task) {
        try {
            vlcExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static void releaseAsync(EmbeddedMediaPlayer player, MediaPlayerFactory factory) {
        if (player != null || factory != null) {
            RELEASE_EXECUTOR.execute(() -> releaseVlc(player, factory));
        }
    }

    private void submitFrame(MediaPlayer player, ByteBuffer[] buffers, BufferFormat format) {
        if (player != mediaPlayer) {
            return;
        }
        if (buffers == null || buffers.length == 0 || format == null
                || format.getWidth() <= 0 || format.getHeight() <= 0) {
            return;
        }
        ByteBuffer source = buffers[0].duplicate();
        int width = format.getWidth();
        int height = format.getHeight();
        int sourceStride = format.getPitches()[0];
        byte[] frame = frameBuffer.acquire(width * height * 4);
        try {
            for (int y = 0; y < height; y++) {
                int rowStart = y * sourceStride;
                for (int x = 0; x < width; x++) {
                    int sourceOffset = rowStart + x * 4;
                    int targetOffset = (y * width + x) * 4;
                    frame[targetOffset] = source.get(sourceOffset + 2);
                    frame[targetOffset + 1] = source.get(sourceOffset + 1);
                    frame[targetOffset + 2] = source.get(sourceOffset);
                    frame[targetOffset + 3] = (byte) 0xFF;
                }
            }
        } catch (RuntimeException conversionException) {
            frameBuffer.release(frame);
            Main.LOGGER.warn("VLCJ video frame conversion failed; dropping frame: session={}",
                    sessionId, conversionException);
            return;
        }
        long position = nextFramePositionMs();
        lastFrameNanos = System.nanoTime();
        if (nativePositionMs < 0L && position >= 0L) {
            nativePositionMs = position;
            nativePositionObservedNanos = lastFrameNanos;
        }
        frameBuffer.submit(new VideoFrameBuffer.DecodedFrame(width, height, position,
                pixelFormat, frame));
        if (!firstFrameReceived) {
            firstFrameReceived = true;
            Main.LOGGER.info("VLCJ produced the first video frame: session={}, size={}x{}",
                    sessionId, width, height);
        }
    }

    private long nextFramePositionMs() {
        long now = System.nanoTime();
        long candidate;
        if (!liveStream && waitingForClients) {
            // Keep frames decoded during preload on the same timeline as the
            // authoritative target so the first frame can establish readiness
            // without importing LibVLC's buffering delay into playback time.
            candidate = anchorPositionMs;
        } else {
            candidate = liveStream
                    ? livePositionAt(now) : estimatedNativePositionMs(now);
        }
        if (candidate < 0L) {
            candidate = 0L;
        }
        synchronized (this) {
            if (lastFramePositionMs >= 0L && candidate < lastFramePositionMs) {
                candidate = lastFramePositionMs;
            }
            lastFramePositionMs = candidate;
            return candidate;
        }
    }

    private long livePositionAt(long nowNanos) {
        if (!clockStarted || clientPaused || (!playing && !waitingForClients)) {
            return anchorPositionMs;
        }
        return anchorPositionMs + TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, nowNanos - anchorNanos));
    }

    private long estimatedNativePositionMs(long nowNanos) {
        long base = nativePositionMs;
        if (base < 0L) {
            if (!clockStarted || clientPaused || (!playing && !waitingForClients)) {
                return -1L;
            }
            return anchorPositionMs + TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0L, nowNanos - anchorNanos));
        }
        if (!nativePlaying || clientPaused || (!playing && !waitingForClients)
                || nativePositionObservedNanos <= 0L) {
            return base;
        }
        return base + TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, nowNanos - nativePositionObservedNanos));
    }

    private static boolean isLikelyLiveStreamUrl(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(mediaUrl.trim());
            String scheme = uri.getScheme();
            if (scheme != null) {
                switch (scheme.toLowerCase(Locale.ROOT)) {
                    case "rtmp", "rtmps", "rtsp", "rtsps", "srt", "udp", "rtp", "rist",
                            "tcp" -> {
                        return true;
                    }
                    default -> {
                    }
                }
            }
            String path = uri.getPath();
            if (path == null) {
                return false;
            }
            String normalizedPath = path.toLowerCase(Locale.ROOT);
            return normalizedPath.endsWith(".m3u8") || normalizedPath.endsWith(".m3u");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private List<String> mediaOptions(MediaRequestOptions requestOptions) {
        List<String> options = new ArrayList<>();
        if (liveStream) {
            options.add(":http-reconnect=1");
            options.add(":network-caching=1000");
        }
        for (String line : requestOptions.headers().split("\\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (name.equalsIgnoreCase("User-Agent")) {
                options.add(":http-user-agent=" + value);
            } else if (name.equalsIgnoreCase("Referer")) {
                options.add(":http-referrer=" + value);
            }
        }
        if (!requestOptions.cookie().isBlank()) {
            options.add(":http-cookie=" + requestOptions.cookie());
        }
        return options;
    }

    private static void releaseVlc(EmbeddedMediaPlayer player, MediaPlayerFactory factory) {
        if (player != null) {
            try {
                player.controls().stop();
            } catch (Throwable stopException) {
                Main.LOGGER.debug("VLCJ media player stop failed", stopException);
            }
            try {
                player.release();
            } catch (Throwable releaseException) {
                Main.LOGGER.debug("VLCJ media player release failed", releaseException);
            }
        }
        if (factory != null) {
            try {
                factory.release();
            } catch (Throwable releaseException) {
                Main.LOGGER.debug("VLCJ factory release failed", releaseException);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean hasLibVlc(Path directory) {
        return Files.isRegularFile(directory.resolve("libvlc.dll"))
                && Files.isRegularFile(directory.resolve("libvlccore.dll"));
    }

    private static List<Path> vlcDirectoryCandidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        addVlcPath(candidates, System.getProperty("video_synchronizer.vlcPath"));
        addVlcPath(candidates, System.getenv("VLC_HOME"));
        addVlcPath(candidates, System.getenv("VLC_INSTALL_DIR"));
        addVlcPath(candidates, System.getenv("VLC_PATH"));
        addVlcPath(candidates, join(System.getenv("ProgramFiles"), "VideoLAN", "VLC"));
        addVlcPath(candidates, join(System.getenv("ProgramW6432"), "VideoLAN", "VLC"));
        addVlcPath(candidates, join(System.getenv("ProgramFiles(x86)"), "VideoLAN", "VLC"));
        addVlcPath(candidates, join(System.getenv("LOCALAPPDATA"), "Programs", "VideoLAN", "VLC"));
        addVlcPath(candidates, join(System.getenv("LOCALAPPDATA"), "VideoLAN", "VLC"));
        return new ArrayList<>(candidates);
    }

    private static void addVlcPath(Set<Path> candidates, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            Path path = Paths.get(value).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                candidates.add(path);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static String join(String parent, String... children) {
        if (parent == null || parent.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(parent);
            for (String child : children) {
                path = path.resolve(child);
            }
            return path.toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static long positiveLongProperty(String name, long fallback) {
        long value = Long.getLong(name, fallback);
        return value > 0L ? value : fallback;
    }

    private static void setJnaLibraryPath(String candidate, String originalJnaPath) {
        String value = originalJnaPath == null || originalJnaPath.isBlank()
                ? candidate : candidate + File.pathSeparator + originalJnaPath;
        System.setProperty("jna.library.path", value);
    }

    private static boolean tryDirectNativeLoad(String directory) {
        try {
            com.sun.jna.NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), directory);
            com.sun.jna.NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), directory);
            String pluginPath = Paths.get(directory, "plugins").toString();
            try {
                uk.co.caprica.vlcj.binding.lib.LibC.INSTANCE
                        ._putenv("VLC_PLUGIN_PATH=" + pluginPath);
            } catch (Throwable pluginException) {
                Main.LOGGER.debug("Could not set VLC_PLUGIN_PATH for {}", directory, pluginException);
            }
            MediaPlayerFactory factory = new MediaPlayerFactory((NativeDiscovery) null);
            factory.release();
            return true;
        } catch (Throwable directException) {
            Main.LOGGER.debug("Direct LibVLC load failed for {}", directory, directException);
            return false;
        }
    }
}
