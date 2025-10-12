package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;

public record ProbeStatsSyncPayload(
        BlockPos position,
        int itemsProcessed
) implements CustomPayload {

    public static final CustomPayload.Id<ProbeStatsSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "probe_stats_sync"));

    public static final PacketCodec<RegistryByteBuf, ProbeStatsSyncPayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> write(buf, value),
                    buf -> read(buf)
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void write(RegistryByteBuf buf, ProbeStatsSyncPayload payload) {
        buf.writeLong(payload.position.asLong());
        buf.writeInt(payload.itemsProcessed);
    }

    public static ProbeStatsSyncPayload read(RegistryByteBuf buf) {
        BlockPos pos = BlockPos.fromLong(buf.readLong());
        int itemsProcessed = buf.readInt();
        return new ProbeStatsSyncPayload(pos, itemsProcessed);
    }
}