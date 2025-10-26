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

public record OverflowNotificationPayload(
        Map<ItemVariant, Long> overflowedItems,
        Map<ItemVariant, String> overflowDestinations
) implements CustomPayload {

    public static final CustomPayload.Id<OverflowNotificationPayload> ID =
            new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "overflow_notification"));

    public static final PacketCodec<RegistryByteBuf, OverflowNotificationPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(payload.overflowedItems.size());
                for (Map.Entry<ItemVariant, Long> entry : payload.overflowedItems.entrySet()) {
                    ItemStack stack = entry.getKey().toStack();
                    ItemStack.PACKET_CODEC.encode(buf, stack);
                    buf.writeLong(entry.getValue());
                    String destination = payload.overflowDestinations.getOrDefault(entry.getKey(), "Unknown");
                    buf.writeString(destination);
                }
            },
            buf -> {
                int size = buf.readInt();
                Map<ItemVariant, Long> items = new HashMap<>();
                Map<ItemVariant, String> destinations = new HashMap<>();

                for (int i = 0; i < size; i++) {
                    ItemStack stack = ItemStack.PACKET_CODEC.decode(buf);
                    ItemVariant variant = ItemVariant.of(stack);
                    long count = buf.readLong();
                    String destination = buf.readString();

                    items.put(variant, count);
                    destinations.put(variant, destination);
                }

                return new OverflowNotificationPayload(items, destinations);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}