package org.arkcraft.video_synchronizer.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenLayout;
import org.arkcraft.video_synchronizer.block.ScreenOrientation;
import org.arkcraft.video_synchronizer.client.render.ScreenTexture;
import org.arkcraft.video_synchronizer.network.VideoScreenTargetMessage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side screen bindings, one entry per synchronized playback session. */
public final class ClientScreenTarget {
    private static final Map<String, Target> TARGETS = new ConcurrentHashMap<>();
    private static final Set<String> RECEIVED_SESSIONS = ConcurrentHashMap.newKeySet();

    private ClientScreenTarget() {
    }

    public static void accept(VideoScreenTargetMessage message) {
        RECEIVED_SESSIONS.add(message.sessionId());
        if (!message.bound() || message.width() < 1
                || message.width() > ScreenLayout.MAX_DIMENSION
                || message.height() < 1 || message.height() > ScreenLayout.MAX_DIMENSION
                || !message.screenUp().getAxis().isHorizontal()) {
            TARGETS.remove(message.sessionId());
            return;
        }
        TARGETS.put(message.sessionId(), new Target(message.screenId(), message.dimension(),
                new BlockPos(message.originX(), message.originY(), message.originZ()),
                ScreenOrientation.of(message.facing(), message.screenUp()),
                message.width(), message.height()));
    }

    public static void clear(String sessionId) {
        if (sessionId == null) {
            return;
        }
        TARGETS.remove(sessionId);
        RECEIVED_SESSIONS.remove(sessionId);
        ScreenTexture.closeSession(sessionId);
    }

    public static void clear() {
        TARGETS.clear();
        RECEIVED_SESSIONS.clear();
        ScreenTexture.closeAll();
    }

    public static boolean isTargetScreen(Level level, BlockPos pos) {
        return coordinates(level, pos) != null;
    }

    public static RenderTile renderTile(Level level, BlockPos pos) {
        Map.Entry<String, Coordinates> match = coordinates(level, pos);
        if (match == null) {
            return null;
        }
        Coordinates coordinates = match.getValue();
        Target target = TARGETS.get(match.getKey());
        if (target == null) {
            return null;
        }
        Direction right = target.orientation.right();
        Direction up = target.orientation.up();
        if (coordinates.column > 0
                && sameSection(pos, pos.relative(right.getOpposite()))) {
            return null;
        }
        if (coordinates.row > 0
                && sameSection(pos, pos.relative(up.getOpposite()))) {
            return null;
        }

        int tileWidth = 1;
        while (coordinates.column + tileWidth < target.width
                && sameSection(pos, pos.relative(right, tileWidth))) {
            tileWidth++;
        }
        int tileHeight = 1;
        while (coordinates.row + tileHeight < target.height
                && sameSection(pos, pos.relative(up, tileHeight))) {
            tileHeight++;
        }
        return new RenderTile(match.getKey(), coordinates.column, coordinates.row,
                tileWidth, tileHeight, target.width, target.height);
    }

    public static SourcePosition sourcePosition(String sessionId) {
        Target target = TARGETS.get(sessionId);
        if (target == null) {
            return null;
        }
        Direction right = target.orientation.right();
        Direction up = target.orientation.up();
        return new SourcePosition(target.dimension,
                new Vec3(target.origin.getX() + 0.5D
                                + right.getStepX() * (target.width - 1) / 2.0D
                                + up.getStepX() * (target.height - 1) / 2.0D,
                        target.origin.getY() + 0.5D
                                + right.getStepY() * (target.width - 1) / 2.0D
                                + up.getStepY() * (target.height - 1) / 2.0D,
                        target.origin.getZ() + 0.5D
                                + right.getStepZ() * (target.width - 1) / 2.0D
                                + up.getStepZ() * (target.height - 1) / 2.0D));
    }

    public static boolean hasReceivedTarget(String sessionId) {
        return RECEIVED_SESSIONS.contains(sessionId);
    }

    public static String screenId(String sessionId) {
        Target target = TARGETS.get(sessionId);
        return target == null || target.screenId.isBlank() ? null : target.screenId;
    }

    private static Map.Entry<String, Coordinates> coordinates(Level level, BlockPos pos) {
        for (Map.Entry<String, Target> entry : TARGETS.entrySet()) {
            Coordinates coordinates = entry.getValue().coordinates(level, pos);
            if (coordinates != null) {
                return Map.entry(entry.getKey(), coordinates);
            }
        }
        return null;
    }

    private static boolean sameSection(BlockPos first, BlockPos second) {
        return SectionPos.blockToSectionCoord(first.getX())
                == SectionPos.blockToSectionCoord(second.getX())
                && SectionPos.blockToSectionCoord(first.getY())
                == SectionPos.blockToSectionCoord(second.getY())
                && SectionPos.blockToSectionCoord(first.getZ())
                == SectionPos.blockToSectionCoord(second.getZ());
    }

    private record Target(String screenId, String dimension, BlockPos origin,
                          ScreenOrientation orientation,
                          int width, int height) {
        private Coordinates coordinates(Level level, BlockPos pos) {
            if (!dimension.equals(level.dimension().location().toString())) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ScreenBlock)
                    || !ScreenOrientation.of(state.getValue(ScreenBlock.FACING),
                    state.getValue(ScreenBlock.SCREEN_UP)).equals(orientation)) {
                return null;
            }
            int dx = pos.getX() - origin.getX();
            int dy = pos.getY() - origin.getY();
            int dz = pos.getZ() - origin.getZ();
            if (dot(dx, dy, dz, orientation.facing()) != 0) {
                return null;
            }
            int column = dot(dx, dy, dz, orientation.right());
            int row = dot(dx, dy, dz, orientation.up());
            if (column < 0 || column >= width || row < 0 || row >= height) {
                return null;
            }
            return new Coordinates(column, row);
        }
    }

    private static int dot(int dx, int dy, int dz, Direction direction) {
        return dx * direction.getStepX() + dy * direction.getStepY()
                + dz * direction.getStepZ();
    }

    private record Coordinates(int column, int row) {
    }

    public record RenderTile(String sessionId, int column, int row, int width, int height,
                             int screenWidth, int screenHeight) {
    }

    public record SourcePosition(String dimension, Vec3 position) {
    }
}
