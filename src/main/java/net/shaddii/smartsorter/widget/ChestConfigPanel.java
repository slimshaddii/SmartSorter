package net.shaddii.smartsorter.widget;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
//? if >=1.21.9 {
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
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

public class ChestConfigPanel implements Drawable, Element, Selectable {
    private final int x, y, width, height;
    private final TextRenderer textRenderer;

    private ChestConfig currentConfig;
    private Consumer<ChestConfig> onConfigUpdate;

    // Widgets
    private DropdownWidget categoryDropdown;
    private DropdownWidget filterModeDropdown;
    private CheckboxWidget strictNBTCheckbox;
    private TextFieldWidget priorityField;
    private TextFieldWidget nameField; // ✅ ADD: Name field
    private ButtonWidget renameButton; // ✅ ADD: Rename button
    private int maxPriority = 1;

    private final List<Category> categoryList = new ArrayList<>();

    private static final int PADDING = 5;
    private static final int LINE_HEIGHT = 11;

    // Renaming state
    private boolean isRenaming = false;
    private final boolean showRenameButton;

    public ChestConfigPanel(int x, int y, int width, int height, TextRenderer textRenderer, boolean showRenameButton) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.textRenderer = textRenderer;
        this.showRenameButton = showRenameButton;

        initWidgets();
    }

    public ChestConfigPanel(int x, int y, int width, int height, TextRenderer textRenderer) {
        this(x, y, width, height, textRenderer, false);
    }

    private void initWidgets() {
        int innerX = x + PADDING;
        int innerY = y + 18;

        // Name field (initially hidden)
        if (showRenameButton) {
            nameField = new TextFieldWidget(textRenderer, innerX, innerY, width - PADDING * 2 - 45, 10, Text.literal(""));
            nameField.setMaxLength(32);
            nameField.setVisible(false);
            nameField.setChangedListener(this::onNameChanged);

            renameButton = ButtonWidget.builder(Text.literal("✎"), btn -> toggleRename())
                    .dimensions(innerX + width - PADDING * 2 - 40, innerY, 35, 10)
                    .build();

            innerY += 13; // Adjust for name field space
        }

        categoryDropdown = new DropdownWidget(innerX, innerY, 80, 10, Text.literal(""));
        List<Category> allCategories = CategoryManager.getInstance().getAllCategories();
        for (Category category : allCategories) {
            categoryList.add(category);
            categoryDropdown.addEntry(category.getShortName(), category.getDisplayName());
        }
        categoryDropdown.setOnSelect(this::onCategoryChanged);

        priorityField = new TextFieldWidget(textRenderer, innerX + 35, innerY, 30, 10, Text.literal(""));
        priorityField.setMaxLength(3);
        priorityField.setText("1");
        priorityField.setChangedListener(this::onPriorityChanged);

        filterModeDropdown = new DropdownWidget(innerX + 35, innerY + 36, 100, 10, Text.literal(""));
        for (ChestConfig.FilterMode mode : ChestConfig.FilterMode.values()) {
            filterModeDropdown.addEntry(mode.getDisplayName(), mode.getDisplayName());
        }
        filterModeDropdown.setOnSelect(this::onFilterModeChanged);

        strictNBTCheckbox = CheckboxWidget.builder(Text.literal("Match NBT"), textRenderer)
                .pos(innerX, innerY + 50)
                .dimensions(60, 9)
                .callback(this::onStrictNBTChanged)
                .build();
    }

    // ✅ ADD: Toggle rename mode
    private void toggleRename() {
        isRenaming = !isRenaming;
        nameField.setVisible(isRenaming);

        if (isRenaming) {
            nameField.setFocused(true);
            if (currentConfig != null && currentConfig.customName != null) {
                nameField.setText(currentConfig.customName);
            }
        } else {
            nameField.setFocused(false);
            // Save on close
            if (currentConfig != null && !nameField.getText().equals(currentConfig.customName)) {
                currentConfig.customName = nameField.getText();
                notifyUpdate();
            }
        }
    }

    // ✅ ADD: Name changed callback
    private void onNameChanged(String text) {
        // Update happens when rename mode is toggled off
    }

    private void onPriorityChanged(String text) {
        if (currentConfig == null || text.isEmpty()) return;
        try {
            int value = Integer.parseInt(text);
            if (value < 1) value = 1;
            if (value > maxPriority) value = maxPriority;
            if (currentConfig.priority == value) return;
            currentConfig.priority = value;
            currentConfig.updateHiddenPriority();
            notifyUpdate();
        } catch (NumberFormatException e) {
            // ignore
        }
    }

    private void onStrictNBTChanged(boolean checked) {
        if (currentConfig != null) {
            currentConfig.strictNBTMatch = checked;
            notifyUpdate();
        }
    }

    @Override
    public boolean isFocused() {
        return (priorityField != null && priorityField.isFocused()) ||
                (nameField != null && nameField.isFocused());
    }

    @Override
    public void setFocused(boolean focused) {
        if (priorityField != null) {
            priorityField.setFocused(focused);
        }
        if (nameField != null && !focused) {
            nameField.setFocused(false);
        }
    }

    public void setConfig(ChestConfig config) {
        this.currentConfig = config;
        if (config == null) {
            categoryDropdown.setSelectedIndex(0);
            priorityField.setText("1");
            filterModeDropdown.setSelectedIndex(0);

            // Only clear nameField if it exists
            if (nameField != null) {
                nameField.setText("");
                nameField.setVisible(false);
            }
            isRenaming = false;
            return;
        }

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

        // Only set name field if it exists
        if (nameField != null) {
            if (config.customName != null && !config.customName.isEmpty()) {
                nameField.setText(config.customName);
            } else {
                nameField.setText("");
            }
        }
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
            currentConfig.updateHiddenPriority();
            ClientPlayNetworking.send(new ChestConfigUpdatePayload(currentConfig));
            onConfigUpdate.accept(currentConfig);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(x, y, x + width, y + height, 0xFF2B2B2B);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF3C3C3C);

        if (currentConfig == null) {
            drawScaledText(context, "No chest selected", x + width / 2 - 30, y + height / 2, 0xFF888888, 0.65f);
            return;
        }

        int currentY = y + 4;
        int innerX = x + PADDING;

        drawScaledText(context, "Chest Config", innerX, currentY, 0xFFFFFFFF, 0.7f);
        currentY += 8;

        // Show name or rename field
        if (showRenameButton) {
            if (isRenaming) {
                nameField.setX(innerX);
                nameField.setY(currentY);
                nameField.setWidth(width - PADDING * 2 - 40);
                nameField.render(context, mouseX, mouseY, delta);
            } else if (currentConfig.customName != null && !currentConfig.customName.isEmpty()) {
                String nameDisplay = currentConfig.customName.length() > 25
                        ? currentConfig.customName.substring(0, 22) + "..."
                        : currentConfig.customName;
                drawScaledText(context, "§7Name: §f" + nameDisplay, innerX, currentY, 0xFFFFFFFF, 0.65f);
            } else {
                drawScaledText(context, "§7Name: §8(unnamed)", innerX, currentY, 0xFF888888, 0.65f);
            }

            renameButton.setX(innerX + width - PADDING * 2 - 40);
            renameButton.setY(currentY - 1);
            renameButton.render(context, mouseX, mouseY, delta);

            currentY += 12;
        } else {
            // Show name without rename button (controller's selector handles renaming)
            if (currentConfig.customName != null && !currentConfig.customName.isEmpty()) {
                String nameDisplay = currentConfig.customName.length() > 25
                        ? currentConfig.customName.substring(0, 22) + "..."
                        : currentConfig.customName;
                drawScaledText(context, "§7Name: §f" + nameDisplay, innerX, currentY, 0xFFFFFFFF, 0.65f);
                currentY += 7;
            }
        }


        String location = String.format("§8[%d, %d, %d]",
                currentConfig.position.getX(),
                currentConfig.position.getY(),
                currentConfig.position.getZ());
        drawScaledText(context, location, innerX, currentY, 0xFFAAAAAA, 0.65f);
        currentY += 10;

        if (currentConfig.filterMode.needsCategoryFilter()) {
            drawScaledText(context, "§7Filter:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
            categoryDropdown.setX(innerX + 35);
            categoryDropdown.setY(currentY);
            categoryDropdown.render(context, mouseX, mouseY, delta);
            currentY += 13;
        }

        drawScaledText(context, "§7Priority:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
        priorityField.setX(innerX + 35);
        priorityField.setY(currentY);
        priorityField.render(context, mouseX, mouseY, delta);

        drawScaledText(context, "§8(1-" + maxPriority + ")", innerX + 68, currentY + 1, 0xFF888888, 0.55f);
        currentY += 13;

        drawScaledText(context, "§7Mode:", innerX, currentY + 1, 0xFFAAAAAA, 0.65f);
        filterModeDropdown.setX(innerX + 35);
        filterModeDropdown.setY(currentY);
        filterModeDropdown.render(context, mouseX, mouseY, delta);
        currentY += 13;

        if (currentConfig.filterMode != null) {
            String description = "§8" + currentConfig.filterMode.getDescription();
            drawScaledText(context, description, innerX + 2, currentY, 0xFF888888, 0.55f);
            currentY += 8;
        }

        if (currentConfig.filterMode == ChestConfig.FilterMode.CUSTOM) {
            strictNBTCheckbox.setX(innerX);
            strictNBTCheckbox.setY(currentY);
            strictNBTCheckbox.setChecked(currentConfig.strictNBTMatch);
            strictNBTCheckbox.render(context, mouseX, mouseY, delta);

            int descX = innerX + 63;
            if (currentConfig.strictNBTMatch) {
                drawScaledText(context, "§8- Exact match (enchants, damage)", descX, currentY + 1, 0xFF888888, 0.55f);
            } else {
                drawScaledText(context, "§8- Item type only (ignores NBT)", descX, currentY + 1, 0xFF888888, 0.55f);
            }
        }

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
    public boolean keyPressed(KeyInput input) {
        if (nameField != null && nameField.isFocused()) {
            if (input.key() == 257) { // Enter key
                toggleRename();
                return true;
            }
            return nameField.keyPressed(input);
        }
        if (priorityField != null && priorityField.isFocused()) {
            return priorityField.keyPressed(input);
        }
        return false;
    }

    public boolean charTyped(CharInput input) {
        if (nameField != null && nameField.isFocused()) {
            return nameField.charTyped(input);
        }
        if (priorityField != null && priorityField.isFocused()) {
            return priorityField.charTyped(input);
        }
        return false;
    }
    //?} else {
    /*public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameField != null && nameField.isFocused()) {
            if (keyCode == 257) { // Enter key
                toggleRename();
                return true;
            }
            return nameField.keyPressed(keyCode, scanCode, modifiers);
        }
        if (priorityField != null && priorityField.isFocused()) {
            return priorityField.keyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (nameField != null && nameField.isFocused()) {
            return nameField.charTyped(chr, modifiers);
        }
        if (priorityField != null && priorityField.isFocused()) {
            return priorityField.charTyped(chr, modifiers);
        }
        return false;
    }
    *///?}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (currentConfig == null) return false;

        if (filterModeDropdown.isOpen()) {
            return filterModeDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        if (currentConfig.filterMode.needsCategoryFilter() && categoryDropdown.isOpen()) {
            return categoryDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        return false;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (currentConfig == null) return false;

        // Only handle rename button if enabled
        if (showRenameButton && renameButton != null) {
            //? if >=1.21.9 {
            if (renameButton.mouseClicked(new Click(mouseX, mouseY, new MouseInput(button, 0)), false)) {
                return true;
            }
            //?} else {
        /*if (renameButton.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        *///?}

            // Handle name field
            if (isRenaming) {
                //? if >=1.21.9 {
                if (nameField.mouseClicked(new Click(mouseX, mouseY, new MouseInput(button, 0)), false)) {
                    nameField.setFocused(true);
                    return true;
                }
                //?} else {
            /*if (nameField.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            *///?}
            }
        }

        if (currentConfig.filterMode.needsCategoryFilter()) {
            if (categoryDropdown.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        //? if >=1.21.9 {
        if (priorityField.mouseClicked(new Click(mouseX, mouseY, new MouseInput(button, 0)), false)) {
            priorityField.setFocused(true);
            return true;
        }
        //?} else {
        /*if (priorityField.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        *///?}

        if (filterModeDropdown.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        if (currentConfig.filterMode == ChestConfig.FilterMode.CUSTOM) {
            //? if >=1.21.9 {
            if (strictNBTCheckbox.mouseClicked(new Click(mouseX, mouseY, new MouseInput(button, 0)), false)) {
                return true;
            }
            //?} else {
            /*if (strictNBTCheckbox.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            *///?}
        }

        return false;
    }

    @Override
    public SelectionType getType() {
        return SelectionType.NONE;
    }

    @Override
    public void appendNarrations(NarrationMessageBuilder builder) {
        // Can be left empty
    }
}