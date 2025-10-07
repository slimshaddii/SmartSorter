package net.shaddii.smartsorter;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StorageLogic {
    private StorageLogic() {}

    public static final Logger LOGGER = LoggerFactory.getLogger("SmartSorter/StorageLogic");

    // Limit how many items are pulled each operation
    private static final int MAX_PULL_PER_OP = 2;

    /**
     * Pulls items from the block entity facing the intake block into the intake's buffer.
     */
    public static void pullFromFacingIntoBuffer(IntakeBlockEntity be) {
        if (be.getWorld() == null || !be.getBuffer().isEmpty()) return;
        World world = be.getWorld();
        Direction face = be.getCachedState().get(net.shaddii.smartsorter.block.IntakeBlock.FACING);
        BlockPos sourcePos = be.getPos().offset(face);
        BlockEntity sourceBlock = world.getBlockEntity(sourcePos);

        Storage<ItemVariant> from = null;

        // Get storage using Fabric Transfer API
        from = ItemStorage.SIDED.find(world, sourcePos, face.getOpposite());
        if (from == null) {
            from = ItemStorage.SIDED.find(world, sourcePos, null);
        }

        // If no standard storage found, try to get it from inventory
        if (from == null && sourceBlock instanceof Inventory inv) {
            from = InventoryStorage.of(inv, null);
        }

        if (from == null) {
            return;
        }

        for (StorageView<ItemVariant> view : from) {
            if (view.getAmount() == 0) continue;
            ItemVariant var = view.getResource();
            if (var.isBlank()) continue;

            int toTake = Math.min(
                    MAX_PULL_PER_OP,
                    Math.min((int) view.getAmount(), var.getItem().getMaxCount())
            );

            // Check if any of the output probes accept the item
            if (!canInsertAnywhere(world, be, var, toTake)) {
                continue;
            }

            try (Transaction tx = Transaction.openOuter()) {
                long moved = view.extract(var, toTake, tx);
                if (moved > 0) {
                    be.setBuffer(var.toStack((int) moved));
                    tx.commit();
                    return;
                }
            }
        }
    }

    public static boolean routeBuffer(World world, IntakeBlockEntity be) {
        ItemStack buffer = be.getBuffer();
        if (buffer.isEmpty()) return false;

        ItemVariant incoming = ItemVariant.of(buffer);
        int remaining = buffer.getCount();

        for (BlockPos probePos : be.getOutputs()) {
            BlockEntity beTarget = world.getBlockEntity(probePos);
            if (!(beTarget instanceof OutputProbeBlockEntity probe)) continue;

            if (!probe.accepts(incoming)) {
                continue;
            }

            // Try to insert directly into the specific chest's inventory
            int inserted = insertDirectIntoChest(world, probe, incoming, remaining);

            if (inserted > 0) {
                remaining -= inserted;
            }

            if (remaining == 0) break;
        }

        if (remaining != buffer.getCount()) {
            if (remaining == 0) {
                be.setBuffer(ItemStack.EMPTY);
            } else {
                buffer.setCount(remaining);
                be.setBuffer(buffer);
            }
            return true;
        }
        return false;
    }

    /**
     * Insert items directly into the chest the probe is facing.
     * Priority: Stack with existing items first, then fill empty slots.
     */
    private static int insertDirectIntoChest(World world, OutputProbeBlockEntity probe, ItemVariant incoming, int amount) {
        Direction face = probe.getCachedState().get(net.shaddii.smartsorter.block.OutputProbeBlock.FACING);
        BlockPos chestPos = probe.getPos().offset(face);
        BlockEntity be = world.getBlockEntity(chestPos);

        if (!(be instanceof Inventory inv)) {
            return 0;
        }

        ItemStack toInsert = incoming.toStack(amount);
        int originalAmount = toInsert.getCount();

        // FIRST PASS: Try to stack with existing items
        for (int i = 0; i < inv.size(); i++) {
            if (toInsert.isEmpty()) break;

            ItemStack slotStack = inv.getStack(i);

            // Skip empty slots in first pass
            if (slotStack.isEmpty()) continue;

            // Check if items can stack
            if (ItemStack.areItemsAndComponentsEqual(slotStack, toInsert)) {
                int maxStack = Math.min(slotStack.getMaxCount(), inv.getMaxCountPerStack());
                int canAdd = maxStack - slotStack.getCount();

                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, toInsert.getCount());
                    slotStack.setCount(slotStack.getCount() + toAdd);
                    toInsert.setCount(toInsert.getCount() - toAdd);
                    inv.markDirty();
                }
            }
        }

        // SECOND PASS: Fill empty slots with remaining items
        for (int i = 0; i < inv.size(); i++) {
            if (toInsert.isEmpty()) break;

            ItemStack slotStack = inv.getStack(i);

            // Only process empty slots in second pass
            if (!slotStack.isEmpty()) continue;

            int maxStack = Math.min(toInsert.getMaxCount(), inv.getMaxCountPerStack());
            int toAdd = Math.min(maxStack, toInsert.getCount());

            ItemStack newStack = toInsert.copy();
            newStack.setCount(toAdd);
            inv.setStack(i, newStack);
            toInsert.setCount(toInsert.getCount() - toAdd);
            inv.markDirty();
        }

        int inserted = originalAmount - toInsert.getCount();
        return inserted;
    }

    private static boolean canInsertAnywhere(World world, IntakeBlockEntity be, ItemVariant var, long amount) {
        for (BlockPos probePos : be.getOutputs()) {
            BlockEntity beTarget = world.getBlockEntity(probePos);
            if (!(beTarget instanceof OutputProbeBlockEntity probe)) continue;
            if (!probe.accepts(var)) continue;

            // Check if the specific chest has space
            Direction face = probe.getCachedState().get(net.shaddii.smartsorter.block.OutputProbeBlock.FACING);
            BlockPos chestPos = probe.getPos().offset(face);
            BlockEntity chestBE = world.getBlockEntity(chestPos);

            if (chestBE instanceof Inventory inv) {
                // Check if there's any space in this specific chest
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack stack = inv.getStack(i);
                    if (stack.isEmpty()) return true; // Empty slot
                    if (ItemStack.areItemsAndComponentsEqual(stack, var.toStack(1)) &&
                            stack.getCount() < stack.getMaxCount()) {
                        return true; // Can stack
                    }
                }
            }
        }
        return false;
    }
}