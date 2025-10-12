package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.RecipeFilterMode;
import net.shaddii.smartsorter.util.FuelFilterMode;

import java.util.HashMap;
import java.util.Map;

public record ProbeConfigBatchPayload(Map<BlockPos, ProcessProbeConfig> configs) implements CustomPayload {
    public static final Id<ProbeConfigBatchPayload> ID =
            new Id<>(Identifier.of(SmartSorter.MOD_ID, "probe_config_batch"));

    public static final PacketCodec<RegistryByteBuf, ProbeConfigBatchPayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> write(buf, value),  // Fixed: swapped parameter order
                    buf -> read(buf)
            );

    private static void write(RegistryByteBuf buf, ProbeConfigBatchPayload payload) {
        buf.writeVarInt(payload.configs.size());

        for (Map.Entry<BlockPos, ProcessProbeConfig> entry : payload.configs.entrySet()) {
            buf.writeLong(entry.getKey().asLong());
            ProcessProbeConfig config = entry.getValue();

            buf.writeString(config.machineType);
            buf.writeBoolean(config.customName != null);
            if (config.customName != null) {
                buf.writeString(config.customName);
            }
            buf.writeBoolean(config.enabled);
            buf.writeString(config.recipeFilter.asString());
            buf.writeString(config.fuelFilter.asString());
            buf.writeVarInt(config.itemsProcessed);
            buf.writeVarInt(config.index);
        }
    }

    private static ProbeConfigBatchPayload read(RegistryByteBuf buf) {
        Map<BlockPos, ProcessProbeConfig> configs = new HashMap<>();
        int count = buf.readVarInt();

        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.fromLong(buf.readLong());
            ProcessProbeConfig config = new ProcessProbeConfig();
            config.position = pos;
            config.machineType = buf.readString();

            if (buf.readBoolean()) {
                config.customName = buf.readString();
            }

            config.enabled = buf.readBoolean();
            config.recipeFilter = RecipeFilterMode.fromString(buf.readString());
            config.fuelFilter = FuelFilterMode.fromString(buf.readString());
            config.itemsProcessed = buf.readVarInt();
            config.index = buf.readVarInt();

            configs.put(pos, config);
        }

        return new ProbeConfigBatchPayload(configs);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}