package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;

public record FilterCategoryChangePayload(String categoryId) implements CustomPayload {
    public static final CustomPayload.Id<FilterCategoryChangePayload> ID =
            new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "filter_category_change"));

    public static final PacketCodec<RegistryByteBuf, FilterCategoryChangePayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> buf.writeString(value.categoryId()),
                    buf -> new FilterCategoryChangePayload(buf.readString())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public Category getCategory() {
        return CategoryManager.getInstance().getCategory(categoryId);
    }
}