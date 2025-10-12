package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;

public record CollectXpPayload() implements CustomPayload {
    public static final Id<CollectXpPayload> ID =
            new Id<>(Identifier.of(SmartSorter.MOD_ID, "collect_xp"));

    public static final PacketCodec<RegistryByteBuf, CollectXpPayload> CODEC =
            PacketCodec.of((buf, payload) -> {}, buf -> new CollectXpPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}