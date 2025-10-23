package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.util.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StorageControllerScreenHandler extends ScreenHandler {
    // ========================================
    // CONSTANTS
    // ========================================

    private static final int PLAYER_INV_Y = 120;
    private static final int HOTBAR_Y = 178;
    private static final int PLAYER_INVENTORY_SIZE = 27;
    private static final int HOTBAR_SIZE = 9;
    private static final int TOTAL_SLOTS = PLAYER_INVENTORY_SIZE + HOTBAR_SIZE;
    private static final long SYNC_BATCH_DELAY_MS = 50;
    private static final int CONFIG_BATCH_SIZE = 5;

    // ========================================
    // FIELDS
    // ========================================

    // Server-side reference
    public final StorageControllerBlockEntity controller;
    public final BlockPos controllerPos;

    // Client-side cached state
    private Map<ItemVariant, Long> clientNetworkItems = new HashMap<>();
    private Map<BlockPos, ProcessProbeConfig> clientProbeConfigs = new HashMap<>();
    private Map<BlockPos, ChestConfig> clientChestConfigs = new HashMap<>();
    private int clientStoredXp = 0;

    // UI state
    private SortMode sortMode = SortMode.NAME;
    private Category filterCategory = Category.ALL;

    // Sync state
    private boolean needsFullSync = true;
    private boolean pendingSync = false;
    private long lastSyncTime = 0;

    // Priority management
    private final ChestPriorityManager priorityManager = new ChestPriorityManager();

    // ========================================
    // CONSTRUCTORS
    // ========================================

    public StorageControllerScreenHandler(int syncId, PlayerInventory inv, StorageControllerBlockEntity controller) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = controller;
        this.controllerPos = controller != null ? controller.getPos() : null;

        addPlayerInventory(inv);
        addPlayerHotbar(inv);

        if (inv.player instanceof ServerPlayerEntity sp) {
            sendNetworkUpdate(sp);
        }
    }

    public StorageControllerScreenHandler(int syncId, PlayerInventory inv) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = null;
        this.controllerPos = null;

        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    // ========================================
    // INVENTORY SETUP
    // ========================================

    private void addPlayerInventory(PlayerInventory inv) {
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
    }

    private void addPlayerHotbar(PlayerInventory inv) {
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(inv, col, 8 + col * 18, HOTBAR_Y));
        }
    }

    // ========================================
    // SCREEN HANDLER OVERRIDES
    // ========================================

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= this.slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = this.slots.get(slotIndex);
        if (slot == null || !slot.hasStack()) {
            return ItemStack.EMPTY;
        }

        ItemStack stackInSlot = slot.getStack();
        ItemStack original = stackInSlot.copy();

        // Check if it's a player slot (shift-click to network)
        if (slotIndex >= 0 && slotIndex < TOTAL_SLOTS) {
            if (controller == null) {
                return ItemStack.EMPTY;
            }

            ItemStack remaining = controller.insertItem(stackInSlot.copy()).remainder();
            int inserted = stackInSlot.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());

            if (inserted > 0) {
                slot.setStack(remaining);
                slot.markDirty();

                if (player instanceof ServerPlayerEntity sp) {
                    sendNetworkUpdate(sp);
                }

                return original;
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity player) {
        if (index < 0 || index >= this.slots.size()) {
            super.onSlotClick(index, button, type, player);
            if (controller != null && player instanceof ServerPlayerEntity sp) {
                sendNetworkUpdate(sp);
            }
            return;
        }

        super.onSlotClick(index, button, type, player);
        if (controller != null && player instanceof ServerPlayerEntity sp) {
            sendNetworkUpdate(sp);
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return controller == null || controller.canPlayerUse(player);
    }

    // ========================================
    // NETWORK SYNCING
    // ========================================

    public void sendNetworkUpdate(ServerPlayerEntity player) {
        if (controller == null) return;

        this.needsFullSync = true;

        controller.updateNetworkCache();
        Map<ItemVariant, Long> items = controller.getNetworkItems();
        int xp = controller.getStoredExperience();
        Map<BlockPos, ProcessProbeConfig> configs = controller.getProcessProbeConfigs();
        Map<BlockPos, ChestConfig> chestConfigs = controller.getChestConfigs();

        // Send items and XP first with empty configs to clear client state
        StorageControllerSyncPacket.send(player, items, xp, new HashMap<>());

        if (!configs.isEmpty()) {
            sendProbeConfigsInBatches(player, configs);
        }
        if (!chestConfigs.isEmpty()) {
            sendChestConfigsInBatches(player, chestConfigs);
        }

        this.needsFullSync = false;
    }

    public void sendNetworkUpdate(ServerPlayerEntity player, Map<ItemVariant, Long> changes) {
        if (controller == null) return;

        if (this.needsFullSync) {
            sendNetworkUpdate(player);
        } else if (!changes.isEmpty()) {
            ServerPlayNetworking.send(player, new StorageDeltaSyncPayload(changes));
        }
    }

    private void sendProbeConfigsInBatches(ServerPlayerEntity player, Map<BlockPos, ProcessProbeConfig> configs) {
        Map<BlockPos, ProcessProbeConfig> batch = new HashMap<>();
        int count = 0;

        for (Map.Entry<BlockPos, ProcessProbeConfig> entry : configs.entrySet()) {
            batch.put(entry.getKey(), entry.getValue());
            count++;

            if (count >= CONFIG_BATCH_SIZE) {
                ServerPlayNetworking.send(player, new ProbeConfigBatchPayload(new HashMap<>(batch)));
                batch.clear();
                count = 0;
            }
        }

        if (!batch.isEmpty()) {
            ServerPlayNetworking.send(player, new ProbeConfigBatchPayload(new HashMap<>(batch)));
        }
    }

    private void sendChestConfigsInBatches(ServerPlayerEntity player, Map<BlockPos, ChestConfig> configs) {
        Map<BlockPos, ChestConfig> batch = new HashMap<>();
        int count = 0;

        for (Map.Entry<BlockPos, ChestConfig> entry : configs.entrySet()) {
            batch.put(entry.getKey(), entry.getValue());
            count++;

            if (count >= CONFIG_BATCH_SIZE) {
                ServerPlayNetworking.send(player, new ChestConfigBatchPayload(new HashMap<>(batch)));
                batch.clear();
                count = 0;
            }
        }

        if (!batch.isEmpty()) {
            ServerPlayNetworking.send(player, new ChestConfigBatchPayload(new HashMap<>(batch)));
        }
    }

    private void requestBatchedSync() {
        pendingSync = true;
        lastSyncTime = System.currentTimeMillis();
    }

    public void processPendingSync(ServerPlayerEntity player) {
        if (pendingSync && System.currentTimeMillis() - lastSyncTime >= SYNC_BATCH_DELAY_MS) {
            sendNetworkUpdate(player);
            pendingSync = false;
        }
    }

    public void requestSync() {
        if (controller == null) {
            ClientPlayNetworking.send(new SyncRequestPayload());
        }
    }

    // ========================================
    // CLIENT STATE UPDATES
    // ========================================

    public void updateNetworkItems(Map<ItemVariant, Long> items) {
        this.clientNetworkItems = items;
    }

    public void updateProbeConfigs(Map<BlockPos, ProcessProbeConfig> configs) {
        this.clientProbeConfigs.putAll(configs);
    }

    public void clearProbeConfigs() {
        this.clientProbeConfigs.clear();
    }

    public void updateChestConfigs(Map<BlockPos, ChestConfig> configs) {
        this.clientChestConfigs.putAll(configs);
    }

    public void clearChestConfigs() {
        this.clientChestConfigs.clear();
    }

    public void updateStoredXp(int xp) {
        this.clientStoredXp = xp;
    }

    public void updateProbeStats(BlockPos position, int itemsProcessed) {
        if (controller != null) {
            ProcessProbeConfig config = controller.getProbeConfig(position);
            if (config != null) {
                config.itemsProcessed = itemsProcessed;
            }
        } else {
            ProcessProbeConfig config = clientProbeConfigs.get(position);
            if (config != null) {
                config.itemsProcessed = itemsProcessed;
                clientProbeConfigs.put(position, config);
            }
        }
    }

    public void refreshChestFullness() {
        if (controller == null) return;

        Map<BlockPos, ChestConfig> configs = controller.getChestConfigs();
        for (ChestConfig config : configs.values()) {
            config.cachedFullness = calculateChestFullness(config.position);
            config.previewItems = getChestPreviewItems(config.position);
        }
        clientChestConfigs.putAll(configs);
    }

    // ========================================
    // GETTERS
    // ========================================

    public Map<ItemVariant, Long> getNetworkItems() {
        if (controller != null) return controller.getNetworkItems();
        return new HashMap<>(clientNetworkItems);
    }

    public Map<BlockPos, ProcessProbeConfig> getProcessProbeConfigs() {
        if (controller != null) return controller.getProcessProbeConfigs();
        return new HashMap<>(clientProbeConfigs);
    }

    public Map<BlockPos, ChestConfig> getChestConfigs() {
        if (controller != null) return controller.getChestConfigs();
        return new HashMap<>(clientChestConfigs);
    }

    public int getStoredExperience() {
        if (controller != null) return controller.getStoredExperience();
        return clientStoredXp;
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public Category getFilterCategory() {
        return filterCategory;
    }

    // ========================================
    // SETTERS
    // ========================================

    public void setSortMode(SortMode mode) {
        this.sortMode = mode;
        PlayerEntity player = getPlayerFromSlots();
        if (player instanceof ServerPlayerEntity sp) {
            sendNetworkUpdate(sp);
        }
    }

    public void setFilterCategory(Category category) {
        this.filterCategory = category;
        PlayerEntity player = getPlayerFromSlots();
        if (player instanceof ServerPlayerEntity sp) {
            sendNetworkUpdate(sp);
        }
    }

    // ========================================
    // ITEM OPERATIONS
    // ========================================

    public void requestExtraction(ItemVariant variant, int amount, boolean toInventory) {
        if (controller != null) {
            PlayerEntity player = getPlayerFromSlots();
            if (player != null) {
                extractItem(variant, amount, toInventory, player);
            }
            if (player instanceof ServerPlayerEntity sp) {
                sendNetworkUpdate(sp);
            }
        } else {
            ClientPlayNetworking.send(new ExtractionRequestPayload(variant, amount, toInventory));
        }
    }

    public void requestDeposit(ItemStack stack, int amount) {
        if (controller != null) {
            PlayerEntity player = getPlayerFromSlots();
            if (player != null) {
                depositItem(stack, amount, player);
            }
            if (player instanceof ServerPlayerEntity sp) {
                sendNetworkUpdate(sp);
            }
        } else {
            ClientPlayNetworking.send(new DepositRequestPayload(ItemVariant.of(stack), amount));
        }
    }

    public void extractItem(ItemVariant variant, int amount, boolean toInventory, PlayerEntity player) {
        if (controller == null || player == null) return;

        ItemStack extracted = controller.extractItem(variant, amount);
        if (extracted.isEmpty()) return;

        if (toInventory) {
            int beforeCount = extracted.getCount();
            player.getInventory().insertStack(extracted);
            int afterCount = extracted.getCount();

            if (afterCount > 0) {
                player.dropItem(extracted, false);
            }
        } else {
            ItemStack cursor = getCursorStack();

            if (cursor.isEmpty()) {
                setCursorStack(extracted);
            } else if (ItemStack.areItemsAndComponentsEqual(cursor, extracted)) {
                int maxStack = Math.min(cursor.getMaxCount(), 64);
                int canAdd = maxStack - cursor.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, extracted.getCount());
                    cursor.increment(toAdd);
                    extracted.decrement(toAdd);
                    setCursorStack(cursor);
                }
                if (!extracted.isEmpty()) {
                    ItemStack remaining = controller.insertItem(extracted).remainder();
                    if (!remaining.isEmpty()) {
                        player.dropItem(remaining, false);
                    }
                }
            } else {
                if (!player.getInventory().insertStack(extracted)) {
                    player.dropItem(extracted, false);
                }
            }
        }

        if (player instanceof ServerPlayerEntity sp) {
            requestBatchedSync();
            player.playerScreenHandler.setCursorStack(getCursorStack());
        }
    }

    public void depositItem(ItemStack stack, int amount, PlayerEntity player) {
        if (controller == null || player == null || stack.isEmpty()) return;

        ItemStack cursor = getCursorStack();
        if (cursor.isEmpty()) return;

        ItemStack toDeposit = cursor.copy();
        toDeposit.setCount(Math.min(amount, cursor.getCount()));

        ItemStack remaining = controller.insertItem(toDeposit).remainder();
        int deposited = toDeposit.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());

        if (deposited > 0) {
            cursor.decrement(deposited);
            if (cursor.isEmpty()) {
                setCursorStack(ItemStack.EMPTY);
            } else {
                setCursorStack(cursor);
            }

            if (player instanceof ServerPlayerEntity sp) {
                requestBatchedSync();
                player.playerScreenHandler.setCursorStack(getCursorStack());
            }
        }
    }

    // ========================================
    // CONFIG MANAGEMENT
    // ========================================

    public void updateChestConfig(BlockPos position, ChestConfig config) {
        if (controller != null) {
            ChestConfig oldConfig = controller.getChestConfigs().get(position);

            // Check if this is a manual priority change (not SimplePriority dropdown)
            if (oldConfig != null &&
                    config.simplePrioritySelection == null &&
                    oldConfig.priority != config.priority &&
                    config.filterMode != ChestConfig.FilterMode.CUSTOM) {

                // Use priority manager for manual priority changes
                handlePriorityChange(position, config.priority);
                return;
            }

            // Check if this is a SimplePriority selection update
            if (config.simplePrioritySelection != null &&
                    config.filterMode != ChestConfig.FilterMode.CUSTOM) {

                // Let the controller handle it with priority management
                controller.updateChestConfig(position, config);

                // Refresh all configs after priority changes
                Map<BlockPos, ChestConfig> updatedConfigs = controller.getChestConfigs();
                clientChestConfigs.clear();
                clientChestConfigs.putAll(updatedConfigs);

                // Sync to clients
                PlayerEntity player = getPlayerFromSlots();
                if (player instanceof ServerPlayerEntity sp) {
                    sendChestConfigsInBatches(sp, updatedConfigs);
                }
            } else {
                // Normal update
                controller.updateChestConfig(position, config);
            }
        } else {
            clientChestConfigs.put(position, config);
        }
    }

    private int calculateChestFullness(BlockPos chestPos) {
        if (controller == null || controller.getWorld() == null) return -1;
        return controller.calculateChestFullness(chestPos);
    }

    private List<ItemStack> getChestPreviewItems(BlockPos chestPos) {
        if (controller == null) return new ArrayList<>();

        for (BlockPos probePos : controller.getLinkedProbes()) {
            BlockEntity be = controller.getWorld().getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (chestPos.equals(probe.getTargetPos())) {
                    Inventory inv = probe.getTargetInventory();
                    if (inv == null) break;

                    List<ItemStack> items = new ArrayList<>();
                    for (int i = 0; i < inv.size() && items.size() < 8; i++) {
                        ItemStack stack = inv.getStack(i);
                        if (!stack.isEmpty()) {
                            items.add(stack.copy());
                        }
                    }
                    return items;
                }
            }
        }
        return new ArrayList<>();
    }

    // ========================================
// PRIORITY MANAGEMENT
// ========================================

    /**
     * Handle chest addition with automatic priority assignment
     */
    public void handleChestAddition(BlockPos chestPos, ChestConfig config) {
        if (controller == null) return;

        Map<BlockPos, ChestConfig> allConfigs = new HashMap<>(controller.getChestConfigs());

        // Determine insertion priority based on SimplePriority selection
        int desiredPriority = 1;
        if (config.simplePrioritySelection != null && config.filterMode != ChestConfig.FilterMode.CUSTOM) {
            int regularCount = priorityManager.getRegularChestCount(allConfigs);
            desiredPriority = priorityManager.getInsertionPriority(config.simplePrioritySelection, regularCount);
        }

        // Insert chest and get updated priorities
        Map<BlockPos, Integer> newPriorities = priorityManager.insertChest(
                chestPos, config, desiredPriority, allConfigs
        );

        // Apply new priorities to all chests
        applyPriorityUpdates(newPriorities, allConfigs);

        // Send ONLY priority updates (optimized)
        sendPriorityUpdates(allConfigs);
    }

    /**
     * Handle chest removal with automatic priority adjustment
     */
    public void handleChestRemoval(BlockPos chestPos) {
        if (controller == null) return;

        Map<BlockPos, ChestConfig> allConfigs = new HashMap<>(controller.getChestConfigs());

        // Remove chest and get updated priorities
        Map<BlockPos, Integer> newPriorities = priorityManager.removeChest(chestPos, allConfigs);

        // Apply new priorities
        applyPriorityUpdates(newPriorities, allConfigs);

        // Send ONLY priority updates (optimized)
        sendPriorityUpdates(allConfigs);
    }

    /**
     * Handle manual priority change with automatic shifting
     */
    public void handlePriorityChange(BlockPos chestPos, int newPriority) {
        if (controller == null) return;

        Map<BlockPos, ChestConfig> allConfigs = new HashMap<>(controller.getChestConfigs());
        ChestConfig config = allConfigs.get(chestPos);

        if (config == null || config.filterMode == ChestConfig.FilterMode.CUSTOM) {
            return;
        }

        // Move chest and get updated priorities
        Map<BlockPos, Integer> newPriorities = priorityManager.moveChest(
                chestPos, newPriority, allConfigs
        );

        // Apply new priorities
        applyPriorityUpdates(newPriorities, allConfigs);

        // Send ONLY priority updates (optimized)
        sendPriorityUpdates(allConfigs);
    }

    /**
     * Handle SimplePriority selection from dropdown
     */
    public void handleSimplePrioritySelection(BlockPos chestPos, ChestConfig.SimplePriority simplePriority) {
        if (controller == null) return;

        Map<BlockPos, ChestConfig> allConfigs = new HashMap<>(controller.getChestConfigs());
        ChestConfig config = allConfigs.get(chestPos);

        if (config == null || config.filterMode == ChestConfig.FilterMode.CUSTOM) {
            return;
        }

        // Store the selection
        config.simplePrioritySelection = simplePriority;

        // Calculate target priority
        int regularCount = priorityManager.getRegularChestCount(allConfigs);
        int targetPriority = priorityManager.getInsertionPriority(simplePriority, regularCount);

        // Move to target position
        Map<BlockPos, Integer> newPriorities = priorityManager.moveChest(
                chestPos, targetPriority, allConfigs
        );

        // Apply new priorities
        applyPriorityUpdates(newPriorities, allConfigs);

        // Send ONLY priority updates (optimized)
        sendPriorityUpdates(allConfigs);
    }

    /**
     * Apply priority updates to all chest configs
     */
    private void applyPriorityUpdates(Map<BlockPos, Integer> newPriorities, Map<BlockPos, ChestConfig> configs) {
        for (Map.Entry<BlockPos, Integer> entry : newPriorities.entrySet()) {
            ChestConfig config = configs.get(entry.getKey());
            if (config != null) {
                config.priority = entry.getValue();

                // Update the SimplePriority selection to match new position
                if (config.filterMode != ChestConfig.FilterMode.CUSTOM) {
                    int regularCount = priorityManager.getRegularChestCount(configs);
                    config.simplePrioritySelection = ChestConfig.SimplePriority.fromNumeric(
                            config.priority, regularCount
                    );
                }

                config.updateHiddenPriority();
            }
        }

        // Save to controller
        if (controller != null) {
            for (ChestConfig config : configs.values()) {
                controller.updateChestConfig(config.position, config);
            }
            controller.markDirty();
        }
    }

    /**
     * Send optimized priority-only updates to all viewing clients
     */
    private void sendPriorityUpdates(Map<BlockPos, ChestConfig> configs) {
        if (controller == null || controller.getWorld() == null) return;
        if (!(controller.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld)) return;

        for (ChestConfig config : configs.values()) {
            config.cachedFullness = calculateChestFullness(config.position);
        }

        // Create lightweight priority update packet
        ChestPriorityBatchPayload payload = ChestPriorityBatchPayload.fromConfigs(configs);

        // Send to all players viewing this controller
        for (net.minecraft.server.network.ServerPlayerEntity player : serverWorld.getPlayers()) {
            if (player.currentScreenHandler == this) {
                ServerPlayNetworking.send(player, payload);
            }
        }

        // Update local client cache
        clientChestConfigs.putAll(configs);
    }

    /**
     * Client-side: Apply priority updates to cached configs
     */
    public void applyPriorityUpdatesFromServer(Map<BlockPos, ChestPriorityBatchPayload.PriorityUpdate> updates) {
        for (Map.Entry<BlockPos, ChestPriorityBatchPayload.PriorityUpdate> entry : updates.entrySet()) {
            ChestConfig config = clientChestConfigs.get(entry.getKey());
            if (config != null) {
                config.priority = entry.getValue().priority();
                config.simplePrioritySelection = entry.getValue().simplePriority();
                config.hiddenPriority = entry.getValue().hiddenPriority();
                config.cachedFullness = entry.getValue().cachedFullness();
            }
        }
    }

    /**
     * Recalculate all priorities (useful after loading or major changes)
     */
    public void recalculateAllPriorities() {
        if (controller == null) return;

        Map<BlockPos, ChestConfig> allConfigs = new HashMap<>(controller.getChestConfigs());
        Map<BlockPos, Integer> newPriorities = priorityManager.recalculatePriorities(allConfigs);
        applyPriorityUpdates(newPriorities, allConfigs);

        sendPriorityUpdates(allConfigs);
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private PlayerInventory getFirstPlayerInventory() {
        for (Slot s : this.slots) {
            if (s.inventory instanceof PlayerInventory pi) return pi;
        }
        return null;
    }

    private PlayerEntity getPlayerFromSlots() {
        PlayerInventory pi = getFirstPlayerInventory();
        return pi != null ? pi.player : null;
    }

    // ========================================
    // NETWORK PAYLOAD DEFINITIONS
    // ========================================

    public record SyncRequestPayload() implements CustomPayload {
        public static final Id<SyncRequestPayload> ID =
                new Id<>(Identifier.of(SmartSorter.MOD_ID, "sync_request"));
        public static final PacketCodec<RegistryByteBuf, SyncRequestPayload> CODEC =
                PacketCodec.of((b, p) -> {}, b -> new SyncRequestPayload());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record DepositRequestPayload(ItemVariant variant, int amount) implements CustomPayload {
        public static final Id<DepositRequestPayload> ID =
                new Id<>(Identifier.of(SmartSorter.MOD_ID, "deposit_request"));
        public static final PacketCodec<RegistryByteBuf, DepositRequestPayload> CODEC =
                PacketCodec.of((value, buf) -> write(buf, value), buf -> read(buf));

        public static void write(RegistryByteBuf buf, DepositRequestPayload payload) {
            ItemStack.PACKET_CODEC.encode(buf, payload.variant().toStack(1));
            buf.writeVarInt(payload.amount());
        }

        public static DepositRequestPayload read(RegistryByteBuf buf) {
            ItemStack s = ItemStack.PACKET_CODEC.decode(buf);
            return new DepositRequestPayload(ItemVariant.of(s), buf.readVarInt());
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record ExtractionRequestPayload(ItemVariant variant, int amount, boolean toInventory)
            implements CustomPayload {
        public static final Id<ExtractionRequestPayload> ID =
                new Id<>(Identifier.of(SmartSorter.MOD_ID, "extraction_request"));
        public static final PacketCodec<RegistryByteBuf, ExtractionRequestPayload> CODEC =
                PacketCodec.of((value, buf) -> write(buf, value), buf -> read(buf));

        public static void write(RegistryByteBuf buf, ExtractionRequestPayload payload) {
            ItemStack.PACKET_CODEC.encode(buf, payload.variant().toStack(1));
            buf.writeVarInt(payload.amount());
            buf.writeBoolean(payload.toInventory());
        }

        public static ExtractionRequestPayload read(RegistryByteBuf buf) {
            ItemStack s = ItemStack.PACKET_CODEC.decode(buf);
            ItemVariant v = ItemVariant.of(s);
            int amt = buf.readVarInt();
            boolean inv = buf.readBoolean();
            return new ExtractionRequestPayload(v, amt, inv);
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}