package net.shaddii.smartsorter.blockentity;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter; // DEBUG: For debug logging
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.util.SortUtil;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import org.jetbrains.annotations.Nullable;

// Fixed for Minecraft 1.21.9 compatibility
// Main changes: setChanged() → markDirty(), NBT methods return Optional

public class OutputProbeBlockEntity extends BlockEntity {
    // Configuration
    public boolean ignoreComponents = true;  // ID-only matching by default
    public boolean useTags = false;
    public boolean requireAllTags = false;

    // Mode
    public ProbeMode mode = ProbeMode.FILTER; // Default to filter mode

    // Controller link
    private BlockPos linkedController = null;

    public enum ProbeMode {
        FILTER,      // Only accepts matching items
        ACCEPT_ALL,  // Accepts anything
        PRIORITY     // Future: Priority-based routing with configurable priority levels
    }

    public OutputProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROBE_BE_TYPE, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, OutputProbeBlockEntity be) {
        // ✅ Correct for 1.21.9
        if (world.isClient()) return;
    }

    // === Controller Link Management ===

    public void setLinkedController(BlockPos controllerPos) {
        this.linkedController = controllerPos;
        // 1.21.9: setChanged() renamed to markDirty()
        markDirty();
    }

    // Link status and position (for persistence)
    private boolean isLinked = false;
    private BlockPos linkedControllerPos = null;

    public BlockPos getLinkedController() {
        return linkedController;
    }

    // === Storage Access ===

    /**
     * Get the storage of the chest or block this probe is facing.
     */
    public Storage<ItemVariant> getTargetStorage() {
        if (world == null) return null;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);

        // Try Fabric sided storage
        Storage<ItemVariant> sidedStorage = ItemStorage.SIDED.find(world, targetPos, face.getOpposite());
        if (sidedStorage != null) return sidedStorage;

        // Try unsided
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(world, targetPos, null);
        if (storage != null) return storage;

        // Fallback to inventory wrapper
        Inventory inv = getTargetInventory();
        if (inv != null) return InventoryStorage.of(inv, null);

        return null;
    }

    /**
     * Get the inventory this probe is facing.
     * Handles vanilla and modded inventories.
     */
    public Inventory getTargetInventory() {
        if (world == null) return null;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);
        BlockState targetState = world.getBlockState(targetPos);

        // Vanilla chest (merges double chests)
        if (targetState.getBlock() instanceof net.minecraft.block.ChestBlock chestBlock) {
            Inventory chestInv = net.minecraft.block.ChestBlock.getInventory(chestBlock, targetState, world, targetPos, true);
            if (chestInv != null) {
                // DEBUG: SmartSorter.LOGGER.info("Probe at {} - Found vanilla chest, size: {}", pos, chestInv.size());
                return chestInv;
            }
        }

        // Modded inventories
        BlockEntity be = world.getBlockEntity(targetPos);
        if (be instanceof Inventory inv) {
            // DEBUG: SmartSorter.LOGGER.info("Probe at {} - Found modded inventory ({}), size: {}",
            //         pos, inv.getClass().getSimpleName(), inv.size());
            return inv;
        }

        // DEBUG: SmartSorter.LOGGER.warn("Probe at {} - No inventory found at {}", pos, targetPos);
        return null;
    }

    // === Item Acceptance Logic ===

    /**
     * Check if this probe accepts the given item.
     */
    public boolean accepts(ItemVariant incoming) {
        if (world == null) return false;

        Inventory inv = getTargetInventory();
        if (inv == null) {
            // DEBUG: SmartSorter.LOGGER.warn("Probe accepts() - No inventory found");
            return false;
        }

        int invSize = inv.size();
        // String itemName = incoming.getItem().getName().getString(); // DEBUG: Used in debug logging

        // DEBUG: SmartSorter.LOGGER.info("=== ACCEPT CHECK ===");
        // DEBUG: SmartSorter.LOGGER.info("Probe: {}, Mode: {}", pos, mode);
        // DEBUG: SmartSorter.LOGGER.info("Item: {}, Inventory Size: {}", itemName, invSize);

        // ACCEPT_ALL
        if (mode == ProbeMode.ACCEPT_ALL) {
            boolean hasSpace = hasSpaceInInventory(inv, incoming, 1);

            // int emptyCount = 0; // DEBUG: Used in debug logging
            // for (int i = 0; i < invSize; i++) {
            //     if (inv.getStack(i).isEmpty()) emptyCount++;
            // }

            // DEBUG: SmartSorter.LOGGER.info("ACCEPT_ALL - Empty slots: {}/{}, Result: {}", emptyCount, invSize, hasSpace);
            return hasSpace;
        }

        // FILTER
        if (mode == ProbeMode.FILTER) {
            if (useTags) {
                boolean result = SortUtil.acceptsByInventoryTags(inv, incoming, requireAllTags);
                // DEBUG: SmartSorter.LOGGER.info("FILTER (tags) - Result: {}", result);
                return result;
            }

            // DEBUG: SmartSorter.LOGGER.info("FILTER - Checking {} slots for matches...", invSize);

            for (int i = 0; i < invSize; i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) continue;

                ItemVariant present = ItemVariant.of(stack);
                // String presentName = present.getItem().getName().getString(); // DEBUG: Used in debug logging

                if (ignoreComponents) {
                    if (present.getItem() == incoming.getItem()) {
                        // DEBUG: SmartSorter.LOGGER.info("FILTER - Match found in slot {}: {} == {}", i, presentName, itemName);
                        return true;
                    }
                } else {
                    if (present.equals(incoming)) {
                        // DEBUG: SmartSorter.LOGGER.info("FILTER - Exact match found in slot {}: {}", i, presentName);
                        return true;
                    }
                }
            }

            // DEBUG: SmartSorter.LOGGER.info("FILTER - No matching items found in {} slots", invSize);
            return false;
        }

        // PRIORITY — acts like ACCEPT_ALL for now
        if (mode == ProbeMode.PRIORITY) {
            boolean hasSpace = hasSpaceInInventory(inv, incoming, 1);
            // DEBUG: SmartSorter.LOGGER.info("PRIORITY - Result: {}", hasSpace);
            return hasSpace;
        }

        return false;
    }

    /**
     * Check if the given inventory has space for the item.
     */
    private boolean hasSpaceInInventory(Inventory inv, ItemVariant variant, int amount) {
        if (inv == null) return false;

        int invSize = inv.size();
        for (int i = 0; i < invSize; i++) {
            ItemStack stack = inv.getStack(i);

            if (stack.isEmpty()) {
                // DEBUG: SmartSorter.LOGGER.info("  → Found empty slot at index {}", i);
                return true;
            } else if (ItemStack.areItemsAndComponentsEqual(stack, variant.toStack(1))) {
                int maxStack = Math.min(stack.getMaxCount(), inv.getMaxCountPerStack());
                int canAdd = maxStack - stack.getCount();
                if (canAdd > 0) {
                    // DEBUG: SmartSorter.LOGGER.info("  → Can stack {} more in slot {} (current: {}/{})",
                    //         canAdd, i, stack.getCount(), maxStack);
                    return true;
                }
            }
        }

        // DEBUG: SmartSorter.LOGGER.info("  → No space found in {} slots", invSize);
        return false;
    }

    public boolean hasSpace(ItemVariant variant, int amount) {
        Inventory inv = getTargetInventory();
        return hasSpaceInInventory(inv, variant, amount);
    }

    // === Mode Management ===

    public void cycleMode() {
        mode = switch (mode) {
            case FILTER -> ProbeMode.ACCEPT_ALL;
            case ACCEPT_ALL -> ProbeMode.FILTER; // Priority can be added later
            case PRIORITY -> ProbeMode.FILTER;
        };
        // 1.21.9: setChanged() renamed to markDirty()
        markDirty();
    }

    public String getModeName() {
        return switch (mode) {
            case FILTER -> "Filter Mode";
            case ACCEPT_ALL -> "Accept All";
            case PRIORITY -> "Priority Mode";
        };
    }

    public int getModeColor() {
        return switch (mode) {
            case FILTER -> 0x4A90E2;   // Blue
            case ACCEPT_ALL -> 0x7ED321; // Green
            case PRIORITY -> 0xF5A623;  // Orange
        };
    }

    // === Cleanup ===

    public void onRemoved(World world) {
        if (linkedController != null) {
            BlockEntity be = world.getBlockEntity(linkedController);
            if (be instanceof StorageControllerBlockEntity controller) {
                if (controller.getLinkedProbes().remove(pos)) {
                    // 1.21.9: setChanged() renamed to markDirty()
                    controller.markDirty();
                    // DEBUG: SmartSorter.LOGGER.info("Removed probe {} from controller {}", pos, linkedController);
                }
            }
        }
    }

    // === NBT Serialization ===
// ===================================================================
// NBT SERIALIZATION (save/load from disk)
// ===================================================================

    /** Helper: write probe data into a WriteView */
    private void writeProbeData(WriteView view) {
        view.putBoolean("isLinked", isLinked);
        view.putString("linkedController", linkedControllerPos == null ? "" : linkedControllerPos.toShortString());
        view.putString("mode", mode.name());
    }

    /** Helper: read probe data from a ReadView */
    private void readProbeData(ReadView view) {
        isLinked = view.getBoolean("isLinked", false);

        String controllerStr = view.getString("linkedController", "");
        if (!controllerStr.isEmpty()) {
            String[] parts = controllerStr.split(",");
            if (parts.length == 3) {
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int z = Integer.parseInt(parts[2].trim());
                    linkedControllerPos = new BlockPos(x, y, z);
                } catch (NumberFormatException ignored) {}
            }
        }

        try {
            mode = OutputProbeBlockEntity.ProbeMode.valueOf(view.getString("mode", "ACCEPT_ALL"));
        } catch (IllegalArgumentException e) {
            mode = OutputProbeBlockEntity.ProbeMode.ACCEPT_ALL;
        }
    }

    /** Called by vanilla to serialize block-entity data (server → disk / network) */
    @Override
    public void writeData(WriteView view) {
        super.writeData(view);
        writeProbeData(view);
    }

    /** Called by vanilla to deserialize block-entity data (disk → object) */
    @Override
    public void readData(ReadView view) {
        super.readData(view);
        readProbeData(view);
    }


    /*
    // 1.21.9: NBT methods - removed @Override, super calls don't exist
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putBoolean("ignoreComponents", ignoreComponents);
        nbt.putBoolean("useTags", useTags);
        nbt.putBoolean("requireAllTags", requireAllTags);
        nbt.putString("mode", mode.name());

        if (linkedController != null) {
            nbt.putLong("linkedController", linkedController.asLong());
        }
    }

    // 1.21.9: NBT methods - removed @Override, NBT getters return Optional
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        // 1.21.9: NBT getBoolean/getString/getLong now return Optional
        ignoreComponents = nbt.getBoolean("ignoreComponents").orElse(true);
        useTags = nbt.getBoolean("useTags").orElse(false);
        requireAllTags = nbt.getBoolean("requireAllTags").orElse(false);

        try {
            mode = ProbeMode.valueOf(nbt.getString("mode").orElse("FILTER"));
        } catch (IllegalArgumentException e) {
            mode = ProbeMode.FILTER;
        }

        if (nbt.contains("linkedController")) {
            nbt.getLong("linkedController").ifPresent(pos -> linkedController = BlockPos.fromLong(pos));
        } else {
            linkedController = null;
        }
    }*/

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
