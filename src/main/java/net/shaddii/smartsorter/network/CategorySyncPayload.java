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

            // Write item patterns
            buf.writeVarInt(cat.items().size());
            for (String item : cat.items()) {
                buf.writeString(item);
            }
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

            // Read item patterns
            int itemCount = buf.readVarInt();
            List<String> items = new ArrayList<>();
            for (int j = 0; j < itemCount; j++) {
                items.add(buf.readString());
            }

            categories.add(new CategoryData(id, displayName, shortName, order, items));
        }
        return new CategorySyncPayload(categories);
    }

    public record CategoryData(String id, String displayName, String shortName, int order, List<String> items) {}
}