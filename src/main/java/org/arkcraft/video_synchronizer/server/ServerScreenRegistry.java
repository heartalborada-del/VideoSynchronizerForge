package org.arkcraft.video_synchronizer.server;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenBlockEntity;
import org.arkcraft.video_synchronizer.block.ScreenLayout;
import org.arkcraft.video_synchronizer.block.ScreenOrientation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ServerScreenRegistry {
    private static final Map<String, ScreenReference> SCREENS = new HashMap<>();

    private ServerScreenRegistry() {
    }

    public static String normalizeId(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Screen ID must use 1-32 lowercase letters, digits, _ or -");
        }
        return id;
    }

    public static void register(String screenId, ServerLevel level, BlockPos pos) {
        if (screenId != null && !screenId.isBlank()) {
            SCREENS.put(screenId, new ScreenReference(level.dimension(), pos.immutable()));
        }
    }

    public static String assignGroup(ServerLevel level, BlockPos clickedPos, String requestedId) {
        String screenId = normalizeId(requestedId);
        ScreenBlockEntity clicked = getScreen(level, clickedPos)
                .orElseThrow(() -> new IllegalArgumentException("The target is not a video screen"));
        clicked.invalidateLayout();
        ScreenLayout layout = clicked.getLayout();
        ScreenOrientation orientation = ScreenOrientation.of(
                clicked.getBlockState().getValue(ScreenBlock.FACING),
                clicked.getBlockState().getValue(ScreenBlock.SCREEN_UP));

        ScreenReference existing = SCREENS.get(screenId);
        boolean sameGroup = existing != null && existing.dimension.equals(level.dimension())
                && contains(clickedPos, layout, orientation, existing.pos);
        if (existing != null && !sameGroup
                && getScreen(level.getServer(), existing).isPresent()) {
            throw new IllegalArgumentException("Screen ID is already in use: " + screenId);
        }

        for (int column = -layout.column(); column < layout.width() - layout.column(); column++) {
            for (int row = -layout.row(); row < layout.height() - layout.row(); row++) {
                BlockPos pos = clickedPos.relative(orientation.right(), column)
                        .relative(orientation.up(), row);
                getScreen(level, pos).ifPresent(screen -> screen.setScreenId(screenId));
            }
        }
        register(screenId, level, clickedPos);
        return screenId;
    }

    public static String requireUnused(MinecraftServer server, String requestedId) {
        String screenId = normalizeId(requestedId);
        ScreenReference existing = SCREENS.get(screenId);
        if (existing != null && getScreen(server, existing).isPresent()) {
            throw new IllegalArgumentException("Screen ID is already in use: " + screenId);
        }
        SCREENS.remove(screenId);
        return screenId;
    }

    public static ScreenReference require(MinecraftServer server, String requestedId) {
        String screenId = normalizeId(requestedId);
        ScreenReference reference = SCREENS.get(screenId);
        if (reference == null || getScreen(server, reference)
                .filter(screen -> screenId.equals(screen.getScreenId())).isEmpty()) {
            SCREENS.remove(screenId);
            throw new IllegalArgumentException("Unknown or unloaded screen ID: " + screenId);
        }
        return reference;
    }

    public static void clear() {
        SCREENS.clear();
    }

    private static Optional<ScreenBlockEntity> getScreen(MinecraftServer server, ScreenReference reference) {
        ServerLevel level = server.getLevel(reference.dimension);
        return level == null ? Optional.empty() : getScreen(level, reference.pos);
    }

    private static Optional<ScreenBlockEntity> getScreen(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof ScreenBlockEntity screen ? Optional.of(screen) : Optional.empty();
    }

    private static boolean contains(BlockPos origin, ScreenLayout layout, ScreenOrientation orientation,
                                    BlockPos candidate) {
        int dx = candidate.getX() - origin.getX();
        int dy = candidate.getY() - origin.getY();
        int dz = candidate.getZ() - origin.getZ();
        int column = dx * orientation.right().getStepX() + dy * orientation.right().getStepY()
                + dz * orientation.right().getStepZ();
        int row = dx * orientation.up().getStepX() + dy * orientation.up().getStepY()
                + dz * orientation.up().getStepZ();
        return column >= -layout.column() && column < layout.width() - layout.column()
                && row >= -layout.row() && row < layout.height() - layout.row();
    }

    public record ScreenReference(ResourceKey<Level> dimension, BlockPos pos) {
    }
}
