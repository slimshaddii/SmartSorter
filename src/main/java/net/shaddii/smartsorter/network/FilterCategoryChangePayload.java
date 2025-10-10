package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.FilterCategory;

public record FilterCategoryChangePayload(String category) implements CustomPayload {

    public static final CustomPayload.Id<FilterCategoryChangePayload> ID =
            new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "filter_category_change"));

    public static final PacketCodec<RegistryByteBuf, FilterCategoryChangePayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING, FilterCategoryChangePayload::category,
                    FilterCategoryChangePayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public FilterCategory getCategory() {
        return FilterCategory.fromString(category);
    }
}