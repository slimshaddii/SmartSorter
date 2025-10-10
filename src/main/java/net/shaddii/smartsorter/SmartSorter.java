package net.shaddii.smartsorter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import net.shaddii.smartsorter.block.*;
import net.shaddii.smartsorter.blockentity.*;
import net.shaddii.smartsorter.item.LinkingToolItem;
import net.shaddii.smartsorter.network.*;
import net.shaddii.smartsorter.screen.StorageControllerScreenHandler;

public class SmartSorter implements ModInitializer {
    public static final String MOD_ID = "smartsorter";

    // === Blocks ===
    public static Block INTAKE_BLOCK;
    public static Block PROBE_BLOCK;
    public static Block STORAGE_CONTROLLER_BLOCK;

    // === Items ===
    public static Item INTAKE_ITEM;
    public static Item PROBE_ITEM;
    public static Item STORAGE_CONTROLLER_ITEM;
    public static Item LINKING_TOOL;

    // === Block Entities ===
    public static BlockEntityType<IntakeBlockEntity> INTAKE_BE_TYPE;
    public static BlockEntityType<OutputProbeBlockEntity> PROBE_BE_TYPE;
    public static BlockEntityType<StorageControllerBlockEntity> STORAGE_CONTROLLER_BE_TYPE;

    // === Screen Handlers ===
    public static ScreenHandlerType<StorageControllerScreenHandler> STORAGE_CONTROLLER_SCREEN_HANDLER;

    @Override
    public void onInitialize() {
        registerBlocks();
        registerItems();
        registerBlockEntities();
        registerScreens();
        registerTools();
        registerCreativeTab();
        registerNetworkPayloads();
        registerNetworkHandlers();
        registerEvents();
    }

    // ------------------------------------------------------
    // Blocks - FIXED VERSION
    // ------------------------------------------------------
    private void registerBlocks() {
        INTAKE_BLOCK = registerBlock("intake",
                new IntakeBlock(AbstractBlock.Settings.create()
                        .registryKey(RegistryKey.of(RegistryKeys.BLOCK, id("intake")))
                        .strength(0.6F).nonOpaque()));

        PROBE_BLOCK = registerBlock("output_probe",
                new OutputProbeBlock(AbstractBlock.Settings.create()
                        .registryKey(RegistryKey.of(RegistryKeys.BLOCK, id("output_probe")))
                        .strength(0.6F).nonOpaque()));

        STORAGE_CONTROLLER_BLOCK = registerBlock("storage_controller",
                new StorageControllerBlock(AbstractBlock.Settings.create()
                        .registryKey(RegistryKey.of(RegistryKeys.BLOCK, id("storage_controller")))
                        .strength(0.6F)));
    }

    private Block registerBlock(String name, Block block) {
        return Registry.register(Registries.BLOCK, id(name), block);
    }

    // ------------------------------------------------------
    // Block Items - FIXED VERSION
    // ------------------------------------------------------
    private void registerItems() {
        INTAKE_ITEM = registerBlockItem("intake", INTAKE_BLOCK);
        PROBE_ITEM = registerBlockItem("output_probe", PROBE_BLOCK);
        STORAGE_CONTROLLER_ITEM = registerBlockItem("storage_controller", STORAGE_CONTROLLER_BLOCK);
    }

    private Item registerBlockItem(String name, Block block) {
        // Create item settings with registry key pre-set
        Item.Settings settings = new Item.Settings()
                .registryKey(RegistryKey.of(RegistryKeys.ITEM, id(name)));

        return Registry.register(Registries.ITEM, id(name), new BlockItem(block, settings));
    }

    // ------------------------------------------------------
    // Block Entities
    // ------------------------------------------------------
    private void registerBlockEntities() {
        INTAKE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("intake"),
                FabricBlockEntityTypeBuilder.create(IntakeBlockEntity::new, INTAKE_BLOCK).build());
        PROBE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("output_probe"),
                FabricBlockEntityTypeBuilder.create(OutputProbeBlockEntity::new, PROBE_BLOCK).build());
        STORAGE_CONTROLLER_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("storage_controller"),
                FabricBlockEntityTypeBuilder.create(StorageControllerBlockEntity::new, STORAGE_CONTROLLER_BLOCK).build());
    }

    // ------------------------------------------------------
    // Screens
    // ------------------------------------------------------
    private void registerScreens() {
        STORAGE_CONTROLLER_SCREEN_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER, id("storage_controller"),
                new ScreenHandlerType<>(StorageControllerScreenHandler::new, null));
    }

    // ------------------------------------------------------
    // Tools - FIXED VERSION
    // ------------------------------------------------------
    private void registerTools() {
        // Create settings with registry key pre-set
        Item.Settings settings = new Item.Settings()
                .registryKey(RegistryKey.of(RegistryKeys.ITEM, id("linking_tool")))
                .maxCount(1);

        LINKING_TOOL = Registry.register(
                Registries.ITEM, id("linking_tool"),
                new LinkingToolItem(settings));
    }

    // ------------------------------------------------------
    // Creative Tab
    // ------------------------------------------------------
    private void registerCreativeTab() {
        Registry.register(Registries.ITEM_GROUP, id("smartsorter_group"),
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.smartsorter"))
                        .icon(() -> new ItemStack(STORAGE_CONTROLLER_ITEM))
                        .entries((ctx, entries) -> {
                            entries.add(STORAGE_CONTROLLER_ITEM);
                            entries.add(INTAKE_ITEM);
                            entries.add(PROBE_ITEM);
                            entries.add(LINKING_TOOL);
                        })
                        .build());
    }

    // ------------------------------------------------------
    // Networking
    // ------------------------------------------------------
    private void registerNetworkPayloads() {
        PayloadTypeRegistry.playS2C().register(
                StorageControllerSyncPacket.SyncPayload.ID_PAYLOAD,
                StorageControllerSyncPacket.SyncPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                StorageControllerScreenHandler.ExtractionRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StorageControllerScreenHandler.SyncRequestPayload.ID,
                StorageControllerScreenHandler.SyncRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(StorageControllerScreenHandler.DepositRequestPayload.ID,
                StorageControllerScreenHandler.DepositRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SortModeChangePayload.ID, SortModeChangePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(FilterCategoryChangePayload.ID, FilterCategoryChangePayload.CODEC);
    }

    private void registerNetworkHandlers() {
        // Extraction
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.ExtractionRequestPayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler)
                        handler.extractItem(payload.variant(), payload.amount(), payload.toInventory(), context.player());
                }));

        // Sync
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.SyncRequestPayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler)
                        handler.sendNetworkUpdate(context.player());
                }));

        // Deposit
        ServerPlayNetworking.registerGlobalReceiver(
                StorageControllerScreenHandler.DepositRequestPayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler) {
                        var cursor = context.player().currentScreenHandler.getCursorStack();
                        if (!cursor.isEmpty()) handler.depositItem(cursor, payload.amount(), context.player());
                    }
                }));

        // Sort mode
        ServerPlayNetworking.registerGlobalReceiver(
                SortModeChangePayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler)
                        handler.setSortMode(payload.getSortMode());
                }));

        // Filter category
        ServerPlayNetworking.registerGlobalReceiver(
                FilterCategoryChangePayload.ID,
                (payload, context) -> context.server().execute(() -> {
                    if (context.player().currentScreenHandler instanceof StorageControllerScreenHandler handler)
                        handler.setFilterCategory(payload.getCategory());
                }));
    }

    // ------------------------------------------------------
    // Events
    // ------------------------------------------------------
    private void registerEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> ActionResult.PASS);
    }

    // ------------------------------------------------------
    // Utility
    // ------------------------------------------------------
    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);  // Using Identifier.of() instead of new Identifier()
    }
}