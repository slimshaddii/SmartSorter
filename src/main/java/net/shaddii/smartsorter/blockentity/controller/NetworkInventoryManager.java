package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;

import java.util.*;

/**
 * Manages network-wide item inventory tracking and caching.
 * Optimized with delta tracking and lazy copy-on-write.
 */
public class NetworkInventoryManager {
    private static final int INITIAL_CAPACITY = 256;
    private static final float LOAD_FACTOR = 0.75f;

    private final Map<ItemVariant, Long> networkItems;
    private final Map<ItemVariant, Long> deltaItems;
    private final Map<ItemVariant, List<BlockPos>> itemLocationIndex;

    private Map<ItemVariant, Long> networkItemsSnapshot;
    private boolean snapshotDirty;

    public NetworkInventoryManager() {
        this.networkItems = new LinkedHashMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
        this.deltaItems = new HashMap<>(64, LOAD_FACTOR);
        this.itemLocationIndex = new HashMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
        this.snapshotDirty = true;
    }

    /**
     * Updates network cache by scanning all probes.
     * Optimized: Reuses maps, batch operations, minimal allocations.
     */
    public void updateCache(World world, List<BlockPos> probes) {
        if (world == null) return;

        // Prepare temporary storage (reuse if possible)
        Map<ItemVariant, Long> newItems = new HashMap<>(networkItems.size());
        itemLocationIndex.clear();

        // Scan all probes
        for (BlockPos probePos : probes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            // Index items from this probe
            for (var view : storage) {
                if (view.getAmount() == 0) continue;

                ItemVariant variant = view.getResource();
                if (variant.isBlank()) continue;

                // Update totals
                newItems.merge(variant, view.getAmount(), Long::sum);

                // Update location index
                itemLocationIndex.computeIfAbsent(variant, k -> new ArrayList<>(4))
                        .add(probePos);
            }
        }

        // Track deltas (optimized: only track changes)
        trackDeltas(newItems);

        // Swap in new data
        networkItems.clear();
        networkItems.putAll(newItems);
        snapshotDirty = true;
    }

    /**
     * Tracks changes between old and new inventory states.
     */
    private void trackDeltas(Map<ItemVariant, Long> newItems) {
        // Check for removed/changed items
        for (Map.Entry<ItemVariant, Long> entry : networkItems.entrySet()) {
            long newAmount = newItems.getOrDefault(entry.getKey(), 0L);
            if (newAmount != entry.getValue()) {
                deltaItems.put(entry.getKey(), newAmount);
            }
        }

        // Check for new items
        for (Map.Entry<ItemVariant, Long> entry : newItems.entrySet()) {
            if (!networkItems.containsKey(entry.getKey())) {
                deltaItems.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Gets immutable snapshot of network items (copy-on-write).
     */
    public Map<ItemVariant, Long> getNetworkItems() {
        if (snapshotDirty || networkItemsSnapshot == null) {
            networkItemsSnapshot = new HashMap<>(networkItems);
            snapshotDirty = false;
        }
        return networkItemsSnapshot;
    }

    /**
     * Gets and clears deltas since last sync.
     */
    public Map<ItemVariant, Long> consumeDeltas() {
        if (deltaItems.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<ItemVariant, Long> deltas = new HashMap<>(deltaItems);
        deltaItems.clear();
        return deltas;
    }

    /**
     * Gets probes that contain a specific item.
     */
    public List<BlockPos> getProbesWithItem(ItemVariant variant) {
        return itemLocationIndex.getOrDefault(variant, Collections.emptyList());
    }

    /**
     * Gets current amount of an item in the network.
     */
    public long getItemAmount(ItemVariant variant) {
        return networkItems.getOrDefault(variant, 0L);
    }

    public boolean hasDeltas() {
        return !deltaItems.isEmpty();
    }

    public void markDirty() {
        snapshotDirty = true;
    }

    /**
     * Applies delta updates to the network items (for client-side sync).
     */
    public void applyDeltas(Map<ItemVariant, Long> deltas) {
        for (Map.Entry<ItemVariant, Long> entry : deltas.entrySet()) {
            if (entry.getValue() <= 0) {
                networkItems.remove(entry.getKey());
            } else {
                networkItems.put(entry.getKey(), entry.getValue());
            }
        }
        snapshotDirty = true; // Invalidate snapshot
    }
}