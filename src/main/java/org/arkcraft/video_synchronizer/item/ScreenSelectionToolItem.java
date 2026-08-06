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
import org.arkcraft.video_synchronizer.network.OpenScreenBindingMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.server.ScreenCreator;
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
        if (!player.hasPermissions(2)) {
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.permission"));
            return InteractionResult.FAIL;
        }

        ItemStack stack = context.getItemInHand();
        if (player.isSecondaryUseActive()) {
            clearSelection(stack);
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.cleared"));
            return InteractionResult.SUCCESS;
        }

        BlockPos screenPos = context.getClickedPos().relative(context.getClickedFace());
        Selection selection = selection(stack);
        if (selection == null) {
            storeSelection(stack, player, screenPos, context.getClickedFace());
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.first",
                    screenPos.toShortString()));
            return InteractionResult.SUCCESS;
        }

        try {
            BlockPos anchor = ScreenCreator.createSelection(player, selection.pos,
                    screenPos, selection.facing, selection.screenUp, selection.dimension,
                    context.getClickedFace());
            clearSelection(stack);
            VideoNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new OpenScreenBindingMessage(anchor, ""));
            player.sendSystemMessage(Component.translatable(
                    "message.video_synchronizer.selection_tool.created"));
            return InteractionResult.SUCCESS;
        } catch (ScreenCreator.SelectionException exception) {
            player.sendSystemMessage(exception.component().copy().withStyle(ChatFormatting.RED));
            return InteractionResult.FAIL;
        } catch (IllegalArgumentException exception) {
            player.sendSystemMessage(Component.literal(exception.getMessage())
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
                                       Direction facing) {
        CompoundTag selection = new CompoundTag();
        selection.putLong(POS_TAG, pos.asLong());
        selection.putString(DIMENSION_TAG,
                player.serverLevel().dimension().location().toString());
        selection.putByte(FACING_TAG, (byte) facing.get3DDataValue());
        selection.putByte(SCREEN_UP_TAG, (byte) player.getDirection().get3DDataValue());
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
