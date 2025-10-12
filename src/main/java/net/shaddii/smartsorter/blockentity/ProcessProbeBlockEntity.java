package net.shaddii.smartsorter.blockentity;

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
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.ProcessProbeBlock;
import net.shaddii.smartsorter.util.ControllerLinkable;

import net.shaddii.smartsorter.util.FuelFilterMode;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.RecipeFilterMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ProcessProbeBlockEntity extends BlockEntity implements ControllerLinkable {
    private static final Logger LOGGER = LoggerFactory.getLogger("smartsorter");

    // Core properties
    private BlockPos controllerPos;
    private Direction facing;
    private boolean enabled = false; // OFF by default
    private int processedCount = 0;
    private String machineType = "None";
    private BlockPos targetMachinePos;

    // Experience storage
    private int storedExperience = 0;

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

    // Store the probe's own configuration
    private ProcessProbeConfig config;
    private boolean hasBeenConfigured = false;


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

    public boolean hasBeenConfigured() {
        return hasBeenConfigured;
    }

    public static void tick(World world, BlockPos pos, BlockState state, ProcessProbeBlockEntity be) {
        if (world == null || world.isClient()) return;

        be.tickCounter++;

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

        // Explicitly check below (DOWN direction)
        BlockPos below = pos.down();
        if (world.getEmittedRedstonePower(below, Direction.UP) > 0) {
            return true;
        }

        // Check all other sides
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = world.getBlockState(neighborPos);

            // Check if neighbor is emitting redstone
            if (world.getEmittedRedstonePower(neighborPos, dir) > 0) {
                return true;
            }

            // Also check if the block itself is receiving power
            if (neighborState.emitsRedstonePower() && world.getReceivedRedstonePower(neighborPos) > 0) {
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

        // Find nearest controller
        BlockPos nearestController = findNearestController(serverWorld, 32);

        if (nearestController != null) {
            BlockEntity be = world.getBlockEntity(nearestController);
            if (be instanceof StorageControllerBlockEntity controller) {
                boolean success = controller.registerProcessProbe(pos, machineType);

                if (success) {
                    this.controllerPos = nearestController;
                    isLinked = true;
                    markDirty();

                    // Better message: "Linked to Furnace - Link Active"
                    String message = String.format("Linked to %s - Link Active", machineType);
                    notifyPlayers(serverWorld, message, true);
                    LOGGER.info("Process Probe at {} linked to {} at controller {}",
                            pos, machineType, nearestController);
                } else {
                    isLinked = false;
                    notifyPlayers(serverWorld, "Controller rejected link", false);
                }
            }
        } else {
            isLinked = false;
            notifyPlayers(serverWorld, "No Storage Controller found nearby", false);
        }
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
            LOGGER.info("Process Probe at {} disconnected", pos);
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

        // ✅ Update config's machine type too
        if (config != null) {
            config.machineType = machineType;
        }
    }

    private BlockPos findNearestController(ServerWorld world, int radius) {
        BlockPos.Mutable searchPos = new BlockPos.Mutable();
        double closestDistSq = Double.MAX_VALUE;
        BlockPos closest = null;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    searchPos.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);

                    BlockEntity be = world.getBlockEntity(searchPos);
                    if (be instanceof StorageControllerBlockEntity) {
                        double distSq = pos.getSquaredDistance(searchPos);
                        if (distSq < closestDistSq) {
                            closestDistSq = distSq;
                            closest = searchPos.toImmutable();
                        }
                    }
                }
            }
        }

        return closest;
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

    // ... (keep all the processing methods from the optimized version)
    // handleProcessTick, processFurnaceOptimized, canSmelt, isFuel, etc.
    // I'll include them below but they're the same as before

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

        // ✅ GET THE CONFIG
        ProcessProbeConfig config = controller.getProbeConfig(pos);
        if (config == null) {
            return; // No config = don't process
        }

        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            processFurnaceOptimized(world, furnace, controller, slots, config); // ✅ Pass config
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
                            matchesRecipeFilter(variant, config.recipeFilter)) { // ✅ Check filter
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
                            matchesFuelFilter(variant, config.fuelFilter)) { // ✅ Check filter
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
        ItemStack stack = variant.toStack(1);
        boolean isFuel = world.getFuelRegistry().isFuel(stack);

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
                // Collect XP BEFORE extracting output
                if (inventory instanceof AbstractFurnaceBlockEntity furnace) {
                    collectFurnaceExperience(world, furnace, controller); // ✅ Fixed - added controller
                }

                ItemStack toInsert = output.copy();
                ItemStack remaining = controller.insertItem(toInsert);

                if (remaining.isEmpty()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                    inventory.markDirty();
                    processedCount += toInsert.getCount();
                    markDirty();
                } else {
                    allExtracted = false;
                }
            }
        }

        return allExtracted;
    }

    private void collectFurnaceExperience(ServerWorld world, AbstractFurnaceBlockEntity furnace,
                                          StorageControllerBlockEntity controller) {
        // Don't call getRecipesUsedAndDropExperience - that spawns orbs!
        // Instead, we need to calculate XP manually and add to controller

        // For now, add a fixed amount per item (we'll improve this later)
        // TODO: Calculate actual XP from recipes
        int xpAmount = 10; // 1 XP per smelted item
        controller.addExperience(xpAmount);
        LOGGER.info("Added {} XP to controller. Total now: {}", xpAmount, controller.getStoredExperience());
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

    private void cleanCache() {
        recipeCache.entrySet().removeIf(entry -> !entry.getValue().isValid());

        if (knownFuels.size() > 100) {
            knownFuels.clear();
        }
        if (knownNonFuels.size() > 500) {
            knownNonFuels.clear();
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

        //Load the configured flag
        this.hasBeenConfigured = view.getBoolean("HasBeenConfigured", false);

        //Conditional loading based on configured state
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

            LOGGER.info("Loaded EXISTING config from probe at {}", pos);
        } else {
            // Brand new probe - create default config
            this.config = new ProcessProbeConfig(this.pos, this.machineType);
            LOGGER.info("Created DEFAULT config for new probe");
        }
    }

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