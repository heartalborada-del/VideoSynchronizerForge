package org.arkcraft.video_synchronizer.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenLayout;
import org.arkcraft.video_synchronizer.block.ScreenOrientation;

import java.util.ArrayList;
import java.util.List;

public final class ScreenCreator {
    private static final double REACH = 32.0D;

    private ScreenCreator() {
    }

    /** Creates a screen on the wall, floor, or ceiling face under the player's crosshair. */
    public static BlockPos create(ServerPlayer player, int width, int height) {
        if (width < 1 || width > ScreenLayout.MAX_DIMENSION
                || height < 1 || height > ScreenLayout.MAX_DIMENSION) {
            throw new IllegalArgumentException("Screen dimensions must be between 1 and "
                    + ScreenLayout.MAX_DIMENSION);
        }

        ServerLevel level = player.serverLevel();
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(REACH));
        BlockHitResult hit = level.clip(new ClipContext(start, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            throw new IllegalArgumentException("Look at a solid surface within 32 blocks");
        }

        Direction facing = hit.getDirection();
        Direction screenUp = player.getDirection();
        ScreenOrientation orientation = ScreenOrientation.of(facing, screenUp);
        BlockPos center = hit.getBlockPos().relative(facing);
        int firstColumn = -width / 2;
        int firstRow = -height / 2;
        List<BlockPos> positions = new ArrayList<>(width * height);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                BlockPos pos = center.relative(orientation.right(), firstColumn + column)
                        .relative(orientation.up(), firstRow + row);
                if (!Level.isInSpawnableBounds(pos) || !level.hasChunkAt(pos)) {
                    throw new IllegalArgumentException("Part of the screen is outside the loaded world");
                }
                BlockState existing = level.getBlockState(pos);
                if (!existing.canBeReplaced()) {
                    throw new IllegalArgumentException("The screen area is obstructed at " + pos.toShortString());
                }
                positions.add(pos.immutable());
            }
        }

        BlockState screen = Main.SCREEN_BLOCK.get().defaultBlockState()
                .setValue(ScreenBlock.FACING, facing)
                .setValue(ScreenBlock.SCREEN_UP, screenUp);
        for (BlockPos pos : positions) {
            level.setBlock(pos, screen, Block.UPDATE_CLIENTS);
        }
        return center;
    }
}
