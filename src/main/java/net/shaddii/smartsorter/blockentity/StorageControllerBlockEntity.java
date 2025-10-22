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
import net.minecraft.registry.Registries;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.network.OverflowNotificationPayload;
import net.shaddii.smartsorter.network.ProbeStatsSyncPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.*;
import net.shaddii.smartsorter.util.ChestPriorityManager;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class StorageControllerBlockEntity extends BlockEntity implements NamedScreenHandlerFactory, Inventory {
    // ========================================
    // CONSTANTS
    // ========================================

    private static final long CACHE_DURATION = 40;
    private static final long SYNC_COOLDOWN = 5;
    private static final long VALIDATION_INTERVAL = 200L;
    private static final long TAG_CACHE_CLEAR_INTERVAL = 6000L;
    private static final InsertionResult EMPTY_SUCCESS =
            new InsertionResult(ItemStack.EMPTY, false, null, null);

    // ========================================
    // INNER CLASSES
    // ========================================

    public record InsertionResult(
            ItemStack remainder,
            boolean overflowed,
            BlockPos destination,
            String destinationName
    ) {}

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

    // ========================================
    // NBT KEY HELPERS (Replacing static arrays)
    // ========================================

    private static String getProbeKey(int i) { return "probe_" + i; }
    private static String getPPPosKey(int i) { return "pp_pos_" + i; }
    private static String getPPNameKey(int i) { return "pp_name_" + i; }
    private static String getPPTypeKey(int i) { return "pp_type_" + i; }
    private static String getPPEnabledKey(int i) { return "pp_enabled_" + i; }
    private static String getPPRecipeKey(int i) { return "pp_recipe_" + i; }
    private static String getPPFuelKey(int i) { return "pp_fuel_" + i; }
    private static String getPPProcessedKey(int i) { return "pp_processed_" + i; }
    private static String getPPIndexKey(int i) { return "pp_index_" + i; }
    private static String getChestPosKey(int i) { return "chest_pos_" + i; }
    private static String getChestNameKey(int i) { return "chest_name_" + i; }
    private static String getChestCategoryKey(int i) { return "chest_cat_" + i; }
    private static String getChestPriorityKey(int i) { return "chest_pri_" + i; }
    private static String getChestModeKey(int i) { return "chest_mode_" + i; }
    private static String getChestFrameKey(int i) { return "chest_frame_" + i; }

    // ========================================
    // FIELDS
    // ========================================

    // Linked blocks
    private final List<BlockPos> linkedProbes = new ArrayList<>();
    private final List<BlockPos> linkedIntakes = new ArrayList<>();

    // Network storage
    private final Map<ItemVariant, Long> networkItems = new LinkedHashMap<>();
    private final Map<ItemVariant, Long> deltaItems = new HashMap<>();
    private final Map<ItemVariant, List<BlockPos>> itemLocationIndex = new HashMap<>();
    private Map<ItemVariant, Long> networkItemsCopy = null;
    private boolean networkItemsCopyDirty = true;

    // Process probes
    private final Map<BlockPos, ProcessProbeConfig> linkedProcessProbes = new LinkedHashMap<>();
    private int storedExperience = 0;

    // Chest configs
    private final Map<BlockPos, ChestConfig> chestConfigs = new LinkedHashMap<>();

    // Priority management
    private final ChestPriorityManager priorityManager = new ChestPriorityManager();

    // Cache & optimization
    private boolean networkDirty = true;
    private long lastCacheUpdate = 0;
    private long lastSyncTime = 0;
    private List<BlockPos> sortedProbesCache = null;
    private boolean probeOrderDirty = true;
    private boolean needsValidation = true;
    private InventoryOrganizer.Strategy organizationStrategy = InventoryOrganizer.Strategy.ADAPTIVE;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public StorageControllerBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.STORAGE_CONTROLLER_BE_TYPE, pos, state);
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(World world, BlockPos pos, BlockState state, StorageControllerBlockEntity be) {
        if (world.isClient()) return;

        // Validate links periodically
        if (world.getTime() % VALIDATION_INTERVAL == 0) {
            be.validateLinks();
        }

        // Clear tag cache periodically
        if (world.getTime() % TAG_CACHE_CLEAR_INTERVAL == 0) {
            SortUtil.clearTagCache();
        }

        // Update network cache if dirty
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

    // ========================================
    // NETWORK CACHE & SYNC
    // ========================================

    public void updateNetworkCache() {
        if (world == null) return;

        Map<ItemVariant, Long> newNetworkItems = new HashMap<>();
        this.itemLocationIndex.clear();

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            for (var view : storage) {
                if (view.getAmount() == 0) continue;
                ItemVariant variant = view.getResource();
                if (variant.isBlank()) continue;

                newNetworkItems.merge(variant, view.getAmount(), Long::sum);
                this.itemLocationIndex.computeIfAbsent(variant, k -> new ArrayList<>()).add(probePos);
            }
        }

        // Track changes
        for (Map.Entry<ItemVariant, Long> oldEntry : this.networkItems.entrySet()) {
            long newAmount = newNetworkItems.getOrDefault(oldEntry.getKey(), 0L);
            if (newAmount != oldEntry.getValue()) {
                deltaItems.put(oldEntry.getKey(), newAmount);
            }
        }
        for (Map.Entry<ItemVariant, Long> newEntry : newNetworkItems.entrySet()) {
            if (!this.networkItems.containsKey(newEntry.getKey())) {
                deltaItems.put(newEntry.getKey(), newEntry.getValue());
            }
        }

        this.networkItems.clear();
        this.networkItems.putAll(newNetworkItems);
        networkItemsCopyDirty = true;
    }

    private void syncToViewers() {
        if (!(world instanceof ServerWorld serverWorld)) return;

        long currentTime = world.getTime();
        if (currentTime - lastSyncTime < SYNC_COOLDOWN) return;
        lastSyncTime = currentTime;

        if (deltaItems.isEmpty()) return;

        for (ServerPlayerEntity player : serverWorld.getPlayers()) {
            if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler && handler.controller == this) {
                handler.sendNetworkUpdate(player, new HashMap<>(deltaItems));
            }
        }

        deltaItems.clear();
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

    // ========================================
    // PROBE MANAGEMENT
    // ========================================

    public boolean addProbe(BlockPos probePos) {
        if (!linkedProbes.contains(probePos)) {
            linkedProbes.add(probePos);
            probeOrderDirty = true;
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);

                // Immediately detect and add chest config
                BlockEntity be = world.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    BlockPos targetPos = probe.getTargetPos();
                    if (targetPos != null) {
                        onChestDetected(targetPos);
                    }

                    // Also sync the probe's local config if it has one
                    ChestConfig probeConfig = probe.getChestConfig();
                    if (probeConfig != null && targetPos != null) {
                        updateChestConfig(targetPos, probeConfig);
                    }
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
            // Primary sort: hiddenPriority (higher = first)
            int aPriority = a.chestConfig != null ? a.chestConfig.hiddenPriority : 0;
            int bPriority = b.chestConfig != null ? b.chestConfig.hiddenPriority : 0;

            if (aPriority != bPriority) {
                return Integer.compare(bPriority, aPriority);
            }

            // Secondary sort: Chests with items before empty chests (for same priority level)
            boolean aHasItems = hasItemsInChest(a.pos);
            boolean bHasItems = hasItemsInChest(b.pos);

            if (aHasItems && !bHasItems) return -1; // a first
            if (!aHasItems && bHasItems) return 1;  // b first

            // Tertiary sort: Mode order (if still tied)
            return Integer.compare(getModeOrder(a.mode), getModeOrder(b.mode));
        });

        sortedProbesCache = new ArrayList<>(entries.size());
        for (ProbeEntry entry : entries) {
            sortedProbesCache.add(entry.pos);
        }

        probeOrderDirty = false;
        return sortedProbesCache;
    }

    private boolean hasItemsInChest(BlockPos probePos) {
        if (world == null) return false;

        BlockEntity be = world.getBlockEntity(probePos);
        if (!(be instanceof OutputProbeBlockEntity probe)) return false;

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

    // ========================================
    // INTAKE MANAGEMENT
    // ========================================

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

    // ========================================
    // ITEM INSERTION & EXTRACTION
    // ========================================

    public InsertionResult insertItem(ItemStack stack) {
        if (world == null || stack.isEmpty()) {
            return new InsertionResult(stack, false, null, null);
        }

        Map<BlockPos, BlockEntity> beCache = new HashMap<>();

        ItemVariant variant = ItemVariant.of(stack);
        ItemStack remainingStack = stack.copy();
        List<BlockPos> sortedProbes = getSortedProbesByPriority();
        boolean potentialOverflow = false;
        BlockPos insertedInto = null;
        String insertedIntoName = null;

        // Pass 1: High-priority & filtered destinations
        for (BlockPos probePos : sortedProbes) {
            if (remainingStack.isEmpty()) break;

            OutputProbeBlockEntity probe = getCachedProbe(probePos, beCache);
            if (probe == null) continue;

            ChestConfig chestConfig = probe.getChestConfig();
            if (chestConfig == null) continue;

            // Skip NONE and OVERFLOW in first pass
            if (chestConfig.filterMode == ChestConfig.FilterMode.NONE ||
                    chestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                continue;
            }

            if (probe.accepts(variant)) {
                int beforeCount = remainingStack.getCount();
                int inserted = insertIntoInventory(world, probe, remainingStack);

                if (inserted > 0 && insertedInto == null) {
                    insertedInto = probe.getTargetPos();
                    insertedIntoName = getChestDisplayName(chestConfig);
                }

                remainingStack.decrement(inserted);
            }
        }

        if (!remainingStack.isEmpty()) {
            potentialOverflow = true;
        } else {
            if (remainingStack.getCount() != stack.getCount()) networkDirty = true;
            if (insertedInto == null && insertedIntoName == null) {
                return EMPTY_SUCCESS;
            }
            return new InsertionResult(ItemStack.EMPTY, false, insertedInto, insertedIntoName);
        }

        // Pass 2: General & overflow destinations
        boolean didOverflow = false;
        for (BlockPos probePos : sortedProbes) {
            if (remainingStack.isEmpty()) break;

            OutputProbeBlockEntity probe = getCachedProbe(probePos, beCache);
            if (probe == null) continue;

            ChestConfig chestConfig = probe.getChestConfig();
            if (chestConfig == null) continue;

            // Only process NONE and OVERFLOW in second pass
            if (chestConfig.filterMode != ChestConfig.FilterMode.NONE &&
                    chestConfig.filterMode != ChestConfig.FilterMode.OVERFLOW) {
                continue;
            }

            if (probe.accepts(variant)) {
                int beforeCount = remainingStack.getCount();
                int inserted = insertIntoInventory(world, probe, remainingStack);

                if (inserted > 0) {
                    if (insertedInto == null) {
                        insertedInto = probe.getTargetPos();
                        insertedIntoName = getChestDisplayName(chestConfig);
                    }

                    if (potentialOverflow && chestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                        didOverflow = true;
                    }
                }

                remainingStack.decrement(inserted);
            }
        }

        if (remainingStack.getCount() != stack.getCount()) {
            networkDirty = true;
        }
        if (remainingStack.isEmpty() && !didOverflow && insertedInto == null) {
            return EMPTY_SUCCESS;
        }
        return new InsertionResult(remainingStack, didOverflow, insertedInto, insertedIntoName);
    }

    private String getChestDisplayName(ChestConfig config) {
        if (config.customName != null && !config.customName.isEmpty()) {
            return config.customName;
        }

        // Return category name + "Storage"
        return config.filterCategory.getDisplayName() + " Storage";
    }


    public ItemStack extractItem(ItemVariant variant, int amount) {
        if (world == null || amount <= 0) return ItemStack.EMPTY;

        List<BlockPos> probesWithItem = itemLocationIndex.get(variant);
        if (probesWithItem == null || probesWithItem.isEmpty()) {
            return ItemStack.EMPTY;
        }

        int remainingToExtract = amount;

        for (BlockPos probePos : new ArrayList<>(probesWithItem)) {
            if (remainingToExtract <= 0) break;

            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;

            var storage = probe.getTargetStorage();
            if (storage == null) continue;

            int extracted = extractFromInventory(world, probe, variant, remainingToExtract);
            remainingToExtract -= extracted;
        }

        int totalExtracted = amount - remainingToExtract;
        if (totalExtracted > 0) {
            networkDirty = true;
            return variant.toStack(totalExtracted);
        }

        return ItemStack.EMPTY;
    }

    public boolean canInsertItem(ItemVariant variant, int amount) {
        if (world == null || linkedProbes.isEmpty()) return false;

        if (itemLocationIndex.containsKey(variant)) {
            List<BlockPos> relevantProbes = itemLocationIndex.get(variant);
            for (BlockPos probePos : relevantProbes) {
                BlockEntity be = world.getBlockEntity(probePos);
                if (!(be instanceof OutputProbeBlockEntity probe)) continue;

                if (probe.hasSpace(variant, amount)) {
                    return true;
                }
            }
        }

        for (BlockPos probePos : linkedProbes) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe) || !probe.accepts(variant)) continue;

            Inventory inv = probe.getTargetInventory();
            if (inv == null) continue;

            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isEmpty()) {
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

    @Nullable
    private OutputProbeBlockEntity getCachedProbe(BlockPos pos, Map<BlockPos, BlockEntity> beCache) {
        if (world == null) return null;

        BlockEntity be = beCache.get(pos);
        if (be == null) {
            be = world.getBlockEntity(pos);
            if (be != null) {
                beCache.put(pos, be);
            }
        }

        return be instanceof OutputProbeBlockEntity probe ? probe : null;
    }

    // ========================================
    // CAPACITY CALCULATION
    // ========================================

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

    // ========================================
    // PROCESS PROBES
    // ========================================

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
                        ServerPlayNetworking.send(player, new ProbeStatsSyncPayload(probePos, itemsProcessed));
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
                    ServerPlayNetworking.send(player, new ProbeStatsSyncPayload(probePos, config.itemsProcessed));
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

    // ========================================
    // XP MANAGEMENT
    // ========================================

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

    // ========================================
    // CHEST CONFIG MANAGEMENT
    // ========================================

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
        boolean isNewChest = !chestConfigs.containsKey(position);
        ChestConfig oldConfig = chestConfigs.get(position);

        if (isNewChest) {
            // New chest - handle insertion with shifting
            Map<BlockPos, ChestConfig> allConfigs = new HashMap<>(chestConfigs);

            int desiredPriority = config.priority;
            if (config.simplePrioritySelection != null && config.filterMode != ChestConfig.FilterMode.CUSTOM) {
                int regularCount = priorityManager.getRegularChestCount(allConfigs);
                desiredPriority = priorityManager.getInsertionPriority(config.simplePrioritySelection, regularCount);
            }

            Map<BlockPos, Integer> newPriorities = priorityManager.insertChest(
                    position, config, desiredPriority, allConfigs
            );

            applyPriorityUpdates(newPriorities);

        } else if (oldConfig != null && oldConfig.priority != config.priority &&
                config.filterMode != ChestConfig.FilterMode.CUSTOM) {
            // Priority changed - handle shifting
            Map<BlockPos, ChestConfig> allConfigs = new HashMap<>(chestConfigs);
            Map<BlockPos, Integer> newPriorities = priorityManager.moveChest(
                    position, config.priority, allConfigs
            );
            applyPriorityUpdates(newPriorities);

        } else {
            // Simple update without priority change
            ChestConfig newConfig = config.copy();
            newConfig.updateHiddenPriority();
            chestConfigs.put(position, newConfig);
        }

        writeNameToChest(position, config.customName);

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
            ChestConfig config = null;

            // First, try to get config from the probe that's targeting this chest
            for (BlockPos probePos : linkedProbes) {
                BlockEntity be = world.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    BlockPos targetPos = probe.getTargetPos();
                    if (targetPos != null && targetPos.equals(chestPos)) {
                        // Get the probe's local config
                        ChestConfig probeConfig = probe.getChestConfig();
                        if (probeConfig != null) {
                            config = probeConfig.copy();
                            break;
                        }
                    }
                }
            }

            // If no probe config found, create default
            if (config == null) {
                String existingName = readNameFromChest(chestPos);
                config = new ChestConfig(chestPos);
                if (existingName != null && !existingName.isEmpty()) {
                    config.customName = existingName;
                }
                // Set default SimplePriority for new chests
                config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
            }

            // Handle dynamic priority insertion
            if (config.simplePrioritySelection != null && config.filterMode != ChestConfig.FilterMode.CUSTOM) {
                // FIRST: Add the chest to chestConfigs
                chestConfigs.put(chestPos, config);

                // THEN: Let priority manager recalculate positions
                Map<BlockPos, Integer> newPriorities = priorityManager.recalculatePriorities(chestConfigs);

                // Apply the recalculated priorities
                applyPriorityUpdates(newPriorities);
            } else {
                // For custom or no simple priority, just add normally
                config.updateHiddenPriority();
                chestConfigs.put(chestPos, config);
            }

            probeOrderDirty = true;
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

    public void removeChestConfig(BlockPos chestPos) {
        ChestConfig removed = chestConfigs.remove(chestPos);

        if (removed != null) {
            // Use priority manager to handle removal and shifting
            Map<BlockPos, ChestConfig> allConfigs = new HashMap<>(chestConfigs);
            Map<BlockPos, Integer> newPriorities = priorityManager.removeChest(chestPos, allConfigs);
            applyPriorityUpdates(newPriorities);

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

    private void applyPriorityUpdates(Map<BlockPos, Integer> newPriorities) {
        for (Map.Entry<BlockPos, Integer> entry : newPriorities.entrySet()) {
            ChestConfig config = chestConfigs.get(entry.getKey());
            if (config != null) {
                int oldPriority = config.priority;
                config.priority = entry.getValue();

                // Update SimplePriority selection to match new position
                if (config.filterMode != ChestConfig.FilterMode.CUSTOM) {
                    int regularCount = priorityManager.getRegularChestCount(chestConfigs);
                    config.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                            config.priority, regularCount
                    );
                }

                config.updateHiddenPriority();
            }
        }
        this.networkDirty = true;
        this.syncToViewers();
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

    // ========================================
    // CHEST SORTING
    // ========================================

    public void sortChestsInOrder(List<BlockPos> positions, @Nullable ServerPlayerEntity player) {
        if (world == null || world.isClient()) return;

        this.updateNetworkCache();

        Map<ItemVariant, Long> overflowCounts = new HashMap<>();

        for (BlockPos pos : positions) {
            if (isChestLinked(pos)) {
                sortChestIntoNetwork(pos, overflowCounts);
            }
        }

        if (player != null && !overflowCounts.isEmpty()) {
            ServerPlayNetworking.send(player, new OverflowNotificationPayload(overflowCounts));
        }

        this.markDirty();
        this.updateNetworkCache();
    }

    public void sortChestIntoNetwork(BlockPos sourceChestPos, Map<ItemVariant, Long> overflowCounts) {
        sortChestIntoNetwork(sourceChestPos, overflowCounts, new HashMap<>());
    }

    public void sortChestIntoNetwork(BlockPos sourceChestPos, Map<ItemVariant, Long> overflowCounts, Map<ItemVariant, String> overflowDestinations) {
        if (world == null) return;

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

        // Track which chests received items (for optional organization)
        Set<BlockPos> modifiedChests = new HashSet<>();

        // CHANGED: Use new organizer (fast, always enabled)
        organizeInventory(sourceInv);

        List<ItemStack> itemsToSort = new ArrayList<>();
        for (int i = 0; i < sourceInv.size(); i++) {
            ItemStack stack = sourceInv.getStack(i);
            if (!stack.isEmpty()) {
                itemsToSort.add(sourceInv.removeStack(i));
            }
        }
        sourceInv.markDirty();

        if (itemsToSort.isEmpty()) return;

        List<ItemStack> unsortedItems = new ArrayList<>();
        for (ItemStack stack : itemsToSort) {
            ItemVariant originalVariant = ItemVariant.of(stack);
            int originalCount = stack.getCount();

            InsertionResult result = this.insertItem(stack);

            // Track destination
            if (result.destination() != null) {
                modifiedChests.add(result.destination());
            }

            if (result.overflowed()) {
                long amountOverflowed = originalCount - result.remainder().getCount();
                if (amountOverflowed > 0) {
                    overflowCounts.merge(originalVariant, amountOverflowed, Long::sum);
                    if (result.destinationName() != null) {
                        overflowDestinations.put(originalVariant, result.destinationName());
                    }
                }
            }

            if (!result.remainder().isEmpty()) {
                unsortedItems.add(result.remainder());
            }
        }

        for (ItemStack stack : unsortedItems) {
            insertIntoInventory(world, sourceProbe, stack);
        }

        // NEW: Organize destination chests that received items (fast operation)
        for (BlockPos chestPos : modifiedChests) {
            for (BlockPos probePos : linkedProbes) {
                BlockEntity be = world.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    if (chestPos.equals(probe.getTargetPos())) {
                        Inventory destInv = probe.getTargetInventory();
                        if (destInv != null) {
                            organizeInventory(destInv);
                        }
                        break;
                    }
                }
            }
        }
    }

    private void organizeInventory(Inventory inventory) {
        if (inventory == null) return;
        InventoryOrganizer.organize(inventory, organizationStrategy);
    }

    // ========================================
    // CHEST NAME SYNC (NBT-BASED)
    // ========================================

    //? if >=1.21.8 {
    private void writeNameToChest(BlockPos chestPos, String customName) {
        if (world == null) return;

        BlockEntity blockEntity = world.getBlockEntity(chestPos);
        if (blockEntity == null) return;

        NbtCompound nbt = blockEntity.createNbt(world.getRegistryManager());

        if (customName == null || customName.isEmpty()) {
            nbt.remove("CustomName");
        } else {
            Text textComponent = Text.literal(customName);
            DataResult<NbtElement> result = TextCodecs.CODEC.encodeStart(
                    world.getRegistryManager().getOps(NbtOps.INSTANCE),
                    textComponent
            );
            result.result().ifPresent(nbtElement -> {
                nbt.put("CustomName", nbtElement);
            });
        }

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

    // ========================================
    // INVENTORY INTERFACE (EMPTY)
    // ========================================

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

    // ========================================
    // SCREEN HANDLER FACTORY
    // ========================================

    @Override
    public Text getDisplayName() {
        return Text.translatable("container.smartsorter.storage_controller");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new StorageControllerScreenHandler(syncId, playerInventory, this);
    }

    // ========================================
    // CONFIG EXPORT
    // ========================================

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

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    //? if >=1.21.8 {
    private void writeProbesToView(WriteView view) {
        int probeCount = linkedProbes.size();
        view.putInt("probe_count", probeCount);
        for (int i = 0; i < probeCount; i++) {
            view.putLong(getProbeKey(i), linkedProbes.get(i).asLong());
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

        // Intakes
        view.putInt("intake_count", linkedIntakes.size());
        for (int i = 0; i < linkedIntakes.size(); i++) {
            view.putLong("intake_" + i, linkedIntakes.get(i).asLong());
        }

        // Process probes
        view.putInt("processProbeCount", linkedProcessProbes.size());
        int idx = 0;
        for (ProcessProbeConfig config : linkedProcessProbes.values()) {
            view.putLong(getPPPosKey(idx), config.position.asLong());
            if (config.customName != null) {
                view.putString(getPPNameKey(idx), config.customName);
            }
            view.putString(getPPTypeKey(idx), config.machineType);
            view.putBoolean(getPPEnabledKey(idx), config.enabled);
            view.putString(getPPRecipeKey(idx), config.recipeFilter.asString());
            view.putString(getPPFuelKey(idx), config.fuelFilter.asString());
            view.putInt(getPPProcessedKey(idx), config.itemsProcessed);
            view.putInt(getPPIndexKey(idx), config.index);
            idx++;
        }
        view.putInt("storedXp", storedExperience);

        // Chest configs
        view.putInt("chestConfigCount", chestConfigs.size());
        idx = 0;
        for (ChestConfig config : chestConfigs.values()) {
            view.putLong(getChestPosKey(idx), config.position.asLong());
            view.putString(getChestNameKey(idx), config.customName);
            view.putString(getChestCategoryKey(idx), config.filterCategory.asString());
            view.putInt(getChestPriorityKey(idx), config.priority);
            view.putString(getChestModeKey(idx), config.filterMode.name());
            view.putBoolean(getChestFrameKey(idx), config.autoItemFrame);
            idx++;
        }
    }

    @Override
    public void readData(ReadView view) {
        super.readData(view);
        readProbesFromView(view);

        // Intakes
        linkedIntakes.clear();
        int intakeCount = view.getInt("intake_count", 0);
        for (int i = 0; i < intakeCount; i++) {
            view.getOptionalLong("intake_" + i).ifPresent(posLong ->
                    linkedIntakes.add(BlockPos.fromLong(posLong))
            );
        }

        // Process probes
        linkedProcessProbes.clear();
        int probeCount = view.getInt("processProbeCount", 0);
        for (int i = 0; i < probeCount; i++) {
            ProcessProbeConfig config = new ProcessProbeConfig();

            view.getOptionalLong(getPPPosKey(i)).ifPresent(posLong ->
                    config.position = BlockPos.fromLong(posLong)
            );

            config.customName = view.getString(getPPNameKey(i), null);
            config.machineType = view.getString(getPPTypeKey(i), "Unknown");
            config.enabled = view.getBoolean(getPPEnabledKey(i), true);
            config.recipeFilter = RecipeFilterMode.fromString(
                    view.getString(getPPRecipeKey(i), "ORES_ONLY")
            );
            config.fuelFilter = FuelFilterMode.fromString(
                    view.getString(getPPFuelKey(i), "COAL_ONLY")
            );
            config.itemsProcessed = view.getInt(getPPProcessedKey(i), 0);
            config.index = view.getInt(getPPIndexKey(i), 0);

            if (config.position != null) {
                linkedProcessProbes.put(config.position, config);
            }
        }

        storedExperience = view.getInt("storedXp", 0);

        // Chest configs
        chestConfigs.clear();
        int chestCount = view.getInt("chestConfigCount", 0);
        for (int i = 0; i < chestCount; i++) {
            final int index = i;
            view.getOptionalLong(getChestPosKey(index)).ifPresent(posLong -> {
                BlockPos pos = BlockPos.fromLong(posLong);
                String name = view.getString(getChestNameKey(index), "");
                String categoryStr = view.getString(getChestCategoryKey(index), "smartsorter:all");

                int priority;
                try {
                    priority = view.getInt(getChestPriorityKey(index), 1);
                } catch (Exception e) {
                    String priorityStr = view.getString(getChestPriorityKey(index), "MEDIUM");
                    priority = switch (priorityStr) {
                        case "HIGH" -> 1;
                        case "LOW" -> 3;
                        default -> 2;
                    };
                }

                String modeStr = view.getString(getChestModeKey(index), "NONE");
                boolean autoFrame = view.getBoolean(getChestFrameKey(index), false);

                Category category = CategoryManager.getInstance().getCategory(categoryStr);
                ChestConfig.FilterMode mode = ChestConfig.FilterMode.valueOf(modeStr);

                ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
                chestConfigs.put(pos, config);
            });
        }

        this.needsValidation = true;
        if (!chestConfigs.isEmpty()) {
            Map<BlockPos, Integer> recalculated = priorityManager.recalculatePriorities(chestConfigs);
            applyPriorityUpdates(recalculated);
        }
    }
    //?} else {
    /*private void writeProbesToNbt(NbtCompound nbt) {
        nbt.putInt("probe_count", linkedProbes.size());
        for (int i = 0; i < linkedProbes.size(); i++) {
            nbt.putLong(getProbeKey(i), linkedProbes.get(i).asLong());
        }
    }

    private void readProbesFromNbt(NbtCompound nbt) {
        linkedProbes.clear();
        int count = nbt.getInt("probe_count");
        for (int i = 0; i < count; i++) {
            String key = getProbeKey(i);
            if (nbt.contains(key)) {
                linkedProbes.add(BlockPos.fromLong(nbt.getLong(key)));
            }
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        writeProbesToNbt(nbt);

        // Optimization config
        nbt.putBoolean("enableCompaction", enableInventoryCompaction);

        nbt.putInt("intake_count", linkedIntakes.size());
        for (int i = 0; i < linkedIntakes.size(); i++) {
            nbt.putLong("intake_" + i, linkedIntakes.get(i).asLong());
        }

        nbt.putInt("processProbeCount", linkedProcessProbes.size());
        int idx = 0;
        for (ProcessProbeConfig config : linkedProcessProbes.values()) {
            nbt.putLong(getPPPosKey(idx), config.position.asLong());
            if (config.customName != null) {
                nbt.putString(getPPNameKey(idx), config.customName);
            }
            nbt.putString(getPPTypeKey(idx), config.machineType);
            nbt.putBoolean(getPPEnabledKey(idx), config.enabled);
            nbt.putString(getPPRecipeKey(idx), config.recipeFilter.asString());
            nbt.putString(getPPFuelKey(idx), config.fuelFilter.asString());
            nbt.putInt(getPPProcessedKey(idx), config.itemsProcessed);
            nbt.putInt(getPPIndexKey(idx), config.index);
            idx++;
        }
        nbt.putInt("storedXp", storedExperience);

        nbt.putInt("chestConfigCount", chestConfigs.size());
        idx = 0;
        for (ChestConfig config : chestConfigs.values()) {
            nbt.putLong(getChestPosKey(idx), config.position.asLong());
            nbt.putString(getChestNameKey(idx), config.customName);
            nbt.putString(getChestCategoryKey(idx), config.filterCategory.asString());
            nbt.putInt(getChestPriorityKey(idx), config.priority);
            nbt.putString(getChestModeKey(idx), config.filterMode.name());
            nbt.putBoolean(getChestFrameKey(idx), config.autoItemFrame);
            idx++;
        }
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        readProbesFromNbt(nbt);

        // Optimization config
        this.enableInventoryCompaction = nbt.getBoolean("enableCompaction");

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

            if (nbt.contains(getPPPosKey(i))) {
                config.position = BlockPos.fromLong(nbt.getLong(getPPPosKey(i)));
            }

            config.customName = nbt.contains(getPPNameKey(i)) ? nbt.getString(getPPNameKey(i)) : null;
            config.machineType = nbt.contains(getPPTypeKey(i)) ? nbt.getString(getPPTypeKey(i)) : "Unknown";
            config.enabled = nbt.contains(getPPEnabledKey(i)) ? nbt.getBoolean(getPPEnabledKey(i)) : true;
            config.recipeFilter = RecipeFilterMode.fromString(
                    nbt.contains(getPPRecipeKey(i)) ? nbt.getString(getPPRecipeKey(i)) : "ORES_ONLY"
            );
            config.fuelFilter = FuelFilterMode.fromString(
                    nbt.contains(getPPFuelKey(i)) ? nbt.getString(getPPFuelKey(i)) : "COAL_ONLY"
            );
            config.itemsProcessed = nbt.getInt(getPPProcessedKey(i));
            config.index = nbt.getInt(getPPIndexKey(i));

            if (config.position != null) {
                linkedProcessProbes.put(config.position, config);
            }
        }

        storedExperience = nbt.getInt("storedXp");

        chestConfigs.clear();
        int chestCount = nbt.getInt("chestConfigCount");
        for (int i = 0; i < chestCount; i++) {
            if (!nbt.contains(getChestPosKey(i))) continue;

            BlockPos pos = BlockPos.fromLong(nbt.getLong(getChestPosKey(i)));
            String name = nbt.getString(getChestNameKey(i));
            String categoryStr = nbt.getString(getChestCategoryKey(i));

            int priority;
            try {
                priority = nbt.getInt(getChestPriorityKey(i));
            } catch (Exception e) {
                String priorityStr = nbt.getString(getChestPriorityKey(i));
                priority = switch (priorityStr) {
                    case "HIGH" -> 1;
                    case "LOW" -> 3;
                    default -> 2;
                };
            }

            String modeStr = nbt.getString(getChestModeKey(i));
            boolean autoFrame = nbt.getBoolean(getChestFrameKey(i));

            Category category = CategoryManager.getInstance().getCategory(categoryStr);
            ChestConfig.FilterMode mode = ChestConfig.FilterMode.valueOf(modeStr);

            ChestConfig config = new ChestConfig(pos, name, category, priority, mode, autoFrame);
            chestConfigs.put(pos, config);
        }

        this.needsValidation = true;
        if (!chestConfigs.isEmpty()) {
            Map<BlockPos, Integer> recalculated = priorityManager.recalculatePriorities(chestConfigs);
            applyPriorityUpdates(recalculated);
        }
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

    // ========================================
    // CLEANUP
    // ========================================

    public void onRemoved() {
        // Drop stored XP as experience orbs before destruction
        if (world != null && !world.isClient() && storedExperience > 0) {
            // Drop XP orbs at controller position
            net.minecraft.entity.ExperienceOrbEntity.spawn(
                    (ServerWorld) world,
                    Vec3d.ofCenter(pos),
                    storedExperience
            );
            storedExperience = 0;
        }

        // Notify all linked probes to clear their controller reference
        if (world != null && !world.isClient()) {
            // Unlink all output probes
            for (BlockPos probePos : new ArrayList<>(linkedProbes)) {
                BlockEntity be = world.getBlockEntity(probePos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    probe.clearController(); // Tell probe to unlink but keep config
                }
            }

            // Unlink all intake blocks
            for (BlockPos intakePos : new ArrayList<>(linkedIntakes)) {
                BlockEntity be = world.getBlockEntity(intakePos);
                if (be instanceof IntakeBlockEntity intake) {
                    intake.clearController(); // This method already exists!
                }
            }

            // Unlink all process probes
            for (BlockPos processProbePos : new ArrayList<>(linkedProcessProbes.keySet())) {
                BlockEntity be = world.getBlockEntity(processProbePos);
                if (be instanceof ProcessProbeBlockEntity processProbe) {
                    processProbe.clearController(); // Tell process probe to unlink
                }
            }

            // Clear chest names
            for (BlockPos chestPos : chestConfigs.keySet()) {
                clearNameFromChest(chestPos);
            }
        }

        // Clear local references
        linkedProbes.clear();
        linkedIntakes.clear();
        networkItems.clear();
        linkedProcessProbes.clear();
        chestConfigs.clear();
    }
}