package net.shaddii.smartsorter.util;

import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChestPriorityManager {
    private static final int CUSTOM_PRIORITY = 0;

    private final Map<BlockPos, Integer> priorityCache = new ConcurrentHashMap<>();
    private final List<ChestEntry> sortedChests = new ArrayList<>();
    private boolean isDirty = true;

    private static class ChestEntry {
        final BlockPos pos;
        final ChestConfig config;
        int assignedPriority;

        ChestEntry(BlockPos pos, ChestConfig config) {
            this.pos = pos;
            this.config = config;
            this.assignedPriority = config.priority;
        }
    }

    /**
     * Recalculate all priorities, ensuring they are sequential
     */
    public Map<BlockPos, Integer> recalculatePriorities(Map<BlockPos, ChestConfig> chestConfigs) {
        sortedChests.clear();
        priorityCache.clear();

        List<ChestEntry> customChests = new ArrayList<>();
        List<ChestEntry> regularChests = new ArrayList<>();

        for (Map.Entry<BlockPos, ChestConfig> entry : chestConfigs.entrySet()) {
            ChestEntry chestEntry = new ChestEntry(entry.getKey(), entry.getValue());

            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) {
                customChests.add(chestEntry);
            } else {
                regularChests.add(chestEntry);
            }
        }

        // Sort custom chests (they all get priority 0)
        customChests.sort(Comparator.comparingInt(a -> a.config.priority));

        // Sort regular chests by their current priority
        regularChests.sort(Comparator.comparingInt(a -> a.config.priority));

        // Assign priorities
        for (ChestEntry chest : customChests) {
            chest.assignedPriority = CUSTOM_PRIORITY;
            chest.config.priority = CUSTOM_PRIORITY;
            priorityCache.put(chest.pos, CUSTOM_PRIORITY);
            sortedChests.add(chest);
        }

        // Ensure regular chests have sequential priorities starting from 1
        int currentPriority = 1;
        for (ChestEntry chest : regularChests) {
            chest.assignedPriority = currentPriority;
            chest.config.priority = currentPriority;
            priorityCache.put(chest.pos, currentPriority);
            sortedChests.add(chest);
            currentPriority++;
        }

        isDirty = false;
        return new HashMap<>(priorityCache);
    }

    /**
     * Insert a new chest with dynamic priority shifting
     */
    public Map<BlockPos, Integer> insertChest(BlockPos newChestPos, ChestConfig newConfig,
                                              int desiredPriority, Map<BlockPos, ChestConfig> allConfigs) {
        // Handle CUSTOM filter mode
        if (newConfig.filterMode == ChestConfig.FilterMode.CUSTOM) {
            newConfig.priority = CUSTOM_PRIORITY;
            allConfigs.put(newChestPos, newConfig);
            return recalculatePriorities(allConfigs);
        }

        // Get all regular chests (excluding the new one)
        List<ChestEntry> regularChests = new ArrayList<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (!entry.getKey().equals(newChestPos) &&
                    entry.getValue().filterMode != ChestConfig.FilterMode.CUSTOM) {
                regularChests.add(new ChestEntry(entry.getKey(), entry.getValue()));
            }
        }

        // Sort by current priority
        regularChests.sort(Comparator.comparingInt(e -> e.config.priority));

        // Determine actual insertion position (1-based)
        int insertPosition;
        if (desiredPriority <= 0 || desiredPriority > regularChests.size() + 1) {
            insertPosition = regularChests.size() + 1; // Add to end
        } else {
            insertPosition = desiredPriority;
        }

        // Create new priority assignments
        int priority = 1;
        for (ChestEntry chest : regularChests) {
            if (priority == insertPosition) {
                // Skip this priority number for the new chest
                priority++;
            }
            chest.config.priority = priority++;
        }

        // Set the new chest's priority
        newConfig.priority = insertPosition;
        allConfigs.put(newChestPos, newConfig);

        // Final recalculation to ensure everything is clean
        return recalculatePriorities(allConfigs);
    }

    /**
     * Remove a chest and compact priorities
     */
    public Map<BlockPos, Integer> removeChest(BlockPos removedPos, Map<BlockPos, ChestConfig> allConfigs) {
        ChestConfig removed = allConfigs.remove(removedPos);
        if (removed == null) return priorityCache;

        // Simply recalculate to ensure sequential priorities
        return recalculatePriorities(allConfigs);
    }

    /**
     * Move a chest to a new priority position
     */
    public Map<BlockPos, Integer> moveChest(BlockPos chestPos, int newPriority, Map<BlockPos, ChestConfig> allConfigs) {
        ChestConfig movingChest = allConfigs.get(chestPos);
        if (movingChest == null || movingChest.filterMode == ChestConfig.FilterMode.CUSTOM) {
            return priorityCache; // Can't move custom chests
        }

        int oldPriority = movingChest.priority;
        if (oldPriority == newPriority) return priorityCache;

        // Get all regular chests
        List<ChestEntry> regularChests = new ArrayList<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode != ChestConfig.FilterMode.CUSTOM) {
                regularChests.add(new ChestEntry(entry.getKey(), entry.getValue()));
            }
        }

        // Sort by current priority
        regularChests.sort(Comparator.comparingInt(e -> e.config.priority));

        // Remove the moving chest from the list
        regularChests.removeIf(e -> e.pos.equals(chestPos));

        // Insert at new position
        int insertIndex = Math.min(Math.max(0, newPriority - 1), regularChests.size());
        regularChests.add(insertIndex, new ChestEntry(chestPos, movingChest));

        // Reassign priorities
        int priority = 1;
        for (ChestEntry chest : regularChests) {
            chest.config.priority = priority++;
        }

        return recalculatePriorities(allConfigs);
    }

    /**
     * Get the appropriate priority for a SimplePriority selection
     */
    public int getInsertionPriority(ChestConfig.SimplePriority simplePriority, int totalRegularChests) {
        if (totalRegularChests == 0) return 1;

        return switch (simplePriority) {
            case HIGHEST -> 1; // Insert at beginning
            case HIGH -> Math.max(1, (totalRegularChests + 3) / 4); // 25% position
            case MEDIUM -> Math.max(1, (totalRegularChests + 1) / 2); // 50% position
            case LOW -> Math.max(1, (totalRegularChests * 3 + 3) / 4); // 75% position
            case LOWEST -> totalRegularChests + 1; // Insert at end
        };
    }

    /**
     * Check if priorities need recalculation
     */
    public boolean isDirty() {
        return isDirty;
    }

    /**
     * Mark as needing recalculation
     */
    public void markDirty() {
        isDirty = true;
    }

    /**
     * Get cached priority for a chest
     */
    public int getCachedPriority(BlockPos pos) {
        return priorityCache.getOrDefault(pos, -1);
    }

    /**
     * Get total number of regular (non-custom) chests
     */
    public int getRegularChestCount(Map<BlockPos, ChestConfig> allConfigs) {
        return (int) allConfigs.values().stream()
                .filter(config -> config.filterMode != ChestConfig.FilterMode.CUSTOM)
                .count();
    }
}