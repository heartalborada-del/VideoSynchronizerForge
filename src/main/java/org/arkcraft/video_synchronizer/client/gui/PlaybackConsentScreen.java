package org.arkcraft.video_synchronizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.network.OpenPlaybackConsentMessage;
import org.arkcraft.video_synchronizer.network.UpdatePlaybackConsentMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;

public final class PlaybackConsentScreen extends Screen {
    private final OpenPlaybackConsentMessage state;

    private PlaybackConsentScreen(OpenPlaybackConsentMessage state) {
        super(Component.translatable("gui.video_synchronizer.consent.title"));
        this.state = state;
    }

    public static void open(OpenPlaybackConsentMessage message) {
        Minecraft.getInstance().setScreen(new PlaybackConsentScreen(message));
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int top = height / 2 - 58;
        int buttonWidth = 96;
        addRenderableWidget(Button.builder(Component.translatable(
                        "gui.video_synchronizer.consent.allow"), button -> update(true))
                .bounds(centerX - 100, top + 78, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(
                        "gui.video_synchronizer.consent.block"), button -> update(false))
                .bounds(centerX + 4, top + 78, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                        button -> onClose())
                .bounds(centerX - 100, top + 102, 200, 20).build());
    }

    private void update(boolean allowed) {
        VideoNetwork.CHANNEL.sendToServer(new UpdatePlaybackConsentMessage(
                state.sourcePos(), state.screenId(), allowed));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int centerX = width / 2;
        int top = height / 2 - 58;
        graphics.drawCenteredString(font, fitLine(title), centerX, top, 0xFFFFFF);
        graphics.drawCenteredString(font, fitLine(Component.translatable(
                        "gui.video_synchronizer.consent.screen", state.screenId())),
                centerX, top + 18, 0xA0A0A0);
        graphics.drawCenteredString(font, fitLine(Component.translatable(
                        "gui.video_synchronizer.consent.owner", state.ownerName())),
                centerX, top + 32, 0xA0A0A0);
        graphics.drawCenteredString(font, fitLine(state.initiatedBy().isBlank()
                        ? Component.translatable("gui.video_synchronizer.consent.source_idle")
                        : Component.translatable("gui.video_synchronizer.consent.source",
                        state.initiatedBy().equals(OpenPlaybackConsentMessage.SERVER_INITIATOR)
                                ? Component.translatable(
                                "gui.video_synchronizer.consent.source_server")
                                : state.initiatedBy())),
                centerX, top + 46, 0xA0A0A0);
        graphics.drawCenteredString(font, fitLine(Component.translatable(state.allowed()
                        ? "gui.video_synchronizer.consent.current_allowed"
                        : "gui.video_synchronizer.consent.current_blocked")),
                centerX, top + 60, state.allowed() ? 0x80FF80 : 0xFF8080);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Component fitLine(Component component) {
        int maxWidth = Math.max(1, width - 20);
        String text = component.getString();
        if (font.width(text) <= maxWidth) {
            return component;
        }
        String suffix = "...";
        return Component.literal(font.plainSubstrByWidth(text,
                Math.max(1, maxWidth - font.width(suffix))) + suffix);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
