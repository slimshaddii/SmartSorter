package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;

public record SortProgressPayload(
        int current,
        int total,
        boolean isComplete
) implements CustomPayload {

    public static final Id<SortProgressPayload> ID =
            new Id<>(Identifier.of(SmartSorter.MOD_ID, "sort_progress"));

    public static final PacketCodec<RegistryByteBuf, SortProgressPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeInt(payload.current);
                buf.writeInt(payload.total);
                buf.writeBoolean(payload.isComplete);
            },
            buf -> {
                int current = buf.readInt();
                int total = buf.readInt();
                boolean isComplete = buf.readBoolean();
                return new SortProgressPayload(current, total, isComplete);
            }
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}