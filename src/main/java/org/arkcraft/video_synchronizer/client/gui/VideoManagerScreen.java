package org.arkcraft.video_synchronizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.network.OpenVideoManagerMessage;
import org.arkcraft.video_synchronizer.network.AudioPlaybackMode;
import org.arkcraft.video_synchronizer.network.MediaRequestOptions;
import org.arkcraft.video_synchronizer.network.VideoManagerActionMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.network.VideoPixelFormat;

import java.util.Locale;

public final class VideoManagerScreen extends Screen {
    private final BlockPos managerPos;
    private String screenId;
    private String videoUrl;
    private String audioUrl;
    private String requestHeaders;
    private String cookie;
    private boolean splitStreams;
    private boolean disableScaling;
    private int videoPipeLanes;
    private VideoPixelFormat videoPixelFormat;
    private double audioRange;
    private AudioPlaybackMode audioPlaybackMode;
    private boolean active;
    private boolean live;
    private boolean playing;
    private boolean waitingForClients;
    private long positionMs;
    private long durationMs;
    private long stateReceivedNanos;

    private EditBox screenIdInput;
    private EditBox videoUrlInput;
    private EditBox audioUrlInput;
    private EditBox positionInput;
    private EditBox audioRangeInput;
    private Button audioModeButton;
    private Button streamModeButton;
    private Button scalingButton;
    private Button pipeLanesButton;
    private Button pixelFormatButton;
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

    public static void acceptPlaybackState(String screenId, long positionMs, long durationMs,
                                           boolean live, boolean playing,
                                           boolean waitingForClients) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof VideoManagerScreen screen && screen.active
                && screen.screenId.equals(screenId)) {
            screen.positionMs = positionMs;
            screen.live = live;
            if (durationMs > 0L) {
                screen.durationMs = durationMs;
            }
            screen.playing = playing;
            screen.waitingForClients = waitingForClients;
            screen.stateReceivedNanos = System.nanoTime();
            if (screen.positionInput != null && !screen.positionInput.isFocused()) {
                screen.positionInput.setValue(formatTime(positionMs));
            }
            screen.updateButtonState();
        }
    }

    public static void acceptStop(String screenId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof VideoManagerScreen screen && screen.active
                && screen.screenId.equals(screenId)) {
            screen.active = false;
            screen.playing = false;
            screen.waitingForClients = false;
            screen.positionMs = 0L;
            screen.durationMs = 0L;
            screen.live = false;
            screen.updateButtonState();
        }
    }

    @Override
    protected void init() {
        formWidth = Math.min(300, width - 20);
        formLeft = (width - formWidth) / 2;
        formTop = Math.max(3, (height - 236) / 2);

        screenIdInput = new EditBox(font, formLeft, formTop + 24, formWidth, 20,
                Component.translatable("gui.video_synchronizer.manager.screen_id"));
        screenIdInput.setMaxLength(32);
        screenIdInput.setFilter(value -> value.matches("[a-zA-Z0-9_-]*"));
        screenIdInput.setValue(screenId);
        addRenderableWidget(screenIdInput);

        int modeButtonSize = 20;
        int modeButtonGap = 4;
        videoUrlInput = new EditBox(font, formLeft, formTop + 59,
                formWidth - modeButtonSize - modeButtonGap, 20,
                Component.translatable("gui.video_synchronizer.manager.video_url"));
        videoUrlInput.setMaxLength(2048);
        videoUrlInput.setValue(videoUrl);
        addRenderableWidget(videoUrlInput);
        streamModeButton = addRenderableWidget(Button.builder(
                Component.empty(), button -> {
                    splitStreams = !splitStreams;
                    updateStreamModeControls();
                }).bounds(formLeft + formWidth - modeButtonSize, formTop + 59,
                        modeButtonSize, modeButtonSize).build());

        audioUrlInput = new EditBox(font, formLeft, formTop + 92, formWidth, 20,
                Component.translatable("gui.video_synchronizer.manager.audio_url"));
        audioUrlInput.setMaxLength(2048);
        audioUrlInput.setValue(audioUrl);
        addRenderableWidget(audioUrlInput);

        int optionButtonWidth = (formWidth - 16) / 5;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.request_headers"),
                button -> openHeadersEditor())
                .bounds(formLeft, formTop + 116, optionButtonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.cookie"),
                button -> openCookieEditor())
                .bounds(formLeft + optionButtonWidth + 4, formTop + 116,
                        optionButtonWidth, 20).build());

        scalingButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
                    disableScaling = !disableScaling;
                    updateScalingButton();
                }).bounds(formLeft + (optionButtonWidth + 4) * 2, formTop + 116,
                        optionButtonWidth, 20).build());
        pipeLanesButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
                    videoPipeLanes = nextVideoPipeLanes(videoPipeLanes);
                    updatePipeLanesButton();
                }).bounds(formLeft + (optionButtonWidth + 4) * 3, formTop + 116,
                        optionButtonWidth, 20).build());
        pixelFormatButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
                    videoPixelFormat = videoPixelFormat.next();
                    updatePixelFormatButton();
                }).bounds(formLeft + (optionButtonWidth + 4) * 4, formTop + 116,
                        optionButtonWidth, 20).build());

        int audioRangeWidth = 64;
        int audioModeWidth = 68;
        int rowGap = 4;
        int positionSectionWidth = formWidth - audioRangeWidth - audioModeWidth - rowGap * 2;
        int seekButtonWidth = 60;
        positionInput = new EditBox(font, formLeft, formTop + 165,
                positionSectionWidth - seekButtonWidth - rowGap, 20,
                Component.translatable("gui.video_synchronizer.manager.position"));
        positionInput.setMaxLength(12);
        positionInput.setFilter(value -> value.matches("[0-9:]*"));
        positionInput.setHint(Component.literal("--:--:--"));
        positionInput.setValue(formatTime(currentPositionMs()));
        addRenderableWidget(positionInput);
        seekButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.seek"),
                button -> send(VideoManagerActionMessage.Action.SEEK))
                .bounds(formLeft + positionSectionWidth - seekButtonWidth, formTop + 165,
                        seekButtonWidth, 20).build());

        audioRangeInput = new EditBox(font,
                formLeft + positionSectionWidth + rowGap, formTop + 165,
                audioRangeWidth, 20,
                Component.translatable("gui.video_synchronizer.manager.audio_range"));
        audioRangeInput.setMaxLength(6);
        audioRangeInput.setFilter(value -> value.matches("[0-9.]*"));
        audioRangeInput.setValue(formatAudioRange(audioRange));
        audioRangeInput.setTooltip(Tooltip.create(Component.translatable(
                "gui.video_synchronizer.manager.audio_range_tooltip")));
        addRenderableWidget(audioRangeInput);

        audioModeButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
                    audioPlaybackMode = audioPlaybackMode.next();
                    updateAudioModeButton();
                }).bounds(audioRangeInput.getX() + audioRangeWidth + rowGap,
                        formTop + 165, audioModeWidth, 20).build());

        int halfWidth = (formWidth - 4) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.save"),
                button -> send(VideoManagerActionMessage.Action.SAVE))
                .bounds(formLeft, formTop + 190, halfWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.start"),
                button -> send(VideoManagerActionMessage.Action.START))
                .bounds(formLeft + halfWidth + 4, formTop + 190, halfWidth, 20).build());

        int thirdWidth = (formWidth - 8) / 3;
        pauseButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.pause"),
                button -> send(VideoManagerActionMessage.Action.PAUSE))
                .bounds(formLeft, formTop + 214, thirdWidth, 20).build());
        resumeButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.resume"),
                button -> send(VideoManagerActionMessage.Action.RESUME))
                .bounds(formLeft + thirdWidth + 4, formTop + 214, thirdWidth, 20).build());
        stopButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.manager.stop"),
                button -> send(VideoManagerActionMessage.Action.STOP))
                .bounds(formLeft + (thirdWidth + 4) * 2, formTop + 214, thirdWidth, 20).build());
        setInitialFocus(screenIdInput);
        updateStreamModeControls();
        updateScalingButton();
        updatePipeLanesButton();
        updatePixelFormatButton();
        updateAudioModeButton();
        updateButtonState();
    }

    @Override
    public void tick() {
        super.tick();
        if (active && !positionInput.isFocused()) {
            positionInput.setValue(formatTime(currentPositionMs()));
        }
        updateButtonState();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, formTop, 0xFFFFFF);
        graphics.drawString(font,
                Component.translatable("gui.video_synchronizer.manager.screen_id"),
                formLeft, formTop + 13, 0xA0A0A0);
        graphics.drawString(font,
                Component.translatable("gui.video_synchronizer.manager.video_url"),
                formLeft, formTop + 48, 0xA0A0A0);
        if (splitStreams) {
            graphics.drawString(font,
                    Component.translatable("gui.video_synchronizer.manager.audio_url"),
                    formLeft, formTop + 81, 0xA0A0A0);
        }
        String statusKey = waitingForClients
                ? "gui.video_synchronizer.manager.status_buffering"
                : (playing
                ? "gui.video_synchronizer.manager.status_playing"
                : "gui.video_synchronizer.manager.status_paused");
        Component status = active
                ? (live ? Component.translatable(
                        "gui.video_synchronizer.manager.status_live_"
                                + (waitingForClients ? "buffering" : (playing
                                ? "playing" : "paused")))
                        : Component.translatable(statusKey,
                        formatTime(currentPositionMs()), formatTime(durationMs)))
                : Component.translatable("gui.video_synchronizer.manager.status_idle");
        int statusColor = waitingForClients ? 0xD080FF : (active ? 0x80FF80 : 0xA0A0A0);
        graphics.drawString(font, status, formLeft, formTop + 142, statusColor);
        graphics.drawString(font,
                Component.translatable("gui.video_synchronizer.manager.position"),
                formLeft, formTop + 154, 0xA0A0A0);
        graphics.drawString(font,
                Component.translatable("gui.video_synchronizer.manager.audio_range"),
                audioRangeInput.getX(), formTop + 154, 0xA0A0A0);
        graphics.drawString(font,
                Component.translatable("gui.video_synchronizer.manager.audio_mode"),
                audioModeButton.getX(), formTop + 154, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void applyState(OpenVideoManagerMessage message) {
        screenId = message.screenId();
        videoUrl = message.videoUrl();
        audioUrl = message.audioUrl();
        requestHeaders = message.requestHeaders();
        cookie = message.cookie();
        disableScaling = message.disableScaling();
        videoPipeLanes = normalizeVideoPipeLanes(message.videoPipeLanes());
        videoPixelFormat = message.videoPixelFormat();
        audioRange = message.audioRange();
        audioPlaybackMode = message.audioPlaybackMode();
        splitStreams = !audioUrl.isBlank();
        active = message.active();
        live = message.live();
        playing = message.playing();
        waitingForClients = message.waitingForClients();
        positionMs = message.positionMs();
        durationMs = message.durationMs();
        stateReceivedNanos = System.nanoTime();
        if (screenIdInput != null) {
            screenIdInput.setValue(screenId);
            videoUrlInput.setValue(videoUrl);
            audioUrlInput.setValue(audioUrl);
            positionInput.setValue(formatTime(positionMs));
            audioRangeInput.setValue(formatAudioRange(audioRange));
            updateStreamModeControls();
            updateScalingButton();
            updatePipeLanesButton();
            updatePixelFormatButton();
            updateAudioModeButton();
            updateButtonState();
        }
    }

    private void send(VideoManagerActionMessage.Action action) {
        long requestedPosition = 0L;
        if (action == VideoManagerActionMessage.Action.SEEK) {
            try {
                requestedPosition = parseTime(positionInput.getValue());
            } catch (ArithmeticException | NumberFormatException exception) {
                return;
            }
        }
        double requestedAudioRange = audioRange;
        if (action == VideoManagerActionMessage.Action.SAVE
                || action == VideoManagerActionMessage.Action.START) {
            if (audioPlaybackMode == AudioPlaybackMode.GLOBAL) {
                audioRangeInput.setTextColor(0xE0E0E0);
            } else {
                try {
                    requestedAudioRange = parseAudioRange(audioRangeInput.getValue());
                    audioRangeInput.setTextColor(0xE0E0E0);
                } catch (NumberFormatException exception) {
                    audioRangeInput.setTextColor(0xFF5555);
                    return;
                }
            }
        }
        VideoNetwork.CHANNEL.sendToServer(new VideoManagerActionMessage(
                managerPos, action, screenIdInput.getValue(), videoUrlInput.getValue(),
                splitStreams ? audioUrlInput.getValue() : "", requestHeaders, cookie,
                disableScaling, videoPipeLanes, videoPixelFormat,
                requestedAudioRange, audioPlaybackMode,
                requestedPosition));
    }

    private void openHeadersEditor() {
        captureConfigurationInputs();
        minecraft.setScreen(new MediaRequestEditorScreen(this,
                Component.translatable("gui.video_synchronizer.headers.title"),
                requestHeaders, MediaRequestOptions.MAX_HEADERS_LENGTH,
                false, MediaRequestOptions::normalizeHeaders,
                value -> requestHeaders = value));
    }

    private void openCookieEditor() {
        captureConfigurationInputs();
        minecraft.setScreen(new MediaRequestEditorScreen(this,
                Component.translatable("gui.video_synchronizer.cookie.title"),
                cookie, MediaRequestOptions.MAX_COOKIE_LENGTH,
                true, MediaRequestOptions::normalizeCookie,
                value -> cookie = value));
    }

    private void captureConfigurationInputs() {
        screenId = screenIdInput.getValue();
        videoUrl = videoUrlInput.getValue();
        audioUrl = audioUrlInput.getValue();
        try {
            audioRange = parseAudioRange(audioRangeInput.getValue());
        } catch (NumberFormatException ignored) {
            // Keep the last valid value while an editor screen is open.
        }
    }

    private void updateStreamModeControls() {
        if (streamModeButton == null || audioUrlInput == null) {
            return;
        }
        String modeKey = splitStreams
                ? "gui.video_synchronizer.manager.mode_split"
                : "gui.video_synchronizer.manager.mode_combined";
        streamModeButton.setMessage(Component.translatable(splitStreams
                ? "gui.video_synchronizer.manager.mode_split_short"
                : "gui.video_synchronizer.manager.mode_combined_short"));
        streamModeButton.setTooltip(Tooltip.create(Component.translatable(modeKey)));
        audioUrlInput.visible = splitStreams;
        audioUrlInput.active = splitStreams;
    }

    private void updateScalingButton() {
        if (scalingButton == null) {
            return;
        }
        scalingButton.setMessage(Component.translatable(disableScaling
                ? "gui.video_synchronizer.manager.scaling_disabled"
                : "gui.video_synchronizer.manager.scaling_enabled"));
        scalingButton.setTooltip(Tooltip.create(Component.translatable(
                "gui.video_synchronizer.manager.scaling_tooltip")));
    }

    private void updatePipeLanesButton() {
        if (pipeLanesButton == null) {
            return;
        }
        Component value = videoPipeLanes == 0
                ? Component.translatable("gui.video_synchronizer.manager.pipe_lanes_auto")
                : Component.literal(Integer.toString(videoPipeLanes));
        pipeLanesButton.setMessage(Component.translatable(
                "gui.video_synchronizer.manager.pipe_lanes").append(value));
        pipeLanesButton.setTooltip(Tooltip.create(Component.translatable(
                "gui.video_synchronizer.manager.pipe_lanes_tooltip")));
    }

    private void updatePixelFormatButton() {
        if (pixelFormatButton == null) {
            return;
        }
        pixelFormatButton.setMessage(Component.literal(videoPixelFormat.name()));
        pixelFormatButton.setTooltip(Tooltip.create(Component.translatable(
                "gui.video_synchronizer.manager.pixel_format_tooltip")));
    }

    private void updateAudioModeButton() {
        if (audioModeButton == null || audioRangeInput == null) {
            return;
        }
        String suffix = switch (audioPlaybackMode) {
            case POSITIONAL -> "positional";
            case FIXED_RANGE -> "fixed_range";
            case GLOBAL -> "global";
        };
        audioModeButton.setMessage(Component.translatable(
                "gui.video_synchronizer.manager.audio_mode_" + suffix + "_short"));
        audioModeButton.setTooltip(Tooltip.create(Component.translatable(
                "gui.video_synchronizer.manager.audio_mode_" + suffix)));
        audioRangeInput.active = audioPlaybackMode != AudioPlaybackMode.GLOBAL;
    }

    private static int nextVideoPipeLanes(int lanes) {
        return switch (lanes) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            case 4 -> 8;
            case 8 -> 16;
            default -> 0;
        };
    }

    private static int normalizeVideoPipeLanes(int lanes) {
        return switch (lanes) {
            case 1, 2, 4, 8, 16 -> lanes;
            default -> 0;
        };
    }

    private void updateButtonState() {
        if (pauseButton == null) {
            return;
        }
        pauseButton.active = active && playing && !waitingForClients;
        resumeButton.active = active && !playing && !waitingForClients;
        seekButton.active = active && !live;
        positionInput.active = !live;
        stopButton.active = active;
    }

    private long currentPositionMs() {
        if (live) {
            return 0L;
        }
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

    private static long parseTime(String value) {
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            throw new NumberFormatException("Expected HH:MM:SS");
        }
        long hours = Long.parseLong(parts[0]);
        long minutes = Long.parseLong(parts[1]);
        long seconds = Long.parseLong(parts[2]);
        if (minutes > 59L || seconds > 59L) {
            throw new NumberFormatException("Minutes and seconds must be below 60");
        }
        long totalSeconds = Math.addExact(Math.multiplyExact(hours, 3_600L),
                Math.addExact(Math.multiplyExact(minutes, 60L), seconds));
        return Math.multiplyExact(totalSeconds, 1_000L);
    }

    private static double parseAudioRange(String value) {
        double range = Double.parseDouble(value);
        if (!Double.isFinite(range) || range < 1.0D || range > 1024.0D) {
            throw new NumberFormatException("Audio range must be between 1 and 1024");
        }
        return range;
    }

    private static String formatAudioRange(double value) {
        return value == Math.rint(value)
                ? Long.toString(Math.round(value)) : Double.toString(value);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
