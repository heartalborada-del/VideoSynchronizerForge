package org.arkcraft.video_synchronizer.client.player.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Drains an FFmpeg stderr stream without allowing diagnostics to grow without bound. */
public final class FfmpegErrorCollector {
    private static final int MAX_ERROR_LENGTH = 8_192;

    private final InputStream input;
    private final StringBuilder contents = new StringBuilder();
    private final Thread thread;

    public FfmpegErrorCollector(InputStream input) {
        this.input = input;
        this.thread = new Thread(this::read, "VideoSynchronizer-FFmpeg-stderr");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void await() throws InterruptedException {
        thread.join(1_000L);
    }

    public String text() {
        synchronized (contents) {
            return contents.toString().strip().replace('\r', ' ').replace('\n', ' ');
        }
    }

    private void read() {
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[512];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                synchronized (contents) {
                    int remaining = MAX_ERROR_LENGTH - contents.length();
                    if (remaining > 0) {
                        contents.append(buffer, 0, Math.min(count, remaining));
                    }
                }
            }
        } catch (IOException ignored) {
            // The stream normally closes when a seek terminates the decoder process.
        }
    }
}
