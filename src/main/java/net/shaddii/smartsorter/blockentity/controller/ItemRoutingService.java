package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.util.ChestConfig;

import java.util.*;

/**
 * Handles smart item routing with priority-based insertion.
 * Optimized with probe caching and batch operations.
 */
public class ItemRoutingService {
    private static final InsertionResult EMPTY_SUCCESS =
            new InsertionResult(ItemStack.EMPTY, false, null, null);

    private final ProbeRegistry probeRegistry;

    // Cache to avoid repeated BlockEntity lookups
    private final Map<BlockPos, OutputProbeBlockEntity> probeCache = new HashMap<>();

    public ItemRoutingService(ProbeRegistry probeRegistry) {
        this.probeRegistry = probeRegistry;
    }

    /**
     * Inserts item into network using smart routing.
     * Two-phase approach: filtered destinations first, then overflow.
     */
    public InsertionResult insertItem(World world, ItemStack stack) {
        if (world == null || stack.isEmpty()) {
            return new InsertionResult(stack, false, null, null);
        }

        probeCache.clear(); // Clear cache for this operation

        ItemVariant variant = ItemVariant.of(stack);
        ItemStack remaining = stack.copy();

        BlockPos insertedInto = null;
        String insertedIntoName = null;
        boolean potentialOverflow = false;

        // Phase 1: High-priority & filtered destinations
        List<BlockPos> sortedProbes = probeRegistry.getSortedProbes(world);

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

            if (probe.accepts(variant)) {
                int inserted = insertIntoInventory(world, probe, remaining);

                if (inserted > 0 && insertedInto == null) {
                    insertedInto = probe.getTargetPos();
                    insertedIntoName = getChestDisplayName(config);
                }

                remaining.decrement(inserted);
            }
        }

        if (!remaining.isEmpty()) {
            potentialOverflow = true;
        } else {
            return new InsertionResult(ItemStack.EMPTY, false, insertedInto, insertedIntoName);
        }

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
                int inserted = insertIntoInventory(world, probe, remaining);

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
     * Extracts item from network.
     * Optimized: Uses location index to only check relevant probes.
     */
    public ItemStack extractItem(World world, ItemVariant variant, int amount,
                                 NetworkInventoryManager networkManager) {
        if (world == null || amount <= 0) return ItemStack.EMPTY;

        List<BlockPos> probesWithItem = networkManager.getProbesWithItem(variant);
        if (probesWithItem.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int remaining = amount;

        for (BlockPos probePos : new ArrayList<>(probesWithItem)) {
            if (remaining <= 0) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            int extracted = extractFromInventory(world, probe, variant, remaining);
            remaining -= extracted;
        }

        int totalExtracted = amount - remaining;
        return totalExtracted > 0 ? variant.toStack(totalExtracted) : ItemStack.EMPTY;
    }

    /**
     * Inserts items into inventory using two-pass strategy.
     * Pass 1: Stack existing items
     * Pass 2: Fill empty slots
     */
    private int insertIntoInventory(World world, OutputProbeBlockEntity probe, ItemStack stack) {
        Inventory inv = probe.getTargetInventory();
        if (inv == null) return 0;

        int originalCount = stack.getCount();

        // Pass 1: Stack with existing items
        for (int i = 0; i < inv.size(); i++) {
            if (stack.isEmpty()) break;

            ItemStack slotStack = inv.getStack(i);
            if (slotStack.isEmpty()) continue;

            if (ItemStack.areItemsAndComponentsEqual(slotStack, stack)) {
                int maxStack = Math.min(slotStack.getMaxCount(), inv.getMaxCountPerStack());
                int canAdd = maxStack - slotStack.getCount();

                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, stack.getCount());
                    slotStack.increment(toAdd);
                    stack.decrement(toAdd);
                    inv.markDirty();
                }
            }
        }

        // Pass 2: Fill empty slots
        for (int i = 0; i < inv.size(); i++) {
            if (stack.isEmpty()) break;

            ItemStack slotStack = inv.getStack(i);
            if (!slotStack.isEmpty()) continue;

            int maxStack = Math.min(stack.getMaxCount(), inv.getMaxCountPerStack());
            int toAdd = Math.min(maxStack, stack.getCount());

            ItemStack newStack = stack.copy();
            newStack.setCount(toAdd);
            inv.setStack(i, newStack);
            stack.decrement(toAdd);
            inv.markDirty();
        }

        return originalCount - stack.getCount();
    }

    /**
     * Extracts items from inventory.
     */
    private int extractFromInventory(World world, OutputProbeBlockEntity probe,
                                     ItemVariant variant, int amount) {
        Inventory inv = probe.getTargetInventory();
        if (inv == null) return 0;

        int extracted = 0;

        for (int i = 0; i < inv.size(); i++) {
            if (extracted >= amount) break;

            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            ItemVariant stackVariant = ItemVariant.of(stack);
            if (!stackVariant.equals(variant)) continue;

            int toExtract = Math.min(amount - extracted, stack.getCount());
            stack.decrement(toExtract);
            inv.markDirty();
            extracted += toExtract;
        }

        return extracted;
    }

    /**
     * Cached probe lookup to avoid repeated BlockEntity fetches.
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