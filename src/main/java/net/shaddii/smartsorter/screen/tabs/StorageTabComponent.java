package net.shaddii.smartsorter.screen.tabs;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.screen.util.ItemCacheManager;
import net.shaddii.smartsorter.screen.util.ItemGridRenderer;
import net.shaddii.smartsorter.screen.util.TooltipRenderer;
import net.shaddii.smartsorter.util.*;
import net.shaddii.smartsorter.widget.DropdownWidget;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class StorageTabComponent extends TabComponent {
    // Constants
    private static final int ITEMS_PER_ROW = 9;
    private static final int VISIBLE_ROWS = 5;
    private static final int ITEMS_PER_PAGE = ITEMS_PER_ROW * VISIBLE_ROWS;
    private static final int GRID_START_X = 8;
    private static final int GRID_START_Y = 18;
    private static final int SLOT_SIZE = 18;
    private static final int SCROLLBAR_X = 174;
    private static final int SCROLLBAR_Y = 18;
    private static final int SCROLLBAR_WIDTH = 14;
    private static final int SCROLLBAR_HEIGHT = 90;
    private static final long CAPACITY_UPDATE_INTERVAL = 1000; // Update every 1 second

    // Widgets
    private TextFieldWidget searchBox;
    private ButtonWidget sortButton;
    private DropdownWidget filterDropdown;

    // State
    private String currentSearch = "";
    private float scrollProgress = 0.0f;
    private boolean isScrolling = false;
    private int maxScrollRows = 0;

    // Cache
    private final ItemCacheManager cacheManager;
    private final ItemGridRenderer gridRenderer;
    private final TooltipRenderer tooltipRenderer;

    // Network items
    private List<Map.Entry<ItemVariant, Long>> networkItemsList = new ArrayList<>();

    // Cached capacity display
    private String cachedCapacityText = "";
    private int cachedCapacityColor = 0xFFFFFFFF;
    private long lastCapacityCheck = 0;

    public StorageTabComponent(StorageControllerScreen parent, StorageControllerScreenHandler handler) {
        super(parent, handler);
        this.cacheManager = new ItemCacheManager();
        this.gridRenderer = new ItemGridRenderer(parent.getTextRenderer());
        this.tooltipRenderer = new TooltipRenderer(parent.getTextRenderer());
    }

    @Override
    protected void initWidgets() {
        // Search box
        searchBox = new TextFieldWidget(parent.getTextRenderer(), guiX + 82, guiY + 6, 90, 13, Text.literal(""));
        searchBox.setDrawsBackground(false);
        searchBox.setChangedListener(this::onSearchChanged);
        parent.addWidget(searchBox);

        // Sort button
        sortButton = ButtonWidget.builder(
                Text.literal(handler.getSortMode().getDisplayName()),
                btn -> cycleSortMode()
        ).dimensions(guiX + 82, guiY + 6 + 13 - 34, 30, 12).build();
        parent.addWidget(sortButton);

        // Filter dropdown
        filterDropdown = new DropdownWidget(guiX + 82 + 30 + 2, guiY + 6 + 13 - 34, 60, 12, Text.literal(""));
        initFilterDropdown();
        parent.addWidget(filterDropdown);

        updateNetworkItems();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderTitle(context);
        renderCapacity(context);
        renderNetworkItems(context, mouseX, mouseY);
        drawScrollbar(context);

        if (filterDropdown != null && filterDropdown.isOpen()) {
            //? if >=1.21.8 {
            filterDropdown.renderDropdown(context, mouseX, mouseY);
            //?} else {
            /*context.getMatrices().push();
            context.getMatrices().translate(0, 0, 300);
            filterDropdown.renderDropdown(context, mouseX, mouseY);
            context.getMatrices().pop();
            *///?}
        }
    }

    private void renderTitle(DrawContext context) {
        context.drawText(parent.getTextRenderer(), Text.literal("Controller"), guiX + 7, guiY + 6, 0xFF404040, false);
        context.drawText(parent.getTextRenderer(), Text.literal("Inventory"), guiX + 8, guiY + 109, 0xFF404040, false);
    }

    private void renderCapacity(DrawContext context) {
        if (handler.controller == null) return;

        // Only recalculate capacity periodically
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCapacityCheck > CAPACITY_UPDATE_INTERVAL) {
            updateCapacityCache();
            lastCapacityCheck = currentTime;
        }

        // Render cached values
        if (!cachedCapacityText.isEmpty()) {
            int textWidth = parent.getTextRenderer().getWidth(cachedCapacityText);
            int textX = guiX + backgroundWidth - textWidth - 26;
            int textY = guiY + 6;

            context.drawText(parent.getTextRenderer(), Text.literal(cachedCapacityText),
                    textX, textY, 0xFF000000 | cachedCapacityColor, false);
        }
    }

    private void updateCapacityCache() {
        int free = handler.controller.calculateTotalFreeSlots();
        int total = handler.controller.calculateTotalCapacity();

        if (total > 0) {
            float percentFree = (free / (float) total) * 100;

            if (percentFree > 50) cachedCapacityColor = 0x55FF55;
            else if (percentFree > 25) cachedCapacityColor = 0xFFFF55;
            else if (percentFree > 10) cachedCapacityColor = 0xFFAA00;
            else cachedCapacityColor = 0xFF5555;

            cachedCapacityText = free + "/" + total;
        }
    }

    private void renderNetworkItems(DrawContext context, int mouseX, int mouseY) {
        int scrollOffset = (int) (scrollProgress * maxScrollRows);
        int startIndex = scrollOffset * ITEMS_PER_ROW;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, networkItemsList.size());

        gridRenderer.renderItems(context, networkItemsList, startIndex, endIndex,
                guiX + GRID_START_X, guiY + GRID_START_Y,
                ITEMS_PER_ROW, SLOT_SIZE, mouseX, mouseY);
    }

    public boolean isSearchFieldFocused() {
        return searchBox != null && searchBox.isFocused();
    }

    private void drawScrollbar(DrawContext context) {
        int scrollbarX = guiX + SCROLLBAR_X;
        int scrollbarY = guiY + SCROLLBAR_Y;

        gridRenderer.renderScrollbar(context, scrollbarX, scrollbarY,
                SCROLLBAR_WIDTH, SCROLLBAR_HEIGHT,
                scrollProgress, maxScrollRows > 0);
    }

    public void renderTooltip(DrawContext context, int mouseX, int mouseY) {
        if (filterDropdown != null && filterDropdown.isOpen() && filterDropdown.isMouseOver(mouseX, mouseY)) {
            return;
        }

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
                List<Text> tooltip = tooltipRenderer.createItemTooltip(entry.getKey(), entry.getValue());
                context.drawTooltip(parent.getTextRenderer(), tooltip, mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle filter dropdown
        if (filterDropdown != null) {
            if (filterDropdown.isMouseOver(mouseX, mouseY)) {
                filterDropdown.mouseClicked(mouseX, mouseY, button);
                return true;
            } else if (filterDropdown.isOpen()) {
                filterDropdown.close();
                return true;
            }
        }

        // Handle search box
        if (searchBox != null) {
            int sx = searchBox.getX();
            int sy = searchBox.getY();
            if (mouseX >= sx && mouseX < sx + searchBox.getWidth() &&
                    mouseY >= sy && mouseY < sy + searchBox.getHeight()) {
                parent.setWidgetFocused(searchBox);
                searchBox.setFocused(true);
                return true;
            }
        }

        // Handle scrollbar
        if (needsScrollbar() && isMouseOverScrollbar(mouseX, mouseY)) {
            isScrolling = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        // Handle item grid clicks
        return handleGridClick(mouseX, mouseY, button);
    }

    private boolean handleGridClick(double mouseX, double mouseY, int button) {
        int gridStartX = guiX + GRID_START_X;
        int gridStartY = guiY + GRID_START_Y;
        int gridEndX = gridStartX + (ITEMS_PER_ROW * SLOT_SIZE);
        int gridEndY = gridStartY + (VISIBLE_ROWS * SLOT_SIZE);

        if (mouseX >= gridStartX && mouseX < gridEndX && mouseY >= gridStartY && mouseY < gridEndY) {
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
                    handleNetworkSlotClick(i, button, isShiftDown(), isControlDown());
                    return true;
                }
            }

            if (!handler.getCursorStack().isEmpty()) {
                handleEmptyAreaClick(button);
                return true;
            }
        }

        return false;
    }

    private void handleNetworkSlotClick(int slotIndex, int button, boolean isShift, boolean isCtrl) {
        var entry = networkItemsList.get(slotIndex);
        ItemVariant variant = entry.getKey();
        long itemCount = entry.getValue();

        ItemStack cursorStack = handler.getCursorStack();

        if (!cursorStack.isEmpty()) {
            ItemVariant cursorVariant = ItemVariant.of(cursorStack);

            if (cursorVariant.equals(variant)) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    handler.requestDeposit(cursorStack, cursorStack.getCount());
                    handler.setCursorStack(ItemStack.EMPTY);
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    handler.requestDeposit(cursorStack, 1);
                    cursorStack.decrement(1);
                    handler.setCursorStack(cursorStack.isEmpty() ? ItemStack.EMPTY : cursorStack);
                }
            } else {
                handler.requestDeposit(cursorStack, cursorStack.getCount());
                handler.setCursorStack(ItemStack.EMPTY);
            }
            return;
        }

        int amount;
        if (isShift) {
            amount = (int) Math.min(64, itemCount);
        } else if (isCtrl && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            amount = (int) Math.min(16, Math.max(1, itemCount / 4));
        } else {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                amount = (int) Math.min(64, itemCount);
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                amount = (int) Math.min(32, Math.max(1, itemCount / 2));
            } else {
                amount = (int) Math.min(variant.getItem().getMaxCount(), itemCount);
            }
        }

        if (!isShift) {
            ItemStack extracted = variant.toStack(amount);
            handler.setCursorStack(extracted);
        }

        handler.requestExtraction(variant, amount, isShift);
    }

    private void handleEmptyAreaClick(int button) {
        ItemStack cursorStack = handler.getCursorStack();
        if (cursorStack.isEmpty()) return;

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            handler.requestDeposit(cursorStack, cursorStack.getCount());
            handler.setCursorStack(ItemStack.EMPTY);
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            handler.requestDeposit(cursorStack, 1);
            cursorStack.decrement(1);
            handler.setCursorStack(cursorStack.isEmpty() ? ItemStack.EMPTY : cursorStack);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            isScrolling = false;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (isScrolling && needsScrollbar()) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (filterDropdown != null && filterDropdown.isOpen()) {
            if (filterDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        if (needsScrollbar()) {
            float scrollAmount = (float) (-verticalAmount / (maxScrollRows + 1));
            scrollProgress = Math.max(0, Math.min(1, scrollProgress + scrollAmount));
            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                searchBox.setFocused(false);
                return true;
            }
            return handleKeyPress(searchBox, keyCode, scanCode, modifiers);
        }

        if (searchBox != null && !searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_F && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                searchBox.setFocused(true);
                parent.setWidgetFocused(searchBox);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_SLASH) {
                searchBox.setFocused(true);
                parent.setWidgetFocused(searchBox);
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            return handleCharType(searchBox, chr, modifiers);
        }
        return false;
    }

    @Override
    public void markDirty() {
        cacheManager.invalidate();
        updateNetworkItems();
    }

    @Override
    public void onClose() {
        // Clean up caches
        cacheManager.clearAll();
        tooltipRenderer.invalidateCache();
    }

    public void updateNetworkItems() {
        Map<ItemVariant, Long> items = handler.getNetworkItems();
        SortMode sortMode = handler.getSortMode();
        Category category = handler.getFilterCategory();

        networkItemsList = cacheManager.getFilteredItems(items, currentSearch, sortMode, category);

        int totalRows = (int) Math.ceil(networkItemsList.size() / (double) ITEMS_PER_ROW);
        maxScrollRows = Math.max(0, totalRows - VISIBLE_ROWS);
    }

    private void onSearchChanged(String searchText) {
        currentSearch = searchText.toLowerCase();
        markDirty();
        scrollProgress = 0;
    }

    private void cycleSortMode() {
        SortMode currentMode = handler.getSortMode();
        SortMode newMode = currentMode.next();

        sortButton.setMessage(Text.literal(newMode.getDisplayName()));
        handler.setSortMode(newMode);

        markDirty();

        ClientPlayNetworking.send(new SortModeChangePayload(newMode.asString()));
    }

    private void initFilterDropdown() {
        List<Category> allCategories = CategoryManager.getInstance().getAllCategories();
        if (allCategories.isEmpty()) {
            allCategories = List.of(Category.ALL, Category.MISC);
        }

        final List<Category> categoryList = new ArrayList<>(allCategories);

        for (Category category : categoryList) {
            filterDropdown.addEntry(category.getShortName(), category.getDisplayName());
        }

        Category currentCategory = handler.getFilterCategory();
        int selectedIndex = 0;
        for (int i = 0; i < categoryList.size(); i++) {
            if (categoryList.get(i).getId().equals(currentCategory.getId())) {
                selectedIndex = i;
                break;
            }
        }
        filterDropdown.setSelectedIndex(selectedIndex);

        filterDropdown.setOnSelect(index -> {
            if (index >= 0 && index < categoryList.size()) {
                Category selected = categoryList.get(index);
                handler.setFilterCategory(selected);
                markDirty();
                ClientPlayNetworking.send(new FilterCategoryChangePayload(selected.asString()));
            }
        });

        filterDropdown.active = true;
        filterDropdown.visible = true;
    }

    // Utility methods
    private boolean isMouseOverSlot(int slotX, int slotY, double mouseX, double mouseY) {
        return mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16;
    }

    private boolean needsScrollbar() {
        return maxScrollRows > 0;
    }

    private boolean isMouseOverScrollbar(double mouseX, double mouseY) {
        int barX = guiX + SCROLLBAR_X;
        int barY = guiY + SCROLLBAR_Y;
        return mouseX >= barX && mouseX < barX + SCROLLBAR_WIDTH &&
                mouseY >= barY && mouseY < barY + SCROLLBAR_HEIGHT;
    }

    private void updateScrollFromMouse(double mouseY) {
        int barY = guiY + SCROLLBAR_Y;
        int handleHeight = 15;
        int maxHandleOffset = SCROLLBAR_HEIGHT - handleHeight;

        float relativeY = (float) (mouseY - barY - (handleHeight / 2.0));
        scrollProgress = Math.max(0, Math.min(1, relativeY / maxHandleOffset));
    }

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
}