package net.shaddii.smartsorter.util;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import java.util.ArrayList;
import java.util.List;

public class ChestConfig {
    public final BlockPos position;
    public String customName;
    public Category filterCategory;
    public int priority = 1;
    public FilterMode filterMode;
    public boolean autoItemFrame;
    public boolean strictNBTMatch = false;

    // Hidden priority for special chests (set by filter mode)
    public int hiddenPriority;
    public int cachedFullness = -1;

    public transient List<ItemStack> previewItems = new ArrayList<>();

    public enum FilterMode {
        NONE("General Storage", "Accepts any items"),
        CATEGORY("Dedicated", "Only accepts items in selected category"),
        PRIORITY("Priority Storage", "Accepts any items (fills earlier)"),
        CATEGORY_AND_PRIORITY("Filtered Priority", "Category filter + high priority"),
        OVERFLOW("Overflow Storage", "Only category items (fills last)"),
        BLACKLIST("Blacklist", "Everything EXCEPT selected category"),
        CUSTOM("Custom", "Only items matching chest contents");

        private final String displayName;
        private final String description;

        FilterMode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }

        public FilterMode next() {
            FilterMode[] modes = values();
            return modes[(this.ordinal() + 1) % modes.length];
        }

        // For routing priority calculation
        public int getBasePriority() {
            return switch (this) {
                case CATEGORY_AND_PRIORITY -> 1000;
                case CUSTOM -> 800;
                case PRIORITY -> 500;
                case CATEGORY -> 300;
                case BLACKLIST -> 100;
                case OVERFLOW -> -500;
                case NONE -> 0;
            };
        }

        public boolean needsCategoryFilter() {
            return this == CATEGORY || this == CATEGORY_AND_PRIORITY ||
                    this == OVERFLOW || this == BLACKLIST;
        }

        public boolean isBlacklistMode() {
            return this == BLACKLIST;
        }
    }

    public ChestConfig(BlockPos position) {
        this.position = position;
        this.customName = "";
        this.filterCategory = Category.ALL;
        this.priority = 1;  // Default to highest
        this.filterMode = FilterMode.NONE;
        this.autoItemFrame = false;
        this.hiddenPriority = 0;
    }

    public ChestConfig(BlockPos position, String customName, Category filterCategory,
                       int priority, FilterMode filterMode, boolean autoItemFrame) {
        this.position = position;
        this.customName = customName;
        this.filterCategory = filterCategory;
        this.priority = priority;
        this.filterMode = filterMode;
        this.autoItemFrame = autoItemFrame;
        this.hiddenPriority = calculateHiddenPriority();
    }

    private int calculateHiddenPriority() {
        int baseValue = filterMode.getBasePriority();
        // Lower priority number = higher actual priority
        // So invert it: subtract from a large number
        return baseValue - priority;
    }

    public void updateHiddenPriority() {
        this.hiddenPriority = calculateHiddenPriority();
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putLong("pos", position.asLong());
        nbt.putString("name", customName);
        nbt.putString("category", filterCategory.asString());
        nbt.putInt("priority", priority);
        nbt.putString("filterMode", filterMode.name());
        nbt.putBoolean("autoItemFrame", autoItemFrame);
        nbt.putBoolean("strictNBT", strictNBTMatch);
        nbt.putInt("cachedFullness", cachedFullness);
        return nbt;
    }

    public static ChestConfig fromNbt(NbtCompound nbt) {
        //? if >=1.21.8 {
        BlockPos pos = BlockPos.fromLong(nbt.getLong("pos").orElse(0L));
        String name = nbt.getString("name", "");
        Category category = CategoryManager.getInstance().getCategory(nbt.getString("category", "smartsorter:all"));

        // Migration: Check if old format (string) exists
        int priority;
        if (nbt.contains("priorityLevel")) {
            // Old format - convert
            String oldPriority = nbt.getString("priorityLevel", "MEDIUM");
            priority = switch (oldPriority) {
                case "HIGH" -> 1;
                case "LOW" -> 3;
                default -> 2; // MEDIUM
            };
        } else {
            priority = nbt.getInt("priority", 1);
        }

        FilterMode mode = FilterMode.valueOf(nbt.getString("filterMode", "NONE"));
        boolean autoFrame = nbt.getBoolean("autoItemFrame", false);
        boolean strictNBT = nbt.getBoolean("strictNBT", false);
        int cachedFull = nbt.getInt("cachedFullness", -1);
        //?} else {
    /*BlockPos pos = BlockPos.fromLong(nbt.getLong("pos"));
    String name = nbt.contains("name") ? nbt.getString("name") : "";
    String categoryStr = nbt.contains("category") ? nbt.getString("category") : "smartsorter:all";
    Category category = CategoryManager.getInstance().getCategory(categoryStr);

    // Migration: Check if old format (string) exists
    int priority;
    if (nbt.contains("priorityLevel")) {
        // Old format - convert
        String oldPriority = nbt.getString("priorityLevel");
        priority = switch (oldPriority) {
            case "HIGH" -> 1;
            case "LOW" -> 3;
            default -> 2; // MEDIUM
        };
    } else {
        priority = nbt.contains("priority") ? nbt.getInt("priority") : 1;
    }

    String modeStr = nbt.contains("filterMode") ? nbt.getString("filterMode") : "NONE";
    FilterMode mode = FilterMode.valueOf(modeStr);
    boolean autoFrame = nbt.contains("autoItemFrame") && nbt.getBoolean("autoItemFrame");
    boolean strictNBT = nbt.contains("strictNBT") && nbt.getBoolean("strictNBT");
    int cachedFull = nbt.contains("cachedFullness") ? nbt.getInt("cachedFullness") : -1;
    *///?}

        ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
        config.strictNBTMatch = strictNBT;
        config.cachedFullness = cachedFull;
        return config;
    }




    public ChestConfig copy() {
        ChestConfig copied = new ChestConfig(position, customName, filterCategory, priority, filterMode, autoItemFrame);
        copied.strictNBTMatch = this.strictNBTMatch;
        return copied;
    }

    public String getDisplayName() {
        if (!customName.isEmpty()) {
            return customName;
        }
        return String.format("Chest [%d, %d, %d]", position.getX(), position.getY(), position.getZ());
    }

    public String getSortKey(ChestSortMode mode) {
        return switch (mode) {
            case PRIORITY -> {
                String namePart = customName.isEmpty() ? position.toShortString() : customName;
                yield String.format("%03d_%s", priority, namePart);
            }
            case NAME -> (customName.isEmpty() ? "zzz_" + position.toShortString() : customName.toLowerCase());
            case FULLNESS -> {
                String namePart = customName.isEmpty() ? position.toShortString() : customName;
                yield String.format("%03d_%s", 100 - cachedFullness, namePart);
            }
            case COORDINATES -> position.toShortString();
        };
    }

}