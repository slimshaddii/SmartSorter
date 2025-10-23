package net.shaddii.smartsorter.util;

import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChestPriorityManager {
    private static final int CUSTOM_PRIORITY = 0;

    private final Map<BlockPos, Integer> priorityCache = new ConcurrentHashMap<>();
    private boolean isDirty = true;

    /**
     * Convert SimplePriority to numeric position (1-based)
     * Divides chest count into 5 buckets
     */
    public int getInsertionPriority(ChestConfig.SimplePriority simplePriority, int totalRegularChests) {
        if (totalRegularChests == 0) return 1;

        // Calculate bucket size (minimum 1)
        int bucketSize = Math.max(1, totalRegularChests / 5);

        return switch (simplePriority) {
            case HIGHEST -> 1; // Always first
            case HIGH -> Math.max(1, bucketSize); // ~20% position
            case MEDIUM -> Math.max(1, bucketSize * 2); // ~40% position (middle of first half)
            case LOW -> Math.max(1, bucketSize * 3); // ~60% position
            case LOWEST -> totalRegularChests + 1; // Always last (new chest goes at end)
        };
    }

    /**
     * Insert a new chest with dynamic priority shifting
     */
    public Map<BlockPos, Integer> insertChest(BlockPos newChestPos, ChestConfig newConfig,
                                              int desiredPriority, Map<BlockPos, ChestConfig> allConfigs) {
        // Handle CUSTOM filter mode - always priority 0
        if (newConfig.filterMode == ChestConfig.FilterMode.CUSTOM) {
            newConfig.priority = CUSTOM_PRIORITY;
            newConfig.updateHiddenPriority();
            allConfigs.put(newChestPos, newConfig);
            return recalculatePriorities(allConfigs);
        }

        // Get all regular chests (excluding new one and custom chests)
        List<ChestEntry> regularChests = new ArrayList<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (!entry.getKey().equals(newChestPos) &&
                    entry.getValue().filterMode != ChestConfig.FilterMode.CUSTOM) {
                regularChests.add(new ChestEntry(entry.getKey(), entry.getValue()));
            }
        }

        // Sort by current priority
        regularChests.sort(Comparator.comparingInt(e -> e.config.priority));

        // Validate insertion position (1-based, between 1 and size+1)
        int insertPosition = Math.max(1, Math.min(desiredPriority, regularChests.size() + 1));

        // Insert at position (convert to 0-based index)
        regularChests.add(insertPosition - 1, new ChestEntry(newChestPos, newConfig));

        // Reassign sequential priorities and update SimplePriority
        int priority = 1;
        for (ChestEntry chest : regularChests) {
            chest.config.priority = priority;

            // ONLY set SimplePriority if null (new chest) or recalculating
            if (chest.config.simplePrioritySelection == null) {
                chest.config.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                        priority, regularChests.size()
                );
            }

            chest.config.updateHiddenPriority();
            priority++;
        }

        // Update the main config map
        Map<BlockPos, Integer> result = new HashMap<>();
        for (ChestEntry entry : regularChests) {
            allConfigs.put(entry.pos, entry.config);
            priorityCache.put(entry.pos, entry.config.priority);
        }

        // Update custom chests in cache
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) {
                priorityCache.put(entry.getKey(), CUSTOM_PRIORITY);
                result.put(entry.getKey(), CUSTOM_PRIORITY);
            }
        }

        for (ChestEntry entry : regularChests) {
            result.put(entry.pos, entry.config.priority);
        }

        isDirty = false;
        return result;
    }

    /**
     * Remove a chest and compact priorities
     */
    public Map<BlockPos, Integer> removeChest(BlockPos removedPos, Map<BlockPos, ChestConfig> allConfigs) {
        ChestConfig removed = allConfigs.remove(removedPos);
        if (removed == null) return new HashMap<>(priorityCache);

        return recalculatePriorities(allConfigs);
    }

    /**
     * Move a chest to a new priority position
     */
    public Map<BlockPos, Integer> moveChest(BlockPos chestPos, int newPriority, Map<BlockPos, ChestConfig> allConfigs) {
        ChestConfig movingChest = allConfigs.get(chestPos);
        if (movingChest == null || movingChest.filterMode == ChestConfig.FilterMode.CUSTOM) {
            return new HashMap<>(priorityCache);
        }

        int oldPriority = movingChest.priority;
        int regularCount = getRegularChestCount(allConfigs);

        // Validate new priority
        int validatedPriority = Math.max(1, Math.min(newPriority, regularCount));

        if (oldPriority == validatedPriority) {
            return new HashMap<>(priorityCache);
        }

        // Get all regular chests sorted by current priority
        List<ChestEntry> regularChests = new ArrayList<>();
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode != ChestConfig.FilterMode.CUSTOM) {
                regularChests.add(new ChestEntry(entry.getKey(), entry.getValue()));
            }
        }
        regularChests.sort(Comparator.comparingInt(e -> e.config.priority));

        // Remove the moving chest FIRST
        ChestEntry movingEntry = null;
        Iterator<ChestEntry> it = regularChests.iterator();
        while (it.hasNext()) {
            ChestEntry entry = it.next();
            if (entry.pos.equals(chestPos)) {
                movingEntry = entry;
                it.remove();
                break;
            }
        }

        if (movingEntry == null) return new HashMap<>(priorityCache);

        // Insert at the EXACT new position (0-based index = priority - 1)
        // Since we removed the chest, the list is now 1 smaller
        int insertIndex = Math.max(0, Math.min(validatedPriority - 1, regularChests.size()));
        regularChests.add(insertIndex, movingEntry);

        // Reassign sequential priorities and SimplePriority
        int priority = 1;
        for (ChestEntry chest : regularChests) {
            chest.config.priority = priority;

            // ONLY set SimplePriority if null
            if (chest.config.simplePrioritySelection == null) {
                chest.config.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                        priority, regularChests.size()
                );
            }

            chest.config.updateHiddenPriority();
            priority++;
        }

        // Update main config map AND cache directly (no redundant recalculation)
        Map<BlockPos, Integer> result = new HashMap<>();
        for (ChestEntry entry : regularChests) {
            allConfigs.put(entry.pos, entry.config);
            priorityCache.put(entry.pos, entry.config.priority);
            result.put(entry.pos, entry.config.priority);
        }

        // Update custom chests in cache
        for (Map.Entry<BlockPos, ChestConfig> entry : allConfigs.entrySet()) {
            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) {
                priorityCache.put(entry.getKey(), CUSTOM_PRIORITY);
                result.put(entry.getKey(), CUSTOM_PRIORITY);
            }
        }

        isDirty = false;
        return result;

    }

    /**
     * Recalculate all priorities, ensuring they are sequential
     */
    public Map<BlockPos, Integer> recalculatePriorities(Map<BlockPos, ChestConfig> chestConfigs) {
        priorityCache.clear();

        List<ChestEntry> customChests = new ArrayList<>();
        List<ChestEntry> regularChests = new ArrayList<>();

        // Separate custom and regular chests
        for (Map.Entry<BlockPos, ChestConfig> entry : chestConfigs.entrySet()) {
            ChestEntry chestEntry = new ChestEntry(entry.getKey(), entry.getValue());

            if (entry.getValue().filterMode == ChestConfig.FilterMode.CUSTOM) {
                customChests.add(chestEntry);
            } else {
                regularChests.add(chestEntry);
            }
        }

        // Sort custom chests by position for stable order
        customChests.sort(Comparator.comparing(a -> a.pos.toString()));

        // Sort regular chests by their current priority
        regularChests.sort(Comparator.comparingInt(a -> a.config.priority));

        // Process custom chests (all get priority 0)
        for (ChestEntry chest : customChests) {
            chest.config.priority = CUSTOM_PRIORITY;
            chest.config.updateHiddenPriority();
            priorityCache.put(chest.pos, CUSTOM_PRIORITY);
        }

        // Process regular chests with sequential priorities
        int currentPriority = 1;
        for (ChestEntry chest : regularChests) {
            chest.config.priority = currentPriority;

            // ONLY update SimplePriority if it's null (defensive)
            if (chest.config.simplePrioritySelection == null) {
                chest.config.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                        currentPriority, regularChests.size()
                );
            }

            chest.config.updateHiddenPriority();
            priorityCache.put(chest.pos, currentPriority);
            currentPriority++;
        }

        isDirty = false;
        return new HashMap<>(priorityCache);
    }

    // Utility methods
    public boolean isDirty() {
        return isDirty;
    }

    public void markDirty() {
        isDirty = true;
    }

    public int getCachedPriority(BlockPos pos) {
        return priorityCache.getOrDefault(pos, -1);
    }

    public int getRegularChestCount(Map<BlockPos, ChestConfig> allConfigs) {
        return (int) allConfigs.values().stream()
                .filter(config -> config.filterMode != ChestConfig.FilterMode.CUSTOM)
                .count();
    }

    public Map<BlockPos, Integer> getPriorityCache() {
        return new HashMap<>(priorityCache);
    }

    // Inner class
    private static class ChestEntry {
        final BlockPos pos;
        final ChestConfig config;

        ChestEntry(BlockPos pos, ChestConfig config) {
            this.pos = pos;
            this.config = config;
        }
    }
}