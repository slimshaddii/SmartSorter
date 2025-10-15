package net.shaddii.smartsorter.blockentity;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.StorageLogic;

import java.util.ArrayList;
import java.util.List;

// Fixed for Minecraft 1.21.9 compatibility
// Main change: ItemStack.fromNbt() now takes Optional<NbtCompound>

public class IntakeBlockEntity extends BlockEntity {
    private final List<BlockPos> outputs = new ArrayList<>();
    private ItemStack buffer = ItemStack.EMPTY; // one-stack buffer
    private int cooldown = 0;

    public IntakeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.INTAKE_BE_TYPE, pos, state);
    }

    /**
     * Server tick entry (called by block's getTicker / validateTicker)
     */
    public static void tick(World world, BlockPos pos, BlockState state, IntakeBlockEntity be) {
        // **As you noted**: use world.isClient() here.
        if (world.isClient()) return;

        // Validate links every 5 seconds (100 ticks)
        if (world.getTime() % 100L == 0L) {
            be.validateOutputs(world);
        }

        if (be.cooldown > 0) {
            be.cooldown--;
            return;
        }

        // Pull from the block in front into this BE's buffer
        StorageLogic.pullFromFacingIntoBuffer(be);

        // Try to route the buffer to outputs
        boolean moved = StorageLogic.routeBuffer(world, be);

        // Short cooldown if moved, longer cooldown if idle
        be.cooldown = moved ? 2 : 10;

        // 1.21.9: setChanged() renamed to markDirty()
        be.markDirty();
    }

    /**
     * Remove any invalid output links (checks the supplied world).
     * Passing world in avoids ambiguous field access.
     */
    private void validateOutputs(World world) {
        outputs.removeIf(outputPos -> {
            BlockEntity be = world.getBlockEntity(outputPos);
            return !(be instanceof OutputProbeBlockEntity);
        });
    }

    // Adds an OutputProbe link
    public boolean addOutput(BlockPos probePos) {
        if (!outputs.contains(probePos)) {
            outputs.add(probePos);
            // 1.21.9: setChanged() renamed to markDirty()
            markDirty();
            return true;
        }
        return false;
    }

    public List<BlockPos> getOutputs() {
        return outputs;
    }

    public ItemStack getBuffer() {
        return buffer;
    }

    public boolean removeOutput(BlockPos probePos) {
        boolean removed = outputs.remove(probePos);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public void setBuffer(ItemStack stack) {
        this.buffer = stack;
        // 1.21.9: setChanged() renamed to markDirty()
        markDirty();
    }

    // Save NBT (with RegistryWrapper lookup)
    // 1.21.9: writeNbt signature changed - removed @Override, method name changed
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        // Note: super.writeNbt() doesn't exist in 1.21.9, data is saved differently

        // Save outputs
        nbt.putInt("out_count", outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            nbt.putLong("o" + i, outputs.get(i).asLong());
        }

        // Save buffer (prevents item loss on world reload)
        // 1.21.9: Use OPTIONAL_CODEC to encode ItemStack
        if (!buffer.isEmpty()) {
            var encoded = ItemStack.OPTIONAL_CODEC.encodeStart(lookup.getOps(net.minecraft.nbt.NbtOps.INSTANCE), buffer)
                    .getOrThrow();
            nbt.put("buffer", encoded);
        }
    }

    // Load NBT (with RegistryWrapper lookup)
    // 1.21.9: readNbt signature changed - removed @Override, method name changed
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        // Note: super.readNbt() doesn't exist in 1.21.9, data is loaded differently

        // Load outputs
        outputs.clear();
        // 1.21.9: NBT methods now return Optional
        //? if >=1.21.8 {
        
        int c = nbt.getInt("out_count").orElse(0);
        for (int i = 0; i < c; i++) {
            nbt.getLong("o" + i).ifPresent(pos -> outputs.add(BlockPos.fromLong(pos)));
        }
        //?} else {
        /*int c = nbt.getInt("out_count");
        for (int i = 0; i < c; i++) {
            if (nbt.contains("o" + i)) {
                outputs.add(BlockPos.fromLong(nbt.getLong("o" + i)));
            }
        }
        *///?}

        // Load buffer (restore items after world reload)
        // 1.21.9: Use OPTIONAL_CODEC to decode ItemStack
        if (nbt.contains("buffer")) {
            buffer = ItemStack.OPTIONAL_CODEC.parse(lookup.getOps(net.minecraft.nbt.NbtOps.INSTANCE), nbt.get("buffer"))
                    .result()
                    .orElse(ItemStack.EMPTY);
        } else {
            buffer = ItemStack.EMPTY;
        }
    }
}
