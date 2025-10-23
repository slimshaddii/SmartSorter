package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;

import java.util.ArrayList;
import java.util.List;

public record CategorySyncPayload(List<CategoryData> categories) implements CustomPayload {
    public static final Id<CategorySyncPayload> ID =
            new Id<>(Identifier.of(SmartSorter.MOD_ID, "category_sync"));

    public static final PacketCodec<RegistryByteBuf, CategorySyncPayload> CODEC =
            PacketCodec.of(CategorySyncPayload::write, CategorySyncPayload::read);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private void write(RegistryByteBuf buf) {
        buf.writeVarInt(categories.size());
        for (CategoryData cat : categories) {
            buf.writeString(cat.id());
            buf.writeString(cat.displayName());
            buf.writeString(cat.shortName());
            buf.writeVarInt(cat.order());
        }
    }

    private static CategorySyncPayload read(RegistryByteBuf buf) {
        int count = buf.readVarInt();
        List<CategoryData> categories = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = buf.readString();
            String displayName = buf.readString();
            String shortName = buf.readString();
            int order = buf.readVarInt();
            categories.add(new CategoryData(id, displayName, shortName, order));
        }
        return new CategorySyncPayload(categories);
    }

    public record CategoryData(String id, String displayName, String shortName, int order) {}
}