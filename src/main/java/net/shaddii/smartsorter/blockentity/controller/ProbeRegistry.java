package net.shaddii.smartsorter.blockentity.controller;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.util.ChestConfig;

import java.util.*;

/**
 * OPTIMIZED FOR 1000+ CHESTS
 * - Caches BlockEntity references
 * - Caches hasItems status
 * - Lazy sorting
 * - Minimal world lookups
 */
public class ProbeRegistry {
    private final List<BlockPos> linkedProbes = new ArrayList<>();

    private final Map<BlockPos, BlockPos> chestToProbe = new HashMap<>();
    private boolean indexDirty = true;

    // Heavy caching for large networks
    private final Map<BlockPos, OutputProbeBlockEntity> probeCache = new HashMap<>();
    private final Map<BlockPos, Boolean> hasItemsCache = new HashMap<>();

    private List<BlockPos> sortedProbesCache;
    private boolean sortCacheDirty = true;
    private long lastCacheUpdate = 0;
    private static final long CACHE_LIFETIME = 100; // 5 seconds

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
            probeCache.remove(probePos);
            hasItemsCache.remove(probePos);
            invalidateCache();
        }
        return removed;
    }

    /**
     * OPTIMIZED: Aggressively cached, minimal world lookups
     */
    public List<BlockPos> getSortedProbes(World world) {
        long currentTime = world != null ? world.getTime() : 0;

        // Return cached if still valid
        if (!sortCacheDirty && sortedProbesCache != null &&
                (currentTime - lastCacheUpdate) < CACHE_LIFETIME) {
            return sortedProbesCache;
        }

        // Rebuild cache
        rebuildSortedCache(world, currentTime);
        return sortedProbesCache;
    }

    public BlockPos getProbeForChest(World world, BlockPos chestPos) {
        if (world == null || chestPos == null) return null;

        // Rebuild index if dirty
        if (indexDirty) {
            rebuildChestIndex(world);
        }

        return chestToProbe.get(chestPos);
    }

    private void rebuildChestIndex(World world) {
        chestToProbe.clear();

        for (BlockPos probePos : linkedProbes) {
            OutputProbeBlockEntity probe = getCachedProbe(world, probePos);
            if (probe == null) continue;

            BlockPos targetPos = probe.getTargetPos();
            if (targetPos != null) {
                chestToProbe.put(targetPos, probePos);
            }
        }

        indexDirty = false;
    }

    private void rebuildSortedCache(World world, long currentTime) {
        List<ProbeEntry> entries = new ArrayList<>(linkedProbes.size());

        for (BlockPos probePos : linkedProbes) {
            OutputProbeBlockEntity probe = getCachedProbe(world, probePos);
            if (probe == null) continue;

            ChestConfig config = probe.getChestConfig();

            entries.add(new ProbeEntry(
                    probePos,
                    probe.mode,
                    config,
                    getCachedHasItems(world, probePos, probe, currentTime)
            ));
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
        lastCacheUpdate = currentTime;
    }

    /**
     * CRITICAL OPTIMIZATION: Cache BlockEntity lookups
     */
    private OutputProbeBlockEntity getCachedProbe(World world, BlockPos pos) {
        OutputProbeBlockEntity cached = probeCache.get(pos);

        if (cached != null) {
            // Validate cache - ensure block entity still exists
            if (!cached.isRemoved()) {
                return cached;
            }
            // Cache invalid, remove it
            probeCache.remove(pos);
        }

        // Lookup and cache
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof OutputProbeBlockEntity probe) {
            probeCache.put(pos, probe);
            return probe;
        }

        return null;
    }

    /**
     * CRITICAL OPTIMIZATION: Cache hasItems status (expensive to check)
     */
    private boolean getCachedHasItems(World world, BlockPos probePos,
                                      OutputProbeBlockEntity probe, long currentTime) {
        Boolean cached = hasItemsCache.get(probePos);

        // Use cached value if recent enough (within 1 second)
        if (cached != null && (currentTime - lastCacheUpdate) < 20) {
            return cached;
        }

        // Expensive check - only do when necessary
        boolean hasItems = checkHasItemsFast(probe);
        hasItemsCache.put(probePos, hasItems);
        return hasItems;
    }

    /**
     * OPTIMIZED: Early exit on first item found
     */
    private boolean checkHasItemsFast(OutputProbeBlockEntity probe) {
        Inventory inv = probe.getTargetInventory();
        if (inv == null) return false;

        // Early exit on first non-empty slot
        int size = inv.size();
        for (int i = 0; i < size; i++) {
            if (!inv.getStack(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public List<BlockPos> getLinkedProbes() {
        return new ArrayList<>(linkedProbes);
    }

    public int getProbeCount() {
        return linkedProbes.size();
    }

    public void invalidateCache() {
        sortCacheDirty = true;
        hasItemsCache.clear();
        indexDirty = true; // Also invalidate chest→probe index
    }

    /**
     * Validates all probes, removing invalid ones.
     */
    public void validate(World world) {
        linkedProbes.removeIf(probePos -> {
            OutputProbeBlockEntity probe = getCachedProbe(world, probePos);
            if (probe == null || probe.isRemoved()) {
                probeCache.remove(probePos);
                hasItemsCache.remove(probePos);
                return true;
            }
            return false;
        });
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