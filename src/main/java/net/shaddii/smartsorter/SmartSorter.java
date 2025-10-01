package net.shaddii.smartsorter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
// import net.fabricmc.fabric.api.event.player.UseBlockCallback;   // DEBUG ONLY
// import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;   // DEBUG ONLY
// import net.minecraft.block.BlockState;                          // DEBUG ONLY
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
// import net.minecraft.util.ActionResult;                         // DEBUG ONLY
import net.minecraft.util.Identifier;
// import net.minecraft.util.math.BlockPos;                        // DEBUG ONLY
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartSorter implements ModInitializer {
    public static final String MODID = "smartsorter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    // === Blocks and Items ===
    public static final net.shaddii.smartsorter.block.IntakeBlock INTAKE_BLOCK = new net.shaddii.smartsorter.block.IntakeBlock();
    public static final OutputProbeBlock PROBE_BLOCK = new OutputProbeBlock();
    public static final Item LINKING_TOOL = new net.shaddii.smartsorter.item.LinkingToolItem(new Item.Settings().maxCount(1));

    // === Block Entities ===
    public static net.minecraft.block.entity.BlockEntityType<IntakeBlockEntity> INTAKE_BE_TYPE;
    public static net.minecraft.block.entity.BlockEntityType<OutputProbeBlockEntity> PROBE_BE_TYPE;

    // === Custom Creative Tab ===
    public static final ItemGroup SMARTSORTER_GROUP = Registry.register(
            Registries.ITEM_GROUP,
            Identifier.of(MODID, "smartsorter_group"),
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

    // Helper for registry IDs
    private static Identifier id(String path) {
        return Identifier.of(MODID, path);
    }

    @Override
    public void onInitialize() {
        // === Register Blocks + Items ===
        Registry.register(Registries.BLOCK, id("intake"), INTAKE_BLOCK);
        Registry.register(Registries.ITEM,  id("intake"), new BlockItem(INTAKE_BLOCK, new Item.Settings()));
        Registry.register(Registries.BLOCK, id("output_probe"), PROBE_BLOCK);
        Registry.register(Registries.ITEM,  id("output_probe"), new BlockItem(PROBE_BLOCK, new Item.Settings()));
        Registry.register(Registries.ITEM,  id("linking_tool"), LINKING_TOOL);

        // === Register Block Entities ===
        INTAKE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("intake"),
                net.minecraft.block.entity.BlockEntityType.Builder.create(IntakeBlockEntity::new, INTAKE_BLOCK).build()
        );
        PROBE_BE_TYPE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE, id("output_probe"),
                net.minecraft.block.entity.BlockEntityType.Builder.create(OutputProbeBlockEntity::new, PROBE_BLOCK).build()
        );

        /* ===================== DEBUG MESSAGES (COMMENTED OUT) =====================
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient) return ActionResult.PASS;

            var held = player.getStackInHand(hand);
            if (!held.isEmpty() && held.getItem() == LINKING_TOOL) {
                return ActionResult.PASS; // handled by tool itself
            }

            BlockPos pos = hit.getBlockPos();
            BlockState state = world.getBlockState(pos);

            if (state.getBlock() instanceof IntakeBlock) {
                var be = world.getBlockEntity(pos);
                if (be instanceof IntakeBlockEntity intake) {
                    var buf = intake.getBuffer();
                    StringBuilder sb = new StringBuilder("Outputs=");
                    for (int i = 0; i < intake.getOutputs().size(); i++) {
                        sb.append(intake.getOutputs().get(i));
                        if (i < intake.getOutputs().size() - 1) sb.append(", ");
                    }
                    String msg = sb + " | Buffer=" + (buf.isEmpty() ? "empty" : buf.getCount() + "x " + buf.getItem())
                            + " | Facing=" + state.get(IntakeBlock.FACING);
                    player.sendMessage(Text.literal("SmartSorter: " + msg), false);
                    LOGGER.info("[SmartSorter] Intake {} -> {}", pos, msg);
                    return ActionResult.SUCCESS;
                }
            }

            if (state.getBlock() instanceof OutputProbeBlock) {
                var be = world.getBlockEntity(pos);
                if (be instanceof OutputProbeBlockEntity probe) {
                    if (held.isEmpty()) {
                        player.sendMessage(Text.literal("SmartSorter: Hold an item to test this probe."), false);
                        return ActionResult.SUCCESS;
                    }
                    boolean ok = probe.accepts(ItemVariant.of(held));
                    String msg = "facing=" + state.get(OutputProbeBlock.FACING) + " would " + (ok ? "ACCEPT " : "REJECT ") + held.getItem();
                    player.sendMessage(Text.literal("SmartSorter: Probe " + msg), false);
                    LOGGER.info("[SmartSorter] Probe {} {}", pos, msg);
                    return ActionResult.SUCCESS;
                }
            }

            return ActionResult.PASS;
        });
        // ===================== END DEBUG MESSAGES ===================== */
    }
}