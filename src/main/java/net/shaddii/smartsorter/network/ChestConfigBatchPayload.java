package net.shaddii.smartsorter.network;

import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record ChestConfigBatchPayload(Map<BlockPos, ChestConfig> configs) implements CustomPayload {

    public static final Id<ChestConfigBatchPayload> ID =
            new Id<>(Identifier.of(SmartSorter.MOD_ID, "chest_config_batch"));

    public static final PacketCodec<RegistryByteBuf, ChestConfigBatchPayload> CODEC =
            new PacketCodec<>() {
                @Override
                public ChestConfigBatchPayload decode(RegistryByteBuf buf) {
                    return ChestConfigBatchPayload.read(buf);
                }

                @Override
                public void encode(RegistryByteBuf buf, ChestConfigBatchPayload payload) {
                    ChestConfigBatchPayload.write(buf, payload);
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void write(RegistryByteBuf buf, ChestConfigBatchPayload payload) {
        buf.writeVarInt(payload.configs.size());

        for (Map.Entry<BlockPos, ChestConfig> entry : payload.configs.entrySet()) {
            ChestConfig config = entry.getValue();

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

            // Preview Items
            buf.writeVarInt(config.previewItems.size());
            for (ItemStack stack : config.previewItems) {
                ItemStack.PACKET_CODEC.encode(buf, stack);
            }
        }
    }

    public static ChestConfigBatchPayload read(RegistryByteBuf buf) {
        int size = buf.readVarInt();
        Map<BlockPos, ChestConfig> configs = new HashMap<>(size);

        for (int i = 0; i < size; i++) {
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

            // Preview Items
            int previewSize = buf.readVarInt();
            List<ItemStack> previewItems = new ArrayList<>();
            for (int j = 0; j < previewSize; j++) {
                previewItems.add(ItemStack.PACKET_CODEC.decode(buf));
            }

            ChestConfig config = new ChestConfig(position, customName, category, priority, mode, autoItemFrame);
            config.cachedFullness = cachedFullness;
            config.previewItems = previewItems;
            configs.put(position, config);
        }

        return new ChestConfigBatchPayload(configs);
    }
}