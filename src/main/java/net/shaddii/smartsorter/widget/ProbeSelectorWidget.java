package net.shaddii.smartsorter.widget;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
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
        // Dropdown (takes most of the width)
        int dropdownWidth = width - 25;
        dropdown = new DropdownWidget(x, y, dropdownWidth, height, Text.literal(""));

        // Edit button (pencil icon)
        editButton = ButtonWidget.builder(
                Text.literal("✏"),
                btn -> startRenaming()
        ).dimensions(x + dropdownWidth + 2, y, 20, height).build();

        // Rename field (initially hidden)
        renameField = new TextFieldWidget(textRenderer, x, y, dropdownWidth, height, Text.literal(""));
        renameField.setMaxLength(32);
        renameField.setVisible(false);
        renameField.setDrawsBackground(true);
    }

    public void updateProbes(Map<BlockPos, ProcessProbeConfig> probeConfigs) {
        probes.clear();
        probes.addAll(probeConfigs.values());

        dropdown.clearEntries();

        if (probes.isEmpty()) {
            dropdown.addEntry("none", "No Process Probes");
            dropdown.setSelectedIndex(0);
            selectedIndex = 0;
        } else {
            for (int i = 0; i < probes.size(); i++) {
                ProcessProbeConfig config = probes.get(i);
                String displayName = config.getDisplayName();

                // Add validation warning
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

    private void startRenaming() {
        if (probes.isEmpty() || isRenaming) return;

        isRenaming = true;
        ProcessProbeConfig config = probes.get(selectedIndex);

        // Set current name or empty if using default
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

        // Only update if name changed
        if (!newName.isEmpty() || config.customName != null) {
            config.customName = newName.isEmpty() ? null : newName;

            // Send update to server
            ClientPlayNetworking.send(new ProbeConfigUpdatePayload(
                    config.position,
                    config.customName,
                    config.enabled,
                    config.recipeFilter,
                    config.fuelFilter
            ));

            // Notify callback
            if (onConfigUpdate != null) {
                onConfigUpdate.accept(config);
            }

            // Refresh dropdown
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

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MouseInput mouseInput = new MouseInput(button, 0);
        Click click = new Click(mouseX, mouseY, mouseInput);

        if (isRenaming) {
            if (renameField.mouseClicked(click, false)) {
                return true;
            }
            // Click outside finishes rename
            finishRenaming();
            return true;
        }

        if (editButton.mouseClicked(click, false)) {
            return true;
        }

        if (dropdown.mouseClicked(mouseX, mouseY, button)) {
            // Update selection
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
            return true;  // Always return true to consume the key
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