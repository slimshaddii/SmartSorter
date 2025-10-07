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
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;

public class OutputProbeBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = Properties.FACING;
    public static final MapCodec<OutputProbeBlock> CODEC = createCodec(OutputProbeBlock::new);

    public OutputProbeBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    public OutputProbeBlock() {
        this(AbstractBlock.Settings.create().strength(0.6F).requiresTool().nonOpaque());
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
        return getDefaultState().with(FACING, playerFacing);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new OutputProbeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return type == SmartSorter.PROBE_BE_TYPE ?
                (world1, pos, state1, be) -> OutputProbeBlockEntity.tick(world1, pos, state1, (OutputProbeBlockEntity) be) : null;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof OutputProbeBlockEntity probe)) {
            return ActionResult.PASS;
        }

        ItemStack heldStack = player.getStackInHand(Hand.MAIN_HAND);
        boolean hasLinkingTool = heldStack.getItem() instanceof LinkingToolItem;

        // === LINKING TOOL: Let LinkingToolItem handle everything ===
        if (hasLinkingTool) {
            return ActionResult.PASS; // Let LinkingToolItem.useOnBlock() handle it
        }

        // === OTHER ITEM INTERACTIONS ===
        if (!heldStack.isEmpty()) {
            // Shift + Right-click with item = Allow block placement
            if (player.isSneaking()) {
                return ActionResult.PASS;
            }

            // Right-click with item = Test if accepted
            ItemVariant heldVariant = ItemVariant.of(heldStack);
            boolean accepted = probe.accepts(heldVariant);
            String itemName = heldStack.getItem().getName().getString();
            String status = accepted ? "§aAccepted" : "§cRejected";

            // ACTION BAR
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

        // ACTION BAR
        player.sendMessage(Text.literal(modeColor + modeName + " §7| Use Linking Tool to change"), true);
        return ActionResult.SUCCESS;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (state.getBlock() != newState.getBlock()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof OutputProbeBlockEntity probe) {
                // NEW: Remove this probe from any controllers
                probe.onRemoved(world);
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }
    }
}