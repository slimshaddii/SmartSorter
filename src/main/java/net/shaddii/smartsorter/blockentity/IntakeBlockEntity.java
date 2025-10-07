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

public class IntakeBlockEntity extends BlockEntity {
    private final List<BlockPos> outputs = new ArrayList<>();
    private ItemStack buffer = ItemStack.EMPTY; // one-stack buffer
    private int cooldown = 0;

    public IntakeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.INTAKE_BE_TYPE, pos, state);
    }

    // Called every tick
    public static void tick(World world, BlockPos pos, BlockState state, IntakeBlockEntity be) {
        if (world.isClient) return;

        // NEW: Validate links every 5 seconds
        if (world.getTime() % 100 == 0) {
            be.validateOutputs();
        }

        if (be.cooldown > 0) {
            be.cooldown--;
            return;
        }

        // Pull from intake-facing block
        StorageLogic.pullFromFacingIntoBuffer(be);

        // Try routing buffered item
        boolean moved = StorageLogic.routeBuffer(world, be);

        // Short cooldown if moved, longer cooldown if idle
        be.cooldown = moved ? 2 : 10;
        be.markDirty();
    }

    /**
     * Remove any invalid output links
     */
    private void validateOutputs() {
        outputs.removeIf(outputPos -> {
            BlockEntity be = world.getBlockEntity(outputPos);
            return !(be instanceof OutputProbeBlockEntity);
        });
    }

    // Adds an OutputProbe link
    public boolean addOutput(BlockPos probePos) {
        if (!outputs.contains(probePos)) {
            outputs.add(probePos);
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

    public void setBuffer(ItemStack stack) {
        buffer = stack;
    }

    // Save NBT
    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);

        // Save outputs
        nbt.putInt("out_count", outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            nbt.putLong("o" + i, outputs.get(i).asLong());
        }

        // Save buffer (prevents item loss on world reload)
        if (!buffer.isEmpty()) {
            nbt.put("buffer", buffer.encodeAllowEmpty(lookup));
        }
    }

    // Load NBT
    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);

        // Load outputs
        outputs.clear();
        int c = nbt.getInt("out_count");
        for (int i = 0; i < c; i++) {
            outputs.add(BlockPos.fromLong(nbt.getLong("o" + i)));
        }

        // Load buffer (restore items after world reload)
        if (nbt.contains("buffer")) {
            buffer = ItemStack.fromNbtOrEmpty(lookup, nbt.getCompound("buffer"));
        } else {
            buffer = ItemStack.EMPTY;
        }
    }
}
