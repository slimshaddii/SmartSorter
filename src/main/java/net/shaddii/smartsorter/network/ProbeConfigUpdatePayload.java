package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.FuelFilterMode;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.RecipeFilterMode;

public record ProbeConfigUpdatePayload(
        BlockPos position,
        String customName,
        boolean enabled,
        RecipeFilterMode recipeFilter,
        FuelFilterMode fuelFilter
) implements CustomPayload {

    public static final CustomPayload.Id<ProbeConfigUpdatePayload> ID =
            new CustomPayload.Id<>(Identifier.of(SmartSorter.MOD_ID, "probe_config_update"));

    public static final PacketCodec<RegistryByteBuf, ProbeConfigUpdatePayload> CODEC =
            PacketCodec.of(
                    (value, buf) -> write(buf, value),
                    buf -> read(buf)
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void write(RegistryByteBuf buf, ProbeConfigUpdatePayload payload) {
        buf.writeLong(payload.position.asLong());
        buf.writeBoolean(payload.customName != null);
        if (payload.customName != null) {
            buf.writeString(payload.customName);
        }
        buf.writeBoolean(payload.enabled);
        buf.writeString(payload.recipeFilter.asString());
        buf.writeString(payload.fuelFilter.asString());
    }

    public static ProbeConfigUpdatePayload read(RegistryByteBuf buf) {
        BlockPos pos = BlockPos.fromLong(buf.readLong());
        String customName = buf.readBoolean() ? buf.readString() : null;
        boolean enabled = buf.readBoolean();
        RecipeFilterMode recipeFilter = RecipeFilterMode.fromString(buf.readString());
        FuelFilterMode fuelFilter = FuelFilterMode.fromString(buf.readString());

        return new ProbeConfigUpdatePayload(pos, customName, enabled, recipeFilter, fuelFilter);
    }
}