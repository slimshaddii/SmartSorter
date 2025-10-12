package net.shaddii.smartsorter.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Set;

public class Category implements Comparable<Category> {
    private final Identifier id;
    private final String displayName;
    private final String shortName;
    private final int order;
    private final Set<TagKey<Item>> tags = new HashSet<>();
    private final Set<Identifier> items = new HashSet<>();

    // Special categories (always exist)
    public static final Category ALL = new Category(
            Identifier.of("smartsorter", "all"),
            "All Items",
            "All",
            -1
    );

    public static final Category MISC = new Category(
            Identifier.of("smartsorter", "misc"),
            "Misc / Rare",
            "Misc",
            9999
    );

    public Category(Identifier id, String displayName, String shortName, int order) {
        this.id = id;
        this.displayName = displayName;
        this.shortName = shortName;
        this.order = order;
    }

    /**
     * Add a tag or item ID to this category
     * Format: "#minecraft:logs" for tags, "minecraft:dirt" for items
     */
    public void addEntry(String entry) {
        if (entry.startsWith("#")) {
            // It's a tag
            String tagId = entry.substring(1);
            Identifier tagIdentifier = Identifier.tryParse(tagId);
            if (tagIdentifier != null) {
                this.tags.add(TagKey.of(RegistryKeys.ITEM, tagIdentifier));
            }
        } else {
            // It's an item ID
            Identifier itemId = Identifier.tryParse(entry);
            if (itemId != null) {
                this.items.add(itemId);
            }
        }
    }

    /**
     * Check if an item belongs to this category
     */
    public boolean matches(Item item) {
        // Check direct item match
        Identifier itemId = Registries.ITEM.getId(item);
        if (items.contains(itemId)) {
            return true;
        }

        // Check tag match
        ItemStack stack = item.getDefaultStack();
        for (TagKey<Item> tag : tags) {
            if (stack.isIn(tag)) {
                return true;
            }
        }

        return false;
    }

    // Getters
    public Identifier getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getShortName() { return shortName; }
    public int getOrder() { return order; }

    public String asString() {
        return id.toString();
    }

    @Override
    public int compareTo(Category other) {
        int orderCompare = Integer.compare(this.order, other.order);
        return orderCompare != 0 ? orderCompare : this.displayName.compareTo(other.displayName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Category other)) return false;
        return this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}