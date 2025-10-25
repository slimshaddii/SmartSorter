package net.shaddii.smartsorter.blockentity.processor;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.RecipeType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.util.ProcessProbeConfig;

import java.util.Map;

/**
 * Handles all smelting machine processing (furnace, blast furnace, smoker).
 * Optimized for batch operations and minimal controller queries.
 */
public class SmeltingProcessor {
    private static final int MAX_FUEL_PER_INSERT = 16;
    private static final int MAX_INPUT_PER_INSERT = 4;

    private final RecipeValidator recipeValidator;
    private final ExperienceCollector xpCollector;

    // Stats
    private int itemsProcessed = 0;

    public SmeltingProcessor(RecipeValidator validator, ExperienceCollector collector) {
        this.recipeValidator = validator;
        this.xpCollector = collector;
    }

    /**
     * Main processing method for all smelting machines (furnace, blast furnace, smoker).
     */
    public void processSmeltingMachine(ServerWorld world, BlockPos probePos,
                                       AbstractFurnaceBlockEntity machine,
                                       StorageControllerBlockEntity controller,
                                       ProcessProbeConfig config) {

        // First extract outputs
        boolean outputsHandled = extractOutputs(world, probePos, machine, controller);
        if (!outputsHandled) return;

        // Check what the smelting machine needs
        SmeltingNeeds needs = analyzeSmeltingNeeds(machine);
        if (!needs.needsInput && !needs.needsFuel) return;

        // Get network items once
        Map<ItemVariant, Long> networkItems = controller.getNetworkItems();
        RecipeType<?> recipeType = recipeValidator.getRecipeType(machine);

        // Process needed items
        if (needs.needsInput) {
            supplyInput(world, machine, controller, networkItems,
                    needs, recipeType, config);
        }

        if (needs.needsFuel) {
            supplyFuel(world, machine, controller, networkItems,
                    needs, config);
        }
    }

    /**
     * Extracts outputs from smelting machine to network.
     */
    private boolean extractOutputs(ServerWorld world, BlockPos probePos,
                                   Inventory inventory,
                                   StorageControllerBlockEntity controller) {
        boolean allExtracted = true;
        int outputSlot = 2; // Standard output slot for all vanilla smelting machines

        ItemStack output = inventory.getStack(outputSlot);
        if (!output.isEmpty()) {
            ItemStack toInsert = output.copy();
            ItemStack remaining = controller.insertItem(toInsert).remainder();

            if (remaining.isEmpty()) {
                // Collect XP from smelting
                if (inventory instanceof AbstractFurnaceBlockEntity smeltingMachine) {
                    int xp = xpCollector.collectFurnaceExperience(world, smeltingMachine, toInsert);
                    if (xp > 0) {
                        controller.addExperience(xp);
                    }
                }

                inventory.setStack(outputSlot, ItemStack.EMPTY);
                inventory.markDirty();
                itemsProcessed += toInsert.getCount();

                // Sync stats
                controller.syncProbeStatsToClients(probePos, itemsProcessed);
            } else {
                allExtracted = false;
            }
        }

        return allExtracted;
    }

    /**
     * Supplies input items to smelting machine.
     */
    private void supplyInput(ServerWorld world, AbstractFurnaceBlockEntity machine,
                             StorageControllerBlockEntity controller,
                             Map<ItemVariant, Long> networkItems,
                             SmeltingNeeds needs, RecipeType<?> recipeType,
                             ProcessProbeConfig config) {

        for (Map.Entry<ItemVariant, Long> entry : networkItems.entrySet()) {
            if (entry.getValue() <= 0) continue;

            ItemVariant variant = entry.getKey();

            // Check if existing input matches
            if (!needs.currentInput.isEmpty()) {
                if (!ItemVariant.of(needs.currentInput).equals(variant)) continue;
            } else {
                // Check if item can be smelted
                if (!recipeValidator.canSmelt(world, variant, machine, recipeType)) continue;
                if (!recipeValidator.matchesRecipeFilter(variant, config.recipeFilter)) continue;
            }

            int amount = (int) Math.min(needs.inputSpace, entry.getValue());
            ItemStack extracted = controller.extractItem(variant, amount);

            if (!extracted.isEmpty()) {
                ItemStack existing = machine.getStack(0);
                if (existing.isEmpty()) {
                    machine.setStack(0, extracted);
                } else {
                    existing.increment(extracted.getCount());
                }
                machine.markDirty();
                break;
            }
        }
    }

    /**
     * Supplies fuel to smelting machine.
     */
    private void supplyFuel(ServerWorld world, AbstractFurnaceBlockEntity machine,
                            StorageControllerBlockEntity controller,
                            Map<ItemVariant, Long> networkItems,
                            SmeltingNeeds needs, ProcessProbeConfig config) {

        for (Map.Entry<ItemVariant, Long> entry : networkItems.entrySet()) {
            if (entry.getValue() <= 0) continue;

            ItemVariant variant = entry.getKey();

            // Check if existing fuel matches
            if (!needs.currentFuel.isEmpty()) {
                if (!ItemVariant.of(needs.currentFuel).equals(variant)) continue;
            } else {
                // Check if item is fuel
                if (!recipeValidator.isFuel(world, variant)) continue;
                if (!recipeValidator.matchesFuelFilter(variant, config.fuelFilter)) continue;
            }

            int amount = (int) Math.min(needs.fuelSpace, entry.getValue());
            ItemStack extracted = controller.extractItem(variant, amount);

            if (!extracted.isEmpty()) {
                ItemStack existing = machine.getStack(1);
                if (existing.isEmpty()) {
                    machine.setStack(1, extracted);
                } else {
                    existing.increment(extracted.getCount());
                }
                machine.markDirty();
                break;
            }
        }
    }

    /**
     * Analyzes what a smelting machine needs.
     */
    private SmeltingNeeds analyzeSmeltingNeeds(AbstractFurnaceBlockEntity machine) {
        SmeltingNeeds needs = new SmeltingNeeds();

        // Check input slot (slot 0)
        ItemStack input = machine.getStack(0);
        if (input.isEmpty()) {
            needs.needsInput = true;
            needs.inputSpace = MAX_INPUT_PER_INSERT;
        } else if (input.getCount() < input.getMaxCount()) {
            needs.needsInput = true;
            needs.currentInput = input;
            needs.inputSpace = Math.min(
                    input.getMaxCount() - input.getCount(),
                    MAX_INPUT_PER_INSERT
            );
        }

        // Check fuel slot (slot 1)
        ItemStack fuel = machine.getStack(1);
        if (fuel.isEmpty()) {
            needs.needsFuel = true;
            needs.fuelSpace = MAX_FUEL_PER_INSERT;
        } else if (fuel.getCount() < fuel.getMaxCount()) {
            needs.needsFuel = true;
            needs.currentFuel = fuel;
            needs.fuelSpace = Math.min(
                    fuel.getMaxCount() - fuel.getCount(),
                    MAX_FUEL_PER_INSERT
            );
        }

        return needs;
    }

    /**
     * Gets the number of items processed.
     */
    public int getItemsProcessed() {
        return itemsProcessed;
    }

    /**
     * Sets the items processed count (for loading from NBT).
     */
    public void setItemsProcessed(int count) {
        this.itemsProcessed = count;
    }

    /**
     * Container for analyzing what a smelting machine needs.
     */
    private static class SmeltingNeeds {
        boolean needsInput = false;
        boolean needsFuel = false;
        int inputSpace = 0;
        int fuelSpace = 0;
        ItemStack currentInput = ItemStack.EMPTY;
        ItemStack currentFuel = ItemStack.EMPTY;
    }
}