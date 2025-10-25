package net.shaddii.smartsorter.blockentity.controller;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.util.ChestConfig;

import java.util.*;

/**
 * Manages output probe registration and sorted ordering.
 * Optimized with lazy cache invalidation.
 */
public class ProbeRegistry {
    private final List<BlockPos> linkedProbes = new ArrayList<>();

    // Cache for sorted probe list
    private List<BlockPos> sortedProbesCache;
    private boolean sortCacheDirty = true;

    public boolean addProbe(BlockPos probePos) {
        if (linkedProbes.contains(probePos)) {
            return false;
        }

        linkedProbes.add(probePos);
        invalidateCache();
        return true;
    }

    public boolean removeProbe(BlockPos probePos) {
        boolean removed = linkedProbes.remove(probePos);
        if (removed) {
            invalidateCache();
        }
        return removed;
    }

    /**
     * Gets probes sorted by priority (cached).
     * Sort order: hiddenPriority DESC → hasItems DESC → mode ASC
     */
    public List<BlockPos> getSortedProbes(World world) {
        if (!sortCacheDirty && sortedProbesCache != null) {
            return sortedProbesCache;
        }

        List<ProbeEntry> entries = new ArrayList<>(linkedProbes.size());

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                BlockPos chestPos = probe.getTargetPos();
                ChestConfig config = chestPos != null ? probe.getChestConfig() : null;

                entries.add(new ProbeEntry(
                        probePos,
                        probe.mode,
                        config,
                        hasItemsInChest(world, probePos, probe)
                ));
            }
        }

        // Multi-tier sorting
        entries.sort((a, b) -> {
            // 1. Priority (higher first)
            int aPri = a.config != null ? a.config.hiddenPriority : 0;
            int bPri = b.config != null ? b.config.hiddenPriority : 0;
            if (aPri != bPri) return Integer.compare(bPri, aPri);

            // 2. Has items (occupied chests first)
            if (a.hasItems != b.hasItems) return a.hasItems ? -1 : 1;

            // 3. Mode order
            return Integer.compare(getModeOrder(a.mode), getModeOrder(b.mode));
        });

        sortedProbesCache = new ArrayList<>(entries.size());
        for (ProbeEntry entry : entries) {
            sortedProbesCache.add(entry.pos);
        }

        sortCacheDirty = false;
        return sortedProbesCache;
    }

    public List<BlockPos> getLinkedProbes() {
        return new ArrayList<>(linkedProbes);
    }

    public int getProbeCount() {
        return linkedProbes.size();
    }

    public void invalidateCache() {
        sortCacheDirty = true;
    }

    /**
     * Validates all probes, removing invalid ones.
     */
    public void validate(World world) {
        linkedProbes.removeIf(probePos -> {
            BlockEntity be = world.getBlockEntity(probePos);
            return !(be instanceof OutputProbeBlockEntity);
        });
    }

    private boolean hasItemsInChest(World world, BlockPos probePos, OutputProbeBlockEntity probe) {
        Inventory inv = probe.getTargetInventory();
        if (inv == null) return false;

        for (int i = 0; i < inv.size(); i++) {
            if (!inv.getStack(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int getModeOrder(OutputProbeBlockEntity.ProbeMode mode) {
        return switch (mode) {
            case FILTER -> 0;
            case PRIORITY -> 1;
            case ACCEPT_ALL -> 2;
        };
    }

    private record ProbeEntry(
            BlockPos pos,
            OutputProbeBlockEntity.ProbeMode mode,
            ChestConfig config,
            boolean hasItems
    ) {}
}