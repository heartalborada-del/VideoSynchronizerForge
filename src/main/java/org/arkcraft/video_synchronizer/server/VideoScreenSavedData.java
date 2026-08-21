package org.arkcraft.video_synchronizer.server;

import org.arkcraft.video_synchronizer.LocalizedArgumentException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.arkcraft.video_synchronizer.network.ScreenAccessRole;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class VideoScreenSavedData extends SavedData {
    private static final String DATA_NAME = "video_synchronizer_screens";

    private final Map<UUID, ScreenRecord> screens = new HashMap<>();
    private final Map<String, UUID> displayIds = new HashMap<>();

    public static VideoScreenSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                VideoScreenSavedData::load, VideoScreenSavedData::new, DATA_NAME);
    }

    public static VideoScreenSavedData load(CompoundTag root) {
        VideoScreenSavedData data = new VideoScreenSavedData();
        ListTag values = root.getList("Screens", Tag.TAG_COMPOUND);
        for (int index = 0; index < values.size(); index++) {
            ScreenRecord record = ScreenRecord.load(values.getCompound(index));
            if (record != null && !data.displayIds.containsKey(record.displayId)) {
                data.screens.put(record.screenUuid, record);
                data.displayIds.put(record.displayId, record.screenUuid);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag values = new ListTag();
        screens.values().stream()
                .sorted((left, right) -> left.displayId.compareTo(right.displayId))
                .map(ScreenRecord::save)
                .forEach(values::add);
        root.put("Screens", values);
        return root;
    }

    public Optional<ScreenRecord> find(UUID screenUuid) {
        return Optional.ofNullable(screenUuid == null ? null : screens.get(screenUuid));
    }

    public Optional<ScreenRecord> find(String displayId) {
        UUID screenUuid = displayIds.get(displayId);
        return Optional.ofNullable(screenUuid == null ? null : screens.get(screenUuid));
    }

    public Collection<ScreenRecord> screens() {
        return Collections.unmodifiableCollection(new ArrayList<>(screens.values()));
    }

    public ScreenRecord create(String displayId, UUID ownerId, String dimension,
                               BlockPos origin, Direction facing, Direction screenUp,
                               int width, int height) {
        if (displayIds.containsKey(displayId)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_id_used", displayId);
        }
        ScreenRecord record = new ScreenRecord(UUID.randomUUID(), displayId, ownerId,
                dimension, origin.immutable(), facing, screenUp, width, height);
        if (ownerId != null) {
            record.playbackConsents.add(ownerId);
        }
        screens.put(record.screenUuid, record);
        displayIds.put(displayId, record.screenUuid);
        setDirty();
        return record;
    }

    public void rename(ScreenRecord record, String displayId) {
        UUID existing = displayIds.get(displayId);
        if (existing != null && !existing.equals(record.screenUuid)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_id_used", displayId);
        }
        displayIds.remove(record.displayId);
        record.displayId = displayId;
        displayIds.put(displayId, record.screenUuid);
        setDirty();
    }

    public void setAccess(ScreenRecord record, UUID playerId, ScreenAccessRole role) {
        if (playerId == null || playerId.equals(record.ownerId)) {
            return;
        }
        record.controllers.remove(playerId);
        record.editors.remove(playerId);
        if (role == ScreenAccessRole.EDIT) {
            record.editors.add(playerId);
        } else {
            record.controllers.add(playerId);
        }
        setDirty();
    }

    public void removeAccess(ScreenRecord record, UUID playerId) {
        if (record.controllers.remove(playerId) | record.editors.remove(playerId)) {
            setDirty();
        }
    }

    public void setAccessMode(ScreenRecord record, ScreenAccessMode accessMode) {
        if (record.accessMode != accessMode) {
            record.accessMode = accessMode;
            setDirty();
        }
    }

    public void transfer(ScreenRecord record, UUID ownerId) {
        if (ownerId == null || ownerId.equals(record.ownerId)) {
            return;
        }
        record.ownerId = ownerId;
        record.controllers.remove(ownerId);
        record.editors.remove(ownerId);
        record.playbackConsents.add(ownerId);
        setDirty();
    }

    public void setPlaybackConsent(ScreenRecord record, UUID playerId, boolean allowed) {
        boolean changed = allowed
                ? record.playbackConsents.add(playerId)
                : record.playbackConsents.remove(playerId);
        if (changed) {
            setDirty();
        }
    }

    public void delete(ScreenRecord record) {
        if (screens.remove(record.screenUuid) != null) {
            displayIds.remove(record.displayId);
            setDirty();
        }
    }

    public static final class ScreenRecord {
        private final UUID screenUuid;
        private String displayId;
        private UUID ownerId;
        private final Set<UUID> editors = new HashSet<>();
        private final Set<UUID> controllers = new HashSet<>();
        private final Set<UUID> playbackConsents = new HashSet<>();
        private ScreenAccessMode accessMode = ScreenAccessMode.PRIVATE;
        private final String dimension;
        private final BlockPos origin;
        private final Direction facing;
        private final Direction screenUp;
        private final int width;
        private final int height;

        private ScreenRecord(UUID screenUuid, String displayId, UUID ownerId,
                             String dimension, BlockPos origin, Direction facing,
                             Direction screenUp, int width, int height) {
            this.screenUuid = screenUuid;
            this.displayId = displayId;
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.origin = origin;
            this.facing = facing;
            this.screenUp = screenUp;
            this.width = width;
            this.height = height;
        }

        public UUID screenUuid() {
            return screenUuid;
        }

        public String displayId() {
            return displayId;
        }

        public UUID ownerId() {
            return ownerId;
        }

        public Set<UUID> editors() {
            return Collections.unmodifiableSet(editors);
        }

        public Set<UUID> controllers() {
            return Collections.unmodifiableSet(controllers);
        }

        public Set<UUID> playbackConsents() {
            return Collections.unmodifiableSet(playbackConsents);
        }

        public ScreenAccessMode accessMode() {
            return accessMode;
        }

        public String dimension() {
            return dimension;
        }

        public BlockPos origin() {
            return origin;
        }

        public Direction facing() {
            return facing;
        }

        public Direction screenUp() {
            return screenUp;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int panelCount() {
            return width * height;
        }

        public boolean isOwner(UUID playerId) {
            return ownerId != null && ownerId.equals(playerId);
        }

        public boolean canEdit(UUID playerId) {
            return isOwner(playerId) || accessMode != ScreenAccessMode.PRIVATE
                    && editors.contains(playerId);
        }

        public boolean canControl(UUID playerId) {
            return isOwner(playerId) || accessMode != ScreenAccessMode.PRIVATE
                    && (editors.contains(playerId) || controllers.contains(playerId)
                    || accessMode == ScreenAccessMode.PUBLIC_CONTROL);
        }

        public boolean canView(UUID playerId) {
            return isOwner(playerId) || accessMode != ScreenAccessMode.PRIVATE
                    && (editors.contains(playerId) || controllers.contains(playerId)
                    || accessMode == ScreenAccessMode.PUBLIC_CONTROL
                    || accessMode == ScreenAccessMode.PUBLIC_VIEW);
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("ScreenUuid", screenUuid);
            tag.putString("DisplayId", displayId);
            if (ownerId != null) {
                tag.putUUID("Owner", ownerId);
            }
            tag.put("Editors", saveUuids(editors));
            tag.put("Controllers", saveUuids(controllers));
            tag.put("PlaybackConsents", saveUuids(playbackConsents));
            tag.putString("AccessMode", accessMode.name());
            tag.putString("Dimension", dimension);
            tag.putLong("Origin", origin.asLong());
            tag.putString("Facing", facing.getName());
            tag.putString("ScreenUp", screenUp.getName());
            tag.putInt("Width", width);
            tag.putInt("Height", height);
            return tag;
        }

        private static ScreenRecord load(CompoundTag tag) {
            if (!tag.hasUUID("ScreenUuid") || !tag.contains("DisplayId", Tag.TAG_STRING)
                    || !tag.contains("Dimension", Tag.TAG_STRING)) {
                return null;
            }
            Direction facing = Direction.byName(tag.getString("Facing"));
            Direction screenUp = Direction.byName(tag.getString("ScreenUp"));
            int width = tag.getInt("Width");
            int height = tag.getInt("Height");
            if (facing == null || screenUp == null || width < 1 || height < 1) {
                return null;
            }
            ScreenRecord record = new ScreenRecord(tag.getUUID("ScreenUuid"),
                    tag.getString("DisplayId"),
                    tag.hasUUID("Owner") ? tag.getUUID("Owner") : null,
                    tag.getString("Dimension"), BlockPos.of(tag.getLong("Origin")),
                    facing, screenUp, width, height);
            loadUuids(tag.getList("Editors", Tag.TAG_STRING), record.editors);
            loadUuids(tag.getList("Controllers", Tag.TAG_STRING), record.controllers);
            loadUuids(tag.getList("PlaybackConsents", Tag.TAG_STRING),
                    record.playbackConsents);
            record.accessMode = tag.contains("AccessMode", Tag.TAG_STRING)
                    ? ScreenAccessMode.fromName(tag.getString("AccessMode"))
                    : ScreenAccessMode.TRUSTED;
            return record;
        }
    }

    private static ListTag saveUuids(Set<UUID> values) {
        ListTag result = new ListTag();
        values.stream().map(UUID::toString).sorted()
                .forEach(value -> result.add(StringTag.valueOf(value)));
        return result;
    }

    private static void loadUuids(ListTag values, Set<UUID> target) {
        for (int index = 0; index < values.size(); index++) {
            try {
                target.add(UUID.fromString(values.getString(index)));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed legacy entries without dropping the logical screen.
            }
        }
    }
}
