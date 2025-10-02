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

public final class StorageLogic {
    private StorageLogic() {}

    // Limit how many items are pulled each operation
    private static final int MAX_PULL_PER_OP = 2;

    public static void pullFromFacingIntoBuffer(IntakeBlockEntity be) {
        if (be.getWorld() == null || !be.getBuffer().isEmpty()) return;
        World world = be.getWorld();
        Direction face = be.getCachedState().get(net.shaddii.smartsorter.block.IntakeBlock.FACING);
        BlockPos sourcePos = be.getPos().offset(face);

        Storage<ItemVariant> from = ItemStorage.SIDED.find(world, sourcePos, face.getOpposite());
        if (from == null) from = ItemStorage.SIDED.find(world, sourcePos, null);
        if (from == null) {
            BlockEntity src = world.getBlockEntity(sourcePos);
            if (src instanceof Inventory inv) {
                from = InventoryStorage.of(inv, null);
            }
        }
        if (from == null) return;

        for (StorageView<ItemVariant> view : from) {
            if (view.getAmount() == 0) continue;
            ItemVariant var = view.getResource();
            if (var.isBlank()) continue;


            int toTake = Math.min(
                    MAX_PULL_PER_OP,
                    Math.min((int) view.getAmount(), var.getItem().getMaxCount())
            );

            if (!canInsertAnywhere(world, be, var, toTake)) {
                SmartSorter.LOGGER.debug("[SmartSorter] Skipping pull of {}x {} from {} because no destination accepts",
                        toTake, var.getItem(), sourcePos);
                continue;
            }

            try (Transaction tx = Transaction.openOuter()) {
                long moved = view.extract(var, toTake, tx);
                if (moved > 0) {
                    be.setBuffer(var.toStack((int) moved));
                    SmartSorter.LOGGER.debug("[SmartSorter] Pulled {}x {} from {}", moved, var.getItem(), sourcePos);
                    tx.commit();
                    return;
                }
            }
        }
    }

    static void pullAboveIntoBuffer(IntakeBlockEntity be) {
        pullFromFacingIntoBuffer(be);
    }

    public static boolean routeBuffer(World world, IntakeBlockEntity be) {
        ItemStack buffer = be.getBuffer();
        if (buffer.isEmpty()) return false;

        ItemVariant incoming = ItemVariant.of(buffer);
        long remaining = buffer.getCount();
        boolean anyTried = false;

        for (BlockPos probePos : be.getOutputs()) {
            BlockEntity beTarget = world.getBlockEntity(probePos);
            if (!(beTarget instanceof OutputProbeBlockEntity probe)) continue;

            anyTried = true;

            if (!probe.accepts(incoming)) continue;

            Storage<ItemVariant> target = probe.getTargetStorage();
            if (target == null) continue;

            try (Transaction tx = Transaction.openOuter()) {
                long inserted = insertPreferStacking(target, incoming, remaining, tx);
                if (inserted > 0) {
                    tx.commit();
                    remaining -= inserted;
                    SmartSorter.LOGGER.debug("[SmartSorter] Inserted {}x {} via probe at {}",
                            inserted, incoming.getItem(), probePos);
                }
            }

            if (remaining == 0) break;
        }

        if (!anyTried) {
            SmartSorter.LOGGER.debug("[SmartSorter] No outputs linked; holding items in buffer");
        }

        if (remaining != buffer.getCount()) {
            if (remaining == 0) {
                be.setBuffer(ItemStack.EMPTY);
            } else {
                buffer.setCount((int) remaining);
                be.setBuffer(buffer);
            }
            return true;
        }
        return false;
    }

    private static boolean canInsertAnywhere(World world, IntakeBlockEntity be, ItemVariant var, long amount) {
        for (BlockPos probePos : be.getOutputs()) {
            BlockEntity beTarget = world.getBlockEntity(probePos);
            if (!(beTarget instanceof OutputProbeBlockEntity probe)) continue;
            if (!probe.accepts(var)) continue;

            Storage<ItemVariant> target = probe.getTargetStorage();
            if (target == null) continue;

            try (Transaction sim = Transaction.openOuter()) {
                long can = insertPreferStacking(target, var, amount, sim);
                if (can > 0) return true;
            }
        }
        return false;
    }


    private static long insertPreferStacking(Storage<ItemVariant> target, ItemVariant incoming, long max, Transaction tx) {
        long inserted = 0;

        // First pass: top off existing stacks of the same item.
        for (StorageView<ItemVariant> view : target) {
            if (inserted >= max) break;
            if (view.getAmount() == 0) continue;
            if (!incoming.equals(view.getResource())) continue;

            if (view instanceof SingleSlotStorage<ItemVariant> slot) {
                long ins = slot.insert(incoming, max - inserted, tx);
                if (ins > 0) inserted += ins;
            }
        }

        if (inserted >= max) return inserted;

        // Second pass: fill empties / remaining capacity.
        inserted += target.insert(incoming, max - inserted, tx);

        return inserted;
    }
}