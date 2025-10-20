package net.shaddii.smartsorter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.ProcessProbeConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * SmartSorter Client entrypoint (for Minecraft 1.21.10 + Fabric)
 * OPTIMIZATIONS:
 * - Added markDirty() call to prevent unnecessary re-renders
 * - Added XP syncing from server to client
 * - Added cursor stack syncing
 * - Added chest config syncing
 */
public class SmartSorterClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register custom screen
        HandledScreens.register(
                SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER,
                StorageControllerScreen::new
        );

        // Register main sync packet (items, XP, cursor)
        ClientPlayNetworking.registerGlobalReceiver(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            // Update network items
                            handler.updateNetworkItems(payload.items());

                            // Update stored XP
                            handler.updateStoredXp(payload.storedXp());

                            // CLEAR configs first (empty map signals batches are coming)
                            handler.clearProbeConfigs();
                            handler.clearChestConfigs();

                            // Update probe configs (will be empty, batches follow)
                            handler.updateProbeConfigs(payload.probeConfigs());

                            // Sync cursor stack
                            handler.setCursorStack(payload.cursorStack());

                            // Mark screen dirty to trigger refresh
                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );

        // Register chest config batch receiver
        ClientPlayNetworking.registerGlobalReceiver(
                ChestConfigBatchPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            // Merge chest configs (like probe configs)
                            handler.updateChestConfigs(payload.configs());

                            // Update screen if open
                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );

        // Register chest config single update receiver (for real-time updates)
        ClientPlayNetworking.registerGlobalReceiver(
                ChestConfigUpdatePayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            // Update single chest config
                            Map<BlockPos, ChestConfig> singleUpdate = new HashMap<>();
                            singleUpdate.put(payload.config().position, payload.config());
                            handler.updateChestConfigs(singleUpdate);

                            // Update screen if open
                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );

        // Register probe stats sync (real-time updates)
        ClientPlayNetworking.registerGlobalReceiver(
                ProbeStatsSyncPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            // Update the handler's stats
                            handler.updateProbeStats(payload.position(), payload.itemsProcessed());

                            // Update the screen if it's open
                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.updateProbeStats(payload.position(), payload.itemsProcessed());
                                screen.markDirty();
                            }
                        }
                    });
                }
        );

        // Register probe config batch receiver
        ClientPlayNetworking.registerGlobalReceiver(
                ProbeConfigBatchPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            // Just pass the batch directly (updateProbeConfigs uses putAll to merge)
                            handler.updateProbeConfigs(payload.configs());

                            // Update screen
                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );
    }
}