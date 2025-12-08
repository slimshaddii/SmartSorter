package net.shaddii.smartsorter.widget;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
//? if >=1.21.9 {
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
//?}
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.network.ProbeConfigUpdatePayload;
import net.shaddii.smartsorter.util.ProcessProbeConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ProbeSelectorWidget {
    private final int x, y, width, height;
    private final TextRenderer textRenderer;
    private final List<ProcessProbeConfig> probes = new ArrayList<>();

    private DropdownWidget dropdown;
    private ButtonWidget editButton;
    private TextFieldWidget renameField;

    private int selectedIndex = 0;
    private boolean isRenaming = false;
    private Consumer<ProcessProbeConfig> onConfigUpdate;

    public ProbeSelectorWidget(int x, int y, int width, int height, TextRenderer textRenderer) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.textRenderer = textRenderer;

        initWidgets();
    }

    public boolean isDropdownOpen() {
        return dropdown != null && dropdown.isOpen();
    }

    public void renderDropdownIfOpen(DrawContext context, int mouseX, int mouseY) {
        if (dropdown != null && dropdown.isOpen()) {
            dropdown.renderDropdown(context, mouseX, mouseY);
        }
    }

    private void initWidgets() {
        int dropdownWidth = width - 25;
        dropdown = new DropdownWidget(x, y, dropdownWidth, height, Text.literal(""));

        editButton = ButtonWidget.builder(
                Text.literal("✏"),
                btn -> startRenaming()
        ).dimensions(x + dropdownWidth + 2, y, 20, height).build();

        renameField = new TextFieldWidget(textRenderer, x, y, dropdownWidth, height, Text.literal(""));
        renameField.setMaxLength(32);
        renameField.setVisible(false);
        renameField.setDrawsBackground(true);
    }

    public void updateProbes(Map<BlockPos, ProcessProbeConfig> probeConfigs) {
        probes.clear();
        probes.addAll(probeConfigs.values());

        probes.sort((a, b) -> {
            if (a.position.getX() != b.position.getX())
                return Integer.compare(a.position.getX(), b.position.getX());
            if (a.position.getY() != b.position.getY())
                return Integer.compare(a.position.getY(), b.position.getY());
            return Integer.compare(a.position.getZ(), b.position.getZ());
        });

        dropdown.clearEntries();

        if (probes.isEmpty()) {
            dropdown.addEntry("none", "No Process Probes");
            dropdown.setSelectedIndex(0);
            selectedIndex = 0;
        } else {
            for (int i = 0; i < probes.size(); i++) {
                ProcessProbeConfig config = probes.get(i);
                String displayName = config.getDisplayName();

                if (!config.isRecipeFilterValid()) {
                    displayName = "§c⚠ §r" + displayName + " §7(Config Error)";
                } else if (!config.enabled) {
                    displayName = "§c✗ §r" + displayName;
                } else {
                    displayName = "§a✓ §r" + displayName;
                }

                dropdown.addEntry(displayName, displayName);
            }

            if (selectedIndex >= probes.size()) {
                selectedIndex = 0;
            }
            dropdown.setSelectedIndex(selectedIndex);
        }
    }

    public void updateProbeStats(BlockPos position, int itemsProcessed) {
        for (ProcessProbeConfig probe : probes) {
            if (probe.position.equals(position)) {
                probe.itemsProcessed = itemsProcessed;
                break;
            }
        }
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        if (dropdown != null) {
            dropdown.setSelectedIndex(index);
        }
    }

    private void startRenaming() {
        if (probes.isEmpty() || isRenaming) return;

        isRenaming = true;
        ProcessProbeConfig config = probes.get(selectedIndex);

        renameField.setText(config.customName != null ? config.customName : "");
        renameField.setVisible(true);
        renameField.setFocused(true);
        dropdown.visible = false;
    }

    public void finishRenaming() {
        if (!isRenaming) return;

        isRenaming = false;
        String newName = renameField.getText().trim();

        ProcessProbeConfig config = probes.get(selectedIndex);

        if (!newName.isEmpty() || config.customName != null) {
            config.customName = newName.isEmpty() ? null : newName;

            ClientPlayNetworking.send(new ProbeConfigUpdatePayload(
                    config.position,
                    config.customName,
                    config.enabled,
                    config.recipeFilter,
                    config.fuelFilter
            ));

            if (onConfigUpdate != null) {
                onConfigUpdate.accept(config);
            }

            updateProbesFromList();
        }

        renameField.setVisible(false);
        renameField.setFocused(false);
        dropdown.visible = true;
    }

    private void updateProbesFromList() {
        dropdown.clearEntries();
        for (int i = 0; i < probes.size(); i++) {
            ProcessProbeConfig config = probes.get(i);
            String displayName = config.getDisplayName();

            if (!config.enabled) {
                displayName = "§c✗ §r" + displayName;
            } else {
                displayName = "§a✓ §r" + displayName;
            }

            dropdown.addEntry(displayName, displayName);
        }
        dropdown.setSelectedIndex(selectedIndex);
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (isRenaming) {
            renameField.render(context, mouseX, mouseY, delta);
        } else {
            dropdown.render(context, mouseX, mouseY, delta);
        }
        editButton.render(context, mouseX, mouseY, delta);
    }

    //? if >=1.21.9 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MouseInput mouseInput = new MouseInput(button, 0);
        Click click = new Click(mouseX, mouseY, mouseInput);

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
            return true;
        }

        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isRenaming) {
            KeyInput input = new KeyInput(keyCode, scanCode, modifiers);

            if (keyCode == 257 || keyCode == 335) { // Enter or Numpad Enter
                finishRenaming();
                return true;
            }
            if (keyCode == 256) { // Escape
                renameField.setText("");
                finishRenaming();
                return true;
            }
            renameField.keyPressed(input);
            return true;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (isRenaming) {
            CharInput input = new CharInput(chr, modifiers);
            return renameField.charTyped(input);
        }
        return false;
    }
    //?} else {
    /*public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (isRenaming) {
            // In 1.21.8, check bounds manually instead of calling mouseClicked
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

        // Check button bounds manually
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
            return true;
        }

        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isRenaming) {
            if (keyCode == 257 || keyCode == 335) { // Enter or Numpad Enter
                finishRenaming();
                return true;
            }
            if (keyCode == 256) { // Escape
                renameField.setText("");
                finishRenaming();
                return true;
            }
            renameField.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (isRenaming) {
            return renameField.charTyped(chr, modifiers);
        }
        return false;
    }
    *///?}

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (dropdown != null && dropdown.isOpen()) {
            return dropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        return false;
    }

    public ProcessProbeConfig getSelectedProbe() {
        if (probes.isEmpty() || selectedIndex >= probes.size()) {
            return null;
        }
        return probes.get(selectedIndex);
    }

    public void setOnConfigUpdate(Consumer<ProcessProbeConfig> callback) {
        this.onConfigUpdate = callback;
    }

    public void setOnSelectionChange(Consumer<ProcessProbeConfig> callback) {
        dropdown.setOnSelect(index -> {
            selectedIndex = index;
            if (callback != null && !probes.isEmpty() && index < probes.size()) {
                callback.accept(probes.get(index));
            }
        });
    }
}