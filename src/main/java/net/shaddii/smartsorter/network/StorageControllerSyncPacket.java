package net.shaddii.smartsorter.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.SmartSorter;

import java.util.HashMap;
import java.util.Map;

public class StorageControllerSyncPacket {
    public static final Identifier ID = Identifier.of(SmartSorter.MOD_ID, "storage_sync");

    /**
     * Send network items to client
     */
    public static void send(ServerPlayerEntity player, Map<ItemVariant, Long> items) {
        ServerPlayNetworking.send(player, new SyncPayload(items));
    }

    /**
     * Custom payload record for the sync packet
     */
    public record SyncPayload(Map<ItemVariant, Long> items) implements CustomPayload {
        public static final CustomPayload.Id<SyncPayload> ID_PAYLOAD = new CustomPayload.Id<>(ID);

        public static final PacketCodec<RegistryByteBuf, SyncPayload> CODEC = PacketCodec.of(
                (value, buf) -> write(buf, value),
                buf -> read(buf)
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID_PAYLOAD;
        }

        /**
         * Write payload to buffer
         */
        public static void write(RegistryByteBuf buf, SyncPayload payload) {
            buf.writeVarInt(payload.items.size());

            for (Map.Entry<ItemVariant, Long> entry : payload.items.entrySet()) {
                ItemVariant variant = entry.getKey();

                // Write as ItemStack (handles components automatically)
                ItemStack stack = variant.toStack(1);
                ItemStack.PACKET_CODEC.encode(buf, stack);

                // Write amount
                buf.writeVarLong(entry.getValue());
            }
        }

        /**
         * Read payload from buffer
         */
        public static SyncPayload read(RegistryByteBuf buf) {
            Map<ItemVariant, Long> items = new HashMap<>();
            int size = buf.readVarInt();

            for (int i = 0; i < size; i++) {
                // Read ItemStack (handles components automatically)
                ItemStack stack = ItemStack.PACKET_CODEC.decode(buf);

                // Create ItemVariant from stack
                ItemVariant variant = ItemVariant.of(stack);

                // Read amount
                long amount = buf.readVarLong();

                items.put(variant, amount);
            }

            return new SyncPayload(items);
        }
    }
}