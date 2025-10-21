package net.shaddii.smartsorter.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.network.OverflowNotificationPayload;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;

import java.util.*;

/**
 * Processes chest sorting over multiple ticks to prevent server freezing.
 * Processes 3 chests per tick (60ms total if each chest takes ~20ms).
 */
public class ChunkedSorter {
    private static final Map<UUID, SortTask> activeTasks = new HashMap<>();
    private static final int CHESTS_PER_TICK = 3; // Process 3 chests per tick

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Map.Entry<UUID, SortTask>> iterator = activeTasks.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<UUID, SortTask> entry = iterator.next();
                SortTask task = entry.getValue();

                // Check if player is still online and in the right screen
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player == null || !(player.currentScreenHandler instanceof StorageControllerScreenHandler)) {
                    iterator.remove();
                    continue;
                }

                // Process up to CHESTS_PER_TICK chests this tick
                boolean finished = task.processChunk(player);

                if (finished) {
                    iterator.remove();
                }
            }
        });
    }

    public static void startSorting(ServerPlayerEntity player, StorageControllerBlockEntity controller, List<BlockPos> positions) {
        UUID playerId = player.getUuid();

        // Cancel existing task if any
        activeTasks.remove(playerId);

        // Start new task
        SortTask task = new SortTask(controller, positions);
        activeTasks.put(playerId, task);

        player.sendMessage(Text.literal("§e[Smart Sorter] §7Sorting " + positions.size() + " chests..."), true);
    }

    private static class SortTask {
        private final StorageControllerBlockEntity controller;
        private final List<BlockPos> positions;
        private final Map<ItemVariant, Long> overflowCounts = new HashMap<>();
        private int currentIndex = 0;

        SortTask(StorageControllerBlockEntity controller, List<BlockPos> positions) {
            this.controller = controller;
            this.positions = positions;
        }

        boolean processChunk(ServerPlayerEntity player) {
            int processed = 0;

            while (currentIndex < positions.size() && processed < CHESTS_PER_TICK) {
                BlockPos pos = positions.get(currentIndex);

                if (controller.isChestLinked(pos)) {
                    controller.sortChestIntoNetwork(pos, overflowCounts);
                    processed++;
                }

                currentIndex++;
            }

            // Update progress
            if (currentIndex < positions.size()) {
                int percent = (currentIndex * 100) / positions.size();
                player.sendMessage(Text.literal("§e[Smart Sorter] §7" + percent + "% (" + currentIndex + "/" + positions.size() + ")"), true);
                return false; // Not finished
            } else {
                // Finished!
                controller.markDirty();
                controller.updateNetworkCache();

                // Send overflow notification if needed
                if (!overflowCounts.isEmpty()) {
                    ServerPlayNetworking.send(player, new OverflowNotificationPayload(overflowCounts));
                }

                // Send final update
                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                    handler.sendNetworkUpdate(player);
                }

                player.sendMessage(Text.literal("§a[Smart Sorter] ✓ Sorted " + positions.size() + " chests!"), true);
                return true; // Finished
            }
        }
    }
}