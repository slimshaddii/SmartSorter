package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
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
import net.shaddii.smartsorter.network.ProbeConfigBatchPayload;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.SortMode;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.StorageControllerSyncPacket;

import java.util.HashMap;
import java.util.Map;

/**
 * OPTIMIZATIONS:
 * - Removed virtual slots completely
 * - Server sends raw data, client handles all filtering/sorting
 */
public class StorageControllerScreenHandler extends ScreenHandler {
    public final StorageControllerBlockEntity controller;
    private Map<ItemVariant, Long> clientNetworkItems = new HashMap<>();
    private Map<BlockPos, ProcessProbeConfig> clientProbeConfigs = new HashMap<>();

    private SortMode sortMode = SortMode.NAME;
    private Category filterCategory = Category.ALL;

    // Slot indices now correct without virtual slots
    private static final int PLAYER_INV_Y = 120;
    private static final int HOTBAR_Y = 178;
    private static final int PLAYER_INVENTORY_SIZE = 27;
    private static final int HOTBAR_SIZE = 9;
    private static final int TOTAL_SLOTS = PLAYER_INVENTORY_SIZE + HOTBAR_SIZE;

    private boolean pendingSync = false;
    private long lastSyncTime = 0;
    private static final long SYNC_BATCH_DELAY_MS = 50;

    // XP Tracking
    private int clientStoredXp = 0;

    public StorageControllerScreenHandler(int syncId, PlayerInventory inv, StorageControllerBlockEntity controller) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = controller;

        addPlayerInventory(inv);
        addPlayerHotbar(inv);

        if (inv.player instanceof ServerPlayerEntity sp) sendNetworkUpdate(sp);
    }

    public StorageControllerScreenHandler(int syncId, PlayerInventory inv) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = null;

        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    public void updateStoredXp(int xp) {
        this.clientStoredXp = xp;
    }

    public int getStoredExperience() {
        if (controller != null) {
            return controller.getStoredExperience();
        }
        return clientStoredXp;
    }

    private void sendProbeConfigsInBatches(ServerPlayerEntity player, Map<BlockPos, ProcessProbeConfig> configs) {
        Map<BlockPos, ProcessProbeConfig> batch = new HashMap<>();
        int count = 0;

        for (Map.Entry<BlockPos, ProcessProbeConfig> entry : configs.entrySet()) {
            batch.put(entry.getKey(), entry.getValue());
            count++;

            // Send every 5 configs
            if (count >= 5) {
                // Pass a COPY of the map
                ServerPlayNetworking.send(player, new ProbeConfigBatchPayload(new HashMap<>(batch)));
                batch.clear();
                count = 0;
            }
        }

        // Send remaining configs
        if (!batch.isEmpty()) {
            // Pass a COPY of the map
            ServerPlayNetworking.send(player, new ProbeConfigBatchPayload(new HashMap<>(batch)));
        }
    }


    public void sendNetworkUpdate(ServerPlayerEntity player) {
        if (controller != null) {
            controller.updateNetworkCache();
            Map<ItemVariant, Long> items = controller.getNetworkItems();
            int xp = controller.getStoredExperience();
            Map<BlockPos, ProcessProbeConfig> configs = controller.getProcessProbeConfigs();

            // Always send items and XP first with EMPTY configs to clear client state
            StorageControllerSyncPacket.send(player, items, xp, new HashMap<>());

            // Then send all configs in batches (even if less than 10)
            if (!configs.isEmpty()) {
                sendProbeConfigsInBatches(player, configs);
            }
        }
    }


    public void updateNetworkItems(Map<ItemVariant, Long> items) {
        this.clientNetworkItems = items;
    }

    public Map<ItemVariant, Long> getNetworkItems() {
        if (controller != null) return controller.getNetworkItems();
        return new HashMap<>(clientNetworkItems);
    }

    public void requestSync() {
        if (controller == null) ClientPlayNetworking.send(new SyncRequestPayload());
    }

    public void setSortMode(SortMode mode) {
        this.sortMode = mode;
        PlayerEntity player = getPlayerFromSlots();
        if (player instanceof ServerPlayerEntity sp) {
            sendNetworkUpdate(sp);
        }
    }

    public SortMode getSortMode() {
        return sortMode;
    }

    public void setFilterCategory(Category category) {
        this.filterCategory = category;
        PlayerEntity player = getPlayerFromSlots();
        if (player instanceof ServerPlayerEntity sp) {
            sendNetworkUpdate(sp);
        }
    }

    public Category getFilterCategory() {
        return filterCategory;
    }

    public void requestExtraction(ItemVariant variant, int amount, boolean toInventory) {
        if (controller != null) {
            PlayerEntity player = getPlayerFromSlots();
            if (player != null) extractItem(variant, amount, toInventory, player);
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
            if (player != null) depositItem(stack, amount, player);
            if (player instanceof ServerPlayerEntity sp) {
                sendNetworkUpdate(sp);
            }
        } else {
            ClientPlayNetworking.send(new DepositRequestPayload(ItemVariant.of(stack), amount));
        }
    }

    public void depositItem(ItemStack stack, int amount, PlayerEntity player) {
        if (controller == null || player == null || stack.isEmpty()) {
            return;
        }

        ItemStack cursor = getCursorStack();
        if (cursor.isEmpty()) {
            return;
        }

        ItemStack toDeposit = cursor.copy();
        toDeposit.setCount(Math.min(amount, cursor.getCount()));

        ItemStack remaining = controller.insertItem(toDeposit);
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
                // Sync cursor immediately
                player.playerScreenHandler.setCursorStack(getCursorStack());
            }
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

    // 🔧 TODO: Call processPendingSync(player) periodically from controller tick
    // This will be handled by the controller calling it

    public void extractItem(ItemVariant variant, int amount, boolean toInventory, PlayerEntity player) {
        if (controller == null || player == null) {
            return;
        }

        ItemStack extracted = controller.extractItem(variant, amount);
        if (extracted.isEmpty()) {
            return;
        }

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
                    ItemStack remaining = controller.insertItem(extracted);
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
            // Sync cursor immediately
            player.playerScreenHandler.setCursorStack(getCursorStack());
        }
    }

    private PlayerInventory getFirstPlayerInventory() {
        for (Slot s : this.slots)
            if (s.inventory instanceof PlayerInventory pi) return pi;
        return null;
    }

    public void updateProbeStats(BlockPos position, int itemsProcessed) {
        // On the server side, update the controller
        if (controller != null) {
            ProcessProbeConfig config = controller.getProbeConfig(position);
            if (config != null) {
                config.itemsProcessed = itemsProcessed;
            }
        } else {
            // On the client side, update the cached configs
            ProcessProbeConfig config = clientProbeConfigs.get(position);
            if (config != null) {
                config.itemsProcessed = itemsProcessed;
                clientProbeConfigs.put(position, config); // Ensure it's updated
            }
        }
    }


    private PlayerEntity getPlayerFromSlots() {
        PlayerInventory pi = getFirstPlayerInventory();
        return pi != null ? pi.player : null;
    }

    private void addPlayerInventory(PlayerInventory inv) {
        // Main inventory (slots 0-26)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
    }

    private void addPlayerHotbar(PlayerInventory inv) {
        // Hotbar (slots 27-35)
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(inv, col, 8 + col * 18, HOTBAR_Y));
        }
    }

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

        // Check if it's ANY player slot (inventory or hotbar)
        if (slotIndex >= 0 && slotIndex < TOTAL_SLOTS) {
            if (controller == null) {
                return ItemStack.EMPTY;
            }

            // Try to insert into network storage
            ItemStack remaining = controller.insertItem(stackInSlot.copy());
            int inserted = stackInSlot.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());

            if (inserted > 0) {
                // Update the slot with what's left
                slot.setStack(remaining);
                slot.markDirty();

                if (player instanceof ServerPlayerEntity sp) {
                    sendNetworkUpdate(sp);
                }

                // Return original to signal success
                return original;
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity player) {
        if (index < 0 || index >= this.slots.size()) {
            // Handle clicks outside slot bounds
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

    public Map<BlockPos, ProcessProbeConfig> getProcessProbeConfigs() {
        if (controller != null) {
            return controller.getProcessProbeConfigs();
        }
        return new HashMap<>(clientProbeConfigs);
    }

    public void updateProbeConfigs(Map<BlockPos, ProcessProbeConfig> configs) {
        this.clientProbeConfigs.putAll(configs);
    }

    public void clearProbeConfigs() {
        this.clientProbeConfigs.clear();
    }

    // Payloads (unchanged)
    public record SyncRequestPayload() implements CustomPayload {
        public static final Id<SyncRequestPayload> ID =
                new Id<>(Identifier.of(SmartSorter.MOD_ID, "sync_request"));
        public static final PacketCodec<RegistryByteBuf, SyncRequestPayload> CODEC =
                PacketCodec.of((b, p) -> {}, b -> new SyncRequestPayload());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
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

        @Override public Id<? extends CustomPayload> getId() { return ID; }
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

        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
}