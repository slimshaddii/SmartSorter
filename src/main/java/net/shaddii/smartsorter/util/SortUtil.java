package net.shaddii.smartsorter.util;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SortUtil {
    private SortUtil() {}
    private static final Map<Item, Set<TagKey<Item>>> TAG_CACHE = new HashMap<>();
    private static final int MAX_TAG_CACHE_SIZE = 1000;

    public static boolean accepts(Storage<ItemVariant> target, ItemVariant incoming,
                                  boolean ignoreComponents, boolean useTags, boolean requireAllTags) {
        if (acceptsByContents(target, incoming, ignoreComponents)) return true;
        if (useTags) return acceptsByTags(target, incoming, requireAllTags);
        return false;
    }

    static boolean acceptsByContents(Storage<ItemVariant> target, ItemVariant incoming, boolean ignoreComponents) {
        for (StorageView<ItemVariant> view : target) {
            if (view.getAmount() == 0) continue;
            ItemVariant present = view.getResource();
            if (present.isBlank()) continue;

            if (ignoreComponents) {
                if (present.getItem() == incoming.getItem()) return true;
            } else {
                if (present.equals(incoming)) return true;
            }
        }
        return false;
    }

    static boolean acceptsByTags(Storage<ItemVariant> target, ItemVariant incoming, boolean requireAll) {
        Set<TagKey<Item>> inc = tagsOf(incoming.getItem());
        if (inc.isEmpty()) return false;

        Set<TagKey<Item>> chest = new HashSet<>();
        for (StorageView<ItemVariant> view : target) {
            if (view.getAmount() == 0) continue;
            ItemVariant present = view.getResource();
            if (present.isBlank()) continue;
            chest.addAll(tagsOf(present.getItem()));
        }
        if (chest.isEmpty()) return false;

        return requireAll
                ? inc.stream().allMatch(chest::contains)
                : inc.stream().anyMatch(chest::contains);
    }

    /**
     * Check tags against a specific inventory (not a Storage)
     */
    public static boolean acceptsByInventoryTags(Inventory inv, ItemVariant incoming, boolean requireAll) {
        Set<TagKey<Item>> incomingTags = tagsOf(incoming.getItem());
        if (incomingTags.isEmpty()) return false;

        Set<TagKey<Item>> chestTags = new HashSet<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            chestTags.addAll(tagsOf(stack.getItem()));
        }

        if (chestTags.isEmpty()) return false;

        if (requireAll) {
            return chestTags.containsAll(incomingTags);
        } else {
            for (TagKey<Item> tag : incomingTags) {
                if (chestTags.contains(tag)) return true;
            }
            return false;
        }
    }

    public static void clearTagCache() {
        if (TAG_CACHE.size() > MAX_TAG_CACHE_SIZE) {
            TAG_CACHE.clear();
        }
    }

    static Set<TagKey<Item>> tagsOf(Item item) {
        return TAG_CACHE.computeIfAbsent(item, i -> {
            RegistryEntry<Item> entry = Registries.ITEM.getEntry(i);
            Set<TagKey<Item>> tags = new HashSet<>();
            entry.streamTags().forEach(tags::add);
            return tags;
        });
    }
}