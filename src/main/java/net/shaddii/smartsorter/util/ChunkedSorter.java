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
import net.shaddii.smartsorter.network.SortProgressPayload; // New payload we'll create
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;

import java.util.*;

public class ChunkedSorter {
    private static final Map<UUID, SortTask> activeTasks = new HashMap<>();
    private static final int CHESTS_PER_TICK = 3;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Map.Entry<UUID, SortTask>> iterator = activeTasks.entrySet().iterator();

            while (iterator.hasNext()) {
                Map.Entry<UUID, SortTask> entry = iterator.next();
                SortTask task = entry.getValue();

                // Check if player is still online (removed the screen handler check)
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player == null) {
                    iterator.remove();
                    continue;
                }

                // Process up to CHESTS_PER_TICK chests this tick
                boolean finished = task.processChunk(player);

                if (finished) {
                    iterator.remove();

                    // Send completion notification
                    ServerPlayNetworking.send(player, new SortProgressPayload(
                            task.positions.size(),
                            task.positions.size(),
                            true,
                            task.overflowCounts,
                            task.overflowDestinations
                    ));
                }
            }
        });
    }

    public static void startSorting(ServerPlayerEntity player, StorageControllerBlockEntity controller, List<BlockPos> positions) {
        UUID playerId = player.getUuid();

        activeTasks.remove(playerId);

        SortTask task = new SortTask(controller, positions);
        activeTasks.put(playerId, task);

        ServerPlayNetworking.send(player, new SortProgressPayload(0, positions.size(), false, null, null));
    }


    public static boolean isPlayerSorting(UUID playerId) {
        return activeTasks.containsKey(playerId);
    }

    public static float getSortProgress(UUID playerId) {
        SortTask task = activeTasks.get(playerId);
        if (task == null) return 0f;
        return (float) task.currentIndex / task.positions.size();
    }

    private static class SortTask {
        private final StorageControllerBlockEntity controller;
        private final List<BlockPos> positions;
        private final Map<ItemVariant, Long> overflowCounts = new HashMap<>();
        private final Map<ItemVariant, String> overflowDestinations = new HashMap<>(); // NEW!
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
                    controller.sortChestIntoNetwork(pos, overflowCounts, overflowDestinations);
                    processed++;
                }

                currentIndex++;
            }

            // Send progress update
            ServerPlayNetworking.send(player, new SortProgressPayload(
                    currentIndex,
                    positions.size(),
                    false,
                    null,
                    null
            ));

            if (currentIndex < positions.size()) {
                return false;
            } else {
                // Finished! Send completion with overflow data
                controller.markDirty();
                controller.updateNetworkCache();

                // Send final update WITH overflow data
                ServerPlayNetworking.send(player, new SortProgressPayload(
                        positions.size(),
                        positions.size(),
                        true,
                        overflowCounts.isEmpty() ? null : overflowCounts,
                        overflowDestinations.isEmpty() ? null : overflowDestinations
                ));

                // Update screen handler if open
                if (player.currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                    handler.sendNetworkUpdate(player);
                }

                return true;
            }
        }
    }
}