package net.shaddii.smartsorter.util;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;

import java.util.HashSet;
import java.util.Set;

public final class SortUtil {
    private SortUtil() {}

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

    static Set<TagKey<Item>> tagsOf(Item item) {
        RegistryEntry<Item> entry = Registries.ITEM.getEntry(item);
        Set<TagKey<Item>> out = new HashSet<>();
        entry.streamTags().forEach(out::add);
        return out;
    }
}