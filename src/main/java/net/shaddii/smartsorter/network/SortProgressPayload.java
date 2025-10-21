package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;

import java.util.HashMap;
import java.util.Map;

public record SortProgressPayload(
        int current,
        int total,
        boolean isComplete,
        Map<ItemVariant, Long> overflowItems // null if not complete
) implements CustomPayload {

    public static final Id<SortProgressPayload> ID =
            new Id<>(Identifier.of(SmartSorter.MOD_ID, "sort_progress"));

    public static final PacketCodec<RegistryByteBuf, SortProgressPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(payload.current);
                buf.writeInt(payload.total);
                buf.writeBoolean(payload.isComplete);

                if (payload.isComplete && payload.overflowItems != null) {
                    buf.writeBoolean(true);
                    buf.writeInt(payload.overflowItems.size());
                    payload.overflowItems.forEach((variant, count) -> {
                        // Write the item stack (which includes item and components)
                        ItemStack stack = variant.toStack();
                        ItemStack.PACKET_CODEC.encode(buf, stack);
                        buf.writeLong(count);
                    });
                } else {
                    buf.writeBoolean(false);
                }
            },
            buf -> {
                int current = buf.readInt();
                int total = buf.readInt();
                boolean isComplete = buf.readBoolean();

                Map<ItemVariant, Long> overflow = null;
                if (buf.readBoolean()) {
                    int size = buf.readInt();
                    overflow = new HashMap<>();
                    for (int i = 0; i < size; i++) {
                        // Read the item stack and convert to variant
                        ItemStack stack = ItemStack.PACKET_CODEC.decode(buf);
                        ItemVariant variant = ItemVariant.of(stack);
                        long count = buf.readLong();
                        overflow.put(variant, count);
                    }
                }

                return new SortProgressPayload(current, total, isComplete, overflow);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}