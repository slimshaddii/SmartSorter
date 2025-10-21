package net.shaddii.smartsorter.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.widget.ChestConfigPanel;

//? if >= 1.21.9 {
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
//?}

public class OutputProbeScreen extends HandledScreen<OutputProbeScreenHandler> {
    // ========================================
    // FIELDS
    // ========================================

    private ChestConfigPanel configPanel;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public OutputProbeScreen(OutputProbeScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);

        // Smaller GUI - just for config panel
        this.backgroundWidth = 180;
        this.backgroundHeight = 140;

        this.titleX = 8;
        this.titleY = 6;

        // Hide player inventory title
        this.playerInventoryTitleY = 10000; // Move off-screen
    }

    // ========================================
    // INITIALIZATION
    // ========================================

    @Override
    protected void init() {
        super.init();

        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        // Chest config panel - takes up most of the GUI
        configPanel = new ChestConfigPanel(
                x + 8, y + 18,
                backgroundWidth - 16, backgroundHeight - 26,
                textRenderer,
                true
        );

        ChestConfig config = handler.getChestConfig();
        if (config != null) {
            configPanel.setConfig(config);
            // Set max priority based on total chests in network
            configPanel.setMaxPriority(10); // You can adjust this or get from handler
        }

        configPanel.setOnConfigUpdate(updatedConfig -> {
            handler.updateChestConfig(updatedConfig);
        });

        addDrawableChild(configPanel);

        //? if >= 1.21.9 {
        // Register mouse events for 1.21.9+
        ScreenMouseEvents.allowMouseClick(this).register((screen, click) -> {
            if (!(screen instanceof OutputProbeScreen gui)) return true;

            if (gui.configPanel != null && gui.configPanel.mouseClicked(click.x(), click.y(), click.button())) {
                return false; // Consume the event
            }
            return true;
        });
        //?}
    }

    // ========================================
    // RENDERING
    // ========================================

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        // Main background
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, 0xFF2B2B2B);
        context.fill(x + 1, y + 1, x + backgroundWidth - 1, y + backgroundHeight - 1, 0xFF3C3C3C);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Title
        context.drawText(textRenderer, Text.literal("Chest Configuration"), titleX, titleY, 0xFFFFFFFF, false);

        // Show chest position if available
        if (handler.chestPos != null) {
            String posText = String.format("§8[%d, %d, %d]",
                    handler.chestPos.getX(),
                    handler.chestPos.getY(),
                    handler.chestPos.getZ()
            );
            int posWidth = textRenderer.getWidth(posText);
            context.drawText(textRenderer, Text.literal(posText),
                    backgroundWidth - posWidth - 8, titleY, 0xFF888888, false);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Don't render the default background (no inventory needed)
        super.render(context, mouseX, mouseY, delta);

        if (configPanel != null) {
            configPanel.render(context, mouseX, mouseY, delta);
        }

        // Tooltip rendering
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        // Don't render slot tooltips since we don't have inventory
        // ConfigPanel handles its own tooltips
    }

    // ========================================
// INPUT HANDLING (VERSION-SPECIFIC)
// ========================================

    //? if >= 1.21.9 {
    @Override
    public boolean keyPressed(KeyInput input) {
        // 1. Let the text field handle typing first
        if (configPanel != null && configPanel.keyPressed(input)) {
            return true;
        }

        // 2. If not typing, then block the inventory key from closing the screen
        if (this.client.options.inventoryKey.matchesKey(input)) {
            return true;
        }

        // 3. Fallback to default behavior (e.g., ESC key)
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (configPanel != null && configPanel.charTyped(input)) {
            return true;
        }
        return super.charTyped(input);
    }
//?} else {
    /*@Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 1. Let the text field handle typing first
        if (configPanel != null && configPanel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        // 2. If not typing, then block the inventory key from closing the screen
        if (this.client.options.inventoryKey.matchesKey(keyCode, scanCode)) {
            return true;
        }

        // 3. Fallback to default behavior (e.g., ESC key)
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (configPanel != null && configPanel.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
}

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (configPanel != null && configPanel.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
    *///?}

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (configPanel != null && configPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ========================================
    // UTILITY
    // ========================================

    /**
     * Update the displayed config (e.g., when synced from server)
     */
    public void refreshConfig() {
        if (configPanel != null) {
            ChestConfig config = handler.getChestConfig();
            configPanel.setConfig(config);
        }
    }
}