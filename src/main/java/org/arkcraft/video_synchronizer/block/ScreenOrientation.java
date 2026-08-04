package org.arkcraft.video_synchronizer.block;

import net.minecraft.core.Direction;

/** Two in-plane axes whose cross product points toward the screen face. */
public record ScreenOrientation(Direction facing, Direction right, Direction up) {
    public static ScreenOrientation of(Direction facing, Direction screenUp) {
        if (facing.getAxis().isHorizontal()) {
            return new ScreenOrientation(facing, facing.getCounterClockWise(), Direction.UP);
        }
        Direction horizontalUp = screenUp.getAxis().isHorizontal() ? screenUp : Direction.NORTH;
        Direction right = facing == Direction.UP
                ? horizontalUp.getClockWise()
                : horizontalUp.getCounterClockWise();
        return new ScreenOrientation(facing, right, horizontalUp);
    }
}
