package org.arkcraft.video_synchronizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.arkcraft.video_synchronizer.client.gui.ScreenBindingScreen;

import java.util.function.Supplier;

public record OpenScreenBindingMessage(BlockPos pos, BlockPos selectionEnd, String screenId) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeBlockPos(selectionEnd);
        buf.writeUtf(screenId, 32);
    }

    public static OpenScreenBindingMessage decode(FriendlyByteBuf buf) {
        return new OpenScreenBindingMessage(buf.readBlockPos(), buf.readBlockPos(),
                buf.readUtf(32));
    }

    public static void handle(OpenScreenBindingMessage message, Supplier<NetworkEvent.Context> context) {
        ScreenBindingScreen.open(message.pos(), message.selectionEnd(), message.screenId());
    }
}
