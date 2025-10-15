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
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.util.SortUtil;
//? if >=1.21.8 {

import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?}
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Output Probe Block Entity - Multi-Block Linking Support
 *
 * UPDATED FEATURES:
 * - Can link to multiple blocks (controllers, intakes, etc.)
 * - Automatically notifies all linked blocks when inventory changes
 * - Bidirectional linking support
 */
public class OutputProbeBlockEntity extends BlockEntity {
    // Configuration
    public boolean ignoreComponents = true;
    public boolean useTags = false;
    public boolean requireAllTags = false;

    // Mode
    public ProbeMode mode = ProbeMode.FILTER;

    // UPDATED: Multi-block linking (replaces single linkedController)
    private final List<BlockPos> linkedBlocks = new ArrayList<>();

    public enum ProbeMode {
        FILTER,
        ACCEPT_ALL,
        PRIORITY
    }

    public OutputProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROBE_BE_TYPE, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, OutputProbeBlockEntity be) {
        if (world.isClient()) return;

        // Validate linked blocks every 5 seconds
        if (world.getTime() % 100 == 0) {
            be.validateLinkedBlocks();
        }
    }

    // ===================================================================
    // MULTI-BLOCK LINKING
    // ===================================================================

    /**
     * Add a linked block (controller, intake, etc.)
     */
    public boolean addLinkedBlock(BlockPos blockPos) {
        if (!linkedBlocks.contains(blockPos)) {
            linkedBlocks.add(blockPos);
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }

            return true;
        }
        return false;
    }

    /**
     * Remove a linked block
     */
    public boolean removeLinkedBlock(BlockPos blockPos) {
        boolean removed = linkedBlocks.remove(blockPos);
        if (removed) {
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }
        }
        return removed;
    }

    /**
     * Get all linked blocks
     */
    public List<BlockPos> getLinkedBlocks() {
        return new ArrayList<>(linkedBlocks);
    }

    /**
     * Check if linked to any blocks
     */
    public boolean hasLinkedBlocks() {
        return !linkedBlocks.isEmpty();
    }

    /**
     * Validate all linked blocks (remove invalid ones)
     */
    private void validateLinkedBlocks() {
        if (world == null) return;

        linkedBlocks.removeIf(blockPos -> {
            BlockEntity be = world.getBlockEntity(blockPos);
            return !(be instanceof StorageControllerBlockEntity || be instanceof IntakeBlockEntity);
        });
    }

    /**
     * Notify all linked blocks that this probe's inventory changed
     */
    public void notifyLinkedBlocks() {
        if (world == null || world.isClient()) return;

        for (BlockPos blockPos : new ArrayList<>(linkedBlocks)) {
            BlockEntity be = world.getBlockEntity(blockPos);

            if (be instanceof StorageControllerBlockEntity controller) {
                controller.onProbeInventoryChanged(this);
            }
            // Future: Add more block types here
        }
    }

    // ===================================================================
    // LEGACY COMPATIBILITY (for existing code that uses getLinkedController)
    // ===================================================================

    /**
     * @deprecated Use addLinkedBlock() instead
     */
    @Deprecated
    public void setLinkedController(BlockPos controllerPos) {
        addLinkedBlock(controllerPos);
    }

    /**
     * @deprecated Use getLinkedBlocks() instead
     * Returns first controller found, or null
     */
    @Deprecated
    public BlockPos getLinkedController() {
        if (world == null) return null;

        for (BlockPos blockPos : linkedBlocks) {
            BlockEntity be = world.getBlockEntity(blockPos);
            if (be instanceof StorageControllerBlockEntity) {
                return blockPos;
            }
        }
        return null;
    }

    // ===================================================================
    // STORAGE ACCESS
    // ===================================================================

    public Storage<ItemVariant> getTargetStorage() {
        if (world == null) return null;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);

        Storage<ItemVariant> sidedStorage = ItemStorage.SIDED.find(world, targetPos, face.getOpposite());
        if (sidedStorage != null) return sidedStorage;

        Storage<ItemVariant> storage = ItemStorage.SIDED.find(world, targetPos, null);
        if (storage != null) return storage;

        Inventory inv = getTargetInventory();
        if (inv != null) return InventoryStorage.of(inv, null);

        return null;
    }

    public Inventory getTargetInventory() {
        if (world == null) return null;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);
        BlockState targetState = world.getBlockState(targetPos);

        if (targetState.getBlock() instanceof net.minecraft.block.ChestBlock chestBlock) {
            Inventory chestInv = net.minecraft.block.ChestBlock.getInventory(chestBlock, targetState, world, targetPos, true);
            if (chestInv != null) {
                return chestInv;
            }
        }

        BlockEntity be = world.getBlockEntity(targetPos);
        if (be instanceof Inventory inv) {
            return inv;
        }

        return null;
    }

    // ===================================================================
    // ITEM ACCEPTANCE LOGIC
    // ===================================================================

    public boolean accepts(ItemVariant incoming) {
        if (world == null) return false;

        Inventory inv = getTargetInventory();
        if (inv == null) {
            return false;
        }

        int invSize = inv.size();

        if (mode == ProbeMode.ACCEPT_ALL) {
            return hasSpaceInInventory(inv, incoming, 1);
        }

        if (mode == ProbeMode.FILTER) {
            if (useTags) {
                return SortUtil.acceptsByInventoryTags(inv, incoming, requireAllTags);
            }

            for (int i = 0; i < invSize; i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) continue;

                ItemVariant present = ItemVariant.of(stack);

                if (ignoreComponents) {
                    if (present.getItem() == incoming.getItem()) {
                        return true;
                    }
                } else {
                    if (present.equals(incoming)) {
                        return true;
                    }
                }
            }

            return false;
        }

        if (mode == ProbeMode.PRIORITY) {
            return hasSpaceInInventory(inv, incoming, 1);
        }

        return false;
    }

    private boolean hasSpaceInInventory(Inventory inv, ItemVariant variant, int amount) {
        if (inv == null) return false;

        int invSize = inv.size();
        for (int i = 0; i < invSize; i++) {
            ItemStack stack = inv.getStack(i);

            if (stack.isEmpty()) {
                return true;
            } else if (ItemStack.areItemsAndComponentsEqual(stack, variant.toStack(1))) {
                int maxStack = Math.min(stack.getMaxCount(), inv.getMaxCountPerStack());
                int canAdd = maxStack - stack.getCount();
                if (canAdd > 0) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean hasSpace(ItemVariant variant, int amount) {
        Inventory inv = getTargetInventory();
        return hasSpaceInInventory(inv, variant, amount);
    }

    // ===================================================================
    // MODE MANAGEMENT
    // ===================================================================

    public void cycleMode() {
        mode = switch (mode) {
            case FILTER -> ProbeMode.ACCEPT_ALL;
            case ACCEPT_ALL -> ProbeMode.FILTER;
            case PRIORITY -> ProbeMode.FILTER;
        };
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
            case FILTER -> 0x4A90E2;
            case ACCEPT_ALL -> 0x7ED321;
            case PRIORITY -> 0xF5A623;
        };
    }

    // ===================================================================
    // CLEANUP
    // ===================================================================

    /**
     * Called when this probe is removed from world
     * Unlinks from all connected blocks
     */
    public void onRemoved(World world) {
        if (world.isClient()) return;

        for (BlockPos blockPos : new ArrayList<>(linkedBlocks)) {
            BlockEntity be = world.getBlockEntity(blockPos);

            if (be instanceof StorageControllerBlockEntity controller) {
                controller.removeProbe(pos);
            }

            if (be instanceof IntakeBlockEntity intake) {
                intake.removeOutput(pos);
            }
        }

        linkedBlocks.clear();
    }

    // ===================================================================
// NBT SERIALIZATION
// ===================================================================

    //? if >= 1.21.8 {
    
    private void writeProbeData(WriteView view) {
        view.putBoolean("ignoreComponents", ignoreComponents);
        view.putBoolean("useTags", useTags);
        view.putBoolean("requireAllTags", requireAllTags);
        view.putString("mode", mode.name());

        // Write linked blocks (count + individual longs)
        view.putInt("linked_blocks_count", linkedBlocks.size());
        for (int i = 0; i < linkedBlocks.size(); i++) {
            view.putLong("linked_block_" + i, linkedBlocks.get(i).asLong());
        }
    }

    private void writeProbeData(NbtCompound nbt) {
        nbt.putBoolean("ignoreComponents", ignoreComponents);
        nbt.putBoolean("useTags", useTags);
        nbt.putBoolean("requireAllTags", requireAllTags);
        nbt.putString("mode", mode.name());

        // Write linked blocks (count + individual longs)
        nbt.putInt("linked_blocks_count", linkedBlocks.size());
        for (int i = 0; i < linkedBlocks.size(); i++) {
            nbt.putLong("linked_block_" + i, linkedBlocks.get(i).asLong());
        }
    }

    private void readProbeData(ReadView view) {
        ignoreComponents = view.getBoolean("ignoreComponents", true);
        useTags = view.getBoolean("useTags", false);
        requireAllTags = view.getBoolean("requireAllTags", false);

        try {
            mode = ProbeMode.valueOf(view.getString("mode", "FILTER"));
        } catch (IllegalArgumentException e) {
            mode = ProbeMode.FILTER;
        }

        // Read linked blocks
        linkedBlocks.clear();
        int count = view.getInt("linked_blocks_count", 0);
        for (int i = 0; i < count; i++) {
            view.getOptionalLong("linked_block_" + i).ifPresent(posLong -> {
                linkedBlocks.add(BlockPos.fromLong(posLong));
            });
        }
    }

    @Override
    public void writeData(WriteView view) {
        super.writeData(view);
        writeProbeData(view);
    }

    @Override
    public void readData(ReadView view) {
        super.readData(view);
        readProbeData(view);
    }
    //?} else {
/*private void writeProbeData(NbtCompound nbt) {
    nbt.putBoolean("ignoreComponents", ignoreComponents);
    nbt.putBoolean("useTags", useTags);
    nbt.putBoolean("requireAllTags", requireAllTags);
    nbt.putString("mode", mode.name());

    // Write linked blocks (count + individual longs)
    nbt.putInt("linked_blocks_count", linkedBlocks.size());
    for (int i = 0; i < linkedBlocks.size(); i++) {
        nbt.putLong("linked_block_" + i, linkedBlocks.get(i).asLong());
    }
}

private void readProbeData(NbtCompound nbt) {
    ignoreComponents = nbt.getBoolean("ignoreComponents");
    useTags = nbt.getBoolean("useTags");
    requireAllTags = nbt.getBoolean("requireAllTags");

    try {
        mode = ProbeMode.valueOf(nbt.getString("mode"));
    } catch (IllegalArgumentException e) {
        mode = ProbeMode.FILTER;
    }

    // Read linked blocks
    linkedBlocks.clear();
    int count = nbt.getInt("linked_blocks_count");
    for (int i = 0; i < count; i++) {
        String key = "linked_block_" + i;
        if (nbt.contains(key)) {
            linkedBlocks.add(BlockPos.fromLong(nbt.getLong(key)));
        }
    }
}

@Override
protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
    super.writeNbt(nbt, registryLookup);
    writeProbeData(nbt);
}

@Override
protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
    super.readNbt(nbt, registryLookup);
    readProbeData(nbt);
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
}