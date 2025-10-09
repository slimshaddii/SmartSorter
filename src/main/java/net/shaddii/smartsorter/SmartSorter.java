package net.shaddii.smartsorter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
// import net.fabricmc.fabric.api.resource.ResourceManagerHelper; // DEBUG: Unused import
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
// import net.fabricmc.fabric.api.resource.ResourcePackActivationType; // DEBUG: Unused import
// import net.fabricmc.loader.api.FabricLoader; // DEBUG: Unused import
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shaddii.smartsorter.block.IntakeBlock;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.block.StorageControllerBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;
import net.shaddii.smartsorter.network.StorageControllerSyncPacket;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;
// import net.fabricmc.fabric.api.resource.ResourceManagerHelper; // DEBUG: Duplicate unused import

// import java.io.InputStream; // DEBUG: Unused import

/**
 * Main mod entry point for SmartSorter 1.1.0 (rewrite for Minecraft 1.21.9 + Fabric).
 * Keeps the same feature set as the original: block/item/block-entity/screen registrations,
 * creative tab, linking tool, payload registration, and server-side packet handlers.
 */
public class SmartSorter implements ModInitializer {
    public static final String MOD_ID = "smartsorter";
    // public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // === Blocks ===
    public static IntakeBlock INTAKE_BLOCK;
    public static OutputProbeBlock PROBE_BLOCK;
    public static StorageControllerBlock STORAGE_CONTROLLER_BLOCK;

    // === Block Items ===
    public static Item INTAKE_ITEM;
    public static Item PROBE_ITEM;
    public static Item STORAGE_CONTROLLER_ITEM;

    // === Tools / Items ===
    public static Item LINKING_TOOL;

    // === Block Entities ===
    public static BlockEntityType<IntakeBlockEntity> INTAKE_BE_TYPE;
    public static BlockEntityType<OutputProbeBlockEntity> PROBE_BE_TYPE;
    public static BlockEntityType<StorageControllerBlockEntity> STORAGE_CONTROLLER_BE_TYPE;

    // === Screen Handlers ===
    public static ScreenHandlerType<StorageControllerScreenHandler> STORAGE_CONTROLLER_SCREEN_HANDLER;

    @Override
    public void onInitialize() {
        // DEBUG: LOGGER.info("SmartSorter (1.1.0 rewrite) initializing for Minecraft 1.21.9...");


        // 1) Register payload types used by typed packet system (fabric networking)
        registerNetworkPayloadTypes();

        // 2) Register blocks / block items
        registerBlocks();
        registerBlockItems();

        // 3) Register block entity types
        registerBlockEntities();

        // 4) Register screen (container) types
        registerScreenHandlers();

        // 5) Register items/tools
        registerTools();

        // 6) Register creative item group
        registerCreativeTab();

        // 7) Register event handlers (e.g. use block callback)
        registerEventHandlers();

        // 8) Register network receivers (server-side handlers for payloads)
        registerNetworkHandlers();

/*
        // debug: verify recipe resources are visible to the classloader at runtime
        String[] recipeFiles = {"intake.json","linking_tool.json","output_probe.json","storage_controller.json"};
        ClassLoader cl = SmartSorter.class.getClassLoader();
        for (String f : recipeFiles) {
            String path = "data/smartsorter/recipes/" + f;
            try (InputStream is = cl.getResourceAsStream(path)) {
                // DEBUG: LOGGER.info("Resource {} present on classpath? {}", path, is != null);
            } catch (Exception e) {
                // DEBUG: LOGGER.error("Error reading resource {}", path, e);
            }
        }

        for (String id : new String[]{"intake", "linking_tool", "output_probe", "storage_controller"}) {
            var item = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(SmartSorter.MOD_ID, id));
            // DEBUG: SmartSorter.LOGGER.info("Recipe item check: smartsorter:{} -> {}", id, item == null ? "NULL" : item.toString());
        }


        var modContainer = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow();
        ResourceManagerHelper.registerBuiltinResourcePack(
                Identifier.of(MOD_ID, "builtin"),  // ID of resource/data folder
                modContainer,
                ResourcePackActivationType.DEFAULT_ENABLED
        );
        // DEBUG: LOGGER.info("Registered builtin resource pack with activation type.");



 */
        // DEBUG: LOGGER.info("SmartSorter initialized successfully!");
    }

    // -------------------------------------------
    // Network / Payload registration
    // -------------------------------------------

    /**
     * Register any custom payload types with PayloadTypeRegistry.
     * These are typed payloads that can be (de)serialized automatically.
     */
    private void registerNetworkPayloadTypes() {
        // Server-to-client: storage network sync payload codec & id
        PayloadTypeRegistry.playS2C().register(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                StorageControllerSyncPacket.SyncPayload.CODEC
        );

        // Client-to-server: various screen handler requests use typed payloads defined near the screen handler class.
        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                StorageControllerScreenHandler.ExtractionRequestPayload.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.SyncRequestPayload.ID,
                StorageControllerScreenHandler.SyncRequestPayload.CODEC
        );

        PayloadTypeRegistry.playC2S().register(
                StorageControllerScreenHandler.DepositRequestPayload.ID,
                StorageControllerScreenHandler.DepositRequestPayload.CODEC
        );

        // DEBUG: LOGGER.info("Network payload types registered.");
    }

    // -------------------------------------------
    // Block / Item registrations
    // -------------------------------------------

    /**
     * Register all blocks used by the mod.
     * 
     * CRITICAL FIX for Minecraft 1.21.9:
     * In 1.21.9, AbstractBlock constructor calls getLootTableKey() during initialization,
     * which requires the block to have a registry key. This creates a chicken-and-egg problem:
     * - Can't create block without registry key
     * - Can't register block without creating it
     * 
     * SOLUTION: Explicitly set the loot table key in Settings using .lootTable()
     * This prevents the automatic loot table key generation that requires the registry ID.
     * 
     * Block properties:
     * - strength(0.6F): Mining hardness/resistance (similar to stone buttons)
     * - requiresTool(): Requires proper tool to drop the block
     * - nonOpaque(): For intake/probe blocks - allows light to pass through
     * - lootTable(): Explicitly sets loot table key to avoid NPE during construction
     */
    private static RegistryKey<Block> blockKey(String path) {
        return RegistryKey.of(RegistryKeys.BLOCK, Identifier.of(MOD_ID, path));
    }

    private void registerBlocks() {
        // Intake Block: Pulls items from adjacent inventories and distributes to output probes
        INTAKE_BLOCK = Registry.register(
                Registries.BLOCK,
                Identifier.of(MOD_ID, "intake"),
                new IntakeBlock(AbstractBlock.Settings.create()
                        .registryKey(blockKey("intake"))
                        .strength(0.6F)
                        .nonOpaque())
        );

        // Output Probe Block: Receives items from intakes and outputs to adjacent inventories
        PROBE_BLOCK = Registry.register(
                Registries.BLOCK,
                Identifier.of(MOD_ID, "output_probe"),
                new OutputProbeBlock(AbstractBlock.Settings.create()
                        .registryKey(blockKey("output_probe"))
                        .strength(0.6F)
                        .nonOpaque())
        );

        // Storage Controller Block: Central hub for viewing/managing all linked inventories
        STORAGE_CONTROLLER_BLOCK = Registry.register(
                Registries.BLOCK,
                Identifier.of(MOD_ID, "storage_controller"),
                new StorageControllerBlock(AbstractBlock.Settings.create()
                        .registryKey(blockKey("storage_controller"))
                        .strength(0.6F)

            )
        );

        // DEBUG: LOGGER.info("Blocks registered.");
    }

    /**
     * Register BlockItems for any blocks that should be obtainable as items.
     */
    private void registerBlockItems() {
        INTAKE_ITEM = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "intake"),
                new BlockItem(INTAKE_BLOCK, new Item.Settings()
                        .registryKey(RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "intake")))
                        .useBlockPrefixedTranslationKey())
        );

        PROBE_ITEM = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "output_probe"),
                new BlockItem(PROBE_BLOCK, new Item.Settings()
                        .registryKey(RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "output_probe")))
                        .useBlockPrefixedTranslationKey())
        );

        STORAGE_CONTROLLER_ITEM = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "storage_controller"),
                new BlockItem(STORAGE_CONTROLLER_BLOCK, new Item.Settings()
                        .registryKey(RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "storage_controller")))
                        .useBlockPrefixedTranslationKey())
        );

        // DEBUG: LOGGER.info("Block items registered.");
    }

    // -------------------------------------------
    // Block Entity registrations
    // -------------------------------------------

    /**
     * Register all block entity types used by the mod.
     * Note: The builder takes a supplier for creating the block entity and the valid blocks for that BE.
     */
    private void registerBlockEntities() {
        // 1.21.9: BlockEntityType registration - using Fabric's FabricBlockEntityTypeBuilder
        INTAKE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.of(MOD_ID, "intake"),
                net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(IntakeBlockEntity::new, INTAKE_BLOCK).build()
        );


        PROBE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.of(MOD_ID, "output_probe"),
                net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(OutputProbeBlockEntity::new, PROBE_BLOCK).build()
        );


        STORAGE_CONTROLLER_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.of(MOD_ID, "storage_controller"),
                net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(StorageControllerBlockEntity::new, STORAGE_CONTROLLER_BLOCK).build()
        );

        // DEBUG: LOGGER.info("Block entity types registered.");
    }

    // -------------------------------------------
    // Screen / GUI registrations
    // -------------------------------------------

    /**
     * Register screen handler (container) types so clients and servers agree on the handler id.
     */
    private void registerScreenHandlers() {
        STORAGE_CONTROLLER_SCREEN_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                Identifier.of(MOD_ID, "storage_controller"),
                new ScreenHandlerType<>(StorageControllerScreenHandler::new, FeatureSet.empty())
        );

        // DEBUG: LOGGER.info("Screen handlers registered.");
    }

    // -------------------------------------------
    // Items & Tools
    // -------------------------------------------

    /**
     * Register tools and other standalone items.
     */
    private void registerTools() {
        LINKING_TOOL = Registry.register(
                Registries.ITEM,
                Identifier.of(MOD_ID, "linking_tool"),
                new LinkingToolItem(new Item.Settings()
                        .maxCount(1)
                        .registryKey(RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "linking_tool"))))
        );

        // DEBUG: LOGGER.info("Tools/items registered.");
    }

    // -------------------------------------------
    // Creative Tab
    // -------------------------------------------

    /**
     * Create and register a creative tab (item group) that contains the main mod items/blocks.
     */
    private void registerCreativeTab() {
        Registry.register(
                Registries.ITEM_GROUP,
                Identifier.of(MOD_ID, "smartsorter_group"),
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.smartsorter"))
                        // Icon uses an ItemStack supplier; we show the storage controller block as the icon.
                        .icon(() -> new ItemStack(STORAGE_CONTROLLER_BLOCK))
                        // Populate entries with the blocks/items we want in the creative tab.
                        .entries((displayContext, entries) -> {
                            entries.add(STORAGE_CONTROLLER_BLOCK);
                            entries.add(INTAKE_BLOCK);
                            entries.add(PROBE_BLOCK);
                            entries.add(LINKING_TOOL);
                        })
                        .build()
        );

        // DEBUG: LOGGER.info("Creative tab registered.");
    }

    // -------------------------------------------
    // Event handlers
    // -------------------------------------------

    /**
     * Register game event listeners (UseBlockCallback etc.). Keep these lightweight.
     */
    private void registerEventHandlers() {
        // Example: currently passes through. Place any custom click/use logic here.
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            // Return PASS so vanilla behavior proceeds; update here if you need custom right-click logic.
            return ActionResult.PASS;
        });

        // DEBUG: LOGGER.info("Event handlers registered.");
    }

    // -------------------------------------------
    // Network handlers (server-side)
    // -------------------------------------------

    /**
     * Register server-side handlers for client-to-server typed payloads.
     * This uses Fabric's ServerPlayNetworking typed receiver style matching the typed
     * payloads registered earlier via PayloadTypeRegistry.playC2S().
     */
    private void registerNetworkHandlers() {
        // Extraction request handler (client -> server).
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                // The handler receives a typed payload object and a context (method signature depends on Fabric version).
                (payload, context) -> {
                    // Run on server thread to safely interact with world/inventories.
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

        // Sync request handler (client asks server to send network update)
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

        // Deposit request handler (client wants to deposit cursor stack into controller)
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.DepositRequestPayload.ID,
                (payload, context) -> {
                    context.server().execute(() -> {
                        if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                            // Use the player's current cursor stack (what they are holding in the GUI)
                            var cursorStack = context.player().currentScreenHandler.getCursorStack();
                            if (!cursorStack.isEmpty()) {
                                handler.depositItem(cursorStack, payload.amount(), context.player());
                            }
                        }
                    });
                }
        );

        // DEBUG: LOGGER.info("Server network handlers registered.");
    }
}
