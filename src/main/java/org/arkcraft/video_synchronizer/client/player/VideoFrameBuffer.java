package org.arkcraft.video_synchronizer.client.player;

import org.arkcraft.video_synchronizer.network.VideoPixelFormat;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the newest submitted frame and reuses its backing arrays. */
public final class VideoFrameBuffer {
    public static final VideoFrameBuffer INSTANCE = new VideoFrameBuffer();

    private final AtomicReference<DecodedFrame> pending = new AtomicReference<>();
    private final ConcurrentLinkedQueue<byte[]> pool = new ConcurrentLinkedQueue<>();
    private final AtomicLong allocatedArrays = new AtomicLong();
    private final AtomicLong reusedArrays = new AtomicLong();
    private final AtomicLong submittedFrames = new AtomicLong();
    private final AtomicLong replacedFrames = new AtomicLong();
    private final AtomicLong takenFrames = new AtomicLong();
    private final AtomicLong releasedFrames = new AtomicLong();
    private final AtomicLong discardedArrays = new AtomicLong();
    private final AtomicLong clearedFrames = new AtomicLong();

    private VideoFrameBuffer() {
    }

    public byte[] acquire(int size) {
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

    public void submit(DecodedFrame frame) {
        submittedFrames.incrementAndGet();
        DecodedFrame replaced = pending.getAndSet(frame);
        if (replaced != null) {
            replacedFrames.incrementAndGet();
            release(replaced);
        }
    }

    public DecodedFrame take() {
        DecodedFrame frame = pending.getAndSet(null);
        if (frame != null) {
            takenFrames.incrementAndGet();
        }
        return frame;
    }

    public void release(DecodedFrame frame) {
        if (frame == null) {
            return;
        }
        releasedFrames.incrementAndGet();
        if (pool.size() < 1) {
            pool.offer(frame.data());
        } else {
            discardedArrays.incrementAndGet();
        }
    }

    public void clear() {
        DecodedFrame frame = pending.getAndSet(null);
        if (frame != null) {
            clearedFrames.incrementAndGet();
            release(frame);
        }
        discardedArrays.addAndGet(pool.size());
        pool.clear();
    }

    public Stats stats() {
        return new Stats(allocatedArrays.get(), reusedArrays.get(), submittedFrames.get(),
                replacedFrames.get(), takenFrames.get(), releasedFrames.get(),
                discardedArrays.get(), clearedFrames.get(), pending.get() != null, pool.size());
    }

    public record DecodedFrame(int width, int height, long positionMs,
                               VideoPixelFormat pixelFormat, byte[] data) {
    }

    public record Stats(long allocatedArrays, long reusedArrays, long submittedFrames,
                        long replacedFrames, long takenFrames, long releasedFrames,
                        long discardedArrays, long clearedFrames, boolean pendingFrame,
                        int pooledArrays) {
    }
}
