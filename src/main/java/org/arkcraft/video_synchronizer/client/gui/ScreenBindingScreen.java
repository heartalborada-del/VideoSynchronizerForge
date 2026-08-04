package org.arkcraft.video_synchronizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.network.UpdateScreenBindingMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;

public final class ScreenBindingScreen extends Screen {
    private final BlockPos screenPos;
    private final String initialId;
    private EditBox idInput;

    private ScreenBindingScreen(BlockPos screenPos, String initialId) {
        super(Component.literal("Screen Binding"));
        this.screenPos = screenPos;
        this.initialId = initialId;
    }

    public static void open(BlockPos pos, String screenId) {
        Minecraft.getInstance().setScreen(new ScreenBindingScreen(pos, screenId));
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int top = height / 2 - 45;
        idInput = new EditBox(font, centerX - 100, top, 200, 20, Component.literal("Screen ID"));
        idInput.setMaxLength(32);
        idInput.setFilter(value -> value.matches("[a-zA-Z0-9_-]*"));
        idInput.setValue(initialId);
        addRenderableWidget(idInput);
        setInitialFocus(idInput);

        addRenderableWidget(Button.builder(Component.literal("Bind"), button -> {
            VideoNetwork.CHANNEL.sendToServer(new UpdateScreenBindingMessage(
                    screenPos, idInput.getValue(), true));
            onClose();
        }).bounds(centerX - 100, top + 30, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Unbind"), button -> {
            VideoNetwork.CHANNEL.sendToServer(new UpdateScreenBindingMessage(
                    screenPos, idInput.getValue(), false));
            onClose();
        }).bounds(centerX + 4, top + 30, 96, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 70, 0xFFFFFF);
        graphics.drawString(font, "Screen ID", width / 2 - 100, height / 2 - 58, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
