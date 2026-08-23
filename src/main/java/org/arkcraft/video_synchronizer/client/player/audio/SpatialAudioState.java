package org.arkcraft.video_synchronizer.client.player.audio;

/** Per-channel gain calculated from the listener and screen positions. */
public record SpatialAudioState(double leftGain, double rightGain) {
    public static final SpatialAudioState FULL_VOLUME = new SpatialAudioState(1.0D, 1.0D);
    public static final SpatialAudioState SILENT = new SpatialAudioState(0.0D, 0.0D);
}
