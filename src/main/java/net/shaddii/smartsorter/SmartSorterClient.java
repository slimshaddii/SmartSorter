package net.shaddii.smartsorter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.item.ItemStack;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;

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

        ClientPlayNetworking.registerGlobalReceiver(OverflowNotificationPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                if (client.player == null) return;

                // Build the chat message
                MutableText message = Text.literal("§e[Smart Sorter] §6Items overflowed:").styled(style -> style.withColor(Formatting.GOLD));

                for (Map.Entry<ItemVariant, Long> entry : payload.overflowedItems().entrySet()) {
                    ItemStack stack = entry.getKey().toStack();
                    long count = entry.getValue();

                    MutableText itemText = Text.literal("\n - " + count + "x ").formatted(Formatting.GRAY)
                            .append(stack.getName().copy().formatted(Formatting.AQUA));

                    // Add a hover event to show the item tooltip
                    //? if >= 1.21.8 {
                    itemText.styled(style -> style.withHoverEvent(new HoverEvent.ShowItem(stack)));
                    //?} else {
                    /*itemText.styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackContent(stack))));
                    *///?}

                    message.append(itemText);
                }

                client.player.sendMessage(message, false);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(
                StorageDeltaSyncPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            // Get the handler's client-side item map to modify it
                            Map<ItemVariant, Long> currentItems = handler.getNetworkItems();

                            for (Map.Entry<ItemVariant, Long> entry : payload.changedItems().entrySet()) {
                                if (entry.getValue() > 0) {
                                    // If the count is > 0, it's an addition or update.
                                    currentItems.put(entry.getKey(), entry.getValue());
                                } else {
                                    // If the count is 0, the item has been removed from the network.
                                    currentItems.remove(entry.getKey());
                                }
                            }

                            // Update the handler's internal map with our modified version
                            handler.updateNetworkItems(currentItems);

                            // Mark the screen as dirty so it re-filters and re-renders the grid
                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );
    }
}