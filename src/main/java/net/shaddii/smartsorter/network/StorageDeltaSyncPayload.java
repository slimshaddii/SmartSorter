package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;

import java.util.HashMap;
import java.util.Map;

public record StorageDeltaSyncPayload(
        Map<ItemVariant, Long> changedItems
) implements CustomPayload {

    public static final CustomPayload.Id<StorageDeltaSyncPayload> ID = new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "storage_delta_sync"));

    private static final PacketCodec<RegistryByteBuf, Map<ItemVariant, Long>> MAP_CODEC =
            PacketCodecs.map(HashMap::new, ItemVariant.PACKET_CODEC, PacketCodecs.VAR_LONG);

    public static final PacketCodec<RegistryByteBuf, StorageDeltaSyncPayload> CODEC =
            MAP_CODEC.xmap(StorageDeltaSyncPayload::new, StorageDeltaSyncPayload::changedItems);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}