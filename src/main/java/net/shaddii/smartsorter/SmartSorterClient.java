package net.shaddii.smartsorter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.shaddii.smartsorter.network.StorageControllerSyncPacket;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
//import net.shaddii.smartsorter.SmartSorter; // DEBUG: For debug logging

/**
 * SmartSorter Client entrypoint (for Minecraft 1.21.9 + Fabric).
 *
 * Handles all client-side initialization tasks such as:
 * - GUI screen registration
 * - Client-bound packet listeners for syncing storage data
 *
 * This class complements {@link SmartSorter} which handles server/common setup.
 */
public class SmartSorterClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // DEBUG: SmartSorter.LOGGER.info("Initializing SmartSorter client (Minecraft 1.21.9)...");

        // === 1) Register custom screen ===
        // This binds the server-side ScreenHandler (StorageControllerScreenHandler)
        // to the actual client-side GUI (StorageControllerScreen).
        HandledScreens.register(
                SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER,
                StorageControllerScreen::new
        );

        // === 2) Register client network receiver ===
        // This listens for server-to-client sync packets from the storage controller.
        // These packets contain updates about the items currently in the network.
        ClientPlayNetworking.registerGlobalReceiver(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                (payload, context) -> {
                    // Ensure execution on the main client thread for UI updates
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            // Update the client-side handler data with synced items from the server
                            handler.updateNetworkItems(payload.items());
                        }
                    });
                }
        );

        // DEBUG: SmartSorter.LOGGER.info("SmartSorter client initialized successfully!");
    }
}
