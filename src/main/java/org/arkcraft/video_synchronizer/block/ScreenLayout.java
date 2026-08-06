package org.arkcraft.video_synchronizer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

public record ScreenLayout(int column, int row, int width, int height) {
    public static final ScreenLayout SINGLE = new ScreenLayout(0, 0, 1, 1);
    public static final int MAX_DIMENSION = 1024;

    public static ScreenLayout detect(BlockGetter level, BlockPos origin, ScreenOrientation orientation) {
        int minColumn = scanHorizontal(level, origin, orientation, -1, MAX_DIMENSION - 1);
        int maxColumn = scanHorizontal(level, origin, orientation, 1,
                MAX_DIMENSION - 1 + minColumn);
        int minRow = scanVertical(level, origin, orientation, minColumn, maxColumn, -1,
                MAX_DIMENSION - 1);
        int maxRow = scanVertical(level, origin, orientation, minColumn, maxColumn, 1,
                MAX_DIMENSION - 1 + minRow);
        return new ScreenLayout(-minColumn, -minRow,
                maxColumn - minColumn + 1, maxRow - minRow + 1);
    }

    public boolean isValid() {
        return width >= 1 && width <= MAX_DIMENSION
                && height >= 1 && height <= MAX_DIMENSION
                && column >= 0 && column < width
                && row >= 0 && row < height;
    }

    private static int scanHorizontal(BlockGetter level, BlockPos origin, ScreenOrientation orientation,
                                      int direction, int maxDistance) {
        int offset = 0;
        for (int distance = 1; distance <= maxDistance; distance++) {
            int candidate = distance * direction;
            if (!isScreen(level, origin.relative(orientation.right(), candidate), orientation)) {
                break;
            }
            offset = candidate;
        }
        return offset;
    }

    private static int scanVertical(BlockGetter level, BlockPos origin, ScreenOrientation orientation,
                                    int minColumn, int maxColumn, int direction, int maxDistance) {
        int offset = 0;
        for (int distance = 1; distance <= maxDistance; distance++) {
            int candidate = distance * direction;
            boolean completeRow = true;
            for (int column = minColumn; column <= maxColumn; column++) {
                BlockPos pos = origin.relative(orientation.right(), column)
                        .relative(orientation.up(), candidate);
                if (!isScreen(level, pos, orientation)) {
                    completeRow = false;
                    break;
                }
            }
            if (!completeRow) {
                break;
            }
            offset = candidate;
        }
        return offset;
    }

    private static boolean isScreen(BlockGetter level, BlockPos pos, ScreenOrientation orientation) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScreenBlock)) {
            return false;
        }
        return ScreenOrientation.of(state.getValue(ScreenBlock.FACING),
                state.getValue(ScreenBlock.SCREEN_UP)).equals(orientation);
    }
}
