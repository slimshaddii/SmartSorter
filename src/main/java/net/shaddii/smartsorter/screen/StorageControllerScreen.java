package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
//? if >= 1.21.9 {
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
//?}
//? if >= 1.21.8 {
import net.minecraft.client.gl.RenderPipelines;
//?}
//? if <= 1.21.8 {
/*import net.minecraft.client.util.math.MatrixStack;
*///?}
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.CollectXpPayload;
import net.shaddii.smartsorter.network.FilterCategoryChangePayload;
import net.shaddii.smartsorter.network.SortModeChangePayload;
import net.shaddii.smartsorter.network.SortChestsPayload;
import net.shaddii.smartsorter.util.*;
import net.shaddii.smartsorter.widget.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Storage Controller Screen for SmartSorter
 * Multi-version support (1.21.8 - 1.21.10+)
 */
public class StorageControllerScreen extends HandledScreen<StorageControllerScreenHandler> {
    private static final Identifier TEXTURE = Identifier.of(SmartSorter.MOD_ID, "textures/gui/storage_controller.png");
    private BlockPos lastSelectedProbePos = null;

    // Tab system
    private enum Tab {
        STORAGE("Storage"),
        CHESTS("Chests"),
        AUTO_PROCESSING("Auto-Processing");

        private final String name;

        Tab(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    private record SortableDestination(BlockPos pos, int priority, boolean isOverflow, boolean isGeneral) {}

    private List<Map.Entry<ItemVariant, Long>> cachedFilteredList = null;
    private String lastSearchTerm = "";
    private SortMode lastSortMode = SortMode.NAME;
    private Category lastFilterCategory = Category.ALL;
    private int lastNetworkItemsHash = 0;

    // Tooltip cache
    private List<Text> cachedTooltip = null;
    private ItemVariant cachedTooltipItem = null;
    private long cachedTooltipAmount = 0;

    private ButtonWidget sortAllButton;
    private DropdownWidget chestSortDropdown;
    private long sortAllClickTime = 0;
    private int sortedChestCount = 0;
    private BlockPos controllerPos = null;

    // String format cache
    private static final String[] AMOUNT_CACHE = new String[1000];
    static {
        for (int i = 0; i < 1000; i++) {
            AMOUNT_CACHE[i] = String.valueOf(i);
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

    // Chests Tab
    private ChestSelectorWidget chestSelector;
    private ChestConfigPanel chestConfigPanel;
    private BlockPos lastSelectedChestPos = null;

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

        initTabButtons();

        if (currentTab == Tab.STORAGE) {
            initStorageWidgets();
        } else if (currentTab == Tab.CHESTS) {
            initChestWidgets();
        } else {
            initAutoProcessingWidgets();
        }

        registerMouseEvents();

        handler.requestSync();
        updateNetworkItems();
    }

    // ========== INPUT HANDLING (VERSION-SPECIFIC) ==========

    //? if >=1.21.9 {
    @Override
    public boolean keyPressed(KeyInput input) {
        // Probe selector (AUTO_PROCESSING tab)
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.keyPressed(input.key(), 0, input.modifiers())) {
                return true;
            }
        }

        if (currentTab == Tab.CHESTS) {
            // Block ALL keys when renaming (including inventory close)
            if (chestSelector != null && chestSelector.isCurrentlyRenaming()) {
                chestSelector.keyPressed(input.key(), 0, input.modifiers());
                return true;  // Always consume when renaming
            }

            // Check chest selector first (for rename field)
            if (chestSelector != null && chestSelector.keyPressed(input.key(), 0, input.modifiers())) {
                return true;
            }
            // Then check config panel
            if (chestConfigPanel != null && chestConfigPanel.keyPressed(input.key(), 0, input.modifiers())) {
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

        if (currentTab == Tab.CHESTS) {
            // Check chest selector first (for rename field)
            if (chestSelector != null && chestSelector.charTyped((char) input.codepoint(), input.modifiers())) {
                return true;
            }
            // Then check config panel
            if (chestConfigPanel != null && chestConfigPanel.charTyped((char) input.codepoint(), input.modifiers())) {
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
    //?} else {
    /*@Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    // Probe selector (AUTO_PROCESSING tab)
    if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
        if (probeSelector.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
    }

    if (currentTab == Tab.CHESTS) {
    // Block ALL keys when renaming (including inventory close)
    if (chestSelector != null && chestSelector.isCurrentlyRenaming()) {
        chestSelector.keyPressed(keyCode, scanCode, modifiers);
        return true;  // Always consume when renaming
    }

    if (chestSelector != null && chestSelector.keyPressed(keyCode, scanCode, modifiers)) {
        return true;
    }
    if (chestConfigPanel != null && chestConfigPanel.keyPressed(keyCode, scanCode, modifiers)) {
        return true;
    }
    }

        // Search box (STORAGE tab only)
        if (currentTab == Tab.STORAGE && searchBox != null && searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchBox.setFocused(false);
                return true;
            }

            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                searchBox.setFocused(false);
                return true;
            }

            if (searchBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }

            // Allow the key to pass through to parent for other handling
            return true;  // Changed from just returning the result
        }

        // Search shortcuts (STORAGE tab only)
        if (currentTab == Tab.STORAGE && searchBox != null && !searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_F && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
                searchBox.setFocused(true);
                setFocused(searchBox);
                return true;
            }

            if (keyCode == GLFW.GLFW_KEY_SLASH) {
                searchBox.setFocused(true);
                setFocused(searchBox);
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);  // CHANGED: Call super instead of returning false
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        // Probe selector typing (AUTO_PROCESSING tab)
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.charTyped(chr, modifiers)) {
                return true;
            }
        }

        if (currentTab == Tab.CHESTS) {
    if (chestSelector != null && chestSelector.charTyped(chr, modifiers)) {
        return true;
    }
    if (chestConfigPanel != null && chestConfigPanel.charTyped(chr, modifiers)) {
        return true;
        }
    }


        // Search box typing (STORAGE tab)
        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.charTyped(chr, modifiers);
        }

        return super.charTyped(chr, modifiers);  // CHANGED: Call super instead of returning false
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            // TextFieldWidget doesn't have keyReleased, but parent might handle it
            return super.keyReleased(keyCode, scanCode, modifiers);
        }

        return super.keyReleased(keyCode, scanCode, modifiers);  // CHANGED: Call super
    }
    *///?}

    // ========== TAB SYSTEM ==========

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

        ButtonWidget chestsTab = ButtonWidget.builder(          // NEW TAB
                Text.literal("Chests"),
                btn -> switchTab(Tab.CHESTS)
        ).dimensions(tabX, tabY + (tabHeight + tabSpacing), tabWidth, tabHeight).build();


        ButtonWidget processingTab = ButtonWidget.builder(
                Text.literal("Config"),
                btn -> switchTab(Tab.AUTO_PROCESSING)
        ).dimensions(tabX, tabY + 2 * (tabHeight + tabSpacing), tabWidth, tabHeight).build();

        tabButtons.add(storageTab);
        tabButtons.add(chestsTab);
        tabButtons.add(processingTab);

        addDrawableChild(storageTab);
        addDrawableChild(chestsTab);
        addDrawableChild(processingTab);
    }

    private void switchTab(Tab newTab) {
        if (currentTab == newTab) return;

        currentTab = newTab;

        clearChildren();

        searchBox = null;
        sortButton = null;
        filterDropdown = null;
        probeSelector = null;
        configPanel = null;
        chestSelector = null;
        chestConfigPanel = null;
        sortAllButton = null;           // NEW
        chestSortDropdown = null;       // NEW

        for (ButtonWidget btn : tabButtons) {
            addDrawableChild(btn);
        }

        if (currentTab == Tab.STORAGE) {
            initStorageWidgets();
        } else if (currentTab == Tab.CHESTS) {
            initChestWidgets();
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

        List<Category> allCategories = CategoryManager.getInstance().getAllCategories();
        final List<Category> categoryList = new ArrayList<>();

        for (Category category : allCategories) {
            categoryList.add(category);
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
                needsRefresh = true;
                cachedFilteredList = null;
                ClientPlayNetworking.send(new FilterCategoryChangePayload(selected.asString()));
            }
        });

        addDrawableChild(filterDropdown);

        filterDropdown.active = true;
        filterDropdown.visible = true;
    }

    private void initChestWidgets() {
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        // Sort All Button (top right, similar to search bar position)
        int sortAllBtnX = guiX + 75;
        int sortAllBtnY = guiY + 4;
        int sortAllBtnWidth = 30;
        int sortAllBtnHeight = 12;

        sortAllButton = ButtonWidget.builder(
                Text.literal("Sort All"),
                btn -> handleSortAllChests()
        ).dimensions(sortAllBtnX, sortAllBtnY, sortAllBtnWidth, sortAllBtnHeight).build();
        addDrawableChild(sortAllButton);

        // Sort Mode Dropdown (right of Sort All)
        int sortDropdownX = sortAllBtnX + sortAllBtnWidth + 2;
        int sortDropdownY = sortAllBtnY;
        int sortDropdownWidth = 55;
        int sortDropdownHeight = 12;

        chestSortDropdown = new DropdownWidget(
                sortDropdownX, sortDropdownY,
                sortDropdownWidth, sortDropdownHeight,
                Text.literal("")
        );

        // Add sort mode options
        for (ChestSortMode mode : ChestSortMode.values()) {
            String abbreviation = mode.name().substring(0, 3).toUpperCase();
            String tooltip = switch(mode) {
                case PRIORITY -> "Priority - Sort by routing priority (1-10)";
                case NAME -> "Name - Sort alphabetically by chest name";
                case FULLNESS -> "Fullness - Sort by how full the chest is";
                case COORDINATES -> "Position - Sort by X, Y, Z coordinates";
            };
            chestSortDropdown.addEntry(mode.getDisplayName(), tooltip);
        }

        chestSortDropdown.setSelectedIndex(0); // Default to PRIORITY

        chestSortDropdown.setOnSelect(index -> {
            if (index >= 0 && index < ChestSortMode.values().length) {
                ChestSortMode mode = ChestSortMode.values()[index];
                if (chestSelector != null) {
                    chestSelector.setSortMode(mode);
                }
            }
        });

        addDrawableChild(chestSortDropdown);

        // Create chest selector dropdown (original position)
        chestSelector = new ChestSelectorWidget(
                guiX + 8, guiY + 18,  // Back to original Y position
                backgroundWidth - 16, 10,
                textRenderer,
                this
        );

        // Get chest configs from handler
        Map<BlockPos, ChestConfig> configs = handler.getChestConfigs();

        chestSelector.updateChests(configs);

        // Restore last selection if it exists
        if (lastSelectedChestPos != null && configs.containsKey(lastSelectedChestPos)) {
            ChestSortMode currentMode = chestSelector.getSortMode();
            List<ChestConfig> sortedList = new ArrayList<>(configs.values());
            sortedList.sort((a, b) -> a.getSortKey(currentMode).compareTo(b.getSortKey(currentMode)));

            for (int i = 0; i < sortedList.size(); i++) {
                if (sortedList.get(i).position.equals(lastSelectedChestPos)) {
                    chestSelector.setSelectedIndex(i);
                    break;
                }
            }
        }

        // Set up selection change callback
        chestSelector.setOnSelectionChange(config -> {
            if (chestConfigPanel != null) {
                chestConfigPanel.setConfig(config);
                lastSelectedChestPos = config != null ? config.position : null;
            }
        });

        // Set up config update callback
        chestSelector.setOnConfigUpdate(config -> {
            // 1. Remember which chest we are editing.
            BlockPos editedChestPos = config.position;

            // 2. Send update to server and get fresh data.
            needsRefresh = true;
            handler.updateChestConfig(editedChestPos, config);
            Map<BlockPos, ChestConfig> updatedConfigs = handler.getChestConfigs();

            // 3. Update and re-sort the list in the widget.
            chestSelector.updateChests(updatedConfigs);

            // 4. Find and re-select the edited chest.
            chestSelector.reselectChestByPos(editedChestPos);
        });

        // Create chest config panel (original position)
        chestConfigPanel = new ChestConfigPanel(
                guiX + 8, guiY + 30,  // Back to original Y position
                backgroundWidth - 16, 75,  // Back to original height
                textRenderer
        );

        // Set max priority based on chest count
        chestConfigPanel.setMaxPriority(configs.size());

        // Set initial config
        ChestConfig selected = chestSelector.getSelectedChest();
        chestConfigPanel.setConfig(selected);

        if (selected != null) {
            lastSelectedChestPos = selected.position;
        }

        // Set up config panel update callback
        chestConfigPanel.setOnConfigUpdate(config -> {
            // 1. Remember which chest we are editing.
            BlockPos editedChestPos = config.position;

            // 2. Send the update to the server and get fresh data.
            needsRefresh = true;
            handler.updateChestConfig(editedChestPos, config);
            Map<BlockPos, ChestConfig> updatedConfigs = handler.getChestConfigs();

            // 3. Tell the widget to update and re-sort its internal list.
            chestSelector.updateChests(updatedConfigs);

            // 4. CRUCIAL: Tell the widget to find and re-select the chest we were just editing.
            chestSelector.reselectChestByPos(editedChestPos);
        });
    }

    private void initAutoProcessingWidgets() {
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        probeSelector = new ProbeSelectorWidget(
                guiX + 8, guiY + 18,
                backgroundWidth - 16, 10,
                textRenderer
        );

        Map<BlockPos, ProcessProbeConfig> configs = handler.getProcessProbeConfigs();
        probeSelector.updateProbes(configs);

        if (lastSelectedProbePos != null && configs.containsKey(lastSelectedProbePos)) {
            List<ProcessProbeConfig> configList = new ArrayList<>(configs.values());

            configList.sort((a, b) -> {
                if (a.position.getX() != b.position.getX()) return Integer.compare(a.position.getX(), b.position.getX());
                if (a.position.getY() != b.position.getY()) return Integer.compare(a.position.getY(), b.position.getY());
                return Integer.compare(a.position.getZ(), b.position.getZ());
            });

            for (int i = 0; i < configList.size(); i++) {
                if (configList.get(i).position.equals(lastSelectedProbePos)) {
                    probeSelector.setSelectedIndex(i);
                    break;
                }
            }
        }

        probeSelector.setOnSelectionChange(config -> {
            if (configPanel != null) {
                configPanel.setConfig(config);
                lastSelectedProbePos = config != null ? config.position : null;
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

        if (selected != null) {
            lastSelectedProbePos = selected.position;
        }

        configPanel.setOnConfigUpdate(config -> {
            needsRefresh = true;
        });
    }

    private void registerMouseEvents() {
        //? if >=1.21.9 {
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
        //?}
    }

    //? if <=1.21.8 {
    /*@Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (filterDropdown != null && filterDropdown.isOpen()) {
            if (filterDropdown.isMouseOver(mouseX, mouseY)) {
                return filterDropdown.mouseClicked(mouseX, mouseY, button);
            } else {
                filterDropdown.close();
                return true;
            }
        }

        if (currentTab == Tab.STORAGE && searchBox != null) {
            int sx = searchBox.getX();
            int sy = searchBox.getY();
            int sw = searchBox.getWidth();
            int sh = searchBox.getHeight();
            if (mouseX >= sx && mouseX < sx + sw && mouseY >= sy && mouseY < sy + sh) {
                setFocused(searchBox);
                searchBox.setFocused(true);
                return true;
            }
        }

        if (onMouseClickIntercept(mouseX, mouseY, button)) {
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (onMouseReleaseIntercept(mouseX, mouseY, button)) {
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (onMouseDragIntercept(mouseX, mouseY, button, deltaX, deltaY)) {
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (filterDropdown != null && filterDropdown.isOpen()) {
            if (filterDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        if (onMouseScrollIntercept(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
    *///?}

    private void onSearchChanged(String searchText) {
        currentSearch = searchText.toLowerCase();
        needsRefresh = true;
        scrollProgress = 0;
        cachedFilteredList = null;
    }

    private void cycleSortMode() {
        SortMode currentMode = handler.getSortMode();
        SortMode newMode = currentMode.next();

        sortButton.setMessage(Text.literal(newMode.getDisplayName()));
        handler.setSortMode(newMode);

        needsRefresh = true;
        cachedFilteredList = null;

        ClientPlayNetworking.send(new SortModeChangePayload(newMode.asString()));
    }

    public void handleSortThisChest(BlockPos chestPos) {
        if (chestPos == null) return;

        // Send a list containing just this one chest
        ClientPlayNetworking.send(new SortChestsPayload(List.of(chestPos)));

        // Trigger visual feedback
        this.sortAllClickTime = System.currentTimeMillis();
        this.sortedChestCount = 1;

        // Optional: Add a small delay before refreshing the UI
        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            if (this.client != null) {
                this.client.execute(() -> {
                    handler.requestSync();
                    if (chestSelector != null) {
                        chestSelector.updateChests(handler.getChestConfigs());
                    }
                });
            }
        }).start();
    }

    public void updateNetworkItems() {
        Map<ItemVariant, Long> items = handler.getNetworkItems();
        SortMode currentSortMode = handler.getSortMode();
        Category currentCategory = handler.getFilterCategory();

        // Calculate hash for change detection
        int newHash = items.hashCode() ^ currentSearch.hashCode() ^
                currentSortMode.hashCode() ^ currentCategory.hashCode();

        // Check if we can reuse cached list
        if (cachedFilteredList != null &&
                newHash == lastNetworkItemsHash &&
                currentSearch.equals(lastSearchTerm) &&
                currentSortMode == lastSortMode &&
                currentCategory.equals(lastFilterCategory)) {
            // Reuse cached list
            networkItemsList = cachedFilteredList;
            return;
        }

        // Need to rebuild
        networkItemsList = new ArrayList<>(items.entrySet());

        // Filter by category
        if (currentCategory != Category.ALL) {
            CategoryManager categoryManager = CategoryManager.getInstance();
            networkItemsList.removeIf(entry -> {
                Category itemCategory = categoryManager.categorize(entry.getKey().getItem());
                return !itemCategory.equals(currentCategory);
            });
        }

        // Filter by search
        if (!currentSearch.isEmpty()) {
            String lowerSearch = currentSearch.toLowerCase();
            networkItemsList.removeIf(entry -> {
                String itemName = entry.getKey().getItem().getName().getString().toLowerCase();
                return !itemName.contains(lowerSearch);
            });
        }

        // Sort
        switch (currentSortMode) {
            case NAME:
                networkItemsList.sort((a, b) -> {
                    String nameA = a.getKey().getItem().getName().getString();
                    String nameB = b.getKey().getItem().getName().getString();
                    return nameA.compareTo(nameB);
                });
                break;
            case COUNT:
                networkItemsList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
                break;
        }

        // Cache the result
        cachedFilteredList = networkItemsList;
        lastNetworkItemsHash = newHash;
        lastSearchTerm = currentSearch;
        lastSortMode = currentSortMode;
        lastFilterCategory = currentCategory;

        int totalRows = (int) Math.ceil(networkItemsList.size() / (double) ITEMS_PER_ROW);
        maxScrollRows = Math.max(0, totalRows - VISIBLE_ROWS);
    }


    public void markDirty() {
        needsRefresh = true;
        cachedFilteredList = null;
    }

    private void collectXp() {
        int xp = handler.getStoredExperience();
        if (xp > 0) {
            lastCollectedXp = xp;
            lastCollectionTime = System.currentTimeMillis();
            ClientPlayNetworking.send(new CollectXpPayload());
        }
    }

    private void handleSortAllChests() {
        // 1. Get ONLY the chest configurations from the handler.
        Map<BlockPos, ChestConfig> chestConfigs = handler.getChestConfigs();

        if (chestConfigs.isEmpty()) {
            return; // Nothing to sort.
        }

        List<SortableDestination> destinations = new ArrayList<>();

        // 2. Convert all chests into a unified list of `SortableDestination`.
        for (ChestConfig config : chestConfigs.values()) {
            // Exclude chests in CUSTOM mode from the "Sort All" operation.
            if (config.filterMode == ChestConfig.FilterMode.CUSTOM) {
                continue;
            }
            boolean isOverflow = config.filterMode == ChestConfig.FilterMode.OVERFLOW;
            boolean isGeneral = config.filterMode == ChestConfig.FilterMode.NONE;
            destinations.add(new SortableDestination(config.position, config.priority, isOverflow, isGeneral));
        }

        // 3. Sort the list based on the new priority rules.
        destinations.sort(Comparator
                // First, put all "Overflow" chests at the end.
                .comparing(SortableDestination::isOverflow)
                // Next, put all "General" chests after prioritized ones but before overflow.
                .thenComparing(SortableDestination::isGeneral)
                // Finally, sort the remaining (prioritized) chests by their priority number (1 is highest).
                .thenComparingInt(SortableDestination::priority)
        );

        // 4. Extract the sorted BlockPos list.
        List<BlockPos> sortedPositions = destinations.stream()
                .map(SortableDestination::pos)
                .collect(Collectors.toList());

        if (sortedPositions.isEmpty()) {
            return; // No sortable destinations found.
        }

        // 5. Send the new payload with the fully sorted list.
        ClientPlayNetworking.send(new SortChestsPayload(sortedPositions));

        // Keep the visual feedback.
        this.sortAllClickTime = System.currentTimeMillis();
        this.sortedChestCount = sortedPositions.size();

        // Your existing refresh logic.
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (this.client != null) {
                this.client.execute(() -> {
                    handler.requestSync();
                    if (chestSelector != null) {
                        chestSelector.updateChests(handler.getChestConfigs());
                    }
                });
            }
        }).start();
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        //? if >=1.21.8 {

        context.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);
         //?} else {
        /*context.drawTexture(TEXTURE, x, y, 0, 0, backgroundWidth, backgroundHeight, 256, 256);
        *///?}

        if (currentTab == Tab.STORAGE) {
            drawScrollbar(context, x, y);
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
        if (needsRefresh) {
            updateNetworkItems();
            needsRefresh = false;
        }

        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        if (currentTab == Tab.STORAGE) {
            renderNetworkItems(context, mouseX, mouseY);

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

        } else if (currentTab == Tab.CHESTS) {
            renderChestsTab(context, mouseX, mouseY, delta);  // Render WITHOUT floating text
            if (chestSelector != null) {
                chestSelector.renderDropdownIfOpen(context, mouseX, mouseY);
            }
            if (chestSortDropdown != null && chestSortDropdown.isOpen()) {
                //? if >=1.21.8 {
                chestSortDropdown.renderDropdown(context, mouseX, mouseY);
                //?} else {
            /*context.getMatrices().push();
            context.getMatrices().translate(0, 0, 300);
            chestSortDropdown.renderDropdown(context, mouseX, mouseY);
            context.getMatrices().pop();
            *///?}
            }

        } else {
            renderXpDisplay(context, mouseX, mouseY);  // Render WITHOUT floating text
            renderAutoProcessingTab(context, mouseX, mouseY, delta);

            if (probeSelector != null) {
                probeSelector.renderDropdownIfOpen(context, mouseX, mouseY);
            }
        }

        drawMouseoverTooltip(context, mouseX, mouseY);

        // ========== RENDER FLOATING TEXTS LAST (ALWAYS ON TOP) ==========
        renderFloatingTexts(context, mouseX, mouseY);
    }

    private void renderChestsTab(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        context.drawText(textRenderer, "Chest Config", x + 8, y + 6, 0xFF404040, false);

        if (chestSelector != null) {
            chestSelector.render(context, mouseX, mouseY, delta);
        }

        if (chestConfigPanel != null) {
            chestConfigPanel.render(context, mouseX, mouseY, delta);
        }

        // Render tooltips when shift is held
        if (isShiftDown()) {
            // Sort All button tooltip
            if (sortAllButton != null && sortAllButton.isMouseOver(mouseX, mouseY)) {
                List<Text> tooltip = new ArrayList<>();
                tooltip.add(Text.literal("§6Sort All Chests"));
                tooltip.add(Text.literal("§7Moves all items from every"));
                tooltip.add(Text.literal("§7chest into the network"));
                context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
                return; // Don't show other tooltips
            }

            // Sort dropdown tooltip
            if (chestSortDropdown != null && chestSortDropdown.isMouseOver(mouseX, mouseY)) {
                List<Text> tooltip = new ArrayList<>();
                tooltip.add(Text.literal("§6Sort Order"));
                tooltip.add(Text.literal("§7Changes how chests are"));
                tooltip.add(Text.literal("§7ordered in the list"));
                context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
                return;
            }

            // Individual chest sort button tooltip (📤)
            if (chestSelector != null) {
                ChestConfig selected = chestSelector.getSelectedChest();
                if (selected != null && selected.filterMode != ChestConfig.FilterMode.CUSTOM) {
                    // Updated position - aligned with Sort All row
                    int dropdownWidth = backgroundWidth - 16 - 25;
                    int sortBtnX = x + 8 + dropdownWidth + 2;
                    int sortBtnY = y + 4;  // Same Y as Sort All button!
                    int sortBtnWidth = 20;
                    int sortBtnHeight = 10;

                    if (mouseX >= sortBtnX && mouseX < sortBtnX + sortBtnWidth &&
                            mouseY >= sortBtnY && mouseY < sortBtnY + sortBtnHeight) {
                        List<Text> tooltip = new ArrayList<>();
                        tooltip.add(Text.literal("§6Sort This Chest"));
                        tooltip.add(Text.literal("§7Moves items from this"));
                        tooltip.add(Text.literal("§7chest into the network"));
                        context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
                        return;
                    }
                }
            }

            // Edit button tooltip (✏)
            if (chestSelector != null) {
                // Calculate edit button position (from ChestSelectorWidget)
                int dropdownWidth = backgroundWidth - 16 - 25;
                int editBtnX = x + 8 + dropdownWidth + 2;
                int editBtnY = y + 18;
                int editBtnWidth = 20;
                int editBtnHeight = 10;

                if (mouseX >= editBtnX && mouseX < editBtnX + editBtnWidth &&
                        mouseY >= editBtnY && mouseY < editBtnY + editBtnHeight) {
                    List<Text> tooltip = new ArrayList<>();
                    tooltip.add(Text.literal("§6Rename Chest"));
                    tooltip.add(Text.literal("§7Click to rename the"));
                    tooltip.add(Text.literal("§7selected chest"));
                    context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
                    return;
                }
            }
        }
    }

    private void renderAutoProcessingTab(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        context.drawText(textRenderer, "Process Config", x + 8, y + 6, 0xFF404040, false);

        if (probeSelector != null) {
            probeSelector.render(context, mouseX, mouseY, delta);
        }

        if (configPanel != null) {
            configPanel.render(context, mouseX, mouseY, delta);
        }
    }

    private void renderNetworkItems(DrawContext context, int mouseX, int mouseY) {
        //? if = 1.21.1 {
        /*MatrixStack matrices = context.getMatrices();
        *///?}

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

                //? if >=1.21.8 {

                context.getMatrices().pushMatrix();
                context.getMatrices().translate(textX, textY);
                context.getMatrices().scale(scale, scale);

                context.drawText(textRenderer, amountText, 0, 0, 0xFFFFFFFF, true);

                context.getMatrices().popMatrix();
                //?} else {
                /*matrices.push();
                matrices.translate(textX, textY, 200);  // Add Z coordinate (usually 0)
                matrices.scale(scale, scale, scale);  // All 3 dimensions

                context.drawText(textRenderer, amountText, 0, 0, 0xFFFFFFFF, true);

                matrices.pop();
                *///?}
            }

            if (isMouseOverSlot(slotX, slotY, mouseX, mouseY)) {
                context.fill(slotX, slotY, slotX + 16, slotY + 16, 0x80FFFFFF);
            }
        }
    }

    private void renderFloatingTexts(DrawContext context, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        //? if <1.21.8 {
        /*context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);  // Very high Z to render above everything
        *///?}

        // Chests tab: "✓ Sorted!" confirmation
        if (currentTab == Tab.CHESTS) {
            long timeSinceSortAll = System.currentTimeMillis() - sortAllClickTime;
            if (timeSinceSortAll < 2000 && sortedChestCount > 0) {
                float alpha = 1.0f - (timeSinceSortAll / 2000.0f);
                int yOffset = (int) (timeSinceSortAll / 20);

                String sortedText = "✓ Sorted " + sortedChestCount + " chest" + (sortedChestCount > 1 ? "s" : "") + "!";
                float scale = 0.8f;
                int scaledWidth = (int)(textRenderer.getWidth(sortedText) * scale);
                int textX = x + backgroundWidth / 2 - scaledWidth / 2;
                int textY = y + 108 - yOffset;

                int color = (int) (alpha * 255) << 24 | 0x55FF55;

                //? if >=1.21.8 {
                Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
                Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
                context.getMatrices().mul(scaleMatrix);

                Matrix3x2f translateMatrix = new Matrix3x2f().translation(textX / scale, textY / scale);
                context.getMatrices().mul(translateMatrix);

                context.drawText(textRenderer, Text.literal(sortedText), 0, 0, color, true);
                context.getMatrices().set(oldMatrix);
                //?} else {
            /*MatrixStack matrices = context.getMatrices();
            matrices.push();
            matrices.scale(scale, scale, scale);
            matrices.translate(textX / scale, textY / scale, 0);
            context.drawText(textRenderer, Text.literal(sortedText), 0, 0, color, true);
            matrices.pop();
            *///?}
            }
        }

        // Auto-processing tab: "+XP Collected!" confirmation
        if (currentTab == Tab.AUTO_PROCESSING) {
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

                //? if >=1.21.8 {
                Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
                Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(scale, scale);
                context.getMatrices().mul(scaleMatrix);

                Matrix3x2f translateMatrix = new Matrix3x2f().translation(collectedX / scale, collectedY / scale);
                context.getMatrices().mul(translateMatrix);

                context.drawText(textRenderer, Text.literal(collectedText), 0, 0, color, true);
                context.getMatrices().set(oldMatrix);
                //?} else {
            /*MatrixStack matrices = context.getMatrices();
            matrices.push();
            matrices.scale(scale, scale, scale);
            matrices.translate(collectedX / scale, collectedY / scale, 0);
            context.drawText(textRenderer, Text.literal(collectedText), 0, 0, color, true);
            matrices.pop();
            *///?}
            }
        }

        //? if <1.21.8 {
        /*context.getMatrices().pop();
         *///?}
    }

    private void renderXpDisplay(DrawContext context, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        int xp = handler.getStoredExperience();
        long timeSinceCollection = System.currentTimeMillis() - lastCollectionTime;

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
            //? if >=1.21.8 {

            Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
            Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(textScale, textScale);
            context.getMatrices().mul(scaleMatrix);

            Matrix3x2f translateMatrix = new Matrix3x2f().translation(xpX / textScale, (xpY + 1) / textScale);
            context.getMatrices().mul(translateMatrix);

            context.drawText(textRenderer, Text.literal(xpText), 0, 0, 0xFFFFFF00, true);
            context.getMatrices().set(oldMatrix);
                //?} else {
            /*MatrixStack matrices = context.getMatrices();

            matrices.push();
            matrices.scale(textScale, textScale, textScale);
            matrices.translate(xpX / textScale, (xpY + 1) / textScale, 0);

            context.drawText(textRenderer, Text.literal(xpText), 0, 0, 0xFFFFFF00, true);

            matrices.pop();
            *///?}
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
            //? if >=1.21.8 {

            Matrix3x2f oldMatrix = new Matrix3x2f(context.getMatrices());
            Matrix3x2f scaleMatrix = new Matrix3x2f().scaling(btnTextScale, btnTextScale);
            context.getMatrices().mul(scaleMatrix);

            Matrix3x2f translateMatrix = new Matrix3x2f().translation((btnX + 3) / btnTextScale, (btnY + 2) / btnTextScale);
            context.getMatrices().mul(translateMatrix);

            context.drawText(textRenderer, Text.literal("Collect"), 0, 0, textColor, true);
            context.getMatrices().set(oldMatrix);
                //?} else {
            /*MatrixStack matrices = context.getMatrices();

            matrices.push();
            matrices.scale(btnTextScale, btnTextScale, btnTextScale);
            matrices.translate((btnX + 3) / btnTextScale, (btnY + 2) / btnTextScale, 0);

            context.drawText(textRenderer, Text.literal("Collect"), 0, 0, textColor, true);

            matrices.pop();
             *///?}
        }
    }

    public void updateProbeStats(BlockPos position, int itemsProcessed) {
        if (currentTab == Tab.AUTO_PROCESSING) {
            Map<BlockPos, ProcessProbeConfig> configs = handler.getProcessProbeConfigs();
            ProcessProbeConfig config = configs.get(position);

            if (config != null) {
                config.itemsProcessed = itemsProcessed;

                if (probeSelector != null && configPanel != null) {
                    ProcessProbeConfig selected = probeSelector.getSelectedProbe();
                    if (selected != null && selected.position.equals(position)) {
                        selected.itemsProcessed = itemsProcessed;
                        configPanel.setConfig(selected);
                    }
                }

                if (probeSelector != null) {
                    probeSelector.updateProbes(handler.getProcessProbeConfigs());
                }

                needsRefresh = true;
            }
        }
    }

    // ========== MOUSE HANDLING ==========

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

        // Chest widgets (CHESTS tab only)
        if (currentTab == Tab.CHESTS) {
            // Handle chest sort dropdown first
            if (chestSortDropdown != null) {
                if (chestSortDropdown.isOpen()) {
                    if (chestSortDropdown.isMouseOver(mouseX, mouseY)) {
                        boolean handled = chestSortDropdown.mouseClicked(mouseX, mouseY, button);
                        return handled;
                    } else {
                        chestSortDropdown.close();
                        return true;
                    }
                } else if (chestSortDropdown.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }

            boolean chestSelectorClicked = chestSelector != null && chestSelector.mouseClicked(mouseX, mouseY, button);
            boolean configPanelClicked = chestConfigPanel != null && chestConfigPanel.mouseClicked(mouseX, mouseY, button);

            // If clicked outside the config panel, unfocus it
            if (!configPanelClicked && chestConfigPanel != null) {
                chestConfigPanel.setFocused(false);
                setFocused(null);
            }

            return chestSelectorClicked || configPanelClicked;
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
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.isDropdownOpen()){
                return probeSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            }

            if (probeSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)){
                return true;
            }

            if (configPanel != null) {
                if (configPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                    return true;
                }
            }
        }

        if (currentTab == Tab.STORAGE && filterDropdown != null && filterDropdown.isOpen()) {
            if (filterDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        if (currentTab == Tab.CHESTS) {
            // Handle chest sort dropdown
            if (chestSortDropdown != null && chestSortDropdown.isOpen()) {
                if (chestSortDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                    return true;
                }
            }

            // Handle chest selector dropdown
            if (chestSelector != null && chestSelector.isDropdownOpen()) {
                return chestSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            }

            // Handle config panel
            if (chestConfigPanel != null) {
                if (chestConfigPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                    return true;
                }
            }
        }

        if (currentTab == Tab.AUTO_PROCESSING && configPanel != null) {
            if (configPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        if (currentTab == Tab.STORAGE && needsScrollbar()) {
            float scrollAmount = (float) (-verticalAmount / (maxScrollRows + 1));
            scrollProgress = Math.max(0, Math.min(1, scrollProgress + scrollAmount));
            return true;
        }

        return false;
    }

    // ========== SLOT HANDLING ==========

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

    // ========== UTILITY METHODS ==========

    private String formatAmount(long amount) {
        if (amount < 1000) {
            return amount < AMOUNT_CACHE.length ? AMOUNT_CACHE[(int) amount] : String.valueOf(amount);
        } else if (amount >= 1_000_000_000) {
            // Billions: 1.2B, 10B, etc
            if (amount >= 10_000_000_000L) {
                return (amount / 1_000_000_000) + "B";
            } else {
                long billions = amount / 1_000_000_000;
                long remainder = (amount % 1_000_000_000) / 100_000_000;
                return remainder > 0 ? billions + "." + remainder + "B" : billions + "B";
            }
        } else if (amount >= 1_000_000) {
            // Millions: 1.4M, 10M, etc
            if (amount >= 10_000_000) {
                return (amount / 1_000_000) + "M";
            } else {
                long millions = amount / 1_000_000;
                long remainder = (amount % 1_000_000) / 100_000;
                return remainder > 0 ? millions + "." + remainder + "M" : millions + "M";
            }
        } else {
            // Thousands: 1.4k, 10k, etc
            if (amount >= 10_000) {
                return (amount / 1000) + "k";
            } else {
                long thousands = amount / 1000;
                long remainder = (amount % 1000) / 100;
                return remainder > 0 ? thousands + "." + remainder + "k" : thousands + "k";
            }
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
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        if (currentTab == Tab.STORAGE) {
            context.drawText(textRenderer, Text.literal("Controller"), titleX, titleY, 0xFF404040, false);
            context.drawText(textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0xFF404040, false);

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
            context.drawText(textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, 0xFF404040, false);
        }
    }

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        super.drawMouseoverTooltip(context, mouseX, mouseY);

        if (currentTab != Tab.STORAGE) {
            return;
        }

        if (filterDropdown != null && filterDropdown.isOpen() && filterDropdown.isMouseOver(mouseX, mouseY)) {
        return;
    }

        if (this.focusedSlot != null && this.focusedSlot.hasStack()) {
            return;
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

                // Check if we can reuse cached tooltip
                if (cachedTooltip == null ||
                        !variant.equals(cachedTooltipItem) ||
                        amount != cachedTooltipAmount) {

                    cachedTooltip = new ArrayList<>(7);
                    cachedTooltip.add(variant.getItem().getName());
                    cachedTooltip.add(Text.literal("§7Stored: §f" + String.format("%,d", amount)));
                    cachedTooltip.add(Text.literal(""));
                    cachedTooltip.add(Text.literal("§8Left-Click: §7Take stack (64)"));
                    cachedTooltip.add(Text.literal("§8Right-Click: §7Take half (32)"));
                    cachedTooltip.add(Text.literal("§8Ctrl+Left: §7Take quarter (16)"));
                    cachedTooltip.add(Text.literal("§8Shift-Click: §7To inventory"));

                    cachedTooltipItem = variant;
                    cachedTooltipAmount = amount;
                }

                context.drawTooltip(textRenderer, cachedTooltip, mouseX, mouseY);
                break;
            }
        }
    }
}