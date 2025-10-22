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

import java.util.*;

public final class SortUtil {
    private SortUtil() {}
    private static final Map<Item, CacheEntry> TAG_CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static final int MAX_TAG_CACHE_SIZE = 1000;

    public static boolean accepts(Storage<ItemVariant> target, ItemVariant incoming,
                                  boolean ignoreComponents, boolean useTags, boolean requireAllTags) {
        if (acceptsByContents(target, incoming, ignoreComponents)) return true;
        if (useTags) return acceptsByTags(target, incoming, requireAllTags);
        return false;
    }

    private static class CacheEntry {
        final Set<TagKey<Item>> tags;
        long lastAccess;

        CacheEntry(Set<TagKey<Item>> tags) {
            this.tags = tags;
            this.lastAccess = System.currentTimeMillis();
        }

        void updateAccess() {
            this.lastAccess = System.currentTimeMillis();
        }
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
        if (TAG_CACHE.size() <= MAX_TAG_CACHE_SIZE) return;

        // Remove 25% oldest entries (LinkedHashMap is already ordered by access)
        int toRemove = MAX_TAG_CACHE_SIZE / 4;
        var iterator = TAG_CACHE.entrySet().iterator();
        while (iterator.hasNext() && toRemove > 0) {
            iterator.next();
            iterator.remove();
            toRemove--;
        }
    }

    static Set<TagKey<Item>> tagsOf(Item item) {
        CacheEntry cached = TAG_CACHE.get(item);

        if (cached != null) {
            cached.updateAccess();
            return cached.tags;
        }

        // Cache miss - compute tags
        RegistryEntry<Item> entry = Registries.ITEM.getEntry(item);
        Set<TagKey<Item>> tags = new HashSet<>();
        entry.streamTags().forEach(tags::add);

        TAG_CACHE.put(item, new CacheEntry(tags));
        return tags;
    }
}