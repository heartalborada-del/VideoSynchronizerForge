package org.arkcraft.video_synchronizer.client.player.video;

import org.arkcraft.video_synchronizer.network.model.VideoPixelFormat;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps a small timestamp-ordered frame queue and reuses its backing arrays. */
public final class VideoFrameBuffer {
    private static final int DEFAULT_QUEUE_CAPACITY = 8;
    private static final long FRAME_SELECTION_LEAD_MS = 75L;
    private final int capacity;
    private final Queue<DecodedFrame> frames;
    private final Queue<byte[]> pool = new ArrayDeque<>();
    private final AtomicLong allocatedArrays = new AtomicLong();
    private final AtomicLong reusedArrays = new AtomicLong();
    private final AtomicLong submittedFrames = new AtomicLong();
    private final AtomicLong replacedFrames = new AtomicLong();
    private final AtomicLong skippedFrames = new AtomicLong();
    private final AtomicLong takenFrames = new AtomicLong();
    private final AtomicLong releasedFrames = new AtomicLong();
    private final AtomicLong discardedArrays = new AtomicLong();
    private final AtomicLong clearedFrames = new AtomicLong();

    public VideoFrameBuffer() {
        capacity = positiveIntegerProperty("video_synchronizer.videoFrameQueue", DEFAULT_QUEUE_CAPACITY);
        frames = new ArrayDeque<>(capacity);
    }

    public synchronized byte[] acquire(int size) {
        byte[] candidate;
        while ((candidate = pool.poll()) != null) {
            if (candidate.length == size) {
                reusedArrays.incrementAndGet();
                return candidate;
            }
            discardedArrays.incrementAndGet();
        }
        allocatedArrays.incrementAndGet();
        return new byte[size];
    }

    public synchronized void submit(DecodedFrame frame) {
        submittedFrames.incrementAndGet();
        if (frames.size() >= capacity) {
            DecodedFrame replaced = frames.remove();
            replacedFrames.incrementAndGet();
            release(replaced);
        }
        frames.add(frame);
    }

    /** Returns the newest frame that is due for the supplied playback position. */
    public synchronized DecodedFrame takeForPosition(long targetPositionMs) {
        if (frames.isEmpty()) {
            return null;
        }
        if (targetPositionMs < 0L) {
            DecodedFrame first = frames.remove();
            takenFrames.incrementAndGet();
            return first;
        }
        long duePositionMs = targetPositionMs + FRAME_SELECTION_LEAD_MS;
        if (frames.peek().positionMs() > duePositionMs) {
            return null;
        }
        DecodedFrame selected = frames.remove();
        while (!frames.isEmpty() && frames.peek().positionMs() <= duePositionMs) {
            DecodedFrame older = selected;
            selected = frames.remove();
            skippedFrames.incrementAndGet();
            release(older);
        }
        takenFrames.incrementAndGet();
        return selected;
    }

    /** Retains the old FIFO operation for non-render callers. */
    public synchronized DecodedFrame take() {
        DecodedFrame frame = frames.poll();
        if (frame != null) {
            takenFrames.incrementAndGet();
        }
        return frame;
    }

    public synchronized void release(DecodedFrame frame) {
        if (frame == null) {
            return;
        }
        releasedFrames.incrementAndGet();
        if (pool.size() < capacity) {
            pool.offer(frame.data());
        } else {
            discardedArrays.incrementAndGet();
        }
    }

    public synchronized void clear() {
        while (!frames.isEmpty()) {
            DecodedFrame frame = frames.remove();
            clearedFrames.incrementAndGet();
            release(frame);
        }
        discardedArrays.addAndGet(pool.size());
        pool.clear();
    }

    public Stats stats() {
        synchronized (this) {
            return new Stats(allocatedArrays.get(), reusedArrays.get(), submittedFrames.get(),
                    replacedFrames.get(), skippedFrames.get(), takenFrames.get(), releasedFrames.get(),
                    discardedArrays.get(), clearedFrames.get(), !frames.isEmpty(), frames.size(), pool.size());
        }
    }

    private static int positiveIntegerProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        return value > 0 ? value : fallback;
    }

    public record DecodedFrame(int width, int height, long positionMs,
                               VideoPixelFormat pixelFormat, byte[] data,
                               long playbackGeneration) {
        public DecodedFrame(int width, int height, long positionMs,
                            VideoPixelFormat pixelFormat, byte[] data) {
            this(width, height, positionMs, pixelFormat, data, -1L);
        }
    }

    public record Stats(long allocatedArrays, long reusedArrays, long submittedFrames,
                        long replacedFrames, long skippedFrames, long takenFrames,
                        long releasedFrames, long discardedArrays, long clearedFrames,
                        boolean pendingFrame, int queuedFrames, int pooledArrays) {
    }
}
