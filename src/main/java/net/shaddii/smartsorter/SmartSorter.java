package net.shaddii.smartsorter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.block.IntakeBlock;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartSorter implements ModInitializer {
    public static final String MOD_ID = "smartsorter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // === Blocks and Items ===
    public static IntakeBlock INTAKE_BLOCK;
    public static OutputProbeBlock PROBE_BLOCK;
    public static Item INTAKE_ITEM;
    public static Item PROBE_ITEM;
    public static Item LINKING_TOOL;

    // === Block Entities ===
    public static BlockEntityType<IntakeBlockEntity> INTAKE_BE_TYPE;
    public static BlockEntityType<OutputProbeBlockEntity> PROBE_BE_TYPE;

    @Override
    public void onInitialize() {
        LOGGER.info("SmartSorter initializing...");

        // Register blocks first
        INTAKE_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "intake"), new IntakeBlock());
        PROBE_BLOCK = Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "output_probe"), new OutputProbeBlock());

        // Register block items
        INTAKE_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "intake"),
                new BlockItem(INTAKE_BLOCK, new Item.Settings()));
        PROBE_ITEM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "output_probe"),
                new BlockItem(PROBE_BLOCK, new Item.Settings()));

        // Register standard block entity types
        INTAKE_BE_TYPE = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(MOD_ID, "intake"),
                BlockEntityType.Builder.create(IntakeBlockEntity::new, INTAKE_BLOCK).build());
        PROBE_BE_TYPE = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(MOD_ID, "probe"),
                BlockEntityType.Builder.create(OutputProbeBlockEntity::new, PROBE_BLOCK).build());

        // Initialize TSS integration
        try {
            // LOGGER.info("[SmartSorter] Initializing TSS integration...");
            net.shaddii.smartsorter.integration.tomsstorage.TSSBlockEntityManager.initialize();
        } catch (Exception e) {
            LOGGER.error("[SmartSorter] Failed to initialize TSS integration: ", e);
        }

        // Register linking tool
        LINKING_TOOL = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "linking_tool"),
                new LinkingToolItem(new Item.Settings().maxCount(1)));

        // Register creative tab
        Registry.register(Registries.ITEM_GROUP, Identifier.of(MOD_ID, "smartsorter_group"),
                FabricItemGroup.builder()
                        .displayName(Text.translatable("itemGroup.smartsorter"))
                        .icon(() -> new ItemStack(PROBE_BLOCK))
                        .entries((displayContext, entries) -> {
                            entries.add(INTAKE_BLOCK);
                            entries.add(PROBE_BLOCK);
                            entries.add(LINKING_TOOL);
                        })
                        .build()
        );

        // Register debug handlers
        registerDebugHandlers();

        LOGGER.info("SmartSorter initialized successfully!");
    }

    private static void registerDebugHandlers() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient) return ActionResult.PASS;

            var held = player.getStackInHand(hand);
            if (!held.isEmpty() && held.getItem() == LINKING_TOOL) {
                return ActionResult.PASS; // handled by tool itself
            }

            BlockPos pos = hit.getBlockPos();
            BlockState state = world.getBlockState(pos);
            BlockEntity be = world.getBlockEntity(pos);

            // Block Debug - Sneak + right-click any block with empty hand
            /*if (held.isEmpty() && player.isSneaking()) {
                LOGGER.info("[SmartSorter] Debug - Player right-clicked {} at {}", state, pos);

                if (be != null) {
                    // Enhanced TSS debug
                    net.shaddii.smartsorter.integration.tomsstorage.TomsStorageIntegration.debugBlockEntity(be);

                    // Test TSS connector reference if this is an Output Probe
                    if (be instanceof OutputProbeBlockEntity probe) {
                        Object connectorRef = probe.getConnectorRef();

                        // Test if TSS would recognize this block entity
                        boolean tssRecognized = false;
                        try {
                            // Check TSS interface recognition
                            Class<?> tssInterface = Class.forName("com.tom.storagemod.inventory.IInventoryConnectorReference");
                            tssRecognized = tssInterface.isInstance(be);
                        } catch (Exception e) {
                        }

                        String status = (connectorRef != null ? "ACTIVE" : "NONE");
                        String recognition = tssRecognized ? "TSS_RECOGNIZED" : "TSS_NOT_RECOGNIZED";

                        player.sendMessage(Text.literal("SmartSorter: Probe TSS connector: " + status + " " + recognition), false);
                        LOGGER.info("[SmartSorter] Probe at {} - Connector: {}, TSS Recognition: {}", pos, status, recognition);
                    } else {
                        player.sendMessage(Text.literal("SmartSorter: Block debug info logged"), false);
                    }
                } else {
                    player.sendMessage(Text.literal("SmartSorter: No block entity at this position"), false);
                }
                return ActionResult.SUCCESS;
            }*/

            return ActionResult.PASS;
        });
    }
}