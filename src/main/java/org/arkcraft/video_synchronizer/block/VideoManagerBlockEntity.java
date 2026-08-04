package org.arkcraft.video_synchronizer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.arkcraft.video_synchronizer.Main;

public final class VideoManagerBlockEntity extends BlockEntity {
    private String screenId = "";
    private String mediaUrl = "";

    public VideoManagerBlockEntity(BlockPos pos, BlockState state) {
        super(Main.VIDEO_MANAGER_BLOCK_ENTITY.get(), pos, state);
    }

    public String getScreenId() {
        return screenId;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public void setConfiguration(String screenId, String mediaUrl) {
        this.screenId = screenId;
        this.mediaUrl = mediaUrl;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("ScreenId", screenId);
        tag.putString("MediaUrl", mediaUrl);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        screenId = tag.getString("ScreenId");
        mediaUrl = tag.getString("MediaUrl");
    }
}
