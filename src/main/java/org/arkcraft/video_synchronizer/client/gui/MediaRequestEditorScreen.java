package org.arkcraft.video_synchronizer.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.arkcraft.video_synchronizer.LocalizedArgumentException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

final class MediaRequestEditorScreen extends Screen {
    private static final int ROW_HEIGHT = 26;

    private final Screen parent;
    private final String initialValue;
    private final int characterLimit;
    private final boolean cookieEditor;
    private final UnaryOperator<String> normalizer;
    private final Consumer<String> save;
    private final List<AbstractWidget> editorWidgets = new ArrayList<>();
    private final List<AbstractWidget> importWidgets = new ArrayList<>();

    private KeyValueList entries;
    private MultiLineEditBox importInput;
    private Button doneButton;
    private Component validationError;
    private int editorWidth;
    private boolean importMode;

    MediaRequestEditorScreen(Screen parent, Component title, String initialValue,
                             int characterLimit, boolean cookieEditor,
                             UnaryOperator<String> normalizer, Consumer<String> save) {
        super(title);
        this.parent = parent;
        this.initialValue = initialValue;
        this.characterLimit = characterLimit;
        this.cookieEditor = cookieEditor;
        this.normalizer = normalizer;
        this.save = save;
    }

    @Override
    protected void init() {
        editorWidgets.clear();
        importWidgets.clear();
        importMode = false;
        editorWidth = Math.min(440, width - 24);
        entries = new KeyValueList(minecraft, width, height, 43, height - 62, ROW_HEIGHT);
        addRenderableWidget(entries);
        List<KeyValuePair> initialPairs = parseInitialValue();
        if (initialPairs.isEmpty()) {
            entries.addRow("", "");
        }
        for (KeyValuePair pair : initialPairs) {
            entries.addRow(pair.key(), pair.value());
        }

        int buttonWidth = Math.min(104, (editorWidth - 12) / 4);
        int buttonsLeft = (width - buttonWidth * 4 - 12) / 2;
        addEditorWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.editor.add"),
                button -> {
                    entries.addRow("", "");
                    validateEntries();
                }).bounds(buttonsLeft, height - 28, buttonWidth, 20).build());
        addEditorWidget(Button.builder(
                Component.translatable("gui.video_synchronizer.editor.import"),
                button -> openImportMode())
                .bounds(buttonsLeft + buttonWidth + 4, height - 28,
                        buttonWidth, 20).build());
        doneButton = addEditorWidget(Button.builder(
                Component.translatable("gui.done"), button -> saveAndClose())
                .bounds(buttonsLeft + (buttonWidth + 4) * 2, height - 28,
                        buttonWidth, 20).build());
        addEditorWidget(Button.builder(
                Component.translatable("gui.cancel"), button -> onClose())
                .bounds(buttonsLeft + (buttonWidth + 4) * 3, height - 28,
                        buttonWidth, 20).build());

        int importLeft = (width - editorWidth + 12) / 2;
        importInput = addImportWidget(new MultiLineEditBox(font, importLeft, 35,
                editorWidth - 12, height - 97,
                title, title));
        importInput.setCharacterLimit(characterLimit);
        int importButtonWidth = Math.min(150, (editorWidth - 4) / 2);
        int importButtonsLeft = (width - importButtonWidth * 2 - 4) / 2;
        addImportWidget(Button.builder(Component.translatable("gui.done"),
                button -> confirmImport()).bounds(importButtonsLeft, height - 28,
                importButtonWidth, 20).build());
        addImportWidget(Button.builder(Component.translatable("gui.cancel"),
                button -> closeImportMode()).bounds(importButtonsLeft + importButtonWidth + 4,
                height - 28, importButtonWidth, 20).build());
        setImportWidgetsVisible(false);
        validateEntries();
    }

    @Override
    public void tick() {
        super.tick();
        if (importMode) {
            importInput.tick();
        } else {
            entries.tickRows();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        Component screenTitle = importMode
                ? Component.translatable("gui.video_synchronizer.editor.import_title", title)
                : title;
        graphics.drawCenteredString(font, screenTitle, width / 2, 10, 0xFFFFFF);
        if (!importMode) {
            graphics.drawString(font, Component.translatable(
                            "gui.video_synchronizer.editor.key"),
                    entries.getRowLeft(), 31, 0xA0A0A0);
            graphics.drawString(font, Component.translatable(
                            "gui.video_synchronizer.editor.value"),
                    entries.valueColumnX(), 31, 0xA0A0A0);
        }
        if (validationError != null) {
            graphics.drawCenteredString(font, validationError, width / 2,
                    height - 48, 0xFF6060);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (importMode) {
            closeImportMode();
            return;
        }
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private List<KeyValuePair> parseInitialValue() {
        return parseValue(initialValue);
    }

    private List<KeyValuePair> parseValue(String value) {
        List<KeyValuePair> pairs = new ArrayList<>();
        String normalized = normalizer.apply(value);
        if (normalized.isBlank()) {
            return pairs;
        }
        String separatorPattern = cookieEditor ? ";" : "\n";
        for (String item : normalized.split(separatorPattern)) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf(cookieEditor ? '=' : ':');
            if (separator < 0) {
                pairs.add(new KeyValuePair(trimmed, ""));
            } else {
                pairs.add(new KeyValuePair(trimmed.substring(0, separator).trim(),
                        trimmed.substring(separator + 1).trim()));
            }
        }
        return pairs;
    }

    private <T extends AbstractWidget> T addEditorWidget(T widget) {
        editorWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private <T extends AbstractWidget> T addImportWidget(T widget) {
        importWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private void openImportMode() {
        importMode = true;
        validationError = null;
        importInput.setValue("");
        removeWidget(entries);
        setEditorWidgetsVisible(false);
        setImportWidgetsVisible(true);
        setFocused(importInput);
    }

    private void closeImportMode() {
        importMode = false;
        setFocused(null);
        setImportWidgetsVisible(false);
        setEditorWidgetsVisible(true);
        addRenderableWidget(entries);
        renderables.remove(entries);
        renderables.add(0, entries);
        validateEntries();
    }

    private void setEditorWidgetsVisible(boolean visible) {
        setWidgetsVisible(editorWidgets, visible);
    }

    private void setImportWidgetsVisible(boolean visible) {
        setWidgetsVisible(importWidgets, visible);
    }

    private static void setWidgetsVisible(List<AbstractWidget> widgets, boolean visible) {
        for (AbstractWidget widget : widgets) {
            widget.visible = visible;
            widget.active = visible;
        }
    }

    private void confirmImport() {
        try {
            String value = importInput.getValue();
            if (value.isBlank()) {
                validationError = Component.translatable(
                        "gui.video_synchronizer.editor.import_empty");
                return;
            }
            String importValue = cookieEditor ? normalizeCookieImport(value) : value;
            List<KeyValuePair> importedPairs = parseValue(importValue);
            if (importedPairs.isEmpty()) {
                validationError = Component.translatable(
                        "gui.video_synchronizer.editor.import_empty");
                return;
            }
            entries.addImportedRows(importedPairs);
            closeImportMode();
        } catch (IllegalArgumentException exception) {
            validationError = LocalizedArgumentException.component(exception);
        }
    }

    private static String normalizeCookieImport(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder cookie = new StringBuilder(normalized.length());
        for (String line : normalized.split("\n", -1)) {
            String item = line.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (!cookie.isEmpty()) {
                cookie.append("; ");
            }
            cookie.append(item);
        }
        return cookie.toString();
    }

    private void validateEntries() {
        try {
            serializeEntries();
            validationError = null;
        } catch (IllegalArgumentException exception) {
            validationError = LocalizedArgumentException.component(exception);
        }
        if (doneButton != null) {
            doneButton.active = validationError == null;
        }
    }

    private String serializeEntries() {
        StringBuilder serialized = new StringBuilder();
        for (KeyValueRow row : entries.children()) {
            String key = row.key().trim();
            String value = row.value().trim();
            if (key.isEmpty() && value.isEmpty()) {
                continue;
            }
            if (key.isEmpty()) {
                throw new LocalizedArgumentException(
                        "gui.video_synchronizer.editor.key_empty");
            }
            if (!serialized.isEmpty()) {
                serialized.append(cookieEditor ? "; " : "\n");
            }
            serialized.append(key);
            if (cookieEditor) {
                serialized.append('=').append(value);
            } else {
                serialized.append(": ").append(value);
            }
        }
        return normalizer.apply(serialized.toString());
    }

    private void saveAndClose() {
        try {
            save.accept(serializeEntries());
            minecraft.setScreen(parent);
        } catch (IllegalArgumentException exception) {
            validationError = LocalizedArgumentException.component(exception);
            doneButton.active = false;
        }
    }

    private record KeyValuePair(String key, String value) {
    }

    private final class KeyValueList
            extends ContainerObjectSelectionList<KeyValueRow> {
        private final int keyWidth;
        private final int valueWidth;

        private KeyValueList(Minecraft minecraft, int width, int height,
                             int top, int bottom, int rowHeight) {
            super(minecraft, width, height, top, bottom, rowHeight);
            centerListVertically = false;
            int contentWidth = getRowWidth() - 28;
            keyWidth = Math.min(130, Math.max(80, contentWidth / 3));
            valueWidth = contentWidth - keyWidth - 4;
        }

        private void addRow(String key, String value) {
            KeyValueRow row = new KeyValueRow(this, key, value, keyWidth, valueWidth);
            addEntry(row);
            ensureVisible(row);
        }

        private void addImportedRows(List<KeyValuePair> pairs) {
            if (children().size() == 1) {
                KeyValueRow row = children().get(0);
                if (row.key().isBlank() && row.value().isBlank()) {
                    clearEntries();
                }
            }
            for (KeyValuePair pair : pairs) {
                addRow(pair.key(), pair.value());
            }
        }

        private void removeRow(KeyValueRow row) {
            removeEntry(row);
            validateEntries();
        }

        private void tickRows() {
            for (KeyValueRow row : children()) {
                row.tick();
            }
        }

        private int valueColumnX() {
            return getRowLeft() + keyWidth + 4;
        }

        @Override
        public int getRowWidth() {
            return editorWidth - 12;
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 2;
        }
    }

    private final class KeyValueRow
            extends ContainerObjectSelectionList.Entry<KeyValueRow> {
        private final KeyValueList owner;
        private final EditBox keyInput;
        private final EditBox valueInput;
        private final Button removeButton;
        private final List<AbstractWidget> widgets;

        private KeyValueRow(KeyValueList owner, String key, String value,
                            int keyWidth, int valueWidth) {
            this.owner = owner;
            keyInput = new EditBox(font, 0, 0, keyWidth, 20,
                    Component.translatable("gui.video_synchronizer.editor.key"));
            keyInput.setMaxLength(256);
            keyInput.setValue(key);
            keyInput.setResponder(ignored -> validateEntries());
            valueInput = new EditBox(font, 0, 0, valueWidth, 20,
                    Component.translatable("gui.video_synchronizer.editor.value"));
            valueInput.setMaxLength(characterLimit);
            valueInput.setValue(value);
            valueInput.setResponder(ignored -> validateEntries());
            removeButton = Button.builder(Component.literal("X"),
                    button -> owner.removeRow(this)).bounds(0, 0, 20, 20).build();
            widgets = List.of(keyInput, valueInput, removeButton);
        }

        private String key() {
            return keyInput.getValue();
        }

        private String value() {
            return valueInput.getValue();
        }

        private void tick() {
            keyInput.tick();
            valueInput.tick();
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return widgets;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left,
                           int rowWidth, int rowHeight, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            keyInput.setX(left);
            keyInput.setY(top + 2);
            valueInput.setX(left + keyInput.getWidth() + 4);
            valueInput.setY(top + 2);
            removeButton.setX(left + rowWidth - 20);
            removeButton.setY(top + 2);
            keyInput.render(graphics, mouseX, mouseY, partialTick);
            valueInput.render(graphics, mouseX, mouseY, partialTick);
            removeButton.render(graphics, mouseX, mouseY, partialTick);
        }
    }
}
