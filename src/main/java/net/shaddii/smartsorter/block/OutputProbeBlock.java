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

        // === OTHER ITEM INTERACTIONS ===
        if (!heldStack.isEmpty()) {
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

        // === EMPTY HAND: Show current mode ===
        String modeName = probe.getModeName();
        String modeColor = switch (probe.mode) {
            case FILTER -> "§9";
            case ACCEPT_ALL -> "§a";
            case PRIORITY -> "§6";
        };

        player.sendMessage(Text.literal(modeColor + modeName + " §7| Use Linking Tool to change"), true);
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    // 1.21.9: onRemove method signature changed - removed @Override and super call
    public void onRemove(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof OutputProbeBlockEntity probe) {
                probe.onRemoved(world);
            }
            // Note: super.onRemove() doesn't exist in 1.21.9
        }
    }
}