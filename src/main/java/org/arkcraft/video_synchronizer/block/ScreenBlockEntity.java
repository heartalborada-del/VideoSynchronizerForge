package org.arkcraft.video_synchronizer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ScreenBlockEntity extends BlockEntity {
    private ScreenLayout cachedLayout = ScreenLayout.SINGLE;
    private final AABB renderBoundingBox;
    private boolean layoutValid;
    private UUID screenUuid;
    private LegacyAccess legacyAccess;

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
            cachedLayout = ScreenLayout.detect(level, worldPosition,
                    ScreenOrientation.of(facing, screenUp), screenUuid);
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

    @Nullable
    public UUID getScreenUuid() {
        return screenUuid;
    }

    public void setScreenUuid(@Nullable UUID screenUuid) {
        if (java.util.Objects.equals(this.screenUuid, screenUuid)) {
            return;
        }
        this.screenUuid = screenUuid;
        legacyAccess = null;
        invalidateLayout();
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Nullable
    public LegacyAccess getLegacyAccess() {
        return legacyAccess;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            ServerScreenRegistry.register(this, serverLevel, worldPosition);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (screenUuid != null) {
            tag.putUUID("ScreenUuid", screenUuid);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        screenUuid = tag.hasUUID("ScreenUuid") ? tag.getUUID("ScreenUuid") : null;
        legacyAccess = screenUuid == null ? loadLegacyAccess(tag) : null;
        invalidateLayout();
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        if (screenUuid != null) {
            tag.putUUID("ScreenUuid", screenUuid);
        }
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Nullable
    private static LegacyAccess loadLegacyAccess(CompoundTag tag) {
        String displayId = tag.getString("ScreenId");
        if (displayId.isBlank()) {
            return null;
        }
        UUID ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        Set<UUID> controllers = loadUuids(tag.getList("Controllers", Tag.TAG_STRING));
        Set<UUID> editors = loadUuids(tag.getList("Editors", Tag.TAG_STRING));
        Set<UUID> playbackConsents = tag.contains("PlaybackConsents", Tag.TAG_LIST)
                ? loadUuids(tag.getList("PlaybackConsents", Tag.TAG_STRING))
                : new HashSet<>();
        if (!tag.contains("PlaybackConsents", Tag.TAG_LIST) && ownerId != null) {
            playbackConsents.add(ownerId);
        }
        return new LegacyAccess(displayId, ownerId, controllers, editors, playbackConsents);
    }

    private static Set<UUID> loadUuids(ListTag values) {
        Set<UUID> result = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            try {
                result.add(UUID.fromString(values.getString(index)));
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed old entries during one-time migration.
            }
        }
        return result;
    }

    public record LegacyAccess(String displayId, UUID ownerId, Set<UUID> controllers,
                               Set<UUID> editors, Set<UUID> playbackConsents) {
    }
}
