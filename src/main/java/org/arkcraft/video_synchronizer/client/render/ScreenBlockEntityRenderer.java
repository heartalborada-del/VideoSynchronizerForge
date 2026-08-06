package org.arkcraft.video_synchronizer.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.arkcraft.video_synchronizer.Main;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.arkcraft.video_synchronizer.block.ScreenBlockEntity;
import org.arkcraft.video_synchronizer.block.ScreenOrientation;
import org.arkcraft.video_synchronizer.client.ClientScreenTarget;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.concurrent.TimeUnit;

public final class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
    private static final float BORDER_WIDTH = 1.0F / 16.0F;
    private static final float BORDER_OFFSET = 0.006F;
    private static final float BORDER_SIDE_OFFSET = 0.002F;
    private static final float SCREEN_BACK_DISTANCE = 0.5F;
    private static final float VIDEO_OFFSET = 0.012F;
    private static final long DEBUG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final ResourceLocation BORDER_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            "minecraft", "textures/block/gray_concrete.png");

    private long statsStartNanos;
    private long statsCalls;
    private long statsVideoTiles;
    private long statsBorderBlocks;
    private long statsMissingTexture;

    public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public boolean shouldRender(ScreenBlockEntity blockEntity, Vec3 cameraPosition) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            return false;
        }
        boolean targetScreen = ClientScreenTarget.isTargetScreen(level, blockEntity.getBlockPos());
        boolean hasContent = targetScreen
                ? ClientScreenTarget.renderTile(level, blockEntity.getBlockPos()) != null
                : hasVisibleBorder(blockEntity);
        return hasContent && BlockEntityRenderer.super.shouldRender(blockEntity, cameraPosition);
    }

    @Override
    public boolean shouldRenderOffScreen(ScreenBlockEntity blockEntity) {
        return false;
    }

    @Override
    public int getViewDistance() {
        // Loaded render sections remain the real limit; remove the vanilla 64-block BER cutoff.
        return Integer.MAX_VALUE;
    }

    @Override
    public void render(ScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        beginDebugSample();
        Level level = blockEntity.getLevel();
        if (level == null) {
            logDebugStats();
            return;
        }
        ScreenOrientation orientation = orientation(blockEntity.getBlockState());
        ClientScreenTarget.RenderTile tile = ClientScreenTarget.renderTile(
                level, blockEntity.getBlockPos());
        if (tile != null) {
            renderVideoTile(tile, orientation, poseStack.last(), buffers);
        } else if (!ClientScreenTarget.isTargetScreen(level, blockEntity.getBlockPos())) {
            renderBorder(blockEntity, orientation, poseStack.last(), buffers, packedLight);
        }
        logDebugStats();
    }

    private void renderVideoTile(ClientScreenTarget.RenderTile tile, ScreenOrientation orientation,
                                 PoseStack.Pose pose, MultiBufferSource buffers) {
        ScreenTexture screenTexture = ScreenTexture.forSession(tile.sessionId());
        if (screenTexture == null) {
            statsMissingTexture++;
            return;
        }
        ResourceLocation texture = screenTexture.get();
        if (texture == null) {
            statsMissingTexture++;
            return;
        }
        Fit fit = fit(tile.screenWidth(), tile.screenHeight(), screenTexture.aspectRatio());
        float globalX0 = Math.max(fit.x0, tile.column());
        float globalX1 = Math.min(fit.x1, tile.column() + tile.width());
        float globalY0 = Math.max(fit.y0, tile.row());
        float globalY1 = Math.min(fit.y1, tile.row() + tile.height());
        if (globalX1 <= globalX0 || globalY1 <= globalY0) {
            return;
        }

        float u0 = inverseLerp(fit.x0, fit.x1, globalX0);
        float u1 = inverseLerp(fit.x0, fit.x1, globalX1);
        float vBottom = 1.0F - inverseLerp(fit.y0, fit.y1, globalY0);
        float vTop = 1.0F - inverseLerp(fit.y0, fit.y1, globalY1);
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCullZOffset(texture));
        rectangle(consumer, pose.pose(), pose.normal(), orientation,
                globalX0 - tile.column(), globalX1 - tile.column(),
                globalY0 - tile.row(), globalY1 - tile.row(), VIDEO_OFFSET,
                u0, u1, vTop, vBottom, LightTexture.FULL_BRIGHT);
        statsVideoTiles++;
    }

    private void renderBorder(ScreenBlockEntity blockEntity, ScreenOrientation orientation,
                              PoseStack.Pose pose, MultiBufferSource buffers, int packedLight) {
        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();
        VertexConsumer consumer = buffers.getBuffer(
                RenderType.entityCutoutNoCullZOffset(BORDER_TEXTURE));
        boolean drawn = false;
        if (!connects(level, pos, orientation, orientation.right().getOpposite())) {
            rectangle(consumer, pose.pose(), pose.normal(), orientation,
                    -BORDER_SIDE_OFFSET, BORDER_WIDTH, 0.0F, 1.0F, BORDER_OFFSET,
                    0.0F, 1.0F, 0.0F, 1.0F, packedLight);
            verticalWrap(consumer, pose.pose(), pose.normal(), orientation,
                    -BORDER_SIDE_OFFSET, orientation.right().getOpposite(), packedLight);
            drawn = true;
        }
        if (!connects(level, pos, orientation, orientation.right())) {
            rectangle(consumer, pose.pose(), pose.normal(), orientation,
                    1.0F - BORDER_WIDTH, 1.0F + BORDER_SIDE_OFFSET,
                    0.0F, 1.0F, BORDER_OFFSET,
                    0.0F, 1.0F, 0.0F, 1.0F, packedLight);
            verticalWrap(consumer, pose.pose(), pose.normal(), orientation,
                    1.0F + BORDER_SIDE_OFFSET, orientation.right(), packedLight);
            drawn = true;
        }
        if (!connects(level, pos, orientation, orientation.up().getOpposite())) {
            rectangle(consumer, pose.pose(), pose.normal(), orientation,
                    0.0F, 1.0F, -BORDER_SIDE_OFFSET, BORDER_WIDTH, BORDER_OFFSET,
                    0.0F, 1.0F, 0.0F, 1.0F, packedLight);
            horizontalWrap(consumer, pose.pose(), pose.normal(), orientation,
                    -BORDER_SIDE_OFFSET, orientation.up().getOpposite(), packedLight);
            drawn = true;
        }
        if (!connects(level, pos, orientation, orientation.up())) {
            rectangle(consumer, pose.pose(), pose.normal(), orientation,
                    0.0F, 1.0F, 1.0F - BORDER_WIDTH,
                    1.0F + BORDER_SIDE_OFFSET, BORDER_OFFSET,
                    0.0F, 1.0F, 0.0F, 1.0F, packedLight);
            horizontalWrap(consumer, pose.pose(), pose.normal(), orientation,
                    1.0F + BORDER_SIDE_OFFSET, orientation.up(), packedLight);
            drawn = true;
        }
        if (drawn) {
            statsBorderBlocks++;
        }
    }

    private static boolean hasVisibleBorder(ScreenBlockEntity blockEntity) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            return false;
        }
        ScreenOrientation orientation = orientation(blockEntity.getBlockState());
        BlockPos pos = blockEntity.getBlockPos();
        return !connects(level, pos, orientation, orientation.right().getOpposite())
                || !connects(level, pos, orientation, orientation.right())
                || !connects(level, pos, orientation, orientation.up().getOpposite())
                || !connects(level, pos, orientation, orientation.up());
    }

    private static boolean connects(Level level, BlockPos pos, ScreenOrientation orientation,
                                    Direction direction) {
        BlockState neighbor = level.getBlockState(pos.relative(direction));
        return neighbor.getBlock() instanceof ScreenBlock
                && orientation(neighbor).equals(orientation);
    }

    private static ScreenOrientation orientation(BlockState state) {
        return ScreenOrientation.of(state.getValue(ScreenBlock.FACING),
                state.getValue(ScreenBlock.SCREEN_UP));
    }

    private static void rectangle(VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix,
                                  ScreenOrientation orientation,
                                  float x0, float x1, float y0, float y1, float surfaceOffset,
                                  float u0, float u1, float vTop, float vBottom, int packedLight) {
        Direction facing = orientation.facing();
        Direction right = orientation.right();
        Direction up = orientation.up();
        float surfaceDistance = 0.375F - surfaceOffset;
        float centerX = 0.5F - facing.getStepX() * surfaceDistance;
        float centerY = 0.5F - facing.getStepY() * surfaceDistance;
        float centerZ = 0.5F - facing.getStepZ() * surfaceDistance;
        float px0 = centerX + right.getStepX() * (x0 - 0.5F) + up.getStepX() * (y0 - 0.5F);
        float py0 = centerY + right.getStepY() * (x0 - 0.5F) + up.getStepY() * (y0 - 0.5F);
        float pz0 = centerZ + right.getStepZ() * (x0 - 0.5F) + up.getStepZ() * (y0 - 0.5F);
        float px1 = centerX + right.getStepX() * (x1 - 0.5F) + up.getStepX() * (y0 - 0.5F);
        float py1 = centerY + right.getStepY() * (x1 - 0.5F) + up.getStepY() * (y0 - 0.5F);
        float pz1 = centerZ + right.getStepZ() * (x1 - 0.5F) + up.getStepZ() * (y0 - 0.5F);
        float px2 = centerX + right.getStepX() * (x1 - 0.5F) + up.getStepX() * (y1 - 0.5F);
        float py2 = centerY + right.getStepY() * (x1 - 0.5F) + up.getStepY() * (y1 - 0.5F);
        float pz2 = centerZ + right.getStepZ() * (x1 - 0.5F) + up.getStepZ() * (y1 - 0.5F);
        float px3 = centerX + right.getStepX() * (x0 - 0.5F) + up.getStepX() * (y1 - 0.5F);
        float py3 = centerY + right.getStepY() * (x0 - 0.5F) + up.getStepY() * (y1 - 0.5F);
        float pz3 = centerZ + right.getStepZ() * (x0 - 0.5F) + up.getStepZ() * (y1 - 0.5F);
        vertex(consumer, matrix, normalMatrix, px0, py0, pz0, u0, vBottom,
                facing, packedLight);
        vertex(consumer, matrix, normalMatrix, px1, py1, pz1, u1, vBottom,
                facing, packedLight);
        vertex(consumer, matrix, normalMatrix, px2, py2, pz2, u1, vTop,
                facing, packedLight);
        vertex(consumer, matrix, normalMatrix, px3, py3, pz3, u0, vTop,
                facing, packedLight);
    }

    private static void verticalWrap(VertexConsumer consumer, Matrix4f matrix,
                                     Matrix3f normalMatrix, ScreenOrientation orientation,
                                     float x, Direction normal, int packedLight) {
        float frontDistance = 0.375F - BORDER_OFFSET;
        depthRectangle(consumer, matrix, normalMatrix, orientation,
                x, -BORDER_SIDE_OFFSET, frontDistance,
                x, -BORDER_SIDE_OFFSET, SCREEN_BACK_DISTANCE,
                x, 1.0F + BORDER_SIDE_OFFSET, SCREEN_BACK_DISTANCE,
                x, 1.0F + BORDER_SIDE_OFFSET, frontDistance,
                normal, packedLight);
    }

    private static void horizontalWrap(VertexConsumer consumer, Matrix4f matrix,
                                       Matrix3f normalMatrix, ScreenOrientation orientation,
                                       float y, Direction normal, int packedLight) {
        float frontDistance = 0.375F - BORDER_OFFSET;
        depthRectangle(consumer, matrix, normalMatrix, orientation,
                -BORDER_SIDE_OFFSET, y, frontDistance,
                -BORDER_SIDE_OFFSET, y, SCREEN_BACK_DISTANCE,
                1.0F + BORDER_SIDE_OFFSET, y, SCREEN_BACK_DISTANCE,
                1.0F + BORDER_SIDE_OFFSET, y, frontDistance,
                normal, packedLight);
    }

    private static void depthRectangle(VertexConsumer consumer, Matrix4f matrix,
                                       Matrix3f normalMatrix, ScreenOrientation orientation,
                                       float x0, float y0, float depth0,
                                       float x1, float y1, float depth1,
                                       float x2, float y2, float depth2,
                                       float x3, float y3, float depth3,
                                       Direction normal, int packedLight) {
        depthVertex(consumer, matrix, normalMatrix, orientation,
                x0, y0, depth0, 0.0F, 1.0F, normal, packedLight);
        depthVertex(consumer, matrix, normalMatrix, orientation,
                x1, y1, depth1, 1.0F, 1.0F, normal, packedLight);
        depthVertex(consumer, matrix, normalMatrix, orientation,
                x2, y2, depth2, 1.0F, 0.0F, normal, packedLight);
        depthVertex(consumer, matrix, normalMatrix, orientation,
                x3, y3, depth3, 0.0F, 0.0F, normal, packedLight);
    }

    private static void depthVertex(VertexConsumer consumer, Matrix4f matrix,
                                    Matrix3f normalMatrix, ScreenOrientation orientation,
                                    float x, float y, float depth, float u, float v,
                                    Direction normal, int packedLight) {
        Direction facing = orientation.facing();
        Direction right = orientation.right();
        Direction up = orientation.up();
        float px = 0.5F + right.getStepX() * (x - 0.5F) + up.getStepX() * (y - 0.5F)
                - facing.getStepX() * depth;
        float py = 0.5F + right.getStepY() * (x - 0.5F) + up.getStepY() * (y - 0.5F)
                - facing.getStepY() * depth;
        float pz = 0.5F + right.getStepZ() * (x - 0.5F) + up.getStepZ() * (y - 0.5F)
                - facing.getStepZ() * depth;
        vertex(consumer, matrix, normalMatrix, px, py, pz, u, v, normal, packedLight);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix,
                               float x, float y, float z, float u, float v,
                               Direction normal, int packedLight) {
        consumer.vertex(matrix, x, y, z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(normalMatrix, normal.getStepX(), normal.getStepY(), normal.getStepZ())
                .endVertex();
    }

    private static Fit fit(int width, int height, float videoAspect) {
        float screenAspect = width / (float) height;
        float activeX0 = 0.0F;
        float activeX1 = width;
        float activeY0 = 0.0F;
        float activeY1 = height;
        if (screenAspect > videoAspect) {
            float activeWidth = height * videoAspect;
            activeX0 = (width - activeWidth) / 2.0F;
            activeX1 = activeX0 + activeWidth;
        } else {
            float activeHeight = width / videoAspect;
            activeY0 = (height - activeHeight) / 2.0F;
            activeY1 = activeY0 + activeHeight;
        }
        return new Fit(activeX0, activeX1, activeY0, activeY1);
    }

    private static float inverseLerp(float start, float end, float value) {
        return (value - start) / (end - start);
    }

    private void beginDebugSample() {
        if (statsStartNanos == 0L) {
            statsStartNanos = System.nanoTime();
        }
        statsCalls++;
    }

    private void logDebugStats() {
        long now = System.nanoTime();
        long elapsedNanos = now - statsStartNanos;
        if (elapsedNanos < DEBUG_INTERVAL_NANOS) {
            return;
        }
        Main.LOGGER.debug("Screen render stats: calls={}, videoTiles={}, borderBlocks={}, "
                        + "missingTexture={}",
                statsCalls, statsVideoTiles, statsBorderBlocks, statsMissingTexture);
        statsStartNanos = now;
        statsCalls = 0L;
        statsVideoTiles = 0L;
        statsBorderBlocks = 0L;
        statsMissingTexture = 0L;
    }

    private record Fit(float x0, float x1, float y0, float y1) {
    }
}
