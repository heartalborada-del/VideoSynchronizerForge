package org.arkcraft.video_synchronizer.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import org.arkcraft.video_synchronizer.LocalizedArgumentException;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenBlockEntity;
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
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.screen_dimensions",
                    ScreenLayout.MAX_DIMENSION);
        }

        ServerLevel level = player.serverLevel();
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getLookAngle().scale(REACH));
        BlockHitResult hit = level.clip(new ClipContext(start, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            throw new LocalizedArgumentException(
                    "message.video_synchronizer.error.look_at_surface", (int) REACH);
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
                    throw new LocalizedArgumentException(
                            "message.video_synchronizer.error.screen_outside_world");
                }
                BlockState existing = level.getBlockState(pos);
                if (!existing.canBeReplaced()) {
                    throw new LocalizedArgumentException(
                            "message.video_synchronizer.error.screen_obstructed",
                            pos.toShortString());
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

    public static BlockPos createSelection(ServerPlayer player, BlockPos first, BlockPos second,
                                           Direction facing, Direction screenUp,
                                           String dimension, Direction secondFacing) {
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().toString().equals(dimension)) {
            throw new SelectionException("message.video_synchronizer.selection_tool.dimension");
        }
        if (!(level.getBlockState(first).getBlock() instanceof ScreenBlock)
                || !(level.getBlockState(second).getBlock() instanceof ScreenBlock)) {
            throw new SelectionException("message.video_synchronizer.selection_tool.panels_only");
        }
        Direction actualFacing = level.getBlockState(first).getValue(ScreenBlock.FACING);
        Direction actualScreenUp = level.getBlockState(first).getValue(ScreenBlock.SCREEN_UP);
        Direction secondActualFacing = level.getBlockState(second).getValue(ScreenBlock.FACING);
        Direction secondActualScreenUp = level.getBlockState(second).getValue(ScreenBlock.SCREEN_UP);
        if (facing != secondFacing || facing != actualFacing
                || actualFacing != secondActualFacing || screenUp != actualScreenUp
                || actualScreenUp != secondActualScreenUp) {
            throw new SelectionException("message.video_synchronizer.selection_tool.face");
        }
        ScreenOrientation orientation = ScreenOrientation.of(facing, screenUp);
        int dx = second.getX() - first.getX();
        int dy = second.getY() - first.getY();
        int dz = second.getZ() - first.getZ();
        if (dot(dx, dy, dz, facing) != 0) {
            throw new SelectionException("message.video_synchronizer.selection_tool.plane");
        }
        int columnOffset = dot(dx, dy, dz, orientation.right());
        int rowOffset = dot(dx, dy, dz, orientation.up());
        int width = Math.abs(columnOffset) + 1;
        int height = Math.abs(rowOffset) + 1;
        if (width > ScreenLayout.MAX_DIMENSION || height > ScreenLayout.MAX_DIMENSION) {
            throw new SelectionException("message.video_synchronizer.selection_tool.size",
                    ScreenLayout.MAX_DIMENSION);
        }

        BlockPos origin = first.relative(orientation.right(), Math.min(0, columnOffset))
                .relative(orientation.up(), Math.min(0, rowOffset)).immutable();
        List<BlockPos> positions = new ArrayList<>(width * height);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                BlockPos pos = origin.relative(orientation.right(), column)
                        .relative(orientation.up(), row);
                if (!Level.isInSpawnableBounds(pos) || !level.hasChunkAt(pos)) {
                    throw new SelectionException(
                            "message.video_synchronizer.selection_tool.unloaded");
                }
                BlockState state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof ScreenBlock)
                        || state.getValue(ScreenBlock.FACING) != facing
                        || state.getValue(ScreenBlock.SCREEN_UP) != screenUp) {
                    throw new SelectionException(
                            "message.video_synchronizer.selection_tool.missing_panel",
                            pos.toShortString());
                }
                positions.add(pos.immutable());
            }
        }

        return origin;
    }

    private static int dot(int dx, int dy, int dz, Direction direction) {
        return dx * direction.getStepX() + dy * direction.getStepY()
                + dz * direction.getStepZ();
    }

    public static final class SelectionException extends IllegalArgumentException {
        private final String translationKey;
        private final Object[] arguments;

        private SelectionException(String translationKey, Object... arguments) {
            this.translationKey = translationKey;
            this.arguments = arguments;
        }

        public Component component() {
            return Component.translatable(translationKey, arguments);
        }
    }
}
