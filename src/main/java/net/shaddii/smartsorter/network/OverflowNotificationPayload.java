package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;

import java.util.Map;

// The "record" keyword was missing, causing the "unnamed class" error.
public record OverflowNotificationPayload(Map<ItemVariant, Long> overflowedItems) implements CustomPayload {

    public static final CustomPayload.Id<OverflowNotificationPayload> ID = new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "overflow_notification"));

    // This is the robust codec definition that works across all your target versions.
    public static final PacketCodec<RegistryByteBuf, OverflowNotificationPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                // Create a new HashMap from the payload's map to satisfy the encoder's type requirement.
                java.util.HashMap<ItemVariant, Long> mapToEncode = new java.util.HashMap<>(payload.overflowedItems());
                PacketCodecs.map(java.util.HashMap::new, ItemVariant.PACKET_CODEC, PacketCodecs.VAR_LONG)
                        .encode(buf, mapToEncode);
            },
            buf -> {
                // The decoder's logic is fine as it already returns a map that can be used.
                Map<ItemVariant, Long> map = PacketCodecs.map(java.util.HashMap::new, ItemVariant.PACKET_CODEC, PacketCodecs.VAR_LONG)
                        .decode(buf);
                return new OverflowNotificationPayload(map);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}