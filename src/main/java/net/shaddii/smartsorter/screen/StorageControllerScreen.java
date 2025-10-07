package net.shaddii.smartsorter.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.screen.widget.SearchBoxWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StorageControllerScreen extends HandledScreen<StorageControllerScreenHandler> {
    private static final Identifier TEXTURE = Identifier.of(SmartSorter.MOD_ID, "textures/gui/storage_controller.png");

    // Scrolling
    private float scrollProgress = 0.0f;
    private boolean isScrolling = false;

    private static final int ITEMS_PER_ROW = 9;
    private static final int VISIBLE_ROWS = 5; // uses 5 rows
    private static final int ITEMS_PER_PAGE = ITEMS_PER_ROW * VISIBLE_ROWS;

    // Network grid
    private static final int GRID_START_X = 8;
    private static final int GRID_START_Y = 18;
    private static final int SLOT_SIZE = 18;

    // Scrollbar
    private static final int SCROLLBAR_X = 174;
    private static final int SCROLLBAR_Y = 18;
    private static final int SCROLLBAR_WIDTH = 14;
    private static final int SCROLLBAR_HEIGHT = 90; // 5 rows × 18

    // Cached network items
    private List<Map.Entry<ItemVariant, Long>> networkItemsList = new ArrayList<>();
    private int maxScrollRows = 0;
    private int tickCounter = 0;

    // NEW: Search widget
    private SearchBoxWidget searchBox;
    private String currentSearch = "";


    public StorageControllerScreen(StorageControllerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);

        // Dimensions
        this.backgroundWidth = 194;
        this.backgroundHeight = 202;

        // Exact label positions
        this.titleX = 7;
        this.titleY = 6;
        this.playerInventoryTitleX = 8;
        this.playerInventoryTitleY = 109;

    }

    @Override
    protected void init() {
        super.init();

        handler.requestSync();
        updateNetworkItems();

        // NEW: Create search box
        int searchBoxWidth = 90;
        int searchBoxHeight = 13;
        int searchBoxX = (width + backgroundWidth) / 2 - searchBoxWidth - 25; // Left side
        int searchBoxY = (height - backgroundHeight) / 2 + 2; // Above GUI

        searchBox = new SearchBoxWidget(textRenderer, searchBoxX, searchBoxY, searchBoxWidth, searchBoxHeight);
        searchBox.setOnTextChanged(this::onSearchChanged);
    }

    private void onSearchChanged(String searchText) {
        currentSearch = searchText.toLowerCase();
        updateNetworkItems(); // Re-filter items
        scrollProgress = 0; // Reset scroll to top
    }

    private void updateNetworkItems() {
        Map<ItemVariant, Long> items = handler.getNetworkItems();
        networkItemsList = new ArrayList<>(items.entrySet());

        // NEW: Apply search filter
        if (!currentSearch.isEmpty()) {
            networkItemsList.removeIf(entry -> {
                String itemName = entry.getKey().getItem().getName().getString().toLowerCase();
                return !itemName.contains(currentSearch);
            });
        }

        // Sort by item name
        networkItemsList.sort((a, b) -> {
            String nameA = a.getKey().getItem().getName().getString();
            String nameB = b.getKey().getItem().getName().getString();
            return nameA.compareTo(nameB);
        });

        // Calculate max scroll
        int totalRows = (int) Math.ceil(networkItemsList.size() / (double) ITEMS_PER_ROW);
        maxScrollRows = Math.max(0, totalRows - VISIBLE_ROWS);
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        // Draw main GUI texture
        context.drawTexture(TEXTURE, x, y, 0, 0, this.backgroundWidth, this.backgroundHeight);

        // ALWAYS draw scrollbar (not just when needed)
        drawScrollbar(context, x, y);
    }

    private void drawScrollbar(DrawContext context, int guiX, int guiY) {
        int scrollbarX = guiX + SCROLLBAR_X;
        int scrollbarY = guiY + SCROLLBAR_Y;

        // Always draw scrollbar background (light gray track)
        context.fill(scrollbarX, scrollbarY, scrollbarX + SCROLLBAR_WIDTH, scrollbarY + SCROLLBAR_HEIGHT, 0xFFC6C6C6);

        // Draw border
        context.fill(scrollbarX, scrollbarY, scrollbarX + 1, scrollbarY + SCROLLBAR_HEIGHT, 0xFF373737); // Left
        context.fill(scrollbarX + SCROLLBAR_WIDTH - 1, scrollbarY, scrollbarX + SCROLLBAR_WIDTH, scrollbarY + SCROLLBAR_HEIGHT, 0xFFFFFFFF); // Right

        // Draw handle if scrollable
        if (maxScrollRows > 0) {
            int handleHeight = 15;
            int maxHandleOffset = SCROLLBAR_HEIGHT - handleHeight;
            int handleY = scrollbarY + (int) (scrollProgress * maxHandleOffset);

            // Handle background
            context.fill(scrollbarX + 1, handleY, scrollbarX + SCROLLBAR_WIDTH - 1, handleY + handleHeight, 0xFF8B8B8B);

            // Handle highlight
            context.fill(scrollbarX + 1, handleY, scrollbarX + SCROLLBAR_WIDTH - 1, handleY + 1, 0xFFFFFFFF);
        }
    }


    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);

        tickCounter++;
        if (tickCounter >= 10) {
            updateNetworkItems();
            tickCounter = 0;
        }

        // NEW: Tick search box for cursor blinking
        if (searchBox != null) {
            searchBox.tick();
        }

        super.render(context, mouseX, mouseY, delta);
        renderNetworkItems(context, mouseX, mouseY);

        // NEW: Render search box AFTER GUI but BEFORE tooltips
        if (searchBox != null) {
            searchBox.render(context, mouseX, mouseY);
        }

        drawMouseoverTooltip(context, mouseX, mouseY);
    }


    private void renderNetworkItems(DrawContext context, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        int scrollOffset = (int) (scrollProgress * maxScrollRows);
        int startIndex = scrollOffset * ITEMS_PER_ROW;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, networkItemsList.size());

        for (int i = startIndex; i < endIndex; i++) {
            int relativeIndex = i - startIndex;
            int row = relativeIndex / ITEMS_PER_ROW;
            int col = relativeIndex % ITEMS_PER_ROW;

            int slotX = x + GRID_START_X + (col * SLOT_SIZE);
            int slotY = y + GRID_START_Y + (row * SLOT_SIZE);

            var entry = networkItemsList.get(i);
            ItemVariant variant = entry.getKey();
            long amount = entry.getValue();

            // Draw slot background
            context.fill(slotX, slotY, slotX + 16, slotY + 16, 0x8B8B8B8B);

            // Draw item (WITHOUT count overlay)
            context.drawItem(variant.toStack(), slotX, slotY);

            // REMOVED: context.drawItemInSlot() - this was causing duplicates

            // Draw ONLY our custom scaled amount text
            if (amount > 1) {
                String amountText = formatAmount(amount);

                var matrices = context.getMatrices();
                matrices.push();

                float scale = 0.75f;

                // Position at bottom-right of slot
                float textX = slotX + 17 - textRenderer.getWidth(amountText) * scale;
                float textY = slotY + 9;

                matrices.translate(textX, textY, 200);
                matrices.scale(scale, scale, 1.0f);

                // Draw with white color and shadow
                context.drawText(textRenderer, amountText, 0, 0, 0xFFFFFF, true);

                matrices.pop();
            }

            // Highlight on hover
            if (isMouseOverSlot(slotX, slotY, mouseX, mouseY)) {
                context.fill(slotX, slotY, slotX + 16, slotY + 16, 0x80FFFFFF);
            }
        }
    }

    private String formatAmount(long amount) {
        if (amount >= 1_000_000_000) {
            return (amount / 1_000_000_000) + "B";
        } else if (amount >= 1_000_000) {
            return (amount / 1_000_000) + "M";
        } else if (amount >= 10_000) {
            return (amount / 1000) + "K";
        } else {
            return String.valueOf(amount);
        }
    }

    private boolean isMouseOverSlot(int slotX, int slotY, int mouseX, int mouseY) {
        return mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16;
    }

    private boolean needsScrollbar() {
        return maxScrollRows > 0;
    }

    private boolean isMouseOverScrollbar(double mouseX, double mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        int barX = x + SCROLLBAR_X;
        int barY = y + SCROLLBAR_Y;
        return mouseX >= barX && mouseX < barX + SCROLLBAR_WIDTH &&
                mouseY >= barY && mouseY < barY + SCROLLBAR_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check search box first
        if (searchBox != null && searchBox.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // Handle scrollbar
        if (needsScrollbar() && isMouseOverScrollbar(mouseX, mouseY)) {
            isScrolling = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        // Get GUI bounds
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        // Calculate network grid bounds
        int gridStartX = x + GRID_START_X;
        int gridStartY = y + GRID_START_Y;
        int gridEndX = gridStartX + (ITEMS_PER_ROW * SLOT_SIZE);
        int gridEndY = gridStartY + (VISIBLE_ROWS * SLOT_SIZE);

        // Check if click is within network grid area
        boolean clickInGrid = mouseX >= gridStartX && mouseX < gridEndX &&
                mouseY >= gridStartY && mouseY < gridEndY;

        if (clickInGrid) {
            // Handle network item slots
            int scrollOffset = (int) (scrollProgress * maxScrollRows);
            int startIndex = scrollOffset * ITEMS_PER_ROW;
            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, networkItemsList.size());

            for (int i = startIndex; i < endIndex; i++) {
                int relativeIndex = i - startIndex;
                int row = relativeIndex / ITEMS_PER_ROW;
                int col = relativeIndex % ITEMS_PER_ROW;

                int slotX = x + GRID_START_X + (col * SLOT_SIZE);
                int slotY = y + GRID_START_Y + (row * SLOT_SIZE);

                if (isMouseOverSlot(slotX, slotY, (int)mouseX, (int)mouseY)) {
                    handleNetworkSlotClick(i, button, hasShiftDown(), hasControlDown());
                    return true;
                }
            }

            // Clicked on empty grid area with cursor item - deposit it
            if (!handler.getCursorStack().isEmpty()) {
                handleEmptyAreaClick(button);
                return true;
            }
        }

        // Let parent handle inventory slot clicks (this is the fix!)
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Handle clicking on a network item slot
     * @param slotIndex - Index in the networkItemsList
     * @param button - Mouse button (0=left, 1=right, 2=middle)
     * @param isShift - Whether shift is held
     * @param isCtrl - Whether ctrl is held
     */
    private void handleNetworkSlotClick(int slotIndex, int button, boolean isShift, boolean isCtrl) {
        var entry = networkItemsList.get(slotIndex);
        ItemVariant variant = entry.getKey();
        long itemCount = entry.getValue();

        ItemStack cursorStack = handler.getCursorStack();

        // === DEPOSIT: Clicking with item in cursor ===
        if (!cursorStack.isEmpty()) {
            ItemVariant cursorVariant = ItemVariant.of(cursorStack);

            // Same item - try to merge
            if (cursorVariant.equals(variant)) {
                if (button == 0) {
                    // Left-click: Deposit all
                    handler.requestDeposit(cursorStack, cursorStack.getCount());
                } else if (button == 1) {
                    // Right-click: Deposit one
                    handler.requestDeposit(cursorStack, 1);
                }
            } else {
                // Different item - just deposit the cursor item
                handler.requestDeposit(cursorStack, cursorStack.getCount());
            }
            return;
        }

        // === EXTRACT: Clicking with empty cursor ===
        int amount;

        if (isShift) {
            // Shift-click: Extract to inventory
            amount = (int) Math.min(64, itemCount);
            handler.requestExtraction(variant, amount, true);
        } else if (isCtrl && button == 0) {
            // Ctrl+Left-Click ONLY: Take quarter stack
            amount = (int) Math.min(16, Math.max(1, itemCount / 4));
            handler.requestExtraction(variant, amount, false);
        } else {
            // Normal click
            if (button == 0) {
                // Left-click: Take full stack (64)
                amount = (int) Math.min(64, itemCount);
                handler.requestExtraction(variant, amount, false);
            } else if (button == 1) {
                // Right-click: Take half
                amount = (int) Math.min(32, Math.max(1, itemCount / 2));
                handler.requestExtraction(variant, amount, false);
            } else if (button == 2) {
                // Middle-click: Take max stack size
                amount = (int) Math.min(variant.getItem().getMaxCount(), itemCount);
                handler.requestExtraction(variant, amount, false);
            }
        }
    }

    /**
     * Handle clicking on empty area with item in cursor
     */
    private void handleEmptyAreaClick(int button) {
        ItemStack cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty()) return;

        if (button == 0) {
            // Left-click: Deposit all
            handler.requestDeposit(cursorStack, cursorStack.getCount());
        } else if (button == 1) {
            // Right-click: Deposit one
            handler.requestDeposit(cursorStack, 1);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isScrolling = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (isScrolling && needsScrollbar()) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    private void updateScrollFromMouse(double mouseY) {
        int y = (height - backgroundHeight) / 2;
        int barY = y + SCROLLBAR_Y;

        int handleHeight = 15;
        int maxHandleOffset = SCROLLBAR_HEIGHT - handleHeight;

        float relativeY = (float) (mouseY - barY - (handleHeight / 2.0));
        scrollProgress = Math.max(0, Math.min(1, relativeY / maxHandleOffset));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (needsScrollbar()) {
            float scrollAmount = (float) (-verticalAmount / (maxScrollRows + 1));
            scrollProgress = Math.max(0, Math.min(1, scrollProgress + scrollAmount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Draw "Controller" title
        context.drawText(textRenderer, Text.literal("Controller"), titleX, titleY, 0x404040, false);

        // Draw "Inventory" label
        context.drawText(textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);

        // Draw capacity indicator in top-right corner
        if (handler.controller != null) {
            int free = handler.controller.calculateTotalFreeSlots();
            int total = handler.controller.calculateTotalCapacity();

            if (total > 0) {
                float percentFree = (free / (float) total) * 100;

                // Color code based on capacity
                int color;
                if (percentFree > 50) {
                    color = 0x55FF55; // Green
                } else if (percentFree > 25) {
                    color = 0xFFFF55; // Yellow
                } else if (percentFree > 10) {
                    color = 0xFFAA00; // Orange
                } else {
                    color = 0xFF5555; // Red
                }

                String capacityText = free + "/" + total;
                int textWidth = textRenderer.getWidth(capacityText);

                // Draw in top-right corner (scaled down)
                var matrices = context.getMatrices();
                matrices.push();

                float scale = 0.75f;
                float textX = backgroundWidth - textWidth * scale - 26;
                float textY = 6;

                matrices.translate(textX, textY, 0);
                matrices.scale(scale, scale, 1.0f);

                context.drawText(textRenderer, Text.literal(capacityText), 0, 0, color, false);

                matrices.pop();
            }
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // NEW: Pass to search box first
        if (searchBox != null && searchBox.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        // NEW: Pass to search box first
        if (searchBox != null && searchBox.charTyped(chr, modifiers)) {
            return true;
        }

        return super.charTyped(chr, modifiers);
    }

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        super.drawMouseoverTooltip(context, mouseX, mouseY);

        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        int scrollOffset = (int) (scrollProgress * maxScrollRows);
        int startIndex = scrollOffset * ITEMS_PER_ROW;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, networkItemsList.size());

        for (int i = startIndex; i < endIndex; i++) {
            int relativeIndex = i - startIndex;
            int row = relativeIndex / ITEMS_PER_ROW;
            int col = relativeIndex % ITEMS_PER_ROW;

            int slotX = x + GRID_START_X + (col * SLOT_SIZE);
            int slotY = y + GRID_START_Y + (row * SLOT_SIZE);

            if (isMouseOverSlot(slotX, slotY, mouseX, mouseY)) {
                var entry = networkItemsList.get(i);
                ItemVariant variant = entry.getKey();
                long amount = entry.getValue();

                List<Text> tooltip = new ArrayList<>();
                tooltip.add(variant.getItem().getName());
                tooltip.add(Text.literal("§7Stored: §f" + String.format("%,d", amount)));
                tooltip.add(Text.literal(""));
                tooltip.add(Text.literal("§8Left-Click: §7Take stack (64)"));
                tooltip.add(Text.literal("§8Right-Click: §7Take half (32)"));
                tooltip.add(Text.literal("§8Ctrl+Left: §7Take quarter (16)"));
                tooltip.add(Text.literal("§8Shift-Click: §7To inventory"));
                tooltip.add(Text.literal("§8Middle-Click: §7Take max stack"));

                context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
                break;
            }
        }
    }
}