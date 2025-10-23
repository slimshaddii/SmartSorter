package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//? if >= 1.21.9 {
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
//?}
//? if >= 1.21.8 {
import net.minecraft.client.gl.RenderPipelines;
import org.joml.Matrix3x2f;
//?} else {
/*import net.minecraft.client.util.math.MatrixStack;
 *///?}
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.client.OverflowNotificationOverlay;
import net.shaddii.smartsorter.client.SortProgressOverlay;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.util.*;
import net.shaddii.smartsorter.widget.*;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.stream.Collectors;

public class StorageControllerScreen extends HandledScreen<StorageControllerScreenHandler> {
    private static final Identifier TEXTURE = Identifier.of(SmartSorter.MOD_ID, "textures/gui/storage_controller.png");

    // ========================================
    // CONSTANTS
    // ========================================

    // Grid layout
    private static final int ITEMS_PER_ROW = 9;
    private static final int VISIBLE_ROWS = 5;
    private static final int ITEMS_PER_PAGE = ITEMS_PER_ROW * VISIBLE_ROWS;
    private static final int GRID_START_X = 8;
    private static final int GRID_START_Y = 18;
    private static final int SLOT_SIZE = 18;

    // Scrollbar
    private static final int SCROLLBAR_X = 174;
    private static final int SCROLLBAR_Y = 18;
    private static final int SCROLLBAR_WIDTH = 14;
    private static final int SCROLLBAR_HEIGHT = 90;

    // Amount formatting cache
    private static final String[] AMOUNT_CACHE = new String[1000];
    static {
        for (int i = 0; i < 1000; i++) {
            AMOUNT_CACHE[i] = String.valueOf(i);
        }
    }

    // ========================================
    // TAB SYSTEM
    // ========================================

    private enum Tab {
        STORAGE("Storage"),
        CHESTS("Chests"),
        AUTO_PROCESSING("Auto-Processing");

        private final String name;
        Tab(String name) { this.name = name; }
        public String getName() { return name; }
    }

    private record SortableDestination(BlockPos pos, int priority, boolean isOverflow, boolean isGeneral) {}

    private Tab currentTab = Tab.STORAGE;
    private List<ButtonWidget> tabButtons = new ArrayList<>();

    // ========================================
    // WIDGET REFERENCES
    // ========================================

    // Storage tab
    private TextFieldWidget searchBox;
    private ButtonWidget sortButton;
    private DropdownWidget filterDropdown;

    // Chests tab
    private ChestSelectorWidget chestSelector;
    private ChestConfigPanel chestConfigPanel;
    private ButtonWidget sortAllButton;
    private DropdownWidget chestSortDropdown;

    // Auto-processing tab
    private ProbeSelectorWidget probeSelector;
    private ProbeConfigPanel configPanel;

    // ========================================
    // STATE TRACKING
    // ========================================

    // Selection state
    private BlockPos lastSelectedProbePos = null;
    private BlockPos lastSelectedChestPos = null;
    private BlockPos controllerPos = null;

    // Search state
    private String currentSearch = "";

    // Scroll state
    private float scrollProgress = 0.0f;
    private boolean isScrolling = false;
    private int maxScrollRows = 0;

    // XP collection
    private int lastCollectedXp = 0;
    private long lastCollectionTime = 0;

    // Sort feedback
    private long sortAllClickTime = 0;
    private int sortedChestCount = 0;

    // Dirty flag
    private boolean needsRefresh = true;

    // ========================================
    // CACHING
    // ========================================

    // Item list cache
    private List<Map.Entry<ItemVariant, Long>> networkItemsList = new ArrayList<>();
    private List<Map.Entry<ItemVariant, Long>> cachedFilteredList = null;
    private String lastSearchTerm = "";
    private SortMode lastSortMode = SortMode.NAME;
    private Category lastFilterCategory = Category.ALL;
    private int lastNetworkItemsHash = 0;

    // Tooltip cache
    private List<Text> cachedTooltip = null;
    private ItemVariant cachedTooltipItem = null;
    private long cachedTooltipAmount = 0;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public StorageControllerScreen(StorageControllerScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 194;
        this.backgroundHeight = 202;
        this.titleX = 7;
        this.titleY = 6;
        this.playerInventoryTitleX = 8;
        this.playerInventoryTitleY = 109;
    }

    // ========================================
    // INITIALIZATION
    // ========================================

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

    private void initTabButtons() {
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;
        int tabX = guiX - 60;
        int tabY = guiY + 10;
        int tabWidth = 58;
        int tabHeight = 22;
        int tabSpacing = 4;

        tabButtons.clear();

        ButtonWidget storageTab = ButtonWidget.builder(Text.literal("Items"), btn -> switchTab(Tab.STORAGE))
                .dimensions(tabX, tabY, tabWidth, tabHeight).build();

        ButtonWidget chestsTab = ButtonWidget.builder(Text.literal("Chests"), btn -> switchTab(Tab.CHESTS))
                .dimensions(tabX, tabY + (tabHeight + tabSpacing), tabWidth, tabHeight).build();

        ButtonWidget processingTab = ButtonWidget.builder(Text.literal("Config"), btn -> switchTab(Tab.AUTO_PROCESSING))
                .dimensions(tabX, tabY + 2 * (tabHeight + tabSpacing), tabWidth, tabHeight).build();

        tabButtons.add(storageTab);
        tabButtons.add(chestsTab);
        tabButtons.add(processingTab);

        addDrawableChild(storageTab);
        addDrawableChild(chestsTab);
        addDrawableChild(processingTab);
    }

    private void initStorageWidgets() {
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        // Search box
        searchBox = new TextFieldWidget(textRenderer, guiX + 82, guiY + 6, 90, 13, Text.literal(""));
        searchBox.setDrawsBackground(false);
        searchBox.setChangedListener(this::onSearchChanged);
        addDrawableChild(searchBox);

        // Sort button
        sortButton = ButtonWidget.builder(Text.literal(handler.getSortMode().getDisplayName()), btn -> cycleSortMode())
                .dimensions(guiX + 82, guiY + 6 + 13 - 34, 30, 12).build();
        addDrawableChild(sortButton);

        // Filter dropdown
        filterDropdown = new DropdownWidget(guiX + 82 + 30 + 2, guiY + 6 + 13 - 34, 60, 12, Text.literal(""));

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

        // Sort All button
        sortAllButton = ButtonWidget.builder(Text.literal("Sort All"), btn -> handleSortAllChests())
                .dimensions(guiX + 75, guiY + 4, 30, 12).build();
        addDrawableChild(sortAllButton);

        // Sort mode dropdown
        chestSortDropdown = new DropdownWidget(guiX + 75 + 30 + 2, guiY + 4, 55, 12, Text.literal(""));

        for (ChestSortMode mode : ChestSortMode.values()) {
            String tooltip = switch(mode) {
                case PRIORITY -> "Priority - Sort by routing priority (1-10)";
                case NAME -> "Name - Sort alphabetically by chest name";
                case FULLNESS -> "Fullness - Sort by how full the chest is";
                case COORDINATES -> "Position - Sort by X, Y, Z coordinates";
            };
            chestSortDropdown.addEntry(mode.getDisplayName(), tooltip);
        }

        chestSortDropdown.setSelectedIndex(0);
        chestSortDropdown.setOnSelect(index -> {
            if (index >= 0 && index < ChestSortMode.values().length) {
                ChestSortMode mode = ChestSortMode.values()[index];
                if (chestSelector != null) {
                    chestSelector.setSortMode(mode);
                }
            }
        });
        addDrawableChild(chestSortDropdown);

        // Chest selector
        chestSelector = new ChestSelectorWidget(guiX + 8, guiY + 18, backgroundWidth - 16, 10, textRenderer, this);
        Map<BlockPos, ChestConfig> configs = handler.getChestConfigs();
        chestSelector.updateChests(configs);

        // Restore selection
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

        chestSelector.setOnSelectionChange(config -> {
            if (chestConfigPanel != null) {
                chestConfigPanel.setConfig(config);
                lastSelectedChestPos = config != null ? config.position : null;
            }
        });

        chestSelector.setOnConfigUpdate(config -> {
            BlockPos editedChestPos = config.position;
            needsRefresh = true;
            handler.updateChestConfig(editedChestPos, config);
            Map<BlockPos, ChestConfig> updatedConfigs = handler.getChestConfigs();
            chestSelector.updateChests(updatedConfigs);
            chestSelector.reselectChestByPos(editedChestPos);
        });

        // Chest config panel
        chestConfigPanel = new ChestConfigPanel(guiX + 8, guiY + 30, backgroundWidth - 16, 75, textRenderer);

        // Calculate correct maxPriority (exclude CUSTOM chests)
        int regularChestCount = (int) configs.values().stream()
                .filter(c -> c.filterMode != ChestConfig.FilterMode.CUSTOM)
                .count();
        chestConfigPanel.setMaxPriority(regularChestCount);

        ChestConfig selected = chestSelector.getSelectedChest();
        chestConfigPanel.setConfig(selected);

        if (selected != null) {
            lastSelectedChestPos = selected.position;
        }

        // Remove Thread.sleep() hack
        chestConfigPanel.setOnConfigUpdate(config -> {
            BlockPos editedChestPos = config.position;
            needsRefresh = true;
            handler.updateChestConfig(editedChestPos, config);

            // Let server handle the update via ChestPriorityBatchPayload
            // Local update for optimistic UI (will be overwritten by server)
            Map<BlockPos, ChestConfig> updatedConfigs = handler.getChestConfigs();
            chestSelector.updateChests(updatedConfigs);
            chestSelector.reselectChestByPos(editedChestPos);
        });
    }

    private void initAutoProcessingWidgets() {
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;

        // Probe selector
        probeSelector = new ProbeSelectorWidget(guiX + 8, guiY + 18, backgroundWidth - 16, 10, textRenderer);
        Map<BlockPos, ProcessProbeConfig> configs = handler.getProcessProbeConfigs();
        probeSelector.updateProbes(configs);

        // Restore selection
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

        probeSelector.setOnConfigUpdate(config -> needsRefresh = true);

        // Config panel
        configPanel = new ProbeConfigPanel(guiX + 8, guiY + 30, backgroundWidth - 16, 75, textRenderer);
        ProcessProbeConfig selected = probeSelector.getSelectedProbe();
        configPanel.setConfig(selected);

        if (selected != null) {
            lastSelectedProbePos = selected.position;
        }

        configPanel.setOnConfigUpdate(config -> needsRefresh = true);
    }

    public void onPriorityUpdate() {
        if (currentTab != Tab.CHESTS) return;
        Map<BlockPos, ChestConfig> updatedConfigs = handler.getChestConfigs();

        if (chestConfigPanel != null) {
            int regularChestCount = (int) updatedConfigs.values().stream()
                    .filter(c -> c.filterMode != ChestConfig.FilterMode.CUSTOM)
                    .count();
            chestConfigPanel.setMaxPriority(regularChestCount);
        }

        if (chestSelector != null) {
            chestSelector.updateChests(updatedConfigs);

            if (lastSelectedChestPos != null && updatedConfigs.containsKey(lastSelectedChestPos)) {
                chestSelector.reselectChestByPos(lastSelectedChestPos);

                ChestConfig refreshedConfig = updatedConfigs.get(lastSelectedChestPos);
                if (chestConfigPanel != null && refreshedConfig != null) {
                    chestConfigPanel.setConfig(refreshedConfig);
                }
            }
        }
        needsRefresh = true;
    }

    // ========================================
    // TAB SWITCHING
    // ========================================

    private void switchTab(Tab newTab) {
        if (currentTab == newTab) return;

        currentTab = newTab;
        clearChildren();

        // Clear widget references
        searchBox = null;
        sortButton = null;
        filterDropdown = null;
        probeSelector = null;
        configPanel = null;
        chestSelector = null;
        chestConfigPanel = null;
        sortAllButton = null;
        chestSortDropdown = null;

        // Re-add tab buttons
        for (ButtonWidget btn : tabButtons) {
            addDrawableChild(btn);
        }

        // Initialize new tab
        if (currentTab == Tab.STORAGE) {
            initStorageWidgets();
        } else if (currentTab == Tab.CHESTS) {
            initChestWidgets();
        } else {
            initAutoProcessingWidgets();
        }

        registerMouseEvents();
    }

    // ========================================
    // INPUT HANDLING (VERSION-SPECIFIC)
    // ========================================

    //? if >=1.21.9 {
    @Override
    public boolean keyPressed(KeyInput input) {
        // Auto-processing tab
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.keyPressed(input.key(), 0, input.modifiers())) return true;
        }

        // Chests tab
        if (currentTab == Tab.CHESTS) {
            if (chestSelector != null && chestSelector.isCurrentlyRenaming()) {
                chestSelector.keyPressed(input.key(), 0, input.modifiers());
                return true;
            }
            if (chestSelector != null && chestSelector.keyPressed(input.key(), 0, input.modifiers())) return true;
            if (chestConfigPanel != null && chestConfigPanel.keyPressed(input)) return true;
        }

        // Storage tab - search box
        if (currentTab == Tab.STORAGE && searchBox != null && searchBox.isFocused()) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE || input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
                searchBox.setFocused(false);
                return true;
            }
            if (searchBox.keyPressed(input)) return true;
            return true;
        }

        // Storage tab - search shortcuts
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
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.charTyped((char) input.codepoint(), input.modifiers())) return true;
        }

        if (currentTab == Tab.CHESTS) {
            if (chestSelector != null && chestSelector.charTyped((char) input.codepoint(), input.modifiers())) return true;
            if (chestConfigPanel != null && chestConfigPanel.charTyped(input)) return true;
        }

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
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.keyPressed(keyCode, scanCode, modifiers)) return true;
        }

        if (currentTab == Tab.CHESTS) {
            if (chestSelector != null && chestSelector.isCurrentlyRenaming()) {
                chestSelector.keyPressed(keyCode, scanCode, modifiers);
                return true;
            }
            if (chestSelector != null && chestSelector.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (chestConfigPanel != null && chestConfigPanel.keyPressed(keyCode, scanCode, modifiers)) return true;
        }

        if (currentTab == Tab.STORAGE && searchBox != null && searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                searchBox.setFocused(false);
                return true;
            }
            if (searchBox.keyPressed(keyCode, scanCode, modifiers)) return true;
            return true;
        }

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

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.charTyped(chr, modifiers)) return true;
        }

        if (currentTab == Tab.CHESTS) {
            if (chestSelector != null && chestSelector.charTyped(chr, modifiers)) return true;
            if (chestConfigPanel != null && chestConfigPanel.charTyped(chr, modifiers)) return true;
        }

        if (searchBox != null && searchBox.isFocused()) {
            return searchBox.charTyped(chr, modifiers);
        }

        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            return super.keyReleased(keyCode, scanCode, modifiers);
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }
    *///?}

    // ========================================
    // MOUSE EVENT REGISTRATION
    // ========================================

    private void registerMouseEvents() {
        //? if >=1.21.9 {
        ScreenMouseEvents.allowMouseClick(this).register((screen, click) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            if (gui.filterDropdown != null && gui.filterDropdown.isOpen()) {
                if (gui.filterDropdown.isMouseOver(click.x(), click.y())) {
                    return !gui.filterDropdown.mouseClicked(click.x(), click.y(), click.button());
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

            return !gui.onMouseClickIntercept(click.x(), click.y(), click.button());
        });

        ScreenMouseEvents.allowMouseRelease(this).register((screen, click) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;
            return !gui.onMouseReleaseIntercept(click.x(), click.y(), click.button());
        });

        ScreenMouseEvents.allowMouseDrag(this).register((screen, click, deltaX, deltaY) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;
            return !gui.onMouseDragIntercept(click.x(), click.y(), click.button(), deltaX, deltaY);
        });

        ScreenMouseEvents.allowMouseScroll(this).register((screen, mouseX, mouseY, horizontal, vertical) -> {
            if (!(screen instanceof StorageControllerScreen gui)) return true;

            if (gui.filterDropdown != null && gui.filterDropdown.isOpen()) {
                if (gui.filterDropdown.mouseScrolled(mouseX, mouseY, horizontal, vertical)) return false;
            }

            return !gui.onMouseScrollIntercept(mouseX, mouseY, horizontal, vertical);
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

        if (onMouseClickIntercept(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (onMouseReleaseIntercept(mouseX, mouseY, button)) return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (onMouseDragIntercept(mouseX, mouseY, button, deltaX, deltaY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (filterDropdown != null && filterDropdown.isOpen()) {
            if (filterDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        }

        if (onMouseScrollIntercept(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
    *///?}

    // ========================================
    // MOUSE HANDLERS
    // ========================================

    private boolean onMouseClickIntercept(double mouseX, double mouseY, int button) {
        // PRIORITY 1: Handle chestSortDropdown FIRST (it's always on top)
        if (currentTab == Tab.CHESTS && chestSortDropdown != null) {
            if (chestSortDropdown.isOpen()) {
                if (chestSortDropdown.isMouseOver(mouseX, mouseY)) {
                    return chestSortDropdown.mouseClicked(mouseX, mouseY, button);
                } else {
                    chestSortDropdown.close();
                    return true;
                }
            } else {
                // Check if clicking on the closed dropdown button
                if (chestSortDropdown.isMouseOver(mouseX, mouseY)) {
                    return chestSortDropdown.mouseClicked(mouseX, mouseY, button);
                }
            }
        }

        // PRIORITY 2: Handle ChestSelector dropdown (the main chest list)
        if (currentTab == Tab.CHESTS && chestSelector != null && chestSelector.isDropdownOpen()) {
            // Check if click is on the dropdown
            DropdownWidget dropdown = chestSelector.dropdown;
            int dropdownY = dropdown.getDropdownY();
            int visibleEntries = Math.min(6, dropdown.getEntries().size());
            int dropdownHeight = visibleEntries * 12;

            if (mouseX >= dropdown.getX() && mouseX < dropdown.getX() + dropdown.getWidth() &&
                    mouseY >= dropdownY && mouseY < dropdownY + dropdownHeight) {
                // Click is on the dropdown, let it handle
                return chestSelector.mouseClicked(mouseX, mouseY, button);
            }
            // Click is outside, close the dropdown
            dropdown.close();
            return true;
        }

        // PRIORITY 3: Handle filter dropdown (Storage tab)
        if (currentTab == Tab.STORAGE && filterDropdown != null) {
            if (filterDropdown.isOpen()) {
                if (filterDropdown.isMouseOver(mouseX, mouseY)) {
                    return filterDropdown.mouseClicked(mouseX, mouseY, button);
                } else {
                    filterDropdown.close();
                    return true;
                }
            } else {
                if (filterDropdown.isMouseOver(mouseX, mouseY)) {
                    return filterDropdown.mouseClicked(mouseX, mouseY, button);
                }
            }
        }

        // Rest of the method stays the same...
        // XP collect button (AUTO_PROCESSING tab)
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

            if (mouseX >= btnX && mouseX <= btnX + btnWidth && mouseY >= btnY && mouseY <= btnY + btnHeight) {
                collectXp();
                return true;
            }
        }

        // Chests tab widgets
        if (currentTab == Tab.CHESTS) {
            boolean chestSelectorClicked = chestSelector != null && chestSelector.mouseClicked(mouseX, mouseY, button);
            boolean configPanelClicked = chestConfigPanel != null && chestConfigPanel.mouseClicked(mouseX, mouseY, button);

            if (!configPanelClicked && chestConfigPanel != null) {
                chestConfigPanel.setFocused(false);
                setFocused(null);
            }

            return chestSelectorClicked || configPanelClicked;
        }

        // Auto-processing widgets
        if (currentTab == Tab.AUTO_PROCESSING) {
            if (probeSelector != null && probeSelector.mouseClicked(mouseX, mouseY, button)) return true;
            if (configPanel != null && configPanel.mouseClicked(mouseX, mouseY, button)) return true;
            return false;
        }

        // Storage tab only
        if (currentTab != Tab.STORAGE) return false;

        // Scrollbar
        if (needsScrollbar() && isMouseOverScrollbar(mouseX, mouseY)) {
            isScrolling = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        // Network item grid clicks
        int guiX = (width - backgroundWidth) / 2;
        int guiY = (height - backgroundHeight) / 2;
        int gridStartX = guiX + GRID_START_X;
        int gridStartY = guiY + GRID_START_Y;
        int gridEndX = gridStartX + (ITEMS_PER_ROW * SLOT_SIZE);
        int gridEndY = gridStartY + (VISIBLE_ROWS * SLOT_SIZE);

        boolean clickInGrid = mouseX >= gridStartX && mouseX < gridEndX && mouseY >= gridStartY && mouseY < gridEndY;

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
        // Auto-processing tab scrolling
        if (currentTab == Tab.AUTO_PROCESSING && probeSelector != null) {
            if (probeSelector.isDropdownOpen()) {
                return probeSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            }
            if (probeSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
            if (configPanel != null && configPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        }

        // Storage tab dropdown scrolling
        if (currentTab == Tab.STORAGE && filterDropdown != null && filterDropdown.isOpen()) {
            if (filterDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        }

        // Chests tab scrolling
        if (currentTab == Tab.CHESTS) {
            if (chestSortDropdown != null && chestSortDropdown.isOpen()) {
                if (chestSortDropdown.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
            }
            if (chestSelector != null && chestSelector.isDropdownOpen()) {
                return chestSelector.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
            }
            if (chestConfigPanel != null && chestConfigPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }

        // Storage tab item grid scrolling
        if (currentTab == Tab.STORAGE && needsScrollbar()) {
            float scrollAmount = (float) (-verticalAmount / (maxScrollRows + 1));
            scrollProgress = Math.max(0, Math.min(1, scrollProgress + scrollAmount));
            return true;
        }

        return false;
    }

    // ========================================
    // NETWORK ITEM SLOT HANDLING
    // ========================================

    private void handleNetworkSlotClick(int slotIndex, int button, boolean isShift, boolean isCtrl) {
        var entry = networkItemsList.get(slotIndex);
        ItemVariant variant = entry.getKey();
        long itemCount = entry.getValue();

        ItemStack cursorStack = handler.getCursorStack();

        // Deposit if holding items
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

        // Calculate extraction amount
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

    // ========================================
    // RENDERING
    // ========================================

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
            renderChestsTab(context, mouseX, mouseY, delta);

            // Render ALL dropdowns with proper z-layer for 1.21.1
            //? if <1.21.8 {
            /*context.getMatrices().push();
            context.getMatrices().translate(0, 0, 500);

            if (chestSelector != null) {
                chestSelector.renderDropdownIfOpen(context, mouseX, mouseY);
            }
            if (chestSortDropdown != null && chestSortDropdown.isOpen()) {
                chestSortDropdown.renderDropdown(context, mouseX, mouseY);
            }
            if (chestConfigPanel != null) {
                chestConfigPanel.renderDropdownsOnly(context, mouseX, mouseY);
            }

            context.getMatrices().pop();
            *///?} else {
            if (chestSelector != null) {
                chestSelector.renderDropdownIfOpen(context, mouseX, mouseY);
            }
            if (chestSortDropdown != null && chestSortDropdown.isOpen()) {
                chestSortDropdown.renderDropdown(context, mouseX, mouseY);
            }
            //?}
        } else {
            renderXpDisplay(context, mouseX, mouseY);
            renderAutoProcessingTab(context, mouseX, mouseY, delta);

            if (probeSelector != null) {
                probeSelector.renderDropdownIfOpen(context, mouseX, mouseY);
            }
        }

        drawMouseoverTooltip(context, mouseX, mouseY);
        renderFloatingTexts(context, mouseX, mouseY);
        OverflowNotificationOverlay.render(context, 0f);
        SortProgressOverlay.render(context);

        //? if >=1.21.8 {
        context.getMatrices().pushMatrix();// High Z value to be on top
        OverflowNotificationOverlay.render(context, 0f);
        context.getMatrices().popMatrix();
        //?} else {
        /*context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);
        OverflowNotificationOverlay.render(context, 0f);
        context.getMatrices().pop();
        *///?}
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

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        if (currentTab == Tab.STORAGE) {
            context.drawText(textRenderer, Text.literal("Controller"), titleX, titleY, 0xFF404040, false);
            context.drawText(textRenderer, playerInventoryTitle, playerInventoryTitleX, playerInventoryTitleY, 0xFF404040, false);

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
            context.drawText(textRenderer, playerInventoryTitle, playerInventoryTitleX, playerInventoryTitleY, 0xFF404040, false);
        }
    }

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        super.drawMouseoverTooltip(context, mouseX, mouseY);

        if (currentTab != Tab.STORAGE) return;
        if (filterDropdown != null && filterDropdown.isOpen() && filterDropdown.isMouseOver(mouseX, mouseY)) return;
        if (focusedSlot != null && focusedSlot.hasStack()) return;

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

                // Use cached tooltip if possible
                if (cachedTooltip == null || !variant.equals(cachedTooltipItem) || amount != cachedTooltipAmount) {
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

                //? if >=1.21.8 {
                context.getMatrices().pushMatrix();
                context.getMatrices().translate(textX, textY);
                context.getMatrices().scale(scale, scale);
                context.drawText(textRenderer, amountText, 0, 0, 0xFFFFFFFF, true);
                context.getMatrices().popMatrix();
                //?} else {
                /*context.getMatrices().push();
                context.getMatrices().translate(textX, textY, 200);
                context.getMatrices().scale(scale, scale, scale);
                context.drawText(textRenderer, amountText, 0, 0, 0xFFFFFFFF, true);
                context.getMatrices().pop();
                *///?}
            }

            if (isMouseOverSlot(slotX, slotY, mouseX, mouseY)) {
                context.fill(slotX, slotY, slotX + 16, slotY + 16, 0x80FFFFFF);
            }
        }
    }

    private void renderChestsTab(DrawContext context, int mouseX, int mouseY, float delta) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        context.drawText(textRenderer, "Chest Config", x + 8, y + 6, 0xFF404040, false);

        if (chestSelector != null) {
            chestSelector.render(context, mouseX, mouseY, delta);
        }

        if (chestConfigPanel != null) {
            chestConfigPanel.setExternalDropdownOpen(chestSelector != null && chestSelector.isDropdownOpen());
            chestConfigPanel.render(context, mouseX, mouseY, delta);
        }

        // Tooltips (when shift held)
        if (isShiftDown()) {
            if (sortAllButton != null && sortAllButton.isMouseOver(mouseX, mouseY)) {
                List<Text> tooltip = new ArrayList<>();
                tooltip.add(Text.literal("§6Sort All Chests"));
                tooltip.add(Text.literal("§7Moves all items from every"));
                tooltip.add(Text.literal("§7chest into the network"));
                context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
                return;
            }

            if (chestSortDropdown != null && chestSortDropdown.isMouseOver(mouseX, mouseY)) {
                List<Text> tooltip = new ArrayList<>();
                tooltip.add(Text.literal("§6Sort Order"));
                tooltip.add(Text.literal("§7Changes how chests are"));
                tooltip.add(Text.literal("§7ordered in the list"));
                context.drawTooltip(textRenderer, tooltip, mouseX, mouseY);
                return;
            }

            if (chestSelector != null) {
                ChestConfig selected = chestSelector.getSelectedChest();
                if (selected != null && selected.filterMode != ChestConfig.FilterMode.CUSTOM) {
                    int dropdownWidth = backgroundWidth - 16 - 25;
                    int sortBtnX = x + 8 + dropdownWidth + 2;
                    int sortBtnY = y + 4;
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

            if (chestSelector != null) {
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

    private void renderXpDisplay(DrawContext context, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;
        int xp = handler.getStoredExperience();
        long timeSinceCollection = System.currentTimeMillis() - lastCollectionTime;

        float textScale = 0.7f;
        String xpText = "XP: " + xp;
        int xpTextWidth = (int)(textRenderer.getWidth(xpText) * textScale);

        int xpX = x + backgroundWidth - 85;
        int xpY = y + 6;

        // Background
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
            /*context.getMatrices().push();
            context.getMatrices().scale(textScale, textScale, textScale);
            context.getMatrices().translate(xpX / textScale, (xpY + 1) / textScale, 0);
            context.drawText(textRenderer, Text.literal(xpText), 0, 0, 0xFFFFFF00, true);
            context.getMatrices().pop();
            *///?}
        }

        // Collect button
        int btnX = xpX + xpTextWidth + 3;
        int btnY = xpY;
        int btnWidth = 28;
        int btnHeight = 9;

        boolean hovered = mouseX >= btnX && mouseX < btnX + btnWidth && mouseY >= btnY && mouseY < btnY + btnHeight;
        boolean justClicked = timeSinceCollection < 200;
        int btnBg = xp > 0 ? (justClicked ? 0xFFFFFF55 : (hovered ? 0xFF55FF55 : 0xFF00AA00)) : 0xFF444444;
        int textColor = xp > 0 ? 0xFFFFFFFF : 0xFF888888;

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
            /*context.getMatrices().push();
            context.getMatrices().scale(btnTextScale, btnTextScale, btnTextScale);
            context.getMatrices().translate((btnX + 3) / btnTextScale, (btnY + 2) / btnTextScale, 0);
            context.drawText(textRenderer, Text.literal("Collect"), 0, 0, textColor, true);
            context.getMatrices().pop();
            *///?}
        }
    }

    private void renderFloatingTexts(DrawContext context, int mouseX, int mouseY) {
        int x = (width - backgroundWidth) / 2;
        int y = (height - backgroundHeight) / 2;

        //? if <1.21.8 {
        /*context.getMatrices().push();
        context.getMatrices().translate(0, 0, 400);
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
                /*context.getMatrices().push();
                context.getMatrices().scale(scale, scale, scale);
                context.getMatrices().translate(textX / scale, textY / scale, 0);
                context.drawText(textRenderer, Text.literal(sortedText), 0, 0, color, true);
                context.getMatrices().pop();
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
                /*context.getMatrices().push();
                context.getMatrices().scale(scale, scale, scale);
                context.getMatrices().translate(collectedX / scale, collectedY / scale, 0);
                context.drawText(textRenderer, Text.literal(collectedText), 0, 0, color, true);
                context.getMatrices().pop();
                *///?}
            }
        }

        //? if <1.21.8 {
        /*context.getMatrices().pop();
         *///?}
    }

    // ========================================
    // NETWORK & STATE UPDATES
    // ========================================

    public void updateNetworkItems() {
        Map<ItemVariant, Long> items = handler.getNetworkItems();
        SortMode currentSortMode = handler.getSortMode();
        Category currentCategory = handler.getFilterCategory();

        int newHash = items.hashCode() ^ currentSearch.hashCode() ^ currentSortMode.hashCode() ^ currentCategory.hashCode();

        // Reuse cache if unchanged
        if (cachedFilteredList != null && newHash == lastNetworkItemsHash && currentSearch.equals(lastSearchTerm) &&
                currentSortMode == lastSortMode && currentCategory.equals(lastFilterCategory)) {
            networkItemsList = cachedFilteredList;
            return;
        }

        // Rebuild
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
            case NAME -> networkItemsList.sort((a, b) -> {
                String nameA = a.getKey().getItem().getName().getString();
                String nameB = b.getKey().getItem().getName().getString();
                return nameA.compareTo(nameB);
            });
            case COUNT -> networkItemsList.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        }

        // Update cache
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

    // ========================================
    // ACTIONS
    // ========================================

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

    private void collectXp() {
        int xp = handler.getStoredExperience();
        if (xp > 0) {
            lastCollectedXp = xp;
            lastCollectionTime = System.currentTimeMillis();
            ClientPlayNetworking.send(new CollectXpPayload());
        }
    }

    public void handleSortThisChest(BlockPos chestPos) {
        if (chestPos == null) return;

        List<BlockPos> singleChestList = List.of(chestPos);
        ClientPlayNetworking.send(new SortChestsPayload(singleChestList));

        sortAllClickTime = System.currentTimeMillis();
        sortedChestCount = 1;

        new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            if (client != null) {
                client.execute(() -> {
                    handler.requestSync();
                    if (chestSelector != null) {
                        chestSelector.updateChests(handler.getChestConfigs());
                    }
                });
            }
        }).start();
    }

    private void handleSortAllChests() {
        Map<BlockPos, ChestConfig> chestConfigs = handler.getChestConfigs();
        if (chestConfigs.isEmpty()) return;

        List<SortableDestination> destinations = new ArrayList<>();

        for (ChestConfig config : chestConfigs.values()) {
            if (config.filterMode == ChestConfig.FilterMode.CUSTOM) continue;
            boolean isOverflow = config.filterMode == ChestConfig.FilterMode.OVERFLOW;
            boolean isGeneral = config.filterMode == ChestConfig.FilterMode.NONE;
            destinations.add(new SortableDestination(config.position, config.priority, isOverflow, isGeneral));
        }

        destinations.sort(Comparator
                .comparing(SortableDestination::isOverflow)
                .thenComparing(SortableDestination::isGeneral)
                .thenComparingInt(SortableDestination::priority)
        );

        List<BlockPos> sortedPositions = destinations.stream().map(SortableDestination::pos).collect(Collectors.toList());
        if (sortedPositions.isEmpty()) return;

        ClientPlayNetworking.send(new SortChestsPayload(sortedPositions));

        sortAllClickTime = System.currentTimeMillis();
        sortedChestCount = sortedPositions.size();

        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            if (client != null) {
                client.execute(() -> {
                    handler.requestSync();
                    if (chestSelector != null) {
                        chestSelector.updateChests(handler.getChestConfigs());
                    }
                });
            }
        }).start();
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    private void updateScrollFromMouse(double mouseY) {
        int y = (height - backgroundHeight) / 2;
        int barY = y + SCROLLBAR_Y;

        int handleHeight = 15;
        int maxHandleOffset = SCROLLBAR_HEIGHT - handleHeight;

        float relativeY = (float) (mouseY - barY - (handleHeight / 2.0));
        scrollProgress = Math.max(0, Math.min(1, relativeY / maxHandleOffset));
    }

    private String formatAmount(long amount) {
        if (amount < 1000) {
            return amount < AMOUNT_CACHE.length ? AMOUNT_CACHE[(int) amount] : String.valueOf(amount);
        } else if (amount >= 1_000_000_000) {
            if (amount >= 10_000_000_000L) {
                return (amount / 1_000_000_000) + "B";
            } else {
                long billions = amount / 1_000_000_000;
                long remainder = (amount % 1_000_000_000) / 100_000_000;
                return remainder > 0 ? billions + "." + remainder + "B" : billions + "B";
            }
        } else if (amount >= 1_000_000) {
            if (amount >= 10_000_000) {
                return (amount / 1_000_000) + "M";
            } else {
                long millions = amount / 1_000_000;
                long remainder = (amount % 1_000_000) / 100_000;
                return remainder > 0 ? millions + "." + remainder + "M" : millions + "M";
            }
        } else {
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
        return mouseX >= barX && mouseX < barX + SCROLLBAR_WIDTH && mouseY >= barY && mouseY < barY + SCROLLBAR_HEIGHT;
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