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
    // addPlayerSlots(inv, 8, 120)
    // Hotbar at y + 58 = 120 + 58 = 178

    // Slot indices
    private static final int PLAYER_INVENTORY_START = 0;
    private static final int PLAYER_INVENTORY_SIZE = 27; // 3 rows × 9 columns
    private static final int HOTBAR_SIZE = 9;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + PLAYER_INVENTORY_SIZE + HOTBAR_SIZE; // 36

    private static final int PLAYER_INV_Y = 120;
    private static final int HOTBAR_Y = 178;

    public StorageControllerScreenHandler(int syncId, PlayerInventory playerInventory, StorageControllerBlockEntity controller) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = controller;

        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);

        if (playerInventory.player instanceof ServerPlayerEntity serverPlayer) {
            sendNetworkUpdate(serverPlayer);
        }
    }

    public StorageControllerScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, syncId);
        this.controller = null;

        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);
    }

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
        if (controller != null) {
            return controller.getNetworkItems();
        }
        return new HashMap<>(clientNetworkItems);
    }

    public void requestSync() {
        if (controller == null) {
            ClientPlayNetworking.send(new SyncRequestPayload());
        }
    }

    public void requestExtraction(ItemVariant variant, int amount, boolean toInventory) {
        if (controller != null) {
            PlayerEntity player = getPlayerFromSlots();
            if (player != null) {
                extractItem(variant, amount, toInventory, player);
            }
        } else {
            ClientPlayNetworking.send(new ExtractionRequestPayload(variant, amount, toInventory));
        }
    }

    // Deposit request payload
    public record DepositRequestPayload(ItemVariant variant, int amount) implements CustomPayload {
        public static final CustomPayload.Id<DepositRequestPayload> ID =
                new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "deposit_request"));

        public static final PacketCodec<RegistryByteBuf, DepositRequestPayload> CODEC = PacketCodec.of(
                (value, buf) -> {
                    ItemStack.PACKET_CODEC.encode(buf, value.variant.toStack(1));
                    buf.writeVarInt(value.amount);
                },
                buf -> {
                    ItemStack stack = ItemStack.PACKET_CODEC.decode(buf);
                    ItemVariant variant = ItemVariant.of(stack);
                    int amount = buf.readVarInt();
                    return new DepositRequestPayload(variant, amount);
                }
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Request deposit (called from client)
     */
    public void requestDeposit(ItemStack stack, int amount) {
        if (controller != null) {
            // Server-side: directly deposit
            PlayerEntity player = getPlayerFromSlots();
            if (player != null) {
                depositItem(stack, amount, player);
            }
        } else {
            // Client-side: send packet to server
            ClientPlayNetworking.send(new DepositRequestPayload(ItemVariant.of(stack), amount));
        }
    }

    /**
     * Deposit item from cursor into network (server-side)
     */
    /**
     * Deposit item from cursor into network (server-side)
     */
    public void depositItem(ItemStack stack, int amount, PlayerEntity player) {
        if (controller != null && player != null && !stack.isEmpty()) {
            ItemStack cursor = getCursorStack();
            if (cursor.isEmpty()) return;

            ItemStack toDeposit = cursor.copy();
            toDeposit.setCount(Math.min(amount, cursor.getCount()));

            ItemStack remaining = controller.insertItem(toDeposit);

            int deposited = toDeposit.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());

            if (deposited > 0) {
                // Reduce cursor stack
                cursor.decrement(deposited);

                if (cursor.isEmpty()) {
                    setCursorStack(ItemStack.EMPTY);
                }

                // Update network
                if (player instanceof ServerPlayerEntity serverPlayer) {
                    sendNetworkUpdate(serverPlayer);
                }
            }
        }
    }

    private PlayerEntity getPlayerFromSlots() {
        for (Slot slot : this.slots) {
            if (slot.inventory instanceof PlayerInventory playerInv) {
                return playerInv.player;
            }
        }
        return null;
    }

    /**
     * Extract item from network (server-side)
     */
    public void extractItem(ItemVariant variant, int amount, boolean toInventory, PlayerEntity player) {
        if (controller != null && player != null) {
            ItemStack extracted = controller.extractItem(variant, amount);

            if (!extracted.isEmpty()) {
                if (toInventory) {
                    // Shift-click: Insert directly into player inventory
                    if (!player.getInventory().insertStack(extracted)) {
                        player.dropItem(extracted, false);
                    }
                } else {
                    // Normal click: Put in cursor
                    ItemStack cursor = getCursorStack();

                    if (cursor.isEmpty()) {
                        // Empty cursor - just set it
                        setCursorStack(extracted);
                    } else if (ItemStack.areItemsAndComponentsEqual(cursor, extracted)) {
                        // Same item - try to merge
                        int maxStack = Math.min(cursor.getMaxCount(), 64);
                        int canAdd = maxStack - cursor.getCount();

                        if (canAdd > 0) {
                            int toAdd = Math.min(canAdd, extracted.getCount());
                            cursor.increment(toAdd);
                            extracted.decrement(toAdd);
                        }

                        // Put remaining back in network
                        if (!extracted.isEmpty()) {
                            ItemStack remaining = controller.insertItem(extracted);
                            if (!remaining.isEmpty()) {
                                player.dropItem(remaining, false);
                            }
                        }
                    } else {
                        // Different item - try to put extracted in inventory instead
                        if (!player.getInventory().insertStack(extracted)) {
                            player.dropItem(extracted, false);
                        }
                    }
                }

                // Update network cache and sync to client
                if (player instanceof ServerPlayerEntity serverPlayer) {
                    sendNetworkUpdate(serverPlayer);
                }
            }
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        Slot slot = this.slots.get(slotIndex);

        if (slot == null || !slot.hasStack()) {
            return ItemStack.EMPTY;
        }

        ItemStack stackInSlot = slot.getStack();
        ItemStack originalStack = stackInSlot.copy();

        // Check if the clicked slot is in the player inventory (main inventory + hotbar)
        if (slotIndex >= PLAYER_INVENTORY_START && slotIndex < PLAYER_INVENTORY_END) {

            // Server-side check
            if (controller == null) {
                SmartSorter.LOGGER.warn("QuickMove called but controller is null (client-side?)");
                return ItemStack.EMPTY;
            }

            // Try to insert into network
            ItemStack toInsert = stackInSlot.copy();
            ItemStack remaining = controller.insertItem(toInsert);

            int inserted = toInsert.getCount() - (remaining.isEmpty() ? 0 : remaining.getCount());

            if (inserted > 0) {
                // Update the slot
                if (remaining.isEmpty()) {
                    slot.setStack(ItemStack.EMPTY);
                } else {
                    slot.setStack(remaining);
                }

                slot.markDirty();

                // Sync to client
                if (player instanceof ServerPlayerEntity serverPlayer) {
                    sendNetworkUpdate(serverPlayer);
                }

                return originalStack; // Return original for transfer animation
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        super.onSlotClick(slotIndex, button, actionType, player);

        if (!player.getWorld().isClient && controller != null) {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                sendNetworkUpdate(serverPlayer);
            }
        }
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        if (controller != null) {
            return controller.canPlayerUse(player);
        }
        return true;
    }

    private void addPlayerInventory(PlayerInventory playerInventory) {
        // Main inventory (3 rows × 9 columns)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(
                        playerInventory,
                        col + row * 9 + 9, // Skip hotbar slots (0-8)
                        8 + col * 18,
                        PLAYER_INV_Y + row * 18
                ));
            }
        }
    }

    private void addPlayerHotbar(PlayerInventory playerInventory) {
        // Hotbar (9 slots)
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(
                    playerInventory,
                    col, // Slots 0-8
                    8 + col * 18,
                    HOTBAR_Y
            ));
        }
    }

    public record SyncRequestPayload() implements CustomPayload {
        public static final CustomPayload.Id<SyncRequestPayload> ID =
                new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "sync_request"));

        public static final PacketCodec<RegistryByteBuf, SyncRequestPayload> CODEC = PacketCodec.of(
                (value, buf) -> {},
                buf -> new SyncRequestPayload()
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record ExtractionRequestPayload(ItemVariant variant, int amount, boolean toInventory) implements CustomPayload {
        public static final CustomPayload.Id<ExtractionRequestPayload> ID =
                new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "extraction_request"));

        public static final PacketCodec<RegistryByteBuf, ExtractionRequestPayload> CODEC = PacketCodec.of(
                (value, buf) -> {
                    ItemStack.PACKET_CODEC.encode(buf, value.variant.toStack(1));
                    buf.writeVarInt(value.amount);
                    buf.writeBoolean(value.toInventory);
                },
                buf -> {
                    ItemStack stack = ItemStack.PACKET_CODEC.decode(buf);
                    ItemVariant variant = ItemVariant.of(stack);
                    int amount = buf.readVarInt();
                    boolean toInventory = buf.readBoolean();
                    return new ExtractionRequestPayload(variant, amount, toInventory);
                }
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}