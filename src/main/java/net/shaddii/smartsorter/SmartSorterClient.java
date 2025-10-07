package net.shaddii.smartsorter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.shaddii.smartsorter.network.StorageControllerSyncPacket;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;

public class SmartSorterClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register screen
        HandledScreens.register(SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER, StorageControllerScreen::new);

        // Register packet receiver for network sync
        ClientPlayNetworking.registerGlobalReceiver(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                            handler.updateNetworkItems(payload.items());
                        }
                    });
                }
        );

        SmartSorter.LOGGER.info("SmartSorter client initialized!");
    }
}