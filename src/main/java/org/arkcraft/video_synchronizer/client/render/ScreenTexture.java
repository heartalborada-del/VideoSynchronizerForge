package org.arkcraft.video_synchronizer.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.arkcraft.video_synchronizer.client.ClientVideoState;
import org.arkcraft.video_synchronizer.client.player.VideoFrameBuffer;
import org.arkcraft.video_synchronizer.Main;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class ScreenTexture {
    public static final ScreenTexture INSTANCE = new ScreenTexture();
    private static final int PIXEL_BUFFER_COUNT = 2;
    private static final long DEBUG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);

    private DynamicTexture texture;
    private ResourceLocation location;
    private int width;
    private int height;
    private final int[] pixelBuffers = new int[PIXEL_BUFFER_COUNT];
    private int pixelBufferIndex;
    private int pixelBufferSize;
    private boolean pixelBufferUpload;
    private ByteBuffer directUploadBuffer;
    private long statsStartNanos;
    private long statsRenderTicks;
    private long statsEmptyTicks;
    private long statsUploadedFrames;
    private long statsPixelBufferUploads;
    private long statsDirectUploads;
    private long statsUploadNanos;
    private long statsMaximumUploadNanos;
    private long statsFirstPositionMs;
    private long statsLastPositionMs;
    private VideoFrameBuffer.Stats previousBufferStats;

    private ScreenTexture() {
    }

    public void update() {
        RenderSystem.assertOnRenderThread();
        long now = System.nanoTime();
        VideoFrameBuffer.Stats bufferStats = VideoFrameBuffer.INSTANCE.stats();
        if (statsStartNanos == 0L && (texture != null || bufferStats.pendingFrame())) {
            resetDebugInterval(now, bufferStats);
            Main.LOGGER.debug("Video render diagnostics started (texturePresent={}, pendingFrame={})",
                    texture != null, bufferStats.pendingFrame());
        }
        if (statsStartNanos != 0L) {
            statsRenderTicks++;
        }
        VideoFrameBuffer.DecodedFrame frame = VideoFrameBuffer.INSTANCE.take();
        if (frame != null) {
            long uploadStart = System.nanoTime();
            upload(frame);
            long uploadNanos = System.nanoTime() - uploadStart;
            if (statsUploadedFrames == 0L) {
                statsFirstPositionMs = frame.positionMs();
            }
            statsUploadedFrames++;
            statsLastPositionMs = frame.positionMs();
            statsUploadNanos += uploadNanos;
            statsMaximumUploadNanos = Math.max(statsMaximumUploadNanos, uploadNanos);
        } else if (statsStartNanos != 0L) {
            statsEmptyTicks++;
        }
        if (statsStartNanos != 0L) {
            logDebugInterval(now, VideoFrameBuffer.INSTANCE.stats());
        }
    }

    @Nullable
    public ResourceLocation get() {
        RenderSystem.assertOnRenderThread();
        return location;
    }

    public void close() {
        RenderSystem.assertOnRenderThread();
        if (texture != null || statsStartNanos != 0L) {
            Main.LOGGER.debug("Closing video texture {}x{} (PBO={}, bufferStats={})",
                    width, height, pixelBufferUpload, VideoFrameBuffer.INSTANCE.stats());
        }
        VideoFrameBuffer.INSTANCE.clear();
        releaseTexture();
        statsStartNanos = 0L;
        previousBufferStats = null;
    }

    public void scheduleClose() {
        if (RenderSystem.isOnRenderThread()) {
            close();
        } else {
            RenderSystem.recordRenderCall(this::close);
        }
    }

    public float aspectRatio() {
        return height == 0 ? 16.0F / 9.0F : width / (float) height;
    }

    private void upload(VideoFrameBuffer.DecodedFrame frame) {
        try {
            boolean createdTexture = false;
            if (texture == null || width != frame.width() || height != frame.height()) {
                releaseTexture();
                width = frame.width();
                height = frame.height();
                texture = new DynamicTexture(width, height, false);
                location = Minecraft.getInstance().getTextureManager()
                        .register("video_synchronizer_screen", texture);
                createPixelBuffers(frame.rgba().length);
                createdTexture = true;
                Main.LOGGER.info("Created dynamic video texture {}x{}", width, height);
            }

            if (createdTexture) {
                uploadDirect(frame.rgba());
                ClientVideoState.onFrameRendered(frame.positionMs());
                return;
            }
            if (pixelBufferUpload && uploadWithPixelBuffer(frame.rgba())) {
                ClientVideoState.onFrameRendered(frame.positionMs());
                return;
            }
            if (pixelBufferUpload) {
                deletePixelBuffers();
                Main.LOGGER.warn("Mapped video texture upload is unavailable; using direct upload");
            }
            uploadDirect(frame.rgba());
            ClientVideoState.onFrameRendered(frame.positionMs());
        } finally {
            VideoFrameBuffer.INSTANCE.release(frame);
        }
    }

    private boolean uploadWithPixelBuffer(byte[] rgba) {
        int buffer = pixelBuffers[pixelBufferIndex];
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
        try {
            ByteBuffer mapped = GL30.glMapBufferRange(
                    GL21.GL_PIXEL_UNPACK_BUFFER, 0L, pixelBufferSize,
                    GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT);
            if (mapped == null) {
                Main.LOGGER.debug("Video PBO {} map returned null (size={} bytes)",
                        buffer, pixelBufferSize);
                return false;
            }
            mapped.put(rgba);
            if (!GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER)) {
                Main.LOGGER.debug("Video PBO {} unmap reported corrupted contents", buffer);
                return false;
            }

            texture.bind();
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            pixelBufferIndex = (pixelBufferIndex + 1) % PIXEL_BUFFER_COUNT;
            statsPixelBufferUploads++;
            return true;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }
    }

    private void uploadDirect(byte[] rgba) {
        if (texture == null) {
            return;
        }
        if (directUploadBuffer == null || directUploadBuffer.capacity() != rgba.length) {
            releaseDirectUploadBuffer();
            directUploadBuffer = MemoryUtil.memAlloc(rgba.length);
            Main.LOGGER.debug("Allocated direct video upload buffer (capacity={} bytes)",
                    rgba.length);
        }
        directUploadBuffer.clear();
        directUploadBuffer.put(rgba);
        directUploadBuffer.flip();
        texture.bind();
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, directUploadBuffer);
        statsDirectUploads++;
    }

    private void createPixelBuffers(int frameSize) {
        pixelBufferSize = frameSize;
        pixelBufferIndex = 0;
        for (int index = 0; index < pixelBuffers.length; index++) {
            pixelBuffers[index] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pixelBuffers[index]);
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, frameSize, GL15.GL_STREAM_DRAW);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        pixelBufferUpload = true;
        Main.LOGGER.debug("Allocated {} video PBOs {} with {} bytes each",
                PIXEL_BUFFER_COUNT, Arrays.toString(pixelBuffers), frameSize);
    }

    private void deletePixelBuffers() {
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        if (pixelBufferUpload) {
            Main.LOGGER.debug("Deleting video PBOs {} (size={} bytes, nextIndex={})",
                    Arrays.toString(pixelBuffers), pixelBufferSize, pixelBufferIndex);
        }
        for (int index = 0; index < pixelBuffers.length; index++) {
            if (pixelBuffers[index] != 0) {
                GL15.glDeleteBuffers(pixelBuffers[index]);
                pixelBuffers[index] = 0;
            }
        }
        pixelBufferIndex = 0;
        pixelBufferSize = 0;
        pixelBufferUpload = false;
    }

    private void logDebugInterval(long now, VideoFrameBuffer.Stats bufferStats) {
        long elapsedNanos = now - statsStartNanos;
        if (elapsedNanos < DEBUG_INTERVAL_NANOS) {
            return;
        }
        if (statsUploadedFrames == 0L && bufferStats.equals(previousBufferStats)) {
            resetDebugInterval(now, bufferStats);
            return;
        }
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0D;
        double uploadFps = statsUploadedFrames / elapsedSeconds;
        double averageUploadMs = statsUploadedFrames == 0L ? 0.0D
                : statsUploadNanos / 1_000_000.0D / statsUploadedFrames;
        double maximumUploadMs = statsMaximumUploadNanos / 1_000_000.0D;
        long mediaAdvanceMs = statsUploadedFrames > 1L
                ? statsLastPositionMs - statsFirstPositionMs : 0L;
        Main.LOGGER.debug("Video render stats: {}x{}, ticks={}, uploads={} ({} fps), "
                        + "emptyTicks={}, media={}..{} ms (advance={} ms), PBO={}, direct={}, "
                        + "uploadCpuAvg={} ms, uploadCpuMax={} ms",
                width, height, statsRenderTicks, statsUploadedFrames, uploadFps,
                statsEmptyTicks, statsFirstPositionMs, statsLastPositionMs, mediaAdvanceMs,
                statsPixelBufferUploads, statsDirectUploads, averageUploadMs, maximumUploadMs);
        if (previousBufferStats != null) {
            Main.LOGGER.debug("Video frame buffer stats: submitted=+{} (total {}), "
                            + "replaced=+{} (total {}), taken=+{} (total {}), "
                            + "allocated=+{} (total {}), reused=+{} (total {}), "
                            + "released=+{} (total {}), discarded=+{} (total {}), "
                            + "cleared=+{} (total {}), pending={}, pooled={}",
                    bufferStats.submittedFrames() - previousBufferStats.submittedFrames(),
                    bufferStats.submittedFrames(),
                    bufferStats.replacedFrames() - previousBufferStats.replacedFrames(),
                    bufferStats.replacedFrames(),
                    bufferStats.takenFrames() - previousBufferStats.takenFrames(),
                    bufferStats.takenFrames(),
                    bufferStats.allocatedArrays() - previousBufferStats.allocatedArrays(),
                    bufferStats.allocatedArrays(),
                    bufferStats.reusedArrays() - previousBufferStats.reusedArrays(),
                    bufferStats.reusedArrays(),
                    bufferStats.releasedFrames() - previousBufferStats.releasedFrames(),
                    bufferStats.releasedFrames(),
                    bufferStats.discardedArrays() - previousBufferStats.discardedArrays(),
                    bufferStats.discardedArrays(),
                    bufferStats.clearedFrames() - previousBufferStats.clearedFrames(),
                    bufferStats.clearedFrames(), bufferStats.pendingFrame(),
                    bufferStats.pooledArrays());
        }
        resetDebugInterval(now, bufferStats);
    }

    private void resetDebugInterval(long now, VideoFrameBuffer.Stats bufferStats) {
        statsStartNanos = now;
        statsRenderTicks = 0L;
        statsEmptyTicks = 0L;
        statsUploadedFrames = 0L;
        statsPixelBufferUploads = 0L;
        statsDirectUploads = 0L;
        statsUploadNanos = 0L;
        statsMaximumUploadNanos = 0L;
        statsFirstPositionMs = 0L;
        statsLastPositionMs = 0L;
        previousBufferStats = bufferStats;
    }

    private void releaseTexture() {
        deletePixelBuffers();
        releaseDirectUploadBuffer();
        if (location != null) {
            Minecraft.getInstance().getTextureManager().release(location);
        }
        texture = null;
        location = null;
        width = 0;
        height = 0;
    }

    private void releaseDirectUploadBuffer() {
        if (directUploadBuffer != null) {
            Main.LOGGER.debug("Freeing direct video upload buffer (capacity={} bytes)",
                    directUploadBuffer.capacity());
            MemoryUtil.memFree(directUploadBuffer);
            directUploadBuffer = null;
        }
    }
}
