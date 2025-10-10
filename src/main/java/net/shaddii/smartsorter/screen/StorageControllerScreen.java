package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.shaddii.smartsorter.network.FilterCategoryChangePayload;
import net.shaddii.smartsorter.network.SortModeChangePayload;
import net.shaddii.smartsorter.util.FilterCategory;
import net.shaddii.smartsorter.util.SortMode;
import net.shaddii.smartsorter.widget.DropdownWidget;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix3x2f;
//import org.slf4j.Logger; // DEBUG: For debug logging
//import org.slf4j.LoggerFactory; // DEBUG: For debug logging

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Storage Controller Screen for SmartSorter
 * Updated for Minecraft 1.21.9 with proper API usage
 */
public class StorageControllerScreen extends HandledScreen<StorageControllerScreenHandler> {
   // private static final Logger LOGGER = LoggerFactory.getLogger("SmartSorter-GUI");
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

    // Search widget
    private TextFieldWidget searchBox;
    private String currentSearch = "";

    // Sort button
    private ButtonWidget sortButton;

    // Filter Categories
    private DropdownWidget filterDropdown;


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
        // DEBUG: LOGGER.info("[DEBUG] GUI init() called");

        handler.requestSync();
        updateNetworkItems();
        // DEBUG: LOGGER.info("[DEBUG] Network items count: {}", networkItemsList.size());

        int searchBoxWidth = 90;
        int searchBoxHeight = 13;
        int searchBoxX = (width - backgroundWidth) / 2 + 82;
        int searchBoxY = (height - backgroundHeight) / 2 + 6;

        searchBox = new TextFieldWidget(this.textRenderer, searchBoxX, searchBoxY, searchBoxWidth, searchBoxHeight, Text.literal(""));
        searchBox.setDrawsBackground(false);
        searchBox.setChangedListener(this::onSearchChanged);
        addDrawableChild(searchBox);

        // Add sort button
        int sortButtonX = searchBoxX;
        int sortButtonY = searchBoxY + searchBoxHeight - 34;
        int sortButtonWidth = 30;
        int sortButtonHeight = 12;

        sortButton = ButtonWidget.builder(
                        Text.literal(handler.getSortMode().getDisplayName()),
                        button -> cycleSortMode()
                )
                .dimensions(sortButtonX, sortButtonY, sortButtonWidth, sortButtonHeight)
                .build();

        addDrawableChild(sortButton);

        // Add filter dropdown next to sort button
        int filterDropdownX = sortButtonX + sortButtonWidth + 2;
        int filterDropdownY = sortButtonY;
        int filterDropdownWidth = 60;
        int filterDropdownHeight = 12;

        filterDropdown = new DropdownWidget(
                filterDropdownX, filterDropdownY,
                filterDropdownWidth, filterDropdownHeight,
                Text.literal("")
        );

        // Add all categories to dropdown
        for (FilterCategory category : FilterCategory.values()) {
            filterDropdown.addEntry(category.getShortName(), category.getDisplayName());
        }

        // Set current selection
        filterDropdown.setSelectedIndex(handler.getFilterCategory().ordinal());

        // Set callback when selection changes
        filterDropdown.setOnSelect(index -> {
            FilterCategory selected = FilterCategory.values()[index];
            handler.setFilterCategory(selected);
            updateNetworkItems();
            ClientPlayNetworking.send(new FilterCategoryChangePayload(selected.asString()));
        });

        addDrawableChild(filterDropdown);

        // Debug
        filterDropdown.active = true;
        filterDropdown.visible = true;


        // Register Fabric screen mouse events using Click records (1.21.9)
        ScreenMouseEvents.allowMouseClick(this).register((screen, click) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            // Handle dropdown clicks FIRST (highest priority)
            if (gui.filterDropdown != null && gui.filterDropdown.isOpen()) {
                if (gui.filterDropdown.isMouseOver(click.x(), click.y())) {
                    // Click is on dropdown - handle it
                    boolean handled = gui.filterDropdown.mouseClicked(click.x(), click.y(), click.button());
                    return !handled; // If dropdown handled it, stop propagation
                } else {
                    // Click is outside dropdown - close it
                    gui.filterDropdown.close();
                    return true; // Consume the click
                }
            }

            // If clicking inside the search box, focus it and allow vanilla to handle the click
            if (gui.searchBox != null) {
                int sx = gui.searchBox.getX();
                int sy = gui.searchBox.getY();
                int sw = gui.searchBox.getWidth();
                int sh = gui.searchBox.getHeight();
                if (click.x() >= sx && click.x() < sx + sw && click.y() >= sy && click.y() < sy + sh) {
                    gui.setFocused(gui.searchBox);
                    gui.searchBox.setFocused(true);
                    return true; // allow vanilla routing to deliver to TextFieldWidget
                }
            }

            boolean consumed = gui.onMouseClickIntercept(click.x(), click.y(), click.button());
            return !consumed; // true => allow vanilla; false => stop
        });

        ScreenMouseEvents.allowMouseRelease(this).register((screen, click) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;
            boolean consumed = gui.onMouseReleaseIntercept(click.x(), click.y(), click.button());
            return !consumed;
        });

        ScreenMouseEvents.allowMouseDrag(this).register((screen, click, deltaX, deltaY) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;
            boolean consumed = gui.onMouseDragIntercept(click.x(), click.y(), click.button(), deltaX, deltaY);
            return !consumed;
        });

        ScreenMouseEvents.allowMouseScroll(this).register((screen, mouseX, mouseY, horizontal, vertical) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            // Let dropdown handle scroll first if it's open
            if (gui.filterDropdown != null && gui.filterDropdown.isOpen()) {
                boolean handled = gui.filterDropdown.mouseScrolled(mouseX, mouseY, horizontal, vertical);
                if (handled) {
                    return false; // Consumed by dropdown
                }
            }

            boolean consumed = gui.onMouseScrollIntercept(mouseX, mouseY, horizontal, vertical);
            return !consumed;
        });
    }

    private void onSearchChanged(String searchText) {
        currentSearch = searchText.toLowerCase();
        updateNetworkItems();
        scrollProgress = 0;
    }

    /**
     * Cycle to next sort mode and notify server.
     */
    private void cycleSortMode() {
        SortMode currentMode = handler.getSortMode();
        SortMode newMode = currentMode.next();

        // Update button text
        sortButton.setMessage(Text.literal(newMode.getDisplayName()));

        // Update client-side handler (won't trigger server update because we're on client)
        handler.setSortMode(newMode);

        // Force immediate visual refresh
        updateNetworkItems();

        // Send packet to server to sync
        ClientPlayNetworking.send(new SortModeChangePayload(newMode.asString()));
    }

    private void updateNetworkItems() {
        Map<ItemVariant, Long> items = handler.getNetworkItems();
        networkItemsList = new ArrayList<>(items.entrySet());

        // Apply category filter FIRST
        FilterCategory currentCategory = handler.getFilterCategory();
        if (currentCategory != FilterCategory.ALL) {
            networkItemsList.removeIf(entry -> {
                return !currentCategory.matches(entry.getKey().getItem());
            });
        }

        // Then apply search filter
        if (!currentSearch.isEmpty()) {
            networkItemsList.removeIf(entry -> {
                String itemName = entry.getKey().getItem().getName().getString().toLowerCase();
                return !itemName.contains(currentSearch);
            });
        }

        // Finally, sort based on current sort mode
        SortMode sortMode = handler.getSortMode();
        switch (sortMode) {
            case NAME:
                networkItemsList.sort((a, b) -> {
                    String nameA = a.getKey().getItem().getName().getString();
                    String nameB = b.getKey().getItem().getName().getString();
                    return nameA.compareTo(nameB);
                });
                break;

            case COUNT:
                networkItemsList.sort((a, b) -> {
                    long countA = a.getValue();
                    long countB = b.getValue();
                    return Long.compare(countB, countA); // Highest count first
                });
                break;
        }

        int totalRows = (int) Math.ceil(networkItemsList.size() / (double) ITEMS_PER_ROW);
        maxScrollRows = Math.max(0, totalRows - VISIBLE_ROWS);
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        // Draw background texture
        context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);

        // Always draw scrollbar
        drawScrollbar(context, x, y);
    }

    private void drawScrollbar(DrawContext context, int guiX, int guiY) {
        int scrollbarX = guiX + SCROLLBAR_X;
        int scrollbarY = guiY + SCROLLBAR_Y;

        // track
        context.fill(scrollbarX, scrollbarY, scrollbarX + SCROLLBAR_WIDTH, scrollbarY + SCROLLBAR_HEIGHT, 0xFFC6C6C6);
        // left border
        context.fill(scrollbarX, scrollbarY, scrollbarX + 1, scrollbarY + SCROLLBAR_HEIGHT, 0xFF373737);
        // right border
        context.fill(scrollbarX + SCROLLBAR_WIDTH - 1, scrollbarY, scrollbarX + SCROLLBAR_WIDTH, scrollbarY + SCROLLBAR_HEIGHT, 0xFFFFFFFF);

        if (maxScrollRows > 0) {
            int handleHeight = 15;
            int maxHandleOffset = SCROLLBAR_HEIGHT - handleHeight;
            int handleY = scrollbarY + (int) (scrollProgress * maxHandleOffset);

            context.fill(scrollbarX + 1, handleY, scrollbarX + SCROLLBAR_WIDTH - 1, handleY + handleHeight, 0xFF8B8B8B);
            context.fill(scrollbarX + 1, handleY, scrollbarX + SCROLLBAR_WIDTH - 1, handleY + 1, 0xFFFFFFFF);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        tickCounter++;
        if (tickCounter >= 10) {
            updateNetworkItems();
            tickCounter = 0;
        }

        super.render(context, mouseX, mouseY, delta);
        renderNetworkItems(context, mouseX, mouseY);

        // Render dropdown AFTER everything else so it appears on top
        if (filterDropdown != null && filterDropdown.isOpen()) {
            filterDropdown.renderDropdown(context, mouseX, mouseY);
        }

        // drawMouseoverTooltip will draw item tooltips
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

            // Draw amount text (ARGB color)
            if (amount > 1) {
                String amountText = formatAmount(amount);
                float scale = 0.75f;

                int rawWidth = textRenderer.getWidth(amountText);
                float scaledWidth = rawWidth * scale;

                float textX = slotX + 16 - scaledWidth;
                float textY = slotY + 9;

                // Copy the current transform so we can restore it later
                Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());

                // Build and apply a scaling matrix
                Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
                context.getMatrices().mul(scaleMatrix);

                // Then translate to the desired position (adjusted for scaling)
                Matrix3x2f translateMatrix = new Matrix3x2f().translation(textX / scale, textY / scale);
                context.getMatrices().mul(translateMatrix);

                // Draw text at (0, 0) because we already transformed the matrix
                context.drawText(
                        textRenderer,
                        amountText,
                        0, 0,
                        0xFFFFFFFF,
                        true
                );

                // Restore previous transform
                context.getMatrices().set(oldMatrix);
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

    private boolean isMouseOverSlot(int slotX, int slotY, double mouseX, double mouseY) {
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

    /**
     * This is called from the Fabric allowMouseClick event.
     * Return true if we handled (consumed) the click and the vanilla handling should NOT proceed.
     */
    private boolean onMouseClickIntercept(double mouseX, double mouseY, int button) {

        // Let vanilla route clicks to the TextFieldWidget (do not consume)

        // Scrollbar
        if (needsScrollbar() && isMouseOverScrollbar(mouseX, mouseY)) {
            isScrolling = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        // Dropdown click handling
        //if (filterDropdown != null && filterDropdown.mouseClicked(mouseX, mouseY, button)) {
        //    return true;
        //}

        // Dropdown click handling
        if (filterDropdown != null) {
            boolean result = filterDropdown.mouseClicked(mouseX, mouseY, button);
            if (result) {
                return true;
            }
        }


        // GUI bounds
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        int gridStartX = guiX + GRID_START_X;
        int gridStartY = guiY + GRID_START_Y;
        int gridEndX = gridStartX + (ITEMS_PER_ROW * SLOT_SIZE);
        int gridEndY = gridStartY + (VISIBLE_ROWS * SLOT_SIZE);

        boolean clickInGrid = mouseX >= gridStartX && mouseX < gridEndX &&
                mouseY >= gridStartY && mouseY < gridEndY;

        if (clickInGrid) {
            int scrollOffset = (int) (scrollProgress * maxScrollRows);
            int startIndex = scrollOffset * ITEMS_PER_ROW;
            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, networkItemsList.size());

            for (int i = startIndex; i < endIndex; i++) {
                int relativeIndex = i - startIndex;
                int row = relativeIndex / ITEMS_PER_ROW;
                int col = relativeIndex % ITEMS_PER_ROW;

                int slotX = guiX + GRID_START_X + (col * SLOT_SIZE);
                int slotY = guiY + GRID_START_Y + (row * SLOT_SIZE);

                if (isMouseOverSlot(slotX, slotY, mouseX, mouseY)) {
                    boolean isShiftDown = isShiftDown();
                    boolean isCtrlDown = isControlDown();
                    handleNetworkSlotClick(i, button, isShiftDown, isCtrlDown);
                    return true;
                }
            }

            // Clicked empty grid area while holding cursor item -> deposit
            if (!handler.getCursorStack().isEmpty()) {
                handleEmptyAreaClick(button);
                return true;
            }
        }

        return false;
    }

    /**
     * Called from allowMouseRelease event.
     * Return true if consumed.
     */
    private boolean onMouseReleaseIntercept(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            isScrolling = false;
        }
        // Do not consume the release by default — let parent handle it unless we explicitly want to stop it.
        // If you changed behavior where release must be swallowed, return true here.
        return false;
    }

    /**
     * Called from allowMouseDrag event.
     * Return true if consumed.
     */
    private boolean onMouseDragIntercept(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (isScrolling && needsScrollbar()) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return false;
    }

    /**
     * Called from allowMouseScroll event.
     * Return true if consumed.
     */
    private boolean onMouseScrollIntercept(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (needsScrollbar()) {
            float scrollAmount = (float) (-verticalAmount / (maxScrollRows + 1));
            scrollProgress = Math.max(0, Math.min(1, scrollProgress + scrollAmount));
            return true;
        }
        return false;
    }

    private void handleNetworkSlotClick(int slotIndex, int button, boolean isShift, boolean isCtrl) {
        var entry = networkItemsList.get(slotIndex);
        ItemVariant variant = entry.getKey();
        long itemCount = entry.getValue();
        // DEBUG: LOGGER.info("[DEBUG] handleNetworkSlotClick: item={}, count={}, button={}", variant.getItem().getName().getString(), itemCount, button);

        ItemStack cursorStack = handler.getCursorStack();
        // DEBUG: LOGGER.info("[DEBUG] Cursor stack: {}", cursorStack.isEmpty() ? "empty" : cursorStack.getItem().getName().getString());

        if (!cursorStack.isEmpty()) {
            ItemVariant cursorVariant = ItemVariant.of(cursorStack);

            if (cursorVariant.equals(variant)) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) handler.requestDeposit(cursorStack, cursorStack.getCount());
                else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) handler.requestDeposit(cursorStack, 1);
            } else {
                handler.requestDeposit(cursorStack, cursorStack.getCount());
            }
            return;
        }

        int amount;
        if (isShift) {
            amount = (int) Math.min(64, itemCount);
            handler.requestExtraction(variant, amount, true);
        } else if (isCtrl && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            amount = (int) Math.min(16, Math.max(1, itemCount / 4));
            handler.requestExtraction(variant, amount, false);
        } else {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) amount = (int) Math.min(64, itemCount);
            else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) amount = (int) Math.min(32, Math.max(1, itemCount / 2));
            else amount = (int) Math.min(variant.getItem().getMaxCount(), itemCount);

            handler.requestExtraction(variant, amount, false);
        }
    }

    private void handleEmptyAreaClick(int button) {
        ItemStack cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty()) return;

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) handler.requestDeposit(cursorStack, cursorStack.getCount());
        else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) handler.requestDeposit(cursorStack, 1);
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
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Draw labels
        context.drawText(textRenderer, Text.literal("Controller"), titleX, titleY, 0xFF404040, false);
        context.drawText(textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0xFF404040, false);

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

                int textX = (int) (backgroundWidth - textWidth - 26);
                int textY = 6;
                // color already includes alpha above
                context.drawText(textRenderer, Text.literal(capacityText), textX, textY, 0xFF000000 | color, false);
            }
        }
    }

    // Input events handled by ScreenMouseEvents registrations in init()

    private boolean isControlDown() {
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private boolean isShiftDown() {
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    // Keyboard input is handled via the Screen's default routing; the search box
    // receives events because it's an Element and we focus it on click.

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        super.drawMouseoverTooltip(context, mouseX, mouseY);

        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        int scrollOffset = (int) (scrollProgress * maxScrollRows);
        int startIndex = scrollOffset * ITEMS_PER_ROW;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, networkItemsList.size());

        for (int i = startIndex; i < endIndex; i++) {
            int relativeIndex = i - startIndex;
            int row = relativeIndex / ITEMS_PER_ROW;
            int col = relativeIndex % ITEMS_PER_ROW;

            int slotX = guiX + GRID_START_X + (col * SLOT_SIZE);
            int slotY = guiY + GRID_START_Y + (row * SLOT_SIZE);

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