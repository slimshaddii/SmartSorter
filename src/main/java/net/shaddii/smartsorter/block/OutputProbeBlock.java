package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;
import net.shaddii.smartsorter.util.ChestConfig;

/**
 * Output Probe Block - Receives items from intake blocks and outputs them to inventories.
 * Features:
 * - Has a FACING property to determine output direction
 * - Three modes: FILTER (whitelist), ACCEPT_ALL, PRIORITY (accepts if no other probe wants it)
 * - Can be configured with the linking tool to change modes and set filters
 * - Shows current mode when right-clicked with empty hand
 * - Tests items when right-clicked to show if they would be accepted
 */
public class OutputProbeBlock extends BlockWithEntity {
    public static final EnumProperty<Direction> FACING = Properties.FACING;

    // CODEC for serialization - required by Minecraft's data generation and world save/load systems
    // Uses createCodec() which accepts a function that takes Settings and returns a block instance
    public static final MapCodec<OutputProbeBlock> CODEC = createCodec(OutputProbeBlock::new);

    public OutputProbeBlock(AbstractBlock.Settings settings) {
        super(settings);
        // Set default state: facing north when placed
        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction playerFacing = ctx.getPlayerLookDirection();
        return this.getDefaultState().with(FACING, playerFacing);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new OutputProbeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return type == SmartSorter.PROBE_BE_TYPE
                ? (world1, pos, state1, be) -> OutputProbeBlockEntity.tick(world1, pos, state1, (OutputProbeBlockEntity) be)
                : null;
    }

    // 1.21.9: onUse method signature changed
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) return ActionResult.SUCCESS;

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof OutputProbeBlockEntity probe)) {
            return ActionResult.PASS;
        }

        ItemStack heldStack = player.getMainHandStack();
        boolean hasLinkingTool = heldStack.getItem() instanceof LinkingToolItem;

        // === LINKING TOOL: Let LinkingToolItem handle everything ===
        if (hasLinkingTool) {
            return ActionResult.PASS;
        }

        // === EMPTY HAND: Show chest config info ===
        if (heldStack.isEmpty()) {
            ChestConfig chestConfig = probe.getChestConfig();

            if (chestConfig == null) {
                player.sendMessage(Text.literal("§7No chest configuration"), true);
                player.sendMessage(Text.literal("§8Link to a controller to configure"), true);
                return ActionResult.SUCCESS;
            }

            // Display chest config
            player.sendMessage(Text.literal("§6═══ Output Probe ═══"), true);

            // Chest location or name
            String chestName = chestConfig.customName.isEmpty()
                    ? String.format("§7[%d, %d, %d]",
                    chestConfig.position.getX(),
                    chestConfig.position.getY(),
                    chestConfig.position.getZ())
                    : "§f" + chestConfig.customName;
            player.sendMessage(Text.literal("§7Target: " + chestName), true);

            // Filter mode
            player.sendMessage(Text.literal("§7Mode: §e" + chestConfig.filterMode.getDisplayName()), true);

            // Category filter (if enabled)
            if (chestConfig.filterMode == ChestConfig.FilterMode.CATEGORY ||
                    chestConfig.filterMode == ChestConfig.FilterMode.CATEGORY_AND_PRIORITY) {
                player.sendMessage(Text.literal("§7Filter: §b" + chestConfig.filterCategory.getDisplayName()), true);
            }

            // Priority
            String priorityDisplay = String.valueOf(chestConfig.priority);
            String priorityColor = chestConfig.priority <= 3 ? "§a"
                    : chestConfig.priority <= 7 ? "§e" : "§c";
            player.sendMessage(Text.literal("§7Priority: " + priorityColor + priorityDisplay +
                    " §8(Effective: " + chestConfig.hiddenPriority + ")"), true);


            player.sendMessage(Text.literal("§8Configure via Controller GUI"), true);

            return ActionResult.SUCCESS;
        }

        // === OTHER ITEM: Test if accepted ===
        if (player.isSneaking()) {
            return ActionResult.PASS;
        }

        ItemVariant heldVariant = ItemVariant.of(heldStack);
        boolean accepted = probe.accepts(heldVariant);
        String itemName = heldStack.getItem().getName(heldStack).getString();
        String status = accepted ? "§aAccepted" : "§cRejected";

        player.sendMessage(Text.literal(itemName + ": " + status), true);
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    //? if >=1.21.8 {
@Override
protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
    BlockEntity blockEntity = world.getBlockEntity(pos);
    if (blockEntity instanceof OutputProbeBlockEntity probe) {
        probe.onRemoved(world);
        }
            super.onStateReplaced(state, world, pos, moved);
        }
    //?} else {
    /*@Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof OutputProbeBlockEntity probe && world instanceof ServerWorld serverWorld) {
            probe.onRemoved(serverWorld);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
    }
    *///?}
}