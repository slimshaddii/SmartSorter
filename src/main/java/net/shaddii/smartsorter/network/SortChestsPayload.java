package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;

import java.util.ArrayList;
import java.util.List;

/**
 * A single payload to request sorting of one or more inventories.
 * The inventories are sorted by the server in the order they appear in the list.
 */
public record SortChestsPayload(List<BlockPos> sortedPositions) implements CustomPayload {

    public static final Id<SortChestsPayload> ID =
            new Id<>(Identifier.of(SmartSorter.MOD_ID, "sort_chests"));

    public static final PacketCodec<RegistryByteBuf, SortChestsPayload> CODEC = PacketCodec.of(
            // Encoder: Writes the list of BlockPos to the buffer
            (payload, buf) -> buf.writeCollection(payload.sortedPositions, BlockPos.PACKET_CODEC),
            // Decoder: Reads the list of BlockPos from the buffer
            buf -> new SortChestsPayload(buf.readCollection(ArrayList::new, BlockPos.PACKET_CODEC))
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}