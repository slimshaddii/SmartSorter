package net.shaddii.smartsorter.util;

import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChestPriorityManager {
    private static final int CUSTOM_PRIORITY = 0;
    private final Map<BlockPos, Integer> priorityCache = new ConcurrentHashMap<>();
    private boolean isDirty = true;

    /**
     * Add a new chest to the system
     */
    public Map<BlockPos, Integer> addChest(BlockPos pos, ChestConfig config, Map<BlockPos, ChestConfig> allConfigs) {
        // Ensure SimplePriority is set
        if (config.simplePrioritySelection == null) {
            config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
        }

        // Handle special cases
        if (config.filterMode == ChestConfig.FilterMode.OVERFLOW) {
            config.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
        }

        allConfigs.put(pos, config);
        return reorderAllChests(allConfigs);
    }

    /**
     * Remove a chest from the system
     */
    public Map<BlockPos, Integer> removeChest(BlockPos pos, Map<BlockPos, ChestConfig> allConfigs) {
        allConfigs.remove(pos);
        return reorderAllChests(allConfigs);
    }

    /**
     * Update a chest's SimplePriority
     */
    public Map<BlockPos, Integer> updateChestPriority(BlockPos pos, ChestConfig.SimplePriority newPriority,
                                                      Map<BlockPos, ChestConfig> allConfigs) {
        ChestConfig config = allConfigs.get(pos);
        if (config == null) return new HashMap<>(priorityCache);

        config.simplePrioritySelection = newPriority;
        return reorderAllChests(allConfigs);
    }

    /**
     * Recalculate all priorities (e.g., after loading from disk)
     */
    public Map<BlockPos, Integer> recalculateAll(Map<BlockPos, ChestConfig> allConfigs) {
        priorityCache.clear();
        return reorderAllChests(allConfigs);
    }

    /**
     * THE SINGLE SOURCE OF TRUTH - All priority assignment happens here
     */
    private Map<BlockPos, Integer> reorderAllChests(Map<BlockPos, ChestConfig> allConfigs) {
        Map<BlockPos, Integer> result = new HashMap<>();

        // Step 1: Handle CUSTOM chests (always priority 0)
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) {
                ChestConfig config = entry.getValue();
                config.priority = CUSTOM_PRIORITY;
                config.updateHiddenPriority();
                priorityCache.put(entry.getKey(), CUSTOM_PRIORITY);
                result.put(entry.getKey(), CUSTOM_PRIORITY);
            }
        }

        // Step 2: Collect regular chests
        List<ChestEntry> regularChests = new ArrayList<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode != ChestConfig.FilterMode.CUSTOM) {
                regularChests.add(new ChestEntry(entry.getKey(), entry.getValue()));
            }
        }

        // Step 3: Sort by SimplePriority groups, then by current priority within groups
        regularChests.sort((a, b) -> {
            int aOrder = getSimplePriorityOrder(a.config.simplePrioritySelection);
            int bOrder = getSimplePriorityOrder(b.config.simplePrioritySelection);

            if (aOrder != bOrder) {
                return Integer.compare(aOrder, bOrder);
            }

            // Within same SimplePriority, maintain relative order
            return Integer.compare(a.config.priority, b.config.priority);
        });

        // Step 4: Assign sequential priorities
        int priority = 1;
        for (ChestEntry chest : regularChests) {
            chest.config.priority = priority;
            chest.config.updateHiddenPriority();

            // Update the config in the map
            allConfigs.put(chest.pos, chest.config);

            // Update caches
            priorityCache.put(chest.pos, priority);
            result.put(chest.pos, priority);

            priority++;
        }

        isDirty = false;
        return result;
    }

    private int getSimplePriorityOrder(ChestConfig.SimplePriority priority) {
        if (priority == null) return 3; // Default to MEDIUM

        return switch (priority) {
            case HIGHEST -> 1;
            case HIGH -> 2;
            case MEDIUM -> 3;
            case LOW -> 4;
            case LOWEST -> 5;
        };
    }

    // Utility methods
    public boolean isDirty() { return isDirty; }
    public void markDirty() { isDirty = true; }
    public int getCachedPriority(BlockPos pos) { return priorityCache.getOrDefault(pos, -1); }
    public Map<BlockPos, Integer> getPriorityCache() { return new HashMap<>(priorityCache); }

    public int getRegularChestCount(Map<BlockPos, ChestConfig> allConfigs) {
        return (int) allConfigs.values().stream()
                .filter(config -> config.filterMode != ChestConfig.FilterMode.CUSTOM)
                .count();
    }

    private static class ChestEntry {
        final BlockPos pos;
        final ChestConfig config;

        ChestEntry(BlockPos pos, ChestConfig config) {
            this.pos = pos;
            this.config = config;
        }
    }
}