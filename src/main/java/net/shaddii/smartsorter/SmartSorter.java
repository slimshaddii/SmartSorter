package net.shaddii.smartsorter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.shaddii.smartsorter.block.IntakeBlock;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.block.StorageControllerBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;
import net.shaddii.smartsorter.network.StorageControllerSyncPacket;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartSorter implements ModInitializer {
    public static final String MOD_ID = "smartsorter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // === Blocks ===
    public static IntakeBlock INTAKE_BLOCK;
    public static OutputProbeBlock PROBE_BLOCK;
    public static StorageControllerBlock STORAGE_CONTROLLER_BLOCK;

    // === Block Items ===
    public static Item INTAKE_ITEM;
    public static Item PROBE_ITEM;
    public static Item STORAGE_CONTROLLER_ITEM;

    // === Tools ===
    public static Item LINKING_TOOL;

    // === Block Entities ===
    public static BlockEntityType<IntakeBlockEntity> INTAKE_BE_TYPE;
    public static BlockEntityType<OutputProbeBlockEntity> PROBE_BE_TYPE;
    public static BlockEntityType<StorageControllerBlockEntity> STORAGE_CONTROLLER_BE_TYPE;

    // === Screen Handlers ===
    public static ScreenHandlerType<StorageControllerScreenHandler> STORAGE_CONTROLLER_SCREEN_HANDLER;

    @Override
    public void onInitialize() {
        LOGGER.info("SmartSorter initializing...");

        // === Register Network Packets ===
        registerNetworkPackets();

        // === Register Blocks ===
        registerBlocks();

        // === Register Block Items ===
        registerBlockItems();

        // === Register Block Entity Types ===
        registerBlockEntities();

        // === Register Screen Handlers ===
        registerScreenHandlers();

        // === Register Tools ===
        registerTools();

        // === Register Creative Tab ===
        registerCreativeTab();

        // === Register Event Handlers ===
        registerEventHandlers();

        // === Register Network Handlers ===
        registerNetworkHandlers();

        LOGGER.info("SmartSorter initialized successfully!");
    }

    /**
     * Register all network packets
     */
    private void registerNetworkPackets() {
        // Server to Client: Storage network sync
        PayloadTypeRegistry.playS2C().register(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                StorageControllerSyncPacket.SyncPayload.CODEC
        );

        // Client to Server: Extraction request
        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                StorageControllerScreenHandler.ExtractionRequestPayload.CODEC
        );

        // Client to Server: Sync request
        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.SyncRequestPayload.ID,
                StorageControllerScreenHandler.SyncRequestPayload.CODEC
        );

        // Client to Server: Deposit request
        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.DepositRequestPayload.ID,
                StorageControllerScreenHandler.DepositRequestPayload.CODEC
        );

        LOGGER.info("Network packets registered!");
    }

    /**
     * Register blocks
     */
    private void registerBlocks() {
        INTAKE_BLOCK = Registry.register(
                Registries.BLOCK,
                Identifier.of(MOD_ID, "intake"),
                new IntakeBlock()
        );

        PROBE_BLOCK = Registry.register(
                Registries.BLOCK,
                Identifier.of(MOD_ID, "output_probe"),
                new OutputProbeBlock()
        );

        STORAGE_CONTROLLER_BLOCK = Registry.register(
                Registries.BLOCK,
                Identifier.of(MOD_ID, "storage_controller"),
                new StorageControllerBlock()
        );

        LOGGER.info("Blocks registered!");
    }

    /**
     * Register block items
     */
    private void registerBlockItems() {
        INTAKE_ITEM = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "intake"),
                new BlockItem(INTAKE_BLOCK, new Item.Settings())
        );

        PROBE_ITEM = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "output_probe"),
                new BlockItem(PROBE_BLOCK, new Item.Settings())
        );

        STORAGE_CONTROLLER_ITEM = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "storage_controller"),
                new BlockItem(STORAGE_CONTROLLER_BLOCK, new Item.Settings())
        );

        LOGGER.info("Block items registered!");
    }

    /**
     * Register block entities
     */
    private void registerBlockEntities() {
        INTAKE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.of(MOD_ID, "intake"),
                BlockEntityType.Builder.create(IntakeBlockEntity::new, INTAKE_BLOCK).build()
        );

        PROBE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.of(MOD_ID, "probe"),
                BlockEntityType.Builder.create(OutputProbeBlockEntity::new, PROBE_BLOCK).build()
        );

        STORAGE_CONTROLLER_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.of(MOD_ID, "storage_controller"),
                BlockEntityType.Builder.create(StorageControllerBlockEntity::new, STORAGE_CONTROLLER_BLOCK).build()
        );

        LOGGER.info("Block entities registered!");
    }

    /**
     * Register screen handlers
     */
    private void registerScreenHandlers() {
        STORAGE_CONTROLLER_SCREEN_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                Identifier.of(MOD_ID, "storage_controller"),
                new ScreenHandlerType<>(StorageControllerScreenHandler::new, FeatureSet.empty())
        );

        LOGGER.info("Screen handlers registered!");
    }

    /**
     * Register tools and items
     */
    private void registerTools() {
        LINKING_TOOL = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "linking_tool"),
                new LinkingToolItem(new Item.Settings().maxCount(1))
        );

        LOGGER.info("Tools registered!");
    }

    /**
     * Register creative mode item group
     */
    private void registerCreativeTab() {
        Registry.register(
                Registries.ITEM_GROUP,
                Identifier.of(MOD_ID, "smartsorter_group"),
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.smartsorter"))
                        .icon(() -> new ItemStack(STORAGE_CONTROLLER_BLOCK))
                        .entries((displayContext, entries) -> {
                            entries.add(STORAGE_CONTROLLER_BLOCK);
                            entries.add(INTAKE_BLOCK);
                            entries.add(PROBE_BLOCK);
                            entries.add(LINKING_TOOL);
                        })
                        .build()
        );

        LOGGER.info("Creative tab registered!");
    }

    /**
     * Register event handlers (block interactions, etc.)
     */
    private void registerEventHandlers() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            // Future: Add custom block interaction logic here if needed
            return ActionResult.PASS;
        });

        LOGGER.info("Event handlers registered!");
    }

    /**
     * Register server-side network packet handlers
     */
    private void registerNetworkHandlers() {
        // Handle extraction requests from client
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                            handler.extractItem(
                                    payload.variant(),
                                    payload.amount(),
                                    payload.toInventory(),
                                    context.player()
                            );
                        }
                    });
                }
        );

        // Handle sync requests from client
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.SyncRequestPayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                            handler.sendNetworkUpdate(context.player());
                        }
                    });
                }
        );

        // Handle deposit requests from client
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.DepositRequestPayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                            ItemStack cursorStack = context.player().currentScreenHandler.getCursorStack();
                            if (!cursorStack.isEmpty()) {
                                handler.depositItem(cursorStack, payload.amount(), context.player());
                            }
                        }
                    });
                }
        );

        LOGGER.info("Network handlers registered!");
    }
}