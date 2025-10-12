package net.shaddii.smartsorter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.shaddii.smartsorter.network.StorageControllerSyncPacket;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.network.ProbeConfigUpdatePayload;

/**
 * SmartSorter Client entrypoint (for Minecraft 1.21.10 + Fabric)
 *
 * OPTIMIZATIONS:
 * - Added markDirty() call to prevent unnecessary re-renders
 * - Added XP syncing from server to client
 * - Added cursor stack syncing
 */
public class SmartSorterClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register custom screen
        HandledScreens.register(
                SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER,
                StorageControllerScreen::new
        );

        // Register client network receiver
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

                            // Update probe configs
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
    }
}