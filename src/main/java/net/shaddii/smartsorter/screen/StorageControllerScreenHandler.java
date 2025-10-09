package net.shaddii.smartsorter.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.StorageControllerSyncPacket;

import java.util.HashMap;
import java.util.Map;

public class StorageControllerScreenHandler extends ScreenHandler {
    public final StorageControllerBlockEntity controller;
    private Map<ItemVariant, Long> clientNetworkItems = new HashMap<>();

    // --- dynamic free-slot logic ---
    public static final int ROWS_VISIBLE = 3;
    public static final int COLS = 9;
    public static final int MAX_VISIBLE_FREE_SLOTS = ROWS_VISIBLE * COLS;
    
    // constants
    private static final int PLAYER_INV_Y = 120;
    private static final int HOTBAR_Y = 178;
    private static final int PLAYER_INVENTORY_START = MAX_VISIBLE_FREE_SLOTS; // Start after virtual slots
    private static final int PLAYER_INVENTORY_SIZE = 27;
    private static final int HOTBAR_SIZE = 9;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + PLAYER_INVENTORY_SIZE + HOTBAR_SIZE;
    private int scrollOffset = 0; // current scroll position
    

    public StorageControllerScreenHandler(int syncId, PlayerInventory inv, StorageControllerBlockEntity controller) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = controller;

        // Add fixed number of virtual free slots first (always 27 slots)
        addVirtualFreeSlots();
        
        // Then add player inventory slots
        addPlayerInventory(inv);
        addPlayerHotbar(inv);

        // ensure initial sync on open
        if (inv.player instanceof ServerPlayerEntity sp) sendNetworkUpdate(sp);
    }

    public StorageControllerScreenHandler(int syncId, PlayerInventory inv) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = null;
        
        // Add fixed number of virtual free slots first (always 27 slots)
        addVirtualFreeSlots();
        
        // Then add player inventory slots
        addPlayerInventory(inv);
        addPlayerHotbar(inv);
    }

    // --- free-slot handling ---

    public void setScrollOffset(int offset) {
        scrollOffset = Math.max(offset, 0);
        updateDisplayedSlots();
    }

    public int getScrollOffset() {
        return scrollOffset;
    }

    // Add fixed number of virtual free slots (always 27 slots to prevent sync issues)
    private void addVirtualFreeSlots() {
        int x = 8;
        int y = 18;
        for (int i = 0; i < MAX_VISIBLE_FREE_SLOTS; i++) {
            int slotX = x + (i % COLS) * 18;
            int slotY = y + (i / COLS) * 18;
            this.addSlot(new VirtualFreeSlot(i, slotX, slotY));
        }
    }
    
    // Update virtual slot contents based on scroll position (no slot addition/removal)
    public void updateDisplayedSlots() {
        // Virtual slots are now fixed - we just update their contents
        // This method is kept for compatibility but doesn't change slot count
    }

    // called whenever controller inventory changes; keeps 3 rows free
    public void autoMaintainFreeRows() {
        if (controller == null) return;
        // Note: expandFreeSlotPool method doesn't exist in StorageControllerBlockEntity
        // The free slots are managed by the actual linked inventories, not expanded artificially
        updateDisplayedSlots();
    }

    // --- sync + network ---

    public void sendNetworkUpdate(ServerPlayerEntity player) {
        if (controller != null) {
            controller.updateNetworkCache();
            StorageControllerSyncPacket.send(player, controller.getNetworkItems());
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

    // --- item deposit/extract ---

    public void requestExtraction(ItemVariant variant, int amount, boolean toInventory) {
        if (controller != null) {
            PlayerEntity player = getPlayerFromSlots();
            if (player != null) extractItem(variant, amount, toInventory, player);
        } else {
            ClientPlayNetworking.send(new ExtractionRequestPayload(variant, amount, toInventory));
        }
    }

    public void requestDeposit(ItemStack stack, int amount) {
        if (controller != null) {
            PlayerEntity player = getPlayerFromSlots();
            if (player != null) depositItem(stack, amount, player);
        } else {
            ClientPlayNetworking.send(new DepositRequestPayload(ItemVariant.of(stack), amount));
        }
    }

    public void depositItem(ItemStack stack, int amount, PlayerEntity player) {
        if (controller != null && player != null && !stack.isEmpty()) {
            ItemStack cursor = getCursorStack();
            if (cursor.isEmpty()) return;

            ItemStack toDeposit = cursor.copy();
            toDeposit.setCount(Math.min(amount, cursor.getCount()));
            ItemStack remaining = controller.insertItem(toDeposit);

            int deposited = toDeposit.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());
            if (deposited > 0) {
                cursor.decrement(deposited);
                if (cursor.isEmpty()) setCursorStack(ItemStack.EMPTY);
                if (player instanceof ServerPlayerEntity sp) sendNetworkUpdate(sp);
            }

            // keep 3 rows free visually
            autoMaintainFreeRows();
        }
    }

    public void extractItem(ItemVariant variant, int amount, boolean toInventory, PlayerEntity player) {
        if (controller == null || player == null) return;
        ItemStack extracted = controller.extractItem(variant, amount);
        if (extracted.isEmpty()) return;

        if (toInventory) {
            if (!player.getInventory().insertStack(extracted)) player.dropItem(extracted, false);
        } else {
            ItemStack cursor = getCursorStack();
            if (cursor.isEmpty()) setCursorStack(extracted);
            else if (ItemStack.areItemsAndComponentsEqual(cursor, extracted)) {
                int maxStack = Math.min(cursor.getMaxCount(), 64);
                int canAdd = maxStack - cursor.getCount();
                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, extracted.getCount());
                    cursor.increment(toAdd);
                    extracted.decrement(toAdd);
                }
                if (!extracted.isEmpty()) {
                    ItemStack remaining = controller.insertItem(extracted);
                    if (!remaining.isEmpty()) player.dropItem(remaining, false);
                }
            } else if (!player.getInventory().insertStack(extracted))
                player.dropItem(extracted, false);
        }

        if (player instanceof ServerPlayerEntity sp) sendNetworkUpdate(sp);
        autoMaintainFreeRows();
    }

    // --- util helpers ---

    private PlayerInventory getFirstPlayerInventory() {
        for (Slot s : this.slots)
            if (s.inventory instanceof PlayerInventory pi) return pi;
        return null;
    }

    private PlayerEntity getPlayerFromSlots() {
        PlayerInventory pi = getFirstPlayerInventory();
        return pi != null ? pi.player : null;
    }

    private void addPlayerInventory(PlayerInventory inv) {
        for (int row = 0; row < 3; ++row)
            for (int col = 0; col < 9; ++col)
                this.addSlot(new Slot(inv, col + row * 9 + 9, 8 + col * 18, PLAYER_INV_Y + row * 18));
    }


    private void addPlayerHotbar(PlayerInventory inv) {
        for (int col = 0; col < 9; ++col)
            this.addSlot(new Slot(inv, col, 8 + col * 18, HOTBAR_Y));
    }

    // --- vanilla overrides ---

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        // Safety check to prevent crashes
        if (index < 0 || index >= this.slots.size()) return ItemStack.EMPTY;
        
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasStack()) return ItemStack.EMPTY;
        ItemStack stackInSlot = slot.getStack();
        ItemStack original = stackInSlot.copy();

        if (index >= PLAYER_INVENTORY_START && index < PLAYER_INVENTORY_END) {
            if (controller == null) return ItemStack.EMPTY;
            ItemStack remaining = controller.insertItem(stackInSlot.copy());
            if (remaining.getCount() < stackInSlot.getCount()) {
                slot.setStack(remaining);
                slot.markDirty();
                if (player instanceof ServerPlayerEntity sp) sendNetworkUpdate(sp);
                autoMaintainFreeRows();
                return original;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void onSlotClick(int index, int button, SlotActionType type, PlayerEntity player) {
        // Safety check to prevent crashes
        if (index < 0 || index >= this.slots.size()) return;
        
        super.onSlotClick(index, button, type, player);
        if (controller != null && player instanceof ServerPlayerEntity sp) sendNetworkUpdate(sp);
        autoMaintainFreeRows();
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return controller == null || controller.canPlayerUse(player);
    }

    // --- payloads (unchanged) ---
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
