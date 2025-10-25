package net.shaddii.smartsorter.screen.util;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

//? if >=1.21.8 {
import net.minecraft.item.Item;
import net.minecraft.item.tooltip.TooltipType;
//?}

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TooltipRenderer {
    private static final int MAX_CACHE_SIZE = 100;

    private final TextRenderer textRenderer;
    private final MinecraftClient client;

    // LRU cache for multiple tooltips
    private final Map<TooltipKey, List<Text>> tooltipCache = new LinkedHashMap<TooltipKey, List<Text>>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<TooltipKey, List<Text>> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public TooltipRenderer(TextRenderer textRenderer) {
        this.textRenderer = textRenderer;
        this.client = MinecraftClient.getInstance();
    }

    public List<Text> createItemTooltip(ItemVariant variant, long amount) {
        TooltipKey key = new TooltipKey(variant, amount);

        // Check cache
        List<Text> cached = tooltipCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Generate tooltip (expensive operation)
        List<Text> tooltip = generateTooltip(variant, amount);

        // Cache it
        tooltipCache.put(key, tooltip);

        return tooltip;
    }

    private List<Text> generateTooltip(ItemVariant variant, long amount) {
        List<Text> tooltip = new ArrayList<>();
        ItemStack stack = variant.toStack();

        // Use version-specific code to get the full tooltip
        //? if >=1.21.8 {
        try {
            TooltipType tooltipType = client != null && client.options.advancedItemTooltips ?
                    TooltipType.ADVANCED : TooltipType.BASIC;

            List<Text> vanillaTooltip = stack.getTooltip(
                    Item.TooltipContext.DEFAULT,
                    client != null ? client.player : null,
                    tooltipType
            );
            tooltip.addAll(vanillaTooltip);
        } catch (Exception e) {
            tooltip.add(stack.getName());
        }
        //?} else {
        /*// For older versions, manually build tooltip
        tooltip.add(stack.getName());

        // Add enchantments manually
        try {
            var enchantmentsComponent = stack.getEnchantments();
            if (enchantmentsComponent != null && !enchantmentsComponent.isEmpty()) {
                for (var enchantment : enchantmentsComponent.getEnchantments()) {
                    try {
                        int level = enchantmentsComponent.getLevel(enchantment);
                        String enchantName = enchantment.value().description().getString();

                        String levelStr = level > 1 ? " " + toRoman(level) : "";
                        tooltip.add(Text.literal("§9" + enchantName + levelStr));
                    } catch (Exception e) {
                        // Skip this enchantment if parsing fails
                    }
                }
            }
        } catch (Exception e) {
            // Fallback if enchantment parsing fails
            if (stack.hasGlint()) {
                tooltip.add(Text.literal("§7Enchanted"));
            }
        }

        // Add durability info
        try {
            if (stack.isDamaged()) {
                int durability = stack.getMaxDamage() - stack.getDamage();
                int maxDurability = stack.getMaxDamage();
                tooltip.add(Text.literal("§7Durability: " + durability + " / " + maxDurability));
            }
        } catch (Exception e) {
            // Skip if durability check fails
        }
        *///?}

        // Add storage amount
        tooltip.add(Text.literal("")); // Empty line
        tooltip.add(Text.literal("§7Stored: §f" + String.format("%,d", amount)));

        // Add controls
        tooltip.add(Text.literal(""));
        tooltip.add(Text.literal("§8Left-Click: §7Take stack (64)"));
        tooltip.add(Text.literal("§8Right-Click: §7Take half (32)"));
        tooltip.add(Text.literal("§8Ctrl+Left: §7Take quarter (16)"));
        tooltip.add(Text.literal("§8Shift-Click: §7To inventory"));

        return tooltip;
    }

    public void invalidateCache() {
        tooltipCache.clear();
    }

    private String toRoman(int number) {
        if (number <= 0 || number > 10) return String.valueOf(number);

        String[] romans = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return romans[Math.min(number, 10)];
    }

    // Record for cache key with proper equals/hashCode
    private static final class TooltipKey {
        private final ItemVariant variant;
        private final long amount;
        private final int hashCode;

        TooltipKey(ItemVariant variant, long amount) {
            this.variant = variant;
            this.amount = amount;
            this.hashCode = Objects.hash(variant, amount);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TooltipKey)) return false;
            TooltipKey that = (TooltipKey) o;
            return amount == that.amount && variant.equals(that.variant);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}