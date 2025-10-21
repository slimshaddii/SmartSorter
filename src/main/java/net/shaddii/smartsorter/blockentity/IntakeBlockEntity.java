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
    // ========================================
    // CONSTANTS
    // ========================================

    private static final int MOVE_COOLDOWN = 2;
    private static final int IDLE_COOLDOWN = 10;
    private static final long VALIDATION_INTERVAL = 100L;

    // ========================================
    // FIELDS
    // ========================================

    // Direct mode (links to output probes)
    private final List<BlockPos> outputs = new ArrayList<>();

    // Managed mode (links to storage controller)
    private BlockPos controllerPos = null;

    // State
    private ItemStack buffer = ItemStack.EMPTY;
    private int cooldown = 0;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public IntakeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.INTAKE_BE_TYPE, pos, state);
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(World world, BlockPos pos, BlockState state, IntakeBlockEntity be) {
        if (world.isClient()) return;

        // Validate links periodically
        if (world.getTime() % VALIDATION_INTERVAL == 0L) {
            be.validateLinks(world);
        }

        if (be.cooldown > 0) {
            be.cooldown--;
            return;
        }

        boolean moved = StorageLogic.pullAndRoute(be);

        be.cooldown = moved ? MOVE_COOLDOWN : IDLE_COOLDOWN;
        if (moved) {
            be.markDirty();
        }
    }

    private void validateLinks(World world) {
        // Validate controller
        if (controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (!(be instanceof net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity)) {
                controllerPos = null;
                markDirty();
            }
        }

        // Validate output probes
        outputs.removeIf(outputPos -> {
            BlockEntity be = world.getBlockEntity(outputPos);
            return !(be instanceof net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity);
        });
    }

    // ========================================
    // MANAGED MODE
    // ========================================

    public boolean setController(BlockPos pos) {
        if (controllerPos == null || !controllerPos.equals(pos)) {
            controllerPos = pos;
            outputs.clear();
            markDirty();
            return true;
        }
        return false;
    }

    public BlockPos getController() {
        return controllerPos;
    }

    public boolean clearController() {
        if (controllerPos != null) {
            controllerPos = null;
            markDirty();
            return true;
        }
        return false;
    }

    // ========================================
    // DIRECT MODE
    // ========================================

    public boolean addOutput(BlockPos probePos) {
        if (!outputs.contains(probePos)) {
            outputs.add(probePos);
            controllerPos = null;
            markDirty();
            return true;
        }
        return false;
    }

    public boolean removeOutput(BlockPos probePos) {
        boolean removed = outputs.remove(probePos);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public List<BlockPos> getOutputs() {
        return outputs;
    }

    // ========================================
    // MODE DETECTION
    // ========================================

    public boolean isInManagedMode() {
        return controllerPos != null;
    }

    public boolean isInDirectMode() {
        return !outputs.isEmpty();
    }

    // ========================================
    // BUFFER MANAGEMENT
    // ========================================

    public ItemStack getBuffer() {
        return buffer;
    }

    public void setBuffer(ItemStack stack) {
        this.buffer = stack;
        markDirty();
    }

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        // Controller
        if (controllerPos != null) {
            nbt.putLong("controller", controllerPos.asLong());
        }

        // Direct outputs
        nbt.putInt("out_count", outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            nbt.putLong("o" + i, outputs.get(i).asLong());
        }

        // Buffer
        if (!buffer.isEmpty()) {
            var encoded = ItemStack.OPTIONAL_CODEC.encodeStart(lookup.getOps(net.minecraft.nbt.NbtOps.INSTANCE), buffer)
                    .getOrThrow();
            nbt.put("buffer", encoded);
        }
    }

    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        // Controller
        //? if >=1.21.8 {
        nbt.getLong("controller").ifPresent(pos -> controllerPos = BlockPos.fromLong(pos));

        // Outputs
        outputs.clear();
        int c = nbt.getInt("out_count").orElse(0);
        for (int i = 0; i < c; i++) {
            nbt.getLong("o" + i).ifPresent(pos -> outputs.add(BlockPos.fromLong(pos)));
        }
        //?} else {
        /*if (nbt.contains("controller")) {
            controllerPos = BlockPos.fromLong(nbt.getLong("controller"));
        }

        outputs.clear();
        int c = nbt.getInt("out_count");
        for (int i = 0; i < c; i++) {
            if (nbt.contains("o" + i)) {
                outputs.add(BlockPos.fromLong(nbt.getLong("o" + i)));
            }
        }
        *///?}

        // Buffer
        if (nbt.contains("buffer")) {
            buffer = ItemStack.OPTIONAL_CODEC.parse(lookup.getOps(net.minecraft.nbt.NbtOps.INSTANCE), nbt.get("buffer"))
                    .result()
                    .orElse(ItemStack.EMPTY);
        } else {
            buffer = ItemStack.EMPTY;
        }
    }
}