package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.util.FuelFilterMode;
import net.shaddii.smartsorter.util.ProcessProbeConfig;
import net.shaddii.smartsorter.util.RecipeFilterMode;

import java.util.HashMap;
import java.util.Map;

public class StorageControllerSyncPacket {
    public static final Identifier ID = Identifier.of(SmartSorter.MOD_ID, "storage_sync");

    public static void send(ServerPlayerEntity player, Map<ItemVariant, Long> items, int storedXp, Map<BlockPos, ProcessProbeConfig> probeConfigs) {
        // Get the cursor from the player's currently open screen handler
        ItemStack cursorStack = player.currentScreenHandler.getCursorStack();
        ServerPlayNetworking.send(player, new SyncPayload(items, storedXp, probeConfigs, cursorStack));
    }

    public record SyncPayload(
            Map<ItemVariant, Long> items,
            int storedXp,
            Map<BlockPos, ProcessProbeConfig> probeConfigs,
            ItemStack cursorStack
    ) implements CustomPayload {
        public static final CustomPayload.Id<SyncPayload> ID_PAYLOAD = new CustomPayload.Id<>(ID);

        public static final PacketCodec<RegistryByteBuf, SyncPayload> CODEC = PacketCodec.of(
                (value, buf) -> write(buf, value),
                buf -> read(buf)
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID_PAYLOAD;
        }

        public static void write(RegistryByteBuf buf, SyncPayload payload) {
            // Write items (unchanged)
            buf.writeVarInt(payload.items.size());
            for (Map.Entry<ItemVariant, Long> entry : payload.items.entrySet()) {
                ItemStack stack = entry.getKey().toStack(1);
                ItemStack.PACKET_CODEC.encode(buf, stack);
                buf.writeVarLong(entry.getValue());
            }

            // Write stored XP (unchanged)
            buf.writeVarInt(payload.storedXp);

            // Write probe configs (unchanged)
            buf.writeVarInt(payload.probeConfigs.size());
            for (Map.Entry<BlockPos, ProcessProbeConfig> entry : payload.probeConfigs.entrySet()) {
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

            // Manually handle empty cursor stack
            if (payload.cursorStack.isEmpty()) {
                buf.writeBoolean(false); // Write 'false' to indicate no item stack follows
            } else {
                buf.writeBoolean(true);  // Write 'true' to indicate an item stack follows
                ItemStack.PACKET_CODEC.encode(buf, payload.cursorStack); // Now, safely write the stack
            }
        }

        public static SyncPayload read(RegistryByteBuf buf) {
            // Read items (unchanged)
            Map<ItemVariant, Long> items = new HashMap<>();
            int itemCount = buf.readVarInt();
            for (int i = 0; i < itemCount; i++) {
                ItemStack stack = ItemStack.PACKET_CODEC.decode(buf);
                ItemVariant variant = ItemVariant.of(stack);
                long amount = buf.readVarLong();
                items.put(variant, amount);
            }

            // Read stored XP (unchanged)
            int storedXp = buf.readVarInt();

            // Read probe configs (unchanged)
            Map<BlockPos, ProcessProbeConfig> probeConfigs = new HashMap<>();
            int configCount = buf.readVarInt();
            for (int i = 0; i < configCount; i++) {
                BlockPos pos = BlockPos.fromLong(buf.readLong());
                ProcessProbeConfig config = new ProcessProbeConfig();
                config.position = pos;
                config.machineType = buf.readString();
                boolean hasCustomName = buf.readBoolean();
                if (hasCustomName) {
                    config.customName = buf.readString();
                }
                config.enabled = buf.readBoolean();
                config.recipeFilter = RecipeFilterMode.fromString(buf.readString());
                config.fuelFilter = FuelFilterMode.fromString(buf.readString());
                config.itemsProcessed = buf.readVarInt();
                config.index = buf.readVarInt();
                probeConfigs.put(pos, config);
            }

            // Manually read cursor stack
            ItemStack cursorStack;
            if (buf.readBoolean()) { // Read the boolean flag first
                cursorStack = ItemStack.PACKET_CODEC.decode(buf); // If true, read the stack
            } else {
                cursorStack = ItemStack.EMPTY; // If false, use an empty stack
            }

            return new SyncPayload(items, storedXp, probeConfigs, cursorStack);
        }
    }
}