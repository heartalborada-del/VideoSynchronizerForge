package org.arkcraft.video_synchronizer.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.arkcraft.video_synchronizer.LocalizedArgumentException;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenBlockEntity;
import org.arkcraft.video_synchronizer.block.ScreenLayout;
import org.arkcraft.video_synchronizer.block.ScreenOrientation;
import org.arkcraft.video_synchronizer.network.ScreenAccessRole;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ServerScreenRegistry {
    private static final long SELECTION_EXPIRY_NANOS = TimeUnit.MINUTES.toNanos(2L);
    private static final Map<UUID, PendingSelection> PENDING_SELECTIONS =
            new HashMap<>();

    private ServerScreenRegistry() {
    }

    public static String normalizeId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_id_format");
        }
        return id;
    }

    public static void register(ScreenBlockEntity screen, ServerLevel level, BlockPos pos) {
        VideoScreenSavedData data = VideoScreenSavedData.get(level.getServer());
        if (screen.getScreenUuid() != null) {
            if (data.find(screen.getScreenUuid()).isEmpty()) {
                screen.setScreenUuid(null);
            }
            return;
        }
        ScreenBlockEntity.LegacyAccess legacy = screen.getLegacyAccess();
        if (legacy == null) {
            return;
        }
        String displayId;
        try {
            displayId = normalizeId(legacy.displayId());
        } catch (IllegalArgumentException exception) {
            return;
        }
        VideoScreenSavedData.ScreenRecord record = data.find(displayId).orElse(null);
        if (record == null) {
            Geometry geometry = detectedGeometry(level, pos);
            record = data.create(displayId, legacy.ownerId(), dimension(level),
                    geometry.origin, geometry.facing, geometry.screenUp,
                    geometry.width, geometry.height);
            data.setAccessMode(record, ScreenAccessMode.TRUSTED);
            for (UUID playerId : legacy.controllers()) {
                data.setAccess(record, playerId, ScreenAccessRole.CONTROL);
            }
            for (UUID playerId : legacy.editors()) {
                data.setAccess(record, playerId, ScreenAccessRole.EDIT);
            }
            for (UUID playerId : legacy.playbackConsents()) {
                data.setPlaybackConsent(record, playerId, true);
            }
            assignLoadedRectangle(level, record);
        } else {
            screen.setScreenUuid(record.screenUuid());
        }
    }

    public static String assignGroup(ServerPlayer player, BlockPos clickedPos,
                                     String requestedId) {
        ScreenBlockEntity clicked = getScreen(player.serverLevel(), clickedPos)
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.target_not_panel"));
        VideoScreenSavedData data = VideoScreenSavedData.get(player.getServer());
        VideoScreenSavedData.ScreenRecord existing = data.find(clicked.getScreenUuid())
                .orElse(null);
        String displayId = normalizeId(requestedId);
        if (existing != null) {
            if (!VideoPermissionService.canBind(player, existing)) {
                throw new LocalizedArgumentException(
                        "message.video_synchronizer.error.rename_permission");
            }
            if (!existing.displayId().equals(displayId)) {
                throw new LocalizedArgumentException(
                        "message.video_synchronizer.error.rename_existing");
            }
            return existing.displayId();
        }
        Geometry geometry = VideoPermissionService.isAdmin(player)
                ? detectedGeometry(player.serverLevel(), clickedPos)
                : singleGeometry(clicked, clickedPos);
        return createGroup(player, geometry, displayId).displayId();
    }

    public static String assignSelection(ServerPlayer player, BlockPos first, BlockPos second,
                                         String requestedId) {
        return createGroup(player, selectionGeometry(player.serverLevel(), first, second),
                normalizeId(requestedId)).displayId();
    }

    public static void authorizeSelection(ServerPlayer player, BlockPos first, BlockPos second) {
        PENDING_SELECTIONS.put(player.getUUID(), new PendingSelection(
                dimension(player.serverLevel()), first.immutable(), second.immutable(),
                System.nanoTime() + SELECTION_EXPIRY_NANOS));
    }

    public static boolean consumeAuthorizedSelection(ServerPlayer player, BlockPos first,
                                                     BlockPos second) {
        PendingSelection selection = PENDING_SELECTIONS.remove(player.getUUID());
        return selection != null && selection.expiresAtNanos >= System.nanoTime()
                && selection.dimension.equals(dimension(player.serverLevel()))
                && selection.first.equals(first) && selection.second.equals(second);
    }

    public static void clearPendingSelection(UUID playerId) {
        PENDING_SELECTIONS.remove(playerId);
    }

    private static VideoScreenSavedData.ScreenRecord createGroup(ServerPlayer player,
                                                                  Geometry geometry,
                                                                  String displayId) {
        if (!VideoPermissionService.canCreate(player)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.create_permission");
        }
        VideoScreenSavedData data = VideoScreenSavedData.get(player.getServer());
        if (data.find(displayId).isPresent()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_id_used", displayId);
        }
        VideoUsagePolicy.requireScreenCapacity(player, geometry.width * geometry.height);
        List<ScreenBlockEntity> panels = panels(player.serverLevel(), geometry, true);
        if (panels.stream().anyMatch(panel -> panel.getScreenUuid() != null)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.panel_assigned");
        }
        VideoScreenSavedData.ScreenRecord record = data.create(displayId, player.getUUID(),
                dimension(player.serverLevel()), geometry.origin, geometry.facing,
                geometry.screenUp, geometry.width, geometry.height);
        panels.forEach(panel -> panel.setScreenUuid(record.screenUuid()));
        VideoUsagePolicy.recordScreenCreated(player);
        return record;
    }

    public static String requireUnused(MinecraftServer server, String requestedId) {
        String displayId = normalizeId(requestedId);
        if (VideoScreenSavedData.get(server).find(displayId).isPresent()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_id_used", displayId);
        }
        return displayId;
    }

    public static ScreenReference require(MinecraftServer server, String requestedId) {
        String displayId = normalizeId(requestedId);
        VideoScreenSavedData.ScreenRecord record = VideoScreenSavedData.get(server)
                .find(displayId)
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_screen", displayId));
        ResourceKey<Level> dimension = dimension(record.dimension());
        ServerLevel level = server.getLevel(dimension);
        if (level == null || getScreen(level, record.origin())
                .filter(screen -> record.screenUuid().equals(screen.getScreenUuid())).isEmpty()) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_unloaded", displayId);
        }
        return new ScreenReference(record.screenUuid(), dimension, record.origin(),
                record.facing(), record.screenUp(), record.width(), record.height());
    }

    public static Optional<VideoScreenSavedData.ScreenRecord> find(MinecraftServer server,
                                                                    String requestedId) {
        if (requestedId == null || requestedId.isBlank()) {
            return Optional.empty();
        }
        try {
            return VideoScreenSavedData.get(server).find(normalizeId(requestedId));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static Optional<VideoScreenSavedData.ScreenRecord> find(ServerLevel level,
                                                                    BlockPos pos) {
        return getScreen(level, pos).flatMap(screen ->
                VideoScreenSavedData.get(level.getServer()).find(screen.getScreenUuid()));
    }

    public static String screenId(ServerLevel level, ScreenBlockEntity screen) {
        return VideoScreenSavedData.get(level.getServer()).find(screen.getScreenUuid())
                .map(VideoScreenSavedData.ScreenRecord::displayId).orElse("");
    }

    public static boolean canControl(ServerPlayer player, String requestedId) {
        return find(player.getServer(), requestedId)
                .map(screen -> VideoPermissionService.canControl(player, screen)).orElse(false);
    }

    public static boolean canEdit(ServerPlayer player, String requestedId) {
        return find(player.getServer(), requestedId)
                .map(screen -> VideoPermissionService.canEditSource(player, screen)).orElse(false);
    }

    public static boolean canManage(ServerPlayer player, String requestedId) {
        return find(player.getServer(), requestedId)
                .map(screen -> VideoPermissionService.canManage(player, screen)).orElse(false);
    }

    public static boolean hasPlaybackConsent(ServerPlayer player, String requestedId) {
        return find(player.getServer(), requestedId)
                .map(screen -> screen.playbackConsents().contains(player.getUUID()))
                .orElse(false);
    }

    public static Optional<UUID> ownerId(MinecraftServer server, String requestedId) {
        return find(server, requestedId).map(VideoScreenSavedData.ScreenRecord::ownerId);
    }

    public static Set<UUID> playbackConsents(MinecraftServer server, String requestedId) {
        return find(server, requestedId)
                .map(screen -> new HashSet<>(screen.playbackConsents()))
                .orElseGet(HashSet::new);
    }

    public static void setPlaybackConsent(MinecraftServer server, String requestedId,
                                          UUID playerId, boolean allowed) {
        VideoScreenSavedData data = VideoScreenSavedData.get(server);
        VideoScreenSavedData.ScreenRecord record = data.find(normalizeId(requestedId))
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_screen", requestedId));
        data.setPlaybackConsent(record, playerId, allowed);
    }

    public static List<ScreenOption> editableScreens(ServerPlayer player, BlockPos origin) {
        List<ScreenOption> result = new ArrayList<>();
        for (VideoScreenSavedData.ScreenRecord screen
                : VideoScreenSavedData.get(player.getServer()).screens()) {
            if (!VideoPermissionService.canEditSource(player, screen)) {
                continue;
            }
            ResourceKey<Level> screenDimension = dimension(screen.dimension());
            ServerLevel level = player.getServer().getLevel(screenDimension);
            if (level == null || getScreen(level, screen.origin())
                    .filter(panel -> screen.screenUuid().equals(panel.getScreenUuid())).isEmpty()) {
                continue;
            }
            boolean otherDimension = !screenDimension.equals(player.serverLevel().dimension());
            int distance = otherDimension ? -1 : (int) Math.round(Math.sqrt(
                    screen.origin().distSqr(origin)));
            result.add(new ScreenOption(screen.displayId(), distance, otherDimension,
                    VideoPermissionService.canManage(player, screen)));
        }
        result.sort(Comparator.comparing(ScreenOption::otherDimension)
                .thenComparingInt(option -> option.otherDimension
                        ? Integer.MAX_VALUE : option.distance)
                .thenComparing(ScreenOption::screenId));
        return result;
    }

    public static AccessSnapshot accessSnapshot(MinecraftServer server, String requestedId) {
        VideoScreenSavedData.ScreenRecord screen = find(server, requestedId)
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_screen", requestedId));
        return new AccessSnapshot(screen.screenUuid(), screen.ownerId(),
                new HashSet<>(screen.controllers()), new HashSet<>(screen.editors()),
                screen.accessMode());
    }

    public static void setAccess(MinecraftServer server, String requestedId, UUID playerId,
                                 ScreenAccessRole role) {
        VideoScreenSavedData data = VideoScreenSavedData.get(server);
        VideoScreenSavedData.ScreenRecord record = data.find(normalizeId(requestedId))
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_screen", requestedId));
        data.setAccess(record, playerId, role);
    }

    public static void removeAccess(MinecraftServer server, String requestedId, UUID playerId) {
        VideoScreenSavedData data = VideoScreenSavedData.get(server);
        VideoScreenSavedData.ScreenRecord record = data.find(normalizeId(requestedId))
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_screen", requestedId));
        data.removeAccess(record, playerId);
    }

    public static void setAccessMode(MinecraftServer server, String requestedId,
                                     ScreenAccessMode accessMode) {
        VideoScreenSavedData data = VideoScreenSavedData.get(server);
        VideoScreenSavedData.ScreenRecord record = data.find(normalizeId(requestedId))
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_screen", requestedId));
        data.setAccessMode(record, accessMode);
    }

    public static void transfer(MinecraftServer server, String requestedId, UUID ownerId) {
        VideoScreenSavedData data = VideoScreenSavedData.get(server);
        VideoScreenSavedData.ScreenRecord record = data.find(normalizeId(requestedId))
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.unknown_screen", requestedId));
        data.transfer(record, ownerId);
    }

    public static void disband(ServerLevel level, BlockPos pos) {
        VideoScreenSavedData data = VideoScreenSavedData.get(level.getServer());
        VideoScreenSavedData.ScreenRecord record = find(level, pos).orElse(null);
        if (record == null) {
            return;
        }
        panels(level, new Geometry(record.origin(), record.facing(), record.screenUp(),
                record.width(), record.height()), false)
                .forEach(panel -> panel.setScreenUuid(null));
        ServerVideoSessionManager.stopIfPresent(record.displayId());
        data.delete(record);
    }

    public static int ownedScreenCount(MinecraftServer server, UUID ownerId) {
        return (int) VideoScreenSavedData.get(server).screens().stream()
                .filter(screen -> screen.isOwner(ownerId)).count();
    }

    public static int ownedPanelCount(MinecraftServer server, UUID ownerId) {
        return VideoScreenSavedData.get(server).screens().stream()
                .filter(screen -> screen.isOwner(ownerId))
                .mapToInt(VideoScreenSavedData.ScreenRecord::panelCount).sum();
    }

    public static void clear() {
        PENDING_SELECTIONS.clear();
        // Logical screens are persistent SavedData and are not cleared on server shutdown.
    }

    private static Geometry detectedGeometry(ServerLevel level, BlockPos clickedPos) {
        ScreenBlockEntity clicked = getScreen(level, clickedPos)
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.target_not_panel"));
        clicked.invalidateLayout();
        ScreenLayout layout = clicked.getLayout();
        BlockState state = clicked.getBlockState();
        Direction facing = state.getValue(ScreenBlock.FACING);
        Direction screenUp = state.getValue(ScreenBlock.SCREEN_UP);
        ScreenOrientation orientation = ScreenOrientation.of(facing, screenUp);
        BlockPos origin = clickedPos.relative(orientation.right(), -layout.column())
                .relative(orientation.up(), -layout.row()).immutable();
        return new Geometry(origin, facing, screenUp, layout.width(), layout.height());
    }

    private static Geometry singleGeometry(ScreenBlockEntity panel, BlockPos pos) {
        BlockState state = panel.getBlockState();
        return new Geometry(pos.immutable(), state.getValue(ScreenBlock.FACING),
                state.getValue(ScreenBlock.SCREEN_UP), 1, 1);
    }

    private static Geometry selectionGeometry(ServerLevel level, BlockPos first,
                                              BlockPos second) {
        ScreenBlockEntity firstPanel = getScreen(level, first)
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.first_not_panel"));
        ScreenBlockEntity secondPanel = getScreen(level, second)
                .orElseThrow(() -> new LocalizedArgumentException(
                        "message.video_synchronizer.error.second_not_panel"));
        Direction facing = firstPanel.getBlockState().getValue(ScreenBlock.FACING);
        Direction screenUp = firstPanel.getBlockState().getValue(ScreenBlock.SCREEN_UP);
        if (facing != secondPanel.getBlockState().getValue(ScreenBlock.FACING)
                || screenUp != secondPanel.getBlockState().getValue(ScreenBlock.SCREEN_UP)) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.selection_orientation");
        }
        ScreenOrientation orientation = ScreenOrientation.of(facing, screenUp);
        int dx = second.getX() - first.getX();
        int dy = second.getY() - first.getY();
        int dz = second.getZ() - first.getZ();
        if (dot(dx, dy, dz, facing) != 0) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.selection_plane");
        }
        int columnOffset = dot(dx, dy, dz, orientation.right());
        int rowOffset = dot(dx, dy, dz, orientation.up());
        int width = Math.abs(columnOffset) + 1;
        int height = Math.abs(rowOffset) + 1;
        if (width > ScreenLayout.MAX_DIMENSION || height > ScreenLayout.MAX_DIMENSION) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_size_limit");
        }
        BlockPos origin = first.relative(orientation.right(), Math.min(0, columnOffset))
                .relative(orientation.up(), Math.min(0, rowOffset)).immutable();
        return new Geometry(origin, facing, screenUp, width, height);
    }

    private static List<ScreenBlockEntity> panels(ServerLevel level, Geometry geometry,
                                                   boolean requireComplete) {
        ScreenOrientation orientation = ScreenOrientation.of(
                geometry.facing, geometry.screenUp);
        List<ScreenBlockEntity> result = new ArrayList<>(geometry.width * geometry.height);
        for (int row = 0; row < geometry.height; row++) {
            for (int column = 0; column < geometry.width; column++) {
                BlockPos pos = geometry.origin.relative(orientation.right(), column)
                        .relative(orientation.up(), row);
                ScreenBlockEntity panel = getScreen(level, pos).orElse(null);
                if (panel == null || panel.getBlockState().getValue(ScreenBlock.FACING)
                        != geometry.facing
                        || panel.getBlockState().getValue(ScreenBlock.SCREEN_UP)
                        != geometry.screenUp) {
                    if (requireComplete) {
                        throw new LocalizedArgumentException(
                                "message.video_synchronizer.error.selection_missing_panel",
                                pos.toShortString());
                    }
                    continue;
                }
                result.add(panel);
            }
        }
        return result;
    }

    private static void assignLoadedRectangle(ServerLevel level,
                                              VideoScreenSavedData.ScreenRecord record) {
        panels(level, new Geometry(record.origin(), record.facing(), record.screenUp(),
                record.width(), record.height()), false)
                .forEach(panel -> panel.setScreenUuid(record.screenUuid()));
    }

    private static Optional<ScreenBlockEntity> getScreen(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof ScreenBlockEntity screen
                ? Optional.of(screen) : Optional.empty();
    }

    private static int dot(int dx, int dy, int dz, Direction direction) {
        return dx * direction.getStepX() + dy * direction.getStepY()
                + dz * direction.getStepZ();
    }

    private static String dimension(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static ResourceKey<Level> dimension(String value) {
        ResourceLocation location = ResourceLocation.tryParse(value);
        if (location == null) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.invalid_dimension", value);
        }
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    private record Geometry(BlockPos origin, Direction facing, Direction screenUp,
                            int width, int height) {
    }

    private record PendingSelection(String dimension, BlockPos first, BlockPos second,
                                    long expiresAtNanos) {
    }

    public record ScreenReference(UUID screenUuid, ResourceKey<Level> dimension, BlockPos pos,
                                  Direction facing, Direction screenUp,
                                  int width, int height) {
    }

    public record ScreenOption(String screenId, int distance, boolean otherDimension,
                               boolean manageable) {
    }

    public record AccessSnapshot(UUID screenUuid, UUID ownerId, Set<UUID> controllers,
                                 Set<UUID> editors, ScreenAccessMode accessMode) {
    }
}
