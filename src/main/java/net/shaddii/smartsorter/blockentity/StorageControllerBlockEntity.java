package net.shaddii.smartsorter.blockentity;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage Controller Block Entity
 *
 * Main controller for the network storage system. Manages:
 * - Links to Output Probes (which connect to chests)
 * - Network-wide item cache for GUI display
 * - Smart routing with hidden priority system (FILTER > PRIORITY > ACCEPT_ALL)
 * - Item insertion and extraction across the entire network
 *
 * Future features:
 * - User-configurable priority levels
 * - Sorting options (name, count, type)
 * - Dropdown filtering by category
 * - Cross-dimensional access with chunk load notifications
 */
public class StorageControllerBlockEntity extends BlockEntity implements NamedScreenHandlerFactory, Inventory {

    // ===================================================================
    // FIELDS
    // ===================================================================

    /** All Output Probes linked to this controller */
    private final List<BlockPos> linkedProbes = new ArrayList<>();

    /** Cache of all items in the network (for GUI display) */
    private final Map<ItemVariant, Long> networkItems = new HashMap<>();

    /** Last time the network cache was updated (in game ticks) */
    private long lastCacheUpdate = 0;

    /** How often to update the network cache (in ticks, 20 = 1 second) */
    private static final long CACHE_DURATION = 20;

    // ===================================================================
    // CONSTRUCTOR
    // ===================================================================

    public StorageControllerBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.STORAGE_CONTROLLER_BE_TYPE, pos, state);
    }

    // ===================================================================
    // TICKING & MAINTENANCE
    // ===================================================================

    /**
     * Called every tick on the server
     * Handles periodic maintenance and cache updates
     */
    public static void tick(World world, BlockPos pos, BlockState state, StorageControllerBlockEntity be) {
        if (world.isClient) return;

        // Validate links every 5 seconds (prevents crashes from deleted probes)
        if (world.getTime() % 100 == 0) {
            be.validateLinks();
        }

        // Update network cache periodically (keeps GUI in sync)
        if (world.getTime() - be.lastCacheUpdate >= CACHE_DURATION) {
            be.updateNetworkCache();
            be.lastCacheUpdate = world.getTime();
            be.syncToViewers();
        }
    }

    /**
     * Remove any invalid probe links
     * Prevents crashes when probes are removed via WorldEdit or other mods
     */
    private void validateLinks() {
        linkedProbes.removeIf(probePos -> {
            BlockEntity be = world.getBlockEntity(probePos);
            return !(be instanceof OutputProbeBlockEntity);
        });
    }

    // ===================================================================
    // NETWORK CACHE MANAGEMENT
    // ===================================================================

    /**
     * Force update the network cache
     * Scans all linked probes and tallies up items across the entire network
     * Called when:
     * - GUI requests sync
     * - Items are inserted/extracted
     * - Periodic tick update
     */
    public void updateNetworkCache() {
        networkItems.clear();

        if (world == null) return;

        // Scan all linked probes and their inventories
        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            // Count all items in this probe's inventory
            for (var view : storage) {
                if (view.getAmount() == 0) continue;
                ItemVariant variant = view.getResource();
                if (variant.isBlank()) continue;

                // Merge with existing counts (handles multiple chests with same item)
                networkItems.merge(variant, view.getAmount(), Long::sum);
            }
        }
    }

    /**
     * Send sync packet to all players viewing this controller
     * Only syncs to players who have the controller GUI open (performance optimization)
     */
    private void syncToViewers() {
        if (world instanceof ServerWorld serverWorld) {
            for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                // Only sync to players actually viewing this specific controller
                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler
                        && handler.controller == this) {
                    handler.sendNetworkUpdate(player);
                }
            }
        }
    }

    // ===================================================================
    // PROBE LINKING
    // ===================================================================

    /**
     * Add a linked Output Probe
     * Called when player uses Linking Tool to connect a probe to this controller
     *
     * @param probePos Position of the Output Probe to link
     * @return true if probe was added, false if already linked
     */
    public boolean addProbe(BlockPos probePos) {
        if (!linkedProbes.contains(probePos)) {
            linkedProbes.add(probePos);
            markDirty(); // Save to NBT
            updateNetworkCache(); // Refresh item counts
            return true;
        }
        return false;
    }

    /**
     * Get all linked probes
     * @return List of probe positions (modifiable reference)
     */
    public List<BlockPos> getLinkedProbes() {
        return linkedProbes;
    }

    // ===================================================================
    // NETWORK QUERIES (for GUI)
    // ===================================================================

    /**
     * Get all items currently in the network
     * @return Copy of network items (safe to modify)
     */
    public Map<ItemVariant, Long> getNetworkItems() {
        return new HashMap<>(networkItems);
    }

    /**
     * Calculate total free slots across all linked inventories
     * Used for capacity display in GUI
     *
     * @return Number of empty slots across all chests
     */
    public int calculateTotalFreeSlots() {
        if (world == null) return 0;

        int totalFree = 0;

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            Inventory inv = probe.getTargetInventory();
            if (inv == null) continue;

            // Count empty slots in this inventory
            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isEmpty()) {
                    totalFree++;
                }
            }
        }

        return totalFree;
    }

    /**
     * Calculate total capacity (free + occupied slots)
     *
     * @return Total number of slots across all chests (empty + filled)
     */
    public int calculateTotalCapacity() {
        if (world == null) return 0;

        int totalSlots = 0;

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            Inventory inv = probe.getTargetInventory();
            if (inv != null) {
                totalSlots += inv.size();
            }
        }

        return totalSlots;
    }

    /**
     * Get number of linked inventories
     * @return Count of linked probes
     */
    public int getLinkedInventoryCount() {
        return linkedProbes.size();
    }

    // ===================================================================
    // ITEM EXTRACTION (from network)
    // ===================================================================

    /**
     * Extract items from the network
     * Searches all linked chests for the requested item and removes it
     *
     * @param variant The item type to extract
     * @param amount How many to extract
     * @return ItemStack containing extracted items (or EMPTY if none found)
     */
    public ItemStack extractItem(ItemVariant variant, int amount) {
        if (world == null) return ItemStack.EMPTY;

        int remaining = amount;

        // Try to extract from each linked probe
        for (BlockPos probePos : linkedProbes) {
            if (remaining <= 0) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            // Check if this probe's chest contains this item
            if (!probe.accepts(variant)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            // Try to extract from this chest
            int extracted = extractFromInventory(world, probe, variant, remaining);
            remaining -= extracted;
        }

        int totalExtracted = amount - remaining;
        if (totalExtracted > 0) {
            updateNetworkCache(); // Refresh counts after extraction
            return variant.toStack(totalExtracted);
        }

        return ItemStack.EMPTY;
    }

    /**
     * Extract items directly from an inventory via probe
     * Handles double chests and modded inventories correctly
     *
     * @return Number of items successfully extracted
     */
    private int extractFromInventory(World world, OutputProbeBlockEntity probe, ItemVariant variant, int amount) {
        // Use probe's method to get correct inventory (handles double chests!)
        Inventory inv = probe.getTargetInventory();

        if (inv == null) {
            return 0;
        }

        int extracted = 0;

        // Loop through all slots and extract matching items
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

    // ===================================================================
    // ITEM INSERTION (into network) - SMART ROUTING WITH PRIORITY
    // ===================================================================

    /**
     * Insert items into the network via smart routing
     *
     * HIDDEN PRIORITY SYSTEM (invisible to players):
     * 1. FILTER mode probes are checked first (items go to designated chests)
     * 2. PRIORITY mode probes are checked next (future feature)
     * 3. ACCEPT_ALL mode probes are checked last (overflow/junk chests)
     *
     * This ensures items are sorted correctly:
     * - Diamonds go to diamond chest, not overflow chest
     * - Only when filtered chests are full do items go to ACCEPT_ALL
     *
     * Future: User-configurable priority values will be added here
     *
     * @param stack The items to insert
     * @return Remaining items that couldn't be inserted (or EMPTY if all inserted)
     */
    public ItemStack insertItem(ItemStack stack) {
        if (world == null || stack.isEmpty()) {
            return stack;
        }

        SmartSorter.LOGGER.info("========================================");
        SmartSorter.LOGGER.info("INSERTION START: {} x{}", stack.getItem().getName().getString(), stack.getCount());
        SmartSorter.LOGGER.info("Total linked probes: {}", linkedProbes.size());

        ItemVariant variant = ItemVariant.of(stack);
        int remaining = stack.getCount();

        // IMPORTANT: Get probes sorted by hidden priority (FILTER first, then ACCEPT_ALL)
        List<BlockPos> sortedProbes = getSortedProbesByPriority();

        // Try inserting into probes in priority order
        for (int i = 0; i < sortedProbes.size(); i++) {
            if (remaining <= 0) break;

            BlockPos probePos = sortedProbes.get(i);
            BlockEntity be = world.getBlockEntity(probePos);

            if (!(be instanceof OutputProbeBlockEntity probe)) {
                SmartSorter.LOGGER.warn("#{} - Probe at {} is not OutputProbeBlockEntity", i+1, probePos);
                continue;
            }

            SmartSorter.LOGGER.info("#{} - Checking probe at {} (Mode: {})", i+1, probePos, probe.mode);

            // Check if this probe accepts the item
            boolean accepts = probe.accepts(variant);
            SmartSorter.LOGGER.info("#{} - Accepts {}: {}", i+1, variant.getItem().getName().getString(), accepts);

            if (!accepts) {
                SmartSorter.LOGGER.info("#{} - REJECTED, moving to next probe", i+1);
                continue;
            }

            // Try to insert into this probe's inventory
            ItemStack toInsert = variant.toStack(remaining);
            int inserted = insertIntoInventory(world, probe, toInsert);

            SmartSorter.LOGGER.info("#{} - ACCEPTED! Inserted {} items", i+1, inserted);
            remaining -= inserted;

            if (inserted > 0) {
                SmartSorter.LOGGER.info("Remaining after insertion: {}", remaining);
            }
        }

        SmartSorter.LOGGER.info("INSERTION COMPLETE - Final remaining: {}", remaining);
        SmartSorter.LOGGER.info("========================================");

        // Update cache if something was inserted
        if (remaining != stack.getCount()) {
            updateNetworkCache();
            markDirty();
        }

        return remaining > 0 ? variant.toStack(remaining) : ItemStack.EMPTY;
    }

    /**
     * Get probes sorted by hidden priority system
     *
     * Current order: FILTER > PRIORITY > ACCEPT_ALL
     * This ensures items go to filtered chests first, then overflow to ACCEPT_ALL
     *
     * FUTURE IMPLEMENTATION:
     * When priority GUI is added, this will also sort by user-set priority values:
     * - FILTER mode chests can have priorities 1-10
     * - Higher priority = checked first
     * - Same priority = checked in link order
     *
     * Example future behavior:
     * - Diamond chest (FILTER, priority 10) checked first
     * - Ore chest (FILTER, priority 5) checked second
     * - Junk chest (ACCEPT_ALL, no priority) checked last
     *
     * @return List of probe positions sorted by priority (highest first)
     */
    private List<BlockPos> getSortedProbesByPriority() {
        List<BlockPos> filterProbes = new ArrayList<>();
        List<BlockPos> priorityProbes = new ArrayList<>();
        List<BlockPos> acceptAllProbes = new ArrayList<>();

        SmartSorter.LOGGER.info("--- Sorting probes by priority ---");

        // Categorize probes by mode
        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            switch (probe.mode) {
                case FILTER -> {
                    filterProbes.add(probePos);
                    SmartSorter.LOGGER.info("Added to FILTER list: {}", probePos);
                }
                case PRIORITY -> {
                    priorityProbes.add(probePos);
                    SmartSorter.LOGGER.info("Added to PRIORITY list: {}", probePos);
                }
                case ACCEPT_ALL -> {
                    acceptAllProbes.add(probePos);
                    SmartSorter.LOGGER.info("Added to ACCEPT_ALL list: {}", probePos);
                }
            }
        }

        // FUTURE: Sort each category by user-set priority value
        // Example:
        // filterProbes.sort((a, b) -> {
        //     int priorityA = getProbe(a).getPriority();
        //     int priorityB = getProbe(b).getPriority();
        //     return Integer.compare(priorityB, priorityA); // Higher priority first
        // });

        // Combine in priority order: FILTER first, then PRIORITY, then ACCEPT_ALL
        List<BlockPos> sorted = new ArrayList<>();
        sorted.addAll(filterProbes);    // Highest priority (sorted items)
        sorted.addAll(priorityProbes);  // Medium priority (future use)
        sorted.addAll(acceptAllProbes); // Lowest priority (overflow/junk)

        SmartSorter.LOGGER.info("Final probe order: FILTER({}), PRIORITY({}), ACCEPT_ALL({})",
                filterProbes.size(), priorityProbes.size(), acceptAllProbes.size());

        return sorted;
    }

    /**
     * Called when this controller is removed from the world
     * Clean up to prevent memory leaks
     */
    public void onRemoved() {
        // Clear our probe links
        linkedProbes.clear();

        // Clear cached network items
        networkItems.clear();

        SmartSorter.LOGGER.info("Storage Controller at {} removed and cleaned up", pos);
    }

    /**
     * Insert items directly into an inventory via probe
     * Handles vanilla chests, double chests, and modded inventories
     *
     * Insertion logic:
     * 1. First pass: Try to stack with existing items (fills partial stacks)
     * 2. Second pass: Fill empty slots with new stacks
     *
     * @param world The world
     * @param probe The probe to insert through
     * @param stack The items to insert (will be modified!)
     * @return Number of items successfully inserted
     */
    private int insertIntoInventory(World world, OutputProbeBlockEntity probe, ItemStack stack) {
        // Use the probe's method to get the correct inventory
        // This handles double chests and modded inventories correctly
        Inventory inv = probe.getTargetInventory();

        if (inv == null) {
            SmartSorter.LOGGER.warn("No inventory found for probe at {}", probe.getPos());
            return 0;
        }

        int originalCount = stack.getCount();

        // First pass: Stack with existing items (prioritize filling partial stacks)
        for (int i = 0; i < inv.size(); i++) {
            if (stack.isEmpty()) break;

            ItemStack slotStack = inv.getStack(i);
            if (slotStack.isEmpty()) continue; // Skip empty slots in first pass

            // Check if items can stack together
            if (ItemStack.areItemsAndComponentsEqual(slotStack, stack)) {
                int maxStack = Math.min(slotStack.getMaxCount(), inv.getMaxCountPerStack());
                int canAdd = maxStack - slotStack.getCount();

                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, stack.getCount());
                    slotStack.setCount(slotStack.getCount() + toAdd);
                    stack.decrement(toAdd);
                    inv.markDirty();
                }
            }
        }

        // Second pass: Fill empty slots
        for (int i = 0; i < inv.size(); i++) {
            if (stack.isEmpty()) break;

            ItemStack slotStack = inv.getStack(i);
            if (!slotStack.isEmpty()) continue; // Skip filled slots in second pass

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

    // ===================================================================
    // INVENTORY IMPLEMENTATION (required for ItemScatterer)
    // ===================================================================

    // This controller doesn't actually store items, but implements Inventory
    // so that ItemScatterer can drop items when the block is broken

    @Override
    public int size() {
        return 0; // No internal storage
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        // No-op
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return pos.isWithinDistance(player.getPos(), 8.0);
    }

    @Override
    public void clear() {
        // No-op
    }

    // ===================================================================
    // SCREEN HANDLER FACTORY (for GUI)
    // ===================================================================

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.smartsorter.storage_controller");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new StorageControllerScreenHandler(syncId, playerInventory, this);
    }

    // ===================================================================
    // NBT SERIALIZATION (save/load from disk)
    // ===================================================================

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);

        // Save all linked probe positions
        nbt.putInt("probe_count", linkedProbes.size());
        for (int i = 0; i < linkedProbes.size(); i++) {
            nbt.putLong("probe_" + i, linkedProbes.get(i).asLong());
        }

        // Note: Network cache is NOT saved (rebuilt on world load)
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);

        // Load all linked probe positions
        linkedProbes.clear();
        int count = nbt.getInt("probe_count");
        for (int i = 0; i < count; i++) {
            linkedProbes.add(BlockPos.fromLong(nbt.getLong("probe_" + i)));
        }

        // Network cache will be rebuilt on first tick
    }

    // ===================================================================
    // NETWORK SYNC (client-server communication)
    // ===================================================================

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
        return createNbt(registryLookup);
    }
}