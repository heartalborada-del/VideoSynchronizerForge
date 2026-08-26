package org.arkcraft.video_synchronizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.arkcraft.video_synchronizer.client.ClientVideoState;
import org.arkcraft.video_synchronizer.client.render.ScreenTexture;

import java.util.List;
import java.util.Locale;

/** Small in-game OpenGL preview for checking the uploaded playback texture. */
public final class OpenGLTestScreen extends Screen {
    private static final int PREVIEW_MARGIN = 12;
    private List<ClientVideoState.DebugSession> sessions = List.of();
    private int selectedIndex;
    private Button previousButton;
    private Button nextButton;

    private OpenGLTestScreen() {
        super(Component.translatable("gui.video_synchronizer.opengl_test.title"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new OpenGLTestScreen());
    }

    @Override
    protected void init() {
        int navigationY = Math.max(24, height - 28);
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"), button -> select(-1))
                .bounds(width / 2 - 74, navigationY, 20, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), button -> select(1))
                .bounds(width / 2 + 54, navigationY, 20, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(width / 2 - 48, navigationY, 96, 20).build());
    }

    private void select(int delta) {
        if (sessions.isEmpty()) {
            return;
        }
        selectedIndex = Math.floorMod(selectedIndex + delta, sessions.size());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        sessions = ClientVideoState.debugSessions();
        if (selectedIndex >= sessions.size()) {
            selectedIndex = Math.max(0, sessions.size() - 1);
        }
        if (previousButton != null) {
            previousButton.active = sessions.size() > 1;
            nextButton.active = sessions.size() > 1;
        }

        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        if (sessions.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(
                    "gui.video_synchronizer.opengl_test.empty"), width / 2, height / 2 - 10,
                    0xA0A0A0);
            graphics.drawCenteredString(font, Component.translatable(
                    "gui.video_synchronizer.opengl_test.controls"), width / 2, height / 2 + 8,
                    0x707070);
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        ClientVideoState.DebugSession session = sessions.get(selectedIndex);
        ScreenTexture.DebugInfo textureInfo = session.textureInfo();
        String sessionLabel = Component.translatable("gui.video_synchronizer.opengl_test.session",
                selectedIndex + 1, sessions.size(), fit(session.videoId(), 180)).getString();
        graphics.drawCenteredString(font, sessionLabel, width / 2, 26, 0xD0D0D0);
        drawStatus(graphics, session, textureInfo);
        drawPreview(graphics, textureInfo);
        graphics.drawCenteredString(font, Component.translatable(
                "gui.video_synchronizer.opengl_test.controls"), width / 2, height - 40,
                0x707070);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawStatus(GuiGraphics graphics, ClientVideoState.DebugSession session,
                            ScreenTexture.DebugInfo textureInfo) {
        String playbackState = session.waitingForClients() || (session.decoderLoaded()
                && !session.playbackReady())
                ? Component.translatable("gui.video_synchronizer.opengl_test.state.buffering").getString()
                : session.playing()
                ? Component.translatable("gui.video_synchronizer.opengl_test.state.playing").getString()
                : Component.translatable("gui.video_synchronizer.opengl_test.state.paused").getString();
        if (session.live()) {
            playbackState = Component.translatable(
                    "gui.video_synchronizer.opengl_test.state.live", playbackState).getString();
        }
        String decoderState = session.decoderLoaded()
                ? Component.translatable("gui.video_synchronizer.opengl_test.state.loaded").getString()
                : Component.translatable("gui.video_synchronizer.opengl_test.state.unloaded").getString();
        String textureState = textureInfo != null && textureInfo.location() != null
                ? Component.translatable("gui.video_synchronizer.opengl_test.state.ready").getString()
                : Component.translatable("gui.video_synchronizer.opengl_test.state.pending").getString();
        String status = Component.translatable("gui.video_synchronizer.opengl_test.status",
                playbackState, formatTime(session.positionMs()), formatDuration(session.durationMs()),
                decoderState, textureState).getString();
        graphics.drawCenteredString(font, fit(status, width - 24), width / 2, 40, 0xE0E0E0);

        int queuedFrames = textureInfo == null ? 0 : textureInfo.bufferStats().queuedFrames();
        long uploadedFrames = textureInfo == null ? 0L : textureInfo.uploadedFrames();
        String texture = textureInfo == null ? "-" : textureInfo.width() + "x" + textureInfo.height();
        String queue = Component.translatable("gui.video_synchronizer.opengl_test.texture",
                texture, uploadedFrames, queuedFrames).getString();
        graphics.drawCenteredString(font, fit(queue, width - 24), width / 2, 52, 0xA0A0A0);
    }

    private void drawPreview(GuiGraphics graphics, ScreenTexture.DebugInfo textureInfo) {
        int top = 70;
        int bottom = Math.max(top + 32, height - 58);
        int availableWidth = Math.max(32, width - PREVIEW_MARGIN * 2);
        int availableHeight = Math.max(32, bottom - top);
        graphics.fill(PREVIEW_MARGIN, top, PREVIEW_MARGIN + availableWidth,
                top + availableHeight, 0xFF101010);
        if (textureInfo == null || textureInfo.location() == null
                || textureInfo.width() <= 0 || textureInfo.height() <= 0) {
            graphics.drawCenteredString(font, Component.translatable(
                    "gui.video_synchronizer.opengl_test.pending"), width / 2,
                    top + availableHeight / 2 - 4, 0x707070);
            return;
        }

        float aspect = textureInfo.width() / (float) textureInfo.height();
        int imageWidth = availableWidth;
        int imageHeight = Math.round(imageWidth / aspect);
        if (imageHeight > availableHeight) {
            imageHeight = availableHeight;
            imageWidth = Math.round(imageHeight * aspect);
        }
        int imageX = PREVIEW_MARGIN + (availableWidth - imageWidth) / 2;
        int imageY = top + (availableHeight - imageHeight) / 2;
        ResourceLocation location = textureInfo.location();
        graphics.blit(location, imageX, imageY, imageWidth, imageHeight, 0.0F, 0.0F,
                textureInfo.width(), textureInfo.height(), textureInfo.width(), textureInfo.height());
    }

    private String formatDuration(long durationMs) {
        return durationMs > 0L ? formatTime(durationMs) : "--:--:--";
    }

    private String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", totalSeconds / 3_600L,
                totalSeconds % 3_600L / 60L, totalSeconds % 60L);
    }

    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "...";
        return font.plainSubstrByWidth(text, Math.max(1, maxWidth - font.width(suffix))) + suffix;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
