package net.shaddii.smartsorter.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.item.ItemStack;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.screen.OutputProbeScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreen;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import net.shaddii.smartsorter.util.ChestConfig;

import java.util.HashMap;
import java.util.Map;

public class SmartSorterClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register custom screen
        HandledScreens.register(
                SmartSorter.STORAGE_CONTROLLER_SCREEN_HANDLER,
                StorageControllerScreen::new
        );

        HandledScreens.register(
                SmartSorter.OUTPUT_PROBE_SCREEN_HANDLER,
                OutputProbeScreen::new
        );

        // Register main sync packet (items, XP, cursor)
        ClientPlayNetworking.registerGlobalReceiver(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            handler.updateNetworkItems(payload.items());
                            handler.updateStoredXp(payload.storedXp());
                            handler.clearProbeConfigs();
                            handler.clearChestConfigs();
                            handler.updateProbeConfigs(payload.probeConfigs());
                            handler.setCursorStack(payload.cursorStack());

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

                            handler.updateChestConfigs(payload.configs());

                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );

        // Register chest config single update receiver
        ClientPlayNetworking.registerGlobalReceiver(
                ChestConfigUpdatePayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            Map<BlockPos, ChestConfig> singleUpdate = new HashMap<>();
                            singleUpdate.put(payload.config().position, payload.config());
                            handler.updateChestConfigs(singleUpdate);

                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );

        // Register probe stats sync
        ClientPlayNetworking.registerGlobalReceiver(
                ProbeStatsSyncPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            handler.updateProbeStats(payload.position(), payload.itemsProcessed());

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

                            handler.updateProbeConfigs(payload.configs());

                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );

        // Register overflow notification
        ClientPlayNetworking.registerGlobalReceiver(OverflowNotificationPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                if (client.player == null) return;

                MutableText message = Text.literal("§e[Smart Sorter] §6Items overflowed:").styled(style -> style.withColor(Formatting.GOLD));

                for (Map.Entry<ItemVariant, Long> entry : payload.overflowedItems().entrySet()) {
                    ItemStack stack = entry.getKey().toStack();
                    long count = entry.getValue();

                    MutableText itemText = Text.literal("\n - " + count + "x ").formatted(Formatting.GRAY)
                            .append(stack.getName().copy().formatted(Formatting.AQUA));

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

        // Register delta sync
        ClientPlayNetworking.registerGlobalReceiver(
                StorageDeltaSyncPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        if (context.player() != null &&
                                context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {

                            Map<ItemVariant, Long> currentItems = handler.getNetworkItems();

                            for (Map.Entry<ItemVariant, Long> entry : payload.changedItems().entrySet()) {
                                if (entry.getValue() > 0) {
                                    currentItems.put(entry.getKey(), entry.getValue());
                                } else {
                                    currentItems.remove(entry.getKey());
                                }
                            }

                            handler.updateNetworkItems(currentItems);

                            if (context.client().currentScreen instanceof StorageControllerScreen screen) {
                                screen.markDirty();
                            }
                        }
                    });
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                SortProgressPayload.ID,
                (payload, context) -> {
                    context.client().execute(() -> {
                        SortProgressOverlay.updateProgress(
                                payload.current(),
                                payload.total(),
                                payload.isComplete()
                        );

                        // If sorting is complete and there are overflow items
                        if (payload.isComplete() && payload.overflowItems() != null && !payload.overflowItems().isEmpty()) {
                            // TODO: Show overflow GUI instead of chat (we'll implement this in step 2)
                            // For now, we can still use the existing overflow notification
                            MinecraftClient client = context.client();
                            if (client.player != null) {
                                MutableText message = Text.literal("§a[Smart Sorter] §7Sorting complete! §6Overflow items:").styled(style -> style.withColor(Formatting.GOLD));

                                for (Map.Entry<ItemVariant, Long> entry : payload.overflowItems().entrySet()) {
                                    ItemStack stack = entry.getKey().toStack();
                                    long count = entry.getValue();

                                    MutableText itemText = Text.literal("\n - " + count + "x ").formatted(Formatting.GRAY)
                                            .append(stack.getName().copy().formatted(Formatting.AQUA));

                                    //? if >= 1.21.8 {
                                    itemText.styled(style -> style.withHoverEvent(new HoverEvent.ShowItem(stack)));
                                    //?} else {
                                    /*itemText.styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackContent(stack))));
                                     *///?}

                                    message.append(itemText);
                                }

                                client.player.sendMessage(message, false);
                            }
                        }
                    });
                }
        );
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            SortProgressOverlay.render(drawContext);
        });
    }
}