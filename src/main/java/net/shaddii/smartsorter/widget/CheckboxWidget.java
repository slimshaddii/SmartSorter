package net.shaddii.smartsorter.widget;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;
//? if >=1.21.9
/*import net.minecraft.client.gui.Click;*/
import org.joml.Matrix3x2f;

import java.util.function.Consumer;

public class CheckboxWidget extends ButtonWidget {
    private boolean checked;
    private final Consumer<Boolean> onToggle;
    private final TextRenderer textRenderer;

    public CheckboxWidget(int x, int y, int width, int height, Text message,
                          TextRenderer textRenderer, boolean initialState,
                          Consumer<Boolean> onToggle) {
        super(x, y, width, height, message, button -> {}, DEFAULT_NARRATION_SUPPLIER);
        this.textRenderer = textRenderer;
        this.checked = initialState;
        this.onToggle = onToggle;
    }

    //? if >=1.21.9 {
    /*@Override
    public void onClick(Click click, boolean doubled) {
        checked = !checked;
        if (onToggle != null) {
            onToggle.accept(checked);
        }
    }
    *///?} else {
    public void onClick(double mouseX, double mouseY) {
        checked = !checked;
        if (onToggle != null) {
            onToggle.accept(checked);
        }
    }
    //?}

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        // Draw checkbox box (SMALLER)
        int boxSize = 9;
        int boxX = getX();
        int boxY = getY() + (height - boxSize) / 2;

        // Box background
        context.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, 0xFF000000);
        context.fill(boxX + 1, boxY + 1, boxX + boxSize - 1, boxY + boxSize - 1,
                isHovered() ? 0xFFEEEEEE : 0xFFCCCCCC);

        // Checkmark
        if (checked) {
            context.fill(boxX + 2, boxY + 2, boxX + boxSize - 2, boxY + boxSize - 2, 0xFF00AA00);
        }

        // Label (SCALED DOWN)
        float scale = 0.65f;
        int labelX = boxX + boxSize + 3;
        int labelY = getY() + (height - 6) / 2;

        Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
        Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
        context.getMatrices().mul(scaleMatrix);

        Matrix3x2f translateMatrix = new Matrix3x2f().translation(labelX / scale, labelY / scale);
        context.getMatrices().mul(translateMatrix);

        context.drawText(textRenderer, getMessage(), 0, 0, 0xFFFFFFFF, false);
        context.getMatrices().set(oldMatrix);
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public boolean isChecked() {
        return checked;
    }

    @Override
    public void appendClickableNarrations(NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
    }

    // Builder pattern
    public static Builder builder(Text text, TextRenderer textRenderer) {
        return new Builder(text, textRenderer);
    }

    public static class Builder {
        private final Text text;
        private final TextRenderer textRenderer;
        private int x, y;
        private int width = 100, height = 20;
        private boolean initialState = false;
        private Consumer<Boolean> callback;

        Builder(Text text, TextRenderer textRenderer) {
            this.text = text;
            this.textRenderer = textRenderer;
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder dimensions(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder checked(boolean state) {
            this.initialState = state;
            return this;
        }

        public Builder callback(Consumer<Boolean> callback) {
            this.callback = callback;
            return this;
        }

        public CheckboxWidget build() {
            return new CheckboxWidget(x, y, width, height, text, textRenderer,
                    initialState, callback);
        }
    }
}