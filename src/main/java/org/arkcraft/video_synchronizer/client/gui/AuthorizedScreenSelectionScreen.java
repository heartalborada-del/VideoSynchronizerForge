package org.arkcraft.video_synchronizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.network.OpenVideoManagerMessage;

import java.util.List;

public final class AuthorizedScreenSelectionScreen extends Screen {
    private static final int ROW_HEIGHT = 24;

    private final VideoManagerScreen parent;
    private final List<OpenVideoManagerMessage.ScreenOption> screens;
    private int page;
    private int rowsPerPage;

    public AuthorizedScreenSelectionScreen(VideoManagerScreen parent,
                                           List<OpenVideoManagerMessage.ScreenOption> screens) {
        super(Component.translatable("gui.video_synchronizer.screen_select.title"));
        this.parent = parent;
        this.screens = List.copyOf(screens);
    }

    @Override
    protected void init() {
        int listWidth = Math.min(300, width - 20);
        int left = (width - listWidth) / 2;
        int top = 34;
        rowsPerPage = Math.max(1, Math.min(8, (height - 92) / ROW_HEIGHT));
        int first = page * rowsPerPage;
        int last = Math.min(screens.size(), first + rowsPerPage);
        for (int index = first; index < last; index++) {
            OpenVideoManagerMessage.ScreenOption option = screens.get(index);
            Component distance = option.otherDimension()
                    ? Component.translatable("gui.video_synchronizer.screen_select.other_dimension")
                    : Component.translatable("gui.video_synchronizer.screen_select.distance",
                    option.distance());
            addRenderableWidget(Button.builder(Component.literal(option.screenId() + "  ")
                            .append(distance), button -> select(option.screenId()))
                    .bounds(left, top + (index - first) * ROW_HEIGHT, listWidth, 20).build());
        }

        int navigationY = Math.min(height - 28, top + rowsPerPage * ROW_HEIGHT + 4);
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            page--;
            rebuildWidgets();
        }).bounds(left, navigationY, 40, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            page++;
            rebuildWidgets();
        }).bounds(left + listWidth - 40, navigationY, 40, 20).build());
        next.active = (page + 1) * rowsPerPage < screens.size();
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(left + 44, navigationY, listWidth - 88, 20).build());
    }

    private void select(String screenId) {
        OpenVideoManagerMessage.ScreenOption selected = screens.stream()
                .filter(option -> option.screenId().equals(screenId))
                .findFirst().orElseThrow();
        parent.selectScreen(selected);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        if (screens.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.video_synchronizer.screen_select.empty"),
                    width / 2, height / 2 - 5, 0xA0A0A0);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
