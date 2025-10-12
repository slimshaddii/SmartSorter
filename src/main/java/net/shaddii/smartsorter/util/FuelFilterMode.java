package net.shaddii.smartsorter.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

public enum FuelFilterMode {
    ANY_FUEL("Any Fuel"),
    COAL_ONLY("Coal/Charcoal"),
    BLOCKS_ONLY("Fuel Blocks"),
    NO_WOOD("No Wood"),
    LAVA_ONLY("Lava Buckets"),
    CUSTOM("Custom");

    private final String displayName;

    FuelFilterMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean allows(ItemStack fuel) {
        return switch (this) {
            case ANY_FUEL -> true;
            case COAL_ONLY -> isCoal(fuel);
            case BLOCKS_ONLY -> isFuelBlock(fuel);
            case NO_WOOD -> !isWood(fuel);
            case LAVA_ONLY -> fuel.isOf(Items.LAVA_BUCKET);
            case CUSTOM -> false; // Will check custom whitelist separately
        };
    }

    private boolean isCoal(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.COAL || item == Items.CHARCOAL;
    }

    private boolean isFuelBlock(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.COAL_BLOCK
                || item == Items.DRIED_KELP_BLOCK
                || item == Items.BLAZE_ROD;
    }

    private boolean isWood(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).getPath();
        return id.contains("log") || id.contains("wood") || id.contains("plank");
    }

    public String asString() {
        return this.name();
    }

    public static FuelFilterMode fromString(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return COAL_ONLY; // Safe default
        }
    }
}