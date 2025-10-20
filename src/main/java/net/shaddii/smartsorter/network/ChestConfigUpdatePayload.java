package net.shaddii.smartsorter.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;

public record ChestConfigUpdatePayload(ChestConfig config) implements CustomPayload {

    public static final Id<ChestConfigUpdatePayload> ID =
            new Id<>(Identifier.of(SmartSorter.MOD_ID, "chest_config_update"));

    public static final PacketCodec<RegistryByteBuf, ChestConfigUpdatePayload> CODEC =
            new PacketCodec<>() {
                @Override
                public ChestConfigUpdatePayload decode(RegistryByteBuf buf) {
                    return ChestConfigUpdatePayload.read(buf);
                }

                @Override
                public void encode(RegistryByteBuf buf, ChestConfigUpdatePayload payload) {
                    ChestConfigUpdatePayload.write(buf, payload);
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void write(RegistryByteBuf buf, ChestConfigUpdatePayload payload) {
        ChestConfig config = payload.config;

        // Write position
        buf.writeBlockPos(config.position);

        // Write custom name
        buf.writeString(config.customName);

        // Write filter category
        buf.writeString(config.filterCategory.asString());

        // Write priority
        buf.writeVarInt(config.priority);

        // Write filter mode
        buf.writeString(config.filterMode.name());

        // Write auto item frame
        buf.writeBoolean(config.autoItemFrame);

        // Calculate Fullness
        buf.writeVarInt(config.cachedFullness);
    }

    public static ChestConfigUpdatePayload read(RegistryByteBuf buf) {
        // Read position
        BlockPos position = buf.readBlockPos();

        // Read custom name
        String customName = buf.readString();

        // Read filter category
        String categoryStr = buf.readString();
        Category category = CategoryManager.getInstance().getCategory(categoryStr);

        // Read priority
        int priority = buf.readVarInt();

        // Read filter mode
        String modeStr = buf.readString();
        ChestConfig.FilterMode mode = ChestConfig.FilterMode.valueOf(modeStr);

        // Read auto item frame
        boolean autoItemFrame = buf.readBoolean();

        //Calculate Fullness
        int cachedFullness = buf.readVarInt();

        ChestConfig config = new ChestConfig(position, customName, category, priority, mode, autoItemFrame);
        config.cachedFullness = cachedFullness;
        return new ChestConfigUpdatePayload(config);
    }
}