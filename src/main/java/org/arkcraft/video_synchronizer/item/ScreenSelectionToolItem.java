package org.arkcraft.video_synchronizer.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;
import org.arkcraft.video_synchronizer.LocalizedArgumentException;
import org.arkcraft.video_synchronizer.network.OpenScreenBindingMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.server.ScreenCreator;
import org.arkcraft.video_synchronizer.server.ServerScreenRegistry;
import org.arkcraft.video_synchronizer.server.VideoPermissionService;
import org.arkcraft.video_synchronizer.server.VideoUsagePolicy;
import org.arkcraft.video_synchronizer.block.ScreenBlock;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ScreenSelectionToolItem extends Item {
    private static final String SELECTION_TAG = "ScreenSelection";
    private static final String POS_TAG = "FirstPos";
    private static final String DIMENSION_TAG = "Dimension";
    private static final String FACING_TAG = "Facing";
    private static final String SCREEN_UP_TAG = "ScreenUp";

    public ScreenSelectionToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        if (!VideoPermissionService.canCreate(player)) {
            VideoUsagePolicy.audit(player, "-", "create", false);
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.permission"));
            return InteractionResult.FAIL;
        }

        ItemStack stack = context.getItemInHand();
        if (player.isSecondaryUseActive()) {
            clearSelection(stack);
            ServerScreenRegistry.clearPendingSelection(player.getUUID());
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.cleared"));
            return InteractionResult.SUCCESS;
        }

        BlockPos screenPos = context.getClickedPos();
        if (!(context.getLevel().getBlockState(screenPos).getBlock() instanceof ScreenBlock)) {
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.panels_only"));
            return InteractionResult.FAIL;
        }
        Direction facing = context.getLevel().getBlockState(screenPos)
                .getValue(ScreenBlock.FACING);
        Direction screenUp = context.getLevel().getBlockState(screenPos)
                .getValue(ScreenBlock.SCREEN_UP);
        Selection selection = selection(stack);
        if (selection == null) {
            ServerScreenRegistry.clearPendingSelection(player.getUUID());
            storeSelection(stack, player, screenPos, facing, screenUp);
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.first",
                    screenPos.toShortString()));
            return InteractionResult.SUCCESS;
        }

        try {
            ScreenCreator.createSelection(player, selection.pos,
                    screenPos, selection.facing, selection.screenUp, selection.dimension,
                    facing);
            ServerScreenRegistry.authorizeSelection(player, selection.pos, screenPos);
            clearSelection(stack);
            VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new OpenScreenBindingMessage(selection.pos, screenPos, ""));
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.created"));
            return InteractionResult.SUCCESS;
        } catch (ScreenCreator.SelectionException exception) {
            player.sendSystemMessage(exception.component().copy().withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(LocalizedArgumentException.component(exception).copy()
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return selection(stack) != null || super.isFoil(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(
                "item.video_synchronizer.screen_selection_tool.tooltip")
                .withStyle(ChatFormatting.GRAY));
        Selection selection = selection(stack);
        if (selection != null) {
            tooltip.add(Component.translatable(
                    "item.video_synchronizer.screen_selection_tool.selected",
                    selection.pos.toShortString()).withStyle(ChatFormatting.AQUA));
        }
    }

    private static void storeSelection(ItemStack stack, ServerPlayer player, BlockPos pos,
                                       Direction facing, Direction screenUp) {
        CompoundTag selection = new CompoundTag();
        selection.putLong(POS_TAG, pos.asLong());
        selection.putString(DIMENSION_TAG,
                player.serverLevel().dimension().location().toString());
        selection.putByte(FACING_TAG, (byte) facing.get3DDataValue());
        selection.putByte(SCREEN_UP_TAG, (byte) screenUp.get3DDataValue());
        stack.getOrCreateTag().put(SELECTION_TAG, selection);
    }

    private static Selection selection(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(SELECTION_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag selection = root.getCompound(SELECTION_TAG);
        if (!selection.contains(POS_TAG, Tag.TAG_LONG)
                || !selection.contains(DIMENSION_TAG, Tag.TAG_STRING)) {
            return null;
        }
        return new Selection(BlockPos.of(selection.getLong(POS_TAG)),
                selection.getString(DIMENSION_TAG),
                Direction.from3DDataValue(selection.getByte(FACING_TAG)),
                Direction.from3DDataValue(selection.getByte(SCREEN_UP_TAG)));
    }

    private static void clearSelection(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root != null) {
            root.remove(SELECTION_TAG);
            if (root.isEmpty()) {
                stack.setTag(null);
            }
        }
    }

    private record Selection(BlockPos pos, String dimension, Direction facing,
                             Direction screenUp) {
    }
}
