package org.arkcraft.video_synchronizer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;

public final class VideoManagerBlockEntity extends BlockEntity {
    private String screenId = "";
    private String videoUrl = "";
    private String audioUrl = "";
    private String requestHeaders = "";
    private String cookie = "";
    private boolean disableScaling;
    private int videoPipeLanes;
    private VideoPixelFormat videoPixelFormat = VideoPixelFormat.RGB24;

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

    public void setConfiguration(String screenId, String videoUrl, String audioUrl,
                                 String requestHeaders, String cookie,
                                 boolean disableScaling, int videoPipeLanes,
                                 VideoPixelFormat videoPixelFormat) {
        this.screenId = screenId;
        this.videoUrl = videoUrl;
        this.audioUrl = audioUrl;
        this.requestHeaders = requestHeaders;
        this.cookie = cookie;
        this.disableScaling = disableScaling;
        this.videoPipeLanes = normalizeVideoPipeLanes(videoPipeLanes);
        this.videoPixelFormat = videoPixelFormat == null
                ? VideoPixelFormat.RGB24 : videoPixelFormat;
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
    }

    private static int normalizeVideoPipeLanes(int lanes) {
        return switch (lanes) {
            case 1, 2, 4, 8, 16 -> lanes;
            default -> 0;
        };
    }
}
