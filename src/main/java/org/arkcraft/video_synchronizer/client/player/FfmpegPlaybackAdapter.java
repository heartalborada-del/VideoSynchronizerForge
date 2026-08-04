package org.arkcraft.video_synchronizer.client.player;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.client.ClientVideoState;
import org.arkcraft.video_synchronizer.client.render.ScreenTexture;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    public static final FfmpegPlaybackAdapter INSTANCE = new FfmpegPlaybackAdapter();
    private static final int MAX_SOURCE_DIMENSION = 4096;
    private static final long MAX_SOURCE_PIXELS = 4096L * 2160L;
    private static final int MAX_OUTPUT_WIDTH = positiveIntegerProperty(
            "video_synchronizer.maxVideoWidth", 1920);
    private static final int MAX_OUTPUT_HEIGHT = positiveIntegerProperty(
            "video_synchronizer.maxVideoHeight", 1080);
    private static final boolean SCALE_VIDEO = Boolean.parseBoolean(
            System.getProperty("video_synchronizer.scaleVideo", "true"));
    private static final double MAX_OUTPUT_FPS = 60.0D;
    private static final long DEBUG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final int PROBE_TIMEOUT_SECONDS = positiveIntegerProperty(
            "video_synchronizer.probeTimeoutSeconds", 60);
    private static final String NETWORK_TIMEOUT_US = "15000000";
    private static final String HTTP_SHORT_SEEK_SIZE = "1048576";
    private static final int MAX_FFMPEG_ERROR_LENGTH = 8_192;
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 2;
    private static final int AUDIO_FRAME_SIZE = AUDIO_CHANNELS * Short.BYTES;
    private static final int AUDIO_CHUNK_FRAMES = AUDIO_SAMPLE_RATE / 50;
    private static final int AUDIO_BUFFER_FRAMES = AUDIO_SAMPLE_RATE / 10;
    private static final int AUDIO_READ_CANCELLED = -1;
    private static final long SEEK_PREPARE_TIMEOUT_MS = 5_000L;
    private static final long SEEK_REPLACE_THRESHOLD_MS = 2_000L;

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
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong requestedSeekMs = new AtomicLong(-1L);
    private final AtomicLong seekPreparationGeneration = new AtomicLong();
    private final AudioPlayback audioPlayback = new AudioPlayback();

    private volatile Process process;
    private volatile boolean decoderProcess;
    private volatile Process pendingVideoProcess;
    private volatile VideoMetadata activeMetadata;
    private volatile String activeMediaUrl;
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
    private volatile long anchorPositionMs;
    private volatile long anchorNanos;
    private volatile long decodedPositionMs;
    private volatile boolean playing;
    private volatile boolean clientPaused;
    private volatile boolean clockStarted;

    private FfmpegPlaybackAdapter() {
    }

    @Override
    public synchronized void open(String videoId, String mediaUrl, long durationMs) {
        long sessionGeneration = generation.incrementAndGet();
        Main.LOGGER.debug("Opening video decoder: generation={}, videoId={}, duration={} ms, "
                        + "scaleVideo={}, outputLimit={}x{}, maxFps={}, hardware={}, cudaScale={}",
                sessionGeneration, videoId, durationMs, SCALE_VIDEO, MAX_OUTPUT_WIDTH,
                MAX_OUTPUT_HEIGHT, MAX_OUTPUT_FPS,
                System.getProperty("video_synchronizer.ffmpegHardware", "true"),
                System.getProperty("video_synchronizer.ffmpegCudaScale", "false"));
        destroyProcess();
        audioPlayback.close();
        VideoFrameBuffer.INSTANCE.clear();
        ScreenTexture.INSTANCE.scheduleClose();
        this.durationMs = durationMs;
        this.anchorPositionMs = 0L;
        this.anchorNanos = System.nanoTime();
        this.decodedPositionMs = 0L;
        this.playing = false;
        this.clientPaused = false;
        this.clockStarted = false;
        this.activeMetadata = null;
        this.activeMediaUrl = mediaUrl;
        this.preferredDecodeMode = null;
        cancelPreparedSeek();
        this.requestedSeekMs.set(0L);
        executor.execute(() -> runSession(sessionGeneration, mediaUrl));
    }

    @Override
    public synchronized void applyServerState(long positionMs, boolean playing, boolean hardSeek) {
        long boundedPosition = clampToDuration(positionMs);
        boolean playbackStateChanged = this.playing != playing;
        if (hardSeek && canPrepareSeek()) {
            if (playbackStateChanged) {
                this.anchorPositionMs = positionMs();
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
            VideoFrameBuffer.INSTANCE.clear();
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
    public boolean isPlaying() {
        return playing;
    }

    @Override
    public synchronized void setClientPaused(boolean paused) {
        if (clientPaused == paused) {
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
        Main.LOGGER.debug("Local synchronized playback {}", paused ? "paused" : "resumed");
    }

    @Override
    public synchronized void onFrameRendered(long positionMs) {
        if (clockStarted) {
            return;
        }
        anchorPositionMs = clampToDuration(positionMs);
        anchorNanos = System.nanoTime();
        clockStarted = true;
        Main.LOGGER.debug("Client playback clock started at first rendered frame {} ms",
                anchorPositionMs);
    }

    @Override
    public boolean isPlaybackClockStarted() {
        return clockStarted;
    }

    @Override
    public synchronized boolean isPreparingSeek() {
        return pendingSeekPreparation >= 0L || activatedSeekPreparation >= 0L;
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
        requestedSeekMs.set(-1L);
        cancelPreparedSeek();
        destroyProcess();
        audioPlayback.close();
        VideoFrameBuffer.INSTANCE.clear();
        ScreenTexture.INSTANCE.scheduleClose();
        activeMetadata = null;
        activeMediaUrl = null;
        preferredDecodeMode = null;
    }

    private void runSession(long sessionGeneration, String mediaUrl) {
        Main.LOGGER.debug("Video session worker started: generation={}", sessionGeneration);
        try {
            if (generation.get() != sessionGeneration) {
                return;
            }
            VideoMetadata metadata = probe(mediaUrl, sessionGeneration);
            if (generation.get() != sessionGeneration) {
                return;
            }
            if (metadata.durationMs > 0L) {
                durationMs = metadata.durationMs;
            }
            activeMetadata = metadata;
            OutputDimensions output = outputDimensions(metadata.width, metadata.height);
            boolean needsScaling = output.width != metadata.width
                    || output.height != metadata.height;
            boolean limitsFrameRate = metadata.framesPerSecond > MAX_OUTPUT_FPS;
            Main.LOGGER.info("Streaming media decoder opened {}x{} at {} fps "
                            + "(codec {}, profile {}, pixel format {}, bitrate {} bps, "
                            + "output {}x{}, spatial scaling {}, frame rate {}, audio {})",
                    metadata.width, metadata.height, metadata.framesPerSecond,
                    metadata.codecName, metadata.profile, metadata.pixelFormat,
                    metadata.bitRate,
                    output.width, output.height, needsScaling ? "enabled" : "bypassed",
                    limitsFrameRate ? "limited to 60 fps" : "passthrough",
                    metadata.hasAudio ? "enabled" : "not present");
            if (metadata.hasAudio) {
                audioPlayback.open(sessionGeneration, mediaUrl, positionMs());
            }
            long startPosition = requestedSeekMs.getAndSet(-1L);
            if (startPosition < 0L) {
                startPosition = positionMs();
            }
            boolean tryHardware = Boolean.parseBoolean(
                    System.getProperty("video_synchronizer.ffmpegHardware", "true"));
            boolean tryCudaScale = tryHardware && needsScaling && Boolean.parseBoolean(
                    System.getProperty("video_synchronizer.ffmpegCudaScale", "false"));
            PreparedVideoDecoder preparedDecoder = null;

            while (generation.get() == sessionGeneration) {
                DecodeResult result;
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
                        Main.LOGGER.info("FFmpeg CUDA scaling is unavailable; trying generic hardware decoding");
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
                if (result == DecodeResult.HARDWARE_FAILED) {
                    tryHardware = false;
                    Main.LOGGER.warn("FFmpeg hardware decoding failed; falling back to software decoding");
                    result = decode(mediaUrl, metadata, startPosition,
                            sessionGeneration, DecodeMode.SOFTWARE, null);
                }
                Main.LOGGER.debug("Video decode attempt finished: generation={}, modeResult={}, "
                                + "requestedSeek={} ms, active={}",
                        sessionGeneration, result, requestedSeekMs.get(),
                        generation.get() == sessionGeneration);
                if (result != DecodeResult.SEEK_REQUESTED) {
                    break;
                }
                startPosition = requestedSeekMs.getAndSet(-1L);
                if (startPosition < 0L) {
                    startPosition = positionMs();
                }
                preparedDecoder = takePreparedVideoDecoder(startPosition, sessionGeneration);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (generation.get() == sessionGeneration) {
                Main.LOGGER.error("Unable to play synchronized video", exception);
            }
        } finally {
            Main.LOGGER.debug("Video session worker finished: generation={}, active={}, "
                            + "position={} ms, decoded={} ms",
                    sessionGeneration, generation.get() == sessionGeneration,
                    positionMs(), decodedPositionMs);
        }
    }

    private DecodeResult decode(String mediaUrl, VideoMetadata metadata, long startPosition,
                                long sessionGeneration, DecodeMode mode,
                                PreparedVideoDecoder preparedDecoder)
            throws IOException, InterruptedException {
        double outputFps = Math.max(1.0D, Math.min(MAX_OUTPUT_FPS, metadata.framesPerSecond));
        double frameDurationMs = 1000.0D / outputFps;
        OutputDimensions outputDimensions = outputDimensions(metadata.width, metadata.height);
        boolean needsScaling = outputDimensions.width != metadata.width
                || outputDimensions.height != metadata.height;
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
        addNetworkInputOptions(command);
        command.add("-ss");
        command.add(String.format(Locale.ROOT, "%.3f", startPosition / 1000.0D));
        command.add("-i");
        command.add(mediaUrl);
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        List<String> videoFilters = new ArrayList<>();
        if (mode == DecodeMode.CUDA_SCALE) {
            videoFilters.add(String.format(Locale.ROOT,
                    "scale_cuda=%d:%d:format=nv12", outputDimensions.width,
                    outputDimensions.height));
            videoFilters.add("hwdownload");
            videoFilters.add("format=nv12");
            if (limitsFrameRate) {
                videoFilters.add(String.format(Locale.ROOT, "fps=%.3f", outputFps));
            }
            videoFilters.add("format=rgba");
        } else {
            if (limitsFrameRate) {
                videoFilters.add(String.format(Locale.ROOT, "fps=%.3f", outputFps));
            }
            if (needsScaling) {
                videoFilters.add(String.format(Locale.ROOT,
                        "scale=%d:%d:flags=fast_bilinear", outputDimensions.width,
                        outputDimensions.height));
            }
        }
        if (!videoFilters.isEmpty()) {
            command.add("-vf");
            command.add(String.join(",", videoFilters));
        }
        command.add("-pix_fmt");
        command.add("rgba");
        command.add("-vsync");
        command.add("0");
        command.add("-f");
        command.add("rawvideo");
        command.add("pipe:1");

        int frameSize = Math.multiplyExact(
                Math.multiplyExact(outputDimensions.width, outputDimensions.height), 4);
        Process decoder;
        ErrorCollector errors;
        InputStream decoderOutput;
        byte[] preparedFrame = null;
        if (preparedDecoder != null) {
            decoder = preparedDecoder.process;
            errors = preparedDecoder.errors;
            decoderOutput = preparedDecoder.output;
            preparedFrame = preparedDecoder.firstFrame;
            if (!promotePendingVideoProcess(decoder, sessionGeneration)) {
                closePreparedVideoDecoder(preparedDecoder);
                return DecodeResult.ENDED;
            }
        } else {
            decoder = new ProcessBuilder(command).start();
            if (!registerProcess(decoder, sessionGeneration, true)) {
                terminateProcessTree(decoder);
                return DecodeResult.ENDED;
            }
            errors = new ErrorCollector(decoder.getErrorStream());
            errors.start();
            decoderOutput = decoder.getInputStream();
        }
        long decodeStartNanos = System.nanoTime();
        long statsStartNanos = decodeStartNanos;
        long statsDecodedFrames = 0L;
        long statsReadNanos = 0L;
        long statsMaximumReadNanos = 0L;
        long statsPacingNanos = 0L;
        Main.LOGGER.debug("Started FFmpeg video process: pid={}, generation={}, mode={}, "
                        + "start={} ms, output={}x{} @ {} fps, frameBytes={}, scaling={}, "
                        + "fpsFilter={}, filters={}",
                decoder.pid(), sessionGeneration, mode.description, startPosition,
                outputDimensions.width, outputDimensions.height, outputFps, frameSize,
                needsScaling, limitsFrameRate,
                videoFilters.isEmpty() ? "none" : String.join(",", videoFilters));
        long framePosition = startPosition;
        int decodedFrames = 0;
        try (InputStream output = decoderOutput) {
            while (generation.get() == sessionGeneration) {
                if (!waitForVideoPlayback(sessionGeneration)) {
                    return requestedSeekMs.get() >= 0L
                            ? DecodeResult.SEEK_REQUESTED : DecodeResult.ENDED;
                }
                if (requestedSeekMs.get() >= 0L) {
                    return DecodeResult.SEEK_REQUESTED;
                }
                byte[] rgba = preparedFrame != null
                        ? preparedFrame : VideoFrameBuffer.INSTANCE.acquire(frameSize);
                long readStartNanos = System.nanoTime();
                FrameReadResult readResult;
                if (preparedFrame != null) {
                    preparedFrame = null;
                    readResult = FrameReadResult.FRAME;
                } else {
                    readResult = readFrame(output, rgba, sessionGeneration);
                }
                long readNanos = System.nanoTime() - readStartNanos;
                if (readResult != FrameReadResult.FRAME) {
                    Main.LOGGER.debug("Video frame read ended: pid={}, result={}, read={} ms, "
                                    + "framePosition={} ms, decodedFrames={}, seekRequest={} ms",
                            decoder.pid(), readResult, readNanos / 1_000_000.0D,
                            framePosition, decodedFrames, requestedSeekMs.get());
                    VideoFrameBuffer.INSTANCE.release(
                            new VideoFrameBuffer.DecodedFrame(
                                    outputDimensions.width, outputDimensions.height,
                                    framePosition, rgba));
                    if (readResult == FrameReadResult.CANCELLED
                            || requestedSeekMs.get() >= 0L) {
                        return DecodeResult.SEEK_REQUESTED;
                    }
                    if (decodedFrames == 0 && !errors.text().isBlank()) {
                        Main.LOGGER.warn("FFmpeg exited before producing a video frame using {} decoding: {}",
                                mode.description, errors.text());
                    }
                    if (readResult == FrameReadResult.ENDED && decodedFrames > 0) {
                        Main.LOGGER.info("FFmpeg video stream reached EOF at {} ms "
                                        + "(playback clock {} ms, duration {} ms)",
                                decodedPositionMs, positionMs(), durationMs);
                    }
                    break;
                }

                if (!waitForVideoPlayback(sessionGeneration)) {
                    VideoFrameBuffer.INSTANCE.release(
                            new VideoFrameBuffer.DecodedFrame(
                                    outputDimensions.width, outputDimensions.height,
                                    framePosition, rgba));
                    return requestedSeekMs.get() >= 0L
                            ? DecodeResult.SEEK_REQUESTED : DecodeResult.ENDED;
                }

                long pacingStartNanos = System.nanoTime();
                while (generation.get() == sessionGeneration && requestedSeekMs.get() < 0L
                        && framePosition > positionMs() + 75L) {
                    Thread.sleep(5L);
                }
                long pacingNanos = System.nanoTime() - pacingStartNanos;
                if (generation.get() != sessionGeneration || requestedSeekMs.get() >= 0L) {
                    VideoFrameBuffer.INSTANCE.release(
                            new VideoFrameBuffer.DecodedFrame(
                                    outputDimensions.width, outputDimensions.height,
                                    framePosition, rgba));
                    return requestedSeekMs.get() >= 0L ? DecodeResult.SEEK_REQUESTED : DecodeResult.ENDED;
                }
                if (decodedFrames == 0) {
                    preferredDecodeMode = mode;
                    Main.LOGGER.info("FFmpeg produced the first video frame using {} decoding",
                            mode.description);
                    Main.LOGGER.debug("First video frame diagnostics: pid={}, startup={} ms, "
                                    + "read={} ms, pacing={} ms, framePosition={} ms, clock={} ms",
                            decoder.pid(), (System.nanoTime() - decodeStartNanos) / 1_000_000.0D,
                            readNanos / 1_000_000.0D, pacingNanos / 1_000_000.0D,
                            framePosition, positionMs());
                }
                decodedPositionMs = framePosition;
                decodedFrames++;
                VideoFrameBuffer.DecodedFrame frame = new VideoFrameBuffer.DecodedFrame(
                        outputDimensions.width, outputDimensions.height, framePosition, rgba);
                VideoFrameBuffer.INSTANCE.submit(frame);
                statsDecodedFrames++;
                statsReadNanos += readNanos;
                statsMaximumReadNanos = Math.max(statsMaximumReadNanos, readNanos);
                statsPacingNanos += pacingNanos;
                long statsNow = System.nanoTime();
                long statsElapsedNanos = statsNow - statsStartNanos;
                if (statsElapsedNanos >= DEBUG_INTERVAL_NANOS) {
                    double statsElapsedSeconds = statsElapsedNanos / 1_000_000_000.0D;
                    double decodeFps = statsDecodedFrames / statsElapsedSeconds;
                    double throughputMiB = statsDecodedFrames * frameSize
                            / (1024.0D * 1024.0D) / statsElapsedSeconds;
                    double averageReadMs = statsDecodedFrames == 0L ? 0.0D
                            : statsReadNanos / 1_000_000.0D / statsDecodedFrames;
                    long clockPosition = positionMs();
                    VideoFrameBuffer.Stats bufferStats = VideoFrameBuffer.INSTANCE.stats();
                    Main.LOGGER.debug("Video decode stats: pid={}, mode={}, frames={} ({} fps, "
                                    + "{} MiB/s), totalFrames={}, media={} ms, clock={} ms, "
                                    + "drift={} ms, readAvg={} ms, readMax={} ms, pacing={} ms, "
                                    + "bufferPending={}, submitted={}, replaced={}, taken={}",
                            decoder.pid(), mode.description, statsDecodedFrames, decodeFps,
                            throughputMiB, decodedFrames, framePosition, clockPosition,
                            framePosition - clockPosition, averageReadMs,
                            statsMaximumReadNanos / 1_000_000.0D,
                            statsPacingNanos / 1_000_000.0D, bufferStats.pendingFrame(),
                            bufferStats.submittedFrames(), bufferStats.replacedFrames(),
                            bufferStats.takenFrames());
                    statsStartNanos = statsNow;
                    statsDecodedFrames = 0L;
                    statsReadNanos = 0L;
                    statsMaximumReadNanos = 0L;
                    statsPacingNanos = 0L;
                }
                framePosition = startPosition + Math.round(decodedFrames * frameDurationMs);
            }
        } finally {
            terminateProcessTree(decoder);
            errors.await();
            clearProcess(decoder);
            Main.LOGGER.debug("Stopped FFmpeg video process: pid={}, mode={}, frames={}, "
                            + "elapsed={} ms, lastMedia={} ms, clock={} ms, stderrChars={}",
                    decoder.pid(), mode.description, decodedFrames,
                    (System.nanoTime() - decodeStartNanos) / 1_000_000.0D,
                    decodedPositionMs, positionMs(), errors.text().length());
        }
        if (mode != DecodeMode.SOFTWARE && decodedFrames == 0
                && generation.get() == sessionGeneration) {
            return DecodeResult.HARDWARE_FAILED;
        }
        return DecodeResult.ENDED;
    }

    private synchronized boolean canPrepareSeek() {
        return activeMetadata != null && activeMediaUrl != null && decoderProcess;
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
        if (metadata == null || mediaUrl == null) {
            requestImmediateSeek(positionMs);
            return;
        }
        pendingSeekPreparation = preparation;
        pendingSeekPositionMs = positionMs;
        pendingSeekRequestedNanos = System.nanoTime();
        pendingSeekPlaying = playing;
        pendingSeekNeedsAudio = metadata.hasAudio;
        pendingAudioFailed = false;
        Main.LOGGER.debug("Preparing synchronized seek: preparation={}, generation={}, "
                        + "position={} ms, audio={}",
                preparation, sessionGeneration, positionMs, pendingSeekNeedsAudio);
        if (pendingSeekNeedsAudio) {
            audioPlayback.prepareSeek(preparation, sessionGeneration, mediaUrl, positionMs);
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
        VideoCommand videoCommand = createVideoCommand(mediaUrl, metadata, positionMs, mode);
        Process candidate = new ProcessBuilder(videoCommand.command).start();
        if (!registerPendingVideoProcess(candidate, preparation, sessionGeneration)) {
            terminateProcessTree(candidate);
            return null;
        }
        ErrorCollector errors = new ErrorCollector(candidate.getErrorStream());
        errors.start();
        InputStream output = candidate.getInputStream();
        byte[] frame = VideoFrameBuffer.INSTANCE.acquire(videoCommand.frameSize);
        int offset = 0;
        try {
            while (offset < frame.length) {
                if (!isSeekPreparationCurrent(preparation, sessionGeneration)) {
                    return null;
                }
                int read = output.read(frame, offset, frame.length - offset);
                if (read < 0) {
                    return null;
                }
                offset += read;
            }
            preferredDecodeMode = mode;
            Main.LOGGER.debug("Prepared video seek decoder: preparation={}, pid={}, mode={}, "
                            + "position={} ms, frameBytes={}",
                    preparation, candidate.pid(), mode.description, positionMs, frame.length);
            return new PreparedVideoDecoder(preparation, positionMs, mode,
                    candidate, errors, output, frame, videoCommand.outputDimensions);
        } finally {
            if (offset < frame.length) {
                VideoFrameBuffer.INSTANCE.release(new VideoFrameBuffer.DecodedFrame(
                        videoCommand.outputDimensions.width,
                        videoCommand.outputDimensions.height, positionMs, frame));
                output.close();
                terminateProcessTree(candidate);
                errors.await();
                clearPendingVideoProcess(candidate);
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
        long preparation = pendingSeekPreparation;
        long positionMs = pendingSeekPositionMs;
        anchorPositionMs = positionMs;
        anchorNanos = System.nanoTime();
        clockStarted = false;
        VideoFrameBuffer.INSTANCE.clear();
        requestedSeekMs.set(positionMs);
        audioPlayback.activatePreparedSeek(preparation, positionMs,
                pendingSeekNeedsAudio && !pendingAudioFailed);
        destroyDecoderProcess();
        activatedSeekPreparation = preparation;
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
        long positionMs = pendingSeekPositionMs;
        Main.LOGGER.warn("Seek preparation timed out after {} ms; falling back to a hard decoder restart",
                SEEK_PREPARE_TIMEOUT_MS);
        cancelPreparedSeek();
        requestImmediateSeek(positionMs);
    }

    private void requestImmediateSeek(long positionMs) {
        anchorPositionMs = positionMs;
        anchorNanos = System.nanoTime();
        clockStarted = false;
        VideoFrameBuffer.INSTANCE.clear();
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

    private synchronized PreparedVideoDecoder takePreparedVideoDecoder(
            long positionMs, long sessionGeneration) {
        if (preparedVideoDecoder == null
                || preparedVideoDecoder.positionMs != positionMs
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
            if (outputDimensions(metadata.width, metadata.height).width != metadata.width
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

    private VideoCommand createVideoCommand(String mediaUrl, VideoMetadata metadata,
                                            long startPosition, DecodeMode mode) {
        double outputFps = Math.max(1.0D, Math.min(MAX_OUTPUT_FPS, metadata.framesPerSecond));
        OutputDimensions output = outputDimensions(metadata.width, metadata.height);
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
        addNetworkInputOptions(command);
        command.add("-ss");
        command.add(String.format(Locale.ROOT, "%.3f", startPosition / 1000.0D));
        command.add("-i");
        command.add(mediaUrl);
        command.add("-an");
        command.add("-sn");
        command.add("-dn");
        List<String> filters = new ArrayList<>();
        if (mode == DecodeMode.CUDA_SCALE) {
            filters.add(String.format(Locale.ROOT, "scale_cuda=%d:%d:format=nv12",
                    output.width, output.height));
            filters.add("hwdownload");
            filters.add("format=nv12");
            if (limitsFrameRate) {
                filters.add(String.format(Locale.ROOT, "fps=%.3f", outputFps));
            }
            filters.add("format=rgba");
        } else {
            if (limitsFrameRate) {
                filters.add(String.format(Locale.ROOT, "fps=%.3f", outputFps));
            }
            if (needsScaling) {
                filters.add(String.format(Locale.ROOT,
                        "scale=%d:%d:flags=fast_bilinear", output.width, output.height));
            }
        }
        if (!filters.isEmpty()) {
            command.add("-vf");
            command.add(String.join(",", filters));
        }
        command.add("-pix_fmt");
        command.add("rgba");
        command.add("-vsync");
        command.add("0");
        command.add("-f");
        command.add("rawvideo");
        command.add("pipe:1");
        int frameSize = Math.multiplyExact(Math.multiplyExact(output.width, output.height), 4);
        return new VideoCommand(command, output, frameSize);
    }

    private boolean waitForVideoPlayback(long sessionGeneration) throws InterruptedException {
        while (generation.get() == sessionGeneration && requestedSeekMs.get() < 0L
                && (!playing || clientPaused)) {
            if (!playing && durationMs > 0L && positionMs() >= durationMs) {
                return false;
            }
            Thread.sleep(10L);
        }
        return generation.get() == sessionGeneration && requestedSeekMs.get() < 0L;
    }

    private VideoMetadata probe(String mediaUrl, long sessionGeneration)
            throws IOException, InterruptedException {
        long probeStartNanos = System.nanoTime();
        Main.LOGGER.debug("Starting media probe: generation={}, executable={}, timeout={} s",
                sessionGeneration, ffprobeExecutable(), PROBE_TIMEOUT_SECONDS);
        List<String> command = new ArrayList<>();
        command.add(ffprobeExecutable());
        command.add("-v");
        command.add("error");
        addNetworkInputOptions(command);
        command.add("-show_entries");
        command.add("stream=codec_type,codec_name,profile,pix_fmt,width,height,avg_frame_rate,bit_rate:"
                + "format=duration,bit_rate,format_name");
        command.add("-of");
        command.add("json");
        command.add(mediaUrl);
        Process probe = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
        Main.LOGGER.debug("Started ffprobe process: pid={}, generation={}",
                probe.pid(), sessionGeneration);
        if (!registerProcess(probe, sessionGeneration, false)) {
            terminateProcessTree(probe);
            throw new IOException("Video session was cancelled");
        }
        JsonObject root;
        try {
            long waitStartNanos = System.nanoTime();
            long timeoutNanos = TimeUnit.SECONDS.toNanos(PROBE_TIMEOUT_SECONDS);
            long nextProgressLogNanos = waitStartNanos + TimeUnit.SECONDS.toNanos(5L);
            while (probe.isAlive()) {
                if (generation.get() != sessionGeneration) {
                    throw new IOException("Video session was cancelled during media probing");
                }
                long now = System.nanoTime();
                if (now - waitStartNanos >= timeoutNanos) {
                    throw new IOException("ffprobe timed out after " + PROBE_TIMEOUT_SECONDS
                            + " seconds; increase -Dvideo_synchronizer.probeTimeoutSeconds if needed");
                }
                if (now >= nextProgressLogNanos) {
                    Main.LOGGER.debug("Media probe still running: pid={}, generation={}, "
                                    + "elapsed={} ms, timeout={} s",
                            probe.pid(), sessionGeneration,
                            (now - waitStartNanos) / 1_000_000.0D, PROBE_TIMEOUT_SECONDS);
                    nextProgressLogNanos = now + TimeUnit.SECONDS.toNanos(5L);
                }
                probe.waitFor(1L, TimeUnit.SECONDS);
            }
            if (probe.exitValue() != 0) {
                throw new IOException("ffprobe could not read video metadata");
            }
            try (InputStreamReader reader = new InputStreamReader(probe.getInputStream())) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
        } finally {
            if (probe.isAlive()) {
                terminateProcessTree(probe);
            }
            clearProcess(probe);
        }
        if (!root.has("streams") || root.getAsJsonArray("streams").size() == 0) {
            throw new IOException("ffprobe did not find a video stream");
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
            throw new IOException("ffprobe did not find a video stream");
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
        if (width <= 0 || height <= 0
                || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION
                || (long) width * height > MAX_SOURCE_PIXELS) {
            throw new IOException("Unsupported video dimensions: " + width + "x" + height
                    + " (maximum source size is 4K / 4096x2160 pixels)");
        }
        Main.LOGGER.debug("Media probe completed: generation={}, elapsed={} ms, source={}x{}, "
                        + "fps={}, codec={}, profile={}, pixelFormat={}, bitrate={} bps, "
                        + "duration={} ms, audio={}",
                sessionGeneration, (System.nanoTime() - probeStartNanos) / 1_000_000.0D,
                width, height, fps, codecName, profile, pixelFormat, bitRate,
                detectedDurationMs, hasAudio);
        return new VideoMetadata(width, height, fps, detectedDurationMs, hasAudio,
                codecName, profile, pixelFormat, bitRate);
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

    private static void addNetworkInputOptions(List<String> command) {
        command.add("-rw_timeout");
        command.add(NETWORK_TIMEOUT_US);
        command.add("-reconnect");
        command.add("1");
        command.add("-reconnect_on_network_error");
        command.add("1");
        command.add("-reconnect_on_http_error");
        command.add("429,5xx");
        command.add("-reconnect_streamed");
        command.add("1");
        command.add("-reconnect_delay_max");
        command.add("5");
        command.add("-multiple_requests");
        command.add("1");
        command.add("-short_seek_size");
        command.add(HTTP_SHORT_SEEK_SIZE);
        command.add("-user_agent");
        command.add("VideoSynchronizer/1.0");
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

    private FrameReadResult readFrame(InputStream input, byte[] frame,
                                      long sessionGeneration) throws IOException {
        int offset = 0;
        while (offset < frame.length) {
            if (generation.get() != sessionGeneration || requestedSeekMs.get() >= 0L) {
                return FrameReadResult.CANCELLED;
            }
            int read = input.read(frame, offset, frame.length - offset);
            if (read < 0) {
                return FrameReadResult.ENDED;
            }
            offset += read;
        }
        return FrameReadResult.FRAME;
    }

    private static OutputDimensions outputDimensions(int sourceWidth, int sourceHeight) {
        if (!SCALE_VIDEO) {
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
        VideoFrameBuffer.INSTANCE.release(new VideoFrameBuffer.DecodedFrame(
                prepared.outputDimensions.width, prepared.outputDimensions.height,
                prepared.positionMs, prepared.firstFrame));
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
        return System.getProperty("video_synchronizer.ffmpeg", "ffmpeg");
    }

    private static String ffprobeExecutable() {
        String configured = ffmpegExecutable();
        Path path = Path.of(configured);
        Path fileName = path.getFileName();
        if (fileName == null || path.getParent() == null) {
            return configured.toLowerCase(Locale.ROOT).endsWith(".exe") ? "ffprobe.exe" : "ffprobe";
        }
        String probeName = fileName.toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                ? "ffprobe.exe" : "ffprobe";
        return path.getParent().resolve(probeName).toString();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private long clampToDuration(long value) {
        return durationMs > 0L ? clamp(value, 0L, durationMs) : Math.max(0L, value);
    }

    private record VideoMetadata(int width, int height, double framesPerSecond,
                                 long durationMs, boolean hasAudio, String codecName,
                                 String profile, String pixelFormat, long bitRate) {
    }

    private record OutputDimensions(int width, int height) {
    }

    private record VideoCommand(List<String> command, OutputDimensions outputDimensions,
                                int frameSize) {
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

        private PreparedVideoDecoder(long preparation, long positionMs, DecodeMode mode,
                                     Process process, ErrorCollector errors, InputStream output,
                                     byte[] firstFrame, OutputDimensions outputDimensions) {
            this.preparation = preparation;
            this.positionMs = positionMs;
            this.mode = mode;
            this.process = process;
            this.errors = errors;
            this.output = output;
            this.firstFrame = firstFrame;
            this.outputDimensions = outputDimensions;
        }
    }

    private enum FrameReadResult {
        FRAME,
        ENDED,
        CANCELLED
    }

    private enum DecodeResult {
        SEEK_REQUESTED,
        HARDWARE_FAILED,
        ENDED
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

        private Process audioProcess;
        private Process pendingAudioProcess;
        private PreparedAudioDecoder preparedAudioDecoder;
        private long activeGeneration = -1L;
        private String mediaUrl;
        private boolean workerRunning;

        private synchronized void open(long sessionGeneration, String url, long startPositionMs) {
            Main.LOGGER.debug("Opening synchronized audio: generation={}, start={} ms",
                    sessionGeneration, startPositionMs);
            activeGeneration = sessionGeneration;
            mediaUrl = url;
            audioRequestedSeekMs.set(startPositionMs);
            startWorkerLocked();
        }

        private synchronized void seek(long positionMs) {
            if (activeGeneration < 0L) {
                return;
            }
            Main.LOGGER.debug("Seeking synchronized audio: generation={}, position={} ms",
                    activeGeneration, positionMs);
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
                candidate = new ProcessBuilder(command).start();
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
            }
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
        }

        private synchronized void close() {
            if (activeGeneration >= 0L) {
                Main.LOGGER.debug("Closing synchronized audio: generation={}, workerRunning={}, "
                                + "processPresent={}",
                        activeGeneration, workerRunning, audioProcess != null);
            }
            activeGeneration = -1L;
            mediaUrl = null;
            audioRequestedSeekMs.set(-1L);
            destroyAudioProcessLocked();
            cancelPreparedSeek();
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
                while (isActive(sessionGeneration)) {
                    DecodeResult result = decodeAudio(
                            sessionUrl, startPosition, sessionGeneration);
                    if (result != DecodeResult.SEEK_REQUESTED) {
                        break;
                    }
                    startPosition = nextStartPosition();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (LineUnavailableException | IllegalArgumentException exception) {
                if (isActive(sessionGeneration)) {
                    Main.LOGGER.warn("Audio output is unavailable; continuing video without audio: {}",
                            exception.getMessage());
                }
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

        private DecodeResult decodeAudio(String sessionUrl, long startPosition,
                                         long sessionGeneration)
                throws IOException, InterruptedException, LineUnavailableException {
            PreparedAudioDecoder prepared = takePreparedAudioDecoder(
                    startPosition, sessionGeneration);
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
            if (prepared != null) {
                decoder = prepared.process;
                errors = prepared.errors;
                decoderOutput = prepared.output;
                pcm = prepared.firstChunk;
                preparedChunkBytes = prepared.firstChunkBytes;
                if (!promotePendingAudioProcess(decoder, sessionGeneration)) {
                    closePreparedAudioDecoder(prepared);
                    line.close();
                    return DecodeResult.ENDED;
                }
            } else {
                try {
                    decoder = new ProcessBuilder(audioCommand(sessionUrl, startPosition)).start();
                } catch (IOException exception) {
                    line.close();
                    throw exception;
                }
                if (!registerAudioProcess(decoder, sessionGeneration)) {
                    terminateProcessTree(decoder);
                    line.close();
                    return DecodeResult.ENDED;
                }
                errors = new ErrorCollector(decoder.getErrorStream());
                errors.start();
                decoderOutput = decoder.getInputStream();
                pcm = new byte[AUDIO_CHUNK_FRAMES * AUDIO_FRAME_SIZE];
            }
            Main.LOGGER.debug("Started FFmpeg audio process: pid={}, generation={}, start={} ms, "
                            + "format={} Hz/{} channel/s16le, chunkBytes={}, lineBufferBytes={}",
                    decoder.pid(), sessionGeneration, startPosition, AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNELS, AUDIO_CHUNK_FRAMES * AUDIO_FRAME_SIZE,
                    AUDIO_BUFFER_FRAMES * AUDIO_FRAME_SIZE);
            long decodedFrames = 0L;
            long lineBasePositionMs = startPosition;
            boolean submittedAudio = false;
            boolean lineRunning = false;
            long nextVolumeUpdateNanos = 0L;
            float appliedVolume = Float.NaN;
            long statsStartNanos = System.nanoTime();
            long statsBytes = 0L;
            long statsChunks = 0L;
            long statsDiscardedChunks = 0L;
            try (InputStream output = decoderOutput) {
                while (isActive(sessionGeneration)) {
                    if (audioRequestedSeekMs.get() >= 0L) {
                        return DecodeResult.SEEK_REQUESTED;
                    }
                    if (!playing || clientPaused || !clockStarted) {
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
                    if (submittedAudio && !lineRunning) {
                        line.start();
                        lineRunning = true;
                    }

                    int bytesRead;
                    if (preparedChunkBytes > 0) {
                        bytesRead = preparedChunkBytes;
                        preparedChunkBytes = 0;
                    } else {
                        bytesRead = readAudioChunk(output, pcm, sessionGeneration);
                    }
                    if (bytesRead == AUDIO_READ_CANCELLED
                            || audioRequestedSeekMs.get() >= 0L) {
                        return DecodeResult.SEEK_REQUESTED;
                    }
                    if (bytesRead <= 0) {
                        if (!submittedAudio && !errors.text().isBlank()) {
                            Main.LOGGER.warn("FFmpeg exited before producing audio: {}", errors.text());
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
                    long chunkPositionMs = startPosition
                            + decodedFrames * 1000L / AUDIO_SAMPLE_RATE;
                    decodedFrames += framesRead;
                    long chunkEndMs = startPosition
                            + decodedFrames * 1000L / AUDIO_SAMPLE_RATE;

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
                        return DecodeResult.SEEK_REQUESTED;
                    }

                    if (!submittedAudio) {
                        lineBasePositionMs = chunkPositionMs;
                    }
                    long now = System.nanoTime();
                    if (now >= nextVolumeUpdateNanos) {
                        appliedVolume = updateVolume(line, appliedVolume);
                        nextVolumeUpdateNanos = now + TimeUnit.MILLISECONDS.toNanos(250L);
                    }
                    writeAudio(line, pcm, bytesRead);
                    if (!submittedAudio) {
                        submittedAudio = true;
                        line.start();
                        lineRunning = true;
                        Main.LOGGER.info("FFmpeg produced the first synchronized audio samples");
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
                line.stop();
                line.flush();
                line.close();
                terminateProcessTree(decoder);
                errors.await();
                clearAudioProcess(decoder);
                Main.LOGGER.debug("Stopped FFmpeg audio process: pid={}, decodedFrames={}, "
                                + "submitted={}, stderrChars={}",
                        decoder.pid(), decodedFrames, submittedAudio, errors.text().length());
            }
            return DecodeResult.ENDED;
        }

        private SourceDataLine openAudioLine() throws LineUnavailableException {
            AudioFormat format = new AudioFormat(
                    AUDIO_SAMPLE_RATE, 16, AUDIO_CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, AUDIO_BUFFER_FRAMES * AUDIO_FRAME_SIZE);
            return line;
        }

        private int readAudioChunk(InputStream input, byte[] buffer,
                                   long sessionGeneration) throws IOException {
            int offset = 0;
            while (offset < buffer.length) {
                if (!isActive(sessionGeneration)
                        || audioRequestedSeekMs.get() >= 0L) {
                    return AUDIO_READ_CANCELLED;
                }
                int read = input.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    return offset;
                }
                offset += read;
            }
            return offset;
        }

        private void writeAudio(SourceDataLine line, byte[] data, int length) {
            int offset = 0;
            while (offset < length) {
                offset += line.write(data, offset, length - offset);
            }
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
            addNetworkInputOptions(command);
            command.add("-ss");
            command.add(String.format(Locale.ROOT, "%.3f", startPosition / 1000.0D));
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

        private synchronized PreparedAudioDecoder takePreparedAudioDecoder(
                long positionMs, long sessionGeneration) {
            if (!isActiveLocked(sessionGeneration) || preparedAudioDecoder == null
                    || preparedAudioDecoder.positionMs != positionMs) {
                return null;
            }
            PreparedAudioDecoder prepared = preparedAudioDecoder;
            preparedAudioDecoder = null;
            return prepared;
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

        private boolean isActiveLocked(long sessionGeneration) {
            return activeGeneration == sessionGeneration
                    && generation.get() == sessionGeneration;
        }

        private long nextStartPosition() {
            long requested = audioRequestedSeekMs.getAndSet(-1L);
            return requested >= 0L ? requested : positionMs();
        }

        private synchronized void finishWorker(long sessionGeneration) {
            workerRunning = false;
            Main.LOGGER.debug("Audio worker state cleared: generation={}, active={}, seek={} ms",
                    sessionGeneration, isActiveLocked(sessionGeneration),
                    audioRequestedSeekMs.get());
            if (isActiveLocked(sessionGeneration)
                    && audioRequestedSeekMs.get() >= 0L) {
                startWorkerLocked();
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
