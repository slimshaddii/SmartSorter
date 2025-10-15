package net.shaddii.smartsorter.util;

//? if = 1.21.1 {
/*import com.google.gson.GsonBuilder;
*///?}
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
//? if < 1.21.9 {
/*import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
*///?}
import net.minecraft.item.Item;
import net.minecraft.resource.JsonDataLoader;
//? if >= 1.21.8 {
import net.minecraft.resource.ResourceFinder;
//?}
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

//? if >= 1.21.9 {

public class CategoryManager extends JsonDataLoader<JsonElement> {
//?} elif = 1.21.8 {

/*public class CategoryManager extends JsonDataLoader<JsonElement> implements IdentifiableResourceReloadListener {
    *///?} else {

/*public class CategoryManager extends JsonDataLoader implements IdentifiableResourceReloadListener {
    *///?}
    private static final Logger LOGGER = LoggerFactory.getLogger("smartsorter");
    private static CategoryManager INSTANCE;

    //? if <= 1.21.8 {
    /*private static final Identifier ID = Identifier.of("smartsorter", "category_manager");
    *///?}

    private final List<Category> sortedCategories = new ArrayList<>();
    private final Map<Item, Category> cache = new HashMap<>();

    public CategoryManager() {
        //? if >= 1.21.8 {
        
        super(Codec.PASSTHROUGH.xmap(
                dynamic -> dynamic.convert(JsonOps.INSTANCE).getValue(),
                jsonElement -> new com.mojang.serialization.Dynamic<>(JsonOps.INSTANCE, jsonElement)
        ), ResourceFinder.json("smartsorter/categories"));
        //?} else {
        
        /*super(new GsonBuilder().setPrettyPrinting().create(), "smartsorter/categories");
        *///?}
    }

    public static CategoryManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CategoryManager();
        }
        return INSTANCE;
    }

    //? if <= 1.21.8 {
    /*@Override
    public Identifier getFabricId() {
        return ID;
    }
    *///?}

    @Override
    protected void apply(Map<Identifier, JsonElement> prepared, ResourceManager manager, Profiler profiler) {
        sortedCategories.clear();
        cache.clear();

        // Always add the special categories
        sortedCategories.add(Category.ALL);

        for (Map.Entry<Identifier, JsonElement> entry : prepared.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();

                Identifier id = Identifier.of(obj.get("id").getAsString());
                String displayName = obj.get("display_name").getAsString();
                String shortName = obj.has("short_name") ?
                        obj.get("short_name").getAsString() : displayName;
                int order = obj.get("order").getAsInt();

                Category category = new Category(id, displayName, shortName, order);

                if (obj.has("items")) {
                    obj.getAsJsonArray("items").forEach(element ->
                            category.addEntry(element.getAsString())
                    );
                }

                sortedCategories.add(category);
                LOGGER.info("Loaded category: {} ({})", displayName, id);
            } catch (Exception e) {
                LOGGER.error("Failed to load category from {}: {}", entry.getKey(), e.getMessage());
            }
        }

        // Always add MISC at the end
        sortedCategories.add(Category.MISC);

        // Sort by order
        Collections.sort(sortedCategories);

        LOGGER.info("Loaded {} categories", sortedCategories.size());
    }

     // Categorize an item
    public Category categorize(Item item) {
        return cache.computeIfAbsent(item, i -> {
            // Check each category in order
            for (Category category : sortedCategories) {
                if (category == Category.ALL || category == Category.MISC) {
                    continue; // Skip special categories
                }
                if (category.matches(i)) {
                    return category;
                }
            }
            // Fallback to MISC
            return Category.MISC;
        });
    }

     // Get all loaded categories
    public List<Category> getAllCategories() {
        return new ArrayList<>(sortedCategories);
    }

    // Get category by ID
    public Category getCategory(Identifier id) {
        return sortedCategories.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst()
                .orElse(Category.MISC);
    }

    // Get category by string ID
    public Category getCategory(String idString) {
        Identifier id = Identifier.tryParse(idString);
        return id != null ? getCategory(id) : Category.MISC;
    }
}