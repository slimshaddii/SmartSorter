package net.shaddii.smartsorter;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.util.SortUtil;

public class OutputProbeBlockEntity extends BlockEntity {
    public boolean ignoreComponents = false; // match by ID only if true
    public boolean useTags = false;          // enable tag matching
    public boolean requireAllTags = false;   // if tags enabled, require all instead of any

    public OutputProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROBE_BE_TYPE, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, OutputProbeBlockEntity be) {
        if (world.isClient) return;
    }

    public Storage<ItemVariant> getTargetStorage() {
        if (world == null) return null;
        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos chestPos = pos.offset(face);

        // Try Fabric item storage first
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(world, chestPos, face.getOpposite());
        if (storage == null) storage = ItemStorage.SIDED.find(world, chestPos, null);

        // Fallback to legacy Inventory (some mods expose vanilla Inventory)
        if (storage == null) {
            var be = world.getBlockEntity(chestPos);
            if (be instanceof net.minecraft.inventory.Inventory inv) {
                storage = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(inv, null);
            }
        }

        return storage;
    }

    public boolean accepts(ItemVariant incoming) {
        Storage<ItemVariant> target = getTargetStorage();
        if (target == null) return false;
        return SortUtil.accepts(target, incoming, ignoreComponents, useTags, requireAllTags);
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
    }
}
