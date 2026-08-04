package org.arkcraft.video_synchronizer.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenLayout;
import org.arkcraft.video_synchronizer.block.ScreenOrientation;
import org.arkcraft.video_synchronizer.client.render.ScreenTexture;
import org.arkcraft.video_synchronizer.network.VideoScreenTargetMessage;

public final class ClientScreenTarget {
    private static String sessionId;
    private static String dimension;
    private static BlockPos origin;
    private static ScreenOrientation orientation;
    private static int width;
    private static int height;

    private ClientScreenTarget() {
    }

    public static void accept(VideoScreenTargetMessage message) {
        if (!message.bound() || message.width() < 1
                || message.width() > ScreenLayout.MAX_DIMENSION
                || message.height() < 1 || message.height() > ScreenLayout.MAX_DIMENSION
                || !message.screenUp().getAxis().isHorizontal()) {
            clear();
            return;
        }
        sessionId = message.sessionId();
        dimension = message.dimension();
        origin = new BlockPos(message.originX(), message.originY(), message.originZ());
        orientation = ScreenOrientation.of(message.facing(), message.screenUp());
        width = message.width();
        height = message.height();
    }

    public static void resetForSession(String newSessionId) {
        if (sessionId == null || !sessionId.equals(newSessionId)) {
            clear();
        }
    }

    public static void clear() {
        sessionId = null;
        dimension = null;
        origin = null;
        orientation = null;
        width = 0;
        height = 0;
        ScreenTexture.INSTANCE.scheduleClose();
    }

    public static boolean isTargetScreen(Level level, BlockPos pos) {
        return coordinates(level, pos) != null;
    }

    public static RenderTile renderTile(Level level, BlockPos pos) {
        Coordinates coordinates = coordinates(level, pos);
        if (coordinates == null) {
            return null;
        }
        Direction right = orientation.right();
        Direction up = orientation.up();
        if (coordinates.column > 0
                && sameSection(pos, pos.relative(right.getOpposite()))) {
            return null;
        }
        if (coordinates.row > 0
                && sameSection(pos, pos.relative(up.getOpposite()))) {
            return null;
        }

        int tileWidth = 1;
        while (coordinates.column + tileWidth < width
                && sameSection(pos, pos.relative(right, tileWidth))) {
            tileWidth++;
        }
        int tileHeight = 1;
        while (coordinates.row + tileHeight < height
                && sameSection(pos, pos.relative(up, tileHeight))) {
            tileHeight++;
        }
        return new RenderTile(coordinates.column, coordinates.row,
                tileWidth, tileHeight, width, height);
    }

    private static Coordinates coordinates(Level level, BlockPos pos) {
        if (origin == null || orientation == null || dimension == null
                || !dimension.equals(level.dimension().location().toString())) {
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

    private static int dot(int dx, int dy, int dz, Direction direction) {
        return dx * direction.getStepX() + dy * direction.getStepY()
                + dz * direction.getStepZ();
    }

    private static boolean sameSection(BlockPos first, BlockPos second) {
        return SectionPos.blockToSectionCoord(first.getX())
                == SectionPos.blockToSectionCoord(second.getX())
                && SectionPos.blockToSectionCoord(first.getY())
                == SectionPos.blockToSectionCoord(second.getY())
                && SectionPos.blockToSectionCoord(first.getZ())
                == SectionPos.blockToSectionCoord(second.getZ());
    }

    private record Coordinates(int column, int row) {
    }

    public record RenderTile(int column, int row, int width, int height,
                             int screenWidth, int screenHeight) {
    }
}
