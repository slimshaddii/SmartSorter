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

    // Face the direction the player is looking when placing
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

        // If player is holding an item
        if (!heldStack.isEmpty()) {
            // Shift + Right-click with item = allow block placement next to this block
            if (player.isSneaking()) {
                return ActionResult.PASS;
            }

            // Right-click with item = check if it's accepted
            ItemVariant heldVariant = ItemVariant.of(heldStack);
            boolean accepted = probe.accepts(heldVariant);
            String itemName = heldStack.getItem().getName().getString();
            String status = accepted ? "Accepted" : "Rejected";

            // Send message to action bar (above hotbar)
            player.sendMessage(Text.literal(itemName + ": " + status), true);
            return ActionResult.SUCCESS;
        }

        // Empty hand - no special behavior
        return ActionResult.PASS;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }
}