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
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
//import org.slf4j.Logger; // DEBUG: For debug logging
//import org.slf4j.LoggerFactory; // DEBUG: For debug logging

import java.util.Objects;

/**
 * Handles all logic for moving and routing items between SmartSorter blocks.
 * <p>
 * This class contains no persistent state — it only performs logic for:
 * - Pulling items from inventories into intake buffers.
 * - Routing buffered items into connected outputs.
 */
public final class StorageLogic {

    private StorageLogic() {}
    //private static final Logger LOGGER = LoggerFactory.getLogger("SmartSorter/StorageLogic");

    /** Maximum number of items an intake can pull per tick. */
    private static final int MAX_PULL_PER_OP = 2;

    // ------------------------------------------------------------
    // 1) Pull logic: from facing inventory → intake buffer
    // ------------------------------------------------------------
    public static void pullFromFacingIntoBuffer(IntakeBlockEntity intake) {
        if (intake == null || intake.getWorld() == null || !intake.getBuffer().isEmpty()) return;

        World world = intake.getWorld();
        Direction facing = intake.getCachedState().get(IntakeBlock.FACING);
        BlockPos sourcePos = intake.getPos().offset(facing);

        Storage<ItemVariant> fromStorage = locateItemStorage(world, sourcePos, facing.getOpposite());
        if (fromStorage == null) return;

        // Try to pull one matching stack that can be routed somewhere
        for (StorageView<ItemVariant> view : fromStorage) {
            if (view.isResourceBlank() || view.getAmount() == 0) continue;

            ItemVariant variant = view.getResource();
            int toTake = Math.min(MAX_PULL_PER_OP, (int) Math.min(view.getAmount(), variant.getItem().getMaxCount()));

            // Only pull items that have a valid destination
            if (!canInsertAnywhere(world, intake, variant, toTake)) continue;

            try (Transaction tx = Transaction.openOuter()) {
                long extracted = view.extract(variant, toTake, tx);
                if (extracted > 0) {
                    intake.setBuffer(variant.toStack((int) extracted));
                    tx.commit();
                    // DEBUG: LOGGER.debug("Pulled {}x {} into intake buffer at {}", extracted, variant.getItem(), intake.getPos());
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------
    // 2) Routing logic: intake buffer → connected output probes
    // ------------------------------------------------------------
    public static boolean routeBuffer(World world, IntakeBlockEntity intake) {
        ItemStack buffer = intake.getBuffer();
        if (buffer.isEmpty()) return false;

        ItemVariant variant = ItemVariant.of(buffer);
        int remaining = buffer.getCount();

        for (BlockPos probePos : intake.getOutputs()) {
            BlockEntity target = world.getBlockEntity(probePos);
            if (!(target instanceof OutputProbeBlockEntity probe)) continue;
            if (!probe.accepts(variant)) continue;

            int inserted = insertIntoInventoryFacingProbe(world, probe, variant, remaining);
            if (inserted > 0) remaining -= inserted;
            if (remaining <= 0) break;
        }

        // Update buffer after routing attempt
        if (remaining != buffer.getCount()) {
            if (remaining <= 0) {
                intake.setBuffer(ItemStack.EMPTY);
            } else {
                buffer.setCount(remaining);
                intake.setBuffer(buffer);
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------
    // 3) Insertion helper
    // ------------------------------------------------------------
    private static int insertIntoInventoryFacingProbe(World world, OutputProbeBlockEntity probe, ItemVariant variant, int amount) {
        Direction facing = probe.getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = probe.getPos().offset(facing);
        BlockEntity targetBE = world.getBlockEntity(targetPos);

        if (!(targetBE instanceof Inventory inventory)) return 0;

        ItemStack toInsert = variant.toStack(amount);
        int originalCount = toInsert.getCount();

        // Pass 1: stack onto existing compatible stacks
        for (int i = 0; i < inventory.size() && !toInsert.isEmpty(); i++) {
            ItemStack slot = inventory.getStack(i);
            if (slot.isEmpty()) continue;

            if (ItemStack.areItemsAndComponentsEqual(slot, toInsert)) {
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
            if (!inventory.getStack(i).isEmpty()) continue;

            int insertCount = Math.min(inventory.getMaxCountPerStack(), toInsert.getCount());
            ItemStack newStack = toInsert.copyWithCount(insertCount);
            inventory.setStack(i, newStack);
            toInsert.decrement(insertCount);
            inventory.markDirty();
        }

        return originalCount - toInsert.getCount();
    }

    // ------------------------------------------------------------
    // 4) Can-insert checker
    // ------------------------------------------------------------
    private static boolean canInsertAnywhere(World world, IntakeBlockEntity intake, ItemVariant variant, long amount) {
        for (BlockPos probePos : intake.getOutputs()) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (!(be instanceof OutputProbeBlockEntity probe)) continue;
            if (!probe.accepts(variant)) continue;

            Direction facing = probe.getCachedState().get(OutputProbeBlock.FACING);
            BlockPos targetPos = probe.getPos().offset(facing);
            BlockEntity target = world.getBlockEntity(targetPos);

            if (!(target instanceof Inventory inventory)) continue;

            for (int i = 0; i < inventory.size(); i++) {
                ItemStack slot = inventory.getStack(i);
                if (slot.isEmpty()) return true;
                if (ItemStack.areItemsAndComponentsEqual(slot, variant.toStack(1)) &&
                        slot.getCount() < slot.getMaxCount()) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------
    // 5) Helper: locate any valid item storage for pulling
    // ------------------------------------------------------------
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
