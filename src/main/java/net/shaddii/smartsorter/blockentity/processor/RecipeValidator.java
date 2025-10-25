package net.shaddii.smartsorter.blockentity.processor;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.BlastingRecipe;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.recipe.SmokingRecipe;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.shaddii.smartsorter.util.FuelFilterMode;
import net.shaddii.smartsorter.util.RecipeFilterMode;

import java.util.*;

/**
 * Validates recipes and fuels with aggressive caching.
 * Optimized for repeated checks of the same items.
 */
public class RecipeValidator {
    private static final int RECIPE_CACHE_MAX = 100;
    private static final int FUEL_CACHE_MAX = 50;
    private static final long CACHE_VALIDITY_MS = 10000;

    private final Map<CacheKey, CachedRecipeCheck> recipeCache = new HashMap<>();
    private final Set<ItemVariant> knownFuels = new HashSet<>();
    private final Set<ItemVariant> knownNonFuels = new HashSet<>();

    private static class CacheKey {
        final ItemVariant variant;
        final String furnaceType;

        CacheKey(ItemVariant variant, String type) {
            this.variant = variant;
            this.furnaceType = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey key)) return false;
            return Objects.equals(variant, key.variant) &&
                    Objects.equals(furnaceType, key.furnaceType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(variant, furnaceType);
        }
    }

    private static class CachedRecipeCheck {
        final boolean canProcess;
        final long timestamp;

        CachedRecipeCheck(boolean canProcess) {
            this.canProcess = canProcess;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < CACHE_VALIDITY_MS;
        }
    }

    /**
     * Checks if an item can be smelted in the given furnace.
     * Results are cached for performance.
     */
    public boolean canSmelt(ServerWorld world, ItemVariant variant,
                            AbstractFurnaceBlockEntity furnace, RecipeType<?> recipeType) {
        if (recipeType == null) return false;

        String furnaceType = furnace.getClass().getSimpleName();
        CacheKey key = new CacheKey(variant, furnaceType);

        // Check cache
        CachedRecipeCheck cached = recipeCache.get(key);
        if (cached != null && cached.isValid()) {
            return cached.canProcess;
        }

        // Check recipe
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

        // Cache result
        recipeCache.put(key, new CachedRecipeCheck(canSmelt));

        // Maintain cache size
        if (recipeCache.size() > RECIPE_CACHE_MAX) {
            trimRecipeCache();
        }

        return canSmelt;
    }

    /**
     * Checks if an item is fuel.
     * Results are permanently cached until clear.
     */
    public boolean isFuel(ServerWorld world, ItemVariant variant) {
        if (knownFuels.contains(variant)) return true;
        if (knownNonFuels.contains(variant)) return false;

        ItemStack stack = variant.toStack(1);

        //? if >= 1.21.8 {
        boolean isFuel = world.getFuelRegistry().isFuel(stack);
        //?} else {
        /*boolean isFuel = AbstractFurnaceBlockEntity.canUseAsFuel(stack);
         *///?}

        (isFuel ? knownFuels : knownNonFuels).add(variant);

        // Maintain cache size
        if (knownFuels.size() > FUEL_CACHE_MAX) {
            trimFuelCache();
        }

        return isFuel;
    }

    /**
     * Checks if item matches recipe filter.
     */
    public boolean matchesRecipeFilter(ItemVariant variant, RecipeFilterMode filter) {
        ItemStack stack = variant.toStack(1);
        Item item = variant.getItem();
        String id = Registries.ITEM.getId(item).getPath();

        return switch (filter) {
            case ALL_SMELTABLE -> true;
            case ORES_ONLY -> id.contains("ore") || id.contains("raw_");
            case FOOD_ONLY -> stack.getComponents().contains(
                    net.minecraft.component.DataComponentTypes.FOOD);
            case RAW_METALS_ONLY -> id.startsWith("raw_");
            case NO_WOOD -> !id.contains("log") && !id.contains("wood") && !id.contains("plank");
            case CUSTOM -> false;
            default -> true;
        };
    }

    /**
     * Checks if item matches fuel filter.
     */
    public boolean matchesFuelFilter(ItemVariant variant, FuelFilterMode filter) {
        Item item = variant.getItem();
        String id = Registries.ITEM.getId(item).getPath();

        return switch (filter) {
            case ANY_FUEL -> true;
            case COAL_ONLY -> item == Items.COAL || item == Items.CHARCOAL;
            case BLOCKS_ONLY -> item == Items.COAL_BLOCK ||
                    item == Items.DRIED_KELP_BLOCK ||
                    item == Items.BLAZE_ROD;
            case NO_WOOD -> !id.contains("log") && !id.contains("wood") && !id.contains("plank");
            case LAVA_ONLY -> item == Items.LAVA_BUCKET;
            case CUSTOM -> false;
            default -> true;
        };
    }

    /**
     * Gets the recipe type for a furnace.
     */
    public RecipeType<?> getRecipeType(AbstractFurnaceBlockEntity furnace) {
        if (furnace instanceof FurnaceBlockEntity) return RecipeType.SMELTING;
        if (furnace instanceof BlastFurnaceBlockEntity) return RecipeType.BLASTING;
        if (furnace instanceof SmokerBlockEntity) return RecipeType.SMOKING;
        return null;
    }

    /**
     * Cleans expired entries from caches.
     */
    public void cleanCache() {
        recipeCache.entrySet().removeIf(entry -> !entry.getValue().isValid());
    }

    private void trimRecipeCache() {
        // Remove oldest half
        int toRemove = recipeCache.size() / 2;
        Iterator<Map.Entry<CacheKey, CachedRecipeCheck>> it = recipeCache.entrySet().iterator();
        while (it.hasNext() && toRemove > 0) {
            it.next();
            it.remove();
            toRemove--;
        }
    }

    private void trimFuelCache() {
        // Keep most recent half
        if (knownFuels.size() > FUEL_CACHE_MAX / 2) {
            Set<ItemVariant> temp = new HashSet<>();
            int kept = 0;
            for (ItemVariant fuel : knownFuels) {
                if (kept++ >= FUEL_CACHE_MAX / 2) break;
                temp.add(fuel);
            }
            knownFuels.clear();
            knownFuels.addAll(temp);
        }
    }

    /**
     * Clears all caches.
     */
    public void clearCache() {
        recipeCache.clear();
        knownFuels.clear();
        knownNonFuels.clear();
    }
}