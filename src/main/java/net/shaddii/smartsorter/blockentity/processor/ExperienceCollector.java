package net.shaddii.smartsorter.blockentity.processor;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.*;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.server.world.ServerWorld;

import java.util.*;

/**
 * Collects and calculates experience from furnace outputs.
 * Caches XP values per item type.
 */
public class ExperienceCollector {
    private static final int XP_CACHE_MAX = 200;

    private final Map<ItemVariant, Float> experienceCache = new HashMap<>();

    /**
     * Collects experience from furnace output.
     */
    public int collectFurnaceExperience(ServerWorld world,
                                        AbstractFurnaceBlockEntity furnace,
                                        ItemStack outputStack) {

        RecipeType<?> recipeType = getRecipeType(furnace);
        if (recipeType == null) return 0;

        ItemVariant outputVariant = ItemVariant.of(outputStack);

        // Check cache
        Float experiencePerItem = experienceCache.get(outputVariant);

        if (experiencePerItem == null) {
            experiencePerItem = calculateExperience(world, outputStack, recipeType);
            experienceCache.put(outputVariant, experiencePerItem);

            // Maintain cache size
            if (experienceCache.size() > XP_CACHE_MAX) {
                trimCache();
            }
        }

        return Math.round(experiencePerItem * outputStack.getCount());
    }

    /**
     * Calculates experience for an output item.
     */
    private float calculateExperience(ServerWorld world, ItemStack output,
                                      RecipeType<?> recipeType) {
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
            return 0.1f; // Default XP
        }

        return 0.1f;
    }

    private RecipeType<?> getRecipeType(AbstractFurnaceBlockEntity furnace) {
        if (furnace instanceof net.minecraft.block.entity.FurnaceBlockEntity) {
            return RecipeType.SMELTING;
        }
        if (furnace instanceof net.minecraft.block.entity.BlastFurnaceBlockEntity) {
            return RecipeType.BLASTING;
        }
        if (furnace instanceof net.minecraft.block.entity.SmokerBlockEntity) {
            return RecipeType.SMOKING;
        }
        return null;
    }

    private void trimCache() {
        // Remove oldest half
        int toRemove = experienceCache.size() / 2;
        Iterator<Map.Entry<ItemVariant, Float>> it = experienceCache.entrySet().iterator();
        while (it.hasNext() && toRemove > 0) {
            it.next();
            it.remove();
            toRemove--;
        }
    }

    /**
     * Clears the experience cache.
     */
    public void clearCache() {
        experienceCache.clear();
    }
}