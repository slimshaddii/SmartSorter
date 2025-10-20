package net.shaddii.smartsorter.widget;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.MouseInput;
//?}
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.network.ChestConfigUpdatePayload;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ChestSortMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ChestSelectorWidget {
    private final int x, y, width, height;
    private final TextRenderer textRenderer;
    List<ChestConfig> chests = new ArrayList<>(); // <-- CHANGED: Removed private final

    private int selectedIndex = -1;
    private Consumer<ChestConfig> onSelectionChange;
    private Consumer<ChestConfig> onConfigUpdate;

    private DropdownWidget dropdown;
    private ChestSortMode currentSortMode = ChestSortMode.PRIORITY;
    private Map<BlockPos, ChestConfig> chestConfigs = new HashMap<>();

    private ButtonWidget editButton;
    private ButtonWidget sortButton;
    private TextFieldWidget renameField;
    private boolean isRenaming = false;
    private final StorageControllerScreen parentScreen;

    public ChestSelectorWidget(int x, int y, int width, int height, TextRenderer textRenderer, StorageControllerScreen parentScreen) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.textRenderer = textRenderer;
        this.parentScreen = parentScreen;

        int dropdownWidth = width - 25;  // Keep full width minus space for buttons
        this.dropdown = new DropdownWidget(x, y, dropdownWidth, height, Text.literal("Select Chest"));

        // Sort button
        this.sortButton = ButtonWidget.builder(
                Text.literal("📤"),
                btn -> triggerSort()
        ).dimensions(x + dropdownWidth + 2, y - 14, 20, 10).build();

        // Edit/Rename button - below sort button
        this.editButton = ButtonWidget.builder(
                Text.literal("✏"),
                btn -> startRenaming()
        ).dimensions(x + dropdownWidth + 2, y, 20, height).build();


        this.renameField = new TextFieldWidget(textRenderer, x, y, dropdownWidth, height, Text.literal(""));
        renameField.setMaxLength(32);
        renameField.setVisible(false);
        renameField.setDrawsBackground(true);
    }

    public void reselectChestByPos(BlockPos posToSelect) {
        if (posToSelect == null) {
            if (!this.chests.isEmpty()) {
                this.setSelectedIndex(0);
            }
            return;
        }

        for (int i = 0; i < this.chests.size(); i++) {
            if (this.chests.get(i).position.equals(posToSelect)) {
                this.setSelectedIndex(i);
                if (this.onSelectionChange != null) {
                    this.onSelectionChange.accept(this.chests.get(i));
                }
                return;
            }
        }

        if (!this.chests.isEmpty()) {
            this.setSelectedIndex(0);
        }
    }


    private String getPriorityColor(int priority, int totalChests) {
        if (totalChests == 0) return "§7";

        int topThird = Math.max(1, totalChests / 3);
        int bottomThird = Math.max(1, (totalChests * 2) / 3);

        if (priority <= topThird) {
            return "§a"; // High priority (green)
        } else if (priority <= bottomThird) {
            return "§e"; // Medium priority (yellow)
        } else {
            return "§c"; // Low priority (red)
        }
    }

    private String buildChestDisplayText(ChestConfig config, int totalChests) {
        String displayText = config.customName != null && !config.customName.isEmpty()
                ? config.customName
                : "Chest";

        if (config.filterMode == ChestConfig.FilterMode.CATEGORY ||
                config.filterMode == ChestConfig.FilterMode.CATEGORY_AND_PRIORITY ||
                config.filterMode == ChestConfig.FilterMode.OVERFLOW ||
                config.filterMode == ChestConfig.FilterMode.BLACKLIST) {
            displayText += " §7[" + config.filterCategory.getShortName() + "]";
        }

        displayText += " " + getPriorityColor(config.priority, totalChests) + config.priority;

        // Fullness
        int fullness = config.cachedFullness;
        if (fullness >= 0) {
            String fullnessColor;
            if (fullness >= 90) {
                fullnessColor = "§c";
            } else if (fullness >= 70) {
                fullnessColor = "§e";
            } else {
                fullnessColor = "§a";
            }
            displayText += " " + fullnessColor + fullness + "%";
        } else {
            displayText += " §8--%";
        }

        return displayText;
    }

    private void triggerSort() {
        ChestConfig selected = getSelectedChest();
        if (selected == null) return;

        if (selected.filterMode == ChestConfig.FilterMode.CUSTOM) {
            return;
        }

        ((StorageControllerScreen) this.parentScreen).handleSortThisChest(selected.position);


        if (onConfigUpdate != null) {
            onConfigUpdate.accept(selected);
        }
    }


    public void setSortMode(ChestSortMode mode) {
        this.currentSortMode = mode;
        updateChests(chestConfigs);
    }

    public ChestSortMode getSortMode() {
        return currentSortMode;
    }

    public void updateChests(Map<BlockPos, ChestConfig> configs) {
        this.chestConfigs = configs;
        chests.clear();
        dropdown.clearEntries();

        List<ChestConfig> sortedChests = new ArrayList<>(configs.values());

        // TWO-TIER SORTING: Filtered chests first, then sort by mode
        sortedChests.sort((a, b) -> {
            // FIRST: Separate filtered chests from ALL/NONE chests
            boolean aIsFiltered = a.filterMode != ChestConfig.FilterMode.NONE &&
                    a.filterMode != ChestConfig.FilterMode.CUSTOM;
            boolean bIsFiltered = b.filterMode != ChestConfig.FilterMode.NONE &&
                    b.filterMode != ChestConfig.FilterMode.CUSTOM;

            // If one is filtered and the other isn't, filtered comes first
            if (aIsFiltered != bIsFiltered) {
                return aIsFiltered ? -1 : 1;
            }

            // SECOND: Within same filter status, sort by selected mode
            String keyA = a.getSortKey(currentSortMode);
            String keyB = b.getSortKey(currentSortMode);
            return keyA.compareTo(keyB);
        });

        int totalChests = sortedChests.size();

        for (ChestConfig config : sortedChests) {
            chests.add(config);
            String displayText = buildChestDisplayText(config, totalChests);
            dropdown.addEntry(displayText, displayText);
        }

        if (!chests.isEmpty() && selectedIndex < 0) {
            selectedIndex = 0;
            dropdown.setSelectedIndex(0);
            notifySelectionChange();
        }
    }

    public void setOnSelectionChange(Consumer<ChestConfig> callback) {
        this.onSelectionChange = callback;
        dropdown.setOnSelect(index -> {
            selectedIndex = index;
            notifySelectionChange();
        });
    }

    public void setOnConfigUpdate(Consumer<ChestConfig> callback) {
        this.onConfigUpdate = callback;
    }

    private void startRenaming() {
        if (chests.isEmpty() || isRenaming) return;

        isRenaming = true;
        ChestConfig config = chests.get(selectedIndex);

        renameField.setText(config.customName != null && !config.customName.isEmpty() ? config.customName : "");
        renameField.setVisible(true);
        renameField.setFocused(true);
        dropdown.visible = false;
    }

    private void finishRenaming() {
        if (!isRenaming) return;

        isRenaming = false;
        String newName = renameField.getText().trim();
        ChestConfig config = chests.get(selectedIndex);

        config.customName = newName.isEmpty() ? "" : newName;
        ClientPlayNetworking.send(new ChestConfigUpdatePayload(config));

        if (onConfigUpdate != null) {
            onConfigUpdate.accept(config);
        }

        renameField.setVisible(false);
        renameField.setFocused(false);
        dropdown.visible = true;
    }

    private void notifySelectionChange() {
        if (onSelectionChange != null) {
            ChestConfig selected = getSelectedChest();
            onSelectionChange.accept(selected);
        }
    }

    public ChestConfig getSelectedChest() {
        if (selectedIndex >= 0 && selectedIndex < chests.size()) {
            return chests.get(selectedIndex);
        }
        return null;
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < chests.size()) {
            selectedIndex = index;
            dropdown.setSelectedIndex(index);
            notifySelectionChange();
        }
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Only show sort button if selected chest is NOT custom mode
        ChestConfig selected = getSelectedChest();
        if (selected != null && selected.filterMode != ChestConfig.FilterMode.CUSTOM) {
            sortButton.render(context, mouseX, mouseY, delta);
        }

        if (isRenaming) {
            renameField.render(context, mouseX, mouseY, delta);
        } else {
            dropdown.render(context, mouseX, mouseY, delta);
        }
        editButton.render(context, mouseX, mouseY, delta);
    }

    public void renderDropdownIfOpen(DrawContext context, int mouseX, int mouseY) {
        if (dropdown.isOpen()) {
            dropdown.renderDropdown(context, mouseX, mouseY);

            if (dropdown.isShiftDown()) {
                int hoveredIndex = dropdown.getHoveredEntryIndex();
                if (hoveredIndex >= 0 && hoveredIndex < chests.size()) {
                    ChestConfig hoveredChest = chests.get(hoveredIndex);
                    if (hoveredChest != null && !hoveredChest.previewItems.isEmpty()) {
                        dropdown.renderItemPreviewTooltip(context, mouseX, mouseY,
                                hoveredIndex - dropdown.getScrollOffset(), // Visible index
                                hoveredChest.previewItems);
                    }
                }
            }
        }
    }

    //? if >=1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MouseInput mouseInput = new MouseInput(button, 0);
        Click click = new Click(mouseX, mouseY, mouseInput);

        // Only handle sort button if chest is not CUSTOM mode
        ChestConfig selected = getSelectedChest();
        if (selected != null && selected.filterMode != ChestConfig.FilterMode.CUSTOM) {
            if (sortButton.mouseClicked(click, false)) {
                return true;
            }
        }

        if (isRenaming) {
            if (renameField.mouseClicked(click, false)) {
                return true;
            }
            finishRenaming();
            return true;
        }

        if (editButton.mouseClicked(click, false)) {
            return true;
        }

        if (dropdown.mouseClicked(mouseX, mouseY, button)) {
            selectedIndex = dropdown.getSelectedIndex();
            notifySelectionChange();
            return true;
        }

        return false;
    }
    //?} else {
/*public boolean mouseClicked(double mouseX, double mouseY, int button) {
    ChestConfig selected = getSelectedChest();
    if (selected != null && selected.filterMode != ChestConfig.FilterMode.CUSTOM) {
        int sbX = sortButton.getX();
        int sbY = sortButton.getY();
        int sbW = sortButton.getWidth();
        int sbH = sortButton.getHeight();

        if (mouseX >= sbX && mouseX < sbX + sbW && mouseY >= sbY && mouseY < sbH) {
            sortButton.onPress();
            return true;
        }
    }

    if (isRenaming) {
        int fx = renameField.getX();
        int fy = renameField.getY();
        int fw = renameField.getWidth();
        int fh = renameField.getHeight();

        if (mouseX >= fx && mouseX < fx + fw && mouseY >= fy && mouseY < fy + fh) {
            renameField.setFocused(true);
            renameField.onClick(mouseX, mouseY);
            return true;
        }
        finishRenaming();
        return true;
    }

    int bx = editButton.getX();
    int by = editButton.getY();
    int bw = editButton.getWidth();
    int bh = editButton.getHeight();

    if (mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh) {
        editButton.onPress();
        return true;
    }

    if (dropdown.mouseClicked(mouseX, mouseY, button)) {
        selectedIndex = dropdown.getSelectedIndex();
        notifySelectionChange();
        return true;
    }

    return false;
}
*///?}

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return dropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    //? if >=1.21.9 {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isRenaming) {
            KeyInput input = new KeyInput(keyCode, scanCode, modifiers);

            if (keyCode == 257 || keyCode == 335) { // Enter
                finishRenaming();
                return true;
            }
            if (keyCode == 256) { // Escape
                renameField.setText("");
                finishRenaming();
                return true;
            }
            return renameField.keyPressed(input);
        }

        KeyInput input = new KeyInput(keyCode, scanCode, modifiers);
        return dropdown.keyPressed(input);
    }

    public boolean charTyped(char chr, int modifiers) {
        if (isRenaming) {
            CharInput input = new CharInput(chr, modifiers);
            return renameField.charTyped(input);
        }

        CharInput input = new CharInput(chr, modifiers);
        return dropdown.charTyped(input);
    }
    //?} else {
    /*public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isRenaming) {
            if (keyCode == 257 || keyCode == 335) { // Enter
                finishRenaming();
                return true;
            }
            if (keyCode == 256) { // Escape
                renameField.setText("");
                finishRenaming();
                return true;
            }
            return renameField.keyPressed(keyCode, scanCode, modifiers);
        }

        return dropdown.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char chr, int modifiers) {
        if (isRenaming) {
            return renameField.charTyped(chr, modifiers);
        }

        return dropdown.charTyped(chr, modifiers);
    }
    *///?}

    public boolean isDropdownOpen() {
        return dropdown.isOpen();
    }

    public boolean isCurrentlyRenaming() {
        return isRenaming;
    }
}