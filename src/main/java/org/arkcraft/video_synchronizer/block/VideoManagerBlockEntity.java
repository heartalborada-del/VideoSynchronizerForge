package org.arkcraft.video_synchronizer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;
import org.arkcraft.video_synchronizer.network.AudioPlaybackMode;

import java.util.UUID;

public final class VideoManagerBlockEntity extends BlockEntity {
    public static final double DEFAULT_AUDIO_RANGE = 48.0D;

    private String screenId = "";
    private String videoUrl = "";
    private String audioUrl = "";
    private String requestHeaders = "";
    private String cookie = "";
    private boolean disableScaling;
    private int videoPipeLanes;
    private VideoPixelFormat videoPixelFormat = VideoPixelFormat.RGB24;
    private double audioRange = DEFAULT_AUDIO_RANGE;
    private AudioPlaybackMode audioPlaybackMode = AudioPlaybackMode.POSITIONAL;
    private UUID ownerId;

    public VideoManagerBlockEntity(BlockPos pos, BlockState state) {
        super(Main.VIDEO_MANAGER_BLOCK_ENTITY.get(), pos, state);
    }

    public String getScreenId() {
        return screenId;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public String getAudioUrl() {
        return audioUrl;
    }

    public String getRequestHeaders() {
        return requestHeaders;
    }

    public String getCookie() {
        return cookie;
    }

    public boolean isScalingDisabled() {
        return disableScaling;
    }

    public int getVideoPipeLanes() {
        return videoPipeLanes;
    }

    public VideoPixelFormat getVideoPixelFormat() {
        return videoPixelFormat;
    }

    public double getAudioRange() {
        return audioRange;
    }

    public AudioPlaybackMode getAudioPlaybackMode() {
        return audioPlaybackMode;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerIfAbsent(UUID ownerId) {
        if (this.ownerId == null && ownerId != null) {
            this.ownerId = ownerId;
            setChanged();
        }
    }

    public boolean isOwner(UUID playerId) {
        return ownerId != null && ownerId.equals(playerId);
    }

    public void setConfiguration(String screenId, String videoUrl, String audioUrl,
                                 String requestHeaders, String cookie,
                                 boolean disableScaling, int videoPipeLanes,
                                 VideoPixelFormat videoPixelFormat, double audioRange,
                                 AudioPlaybackMode audioPlaybackMode) {
        this.screenId = screenId;
        this.videoUrl = videoUrl;
        this.audioUrl = audioUrl;
        this.requestHeaders = requestHeaders;
        this.cookie = cookie;
        this.disableScaling = disableScaling;
        this.videoPipeLanes = normalizeVideoPipeLanes(videoPipeLanes);
        this.videoPixelFormat = videoPixelFormat == null
                ? VideoPixelFormat.RGB24 : videoPixelFormat;
        this.audioRange = audioRange;
        this.audioPlaybackMode = audioPlaybackMode == null
                ? AudioPlaybackMode.POSITIONAL : audioPlaybackMode;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("ScreenId", screenId);
        tag.putString("VideoUrl", videoUrl);
        tag.putString("AudioUrl", audioUrl);
        tag.putString("RequestHeaders", requestHeaders);
        tag.putString("Cookie", cookie);
        tag.putBoolean("DisableScaling", disableScaling);
        tag.putInt("VideoPipeLanes", videoPipeLanes);
        tag.putString("VideoPixelFormat", videoPixelFormat.name());
        tag.putDouble("AudioRange", audioRange);
        tag.putString("AudioPlaybackMode", audioPlaybackMode.name());
        if (ownerId != null) {
            tag.putUUID("Owner", ownerId);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        screenId = tag.getString("ScreenId");
        videoUrl = tag.contains("VideoUrl")
                ? tag.getString("VideoUrl") : tag.getString("MediaUrl");
        audioUrl = tag.getString("AudioUrl");
        requestHeaders = tag.getString("RequestHeaders");
        cookie = tag.getString("Cookie");
        disableScaling = tag.getBoolean("DisableScaling");
        videoPipeLanes = normalizeVideoPipeLanes(tag.getInt("VideoPipeLanes"));
        videoPixelFormat = VideoPixelFormat.fromName(tag.getString("VideoPixelFormat"));
        audioRange = normalizeAudioRange(tag.contains("AudioRange")
                ? tag.getDouble("AudioRange") : DEFAULT_AUDIO_RANGE);
        audioPlaybackMode = AudioPlaybackMode.fromName(tag.getString("AudioPlaybackMode"));
        ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    private static int normalizeVideoPipeLanes(int lanes) {
        return switch (lanes) {
            case 1, 2, 4, 8, 16 -> lanes;
            default -> 0;
        };
    }

    private static double normalizeAudioRange(double range) {
        return Double.isFinite(range) && range >= 1.0D && range <= 1024.0D
                ? range : DEFAULT_AUDIO_RANGE;
    }
}
