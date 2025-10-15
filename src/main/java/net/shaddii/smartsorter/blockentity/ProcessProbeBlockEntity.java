package net.shaddii.smartsorter.blockentity;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.recipe.BlastingRecipe;
import net.minecraft.recipe.SmokingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
//? if >=1.21.8 {

import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?}
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.ProcessProbeBlock;
import net.shaddii.smartsorter.network.ProbeStatsSyncPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ControllerLinkable;
import net.shaddii.smartsorter.util.FuelFilterMode;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.RecipeFilterMode;
//? if <= 1.21.1 {
/*import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
*///?}

import java.util.*;

public class ProcessProbeBlockEntity extends BlockEntity implements ControllerLinkable {

    // Core properties
    private BlockPos controllerPos;
    private Direction facing;
    private boolean enabled = false; // OFF by default
    private int processedCount = 0;
    private String machineType = "None";
    private BlockPos targetMachinePos;

    // Experience storage
    private int storedExperience = 0;

    // Cache for experience values
    private final Map<ItemVariant, Float> experienceCache = new HashMap<>();

    // Performance optimization
    private int tickCounter = 0;
    private static final int TICK_INTERVAL = 10;
    private static final int MAX_FUEL_PER_INSERT = 16;
    private static final int MAX_INPUT_PER_INSERT = 4;

    // Caching
    private final Map<ItemVariant, CachedRecipeCheck> recipeCache = new HashMap<>();
    private final Set<ItemVariant> knownFuels = new HashSet<>();
    private final Set<ItemVariant> knownNonFuels = new HashSet<>();

    // Redstone state tracking
    private boolean wasRedstonePowered = false;
    private boolean isLinked = false;

    // Store the probe's own configuration
    private ProcessProbeConfig config;
    private boolean hasBeenConfigured = false;

    // Slot configurations
    private interface SlotConfig {
        int[] getInputSlots();
        int[] getFuelSlots();
        int[] getOutputSlots();
    }

    private static class FurnaceSlots implements SlotConfig {
        public int[] getInputSlots() { return new int[]{0}; }
        public int[] getFuelSlots() { return new int[]{1}; }
        public int[] getOutputSlots() { return new int[]{2}; }
    }

    private static class CachedRecipeCheck {
        final boolean canProcess;
        final long timestamp;

        CachedRecipeCheck(boolean canProcess) {
            this.canProcess = canProcess;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < 10000;
        }
    }

    public ProcessProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROCESS_PROBE_BE_TYPE, pos, state);
        try {
            this.facing = state.get(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            this.facing = Direction.NORTH;
        }

        // Initialize with default config
        this.config = new ProcessProbeConfig(pos, "Unknown");
    }

    @Override
    public void setController(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
        markDirty();
    }

    @Override
    public BlockPos getController() {
        return controllerPos;
    }

    public boolean addLinkedBlock(BlockPos controllerPos) {
        if (this.controllerPos == null || !this.controllerPos.equals(controllerPos)) {
            setController(controllerPos);
            return true;
        }
        return false;
    }

    // Get the probe's configuration
    public ProcessProbeConfig getConfig() {
        if (config == null) {
            config = new ProcessProbeConfig(this.pos, this.machineType);
        }
        // Always ensure position is current
        config.position = this.pos;
        config.machineType = this.machineType;
        config.itemsProcessed = this.processedCount;
        return config;
    }

    // Update the probe's configuration
    public void setConfig(ProcessProbeConfig newConfig) {
        this.config = newConfig.copy();
        this.config.position = this.pos; // Ensure position stays correct

        // Sync fields back to legacy fields (for backwards compatibility)
        this.processedCount = newConfig.itemsProcessed;

        // Mark as configured
        this.hasBeenConfigured = true;

        markDirty();
    }

    private void syncStatsToClients() {
        if (world instanceof ServerWorld serverWorld && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                // Send to all players viewing this controller
                for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                    if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                        if (handler.controller == controller) {
                            ServerPlayNetworking.send(player,
                                    new ProbeStatsSyncPayload(pos, processedCount));
                        }
                    }
                }
            }
        }
    }

    public boolean hasBeenConfigured() {
        return hasBeenConfigured;
    }

    public static void tick(World world, BlockPos pos, BlockState state, ProcessProbeBlockEntity be) {
        if (world == null || world.isClient()) return;

        be.tickCounter++;

        // Check controller config first
        if (be.controllerPos != null) {
            BlockEntity controllerBE = world.getBlockEntity(be.controllerPos);
            if (controllerBE instanceof StorageControllerBlockEntity controller) {
                ProcessProbeConfig config = controller.getProbeConfig(pos);
                if (config != null && !config.enabled) {
                    // Probe is disabled in config, don't process
                    return;
                }
            }
        }

        // Check redstone state from ANY side (including below)
        boolean powered = isReceivingRedstone(world, pos);

        // Redstone state change detection
        if (powered != be.wasRedstonePowered) {
            if (powered) {
                // Redstone turned ON - attempt to link
                be.attemptLink(world, state);
            } else {
                // Redstone turned OFF - disable
                be.disconnect(world);
            }
            be.wasRedstonePowered = powered;
        }

        // Only process if enabled (redstone ON)
        be.setEnabled(powered);

        if (be.tickCounter < TICK_INTERVAL) return;
        be.tickCounter = 0;

        if (!be.enabled || !be.isLinked) return;
        if (!(world instanceof ServerWorld serverWorld)) return;

        if (be.controllerPos == null) return;
        BlockEntity controllerBE = world.getBlockEntity(be.controllerPos);
        if (!(controllerBE instanceof StorageControllerBlockEntity controller)) return;

        // Check if probe is enabled in config
        ProcessProbeConfig config = controller.getProbeConfig(pos);
        if (config != null && !config.enabled) {
            // Probe is disabled via UI - don't process
            return;
        }

        try {
            be.facing = state.get(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            be.facing = Direction.NORTH;
        }

        be.handleProcessTick(serverWorld, controller);

        if (be.tickCounter % 200 == 0) {
            be.cleanCache();
        }
    }

    /**
     * Check for redstone power from ANY side including below
     */
    private static boolean isReceivingRedstone(World world, BlockPos pos) {
        // Check direct power to the block
        if (world.isReceivingRedstonePower(pos)) {
            return true;
        }

        // Check all directions including DOWN
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = world.getBlockState(neighborPos);

            // Check for redstone dust specifically
            if (neighborState.isOf(Blocks.REDSTONE_WIRE)) {
                int power = neighborState.get(net.minecraft.block.RedstoneWireBlock.POWER);
                if (power > 0) {
                    return true;
                }
            }

            // Check for powered levers
            if (neighborState.isOf(Blocks.LEVER)) {
                if (neighborState.get(net.minecraft.block.LeverBlock.POWERED)) {
                    return true;
                }
            }

            // Check other redstone components
            if (world.getEmittedRedstonePower(neighborPos, dir.getOpposite()) > 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Attempt to link when redstone turns ON
     */
    private void attemptLink(World world, BlockState state) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        // First, detect what machine we're facing
        try {
            facing = state.get(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            facing = Direction.NORTH;
        }

        BlockPos machinePos = pos.offset(facing);
        BlockEntity machineEntity = world.getBlockEntity(machinePos);
        BlockState machineState = world.getBlockState(machinePos);

        // Check if it's a valid processing machine
        if (!isValidProcessingMachine(machineEntity, machineState)) {
            notifyPlayers(serverWorld, "No valid processing machine found", false);
            isLinked = false;
            return;
        }

        // Update machine type
        updateMachineType(machineState, machineEntity);
        targetMachinePos = machinePos;

        // Try the original trace method first
        BlockPos controllerInNetwork = traceRedstoneToController(serverWorld, pos, 256);

        // If that fails, try the simpler radius search
        if (controllerInNetwork == null) {
            controllerInNetwork = findControllerWithRedstoneConnection(serverWorld, pos, 32);
        }

        if (controllerInNetwork != null) {
            BlockEntity be = world.getBlockEntity(controllerInNetwork);
            if (be instanceof StorageControllerBlockEntity controller) {
                boolean success = controller.registerProcessProbe(pos, machineType);

                if (success) {
                    this.controllerPos = controllerInNetwork;
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

    /**
     * Trace through the redstone network to find a Storage Controller
     * Uses flood-fill algorithm to explore all connected redstone components
     */
    private BlockPos traceRedstoneToController(ServerWorld world, BlockPos start, int maxBlocks) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> toVisit = new LinkedList<>();

        // Add the probe position itself
        toVisit.add(start);

        // Also add all adjacent blocks to start the search
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = start.offset(dir);
            toVisit.add(adjacent);
        }

        BlockPos closestController = null;
        double closestDistance = Double.MAX_VALUE;
        int blocksChecked = 0;

        while (!toVisit.isEmpty() && blocksChecked < maxBlocks) {
            BlockPos current = toVisit.poll();

            if (visited.contains(current)) {
                continue;
            }

            visited.add(current);
            blocksChecked++;

            // Check if this position is a Storage Controller
            BlockEntity be = world.getBlockEntity(current);
            if (be instanceof StorageControllerBlockEntity) {
                double dist = start.getSquaredDistance(current);
                if (dist < closestDistance) {
                    closestDistance = dist;
                    closestController = current;
                }
                continue; // Keep searching for potentially closer controllers
            }

            BlockState state = world.getBlockState(current);

            // Check if this is a redstone component
            if (isRedstoneComponent(world, current, state)) {
                // Add all neighbors to search queue
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.offset(dir);
                    if (!visited.contains(neighbor)) {
                        toVisit.add(neighbor);

                        // IMPORTANT: Also check if the neighbor is a controller
                        // This handles cases where redstone dust is next to the controller
                        BlockEntity neighborBE = world.getBlockEntity(neighbor);
                        if (neighborBE instanceof StorageControllerBlockEntity) {
                            double dist = start.getSquaredDistance(neighbor);
                            if (dist < closestDistance) {
                                closestDistance = dist;
                                closestController = neighbor;
                            }
                        }
                    }
                }

                // Check diagonals for better connectivity
                for (Direction dir1 : Direction.Type.HORIZONTAL) {
                    for (Direction dir2 : new Direction[]{Direction.UP, Direction.DOWN}) {
                        BlockPos diagonal = current.offset(dir1).offset(dir2);
                        if (!visited.contains(diagonal)) {
                            BlockState diagonalState = world.getBlockState(diagonal);
                            if (isRedstoneComponent(world, diagonal, diagonalState)) {
                                toVisit.add(diagonal);
                            }

                            // Also check if diagonal position is a controller
                            BlockEntity diagonalBE = world.getBlockEntity(diagonal);
                            if (diagonalBE instanceof StorageControllerBlockEntity) {
                                double dist = start.getSquaredDistance(diagonal);
                                if (dist < closestDistance) {
                                    closestDistance = dist;
                                    closestController = diagonal;
                                }
                            }
                        }
                    }
                }
            }
        }

        return closestController;
    }

    /**
     * Alternative simpler approach: Just search in a radius for any controller
     * and check if there's a redstone path between probe and controller
     */
    private BlockPos findControllerWithRedstoneConnection(ServerWorld world, BlockPos probePos, int radius) {
        // First, find all controllers in range
        List<BlockPos> controllers = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = probePos.add(x, y, z);
                    BlockEntity be = world.getBlockEntity(checkPos);
                    if (be instanceof StorageControllerBlockEntity) {
                        controllers.add(checkPos);
                    }
                }
            }
        }

        if (controllers.isEmpty()) {
            return null;
        }

        // Now check if any controller has redstone near it
        for (BlockPos controllerPos : controllers) {
            // Check all sides of the controller for redstone
            for (Direction dir : Direction.values()) {
                BlockPos adjacent = controllerPos.offset(dir);
                BlockState adjacentState = world.getBlockState(adjacent);

                if (adjacentState.isOf(Blocks.REDSTONE_WIRE) ||
                        adjacentState.isOf(Blocks.LEVER) ||
                        adjacentState.isOf(Blocks.REDSTONE_TORCH) ||
                        adjacentState.isOf(Blocks.REDSTONE_BLOCK)) {

                    // Verify there's a path from probe to this redstone
                    if (hasRedstonePath(world, probePos, adjacent, 256)) {
                        return controllerPos;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Check if there's a redstone path between two positions
     */
    private boolean hasRedstonePath(ServerWorld world, BlockPos start, BlockPos end, int maxBlocks) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> toVisit = new LinkedList<>();

        // Start from all blocks adjacent to start position
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = start.offset(dir);
            if (isRedstoneComponent(world, adjacent, world.getBlockState(adjacent))) {
                toVisit.add(adjacent);
            }
        }

        int checked = 0;
        while (!toVisit.isEmpty() && checked < maxBlocks) {
            BlockPos current = toVisit.poll();

            if (visited.contains(current)) continue;
            visited.add(current);
            checked++;

            // Check if we reached the target
            if (current.equals(end) || current.getManhattanDistance(end) <= 1) {
                return true;
            }

            // Add connected redstone components
            BlockState state = world.getBlockState(current);
            if (isRedstoneComponent(world, current, state)) {
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.offset(dir);
                    if (!visited.contains(neighbor)) {
                        toVisit.add(neighbor);
                    }
                }
            }
        }

        return false;
    }

    /**
     * Check if a block is part of the redstone network
     */
    private boolean isRedstoneComponent(World world, BlockPos pos, BlockState state) {
        // Redstone dust - most important
        if (state.isOf(Blocks.REDSTONE_WIRE)) {
            return true;
        }

        // Levers
        if (state.isOf(Blocks.LEVER)) {
            return true;
        }

        // Redstone torches
        if (state.isOf(Blocks.REDSTONE_TORCH) ||
                state.isOf(Blocks.REDSTONE_WALL_TORCH)) {
            return true;
        }

        // Redstone blocks
        if (state.isOf(Blocks.REDSTONE_BLOCK)) {
            return true;
        }

        // Repeaters
        if (state.isOf(Blocks.REPEATER)) {
            return true;
        }

        // Comparators
        if (state.isOf(Blocks.COMPARATOR)) {
            return true;
        }

        return false;
    }

    /**
     * Disconnect when redstone turns OFF
     */
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

    /**
     * Check if the block is a valid processing machine
     * Only furnaces, blast furnaces, and smokers - NOT crafting tables, looms, etc.
     */
    private boolean isValidProcessingMachine(BlockEntity entity, BlockState state) {
        // Must be a furnace-type block entity
        if (!(entity instanceof AbstractFurnaceBlockEntity)) {
            return false;
        }

        // Valid processing blocks
        return state.isOf(Blocks.FURNACE)
                || state.isOf(Blocks.BLAST_FURNACE)
                || state.isOf(Blocks.SMOKER);
    }

    private void updateMachineType(BlockState state, BlockEntity blockEntity) {
        if (state.isOf(Blocks.FURNACE)) {
            machineType = "Furnace";
        } else if (state.isOf(Blocks.BLAST_FURNACE)) {
            machineType = "Blast Furnace";
        } else if (state.isOf(Blocks.SMOKER)) {
            machineType = "Smoker";
        } else {
            machineType = "Unknown";
        }

        // Update config's machine type too
        if (config != null) {
            config.machineType = machineType;
        }
    }

    private void notifyPlayers(ServerWorld world, String message, boolean success) {
        Text text = Text.literal(message)
                .formatted(success ? Formatting.GREEN : Formatting.YELLOW);

        // Send action bar message to nearby players
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) < 256) { // 16 blocks
                player.sendMessage(text, true); // true = action bar
            }
        }
    }

    private void handleProcessTick(ServerWorld world, StorageControllerBlockEntity controller) {
        BlockPos machinePos = pos.offset(facing);
        BlockEntity blockEntity = world.getBlockEntity(machinePos);

        if (!(blockEntity instanceof Inventory inventory)) {
            return;
        }

        targetMachinePos = machinePos;
        SlotConfig slots = getSlotConfig(blockEntity);

        if (slots == null) {
            return;
        }

        // GET THE CONFIG
        ProcessProbeConfig config = controller.getProbeConfig(pos);
        if (config == null) {
            return; // No config = don't process
        }

        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            processFurnaceOptimized(world, furnace, controller, slots, config);
        }
    }

    private SlotConfig getSlotConfig(BlockEntity blockEntity) {
        if (blockEntity instanceof AbstractFurnaceBlockEntity) {
            return new FurnaceSlots();
        }
        return null;
    }

    private void processFurnaceOptimized(ServerWorld world, AbstractFurnaceBlockEntity furnace,
                                         StorageControllerBlockEntity controller, SlotConfig slots,
                                         ProcessProbeConfig config) {

        boolean outputsHandled = extractOutputsOptimized(world, furnace, controller, slots);
        if (!outputsHandled) {
            return;
        }

        boolean needsInput = false;
        boolean needsFuel = false;
        int inputSpace = 0;
        int fuelSpace = 0;
        ItemStack currentInput = ItemStack.EMPTY;
        ItemStack currentFuel = ItemStack.EMPTY;

        for (int slot : slots.getInputSlots()) {
            ItemStack stack = furnace.getStack(slot);
            if (stack.isEmpty()) {
                needsInput = true;
                inputSpace = MAX_INPUT_PER_INSERT;
                break;
            } else if (stack.getCount() < stack.getMaxCount()) {
                needsInput = true;
                currentInput = stack;
                inputSpace = Math.min(stack.getMaxCount() - stack.getCount(), MAX_INPUT_PER_INSERT);
                break;
            }
        }

        for (int slot : slots.getFuelSlots()) {
            ItemStack stack = furnace.getStack(slot);
            if (stack.isEmpty()) {
                needsFuel = true;
                fuelSpace = MAX_FUEL_PER_INSERT;
                break;
            } else if (stack.getCount() < stack.getMaxCount()) {
                needsFuel = true;
                currentFuel = stack;
                fuelSpace = Math.min(stack.getMaxCount() - stack.getCount(), MAX_FUEL_PER_INSERT);
                break;
            }
        }

        if (!needsInput && !needsFuel) {
            return;
        }

        ItemStack foundInput = ItemStack.EMPTY;
        ItemStack foundFuel = ItemStack.EMPTY;

        Map<ItemVariant, Long> networkItems = controller.getNetworkItems();
        RecipeType<?> recipeType = getRecipeTypeForFurnace(furnace);

        for (Map.Entry<ItemVariant, Long> entry : networkItems.entrySet()) {
            if (entry.getValue() <= 0) continue;

            ItemVariant variant = entry.getKey();

            if (needsInput && foundInput.isEmpty()) {
                if (currentInput.isEmpty()) {
                    if (canSmelt(world, variant, furnace, recipeType) &&
                            matchesRecipeFilter(variant, config.recipeFilter)) {
                        int amount = (int) Math.min(inputSpace, entry.getValue());
                        foundInput = controller.extractItem(variant, amount);
                        needsInput = false;
                    }
                } else {
                    if (ItemVariant.of(currentInput).equals(variant)) {
                        int amount = (int) Math.min(inputSpace, entry.getValue());
                        foundInput = controller.extractItem(variant, amount);
                        needsInput = false;
                    }
                }
            }

            if (needsFuel && foundFuel.isEmpty()) {
                if (currentFuel.isEmpty()) {
                    if (isFuel(world, variant) &&
                            matchesFuelFilter(variant, config.fuelFilter)) {
                        int amount = (int) Math.min(fuelSpace, entry.getValue());
                        foundFuel = controller.extractItem(variant, amount);
                        needsFuel = false;
                    }
                } else {
                    if (ItemVariant.of(currentFuel).equals(variant)) {
                        int amount = (int) Math.min(fuelSpace, entry.getValue());
                        foundFuel = controller.extractItem(variant, amount);
                        needsFuel = false;
                    }
                }
            }

            if (!needsInput && !needsFuel) {
                break;
            }
        }

        if (!foundInput.isEmpty()) {
            int inputSlot = slots.getInputSlots()[0];
            ItemStack existing = furnace.getStack(inputSlot);
            if (existing.isEmpty()) {
                furnace.setStack(inputSlot, foundInput);
            } else {
                existing.increment(foundInput.getCount());
            }
            furnace.markDirty();
        }

        if (!foundFuel.isEmpty()) {
            int fuelSlot = slots.getFuelSlots()[0];
            ItemStack existing = furnace.getStack(fuelSlot);
            if (existing.isEmpty()) {
                furnace.setStack(fuelSlot, foundFuel);
            } else {
                existing.increment(foundFuel.getCount());
            }
            furnace.markDirty();
        }
    }

    private boolean canSmelt(ServerWorld world, ItemVariant variant,
                             AbstractFurnaceBlockEntity furnace, RecipeType<?> recipeType) {
        if (recipeType == null) return false;

        CachedRecipeCheck cached = recipeCache.get(variant);
        if (cached != null && cached.isValid()) {
            return cached.canProcess;
        }

        ItemStack stack = variant.toStack(1);
        SingleStackRecipeInput recipeInput = new SingleStackRecipeInput(stack);

        boolean canSmelt = false;
        try {
            if (furnace instanceof FurnaceBlockEntity) {
                canSmelt = world.getRecipeManager()
                        .getFirstMatch((RecipeType<SmeltingRecipe>) RecipeType.SMELTING, recipeInput, world)
                        .isPresent();
            } else if (furnace instanceof BlastFurnaceBlockEntity) {
                canSmelt = world.getRecipeManager()
                        .getFirstMatch((RecipeType<BlastingRecipe>) RecipeType.BLASTING, recipeInput, world)
                        .isPresent();
            } else if (furnace instanceof SmokerBlockEntity) {
                canSmelt = world.getRecipeManager()
                        .getFirstMatch((RecipeType<SmokingRecipe>) RecipeType.SMOKING, recipeInput, world)
                        .isPresent();
            }
        } catch (Exception e) {
            canSmelt = false;
        }

        recipeCache.put(variant, new CachedRecipeCheck(canSmelt));
        return canSmelt;
    }

    private boolean isFuel(ServerWorld world, ItemVariant variant) {
        // Check cache first
        if (knownFuels.contains(variant)) {
            return true;
        }

        if (knownNonFuels.contains(variant)) {
            return false;
        }

        // Check if it's fuel
        //? if >= 1.21.8 {
        
        ItemStack stack = variant.toStack(1);
        boolean isFuel = world.getFuelRegistry().isFuel(stack);
        //?} else {
        /*ItemStack stack = variant.toStack(1);
        boolean isFuel = AbstractFurnaceBlockEntity.canUseAsFuel(stack);
        *///?}

        // Cache the result
        if (isFuel) {
            knownFuels.add(variant);
            return true;
        } else {
            knownNonFuels.add(variant);
            return false;
        }
    }

    private boolean extractOutputsOptimized(ServerWorld world, Inventory inventory,
                                            StorageControllerBlockEntity controller, SlotConfig slots) {
        boolean allExtracted = true;

        for (int slot : slots.getOutputSlots()) {
            ItemStack output = inventory.getStack(slot);
            if (!output.isEmpty()) {
                ItemStack toInsert = output.copy();
                ItemStack remaining = controller.insertItem(toInsert);

                if (remaining.isEmpty()) {
                    // Collect XP BEFORE clearing the slot
                    if (inventory instanceof AbstractFurnaceBlockEntity furnace) {
                        collectFurnaceExperience(world, furnace, controller, toInsert);
                    }

                    inventory.setStack(slot, ItemStack.EMPTY);
                    inventory.markDirty();
                    processedCount += toInsert.getCount();

                    // Update config
                    if (config != null) {
                        config.itemsProcessed = processedCount;
                    }

                    markDirty();

                    // Sync to clients
                    controller.syncProbeStatsToClients(pos, processedCount);
                } else {
                    allExtracted = false;
                }
            }
        }

        return allExtracted;
    }

    /**
     * Calculate and collect the actual XP from smelted items based on their recipes
     */
    private void collectFurnaceExperience(ServerWorld world, AbstractFurnaceBlockEntity furnace,
                                          StorageControllerBlockEntity controller, ItemStack outputStack) {
        RecipeType<?> recipeType = getRecipeTypeForFurnace(furnace);
        if (recipeType == null) return;

        // Get experience per item from cache or recipe lookup
        ItemVariant outputVariant = ItemVariant.of(outputStack);
        Float experiencePerItem = experienceCache.get(outputVariant);

        if (experiencePerItem == null) {
            experiencePerItem = getExperienceForOutput(world, outputStack, recipeType);
            experienceCache.put(outputVariant, experiencePerItem);
        }

        // Calculate total XP (experience is per item)
        int totalXP = Math.round(experiencePerItem * outputStack.getCount());

        if (totalXP > 0) {
            controller.addExperience(totalXP);
        }
    }

    /**
     * Look up the experience value for a given output item in the recipe manager
     * FIXED: Using craft() method instead of protected result()
     */
    private float getExperienceForOutput(ServerWorld world, ItemStack output, RecipeType<?> recipeType) {
        try {
            // Get all recipes and filter by type
            Collection<RecipeEntry<?>> allRecipes = world.getRecipeManager().values();

            for (RecipeEntry<?> recipeEntry : allRecipes) {
                Recipe<?> recipe = recipeEntry.value();

                // Check if this is the right recipe type
                if (recipe.getType() != recipeType) {
                    continue;
                }

                // Check if it's a cooking recipe
                if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
                    // Use craft() instead of protected result() method
                    // craft() returns a copy of the result ItemStack
                    ItemStack recipeOutput = cookingRecipe.craft(
                            new SingleStackRecipeInput(ItemStack.EMPTY), // dummy input
                            world.getRegistryManager()
                    );

                    // Compare with our output
                    if (ItemStack.areItemsEqual(recipeOutput, output)) {
                        // Found matching recipe - return its experience value
                        return cookingRecipe.getExperience();
                    }
                }
            }
        } catch (Exception e) {
            // If lookup fails, return default
            return 0.1f;
        }

        // No matching recipe found
        return 0.1f;
    }

    private RecipeType<?> getRecipeTypeForFurnace(AbstractFurnaceBlockEntity furnace) {
        if (furnace instanceof FurnaceBlockEntity) {
            return RecipeType.SMELTING;
        } else if (furnace instanceof BlastFurnaceBlockEntity) {
            return RecipeType.BLASTING;
        } else if (furnace instanceof SmokerBlockEntity) {
            return RecipeType.SMOKING;
        }
        return null;
    }

    /**
     * Clean caches periodically
     */
    private void cleanCache() {
        recipeCache.entrySet().removeIf(entry -> !entry.getValue().isValid());

        if (knownFuels.size() > 100) {
            knownFuels.clear();
        }
        if (knownNonFuels.size() > 500) {
            knownNonFuels.clear();
        }

        // Also clean experience cache if it gets too large
        if (experienceCache.size() > 200) {
            experienceCache.clear();
        }
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
        return processedCount;
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

    public int getStoredExperience() {
        return storedExperience;
    }

    public void collectExperience(PlayerEntity player) {
        if (storedExperience > 0) {
            player.addExperience(storedExperience);
            player.sendMessage(Text.literal(String.format("§aCollected %d XP!", storedExperience)), true);
            storedExperience = 0;
            markDirty();
        }
    }

    //? if >= 1.21.8 {
    
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
        view.putInt("Processed", processedCount);
        view.putString("MachineType", machineType);
        view.putInt("StoredXP", storedExperience);
        view.putBoolean("WasRedstonePowered", wasRedstonePowered);
        view.putBoolean("IsLinked", isLinked);

        // Save the configured flag
        view.putBoolean("HasBeenConfigured", hasBeenConfigured);

        // Only save config if it's been configured
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
        this.processedCount = view.getInt("Processed", 0);
        this.machineType = view.getString("MachineType", "None");
        this.storedExperience = view.getInt("StoredXP", 0);
        this.wasRedstonePowered = view.getBoolean("WasRedstonePowered", false);
        this.isLinked = view.getBoolean("IsLinked", false);

        // Load the configured flag
        this.hasBeenConfigured = view.getBoolean("HasBeenConfigured", false);

        // Conditional loading based on configured state
        if (hasBeenConfigured) {
            // Probe has saved config - restore it
            this.config = new ProcessProbeConfig(this.pos, this.machineType);
            config.customName = view.getString("Config_CustomName", null);
            config.enabled = view.getBoolean("Config_Enabled", true);
            config.recipeFilter = RecipeFilterMode.fromString(
                    view.getString("Config_RecipeFilter", "ORES_ONLY")
            );
            config.fuelFilter = FuelFilterMode.fromString(
                    view.getString("Config_FuelFilter", "COAL_ONLY")
            );
            config.itemsProcessed = view.getInt("Config_ItemsProcessed", this.processedCount);
            config.index = view.getInt("Config_Index", 0);
        } else {
            // Brand new probe - create default config
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
        nbt.putInt("Processed", processedCount);
        nbt.putString("MachineType", machineType);
        nbt.putInt("StoredXP", storedExperience);
        nbt.putBoolean("WasRedstonePowered", wasRedstonePowered);
        nbt.putBoolean("IsLinked", isLinked);

        // Save the configured flag
        nbt.putBoolean("HasBeenConfigured", hasBeenConfigured);

        // Only save config if it's been configured
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
        this.processedCount = nbt.getInt("Processed");
        this.machineType = nbt.contains("MachineType") ? nbt.getString("MachineType") : "None";
        this.storedExperience = nbt.getInt("StoredXP");
        this.wasRedstonePowered = nbt.getBoolean("WasRedstonePowered");
        this.isLinked = nbt.getBoolean("IsLinked");

        // Load the configured flag
        this.hasBeenConfigured = nbt.getBoolean("HasBeenConfigured");

        // Conditional loading based on configured state
        if (hasBeenConfigured) {
            // Probe has saved config - restore it
            this.config = new ProcessProbeConfig(this.pos, this.machineType);
            config.customName = nbt.contains("Config_CustomName") ? nbt.getString("Config_CustomName") : null;
            config.enabled = nbt.contains("Config_Enabled") ? nbt.getBoolean("Config_Enabled") : true;
            config.recipeFilter = RecipeFilterMode.fromString(
                    nbt.contains("Config_RecipeFilter") ? nbt.getString("Config_RecipeFilter") : "ORES_ONLY"
            );
            config.fuelFilter = FuelFilterMode.fromString(
                    nbt.contains("Config_FuelFilter") ? nbt.getString("Config_FuelFilter") : "COAL_ONLY"
            );
            config.itemsProcessed = nbt.contains("Config_ItemsProcessed") ? nbt.getInt("Config_ItemsProcessed") : this.processedCount;
            config.index = nbt.contains("Config_Index") ? nbt.getInt("Config_Index") : 0;
        } else {
            // Brand new probe - create default config
            this.config = new ProcessProbeConfig(this.pos, this.machineType);
        }
    }
    *///?}

    public void onRemoved() {
        if (world != null && !world.isClient() && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                controller.unregisterProcessProbe(pos);
            }
        }

        this.controllerPos = null;
        this.targetMachinePos = null;
        recipeCache.clear();
        knownFuels.clear();
        knownNonFuels.clear();
        experienceCache.clear();
    }

    /**
     * Check if an item matches the recipe filter settings
     */
    private boolean matchesRecipeFilter(ItemVariant variant, RecipeFilterMode filter) {
        ItemStack stack = variant.toStack(1);

        switch (filter) {
            case ALL_SMELTABLE:
                return true; // Already checked by canSmelt()

            case ORES_ONLY:
                String id = Registries.ITEM.getId(variant.getItem()).getPath();
                return id.contains("ore") || id.contains("raw_");

            case FOOD_ONLY:
                return stack.getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD);

            case RAW_METALS_ONLY:
                String itemId = Registries.ITEM.getId(variant.getItem()).getPath();
                return itemId.startsWith("raw_");

            case NO_WOOD:
                String woodId = Registries.ITEM.getId(variant.getItem()).getPath();
                return !woodId.contains("log") && !woodId.contains("wood") && !woodId.contains("plank");

            case CUSTOM:
                // TODO: Check custom whitelist
                return false;

            default:
                return true;
        }
    }

    /**
     * Check if a fuel matches the fuel filter settings
     */
    private boolean matchesFuelFilter(ItemVariant variant, FuelFilterMode filter) {
        ItemStack stack = variant.toStack(1);
        Item item = variant.getItem();

        switch (filter) {
            case ANY_FUEL:
                return true; // Already checked by isFuel()

            case COAL_ONLY:
                return item == Items.COAL || item == Items.CHARCOAL;

            case BLOCKS_ONLY:
                return item == Items.COAL_BLOCK
                        || item == Items.DRIED_KELP_BLOCK
                        || item == Items.BLAZE_ROD;

            case NO_WOOD:
                String id = Registries.ITEM.getId(item).getPath();
                return !id.contains("log") && !id.contains("wood") && !id.contains("plank");

            case LAVA_ONLY:
                return item == Items.LAVA_BUCKET;

            case CUSTOM:
                // TODO: Check custom whitelist
                return false;

            default:
                return true;
        }
    }
}