package org.arkcraft.video_synchronizer.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.network.VideoProgressMessage;
import org.arkcraft.video_synchronizer.network.VideoResyncMessage;
import org.arkcraft.video_synchronizer.network.VideoStartMessage;
import org.arkcraft.video_synchronizer.network.VideoStateMessage;
import org.arkcraft.video_synchronizer.network.VideoStopMessage;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Client-side bridge between the network and whichever video player the mod uses. */
public final class ClientVideoState {
    private static final long HARD_SEEK_THRESHOLD_MS = 750L;
    private static final int REPORT_INTERVAL_TICKS = 10;
    private static final long PROGRESS_SWITCH_DISPLAY_NANOS = TimeUnit.SECONDS.toNanos(2L);

    private static PlaybackAdapter adapter;
    private static String sessionId;
    private static String videoId;
    private static String url;
    private static long durationMs;
    private static long positionMs;
    private static boolean playing;
    private static long revision;
    private static int ticksSinceReport;
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
            adapter.open(videoId, url, durationMs);
            adapter.applyServerState(positionMs, playing, true);
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
        url = message.url();
        durationMs = message.durationMs();
        positionMs = clampToDuration(message.positionMs());
        playing = message.playing();
        revision = message.revision();
        ticksSinceReport = REPORT_INTERVAL_TICKS;
        Main.LOGGER.debug("Accepted video start: session={}, videoId={}, position={} ms, "
                        + "duration={} ms, playing={}, revision={}, sameSession={}",
                sessionId, videoId, positionMs, durationMs, playing, revision, sameSession);
        if (adapter != null) {
            if (!sameSession) {
                adapter.open(videoId, url, durationMs);
            }
            adapter.applyServerState(positionMs, playing, true);
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
        long serverPosition = clampToDuration(message.positionMs());
        long clientPosition = adapter == null ? positionMs : clampToDuration(adapter.positionMs());
        boolean hardSeek = message.hardSeek()
                || (adapter != null && adapter.isPlaybackClockStarted()
                && Math.abs(serverPosition - clientPosition) >= HARD_SEEK_THRESHOLD_MS);
        if (hardSeek && adapter != null && adapter.isPlaybackClockStarted()
                && clientPosition != serverPosition) {
            showProgressSwitch(clientPosition, serverPosition);
        }
        positionMs = serverPosition;
        playing = message.playing();
        revision = message.revision();
        if (adapter != null) {
            adapter.applyServerState(positionMs, playing, hardSeek);
        }
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
        url = null;
        durationMs = 0L;
        positionMs = 0L;
        playing = false;
        revision = 0L;
        ticksSinceReport = 0;
        clearProgressOverlay();
        ClientScreenTarget.clear();
    }

    public static void clientTick() {
        if (sessionId == null) {
            return;
        }
        updateProgressOverlay();
        if (++ticksSinceReport < REPORT_INTERVAL_TICKS) {
            return;
        }
        ticksSinceReport = 0;
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
        VideoNetwork.CHANNEL.sendToServer(new VideoProgressMessage(
                sessionId, currentPosition, currentDuration, currentPlaying));
    }

    public static void setClientPaused(boolean paused) {
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

    public static void reset() {
        Main.LOGGER.debug("Resetting client video state (session={})", sessionId);
        if (adapter != null) {
            adapter.close();
        }
        sessionId = null;
        videoId = null;
        url = null;
        durationMs = 0L;
        positionMs = 0L;
        playing = false;
        revision = 0L;
        ticksSinceReport = 0;
        clearProgressOverlay();
        ClientScreenTarget.clear();
    }

    private static void updateProgressOverlay() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
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
        ChatFormatting stateColor = currentPlaying ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
        Component overlay = Component.empty()
                .append(Component.translatable(currentPlaying
                                ? "overlay.video_synchronizer.progress_playing"
                                : "overlay.video_synchronizer.progress_paused")
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
        void open(String videoId, String mediaUrl, long durationMs);

        void applyServerState(long positionMs, boolean playing, boolean hardSeek);

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

        /** True while an old stream remains visible during a prepared hard seek. */
        default boolean isPreparingSeek() {
            return false;
        }

        void close();
    }
}
