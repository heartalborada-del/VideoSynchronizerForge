package org.arkcraft.video_synchronizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.network.OpenScreenPermissionsMessage;
import org.arkcraft.video_synchronizer.network.ScreenAccessRole;
import org.arkcraft.video_synchronizer.network.ScreenPermissionActionMessage;
import org.arkcraft.video_synchronizer.network.VideoNetwork;
import org.arkcraft.video_synchronizer.server.ScreenAccessMode;

import java.util.List;
import java.util.UUID;

public final class ScreenPermissionsScreen extends Screen {
    private static final UUID EMPTY_ID = new UUID(0L, 0L);
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private OpenScreenPermissionsMessage state;
    private ScreenAccessRole selectedRole = ScreenAccessRole.CONTROL;
    private EditBox playerNameInput;
    private int page;
    private int rowsPerPage;

    private ScreenPermissionsScreen(Screen parent, OpenScreenPermissionsMessage state) {
        super(Component.translatable("gui.video_synchronizer.permissions.title"));
        this.parent = parent;
        this.state = state;
    }

    public static void openOrUpdate(OpenScreenPermissionsMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ScreenPermissionsScreen screen
                && screen.state.managerPos().equals(message.managerPos())) {
            screen.state = message;
            screen.page = Math.min(screen.page, screen.maxPage());
            screen.rebuildWidgets();
        } else {
            minecraft.setScreen(new ScreenPermissionsScreen(minecraft.screen, message));
        }
    }

    @Override
    protected void init() {
        int formWidth = Math.min(320, width - 20);
        int left = (width - formWidth) / 2;
        int top = 12;

        addRenderableWidget(Button.builder(accessModeName(state.accessMode()), button -> {
            ScreenAccessMode[] modes = ScreenAccessMode.values();
            ScreenAccessMode next = modes[(state.accessMode().ordinal() + 1) % modes.length];
            send(ScreenPermissionActionMessage.Action.SET_MODE, "", EMPTY_ID,
                    ScreenAccessRole.CONTROL, next);
        }).bounds(left, top + 42, formWidth, 20).build());

        playerNameInput = new EditBox(font, left, top + 66, formWidth - 144, 20,
                Component.translatable("gui.video_synchronizer.permissions.player"));
        playerNameInput.setMaxLength(64);
        addRenderableWidget(playerNameInput);

        Button roleButton = addRenderableWidget(Button.builder(roleName(selectedRole), button -> {
            selectedRole = selectedRole == ScreenAccessRole.CONTROL
                    ? ScreenAccessRole.EDIT : ScreenAccessRole.CONTROL;
            button.setMessage(roleName(selectedRole));
        }).bounds(left + formWidth - 140, top + 66, 76, 20).build());
        roleButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.video_synchronizer.permissions.role_tooltip")));
        addRenderableWidget(Button.builder(Component.translatable(
                        "gui.video_synchronizer.permissions.add"), button -> addPlayer())
                .bounds(left + formWidth - 60, top + 66, 60, 20).build());

        rowsPerPage = Math.max(1, Math.min(6, (height - 150) / ROW_HEIGHT));
        int first = page * rowsPerPage;
        List<OpenScreenPermissionsMessage.Entry> entries = state.entries();
        int last = Math.min(entries.size(), first + rowsPerPage);
        for (int index = first; index < last; index++) {
            OpenScreenPermissionsMessage.Entry entry = entries.get(index);
            int rowY = top + 94 + (index - first) * ROW_HEIGHT;
            Button name = addRenderableWidget(Button.builder(
                            Component.literal(entry.playerName()), button -> {
                            })
                    .bounds(left, rowY, formWidth - 112, 20).build());
            name.active = false;
            addRenderableWidget(Button.builder(roleName(entry.role()), button ->
                            setRole(entry, entry.role() == ScreenAccessRole.CONTROL
                                    ? ScreenAccessRole.EDIT : ScreenAccessRole.CONTROL))
                    .bounds(left + formWidth - 108, rowY, 80, 20).build());
            addRenderableWidget(Button.builder(Component.literal("X"), button -> remove(entry))
                    .bounds(left + formWidth - 24, rowY, 24, 20).build());
        }

        int navigationY = Math.min(height - 28, top + 98 + rowsPerPage * ROW_HEIGHT);
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            page--;
            rebuildWidgets();
        }).bounds(left, navigationY, 40, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            page++;
            rebuildWidgets();
        }).bounds(left + formWidth - 40, navigationY, 40, 20).build());
        next.active = page < maxPage();
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(left + 44, navigationY, formWidth - 88, 20).build());
    }

    private void addPlayer() {
        String playerName = playerNameInput.getValue().trim();
        if (!playerName.isBlank()) {
            send(ScreenPermissionActionMessage.Action.SET, playerName, EMPTY_ID, selectedRole,
                    state.accessMode());
        }
    }

    private void setRole(OpenScreenPermissionsMessage.Entry entry, ScreenAccessRole role) {
        send(ScreenPermissionActionMessage.Action.SET, entry.playerName(), entry.playerId(), role,
                state.accessMode());
    }

    private void remove(OpenScreenPermissionsMessage.Entry entry) {
        send(ScreenPermissionActionMessage.Action.REMOVE, "", entry.playerId(), entry.role(),
                state.accessMode());
    }

    private void send(ScreenPermissionActionMessage.Action action, String playerName,
                      UUID playerId, ScreenAccessRole role, ScreenAccessMode accessMode) {
        VideoNetwork.CHANNEL.sendToServer(new ScreenPermissionActionMessage(
                state.managerPos(), action, playerName, playerId, role, accessMode));
    }

    private int maxPage() {
        int rowCount = rowsPerPage == 0 ? 1 : rowsPerPage;
        return Math.max(0, (state.entries().size() - 1) / rowCount);
    }

    private static Component roleName(ScreenAccessRole role) {
        return Component.translatable(role == ScreenAccessRole.EDIT
                ? "gui.video_synchronizer.permissions.edit"
                : "gui.video_synchronizer.permissions.control");
    }

    private static Component accessModeName(ScreenAccessMode mode) {
        return Component.translatable("gui.video_synchronizer.permissions.access_mode",
                Component.translatable("gui.video_synchronizer.permissions.access_mode."
                        + mode.name().toLowerCase(java.util.Locale.ROOT)));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - Math.min(320, width - 20)) / 2;
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawString(font, Component.translatable(
                        "gui.video_synchronizer.permissions.summary",
                        state.screenId(), state.ownerName()),
                left, 31, 0xA0A0A0);
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
