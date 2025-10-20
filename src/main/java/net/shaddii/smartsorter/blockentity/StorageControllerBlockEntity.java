package net.shaddii.smartsorter.blockentity;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
//? if >=1.21.8 {
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.storage.NbtReadView;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.ErrorReporter;
//?}
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.network.ProbeStatsSyncPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.List;

/**
 * Storage Controller Block Entity
 *
 * FEATURES:
 * - Network item storage (via probes)
 * - Auto-routing (via intakes)
 * - Process probe management
 * - Chest configuration & naming
 * - XP collection from smelting
 *
 * OPTIMIZATIONS:
 * - Network dirty flag (only updates when items change)
 * - Reduced cache scans by ~98% for idle storage
 * - Only syncs to viewers when data changes
 */
public class StorageControllerBlockEntity extends BlockEntity implements NamedScreenHandlerFactory, Inventory {

    // ===================================================================
    // CONSTANTS & STATIC FIELDS
    // ===================================================================

    private static final long CACHE_DURATION = 20;
    private static final long SYNC_COOLDOWN = 5;

    private static final String[] PROBE_KEYS;
    private static final String[] PP_POS_KEYS;
    private static final String[] PP_NAME_KEYS;
    private static final String[] PP_TYPE_KEYS;
    private static final String[] PP_ENABLED_KEYS;
    private static final String[] PP_RECIPE_KEYS;
    private static final String[] PP_FUEL_KEYS;
    private static final String[] PP_PROCESSED_KEYS;
    private static final String[] PP_INDEX_KEYS;

    private static final String[] CHEST_POS_KEYS;
    private static final String[] CHEST_NAME_KEYS;
    private static final String[] CHEST_CATEGORY_KEYS;
    private static final String[] CHEST_PRIORITY_KEYS;
    private static final String[] CHEST_MODE_KEYS;
    private static final String[] CHEST_FRAME_KEYS;

    static {
        PROBE_KEYS = new String[256];
        PP_POS_KEYS = new String[256];
        PP_NAME_KEYS = new String[256];
        PP_TYPE_KEYS = new String[256];
        PP_ENABLED_KEYS = new String[256];
        PP_RECIPE_KEYS = new String[256];
        PP_FUEL_KEYS = new String[256];
        PP_PROCESSED_KEYS = new String[256];
        PP_INDEX_KEYS = new String[256];

        CHEST_POS_KEYS = new String[256];
        CHEST_NAME_KEYS = new String[256];
        CHEST_CATEGORY_KEYS = new String[256];
        CHEST_PRIORITY_KEYS = new String[256];
        CHEST_MODE_KEYS = new String[256];
        CHEST_FRAME_KEYS = new String[256];

        for (int i = 0; i < 256; i++) {
            PROBE_KEYS[i] = "probe_" + i;
            PP_POS_KEYS[i] = "pp_pos_" + i;
            PP_NAME_KEYS[i] = "pp_name_" + i;
            PP_TYPE_KEYS[i] = "pp_type_" + i;
            PP_ENABLED_KEYS[i] = "pp_enabled_" + i;
            PP_RECIPE_KEYS[i] = "pp_recipe_" + i;
            PP_FUEL_KEYS[i] = "pp_fuel_" + i;
            PP_PROCESSED_KEYS[i] = "pp_processed_" + i;
            PP_INDEX_KEYS[i] = "pp_index_" + i;

            CHEST_POS_KEYS[i] = "chest_pos_" + i;
            CHEST_NAME_KEYS[i] = "chest_name_" + i;
            CHEST_CATEGORY_KEYS[i] = "chest_cat_" + i;
            CHEST_PRIORITY_KEYS[i] = "chest_pri_" + i;
            CHEST_MODE_KEYS[i] = "chest_mode_" + i;
            CHEST_FRAME_KEYS[i] = "chest_frame_" + i;
        }
    }

    // ===================================================================
    // FIELDS
    // ===================================================================

    // Linked blocks
    private final List<BlockPos> linkedProbes = new ArrayList<>();
    private final List<BlockPos> linkedIntakes = new ArrayList<>();

    // Network storage
    private final Map<ItemVariant, Long> networkItems = new LinkedHashMap<>();
    private Map<ItemVariant, Long> networkItemsCopy = null;
    private boolean networkItemsCopyDirty = true;

    // Process probes
    private final Map<BlockPos, ProcessProbeConfig> linkedProcessProbes = new LinkedHashMap<>();
    private int storedExperience = 0;

    // Chest configs
    private final Map<BlockPos, ChestConfig> chestConfigs = new LinkedHashMap<>();

    // Cache & optimization
    private boolean networkDirty = true;
    private long lastCacheUpdate = 0;
    private long lastSyncTime = 0;
    private List<BlockPos> sortedProbesCache = null;
    private boolean probeOrderDirty = true;
    private boolean needsValidation = true;


    // ===================================================================
    // CONSTRUCTOR & TICK
    // ===================================================================

    public StorageControllerBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.STORAGE_CONTROLLER_BE_TYPE, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, StorageControllerBlockEntity be) {
        if (world.isClient()) return;

        // Validate links every 5 seconds
        if (world.getTime() % 100 == 0) {
            be.validateLinks();
        }

        // Clear tag cache every 5 minutes
        if (world.getTime() % 6000 == 0) {
            SortUtil.clearTagCache();
        }

        // OPTIMIZATION: Only update if marked dirty
        if (be.networkDirty && world.getTime() - be.lastCacheUpdate >= CACHE_DURATION) {
            be.updateNetworkCache();
            be.lastCacheUpdate = world.getTime();
            be.syncToViewers();
            be.networkDirty = false;
        }
    }

    private void validateLinks() {
        linkedProbes.removeIf(probePos -> {
            BlockEntity be = world.getBlockEntity(probePos);
            return !(be instanceof OutputProbeBlockEntity);
        });

        linkedIntakes.removeIf(intakePos -> {
            BlockEntity be = world.getBlockEntity(intakePos);
            return !(be instanceof IntakeBlockEntity);
        });
    }

    // ===================================================================
    // NETWORK CACHE & SYNC
    // ===================================================================

    public void updateNetworkCache() {
        networkItems.clear();
        if (world == null) return;

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            for (var view : storage) {
                if (view.getAmount() == 0) continue;
                ItemVariant variant = view.getResource();
                if (variant.isBlank()) continue;

                networkItems.merge(variant, view.getAmount(), Long::sum);
            }
        }
        networkItemsCopyDirty = true;
    }

    private void syncToViewers() {
        if (world instanceof ServerWorld serverWorld) {
            long currentTime = world.getTime();
            if (currentTime - lastSyncTime < SYNC_COOLDOWN) return;
            lastSyncTime = currentTime;

            for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler
                        && handler.controller == this) {
                    handler.sendNetworkUpdate(player);
                }
            }
        }
    }

    public Map<ItemVariant, Long> getNetworkItems() {
        if (networkItemsCopyDirty || networkItemsCopy == null) {
            networkItemsCopy = new HashMap<>(networkItems);
            networkItemsCopyDirty = false;
        }
        return networkItemsCopy;
    }

    public void onProbeInventoryChanged(OutputProbeBlockEntity probe) {
        networkDirty = true;
    }

    // ===================================================================
    // PROBE MANAGEMENT
    // ===================================================================

    public boolean addProbe(BlockPos probePos) {
        if (!linkedProbes.contains(probePos)) {
            linkedProbes.add(probePos);
            probeOrderDirty = true;
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }

            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                BlockPos targetPos = probe.getTargetPos();
                if (targetPos != null) {
                    onChestDetected(targetPos);
                }
            }

            networkDirty = true;
            updateNetworkCache();
            return true;
        }
        return false;
    }

    public boolean removeProbe(BlockPos probePos) {
        boolean removed = linkedProbes.remove(probePos);
        if (removed) {
            probeOrderDirty = true;
            networkDirty = true;
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }

            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                BlockPos targetPos = probe.getTargetPos();
                if (targetPos != null) {
                    boolean stillHasProbe = false;
                    for (BlockPos otherProbePos : linkedProbes) {
                        BlockEntity otherBe = world.getBlockEntity(otherProbePos);
                        if (otherBe instanceof OutputProbeBlockEntity otherProbe) {
                            BlockPos otherTargetPos = otherProbe.getTargetPos();
                            if (otherTargetPos != null && targetPos.equals(otherTargetPos)) {
                                stillHasProbe = true;
                                break;
                            }
                        }
                    }

                    if (!stillHasProbe) {
                        onChestRemoved(targetPos);
                    }
                }
            }
        }
        return removed;
    }

    public List<BlockPos> getLinkedProbes() {
        return linkedProbes;
    }

    public int getLinkedInventoryCount() {
        return linkedProbes.size();
    }

    // ===================================================================
    // INTAKE MANAGEMENT
    // ===================================================================

    public boolean addIntake(BlockPos intakePos) {
        if (!linkedIntakes.contains(intakePos)) {
            linkedIntakes.add(intakePos);
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }
            return true;
        }
        return false;
    }

    public boolean removeIntake(BlockPos intakePos) {
        boolean removed = linkedIntakes.remove(intakePos);
        if (removed) {
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }
        }
        return removed;
    }

    public List<BlockPos> getLinkedIntakes() {
        return linkedIntakes;
    }

    // ===================================================================
    // ITEM INSERTION & EXTRACTION
    // ===================================================================

    public void sortChestsInOrder(List<BlockPos> positions) {
        if (world == null || world.isClient()) {
            return;
        }

        // Force an update before we start moving items
        this.updateNetworkCache();

        // Iterate through the PRE-SORTED list and sort each one.
        for (BlockPos pos : positions) {
            // Make sure the inventory is still connected to this controller
            if (isChestLinked(pos)) {
                // The existing logic to sort a single chest is perfect.
                sortChestIntoNetwork(pos);
            }
        }

        // Mark dirty to save changes and force a final sync
        this.markDirty();
        this.updateNetworkCache(); // Update again after all sorting is done
    }

    public ItemStack insertItem(ItemStack stack) {
        if (world == null || stack.isEmpty()) return stack;

        ItemVariant variant = ItemVariant.of(stack);
        ItemStack remainingStack = stack.copy();
        List<BlockPos> sortedProbes = getSortedProbesByPriority();

        // --- PASS 1: High-Priority & Filtered Destinations ---
        for (BlockPos probePos : sortedProbes) {
            if (remainingStack.isEmpty()) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            ChestConfig chestConfig = probe.getChestConfig();
            if (chestConfig == null || chestConfig.filterMode == ChestConfig.FilterMode.NONE || chestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                continue;
            }

            if (probe.accepts(variant)) {
                int inserted = insertIntoInventory(world, probe, remainingStack);
                remainingStack.decrement(inserted);
            }
        }

        if (remainingStack.isEmpty()) {
            networkDirty = true;
            return ItemStack.EMPTY;
        }

        // --- PASS 2: General & Overflow Destinations ---
        for (BlockPos probePos : sortedProbes) {
            if (remainingStack.isEmpty()) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            ChestConfig chestConfig = probe.getChestConfig();
            if (chestConfig == null || (chestConfig.filterMode != ChestConfig.FilterMode.NONE && chestConfig.filterMode != ChestConfig.FilterMode.OVERFLOW)) {
                continue;
            }

            if (probe.accepts(variant)) {
                int inserted = insertIntoInventory(world, probe, remainingStack);
                remainingStack.decrement(inserted);
            }
        }

        if (remainingStack.getCount() != stack.getCount()) {
            networkDirty = true;
        }

        return remainingStack;
    }

    public ItemStack extractItem(ItemVariant variant, int amount) {
        if (world == null) return ItemStack.EMPTY;

        int remaining = amount;

        for (BlockPos probePos : linkedProbes) {
            if (remaining <= 0) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;
            if (!probe.contains(variant)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            int extracted = extractFromInventory(world, probe, variant, remaining);
            remaining -= extracted;
        }

        int totalExtracted = amount - remaining;
        if (totalExtracted > 0) {
            networkDirty = true;
            networkItemsCopyDirty = true;
            updateNetworkCache();
            return variant.toStack(totalExtracted);
        }

        return ItemStack.EMPTY;
    }

    public boolean canInsertItem(ItemVariant variant, int amount) {
        if (world == null) return false;
        if (linkedProbes.isEmpty()) return false;

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;
            if (!probe.accepts(variant)) continue;

            Inventory inv = probe.getTargetInventory();
            if (inv == null) continue;

            for (int i = 0; i < inv.size(); i++) {
                ItemStack slot = inv.getStack(i);
                if (slot.isEmpty()) return true;
                if (ItemStack.areItemsAndComponentsEqual(slot, variant.toStack(1))
                        && slot.getCount() < slot.getMaxCount()) {
                    return true;
                }
            }
        }
        return false;
    }

    private int insertIntoInventory(World world, OutputProbeBlockEntity probe, ItemStack stack) {
        Inventory inv = probe.getTargetInventory();
        if (inv == null) return 0;

        int originalCount = stack.getCount();

        // First pass: Try stacking
        for (int i = 0; i < inv.size(); i++) {
            if (stack.isEmpty()) break;

            ItemStack slotStack = inv.getStack(i);
            if (slotStack.isEmpty()) continue;

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

    private int extractFromInventory(World world, OutputProbeBlockEntity probe, ItemVariant variant, int amount) {
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

    private ItemStack insertItemExcluding(ItemStack stack, BlockPos excludePos, boolean ignoreFilters) {
        if (world == null || stack.isEmpty()) return stack;

        ItemVariant variant = ItemVariant.of(stack);
        int remaining = stack.getCount();
        List<BlockPos> sortedProbes = getSortedProbesByPriority();

        for (BlockPos probePos : sortedProbes) {
            if (remaining <= 0) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            BlockPos targetPos = probe.getTargetPos();
            if (targetPos != null && targetPos.equals(excludePos)) continue;

            if (ignoreFilters) {
                ChestConfig chestConfig = getChestConfig(targetPos);

                if (chestConfig == null) continue;

                if (chestConfig.filterMode != ChestConfig.FilterMode.OVERFLOW &&
                        chestConfig.filterMode != ChestConfig.FilterMode.NONE) {
                    continue;
                }

            } else {
                if (!probe.accepts(variant)) continue;
            }

            ItemStack toInsert = variant.toStack(remaining);
            int inserted = insertIntoInventory(world, probe, toInsert);
            remaining -= inserted;

            if (inserted > 0) {
            }
        }

        if (remaining != stack.getCount()) {
            networkDirty = true;
            networkItemsCopyDirty = true;
            updateNetworkCache();
            markDirty();
        }

        return remaining > 0 ? variant.toStack(remaining) : ItemStack.EMPTY;
    }

    private List<BlockPos> getSortedProbesByPriority() {
        if (sortedProbesCache != null && !probeOrderDirty) {
            return sortedProbesCache;
        }

        List<ProbeEntry> entries = new ArrayList<>(linkedProbes.size());
        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                BlockPos chestPos = probe.getTargetPos();
                ChestConfig chestConfig = chestPos != null ? getChestConfig(chestPos) : null;
                entries.add(new ProbeEntry(probePos, probe.mode, chestConfig));
            }
        }

        entries.sort((a, b) -> {
            int aPriority = a.chestConfig != null ? a.chestConfig.hiddenPriority : 0;
            int bPriority = b.chestConfig != null ? b.chestConfig.hiddenPriority : 0;

            if (aPriority != bPriority) {
                return Integer.compare(bPriority, aPriority);
            }

            return Integer.compare(getModeOrder(a.mode), getModeOrder(b.mode));
        });

        sortedProbesCache = new ArrayList<>(entries.size());
        for (ProbeEntry entry : entries) {
            sortedProbesCache.add(entry.pos);
        }

        probeOrderDirty = false;
        return sortedProbesCache;
    }

    private static class ProbeEntry {
        final BlockPos pos;
        final OutputProbeBlockEntity.ProbeMode mode;
        final ChestConfig chestConfig;

        ProbeEntry(BlockPos pos, OutputProbeBlockEntity.ProbeMode mode, ChestConfig chestConfig) {
            this.pos = pos;
            this.mode = mode;
            this.chestConfig = chestConfig;
        }
    }

    private int getModeOrder(OutputProbeBlockEntity.ProbeMode mode) {
        return switch (mode) {
            case FILTER -> 0;
            case PRIORITY -> 1;
            case ACCEPT_ALL -> 2;
        };
    }

    // ===================================================================
    // CAPACITY CALCULATION
    // ===================================================================

    public int calculateTotalFreeSlots() {
        if (world == null) return 0;

        int totalFree = 0;
        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            Inventory inv = probe.getTargetInventory();
            if (inv == null) continue;

            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isEmpty()) {
                    totalFree++;
                }
            }
        }

        return totalFree;
    }

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

    // ===================================================================
    // PROCESS PROBES
    // ===================================================================

    public boolean registerProcessProbe(BlockPos pos, String machineType) {
        if (world == null) return false;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof ProcessProbeBlockEntity probe)) return false;

        ProcessProbeConfig config = probe.getConfig().copy();
        config.machineType = machineType;
        config.position = pos;

        if (!linkedProcessProbes.containsKey(pos)) {
            int index = getNextIndexForMachineType(machineType);
            config.setIndex(index);
        } else {
            ProcessProbeConfig existingConfig = linkedProcessProbes.get(pos);
            if (existingConfig != null) {
                config.setIndex(existingConfig.index);
            }
        }

        linkedProcessProbes.put(pos, config);
        probe.setConfig(config);

        markDirty();
        networkDirty = true;
        return true;
    }

    public void unregisterProcessProbe(BlockPos pos) {
        ProcessProbeConfig config = linkedProcessProbes.get(pos);

        if (config != null) {
            if (world != null) {
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof ProcessProbeBlockEntity probe) {
                    probe.setConfig(config.copy());
                }
            }

            linkedProcessProbes.remove(pos);
            markDirty();
            networkDirty = true;
        }
    }

    public void updateProbeConfig(ProcessProbeConfig config) {
        if (linkedProcessProbes.containsKey(config.position)) {
            ProcessProbeConfig existing = linkedProcessProbes.get(config.position);

            if (existing != null && existing.isFunctionallyEqual(config)) {
                if (existing.itemsProcessed != config.itemsProcessed) {
                    existing.itemsProcessed = config.itemsProcessed;
                    syncProbeStatsToClients(config.position, config.itemsProcessed);
                }
                return;
            }

            linkedProcessProbes.put(config.position, config.copy());

            if (world != null) {
                BlockEntity be = world.getBlockEntity(config.position);
                if (be instanceof ProcessProbeBlockEntity probe) {
                    probe.setConfig(config.copy());
                    syncProbeStatsToClients(config.position, config.itemsProcessed);
                }
            }

            markDirty();
            networkDirty = true;
            syncToViewers();
        }
    }

    private int getNextIndexForMachineType(String machineType) {
        Set<Integer> usedIndices = new HashSet<>();

        for (ProcessProbeConfig config : linkedProcessProbes.values()) {
            if (config.machineType.equals(machineType)) {
                String displayName = config.getDisplayName();
                if (displayName.contains("#")) {
                    try {
                        String numStr = displayName.substring(displayName.indexOf("#") + 1).trim();
                        usedIndices.add(Integer.parseInt(numStr) - 1);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        int index = 0;
        while (usedIndices.contains(index)) {
            index++;
        }

        return index;
    }

    public void syncProbeStatsToClients(BlockPos probePos, int itemsProcessed) {
        if (world instanceof ServerWorld serverWorld) {
            for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                    if (handler.controller == this) {
                        ServerPlayNetworking.send(player,
                                new ProbeStatsSyncPayload(probePos, itemsProcessed));
                    }
                }
            }
        }
    }

    public void syncProbeConfigToClients(BlockPos probePos, ProcessProbeConfig config) {
        if (world instanceof ServerWorld serverWorld) {
            if (linkedProcessProbes.containsKey(probePos)) {
                linkedProcessProbes.put(probePos, config.copy());
            }

            for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler
                        && handler.controller == this) {
                    ServerPlayNetworking.send(player,
                            new ProbeStatsSyncPayload(probePos, config.itemsProcessed));
                    handler.sendNetworkUpdate(player);
                }
            }
        }
    }

    public Map<BlockPos, ProcessProbeConfig> getProcessProbeConfigs() {
        return new LinkedHashMap<>(linkedProcessProbes);
    }

    public ProcessProbeConfig getProbeConfig(BlockPos pos) {
        return linkedProcessProbes.get(pos);
    }

    // ===================================================================
    // EXPERIENCE MANAGEMENT
    // ===================================================================

    public void addExperience(int amount) {
        storedExperience += amount;
        markDirty();
    }

    public int getStoredExperience() {
        return storedExperience;
    }

    public int collectExperience() {
        int xp = storedExperience;
        storedExperience = 0;
        markDirty();
        return xp;
    }

    // ===================================================================
    // CHEST CONFIG MANAGEMENT
    // ===================================================================

    public Map<BlockPos, ChestConfig> getChestConfigs() {
        Map<BlockPos, ChestConfig> configs = new LinkedHashMap<>(chestConfigs);

        for (ChestConfig config : configs.values()) {
            config.cachedFullness = calculateChestFullness(config.position);
            config.previewItems = getChestPreviewItems(config.position);
        }

        return configs;
    }

    public ChestConfig getChestConfig(BlockPos position) {
        return chestConfigs.get(position);
    }

    public void updateChestConfig(BlockPos position, ChestConfig config) {

        ChestConfig newConfig = config.copy();
        newConfig.updateHiddenPriority();

        chestConfigs.put(position, newConfig);

        ChestConfig saved = chestConfigs.get(position);

        writeNameToChest(position, newConfig.customName);

        probeOrderDirty = true;
        markDirty();
        networkDirty = true;
        syncToViewers();

        if (world != null) {
            BlockState state = world.getBlockState(pos);
            world.updateListeners(pos, state, state, 3);
        }
    }


    private void onChestDetected(BlockPos chestPos) {
        if (!chestConfigs.containsKey(chestPos)) {
            String existingName = readNameFromChest(chestPos);

            ChestConfig config = new ChestConfig(chestPos);
            if (existingName != null && !existingName.isEmpty()) {
                config.customName = existingName;
            }

            chestConfigs.put(chestPos, config);
            markDirty();
        }
    }

    private void onChestRemoved(BlockPos chestPos) {
        ChestConfig removedConfig = chestConfigs.remove(chestPos);

        if (removedConfig != null) {
            clearNameFromChest(chestPos);
        }

        markDirty();
    }

    public int calculateChestFullness(BlockPos chestPos) {
        if (world == null) return -1;

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            BlockPos targetPos = probe.getTargetPos();
            if (targetPos != null && targetPos.equals(chestPos)) {
                Inventory inv = probe.getTargetInventory();
                if (inv == null) return -1;

                int totalSlots = inv.size();
                int occupiedSlots = 0;

                for (int i = 0; i < totalSlots; i++) {
                    if (!inv.getStack(i).isEmpty()) {
                        occupiedSlots++;
                    }
                }

                return totalSlots > 0 ? (occupiedSlots * 100 / totalSlots) : 0;
            }
        }

        return -1;
    }

    public List<ItemStack> getChestPreviewItems(BlockPos chestPos) {
        List<ItemStack> items = new ArrayList<>();
        if (world == null) return items;

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            BlockPos targetPos = probe.getTargetPos();
            if (targetPos != null && targetPos.equals(chestPos)) {
                Inventory inv = probe.getTargetInventory();
                if (inv == null) break;

                for (int i = 0; i < inv.size() && items.size() < 8; i++) {
                    ItemStack stack = inv.getStack(i);
                    if (!stack.isEmpty()) {
                        items.add(stack.copy());
                    }
                }
                break;
            }
        }

        return items;
    }

    public boolean chestHasItems(BlockPos chestPos) {
        if (world == null) return false;

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (chestPos.equals(probe.getTargetPos())) {
                    Inventory inv = probe.getTargetInventory();
                    if (inv != null) {
                        for (int i = 0; i < inv.size(); i++) {
                            if (!inv.getStack(i).isEmpty()) {
                                return true;
                            }
                        }
                    }
                    break;
                }
            }
        }

        return false;
    }

    public void sortChestIntoNetwork(BlockPos sourceChestPos) {
        if (world == null) {
            return;
        }

        // Find the probe attached to the source chest
        OutputProbeBlockEntity sourceProbe = null;
        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe && sourceChestPos.equals(probe.getTargetPos())) {
                sourceProbe = probe;
                break;
            }
        }
        if (sourceProbe == null) return;

        Inventory sourceInv = sourceProbe.getTargetInventory();
        if (sourceInv == null) return;

        // STEP 1: Take all items out of the chest.
        List<ItemStack> itemsToSort = new ArrayList<>();
        for (int i = 0; i < sourceInv.size(); i++) {
            ItemStack stack = sourceInv.getStack(i);
            if (!stack.isEmpty()) {
                itemsToSort.add(sourceInv.removeStack(i));
            }
        }
        sourceInv.markDirty();

        if (itemsToSort.isEmpty()) {
            return;
        }

        // STEP 2: Use the new smart insertion for every item.
        List<ItemStack> unsortedItems = new ArrayList<>();
        for (ItemStack stack : itemsToSort) {
            // The new insertItem handles all priority and filtering logic correctly.
            ItemStack remainder = this.insertItem(stack);
            if (!remainder.isEmpty()) {
                unsortedItems.add(remainder);
            }
        }

        // STEP 3: Put any remaining unsorted items back into the original chest.
        for (ItemStack stack : unsortedItems) {
            // We can just use the regular insertion logic here, as it will try to stack/fill empty slots.
            insertIntoInventory(world, sourceProbe, stack);
        }

        // Final update
        networkDirty = true;
        updateNetworkCache();
        syncToViewers();
    }

    public boolean isChestLinked(BlockPos chestPos) {
        if (world == null) return false;

        for (BlockPos probePos : this.linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (chestPos.equals(probe.getTargetPos())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ===================================================================
    // CHEST NAME SYNC (NBT-BASED)
    // ===================================================================

    //? if >=1.21.8 {
    private void writeNameToChest(BlockPos chestPos, String customName) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (customName == null || customName.isEmpty()) {
            nbt.remove("CustomName");
        } else {
            // Use TextCodecs.CODEC to serialize Text to NBT
            Text textComponent = Text.literal(customName);
            DataResult<NbtElement> result = TextCodecs.CODEC.encodeStart(
                    world.getRegistryManager().getOps(NbtOps.INSTANCE),
                    textComponent
            );
            result.result().ifPresent(nbtElement -> {
                nbt.put("CustomName", nbtElement);
            });
        }

        // Use NbtReadView to read the modified NBT back into the block entity
        try (ErrorReporter.Logging logging = new ErrorReporter.Logging(blockEntity.getReporterContext(), LogUtils.getLogger())) {
            blockEntity.read(NbtReadView.create(logging, world.getRegistryManager(), nbt));
        }
        blockEntity.markDirty();

        BlockState state = world.getBlockState(chestPos);
        world.updateListeners(chestPos, state, state, 3);
    }

    private String readNameFromChest(BlockPos chestPos) {
        if (world == null) return "";

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return "";

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (nbt.contains("CustomName")) {
            try {
                // Use TextCodecs.CODEC to deserialize Text from NBT
                DataResult<Text> result = TextCodecs.CODEC.parse(
                        world.getRegistryManager().getOps(NbtOps.INSTANCE),
                        nbt.get("CustomName")
                );
                return result.result()
                        .map(Text::getString)
                        .orElse("");
            } catch (Exception e) {
                return "";
            }
        }

        return "";
    }

    private void clearNameFromChest(BlockPos chestPos) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (nbt.contains("CustomName")) {
            nbt.remove("CustomName");

            try (ErrorReporter.Logging logging = new ErrorReporter.Logging(blockEntity.getReporterContext(), LogUtils.getLogger())) {
                blockEntity.read(NbtReadView.create(logging, world.getRegistryManager(), nbt));
            }
            blockEntity.markDirty();

            BlockState state = world.getBlockState(chestPos);
            world.updateListeners(chestPos, state, state, 3);
        }
    }
    //?} else {
    /*private void writeNameToChest(BlockPos chestPos, String customName) {
        if (world == null) return;
        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (customName == null || customName.isEmpty()) {
        nbt.remove("CustomName");
    } else {
        // Use Text.Serialization for 1.21.1
        Text textComponent = Text.literal(customName);
        nbt.putString("CustomName", Text.Serialization.toJsonString(textComponent, world.getRegistryManager()));
    }

        blockEntity.read(nbt, world.getRegistryManager());
        blockEntity.markDirty();

        BlockState state = world.getBlockState(chestPos);
        world.updateListeners(chestPos, state, state, 3);
}

    private String readNameFromChest(BlockPos chestPos) {
        if (world == null) return "";

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return "";

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (nbt.contains("CustomName")) {
        try {
            Text customName = Text.Serialization.fromJson(nbt.getString("CustomName"), world.getRegistryManager());
            return customName != null ? customName.getString() : "";
        } catch (Exception e) {
            return "";
        }
    }
    return "";
}

    private void clearNameFromChest(BlockPos chestPos) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (nbt.contains("CustomName")) {
        nbt.remove("CustomName");
        blockEntity.read(nbt, world.getRegistryManager());
        blockEntity.markDirty();

        BlockState state = world.getBlockState(chestPos);
        world.updateListeners(chestPos, state, state, 3);
    }
}
*///?}

    // ===================================================================
    // INVENTORY INTERFACE (EMPTY IMPLEMENTATION)
    // ===================================================================

    @Override
    public int size() {
        return 0;
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
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return pos.isWithinDistance(player.getBlockPos(), 8.0);
    }

    @Override
    public void clear() {
    }

    public ItemStack insertItemStack(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();
        insertItem(remaining);
        return ItemStack.EMPTY;
    }

    // ===================================================================
    // SCREEN HANDLER FACTORY
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
    // CONFIG EXPORT/IMPORT
    // ===================================================================

    public NbtCompound exportConfigs() {
        NbtCompound export = new NbtCompound();

        NbtList probeList = new NbtList();
        for (ProcessProbeConfig config : linkedProcessProbes.values()) {
            probeList.add(config.toNbt());
        }
        export.put("probes", probeList);
        export.putInt("storedXp", storedExperience);

        return export;
    }

    // ===================================================================
    // NBT SERIALIZATION
    // ===================================================================

    //? if >=1.21.8 {
    private void writeProbesToView(WriteView view) {
        int probeCount = linkedProbes.size();
        view.putInt("probe_count", probeCount);
        for (int i = 0; i < probeCount && i < PROBE_KEYS.length; i++) {
            view.putLong(PROBE_KEYS[i], linkedProbes.get(i).asLong());
        }
    }

    private void readProbesFromView(ReadView view) {
        linkedProbes.clear();
        int count = view.getInt("probe_count", 0);
        for (int i = 0; i < count; i++) {
            view.getOptionalLong("probe_" + i).ifPresent(posLong ->
                    linkedProbes.add(BlockPos.fromLong(posLong))
            );
        }
    }

    @Override
    public void writeData(WriteView view) {
        super.writeData(view);
        writeProbesToView(view);

        // Save intakes
        view.putInt("intake_count", linkedIntakes.size());
        for (int i = 0; i < linkedIntakes.size() && i < 256; i++) {
            view.putLong("intake_" + i, linkedIntakes.get(i).asLong());
        }

        // Save process probes
        view.putInt("processProbeCount", linkedProcessProbes.size());
        int idx = 0;
        for (ProcessProbeConfig config : linkedProcessProbes.values()) {
            if (idx >= PP_POS_KEYS.length) break;

            view.putLong(PP_POS_KEYS[idx], config.position.asLong());
            if (config.customName != null) {
                view.putString(PP_NAME_KEYS[idx], config.customName);
            }
            view.putString(PP_TYPE_KEYS[idx], config.machineType);
            view.putBoolean(PP_ENABLED_KEYS[idx], config.enabled);
            view.putString(PP_RECIPE_KEYS[idx], config.recipeFilter.asString());
            view.putString(PP_FUEL_KEYS[idx], config.fuelFilter.asString());
            view.putInt(PP_PROCESSED_KEYS[idx], config.itemsProcessed);
            view.putInt(PP_INDEX_KEYS[idx], config.index);
            idx++;
        }
        view.putInt("storedXp", storedExperience);

        // Save chest configs
        view.putInt("chestConfigCount", chestConfigs.size());
        idx = 0;
        for (ChestConfig config : chestConfigs.values()) {
            if (idx >= CHEST_POS_KEYS.length) break;

            view.putLong(CHEST_POS_KEYS[idx], config.position.asLong());
            view.putString(CHEST_NAME_KEYS[idx], config.customName);
            view.putString(CHEST_CATEGORY_KEYS[idx], config.filterCategory.asString());
            view.putInt(CHEST_PRIORITY_KEYS[idx], config.priority);
            view.putString(CHEST_MODE_KEYS[idx], config.filterMode.name());
            view.putBoolean(CHEST_FRAME_KEYS[idx], config.autoItemFrame);
            idx++;
        }
    }

    @Override
    public void readData(ReadView view) {
        super.readData(view);
        readProbesFromView(view);

        // Load intakes
        linkedIntakes.clear();
        int intakeCount = view.getInt("intake_count", 0);
        for (int i = 0; i < intakeCount; i++) {
            view.getOptionalLong("intake_" + i).ifPresent(posLong ->
                    linkedIntakes.add(BlockPos.fromLong(posLong))
            );
        }

        // Load process probes
        linkedProcessProbes.clear();
        int probeCount = view.getInt("processProbeCount", 0);
        for (int i = 0; i < probeCount; i++) {
            ProcessProbeConfig config = new ProcessProbeConfig();

            view.getOptionalLong("pp_pos_" + i).ifPresent(posLong ->
                    config.position = BlockPos.fromLong(posLong)
            );

            config.customName = view.getString("pp_name_" + i, null);
            config.machineType = view.getString("pp_type_" + i, "Unknown");
            config.enabled = view.getBoolean("pp_enabled_" + i, true);
            config.recipeFilter = RecipeFilterMode.fromString(
                    view.getString("pp_recipe_" + i, "ORES_ONLY")
            );
            config.fuelFilter = FuelFilterMode.fromString(
                    view.getString("pp_fuel_" + i, "COAL_ONLY")
            );
            config.itemsProcessed = view.getInt("pp_processed_" + i, 0);
            config.index = view.getInt("pp_index_" + i, 0);

            if (config.position != null) {
                linkedProcessProbes.put(config.position, config);
            }
        }

        storedExperience = view.getInt("storedXp", 0);

        // Load chest configs
        chestConfigs.clear();
        int chestCount = view.getInt("chestConfigCount", 0);
        for (int i = 0; i < chestCount; i++) {
            final int index = i;
            view.getOptionalLong(CHEST_POS_KEYS[index]).ifPresent(posLong -> {
                BlockPos pos = BlockPos.fromLong(posLong);
                String name = view.getString(CHEST_NAME_KEYS[index], "");
                String categoryStr = view.getString(CHEST_CATEGORY_KEYS[index], "smartsorter:all");

                int priority;
                try {
                    priority = view.getInt(CHEST_PRIORITY_KEYS[index], 1);
                } catch (Exception e) {
                    String priorityStr = view.getString(CHEST_PRIORITY_KEYS[index], "MEDIUM");
                    priority = switch (priorityStr) {
                        case "HIGH" -> 1;
                        case "LOW" -> 3;
                        default -> 2;
                    };
                }

                String modeStr = view.getString(CHEST_MODE_KEYS[index], "NONE");
                boolean autoFrame = view.getBoolean(CHEST_FRAME_KEYS[index], false);

                Category category = CategoryManager.getInstance().getCategory(categoryStr);
                ChestConfig.FilterMode mode = ChestConfig.FilterMode.valueOf(modeStr);

                ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
                chestConfigs.put(pos, config);
            });
        }
        this.needsValidation = true;
    }
    //?} else {
    /*private void writeProbesToNbt(NbtCompound nbt) {
        nbt.putInt("probe_count", linkedProbes.size());
        for (int i = 0; i < linkedProbes.size(); i++) {
            nbt.putLong("probe_" + i, linkedProbes.get(i).asLong());
        }
    }

    private void readProbesFromNbt(NbtCompound nbt) {
        linkedProbes.clear();
        int count = nbt.getInt("probe_count");
        for (int i = 0; i < count; i++) {
            String key = "probe_" + i;
            if (nbt.contains(key)) {
                linkedProbes.add(BlockPos.fromLong(nbt.getLong(key)));
            }
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        writeProbesToNbt(nbt);

        nbt.putInt("intake_count", linkedIntakes.size());
        for (int i = 0; i < linkedIntakes.size(); i++) {
            nbt.putLong("intake_" + i, linkedIntakes.get(i).asLong());
        }

        nbt.putInt("processProbeCount", linkedProcessProbes.size());
        int idx = 0;
        for (ProcessProbeConfig config : linkedProcessProbes.values()) {
            nbt.putLong("pp_pos_" + idx, config.position.asLong());
            if (config.customName != null) {
                nbt.putString("pp_name_" + idx, config.customName);
            }
            nbt.putString("pp_type_" + idx, config.machineType);
            nbt.putBoolean("pp_enabled_" + idx, config.enabled);
            nbt.putString("pp_recipe_" + idx, config.recipeFilter.asString());
            nbt.putString("pp_fuel_" + idx, config.fuelFilter.asString());
            nbt.putInt("pp_processed_" + idx, config.itemsProcessed);
            nbt.putInt("pp_index_" + idx, config.index);
            idx++;
        }
        nbt.putInt("storedXp", storedExperience);

        nbt.putInt("chestConfigCount", chestConfigs.size());
        idx = 0;
        for (ChestConfig config : chestConfigs.values()) {
            nbt.putLong("chest_pos_" + idx, config.position.asLong());
            nbt.putString("chest_name_" + idx, config.customName);
            nbt.putString("chest_cat_" + idx, config.filterCategory.asString());
            nbt.putInt("chest_pri_" + idx, config.priority);
            nbt.putString("chest_mode_" + idx, config.filterMode.name());
            nbt.putBoolean("chest_frame_" + idx, config.autoItemFrame);
            idx++;
        }
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        readProbesFromNbt(nbt);

        linkedIntakes.clear();
        int intakeCount = nbt.getInt("intake_count");
        for (int i = 0; i < intakeCount; i++) {
            String key = "intake_" + i;
            if (nbt.contains(key)) {
                linkedIntakes.add(BlockPos.fromLong(nbt.getLong(key)));
            }
        }

        linkedProcessProbes.clear();
        int probeCount = nbt.getInt("processProbeCount");
        for (int i = 0; i < probeCount; i++) {
            ProcessProbeConfig config = new ProcessProbeConfig();

            if (nbt.contains("pp_pos_" + i)) {
                config.position = BlockPos.fromLong(nbt.getLong("pp_pos_" + i));
            }

            config.customName = nbt.contains("pp_name_" + i) ? nbt.getString("pp_name_" + i) : null;
            config.machineType = nbt.contains("pp_type_" + i) ? nbt.getString("pp_type_" + i) : "Unknown";
            config.enabled = nbt.contains("pp_enabled_" + i) ? nbt.getBoolean("pp_enabled_" + i) : true;
            config.recipeFilter = RecipeFilterMode.fromString(
                    nbt.contains("pp_recipe_" + i) ? nbt.getString("pp_recipe_" + i) : "ORES_ONLY"
            );
            config.fuelFilter = FuelFilterMode.fromString(
                    nbt.contains("pp_fuel_" + i) ? nbt.getString("pp_fuel_" + i) : "COAL_ONLY"
            );
            config.itemsProcessed = nbt.getInt("pp_processed_" + i);
            config.index = nbt.getInt("pp_index_" + i);

            if (config.position != null) {
                linkedProcessProbes.put(config.position, config);
            }
        }

        storedExperience = nbt.getInt("storedXp");

        chestConfigs.clear();
        int chestCount = nbt.getInt("chestConfigCount");
        for (int i = 0; i < chestCount; i++) {
            if (!nbt.contains("chest_pos_" + i)) continue;

            BlockPos pos = BlockPos.fromLong(nbt.getLong("chest_pos_" + i));
            String name = nbt.getString("chest_name_" + i);
            String categoryStr = nbt.getString("chest_cat_" + i);

            int priority;
            try {
                priority = nbt.getInt("chest_pri_" + i);
            } catch (Exception e) {
                String priorityStr = nbt.getString("chest_pri_" + i);
                priority = switch (priorityStr) {
                    case "HIGH" -> 1;
                    case "LOW" -> 3;
                    default -> 2;
                };
            }

            String modeStr = nbt.getString("chest_mode_" + i);
            boolean autoFrame = nbt.getBoolean("chest_frame_" + i);

            Category category = CategoryManager.getInstance().getCategory(categoryStr);
            ChestConfig.FilterMode mode = ChestConfig.FilterMode.valueOf(modeStr);

            ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
            chestConfigs.put(pos, config);
        }
        this.needsValidation = true;
    }
    *///?}

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
        return createNbt(registryLookup);
    }

    // ===================================================================
    // CLEANUP
    // ===================================================================

    public void removeChestConfig(BlockPos chestPos) {
        ChestConfig removed = chestConfigs.remove(chestPos);

        if (removed != null) {
            clearNameFromChest(chestPos);
            probeOrderDirty = true;
            markDirty();
            networkDirty = true;
            syncToViewers();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }
        }
    }

    public void onRemoved() {
        linkedProbes.clear();
        linkedIntakes.clear();
        networkItems.clear();
        linkedProcessProbes.clear();

        if (world != null && !world.isClient()) {
            for (BlockPos chestPos : chestConfigs.keySet()) {
                clearNameFromChest(chestPos);
            }
        }

        chestConfigs.clear();
    }
}