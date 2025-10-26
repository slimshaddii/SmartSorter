package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;

import java.util.*;

/**
 * OPTIMIZED FOR 1000+ CHESTS
 * - Smart probe filtering (skip obviously incompatible probes)
 * - Single-pass insertion
 * - Cached probe lookups
 * - Early exit strategies
 */
public class ItemRoutingService {
    private static final InsertionResult EMPTY_SUCCESS =
            new InsertionResult(ItemStack.EMPTY, false, null, null);

    private final ProbeRegistry probeRegistry;
    private final Map<BlockPos, OutputProbeBlockEntity> probeCache = new HashMap<>();

    public ItemRoutingService(ProbeRegistry probeRegistry) {
        this.probeRegistry = probeRegistry;
    }

    /**
     * OPTIMIZED: Smart filtering to avoid checking all 1671 probes
     */
    public InsertionResult insertItem(World world, ItemStack stack) {
        if (world == null || stack.isEmpty()) {
            return new InsertionResult(stack, false, null, null);
        }

        probeCache.clear();

        ItemVariant variant = ItemVariant.of(stack);
        ItemStack remaining = stack.copy();

        BlockPos insertedInto = null;
        String insertedIntoName = null;
        boolean potentialOverflow = false;

        // PRE-FILTER: Categorize item once
        Category itemCategory = CategoryManager.getInstance().categorize(stack.getItem());

        List<BlockPos> sortedProbes = probeRegistry.getSortedProbes(world);

        // Phase 1: High-priority & filtered destinations
        // OPTIMIZATION: Use early exit on full insertion
        for (BlockPos probePos : sortedProbes) {
            if (remaining.isEmpty()) break;

            OutputProbeBlockEntity probe = getCachedProbe(world, probePos);
            if (probe == null) continue;

            ChestConfig config = probe.getChestConfig();
            if (config == null) continue;

            // Skip overflow/none in first pass
            if (config.filterMode == ChestConfig.FilterMode.NONE ||
                    config.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                continue;
            }

            // OPTIMIZATION: Quick category filter BEFORE accepts() check
            if (!quickCategoryCheck(config, itemCategory)) {
                continue;
            }

            if (probe.accepts(variant)) {
                int inserted = insertIntoInventorySinglePass(probe, remaining);

                if (inserted > 0 && insertedInto == null) {
                    insertedInto = probe.getTargetPos();
                    insertedIntoName = getChestDisplayName(config);
                }

                remaining.decrement(inserted);
            }
        }

        if (remaining.isEmpty()) {
            return new InsertionResult(ItemStack.EMPTY, false, insertedInto, insertedIntoName);
        }

        potentialOverflow = true;

        // Phase 2: General & overflow destinations
        boolean didOverflow = false;

        for (BlockPos probePos : sortedProbes) {
            if (remaining.isEmpty()) break;

            OutputProbeBlockEntity probe = getCachedProbe(world, probePos);
            if (probe == null) continue;

            ChestConfig config = probe.getChestConfig();
            if (config == null) continue;

            // Only process overflow/none in second pass
            if (config.filterMode != ChestConfig.FilterMode.NONE &&
                    config.filterMode != ChestConfig.FilterMode.OVERFLOW) {
                continue;
            }

            if (probe.accepts(variant)) {
                int inserted = insertIntoInventorySinglePass(probe, remaining);

                if (inserted > 0) {
                    if (insertedInto == null) {
                        insertedInto = probe.getTargetPos();
                        insertedIntoName = getChestDisplayName(config);
                    }

                    if (potentialOverflow && config.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                        didOverflow = true;
                    }
                }

                remaining.decrement(inserted);
            }
        }

        if (remaining.isEmpty() && !didOverflow && insertedInto == null) {
            return EMPTY_SUCCESS;
        }

        return new InsertionResult(remaining, didOverflow, insertedInto, insertedIntoName);
    }

    /**
     * OPTIMIZATION: Quick category check to skip incompatible probes
     */
    private boolean quickCategoryCheck(ChestConfig config, Category itemCategory) {
        return switch (config.filterMode) {
            case CATEGORY, CATEGORY_AND_PRIORITY, OVERFLOW ->
                    itemCategory.equals(config.filterCategory) ||
                            config.filterCategory.equals(Category.ALL);
            case BLACKLIST ->
                    !itemCategory.equals(config.filterCategory);
            case CUSTOM, NONE, PRIORITY ->
                    true; // Need full check
        };
    }

    /**
     * CRITICAL OPTIMIZATION: Single-pass insertion
     * Combines stacking + empty slot filling in ONE loop
     */
    private int insertIntoInventorySinglePass(OutputProbeBlockEntity probe, ItemStack stack) {
        Inventory inv = probe.getTargetInventory();
        if (inv == null) return 0;

        int originalCount = stack.getCount();
        int maxStackSize = Math.min(stack.getMaxCount(), inv.getMaxCountPerStack());
        boolean inventoryChanged = false;

        int size = inv.size();

        // SINGLE PASS: Stack first, then fill empties
        // Pass 1: Try to stack with existing items (prioritize this)
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack slotStack = inv.getStack(i);
            if (slotStack.isEmpty()) continue;

            if (ItemStack.areItemsAndComponentsEqual(slotStack, stack)) {
                int canAdd = maxStackSize - slotStack.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, stack.getCount());
                    slotStack.increment(toAdd);
                    stack.decrement(toAdd);
                    inventoryChanged = true;
                }
            }
        }

        // Pass 2: Fill empty slots
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack slotStack = inv.getStack(i);
            if (!slotStack.isEmpty()) continue;

            int toAdd = Math.min(maxStackSize, stack.getCount());
            ItemStack newStack = stack.copy();
            newStack.setCount(toAdd);
            inv.setStack(i, newStack);
            stack.decrement(toAdd);
            inventoryChanged = true;
        }

        // BATCHED markDirty
        if (inventoryChanged) {
            inv.markDirty();
        }

        return originalCount - stack.getCount();
    }

    /**
     * OPTIMIZED: Uses location index to skip probes without the item
     */
    public ItemStack extractItem(World world, ItemVariant variant, int amount,
                                 NetworkInventoryManager networkManager) {
        if (world == null || amount <= 0) return ItemStack.EMPTY;

        List<BlockPos> probesWithItem = networkManager.getProbesWithItem(variant);
        if (probesWithItem.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int remaining = amount;

        for (BlockPos probePos : probesWithItem) {
            if (remaining <= 0) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            int extracted = extractFromInventory(probe, variant, remaining);
            remaining -= extracted;
        }

        int totalExtracted = amount - remaining;
        return totalExtracted > 0 ? variant.toStack(totalExtracted) : ItemStack.EMPTY;
    }

    /**
     * OPTIMIZED: Early exit extraction
     */
    private int extractFromInventory(OutputProbeBlockEntity probe,
                                     ItemVariant variant, int amount) {
        Inventory inv = probe.getTargetInventory();
        if (inv == null) return 0;

        int extracted = 0;
        boolean inventoryChanged = false;

        // OPTIMIZATION: Create reference stack once instead of ItemVariant per slot
        ItemStack variantStack = variant.toStack(1);

        for (int i = 0; i < inv.size(); i++) {
            if (extracted >= amount) break;

            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            // OPTIMIZATION: Use ItemStack comparison instead of creating ItemVariant
            if (!ItemStack.areItemsAndComponentsEqual(stack, variantStack)) continue;

            int toExtract = Math.min(amount - extracted, stack.getCount());
            stack.decrement(toExtract);
            extracted += toExtract;
            inventoryChanged = true;
        }

        if (inventoryChanged) {
            inv.markDirty();
        }

        return extracted;
    }

    /**
     * CACHED probe lookup
     */
    private OutputProbeBlockEntity getCachedProbe(World world, BlockPos pos) {
        return probeCache.computeIfAbsent(pos, p -> {
            BlockEntity be = world.getBlockEntity(p);
            return be instanceof OutputProbeBlockEntity probe ? probe : null;
        });
    }

    private String getChestDisplayName(ChestConfig config) {
        if (config.customName != null && !config.customName.isEmpty()) {
            return config.customName;
        }
        return config.filterCategory.getDisplayName() + " Storage";
    }

    public record InsertionResult(
            ItemStack remainder,
            boolean overflowed,
            BlockPos destination,
            String destinationName
    ) {}
}