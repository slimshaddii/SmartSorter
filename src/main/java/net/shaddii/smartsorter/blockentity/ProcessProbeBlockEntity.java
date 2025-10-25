package net.shaddii.smartsorter.blockentity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.ProcessProbeBlock;
import net.shaddii.smartsorter.blockentity.processor.*;
import net.shaddii.smartsorter.util.*;

//? if >=1.21.8 {
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?} else {
/*import net.minecraft.registry.RegistryWrapper;
 *///?}

/**
 * Process Probe Block Entity - Manages automated furnace processing.
 * REFACTORED: ~250 lines (down from 800+)
 */
public class ProcessProbeBlockEntity extends BlockEntity implements ControllerLinkable {

    // ========================================
    // CONSTANTS
    // ========================================

    private static final int TICK_INTERVAL = 10;
    private static final long CACHE_CLEAN_INTERVAL = 200L;

    // ========================================
    // SERVICE COMPONENTS
    // ========================================

    private final RecipeValidator recipeValidator;
    private final SmeltingProcessor smeltingProcessor;
    private final ExperienceCollector experienceCollector;

    // ========================================
    // FIELDS
    // ========================================

    // Core properties
    private BlockPos controllerPos;
    private Direction facing;
    private boolean enabled = false;
    private String machineType = "None";
    private BlockPos targetMachinePos;

    // State tracking
    private boolean wasRedstonePowered = false;
    private boolean isLinked = false;
    private int tickCounter = 0;
    private boolean needsInitialLink = false;

    // Configuration
    private ProcessProbeConfig config;
    private boolean hasBeenConfigured = false;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public ProcessProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROCESS_PROBE_BE_TYPE, pos, state);

        try {
            this.facing = state.get(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            this.facing = Direction.NORTH;
        }

        this.config = new ProcessProbeConfig(pos, "Unknown");

        // Initialize services
        this.experienceCollector = new ExperienceCollector();
        this.recipeValidator = new RecipeValidator();
        this.smeltingProcessor = new SmeltingProcessor(recipeValidator, experienceCollector);
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(World world, BlockPos pos, BlockState state, ProcessProbeBlockEntity be) {
        if (world == null || world.isClient()) return;

        be.tickCounter++;

        if (be.needsInitialLink) {
            boolean powered = isReceivingRedstone(world, pos);
            if (powered) {
                be.attemptLink(world, state);
            }
            be.needsInitialLink = false;
            be.wasRedstonePowered = powered;
        }

        // Check if probe is enabled by controller
        if (!be.checkControllerEnabled(world)) return;

        // Check redstone state changes
        boolean powered = isReceivingRedstone(world, pos);
        if (powered != be.wasRedstonePowered) {
            if (powered) {
                be.attemptLink(world, state);
            } else {
                be.disconnect(world);
            }
            be.wasRedstonePowered = powered;
        }

        be.setEnabled(powered);

        // Process every TICK_INTERVAL
        if (be.tickCounter >= TICK_INTERVAL) {
            be.tickCounter = 0;

            if (be.enabled && be.isLinked && world instanceof ServerWorld serverWorld) {
                be.processTick(serverWorld);
            }
        }

        // Clean caches periodically
        if (be.tickCounter % CACHE_CLEAN_INTERVAL == 0) {
            be.cleanCaches();
        }
    }

    private boolean checkControllerEnabled(World world) {
        if (controllerPos == null) return true;

        BlockEntity controllerBE = world.getBlockEntity(controllerPos);
        if (controllerBE instanceof StorageControllerBlockEntity controller) {
            ProcessProbeConfig config = controller.getProbeConfig(pos);
            return config == null || config.enabled;
        }

        return true;
    }

    private void processTick(ServerWorld world) {
        if (controllerPos == null) return;

        BlockEntity controllerBE = world.getBlockEntity(controllerPos);
        if (!(controllerBE instanceof StorageControllerBlockEntity controller)) return;

        ProcessProbeConfig config = controller.getProbeConfig(pos);
        if (config == null || !config.enabled) return;

        // Update facing
        try {
            BlockState state = world.getBlockState(pos);
            facing = state.get(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            facing = Direction.NORTH;
        }

        // Get target machine
        BlockPos machinePos = pos.offset(facing);
        BlockEntity blockEntity = world.getBlockEntity(machinePos);

        if (blockEntity instanceof AbstractFurnaceBlockEntity smeltingMachine) {
            smeltingProcessor.processSmeltingMachine(world, pos, smeltingMachine, controller, config);

            // Update config with processed count
            int processedCount = smeltingProcessor.getItemsProcessed();
            if (config.itemsProcessed != processedCount) {
                config.itemsProcessed = processedCount;
                controller.updateProbeConfig(config);
            }
        }
    }

    // ========================================
    // LINKING LOGIC
    // ========================================

    private void attemptLink(World world, BlockState state) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        // Update facing
        try {
            facing = state.get(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            facing = Direction.NORTH;
        }

        // Check for valid machine
        BlockPos machinePos = pos.offset(facing);
        BlockEntity machineEntity = world.getBlockEntity(machinePos);
        BlockState machineState = world.getBlockState(machinePos);

        if (!isValidProcessingMachine(machineEntity, machineState)) {
            notifyPlayers(serverWorld, "No valid processing machine found", false);
            isLinked = false;
            return;
        }

        updateMachineType(machineState);
        targetMachinePos = machinePos;

        // Find controller through redstone network
        BlockPos foundController = ControllerFinder.findController(serverWorld, pos);

        if (foundController != null) {
            BlockEntity be = world.getBlockEntity(foundController);
            if (be instanceof StorageControllerBlockEntity controller) {
                boolean success = controller.registerProcessProbe(pos, machineType);

                if (success) {
                    this.controllerPos = foundController;
                    isLinked = true;
                    markDirty();

                    String message = String.format("Linked to %s - Link Active", machineType);
                    notifyPlayers(serverWorld, message, true);
                } else {
                    isLinked = false;
                    notifyPlayers(serverWorld, "Controller rejected link", false);
                }
            }
        } else {
            isLinked = false;
            notifyPlayers(serverWorld, "No Storage Controller found in redstone network", false);
        }
    }

    private void disconnect(World world) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        if (isLinked && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                controller.unregisterProcessProbe(pos);
            }

            isLinked = false;
            notifyPlayers(serverWorld, "Link Inactive", false);
        }

        enabled = false;
    }

    // ========================================
    // UTILITY METHODS
    // ========================================

    private static boolean isReceivingRedstone(World world, BlockPos pos) {
        if (world.isReceivingRedstonePower(pos)) return true;

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = world.getBlockState(neighborPos);

            if (neighborState.isOf(Blocks.REDSTONE_WIRE)) {
                int power = neighborState.get(net.minecraft.block.RedstoneWireBlock.POWER);
                if (power > 0) return true;
            }

            if (neighborState.isOf(Blocks.LEVER) &&
                    neighborState.get(net.minecraft.block.LeverBlock.POWERED)) {
                return true;
            }

            if (world.getEmittedRedstonePower(neighborPos, dir.getOpposite()) > 0) {
                return true;
            }
        }

        return false;
    }

    private boolean isValidProcessingMachine(BlockEntity entity, BlockState state) {
        if (!(entity instanceof AbstractFurnaceBlockEntity)) return false;

        return state.isOf(Blocks.FURNACE) ||
                state.isOf(Blocks.BLAST_FURNACE) ||
                state.isOf(Blocks.SMOKER);
    }

    private void updateMachineType(BlockState state) {
        if (state.isOf(Blocks.FURNACE)) {
            machineType = "Furnace";
        } else if (state.isOf(Blocks.BLAST_FURNACE)) {
            machineType = "Blast Furnace";
        } else if (state.isOf(Blocks.SMOKER)) {
            machineType = "Smoker";
        } else {
            machineType = "Unknown";
        }

        if (config != null) {
            config.machineType = machineType;
        }
    }

    private void notifyPlayers(ServerWorld world, String message, boolean success) {
        Text text = Text.literal(message).formatted(success ? Formatting.GREEN : Formatting.YELLOW);

        for (net.minecraft.server.network.ServerPlayerEntity player : world.getPlayers()) {
            if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) < 256) {
                player.sendMessage(text, true);
            }
        }
    }

    private void cleanCaches() {
        recipeValidator.cleanCache();
        // Experience collector cache is small enough to not need frequent cleaning
    }

    // ========================================
    // GETTERS & SETTERS
    // ========================================

    @Override
    public void setController(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
        markDirty();
    }

    @Override
    public BlockPos getController() {
        return controllerPos;
    }

    public ProcessProbeConfig getConfig() {
        if (config == null) {
            config = new ProcessProbeConfig(this.pos, this.machineType);
        }
        config.position = this.pos;
        config.machineType = this.machineType;
        config.itemsProcessed = smeltingProcessor.getItemsProcessed();
        return config;
    }

    public void setConfig(ProcessProbeConfig newConfig) {
        this.config = newConfig.copy();
        this.config.position = this.pos;

        smeltingProcessor.setItemsProcessed(newConfig.itemsProcessed);
        this.hasBeenConfigured = true;

        markDirty();
    }

    public boolean addLinkedBlock(BlockPos controllerPos) {
        if (this.controllerPos == null || !this.controllerPos.equals(controllerPos)) {
            setController(controllerPos);
            return true;
        }
        return false;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            markDirty();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getProcessedCount() {
        return smeltingProcessor.getItemsProcessed();
    }

    public String getMachineType() {
        return machineType;
    }

    public String getStatusText() {
        if (!enabled) return "§cDisabled";
        if (!isLinked) return "§eNot Linked";
        if (targetMachinePos == null) return "§eNo Machine Found";
        return "§aActive";
    }

    public void collectExperience(PlayerEntity player) {
        // Experience is automatically sent to controller
        player.sendMessage(Text.literal("§eExperience is stored in the controller!"), true);
    }

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    //? if >=1.21.8 {
    @Override
    public void writeData(WriteView view) {
        super.writeData(view);

        if (controllerPos != null) {
            view.putLong("ControllerPos", controllerPos.asLong());
        }
        if (targetMachinePos != null) {
            view.putLong("TargetMachine", targetMachinePos.asLong());
        }

        view.putBoolean("Enabled", enabled);
        view.putInt("Processed", smeltingProcessor.getItemsProcessed());
        view.putString("MachineType", machineType);
        view.putBoolean("WasRedstonePowered", wasRedstonePowered);
        view.putBoolean("IsLinked", isLinked);
        view.putBoolean("HasBeenConfigured", hasBeenConfigured);

        if (config != null && hasBeenConfigured) {
            if (config.customName != null) {
                view.putString("Config_CustomName", config.customName);
            }
            view.putBoolean("Config_Enabled", config.enabled);
            view.putString("Config_RecipeFilter", config.recipeFilter.asString());
            view.putString("Config_FuelFilter", config.fuelFilter.asString());
            view.putInt("Config_ItemsProcessed", config.itemsProcessed);
            view.putInt("Config_Index", config.index);
        }
    }

    @Override
    public void readData(ReadView view) {
        super.readData(view);

        view.getOptionalLong("ControllerPos").ifPresent(posLong ->
                this.controllerPos = BlockPos.fromLong(posLong)
        );
        view.getOptionalLong("TargetMachine").ifPresent(posLong ->
                this.targetMachinePos = BlockPos.fromLong(posLong)
        );

        this.enabled = view.getBoolean("Enabled", false);
        smeltingProcessor.setItemsProcessed(view.getInt("Processed", 0));
        this.machineType = view.getString("MachineType", "None");
        this.wasRedstonePowered = view.getBoolean("WasRedstonePowered", false);
        this.isLinked = view.getBoolean("IsLinked", false);
        this.hasBeenConfigured = view.getBoolean("HasBeenConfigured", false);

        if (this.isLinked && this.controllerPos != null) {
            this.needsInitialLink = true;
            this.isLinked = false; // Reset until re-linked
        }

        if (hasBeenConfigured) {
            this.config = new ProcessProbeConfig(this.pos, this.machineType);
            config.customName = view.getString("Config_CustomName", null);
            config.enabled = view.getBoolean("Config_Enabled", true);
            config.recipeFilter = RecipeFilterMode.fromString(
                    view.getString("Config_RecipeFilter", "ORES_ONLY")
            );
            config.fuelFilter = FuelFilterMode.fromString(
                    view.getString("Config_FuelFilter", "COAL_ONLY")
            );
            config.itemsProcessed = view.getInt("Config_ItemsProcessed", smeltingProcessor.getItemsProcessed());
            config.index = view.getInt("Config_Index", 0);
        } else {
            this.config = new ProcessProbeConfig(this.pos, this.machineType);
        }
    }
    //?} else {
    /*@Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);

        if (controllerPos != null) {
            nbt.putLong("ControllerPos", controllerPos.asLong());
        }
        if (targetMachinePos != null) {
            nbt.putLong("TargetMachine", targetMachinePos.asLong());
        }

        nbt.putBoolean("Enabled", enabled);
        nbt.putInt("Processed", smeltingProcessor.getItemsProcessed());
        nbt.putString("MachineType", machineType);
        nbt.putBoolean("WasRedstonePowered", wasRedstonePowered);
        nbt.putBoolean("IsLinked", isLinked);
        nbt.putBoolean("HasBeenConfigured", hasBeenConfigured);

        if (this.isLinked && this.controllerPos != null) {
            this.needsInitialLink = true;
            this.isLinked = false; // Reset until re-linked
        }

        if (config != null && hasBeenConfigured) {
            if (config.customName != null) {
                nbt.putString("Config_CustomName", config.customName);
            }
            nbt.putBoolean("Config_Enabled", config.enabled);
            nbt.putString("Config_RecipeFilter", config.recipeFilter.asString());
            nbt.putString("Config_FuelFilter", config.fuelFilter.asString());
            nbt.putInt("Config_ItemsProcessed", config.itemsProcessed);
            nbt.putInt("Config_Index", config.index);
        }
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);

        if (nbt.contains("ControllerPos")) {
            this.controllerPos = BlockPos.fromLong(nbt.getLong("ControllerPos"));
        }
        if (nbt.contains("TargetMachine")) {
            this.targetMachinePos = BlockPos.fromLong(nbt.getLong("TargetMachine"));
        }

        this.enabled = nbt.getBoolean("Enabled");
        smeltingProcessor.setItemsProcessed(nbt.getInt("Processed"));
        this.machineType = nbt.contains("MachineType") ? nbt.getString("MachineType") : "None";
        this.wasRedstonePowered = nbt.getBoolean("WasRedstonePowered");
        this.isLinked = nbt.getBoolean("IsLinked");
        this.hasBeenConfigured = nbt.getBoolean("HasBeenConfigured");

        if (hasBeenConfigured) {
            this.config = new ProcessProbeConfig(this.pos, this.machineType);
            config.customName = nbt.contains("Config_CustomName") ? nbt.getString("Config_CustomName") : null;
            config.enabled = nbt.contains("Config_Enabled") ? nbt.getBoolean("Config_Enabled") : true;
            config.recipeFilter = RecipeFilterMode.fromString(
                nbt.contains("Config_RecipeFilter") ? nbt.getString("Config_RecipeFilter") : "ORES_ONLY"
            );
            config.fuelFilter = FuelFilterMode.fromString(
                nbt.contains("Config_FuelFilter") ? nbt.getString("Config_FuelFilter") : "COAL_ONLY"
            );
            config.itemsProcessed = nbt.contains("Config_ItemsProcessed") ?
                nbt.getInt("Config_ItemsProcessed") : smeltingProcessor.getItemsProcessed();
            config.index = nbt.contains("Config_Index") ? nbt.getInt("Config_Index") : 0;
        } else {
            this.config = new ProcessProbeConfig(this.pos, this.machineType);
        }
    }
    *///?}

    // ========================================
    // CLEANUP
    // ========================================

    public void onRemoved() {
        // Grab config from controller before it's gone
        if (world != null && !world.isClient() && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                ProcessProbeConfig controllerConfig = controller.getProbeConfig(pos);
                if (controllerConfig != null) {
                    this.config = controllerConfig.copy();
                    this.hasBeenConfigured = true;
                    markDirty();
                }

                controller.unregisterProcessProbe(pos);
            }
        }

        this.controllerPos = null;
        this.targetMachinePos = null;

        // Clear caches
        recipeValidator.clearCache();
        experienceCollector.clearCache();
    }

    public void clearController() {
        // BEFORE clearing, ensure config is saved locally
        if (world != null && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                ProcessProbeConfig controllerConfig = controller.getProbeConfig(pos);
                if (controllerConfig != null) {
                    // Save the controller's config locally before unlinking
                    this.config = controllerConfig.copy();
                    this.hasBeenConfigured = true;
                }
            }
        }

        this.controllerPos = null;
        this.isLinked = false;

        markDirty();

        if (world != null) {
            BlockState state = world.getBlockState(pos);
            world.updateListeners(pos, state, state, 3);
        }
    }

    public boolean hasBeenConfigured() {
        return hasBeenConfigured;
    }
}