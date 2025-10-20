package net.shaddii.smartsorter.widget;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseInput;
//?}
import net.minecraft.text.Text;
import net.shaddii.smartsorter.network.ChestConfigUpdatePayload;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChestConfigPanel {
    private final int x, y, width, height;
    private final TextRenderer textRenderer;

    private ChestConfig currentConfig;
    private Consumer<ChestConfig> onConfigUpdate;

    // Widgets
    private DropdownWidget categoryDropdown;
    private DropdownWidget filterModeDropdown;
    private CheckboxWidget strictNBTCheckbox;
    private TextFieldWidget priorityField;
    private int maxPriority = 1;

    private final List<Category> categoryList = new ArrayList<>();

    private static final int PADDING = 5;
    private static final int LINE_HEIGHT = 11;

    public ChestConfigPanel(int x, int y, int width, int height, TextRenderer textRenderer) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.textRenderer = textRenderer;

        initWidgets();
    }

    private void initWidgets() {
        int innerX = x + PADDING;
        int innerY = y + 18;

        // Category dropdown
        categoryDropdown = new DropdownWidget(
                innerX, innerY,
                80, 10,
                Text.literal("")
        );

        List<Category> allCategories = CategoryManager.getInstance().getAllCategories();
        for (Category category : allCategories) {
            categoryList.add(category);
            categoryDropdown.addEntry(category.getShortName(), category.getDisplayName());
        }

        categoryDropdown.setOnSelect(this::onCategoryChanged);

        // Priority text field (replace button)
        priorityField = new TextFieldWidget(textRenderer, innerX + 35, innerY, 30, 10, Text.literal(""));
        priorityField.setMaxLength(3); // Max 999 chests
        priorityField.setText("1");
        priorityField.setChangedListener(this::onPriorityChanged);

        // Filter mode dropdown
        filterModeDropdown = new DropdownWidget(
                innerX + 35, innerY + 36,
                100, 10,
                Text.literal("")
        );

        // Strict NBT checkbox (for CUSTOM mode)
        strictNBTCheckbox = CheckboxWidget.builder(Text.literal("Match NBT"), textRenderer)
                .pos(innerX, innerY + 50)
                .dimensions(60, 9)
                .callback(this::onStrictNBTChanged)
                .build();

        // Add all filter modes
        for (ChestConfig.FilterMode mode : ChestConfig.FilterMode.values()) {
            filterModeDropdown.addEntry(mode.getDisplayName(), mode.getDisplayName());
        }

        filterModeDropdown.setOnSelect(this::onFilterModeChanged);
    }

    private void onPriorityChanged(String text) {
        if (currentConfig == null || text.isEmpty()) return;

        try {
            int value = Integer.parseInt(text);
            // Clamp to valid range
            if (value < 1) value = 1;
            if (value > maxPriority) value = maxPriority;

            if (currentConfig.priority == value) {
                return;
            }

            currentConfig.priority = value;
            currentConfig.updateHiddenPriority();
            notifyUpdate();
        } catch (NumberFormatException e) {
            // Invalid input, ignore
        }
    }


    private void onStrictNBTChanged(boolean checked) {
        if (currentConfig != null) {
            currentConfig.strictNBTMatch = checked;
            notifyUpdate();
        }
    }

    public boolean isFocused() {
        // Check if priority text field is focused
        return priorityField != null && priorityField.isFocused();
    }

    public void setFocused(boolean focused) {
        // Unfocus the priority text field
        if (priorityField != null) {
            priorityField.setFocused(focused);
        }
    }

    public void setConfig(ChestConfig config) {
        this.currentConfig = config;

        if (config == null) {
            categoryDropdown.setSelectedIndex(0);
            priorityField.setText("1");
            filterModeDropdown.setSelectedIndex(0);
            return;
        }

        // Find category index
        int categoryIndex = 0;
        for (int i = 0; i < categoryList.size(); i++) {
            if (categoryList.get(i).getId().equals(config.filterCategory.getId())) {
                categoryIndex = i;
                break;
            }
        }
        categoryDropdown.setSelectedIndex(categoryIndex);

        String priorityText = String.valueOf(config.priority);
        if (!priorityField.getText().equals(priorityText)) {
            priorityField.setText(priorityText);
        }

        filterModeDropdown.setSelectedIndex(config.filterMode.ordinal());
        strictNBTCheckbox.setChecked(config.strictNBTMatch);
    }

    public void setMaxPriority(int max) {
        this.maxPriority = Math.max(1, max);
    }

    public void setOnConfigUpdate(Consumer<ChestConfig> callback) {
        this.onConfigUpdate = callback;
    }

    private void onCategoryChanged(int index) {
        if (currentConfig != null && index >= 0 && index < categoryList.size()) {
            currentConfig.filterCategory = categoryList.get(index);
            currentConfig.updateHiddenPriority();
            notifyUpdate();
        }
    }

    private void onFilterModeChanged(int index) {
        if (currentConfig != null && index >= 0 && index < ChestConfig.FilterMode.values().length) {
            currentConfig.filterMode = ChestConfig.FilterMode.values()[index];
            currentConfig.updateHiddenPriority();
            notifyUpdate();
        }
    }

    private void notifyUpdate() {
        if (onConfigUpdate != null && currentConfig != null) {
            // Recalculate hidden priority before sending
            currentConfig.updateHiddenPriority();

            // Send to server
            ClientPlayNetworking.send(new ChestConfigUpdatePayload(currentConfig));

            // Notify local callback (this should trigger selector refresh)
            onConfigUpdate.accept(currentConfig);
        }
    }


    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Background
        context.fill(x, y, x + width, y + height, 0xFF2B2B2B);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF3C3C3C);

        if (currentConfig == null) {
            drawScaledText(context, "No chest selected", x + width / 2 - 30, y + height / 2, 0xFF888888, 0.65f);
            return;
        }

        int currentY = y + 4;
        int innerX = x + PADDING;

        // Title
        drawScaledText(context, "Chest Config", innerX, currentY, 0xFFFFFFFF, 0.7f);
        currentY += 8;

        // Custom name (if set)
        if (currentConfig.customName != null && !currentConfig.customName.isEmpty()) {
            String nameDisplay = currentConfig.customName.length() > 25
                    ? currentConfig.customName.substring(0, 22) + "..."
                    : currentConfig.customName;
            drawScaledText(context, "§7Name: §f" + nameDisplay, innerX, currentY, 0xFFFFFFFF, 0.65f);
            currentY += 7;
        }

        // Location (coordinates)
        String location = String.format("§8[%d, %d, %d]",
                currentConfig.position.getX(),
                currentConfig.position.getY(),
                currentConfig.position.getZ());
        drawScaledText(context, location, innerX, currentY, 0xFFAAAAAA, 0.65f);
        currentY += 10;

        // Category filter (only show if mode needs it)
        if (currentConfig.filterMode.needsCategoryFilter()) {
            drawScaledText(context, "§7Filter:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
            categoryDropdown.setX(innerX + 35);
            categoryDropdown.setY(currentY);
            categoryDropdown.render(context, mouseX, mouseY, delta);
            currentY += 13;
        }

        // Priority
        drawScaledText(context, "§7Priority:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
        priorityField.setX(innerX + 35);
        priorityField.setY(currentY);
        priorityField.render(context, mouseX, mouseY, delta);

        // Show valid range
        drawScaledText(context, "§8(1-" + maxPriority + ")", innerX + 68, currentY + 1, 0xFF888888, 0.55f);
        currentY += 13;

        // Filter Mode
        drawScaledText(context, "§7Mode:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
        filterModeDropdown.setX(innerX + 35);
        filterModeDropdown.setY(currentY);
        filterModeDropdown.render(context, mouseX, mouseY, delta);
        currentY += 13;

        // Show mode description
        if (currentConfig.filterMode != null) {
            String description = "§8" + currentConfig.filterMode.getDescription();
            drawScaledText(context, description, innerX + 2, currentY, 0xFF888888, 0.55f);
            currentY += 8;
        }

        // CUSTOM mode options
        if (currentConfig.filterMode == ChestConfig.FilterMode.CUSTOM) {
            // Checkbox aligned with labels on left
            strictNBTCheckbox.setX(innerX);
            strictNBTCheckbox.setY(currentY);
            strictNBTCheckbox.setChecked(currentConfig.strictNBTMatch);
            strictNBTCheckbox.render(context, mouseX, mouseY, delta);

            // Description text beside "Match NBT" label
            int descX = innerX + 63;  // After checkbox + "Match NBT" text
            if (currentConfig.strictNBTMatch) {
                drawScaledText(context, "§8- Exact match (enchants, damage)", descX, currentY + 1, 0xFF888888, 0.55f);
            } else {
                drawScaledText(context, "§8- Item type only (ignores NBT)", descX, currentY + 1, 0xFF888888, 0.55f);
            }

            currentY += 11;
        }

        // Render dropdowns on top if open
        if (categoryDropdown.isOpen()) {
            categoryDropdown.renderDropdown(context, mouseX, mouseY);
        }
        if (filterModeDropdown.isOpen()) {
            filterModeDropdown.renderDropdown(context, mouseX, mouseY);
        }
    }

    private void drawScaledText(DrawContext context, String text, int x, int y, int color, float scale) {
        //? if >=1.21.8 {
        Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
        Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
        context.getMatrices().mul(scaleMatrix);
        Matrix3x2f translateMatrix = new Matrix3x2f().translation(x / scale, y / scale);
        context.getMatrices().mul(translateMatrix);
        context.drawText(textRenderer, text, 0, 0, color, false);
        context.getMatrices().set(oldMatrix);
        //?} else {
        /*net.minecraft.client.util.math.MatrixStack matrices = context.getMatrices();
        matrices.push();
        matrices.scale(scale, scale, scale);
        matrices.translate(x / scale, y / scale, 0);
        context.drawText(textRenderer, text, 0, 0, color, false);
        matrices.pop();
        *///?}
    }

    //? if >=1.21.9 {
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (priorityField != null && priorityField.isFocused()) {
            return priorityField.keyPressed(new net.minecraft.client.input.KeyInput(keyCode, scanCode, modifiers));
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (priorityField != null && priorityField.isFocused()) {
            return priorityField.charTyped(new net.minecraft.client.input.CharInput(chr, modifiers));
        }
        return false;
    }
    //?} else {
    /*public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    if (priorityField != null && priorityField.isFocused()) {
        return priorityField.keyPressed(keyCode, scanCode, modifiers);
    }
    return false;
    }

    public boolean charTyped(char chr, int modifiers) {
    if (priorityField != null && priorityField.isFocused()) {
        return priorityField.charTyped(chr, modifiers);
    }
    return false;
    }
    *///?}

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (currentConfig == null) return false;

        // Filter mode dropdown scroll
        if (filterModeDropdown.isOpen()) {
            return filterModeDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        // Category dropdown scroll (only if mode needs it)
        if (currentConfig.filterMode.needsCategoryFilter() && categoryDropdown.isOpen()) {
            return categoryDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        return false;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (currentConfig == null) return false;

        // Category dropdown (only if mode needs it)
        if (currentConfig.filterMode.needsCategoryFilter()) {
            if (categoryDropdown.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        // Priority button
        //? if >=1.21.9 {
        if (priorityField.mouseClicked(new net.minecraft.client.gui.Click(mouseX, mouseY, new MouseInput(button, 0)), false)) {
            priorityField.setFocused(true);
            return true;
        }
        //?} else {
        /*int pfX = priorityField.getX();
        int pfY = priorityField.getY();
        int pfW = priorityField.getWidth();
        int pfH = priorityField.getHeight();

        if (mouseX >= pfX && mouseX < pfX + pfW && mouseY >= pfY && mouseY < pfY + pfH) {
        priorityField.setFocused(true);
        priorityField.onClick(mouseX, mouseY);
        return true;
        }
        *///?}

        // Filter mode dropdown
        if (filterModeDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // NBT checkbox (CUSTOM mode only)
        if (currentConfig.filterMode == ChestConfig.FilterMode.CUSTOM) {
            //? if >=1.21.9 {
            if (strictNBTCheckbox.mouseClicked(new net.minecraft.client.gui.Click(mouseX, mouseY, new MouseInput(button, 0)), false)) {
                return true;
            }
            //?} else {
        /*int cbX = strictNBTCheckbox.getX();
        int cbY = strictNBTCheckbox.getY();
        int cbW = strictNBTCheckbox.getWidth();
        int cbH = strictNBTCheckbox.getHeight();

        if (mouseX >= cbX && mouseX < cbX + cbW && mouseY >= cbY && mouseY < cbY + cbH) {
            strictNBTCheckbox.onClick(mouseX, mouseY);
            return true;
        }
        *///?}
        }

        return false;
    }
}