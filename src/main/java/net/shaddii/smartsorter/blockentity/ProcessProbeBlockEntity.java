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
import net.minecraft.recipe.*;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
//? if >=1.21.8 {
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?} else {
/*import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
*///?}
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
import net.shaddii.smartsorter.util.*;

import java.util.*;

public class ProcessProbeBlockEntity extends BlockEntity implements ControllerLinkable {
    // ========================================
    // CONSTANTS
    // ========================================

    private static final int TICK_INTERVAL = 10;
    private static final int MAX_FUEL_PER_INSERT = 16;
    private static final int MAX_INPUT_PER_INSERT = 4;
    private static final int RECIPE_CACHE_MAX = 100;
    private static final int FUEL_CACHE_MAX = 50;
    private static final int NON_FUEL_CACHE_MAX = 100;
    private static final int EXPERIENCE_CACHE_MAX = 200;
    private static final long CACHE_ENTRY_MAX_AGE_MS = 60000;

    // ========================================
    // INNER CLASSES
    // ========================================

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

    // ========================================
    // FIELDS
    // ========================================

    // Core properties
    private BlockPos controllerPos;
    private Direction facing;
    private boolean enabled = false;
    private int processedCount = 0;
    private String machineType = "None";
    private BlockPos targetMachinePos;

    // Experience
    private int storedExperience = 0;

    // State tracking
    private boolean wasRedstonePowered = false;
    private boolean isLinked = false;

    // Configuration
    private ProcessProbeConfig config;
    private boolean hasBeenConfigured = false;

    // Performance
    private int tickCounter = 0;

    // Caching
    private final Map<ItemVariant, CachedRecipeCheck> recipeCache = new HashMap<>();
    private final Set<ItemVariant> knownFuels = new HashSet<>();
    private final Set<ItemVariant> knownNonFuels = new HashSet<>();
    private final Map<ItemVariant, Float> experienceCache = new HashMap<>();

    private BlockPos cachedControllerPos = null;
    private long lastControllerValidation = 0;
    private static final long CONTROLLER_CACHE_DURATION = 200L;
    private static final int SEARCH_RADIUS_NEAR = 8;
    private static final int SEARCH_RADIUS_MID = 16;
    private static final int SEARCH_RADIUS_MID_FAR = 32;
    private static final int SEARCH_RADIUS_FAR = 64;
    private static final int SEARCH_RADIUS_MAX = 128;

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
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(World world, BlockPos pos, BlockState state, ProcessProbeBlockEntity be) {
        if (world == null || world.isClient()) return;

        be.tickCounter++;

        // Check controller config
        if (be.controllerPos != null) {
            BlockEntity controllerBE = world.getBlockEntity(be.controllerPos);
            if (controllerBE instanceof StorageControllerBlockEntity controller) {
                ProcessProbeConfig config = controller.getProbeConfig(pos);
                if (config != null && !config.enabled) {
                    return;
                }
            }
        }

        // Check redstone state
        boolean powered = isReceivingRedstone(world, pos);

        // Redstone state change detection
        if (powered != be.wasRedstonePowered) {
            if (powered) {
                be.attemptLink(world, state);
            } else {
                be.disconnect(world);
            }
            be.wasRedstonePowered = powered;
        }

        be.setEnabled(powered);

        if (be.tickCounter < TICK_INTERVAL) return;
        be.tickCounter = 0;

        if (!be.enabled || !be.isLinked) return;
        if (!(world instanceof ServerWorld serverWorld)) return;

        if (be.controllerPos == null) return;
        BlockEntity controllerBE = world.getBlockEntity(be.controllerPos);
        if (!(controllerBE instanceof StorageControllerBlockEntity controller)) return;

        ProcessProbeConfig config = controller.getProbeConfig(pos);
        if (config != null && !config.enabled) {
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

    private static boolean isReceivingRedstone(World world, BlockPos pos) {
        if (world.isReceivingRedstonePower(pos)) {
            return true;
        }

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.offset(dir);
            BlockState neighborState = world.getBlockState(neighborPos);

            if (neighborState.isOf(Blocks.REDSTONE_WIRE)) {
                int power = neighborState.get(net.minecraft.block.RedstoneWireBlock.POWER);
                if (power > 0) return true;
            }

            if (neighborState.isOf(Blocks.LEVER)) {
                if (neighborState.get(net.minecraft.block.LeverBlock.POWERED)) return true;
            }

            if (world.getEmittedRedstonePower(neighborPos, dir.getOpposite()) > 0) {
                return true;
            }
        }

        return false;
    }

    // ========================================
    // LINKING LOGIC
    // ========================================

    private void attemptLink(World world, BlockState state) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        try {
            facing = state.get(ProcessProbeBlock.FACING);
        } catch (Exception e) {
            facing = Direction.NORTH;
        }

        BlockPos machinePos = pos.offset(facing);
        BlockEntity machineEntity = world.getBlockEntity(machinePos);
        BlockState machineState = world.getBlockState(machinePos);

        if (!isValidProcessingMachine(machineEntity, machineState)) {
            notifyPlayers(serverWorld, "No valid processing machine found", false);
            isLinked = false;
            return;
        }

        updateMachineType(machineState, machineEntity);
        targetMachinePos = machinePos;


        BlockPos controllerInNetwork = getCachedController(serverWorld);

        // If cache miss or invalid, search for controller
        if (controllerInNetwork == null) {
            controllerInNetwork = findController(serverWorld);

            if (controllerInNetwork != null) {
                // Cache the found controller
                cachedControllerPos = controllerInNetwork;
                lastControllerValidation = serverWorld.getTime();
            }
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
            cachedControllerPos = null; // Clear invalid cache
            notifyPlayers(serverWorld, "No Storage Controller found in redstone network", false);
        }
    }

    private void disconnect(World world) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        if (isLinked && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                controller.unregisterProcessProbe(pos);

                // Force sync to all players viewing the controller
                for (ServerPlayerEntity player : serverWorld.getPlayers()) {
                    if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                        if (handler.controller == controller) {
                            handler.sendNetworkUpdate(player);
                        }
                    }
                }
            }

            isLinked = false;
            notifyPlayers(serverWorld, "Link Inactive", false);
        }

        enabled = false;
    }

    private boolean isRedstoneComponent(World world, BlockPos pos, BlockState state) {
        return state.isOf(Blocks.REDSTONE_WIRE) ||
                state.isOf(Blocks.LEVER) ||
                state.isOf(Blocks.REDSTONE_TORCH) ||
                state.isOf(Blocks.REDSTONE_WALL_TORCH) ||
                state.isOf(Blocks.REDSTONE_BLOCK) ||
                state.isOf(Blocks.REPEATER) ||
                state.isOf(Blocks.COMPARATOR) ||
                state.emitsRedstonePower();
    }

    private boolean isValidProcessingMachine(BlockEntity entity, BlockState state) {
        if (!(entity instanceof AbstractFurnaceBlockEntity)) {
            return false;
        }

        return state.isOf(Blocks.FURNACE) ||
                state.isOf(Blocks.BLAST_FURNACE) ||
                state.isOf(Blocks.SMOKER);
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

        if (config != null) {
            config.machineType = machineType;
        }
    }

    private void notifyPlayers(ServerWorld world, String message, boolean success) {
        Text text = Text.literal(message).formatted(success ? Formatting.GREEN : Formatting.YELLOW);

        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) < 256) {
                player.sendMessage(text, true);
            }
        }
    }

    // ========================================
    // PROCESSING LOGIC
    // ========================================

    private void handleProcessTick(ServerWorld world, StorageControllerBlockEntity controller) {
        BlockPos machinePos = pos.offset(facing);
        BlockEntity blockEntity = world.getBlockEntity(machinePos);

        if (!(blockEntity instanceof Inventory inventory)) {
            return;
        }

        targetMachinePos = machinePos;
        SlotConfig slots = getSlotConfig(blockEntity);

        if (slots == null) return;

        ProcessProbeConfig config = controller.getProbeConfig(pos);
        if (config == null) return;

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
        if (!outputsHandled) return;

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

        if (!needsInput && !needsFuel) return;

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

            if (!needsInput && !needsFuel) break;
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

    private boolean extractOutputsOptimized(ServerWorld world, Inventory inventory,
                                            StorageControllerBlockEntity controller, SlotConfig slots) {
        boolean allExtracted = true;

        for (int slot : slots.getOutputSlots()) {
            ItemStack output = inventory.getStack(slot);
            if (!output.isEmpty()) {
                ItemStack toInsert = output.copy();
                ItemStack remaining = controller.insertItem(toInsert).remainder();

                if (remaining.isEmpty()) {
                    if (inventory instanceof AbstractFurnaceBlockEntity furnace) {
                        collectFurnaceExperience(world, furnace, controller, toInsert);
                    }

                    inventory.setStack(slot, ItemStack.EMPTY);
                    inventory.markDirty();
                    processedCount += toInsert.getCount();

                    if (config != null) {
                        config.itemsProcessed = processedCount;
                    }

                    markDirty();

                    controller.syncProbeStatsToClients(pos, processedCount);
                } else {
                    allExtracted = false;
                }
            }
        }

        return allExtracted;
    }

    // ========================================
    // RECIPE & FUEL CHECKING
    // ========================================

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
        if (knownFuels.contains(variant)) return true;
        if (knownNonFuels.contains(variant)) return false;

        //? if >= 1.21.8 {
        ItemStack stack = variant.toStack(1);
        boolean isFuel = world.getFuelRegistry().isFuel(stack);
        //?} else {
        /*ItemStack stack = variant.toStack(1);
        boolean isFuel = AbstractFurnaceBlockEntity.canUseAsFuel(stack);
        *///?}

        if (isFuel) {
            knownFuels.add(variant);
            return true;
        } else {
            knownNonFuels.add(variant);
            return false;
        }
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

    private boolean matchesRecipeFilter(ItemVariant variant, RecipeFilterMode filter) {
        ItemStack stack = variant.toStack(1);

        switch (filter) {
            case ALL_SMELTABLE:
                return true;

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
                return false;

            default:
                return true;
        }
    }

    private boolean matchesFuelFilter(ItemVariant variant, FuelFilterMode filter) {
        ItemStack stack = variant.toStack(1);
        Item item = variant.getItem();

        switch (filter) {
            case ANY_FUEL:
                return true;

            case COAL_ONLY:
                return item == Items.COAL || item == Items.CHARCOAL;

            case BLOCKS_ONLY:
                return item == Items.COAL_BLOCK ||
                        item == Items.DRIED_KELP_BLOCK ||
                        item == Items.BLAZE_ROD;

            case NO_WOOD:
                String id = Registries.ITEM.getId(item).getPath();
                return !id.contains("log") && !id.contains("wood") && !id.contains("plank");

            case LAVA_ONLY:
                return item == Items.LAVA_BUCKET;

            case CUSTOM:
                return false;

            default:
                return true;
        }
    }

    // ========================================
    // XP COLLECTION
    // ========================================

    private void collectFurnaceExperience(ServerWorld world, AbstractFurnaceBlockEntity furnace,
                                          StorageControllerBlockEntity controller, ItemStack outputStack) {
        RecipeType<?> recipeType = getRecipeTypeForFurnace(furnace);
        if (recipeType == null) return;

        ItemVariant outputVariant = ItemVariant.of(outputStack);
        Float experiencePerItem = experienceCache.get(outputVariant);

        if (experiencePerItem == null) {
            experiencePerItem = getExperienceForOutput(world, outputStack, recipeType);
            experienceCache.put(outputVariant, experiencePerItem);
        }

        int totalXP = Math.round(experiencePerItem * outputStack.getCount());

        if (totalXP > 0) {
            controller.addExperience(totalXP);
        }
    }

    private float getExperienceForOutput(ServerWorld world, ItemStack output, RecipeType<?> recipeType) {
        try {
            Collection<RecipeEntry<?>> allRecipes = world.getRecipeManager().values();

            for (RecipeEntry<?> recipeEntry : allRecipes) {
                Recipe<?> recipe = recipeEntry.value();

                if (recipe.getType() != recipeType) continue;

                if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
                    ItemStack recipeOutput = cookingRecipe.craft(
                            new SingleStackRecipeInput(ItemStack.EMPTY),
                            world.getRegistryManager()
                    );

                    if (ItemStack.areItemsEqual(recipeOutput, output)) {
                        return cookingRecipe.getExperience();
                    }
                }
            }
        } catch (Exception e) {
            return 0.1f;
        }

        return 0.1f;
    }

    // ========================================
    // CACHE MANAGEMENT
    // ========================================

    private void cleanCache() {
        recipeCache.entrySet().removeIf(entry -> !entry.getValue().isValid());

        if (recipeCache.size() > RECIPE_CACHE_MAX) {
            int toRemove = recipeCache.size() - (RECIPE_CACHE_MAX / 2);
            Iterator<Map.Entry<ItemVariant, CachedRecipeCheck>> it = recipeCache.entrySet().iterator();
            while (it.hasNext() && toRemove > 0) {
                it.next();
                it.remove();
                toRemove--;
            }
        }

        if (knownFuels.size() > FUEL_CACHE_MAX) {
            Set<ItemVariant> temp = new HashSet<>();
            int kept = 0;
            for (ItemVariant fuel : knownFuels) {
                if (kept++ >= FUEL_CACHE_MAX / 2) break;
                temp.add(fuel);
            }
            knownFuels.clear();
            knownFuels.addAll(temp);
        }

        if (knownNonFuels.size() > NON_FUEL_CACHE_MAX) {
            Set<ItemVariant> temp = new HashSet<>();
            int kept = 0;
            for (ItemVariant nonFuel : knownNonFuels) {
                if (kept++ >= NON_FUEL_CACHE_MAX / 2) break;
                temp.add(nonFuel);
            }
            knownNonFuels.clear();
            knownNonFuels.addAll(temp);
        }

        if (experienceCache.size() > EXPERIENCE_CACHE_MAX) {
            experienceCache.clear();
        }
    }

    private BlockPos getCachedController(ServerWorld world) {
        if (cachedControllerPos == null) {
            return null; // No cache
        }

        long currentTime = world.getTime();

        // Check if cache needs revalidation
        if (currentTime - lastControllerValidation > CONTROLLER_CACHE_DURATION) {
            // Validate cached position
            BlockEntity be = world.getBlockEntity(cachedControllerPos);

            if (!(be instanceof StorageControllerBlockEntity)) {
                // Cache invalid - controller no longer there
                cachedControllerPos = null;
                return null;
            }

            // Cache still valid, update validation time
            lastControllerValidation = currentTime;
        }

        return cachedControllerPos;
    }

    // ========================================
    // CONFIGURATION
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

    private BlockPos findController(ServerWorld world) {
        // Stage 1: Near search (8 blocks) - fastest, handles most cases
        BlockPos found = searchRadiusBFS(world, pos, SEARCH_RADIUS_NEAR);
        if (found != null) {
            return found;
        }

        // Stage 2: Mid-search (16 blocks) - handles medium distances
        found = searchRadiusBFS(world, pos, SEARCH_RADIUS_MID);
        if (found != null) {
            return found;
        }

        // Stage 3: Mid-Far search (32 blocks) - handles medium distances
        found = searchRadiusBFS(world, pos, SEARCH_RADIUS_MID_FAR);
        if (found != null) {
            return found;
        }

        // Stage 4: Far search (64 blocks) - handles medium distances
        found = searchRadiusBFS(world, pos, SEARCH_RADIUS_FAR);
        if (found != null) {
            return found;
        }

        // Stage 5: Max search (128 blocks) - handles edge cases
        found = searchRadiusBFS(world, pos, SEARCH_RADIUS_MAX);
        return found;
    }

    private BlockPos searchRadiusBFS(ServerWorld world, BlockPos start, int radius) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        // Start with probe position and immediate neighbors
        queue.add(start);
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = start.offset(dir);
            queue.add(adjacent);
        }

        int blocksChecked = 0;
        // Adjust max blocks based on radius to allow longer searches
        int maxBlocks = radius * radius * 4; // Less restrictive than radius^3
        BlockPos closestController = null;
        double closestDistance = Double.MAX_VALUE;

        while (!queue.isEmpty() && blocksChecked < maxBlocks) {
            BlockPos current = queue.poll();

            // Skip if already visited
            if (!visited.add(current)) {
                continue;
            }

            // Check Manhattan distance instead of Euclidean for redstone chains
            // This allows following long straight lines
            int manhattanDist = Math.abs(current.getX() - start.getX()) +
                    Math.abs(current.getY() - start.getY()) +
                    Math.abs(current.getZ() - start.getZ());

            if (manhattanDist > radius) {
                continue; // Out of range
            }

            blocksChecked++;

            // Check if this is a controller
            BlockEntity be = world.getBlockEntity(current);
            if (be instanceof StorageControllerBlockEntity) {
                double dist = start.getSquaredDistance(current);
                if (dist < closestDistance) {
                    closestDistance = dist;
                    closestController = current;
                }
                continue; // Found controller, but keep searching for closer ones
            }

            BlockState state = world.getBlockState(current);

            // Only expand through redstone components (MAJOR OPTIMIZATION)
            if (isRedstoneComponent(world, current, state)) {
                // For redstone wire and repeaters, prioritize the direction they're pointing
                if (state.getBlock() == net.minecraft.block.Blocks.REPEATER) {
                    // Repeaters have a facing direction - prioritize that direction
                    Direction facing = state.get(net.minecraft.block.RepeaterBlock.FACING);
                    BlockPos forward = current.offset(facing);
                    BlockPos backward = current.offset(facing.getOpposite());

                    if (!visited.contains(forward)) queue.add(forward);
                    if (!visited.contains(backward)) queue.add(backward);

                    // Also check sides (for T-junctions)
                    for (Direction side : Direction.Type.HORIZONTAL) {
                        if (side != facing && side != facing.getOpposite()) {
                            BlockPos sidePos = current.offset(side);
                            if (!visited.contains(sidePos)) {
                                queue.add(sidePos);
                            }
                        }
                    }
                } else {
                    // For other redstone components, check all adjacent blocks
                    for (Direction dir : Direction.values()) {
                        BlockPos neighbor = current.offset(dir);
                        if (!visited.contains(neighbor)) {
                            queue.add(neighbor);
                        }
                    }

                    // Check diagonal positions for complex redstone
                    if (state.getBlock() == net.minecraft.block.Blocks.REDSTONE_WIRE) {
                        for (Direction dir1 : Direction.Type.HORIZONTAL) {
                            for (Direction dir2 : new Direction[]{Direction.UP, Direction.DOWN}) {
                                BlockPos diagonal = current.offset(dir1).offset(dir2);
                                if (!visited.contains(diagonal)) {
                                    BlockState diagonalState = world.getBlockState(diagonal);
                                    if (isRedstoneComponent(world, diagonal, diagonalState)) {
                                        queue.add(diagonal);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return closestController;
    }

    public boolean addLinkedBlock(BlockPos controllerPos) {
        if (this.controllerPos == null || !this.controllerPos.equals(controllerPos)) {
            setController(controllerPos);
            return true;
        }
        return false;
    }

    public ProcessProbeConfig getConfig() {
        if (config == null) {
            config = new ProcessProbeConfig(this.pos, this.machineType);
        }
        config.position = this.pos;
        config.machineType = this.machineType;
        config.itemsProcessed = this.processedCount;
        return config;
    }

    public void setConfig(ProcessProbeConfig newConfig) {
        this.config = newConfig.copy();
        this.config.position = this.pos;

        this.processedCount = newConfig.itemsProcessed;
        this.hasBeenConfigured = true;

        markDirty();
    }

    public boolean hasBeenConfigured() {
        return hasBeenConfigured;
    }

    private void syncStatsToClients() {
        if (world instanceof ServerWorld serverWorld && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
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

    // ========================================
    // GETTERS & SETTERS
    // ========================================

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

    // ========================================
    // NBT SERIALIZATION
    // ========================================

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
        view.putBoolean("HasBeenConfigured", hasBeenConfigured);

        if (cachedControllerPos != null) {
            view.putLong("CachedControllerPos", cachedControllerPos.asLong());
            view.putLong("LastControllerValidation", lastControllerValidation);
        }

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
        this.hasBeenConfigured = view.getBoolean("HasBeenConfigured", false);

        view.getOptionalLong("CachedControllerPos").ifPresent(posLong -> {
            this.cachedControllerPos = BlockPos.fromLong(posLong);
            this.lastControllerValidation = view.getLong("LastControllerValidation", 0L);
        });

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
            config.itemsProcessed = view.getInt("Config_ItemsProcessed", this.processedCount);
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
        nbt.putInt("Processed", processedCount);
        nbt.putString("MachineType", machineType);
        nbt.putInt("StoredXP", storedExperience);
        nbt.putBoolean("WasRedstonePowered", wasRedstonePowered);
        nbt.putBoolean("IsLinked", isLinked);
        nbt.putBoolean("HasBeenConfigured", hasBeenConfigured);

        if (cachedControllerPos != null) {
        nbt.putLong("CachedControllerPos", cachedControllerPos.asLong());
        nbt.putLong("LastControllerValidation", lastControllerValidation);
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
        this.processedCount = nbt.getInt("Processed");
        this.machineType = nbt.contains("MachineType") ? nbt.getString("MachineType") : "None";
        this.storedExperience = nbt.getInt("StoredXP");
        this.wasRedstonePowered = nbt.getBoolean("WasRedstonePowered");
        this.isLinked = nbt.getBoolean("IsLinked");
        this.hasBeenConfigured = nbt.getBoolean("HasBeenConfigured");

         if (nbt.contains("CachedControllerPos")) {
        this.cachedControllerPos = BlockPos.fromLong(nbt.getLong("CachedControllerPos"));
        this.lastControllerValidation = nbt.getLong("LastControllerValidation");
        }

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
            config.itemsProcessed = nbt.contains("Config_ItemsProcessed") ? nbt.getInt("Config_ItemsProcessed") : this.processedCount;
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
        if (world != null && !world.isClient() && controllerPos != null) {
            BlockEntity be = world.getBlockEntity(controllerPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                controller.unregisterProcessProbe(pos);
            }
        }

        // Clear references
        this.controllerPos = null;
        this.targetMachinePos = null;
        this.cachedControllerPos = null; // NEW: Clear cache

        // Clear caches
        recipeCache.clear();
        knownFuels.clear();
        knownNonFuels.clear();
        experienceCache.clear();
    }

    public void clearController() {
        this.controllerPos = null;
        this.isLinked = false;
        this.cachedControllerPos = null; // NEW: Clear cache

        // Keep the config, processed count, and other settings
        // Just clear the controller link

        markDirty();

        if (world != null) {
            BlockState state = world.getBlockState(pos);
            world.updateListeners(pos, state, state, 3);
        }
    }
}