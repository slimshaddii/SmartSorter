package net.shaddii.smartsorter.blockentity;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
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

import java.util.Iterator;

public class OutputProbeBlockEntity extends BlockEntity {
    public boolean ignoreComponents = false;
    public boolean useTags = false;
    public boolean requireAllTags = false;

    private FilteredStorage filteredStorage = null;
    private long lastRefresh = 0;

    public OutputProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROBE_BE_TYPE, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, OutputProbeBlockEntity be) {
        if (world.isClient) return;

        // Refresh storage every 2 seconds
        if (world.getTime() - be.lastRefresh > 40) {
            be.filteredStorage = null;
            be.lastRefresh = world.getTime();
        }
    }

    public Storage<ItemVariant> getTargetStorage() {
        if (world == null) return null;

        if (filteredStorage != null) {
            return filteredStorage;
        }

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);
        BlockEntity be = world.getBlockEntity(targetPos);

        if (!(be instanceof Inventory inv)) {
            return null;
        }

        InventoryStorage baseStorage = InventoryStorage.of(inv, null);
        filteredStorage = new FilteredStorage(baseStorage, this, targetPos);

        // SmartSorter.LOGGER.info("[SmartSorter] Probe at {} created filtered storage for chest at {}", pos, targetPos);

        return filteredStorage;
    }

    public boolean accepts(ItemVariant incoming) {
        if (world == null) return false;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);
        BlockEntity be = world.getBlockEntity(targetPos);

        if (!(be instanceof Inventory inv)) {
            return false;
        }

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            ItemVariant present = ItemVariant.of(stack);

            if (ignoreComponents) {
                if (present.getItem() == incoming.getItem()) {
                    return true;
                }
            } else if (!useTags) {
                if (present.equals(incoming)) {
                    return true;
                }
            }
        }

        if (useTags) {
            return SortUtil.acceptsByInventoryTags(inv, incoming, requireAllTags);
        }

        return false;
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("ignoreComponents", ignoreComponents);
        nbt.putBoolean("useTags", useTags);
        nbt.putBoolean("requireAllTags", requireAllTags);
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        ignoreComponents = nbt.getBoolean("ignoreComponents");
        useTags = nbt.getBoolean("useTags");
        requireAllTags = nbt.getBoolean("requireAllTags");
        filteredStorage = null;
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        filteredStorage = null;
    }

    private static class FilteredStorage implements Storage<ItemVariant> {
        private final InventoryStorage baseStorage;
        private final OutputProbeBlockEntity probe;
        private final BlockPos chestPos;

        public FilteredStorage(InventoryStorage baseStorage, OutputProbeBlockEntity probe, BlockPos chestPos) {
            this.baseStorage = baseStorage;
            this.probe = probe;
            this.chestPos = chestPos;
        }

        @Override
        public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            if (!probe.accepts(resource)) {
                // SmartSorter.LOGGER.debug("[SmartSorter] Probe at {} REJECTED {} (chest at {} doesn't contain it)",
                //         probe.getPos(), resource.getItem(), chestPos);
                return 0;
            }

            long inserted = baseStorage.insert(resource, maxAmount, transaction);
            // if (inserted > 0) {
            //     SmartSorter.LOGGER.info("[SmartSorter] Probe at {} ACCEPTED {}x {} -> chest at {}",
            //             probe.getPos(), inserted, resource.getItem(), chestPos);
            // }
            return inserted;
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            return baseStorage.extract(resource, maxAmount, transaction);
        }

        @Override
        public Iterator<StorageView<ItemVariant>> iterator() {
            // Expose chest contents to TSS for viewing in terminal
            return baseStorage.iterator();
        }

        @Override
        public boolean supportsInsertion() {
            return true;
        }

        @Override
        public boolean supportsExtraction() {
            return true;
        }
    }

    public Object getConnectorRef() {
        return null;
    }
}