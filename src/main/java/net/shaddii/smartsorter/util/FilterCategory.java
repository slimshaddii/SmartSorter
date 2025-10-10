package net.shaddii.smartsorter.util;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.StringIdentifiable;

/**
 * Categories for filtering items in the Storage Controller
 */
public enum FilterCategory implements StringIdentifiable {
    ALL("all", "All Items"),
    BUILDING("building", "Building Blocks"),
    ORES("ores", "Ores & Minerals"),
    FARMING("farming", "Farming"),
    MOB_DROPS("mob_drops", "Mob Drops"),
    REDSTONE("redstone", "Redstone"),
    TOOLS_ARMOR("tools_armor", "Tools & Armor"),
    MISC_RARE("misc_rare", "Misc / Rare"),
    NETHER_END("nether_end", "Nether / End"),
    WOOD("wood", "Wood Types"),
    WOOL_DYES("wool_dyes", "Wool / Dyes"),
    DECOR("decor", "Decor / Furniture");

    private final String name;
    private final String displayName;

    FilterCategory(String name, String displayName) {
        this.name = name;
        this.displayName = displayName;
    }

    @Override
    public String asString() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Short name for compact button display
     */
    public String getShortName() {
        return switch (this) {
            case ALL -> "All";
            case BUILDING -> "Building";
            case ORES -> "Ores";
            case FARMING -> "Farm";
            case MOB_DROPS -> "Mob";
            case REDSTONE -> "Redstone";
            case TOOLS_ARMOR -> "Tools";
            case MISC_RARE -> "Misc";
            case NETHER_END -> "Nether";
            case WOOD -> "Wood";
            case WOOL_DYES -> "Wool";
            case DECOR -> "Decor";
        };
    }

    public static FilterCategory fromString(String name) {
        for (FilterCategory category : values()) {
            if (category.name.equals(name)) {
                return category;
            }
        }
        return ALL;
    }

    /**
     * Get next category in the list (for cycling)
     */
    public FilterCategory next() {
        return values()[(this.ordinal() + 1) % values().length];
    }

    /**
     * Categorize an item
     */
    public static FilterCategory categorize(Item item) {
        // Wood types (check first - most specific)
        if (item.getDefaultStack().isIn(ItemTags.LOGS) ||
                item.getDefaultStack().isIn(ItemTags.PLANKS) ||
                item == Items.STICK || item == Items.CRAFTING_TABLE) {
            return WOOD;
        }

        // Wool & Dyes
        if (item.getDefaultStack().isIn(ItemTags.WOOL) ||
                item.getDefaultStack().isIn(ItemTags.WOOL_CARPETS) ||
                item == Items.WHITE_DYE || item == Items.ORANGE_DYE || item == Items.MAGENTA_DYE ||
                item == Items.LIGHT_BLUE_DYE || item == Items.YELLOW_DYE || item == Items.LIME_DYE ||
                item == Items.PINK_DYE || item == Items.GRAY_DYE || item == Items.LIGHT_GRAY_DYE ||
                item == Items.CYAN_DYE || item == Items.PURPLE_DYE || item == Items.BLUE_DYE ||
                item == Items.BROWN_DYE || item == Items.GREEN_DYE || item == Items.RED_DYE ||
                item == Items.BLACK_DYE) {
            return WOOL_DYES;
        }

        // Tools & Armor
        if (item.getDefaultStack().isIn(ItemTags.SWORDS) ||
                item.getDefaultStack().isIn(ItemTags.PICKAXES) ||
                item.getDefaultStack().isIn(ItemTags.AXES) ||
                item.getDefaultStack().isIn(ItemTags.SHOVELS) ||
                item.getDefaultStack().isIn(ItemTags.HOES) ||
                item.getDefaultStack().isIn(ItemTags.ARMOR_ENCHANTABLE)) {
            return TOOLS_ARMOR;
        }

        // Ores & Minerals
        if (item == Items.COAL || item == Items.COAL_ORE || item == Items.DEEPSLATE_COAL_ORE ||
                item == Items.IRON_INGOT || item == Items.IRON_ORE || item == Items.DEEPSLATE_IRON_ORE || item == Items.RAW_IRON ||
                item == Items.COPPER_INGOT || item == Items.COPPER_ORE || item == Items.DEEPSLATE_COPPER_ORE || item == Items.RAW_COPPER ||
                item == Items.GOLD_INGOT || item == Items.GOLD_ORE || item == Items.DEEPSLATE_GOLD_ORE || item == Items.RAW_GOLD ||
                item == Items.DIAMOND || item == Items.DIAMOND_ORE || item == Items.DEEPSLATE_DIAMOND_ORE ||
                item == Items.EMERALD || item == Items.EMERALD_ORE || item == Items.DEEPSLATE_EMERALD_ORE ||
                item == Items.LAPIS_LAZULI || item == Items.LAPIS_ORE || item == Items.DEEPSLATE_LAPIS_ORE ||
                item == Items.REDSTONE || item == Items.REDSTONE_ORE || item == Items.DEEPSLATE_REDSTONE_ORE ||
                item == Items.QUARTZ || item == Items.NETHER_QUARTZ_ORE ||
                item == Items.AMETHYST_SHARD) {
            return ORES;
        }

        // Redstone
        if (item == Items.REDSTONE || item == Items.REDSTONE_TORCH || item == Items.REDSTONE_BLOCK ||
                item == Items.REPEATER || item == Items.COMPARATOR || item == Items.OBSERVER ||
                item == Items.PISTON || item == Items.STICKY_PISTON || item == Items.DISPENSER ||
                item == Items.DROPPER || item == Items.HOPPER || item == Items.REDSTONE_LAMP ||
                item == Items.LEVER || item == Items.TRIPWIRE_HOOK || item == Items.TRAPPED_CHEST ||
                item.getDefaultStack().isIn(ItemTags.BUTTONS) ||
                item.getDefaultStack().isIn(ItemTags.WOODEN_PRESSURE_PLATES)) {
            return REDSTONE;
        }

        // Farming
        if (item == Items.WHEAT || item == Items.WHEAT_SEEDS || item == Items.BREAD ||
                item == Items.CARROT || item == Items.POTATO || item == Items.BEETROOT || item == Items.BEETROOT_SEEDS ||
                item == Items.MELON || item == Items.MELON_SLICE || item == Items.MELON_SEEDS ||
                item == Items.PUMPKIN || item == Items.PUMPKIN_SEEDS || item == Items.CARVED_PUMPKIN ||
                item == Items.SUGAR_CANE || item == Items.BAMBOO || item == Items.SWEET_BERRIES ||
                item == Items.COCOA_BEANS || item == Items.APPLE || item == Items.GOLDEN_APPLE ||
                item == Items.BONE_MEAL || item == Items.COMPOSTER) {
            return FARMING;
        }

        // Mob Drops
        if (item == Items.ROTTEN_FLESH || item == Items.BONE || item == Items.SPIDER_EYE ||
                item == Items.STRING || item == Items.GUNPOWDER || item == Items.SLIME_BALL ||
                item == Items.ENDER_PEARL || item == Items.BLAZE_ROD || item == Items.GHAST_TEAR ||
                item == Items.PHANTOM_MEMBRANE || item == Items.RABBIT_HIDE || item == Items.LEATHER ||
                item == Items.FEATHER || item == Items.EGG || item == Items.PORKCHOP || item == Items.BEEF ||
                item == Items.CHICKEN || item == Items.MUTTON || item == Items.RABBIT ||
                item == Items.COD || item == Items.SALMON || item == Items.TROPICAL_FISH || item == Items.PUFFERFISH) {
            return MOB_DROPS;
        }

        // Nether / End
        if (item == Items.NETHERRACK || item == Items.NETHER_BRICKS || item == Items.SOUL_SAND ||
                item == Items.SOUL_SOIL || item == Items.BASALT || item == Items.BLACKSTONE ||
                item == Items.GLOWSTONE || item == Items.SHROOMLIGHT || item == Items.CRIMSON_FUNGUS ||
                item == Items.WARPED_FUNGUS || item == Items.NETHER_WART || item == Items.MAGMA_BLOCK ||
                item == Items.END_STONE || item == Items.PURPUR_BLOCK || item == Items.CHORUS_FRUIT ||
                item == Items.SHULKER_SHELL || item == Items.ENDER_CHEST || item == Items.END_ROD ||
                item == Items.ANCIENT_DEBRIS || item == Items.NETHERITE_SCRAP || item == Items.NETHERITE_INGOT) {
            return NETHER_END;
        }

        // Decor / Furniture
        if (item == Items.TORCH || item == Items.LANTERN || item == Items.SOUL_LANTERN ||
                item == Items.PAINTING || item == Items.ITEM_FRAME || item == Items.GLOW_ITEM_FRAME ||
                item == Items.FLOWER_POT || item == Items.DECORATED_POT ||
                item.getDefaultStack().isIn(ItemTags.SMALL_FLOWERS) ||
                item.getDefaultStack().isIn(ItemTags.CANDLES) ||
                item.getDefaultStack().isIn(ItemTags.BEDS) ||
                item.getDefaultStack().isIn(ItemTags.BANNERS)) {
            return DECOR;
        }

        // Building Blocks (broader category - check after specific ones)
        if (item.getDefaultStack().isIn(ItemTags.STONE_CRAFTING_MATERIALS) ||
                item.getDefaultStack().isIn(ItemTags.SAND) ||
                item == Items.DIRT || item == Items.GRASS_BLOCK || item == Items.COBBLESTONE ||
                item == Items.STONE || item == Items.GRANITE || item == Items.DIORITE || item == Items.ANDESITE ||
                item == Items.GLASS || item == Items.BRICKS || item == Items.TERRACOTTA) {
            return BUILDING;
        }

        // Misc/Rare (everything else + special items)
        if (item == Items.NETHER_STAR || item == Items.DRAGON_EGG || item == Items.ELYTRA ||
                item == Items.TOTEM_OF_UNDYING || item == Items.ENCHANTED_GOLDEN_APPLE ||
                item == Items.BEACON || item == Items.CONDUIT || item == Items.HEART_OF_THE_SEA) {
            return MISC_RARE;
        }

        // Default fallback
        return MISC_RARE;
    }

    /**
     * Check if an item matches this category
     */
    public boolean matches(Item item) {
        if (this == ALL) return true;
        return categorize(item) == this;
    }
}