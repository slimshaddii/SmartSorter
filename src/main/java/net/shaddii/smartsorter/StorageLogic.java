package net.shaddii.smartsorter;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.block.IntakeBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import java.util.Objects;

/**
 * Handles all logic for moving and routing items between SmartSorter blocks.
 *
 * OPTIMIZED FLOW:
 * - Intake now uses a single transactional operation to pull from a source
 *   and push to the controller, avoiding inefficient "check-then-do" logic.
 */
public final class StorageLogic {

    private StorageLogic() {}

    /** Maximum number of items an intake can pull per operation. */
    private static final int MAX_PULL_PER_OP = 8; // Increased slightly as the operation is now more efficient

    // ---------------------------------------------------------------------------------
    // UNIFIED PULL & ROUTE LOGIC
    // This single method replaces both pullFromFacingIntoBuffer and routeBuffer.
    // ---------------------------------------------------------------------------------

    /**
     * Tries to pull an item from the inventory the intake is facing and route it
     * into the connected storage network (either via controller or direct outputs).
     * @param intake The intake block entity performing the operation.
     * @return True if an item was successfully moved, false otherwise.
     */
    public static boolean pullAndRoute(IntakeBlockEntity intake) {
        if (intake == null || intake.getWorld() == null || intake.getWorld().isClient()) {
            return false;
        }

        if (!intake.getBuffer().isEmpty()) {
            return tryRouteBuffer(intake);
        }

        World world = intake.getWorld();
        Direction facing = intake.getCachedState().get(IntakeBlock.FACING);
        BlockPos sourcePos = intake.getPos().offset(facing);

        Storage<ItemVariant> fromStorage = locateItemStorage(world, sourcePos, facing.getOpposite());
        if (fromStorage == null) {
            return false;
        }

        for (StorageView<ItemVariant> view : fromStorage) {
            if (view.isResourceBlank() || view.getAmount() == 0) continue;

            ItemVariant variant = view.getResource();
            int maxAmount = (int) Math.min(MAX_PULL_PER_OP, view.getAmount());

            try (Transaction tx = Transaction.openOuter()) {
                // 1. Optimistically extract the item from the source inventory.
                long extractedAmount = view.extract(variant, maxAmount, tx);
                if (extractedAmount == 0) continue;

                ItemStack extractedStack = variant.toStack((int) extractedAmount);
                ItemStack remainder;

                // 2. Try to insert the extracted item into the network.
                if (intake.isInManagedMode()) {
                    // --- MANAGED MODE: Use Controller ---
                    StorageControllerBlockEntity controller = getController(world, intake);
                    if (controller != null) {
                        remainder = controller.insertItem(extractedStack).remainder();
                    } else {
                        // Controller is missing, cannot insert.
                        tx.abort(); // Abort reverts the extraction.
                        continue;
                    }
                } else if (intake.isInDirectMode()) {
                    // --- DIRECT MODE: Use legacy direct output ---
                    remainder = insertIntoDirectOutputs(world, intake, extractedStack);
                } else {
                    // Not connected to anything
                    tx.abort();
                    return false;
                }

                // 3. Analyze the result and commit/abort.
                long insertedAmount = extractedAmount - remainder.getCount();

                if (insertedAmount > 0) {
                    intake.setBuffer(remainder);
                    tx.commit(); // This makes the extraction and insertion permanent.
                    return true;
                } else {
                    tx.abort();
                }
            }
        }

        return false;
    }

    /**
     * Helper method to try routing an item that's already in the intake's buffer.
     * This is for items that failed to be fully inserted in a previous tick.
     */
    private static boolean tryRouteBuffer(IntakeBlockEntity intake) {
        World world = intake.getWorld();
        ItemStack bufferStack = intake.getBuffer();
        if (world == null || bufferStack.isEmpty()) return false;

        ItemStack remainder;
        if (intake.isInManagedMode()) {
            StorageControllerBlockEntity controller = getController(world, intake);
            if (controller != null) {
                remainder = controller.insertItem(bufferStack).remainder();
            } else {
                return false;
            }
        } else if (intake.isInDirectMode()) {
            remainder = insertIntoDirectOutputs(world, intake, bufferStack);
        } else {
            return false;
        }

        if (remainder.getCount() < bufferStack.getCount()) {
            intake.setBuffer(remainder);
            return true;
        }

        return false;
    }

    // --- HELPER METHODS ---

    private static StorageControllerBlockEntity getController(World world, IntakeBlockEntity intake) {
        BlockPos controllerPos = intake.getController();
        if (controllerPos == null) return null;

        BlockEntity be = world.getBlockEntity(controllerPos);
        return be instanceof StorageControllerBlockEntity controller ? controller : null;
    }

    private static ItemStack insertIntoDirectOutputs(World world, IntakeBlockEntity intake, ItemStack stackToInsert) {
        ItemVariant variant = ItemVariant.of(stackToInsert);
        ItemStack currentStack = stackToInsert.copy();

        for (BlockPos probePos : intake.getOutputs()) {
            if (currentStack.isEmpty()) break;

            BlockEntity target = world.getBlockEntity(probePos);
            if (!(target instanceof OutputProbeBlockEntity probe) || !probe.accepts(variant)) continue;

            int inserted = insertIntoInventoryFacingProbe(world, probe, variant, currentStack.getCount());
            if (inserted > 0) {
                currentStack.decrement(inserted);
            }
        }
        return currentStack;
    }

    private static int insertIntoInventoryFacingProbe(World world, OutputProbeBlockEntity probe, ItemVariant variant, int amount) {
        Inventory inventory = probe.getTargetInventory();
        if (inventory == null) return 0;

        ItemStack toInsert = variant.toStack(amount);
        int originalCount = toInsert.getCount();

        // Pass 1: stack onto existing compatible stacks
        for (int i = 0; i < inventory.size() && !toInsert.isEmpty(); i++) {
            ItemStack slot = inventory.getStack(i);
            if (!slot.isEmpty() && ItemStack.areItemsAndComponentsEqual(slot, toInsert)) {
                int canAdd = Math.min(slot.getMaxCount(), inventory.getMaxCountPerStack()) - slot.getCount();
                if (canAdd > 0) {
                    int add = Math.min(canAdd, toInsert.getCount());
                    slot.increment(add);
                    toInsert.decrement(add);
                    inventory.markDirty();
                }
            }
        }

        // Pass 2: fill empty slots
        for (int i = 0; i < inventory.size() && !toInsert.isEmpty(); i++) {
            if (inventory.getStack(i).isEmpty()) {
                int insertCount = Math.min(inventory.getMaxCountPerStack(), toInsert.getCount());
                ItemStack newStack = toInsert.copyWithCount(insertCount);
                inventory.setStack(i, newStack);
                toInsert.decrement(insertCount);
                inventory.markDirty();
            }
        }

        return originalCount - toInsert.getCount();
    }

    private static Storage<ItemVariant> locateItemStorage(World world, BlockPos pos, Direction searchSide) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(pos);

        Storage<ItemVariant> found = ItemStorage.SIDED.find(world, pos, searchSide);
        if (found == null) found = ItemStorage.SIDED.find(world, pos, null);

        BlockEntity be = world.getBlockEntity(pos);
        if (found == null && be instanceof Inventory inv) {
            found = InventoryStorage.of(inv, null);
        }

        return found;
    }
}