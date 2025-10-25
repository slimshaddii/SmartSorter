package net.shaddii.smartsorter.screen.tabs;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.widget.ChestConfigPanel;

//? if >=1.21.9 {
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
//?}

public abstract class TabComponent {
    protected final StorageControllerScreen parent;
    protected final StorageControllerScreenHandler handler;
    protected int guiX;
    protected int guiY;
    protected int backgroundWidth;
    protected int backgroundHeight;

    public TabComponent(StorageControllerScreen parent, StorageControllerScreenHandler handler) {
        this.parent = parent;
        this.handler = handler;
        this.backgroundWidth = 194;
        this.backgroundHeight = 202;
    }

    // Helper methods for version-specific input handling
    protected boolean handleKeyPress(TextFieldWidget widget, int keyCode, int scanCode, int modifiers) {
        //? if >=1.21.9 {
        return widget.keyPressed(new KeyInput(keyCode, scanCode, modifiers));
        //?} else {
        /*return widget.keyPressed(keyCode, scanCode, modifiers);
         *///?}
    }

    protected boolean handleKeyPress(ChestConfigPanel widget, int keyCode, int scanCode, int modifiers) {
        //? if >=1.21.9 {
        return widget.keyPressed(new KeyInput(keyCode, scanCode, modifiers));
        //?} else {
        /*return widget.keyPressed(keyCode, scanCode, modifiers);
         *///?}
    }

    protected boolean handleCharType(TextFieldWidget widget, char chr, int modifiers) {
        //? if >=1.21.9 {
        return widget.charTyped(new CharInput(chr, modifiers));
        //?} else {
        /*return widget.charTyped(chr, modifiers);
         *///?}
    }

    protected boolean handleCharType(ChestConfigPanel widget, char chr, int modifiers) {
        //? if >=1.21.9 {
        return widget.charTyped(new CharInput(chr, modifiers));
        //?} else {
        /*return widget.charTyped(chr, modifiers);
         *///?}
    }

    public void init(int guiX, int guiY) {
        this.guiX = guiX;
        this.guiY = guiY;
        initWidgets();
    }

    protected abstract void initWidgets();

    public abstract void render(DrawContext context, int mouseX, int mouseY, float delta);

    public abstract boolean mouseClicked(double mouseX, double mouseY, int button);

    public abstract boolean mouseReleased(double mouseX, double mouseY, int button);

    public abstract boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY);

    public abstract boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount);

    public abstract boolean keyPressed(int keyCode, int scanCode, int modifiers);

    public abstract boolean charTyped(char chr, int modifiers);

    public void onClose() {
        // Override if cleanup needed
    }

    public void markDirty() {
        // Override if refresh needed
    }
}