package org.arkcraft.video_synchronizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.network.OpenVideoManagerMessage;
import org.arkcraft.video_synchronizer.network.VideoManagerActionMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;

import java.util.Locale;

public final class VideoManagerScreen extends Screen {
    private final BlockPos managerPos;
    private String screenId;
    private String url;
    private boolean active;
    private boolean playing;
    private long positionMs;
    private long durationMs;
    private long stateReceivedNanos;

    private EditBox screenIdInput;
    private EditBox urlInput;
    private EditBox positionInput;
    private Button pauseButton;
    private Button resumeButton;
    private Button seekButton;
    private Button stopButton;
    private int formLeft;
    private int formTop;
    private int formWidth;

    private VideoManagerScreen(OpenVideoManagerMessage message) {
        super(Component.translatable("gui.video_synchronizer.manager.title"));
        managerPos = message.pos();
        applyState(message);
    }

    public static void openOrUpdate(OpenVideoManagerMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof VideoManagerScreen screen
                && screen.managerPos.equals(message.pos())) {
            screen.applyState(message);
        } else {
            minecraft.setScreen(new VideoManagerScreen(message));
        }
    }

    @Override
    protected void init() {
        formWidth = Math.min(300, width - 20);
        formLeft = (width - formWidth) / 2;
        formTop = Math.max(18, height / 2 - 98);

        screenIdInput = new EditBox(font, formLeft, formTop + 29, formWidth, 20,
                Component.translatable("gui.video_synchronizer.manager.screen_id"));
        screenIdInput.setMaxLength(32);
        screenIdInput.setFilter(value -> value.matches("[a-zA-Z0-9_-]*"));
        screenIdInput.setValue(screenId);
        addRenderableWidget(screenIdInput);

        urlInput = new EditBox(font, formLeft, formTop + 66, formWidth, 20,
                Component.translatable("gui.video_synchronizer.manager.url"));
        urlInput.setMaxLength(2048);
        urlInput.setValue(url);
        addRenderableWidget(urlInput);

        int seekButtonWidth = 72;
        positionInput = new EditBox(font, formLeft, formTop + 121,
                formWidth - seekButtonWidth - 4, 20,
                Component.translatable("gui.video_synchronizer.manager.position"));
        positionInput.setMaxLength(15);
        positionInput.setFilter(value -> value.matches("[0-9]*"));
        positionInput.setValue(Long.toString(currentPositionMs()));
        addRenderableWidget(positionInput);
        seekButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.seek"),
                button -> send(VideoManagerActionMessage.Action.SEEK))
                .bounds(formLeft + formWidth - seekButtonWidth, formTop + 121,
                        seekButtonWidth, 20).build());

        int halfWidth = (formWidth - 4) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.save"),
                button -> send(VideoManagerActionMessage.Action.SAVE))
                .bounds(formLeft, formTop + 151, halfWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.start"),
                button -> send(VideoManagerActionMessage.Action.START))
                .bounds(formLeft + halfWidth + 4, formTop + 151, halfWidth, 20).build());

        int thirdWidth = (formWidth - 8) / 3;
        pauseButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.pause"),
                button -> send(VideoManagerActionMessage.Action.PAUSE))
                .bounds(formLeft, formTop + 177, thirdWidth, 20).build());
        resumeButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.resume"),
                button -> send(VideoManagerActionMessage.Action.RESUME))
                .bounds(formLeft + thirdWidth + 4, formTop + 177, thirdWidth, 20).build());
        stopButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.stop"),
                button -> send(VideoManagerActionMessage.Action.STOP))
                .bounds(formLeft + (thirdWidth + 4) * 2, formTop + 177, thirdWidth, 20).build());
        setInitialFocus(screenIdInput);
        updateButtonState();
    }

    @Override
    public void tick() {
        super.tick();
        if (active && !positionInput.isFocused()) {
            positionInput.setValue(Long.toString(currentPositionMs()));
        }
        updateButtonState();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, formTop, 0xFFFFFF);
        graphics.drawString(font,
                Component.translatable("gui.video_synchronizer.manager.screen_id"),
                formLeft, formTop + 18, 0xA0A0A0);
        graphics.drawString(font,
                Component.translatable("gui.video_synchronizer.manager.url"),
                formLeft, formTop + 55, 0xA0A0A0);
        Component status = active
                ? Component.translatable(playing
                                ? "gui.video_synchronizer.manager.status_playing"
                                : "gui.video_synchronizer.manager.status_paused",
                        formatTime(currentPositionMs()), formatTime(durationMs))
                : Component.translatable("gui.video_synchronizer.manager.status_idle");
        graphics.drawString(font, status, formLeft, formTop + 94,
                active ? 0x80FF80 : 0xA0A0A0);
        graphics.drawString(font,
                Component.translatable("gui.video_synchronizer.manager.position"),
                formLeft, formTop + 110, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void applyState(OpenVideoManagerMessage message) {
        screenId = message.screenId();
        url = message.url();
        active = message.active();
        playing = message.playing();
        positionMs = message.positionMs();
        durationMs = message.durationMs();
        stateReceivedNanos = System.nanoTime();
        if (screenIdInput != null) {
            screenIdInput.setValue(screenId);
            urlInput.setValue(url);
            positionInput.setValue(Long.toString(positionMs));
            updateButtonState();
        }
    }

    private void send(VideoManagerActionMessage.Action action) {
        long requestedPosition = 0L;
        if (action == VideoManagerActionMessage.Action.SEEK) {
            try {
                requestedPosition = Long.parseLong(positionInput.getValue());
            } catch (NumberFormatException exception) {
                return;
            }
        }
        VideoNetwork.CHANNEL.sendToServer(new VideoManagerActionMessage(
                managerPos, action, screenIdInput.getValue(), urlInput.getValue(),
                requestedPosition));
    }

    private void updateButtonState() {
        if (pauseButton == null) {
            return;
        }
        pauseButton.active = active && playing;
        resumeButton.active = active && !playing;
        seekButton.active = active;
        stopButton.active = active;
    }

    private long currentPositionMs() {
        long current = positionMs;
        if (active && playing) {
            current += (System.nanoTime() - stateReceivedNanos) / 1_000_000L;
        }
        return durationMs > 0L ? Math.min(current, durationMs) : current;
    }

    private static String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
