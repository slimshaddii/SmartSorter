package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.SortMode;

/**
 * Client -> Server packet to change sort mode in Storage Controller.
 */
public record SortModeChangePayload(String sortMode) implements CustomPayload {

    public static final CustomPayload.Id<SortModeChangePayload> ID =
            new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "sort_mode_change"));

    public static final PacketCodec<RegistryByteBuf, SortModeChangePayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING, SortModeChangePayload::sortMode,
                    SortModeChangePayload::new
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public SortMode getSortMode() {
        return SortMode.fromString(sortMode);
    }
}