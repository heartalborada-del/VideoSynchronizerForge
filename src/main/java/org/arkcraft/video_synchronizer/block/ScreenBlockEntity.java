package org.arkcraft.video_synchronizer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.jetbrains.annotations.Nullable;

public final class ScreenBlockEntity extends BlockEntity {
    private ScreenLayout cachedLayout = ScreenLayout.SINGLE;
    private final AABB renderBoundingBox;
    private boolean layoutValid;
    private String screenId = "";

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(Main.SCREEN_BLOCK_ENTITY.get(), pos, state);
        int sectionX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getX()));
        int sectionY = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getY()));
        int sectionZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(pos.getZ()));
        renderBoundingBox = new AABB(sectionX, sectionY, sectionZ,
                sectionX + 16.0D, sectionY + 16.0D, sectionZ + 16.0D);
    }

    public ScreenLayout getLayout() {
        if (level == null) {
            return ScreenLayout.SINGLE;
        }
        if (!layoutValid) {
            Direction facing = getBlockState().getValue(ScreenBlock.FACING);
            Direction screenUp = getBlockState().getValue(ScreenBlock.SCREEN_UP);
            cachedLayout = ScreenLayout.detect(level, worldPosition, ScreenOrientation.of(facing, screenUp));
            layoutValid = true;
        }
        return cachedLayout;
    }

    public void invalidateLayout() {
        cachedLayout = ScreenLayout.SINGLE;
        layoutValid = false;
    }

    @Override
    public AABB getRenderBoundingBox() {
        return renderBoundingBox;
    }

    public String getScreenId() {
        return screenId;
    }

    public void setScreenId(String screenId) {
        if (this.screenId.equals(screenId)) {
            return;
        }
        this.screenId = screenId;
        setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel && !screenId.isBlank()) {
            ServerScreenRegistry.register(screenId, serverLevel, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("ScreenId", screenId);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        screenId = tag.getString("ScreenId");
        invalidateLayout();
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
