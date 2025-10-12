package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.shaddii.smartsorter.network.CollectXpPayload;
import net.shaddii.smartsorter.network.FilterCategoryChangePayload;
import net.shaddii.smartsorter.network.SortModeChangePayload;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.SortMode;
import net.shaddii.smartsorter.widget.DropdownWidget;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.shaddii.smartsorter.widget.ProbeConfigPanel;
import net.shaddii.smartsorter.widget.ProbeSelectorWidget;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Storage Controller Screen for SmartSorter
 * Updated for Minecraft 1.21.10 with Tabs and Performance Optimizations
 */
public class StorageControllerScreen extends HandledScreen<StorageControllerScreenHandler> {
    private static final Identifier TEXTURE = Identifier.of(SmartSorter.MOD_ID, "textures/gui/storage_controller.png");

    // Tab system
    private enum Tab {
        STORAGE("Storage"),
        AUTO_PROCESSING("Auto-Processing");

        private final String name;

        Tab(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private Tab currentTab = Tab.STORAGE;
    private List<ButtonWidget> tabButtons = new ArrayList<>();

    // Auto-processing widgets
    private ProbeSelectorWidget probeSelector;
    private ProbeConfigPanel configPanel;

    // XP collection
    private int lastCollectedXp = 0;
    private long lastCollectionTime = 0;

    // Scrolling
    private float scrollProgress = 0.0f;
    private boolean isScrolling = false;

    private static final int ITEMS_PER_ROW = 9;
    private static final int VISIBLE_ROWS = 5;
    private static final int ITEMS_PER_PAGE = ITEMS_PER_ROW * VISIBLE_ROWS;

    // Network grid
    private static final int GRID_START_X = 8;
    private static final int GRID_START_Y = 18;
    private static final int SLOT_SIZE = 18;

    // Scrollbar
    private static final int SCROLLBAR_X = 174;
    private static final int SCROLLBAR_Y = 18;
    private static final int SCROLLBAR_WIDTH = 14;
    private static final int SCROLLBAR_HEIGHT = 90;

    // Cached network items
    private List<Map.Entry<ItemVariant, Long>> networkItemsList = new ArrayList<>();
    private int maxScrollRows = 0;

    // Dirty flag
    private boolean needsRefresh = true;

    // Search widget
    private TextFieldWidget searchBox;
    private String currentSearch = "";

    // Sort button
    private ButtonWidget sortButton;

    // Filter Categories
    private DropdownWidget filterDropdown;

    public StorageControllerScreen(StorageControllerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);

        this.backgroundWidth = 194;
        this.backgroundHeight = 202;

        this.titleX = 7;
        this.titleY = 6;
        this.playerInventoryTitleX = 8;
        this.playerInventoryTitleY = 109;
    }

    @Override
    protected void init() {
        super.init();

        // Initialize tab buttons FIRST
        initTabButtons();

        // Initialize widgets based on current tab
        if (currentTab == Tab.STORAGE) {
            initStorageWidgets();
        } else {
            initAutoProcessingWidgets();
        }

        registerMouseEvents();

        // ✅ Request sync and update AFTER widgets are ready
        handler.requestSync();
        updateNetworkItems();
    }

    private void initTabButtons() {
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        int tabX = guiX - 60;
        int tabY = guiY + 10;
        int tabWidth = 58;
        int tabHeight = 22;
        int tabSpacing = 4;

        tabButtons.clear();

        ButtonWidget storageTab = ButtonWidget.builder(
                Text.literal("Items"),
                btn -> switchTab(Tab.STORAGE)
        ).dimensions(tabX, tabY, tabWidth, tabHeight).build();

        ButtonWidget processingTab = ButtonWidget.builder(
                Text.literal("Config"),
                btn -> switchTab(Tab.AUTO_PROCESSING)
        ).dimensions(tabX, tabY + tabHeight + tabSpacing, tabWidth, tabHeight).build();

        tabButtons.add(storageTab);
        tabButtons.add(processingTab);

        addDrawableChild(storageTab);
        addDrawableChild(processingTab);
    }

    private void switchTab(Tab newTab) {
        if (currentTab == newTab) return;

        currentTab = newTab;

        // Clear widgets (except tabs)
        clearChildren();

        searchBox = null;
        sortButton = null;
        filterDropdown = null;
        probeSelector = null;
        configPanel = null;

        // Re-add tab buttons
        for (ButtonWidget btn : tabButtons) {
            addDrawableChild(btn);
        }

        // Initialize widgets for new tab
        if (currentTab == Tab.STORAGE) {
            initStorageWidgets();
        } else {
            initAutoProcessingWidgets();
        }

        registerMouseEvents();
    }

    private void initStorageWidgets() {
        int searchBoxWidth = 90;
        int searchBoxHeight = 13;
        int searchBoxX = (width - backgroundWidth) / 2 + 82;
        int searchBoxY = (height - backgroundHeight) / 2 + 6;

        searchBox = new TextFieldWidget(this.textRenderer, searchBoxX, searchBoxY, searchBoxWidth, searchBoxHeight, Text.literal(""));
        searchBox.setDrawsBackground(false);
        searchBox.setChangedListener(this::onSearchChanged);
        addDrawableChild(searchBox);

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

        int filterDropdownX = sortButtonX + sortButtonWidth + 2;
        int filterDropdownY = sortButtonY;
        int filterDropdownWidth = 60;
        int filterDropdownHeight = 12;

        filterDropdown = new DropdownWidget(
                filterDropdownX, filterDropdownY,
                filterDropdownWidth, filterDropdownHeight,
                Text.literal("")
        );

        // Get categories dynamically from CategoryManager
        List<Category> allCategories = CategoryManager.getInstance().getAllCategories();

        // Build a list to track which category each index corresponds to
        final List<Category> categoryList = new ArrayList<>();

        for (Category category : allCategories) {
            categoryList.add(category);
            filterDropdown.addEntry(category.getShortName(), category.getDisplayName());
        }

        // Find the index of the current category
        Category currentCategory = handler.getFilterCategory();
        int selectedIndex = 0;
        for (int i = 0; i < categoryList.size(); i++) {
            if (categoryList.get(i).getId().equals(currentCategory.getId())) {
                selectedIndex = i;
                break;
            }
        }
        filterDropdown.setSelectedIndex(selectedIndex);

        // Use category from list when selected
        filterDropdown.setOnSelect(index -> {
            if (index >= 0 && index < categoryList.size()) {
                Category selected = categoryList.get(index);
                handler.setFilterCategory(selected);
                needsRefresh = true;
                ClientPlayNetworking.send(new FilterCategoryChangePayload(selected.asString()));
            }
        });

        addDrawableChild(filterDropdown);

        filterDropdown.active = true;
        filterDropdown.visible = true;
    }

    private void initAutoProcessingWidgets() {
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        probeSelector = new ProbeSelectorWidget(
                guiX + 8, guiY + 18,
                backgroundWidth - 16, 10,
                textRenderer
        );

        probeSelector.updateProbes(handler.getProcessProbeConfigs());

        probeSelector.setOnSelectionChange(config -> {
            if (configPanel != null) {
                configPanel.setConfig(config);
            }
        });

        probeSelector.setOnConfigUpdate(config -> {
            needsRefresh = true;
        });

        configPanel = new ProbeConfigPanel(
                guiX + 8, guiY + 30,
                backgroundWidth - 16, 75,
                textRenderer
        );

        ProcessProbeConfig selected = probeSelector.getSelectedProbe();
        configPanel.setConfig(selected);

        configPanel.setOnConfigUpdate(config -> {
            needsRefresh = true;
        });
    }

    private void registerMouseEvents() {
        ScreenMouseEvents.allowMouseClick(this).register((screen, click) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            if (gui.filterDropdown != null && gui.filterDropdown.isOpen()) {
                if (gui.filterDropdown.isMouseOver(click.x(), click.y())) {
                    boolean handled = gui.filterDropdown.mouseClicked(click.x(), click.y(), click.button());
                    return !handled;
                } else {
                    gui.filterDropdown.close();
                    return true;
                }
            }

            // ✅ FIX: Only check searchBox on STORAGE tab
            if (gui.currentTab == Tab.STORAGE && gui.searchBox != null) {
                int sx = gui.searchBox.getX();
                int sy = gui.searchBox.getY();
                int sw = gui.searchBox.getWidth();
                int sh = gui.searchBox.getHeight();
                if (click.x() >= sx && click.x() < sx + sw && click.y() >= sy && click.y() < sy + sh) {
                    gui.setFocused(gui.searchBox);
                    gui.searchBox.setFocused(true);
                    return true;
                }
            }

            boolean consumed = gui.onMouseClickIntercept(click.x(), click.y(), click.button());
            return !consumed;
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

            if (gui.filterDropdown != null && gui.filterDropdown.isOpen()) {
                boolean handled = gui.filterDropdown.mouseScrolled(mouseX, mouseY, horizontal, vertical);
                if (handled) {
                    return false;
                }
            }

            boolean consumed = gui.onMouseScrollIntercept(mouseX, mouseY, horizontal, vertical);
            return !consumed;
        });
    }

    private void onSearchChanged(String searchText) {
        currentSearch = searchText.toLowerCase();
        needsRefresh = true;
        scrollProgress = 0;
    }

    private void cycleSortMode() {
        SortMode currentMode = handler.getSortMode();
        SortMode newMode = currentMode.next();

        sortButton.setMessage(Text.literal(newMode.getDisplayName()));
        handler.setSortMode(newMode);

        needsRefresh = true;

        ClientPlayNetworking.send(new SortModeChangePayload(newMode.asString()));
    }

    public void updateNetworkItems() {
        Map<ItemVariant, Long> items = handler.getNetworkItems();
        networkItemsList = new ArrayList<>(items.entrySet());

        // Use Category instead of FilterCategory
        Category currentCategory = handler.getFilterCategory();
        if (currentCategory != Category.ALL) {
            networkItemsList.removeIf(entry -> {
                // Use CategoryManager to categorize items
                Category itemCategory = CategoryManager.getInstance().categorize(entry.getKey().getItem());
                return !itemCategory.equals(currentCategory);
            });
        }

        if (!currentSearch.isEmpty()) {
            networkItemsList.removeIf(entry -> {
                String itemName = entry.getKey().getItem().getName().getString().toLowerCase();
                return !itemName.contains(currentSearch);
            });
        }

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
                    return Long.compare(countB, countA);
                });
                break;
        }

        int totalRows = (int) Math.ceil(networkItemsList.size() / (double) ITEMS_PER_ROW);
        maxScrollRows = Math.max(0, totalRows - VISIBLE_ROWS);
    }

    // Only set flag, never call updateNetworkItems() directly
    public void markDirty() {
        needsRefresh = true;
    }

    private void collectXp() {
        int xp = handler.getStoredExperience();
        if (xp > 0) {
            lastCollectedXp = xp;
            lastCollectionTime = System.currentTimeMillis();
            ClientPlayNetworking.send(new CollectXpPayload());
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);

        // ✅ Draw scrollbar only on STORAGE tab
        if (currentTab == Tab.STORAGE) {
            drawScrollbar(context, x, y);
        }

        // ✅ If you need a background for AUTO_PROCESSING, draw it here BEFORE slots are drawn
        if (currentTab == Tab.AUTO_PROCESSING) {
            // Draw any custom background elements for auto-processing tab
            // But DON'T cover the player inventory area (y + 109 and below)
        }
    }

    private void drawScrollbar(DrawContext context, int guiX, int guiY) {
        int scrollbarX = guiX + SCROLLBAR_X;
        int scrollbarY = guiY + SCROLLBAR_Y;

        context.fill(scrollbarX, scrollbarY, scrollbarX + SCROLLBAR_WIDTH, scrollbarY + SCROLLBAR_HEIGHT, 0xFFC6C6C6);
        context.fill(scrollbarX, scrollbarY, scrollbarX + 1, scrollbarY + SCROLLBAR_HEIGHT, 0xFF373737);
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
        // ✅ Update BEFORE rendering
        if (needsRefresh) {
            updateNetworkItems();
            needsRefresh = false;
        }

        renderBackground(context, mouseX, mouseY, delta);

        // ✅ Draw base GUI elements (slots, titles, etc.)
        super.render(context, mouseX, mouseY, delta);

        // ✅ Tab-specific rendering AFTER base elements
        if (currentTab == Tab.STORAGE) {
            renderNetworkItems(context, mouseX, mouseY);

            if (filterDropdown != null && filterDropdown.isOpen()) {
                filterDropdown.renderDropdown(context, mouseX, mouseY);
            }
        } else {
            // ✅ Don't cover base elements - only add on top
            renderXpDisplay(context, mouseX, mouseY);
            renderAutoProcessingTab(context, mouseX, mouseY, delta);

            if (probeSelector != null) {
                probeSelector.renderDropdownIfOpen(context, mouseX, mouseY);
            }
        }

        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    private void renderAutoProcessingTab(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        context.drawText(textRenderer, "Auto-Processing", x + 8, y + 6, 0x404040, false);

        if (probeSelector != null) {
            probeSelector.render(context, mouseX, mouseY, delta);
        }

        if (configPanel != null) {
            configPanel.render(context, mouseX, mouseY, delta);
        }
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

            context.fill(slotX, slotY, slotX + 16, slotY + 16, 0x8B8B8B8B);
            context.drawItem(variant.toStack(), slotX, slotY);

            if (amount > 1) {
                String amountText = formatAmount(amount);
                float scale = 0.75f;

                int rawWidth = textRenderer.getWidth(amountText);
                float scaledWidth = rawWidth * scale;

                float textX = slotX + 16 - scaledWidth;
                float textY = slotY + 9;

                // ✅ Use push/pop instead of manual save/restore
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(textX, textY);
                context.getMatrices().scale(scale, scale);

                context.drawText(textRenderer, amountText, 0, 0, 0xFFFFFFFF, true);

                context.getMatrices().popMatrix(); // ✅ Guaranteed to restore correctly
            }

            if (isMouseOverSlot(slotX, slotY, mouseX, mouseY)) {
                context.fill(slotX, slotY, slotX + 16, slotY + 16, 0x80FFFFFF);
            }
        }
    }

    private void renderCleanBackground(DrawContext context) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        int gridX = x + GRID_START_X - 2;
        int gridY = y + GRID_START_Y - 2;
        int gridWidth = (ITEMS_PER_ROW * SLOT_SIZE) + 4;
        int gridHeight = (VISIBLE_ROWS * SLOT_SIZE) + 2;

        context.fill(gridX, gridY, gridX + gridWidth, gridY + gridHeight, 0xFFC6C6C6);
        context.fill(gridX, gridY, gridX + gridWidth, gridY + 1, 0xFF8B8B8B);
        context.fill(gridX, gridY, gridX + 1, gridY + gridHeight, 0xFF8B8B8B);
        context.fill(gridX, gridY + gridHeight - 1, gridX + gridWidth, gridY + gridHeight, 0xFFFFFFFF);
        context.fill(gridX + gridWidth - 1, gridY, gridX + gridWidth, gridY + gridHeight, 0xFFFFFFFF);
    }

    private void renderXpDisplay(DrawContext context, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        int xp = handler.getStoredExperience();

        // Collection animation
        long timeSinceCollection = System.currentTimeMillis() - lastCollectionTime;
        if (timeSinceCollection < 2000 && lastCollectedXp > 0) {
            float alpha = 1.0f - (timeSinceCollection / 2000.0f);
            int yOffset = (int) (timeSinceCollection / 20);

            String collectedText = "+" + lastCollectedXp + " XP!";
            float scale = 0.7f;
            int scaledWidth = (int)(textRenderer.getWidth(collectedText) * scale);
            int collectedX = x + backgroundWidth / 2 - scaledWidth / 2;
            int collectedY = y + 50 - yOffset;

            int color = (int) (alpha * 255) << 24 | 0x55FF55;

            Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
            Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
            context.getMatrices().mul(scaleMatrix);

            Matrix3x2f translateMatrix = new Matrix3x2f().translation(collectedX / scale, collectedY / scale);
            context.getMatrices().mul(translateMatrix);

            context.drawText(textRenderer, Text.literal(collectedText), 0, 0, color, true);
            context.getMatrices().set(oldMatrix);
        }

        // XP display
        float textScale = 0.7f;
        String xpText = "XP: " + xp;
        int xpTextWidth = (int)(textRenderer.getWidth(xpText) * textScale);

        int xpX = x + backgroundWidth - 85;
        int xpY = y + 6;

        // Background box
        context.fill(xpX - 2, xpY - 1, xpX + xpTextWidth + 32, xpY + 10, 0xAA000000);
        context.fill(xpX - 3, xpY - 2, xpX + xpTextWidth + 33, xpY - 1, 0xFFFFFFFF);
        context.fill(xpX - 3, xpY + 10, xpX + xpTextWidth + 33, xpY + 11, 0xFF888888);

        // XP text
        {
            Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
            Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(textScale, textScale);
            context.getMatrices().mul(scaleMatrix);

            Matrix3x2f translateMatrix = new Matrix3x2f().translation(xpX / textScale, (xpY + 1) / textScale);
            context.getMatrices().mul(translateMatrix);

            context.drawText(textRenderer, Text.literal(xpText), 0, 0, 0xFFFFFF00, true);
            context.getMatrices().set(oldMatrix);
        }

        // Collect button
        int btnX = xpX + xpTextWidth + 3;
        int btnY = xpY;
        int btnWidth = 28;
        int btnHeight = 9;

        boolean hovered = mouseX >= btnX && mouseX < btnX + btnWidth
                && mouseY >= btnY && mouseY < btnY + btnHeight;

        boolean justClicked = timeSinceCollection < 200;
        int btnBg = xp > 0 ? (justClicked ? 0xFFFFFF55 : (hovered ? 0xFF55FF55 : 0xFF00AA00)) : 0xFF444444;
        int textColor = xp > 0 ? 0xFFFFFFFF : 0xFF888888;

        // Draw button
        context.fill(btnX, btnY, btnX + btnWidth, btnY + btnHeight, btnBg);
        context.fill(btnX, btnY, btnX + btnWidth, btnY + 1, 0xFFFFFFFF);
        context.fill(btnX, btnY + btnHeight - 1, btnX + btnWidth, btnY + btnHeight, 0xFF888888);
        context.fill(btnX, btnY, btnX + 1, btnY + btnHeight, 0xFFFFFFFF);
        context.fill(btnX + btnWidth - 1, btnY, btnX + btnWidth, btnY + btnHeight, 0xFF888888);

        // Button text
        float btnTextScale = 0.65f;
        {
            Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
            Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(btnTextScale, btnTextScale);
            context.getMatrices().mul(scaleMatrix);

            Matrix3x2f translateMatrix = new Matrix3x2f().translation((btnX + 3) / btnTextScale, (btnY + 2) / btnTextScale);
            context.getMatrices().mul(translateMatrix);

            context.drawText(textRenderer, Text.literal("Collect"), 0, 0, textColor, true);
            context.getMatrices().set(oldMatrix);
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

    private boolean onMouseClickIntercept(double mouseX, double mouseY, int button) {
        // XP button (AUTO_PROCESSING tab only)
        if (currentTab == Tab.AUTO_PROCESSING && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int x = (width - backgroundWidth) / 2;
            int y = (height - backgroundHeight) / 2;

            float textScale = 0.7f;
            String xpText = "XP: " + handler.getStoredExperience();
            int xpTextWidth = (int)(textRenderer.getWidth(xpText) * textScale);

            int xpX = x + backgroundWidth - 85;
            int btnX = xpX + xpTextWidth + 3;
            int btnY = y + 6;
            int btnWidth = 28;
            int btnHeight = 9;

            if (mouseX >= btnX && mouseX <= btnX + btnWidth
                    && mouseY >= btnY && mouseY <= btnY + btnHeight) {
                collectXp();
                return true;
            }
        }

        // Auto-processing widgets
        if (currentTab == Tab.AUTO_PROCESSING) {
            if (probeSelector != null && probeSelector.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (configPanel != null && configPanel.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            return false;
        }

        // Storage tab only from here
        if (currentTab != Tab.STORAGE) {
            return false;
        }

        if (needsScrollbar() && isMouseOverScrollbar(mouseX, mouseY)) {
            isScrolling = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        if (filterDropdown != null) {
            boolean result = filterDropdown.mouseClicked(mouseX, mouseY, button);
            if (result) {
                return true;
            }
        }

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

            if (!handler.getCursorStack().isEmpty()) {
                handleEmptyAreaClick(button);
                return true;
            }
        }

        return false;
    }

    private boolean onMouseReleaseIntercept(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            isScrolling = false;
        }
        return false;
    }

    private boolean onMouseDragIntercept(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (isScrolling && needsScrollbar()) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return false;
    }

    private boolean onMouseScrollIntercept(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Config panel scrolling (AUTO_PROCESSING tab)
        if (currentTab == Tab.AUTO_PROCESSING && configPanel != null) {
            if (configPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        // Storage grid scrolling
        if (currentTab == Tab.STORAGE && needsScrollbar()) {
            float scrollAmount = (float) (-verticalAmount / (maxScrollRows + 1));
            scrollProgress = Math.max(0, Math.min(1, scrollProgress + scrollAmount));
            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        // Probe selector (AUTO_PROCESSING tab)
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.keyPressed(input.key(), 0, input.modifiers())) {
                return true;
            }
        }

        // Search box (STORAGE tab only)
        if (currentTab == Tab.STORAGE && searchBox != null && searchBox.isFocused()) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setFocused(false);
                return true;
            }

            if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
                searchBox.setFocused(false);
                return true;
            }

            if (searchBox.keyPressed(input)) {
                return true;
            }

            return true;
        }

        // Search shortcuts (STORAGE tab only)
        if (currentTab == Tab.STORAGE && searchBox != null && !searchBox.isFocused()) {
            if (input.key() == GLFW.GLFW_KEY_F && (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
                searchBox.setFocused(true);
                setFocused(searchBox);
                return true;
            }

            if (input.key() == GLFW.GLFW_KEY_SLASH) {
                searchBox.setFocused(true);
                setFocused(searchBox);
                return true;
            }
        }

        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        // Probe selector typing (AUTO_PROCESSING tab)
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.charTyped((char) input.codepoint(), input.modifiers())) {
                return true;
            }
        }

        // Search box typing (STORAGE tab)
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.charTyped(input);
        }

        return super.charTyped(input);
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.keyReleased(input);
        }

        return super.keyReleased(input);
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
                    if (cursorStack.isEmpty()) {
                        handler.setCursorStack(ItemStack.EMPTY);
                    } else {
                        handler.setCursorStack(cursorStack);
                    }
                }
            } else {
                handler.requestDeposit(cursorStack, cursorStack.getCount());
                handler.setCursorStack(ItemStack.EMPTY);
            }
            return;
        }

        // Calculate amount
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

        // Optimistic client-side cursor update
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
            if (cursorStack.isEmpty()) {
                handler.setCursorStack(ItemStack.EMPTY);
            } else {
                handler.setCursorStack(cursorStack);
            }
        }
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
        // ✅ Only draw titles on STORAGE tab (avoid overlap with auto-processing widgets)
        if (currentTab == Tab.STORAGE) {
            context.drawText(textRenderer, Text.literal("Controller"), titleX, titleY, 0x404040, false);
            context.drawText(textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);

            if (handler.controller != null) {
                int free = handler.controller.calculateTotalFreeSlots();
                int total = handler.controller.calculateTotalCapacity();

                if (total > 0) {
                    float percentFree = (free / (float) total) * 100;

                    int color;
                    if (percentFree > 50) color = 0x55FF55;
                    else if (percentFree > 25) color = 0xFFFF55;
                    else if (percentFree > 10) color = 0xFFAA00;
                    else color = 0xFF5555;

                    String capacityText = free + "/" + total;
                    int textWidth = textRenderer.getWidth(capacityText);
                    int textX = backgroundWidth - textWidth - 26;
                    int textY = 6;

                    context.drawText(textRenderer, Text.literal(capacityText), textX, textY, 0xFF000000 | color, false);
                }
            }
        } else {
            // AUTO_PROCESSING tab - only draw player inventory title
            context.drawText(textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0x404040, false);
        }
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

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        super.drawMouseoverTooltip(context, mouseX, mouseY);

        // Only show item tooltips on Storage tab
        if (currentTab != Tab.STORAGE) {
            return;
        }

        if (this.focusedSlot != null && this.focusedSlot.hasStack()) {
            return; // Don't overlap with vanilla slot tooltips
        }

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

                context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
                break;
            }
        }
    }
}