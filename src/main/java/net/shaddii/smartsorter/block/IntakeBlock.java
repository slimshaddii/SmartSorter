package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;

public class IntakeBlock extends BlockWithEntity {
    public static final EnumProperty<Direction> FACING = Properties.FACING;
    public static final MapCodec<IntakeBlock> CODEC = createCodec(IntakeBlock::new);

    public IntakeBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        super.appendProperties(builder); // 🩵 Safe addition
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction playerFacing = ctx.getPlayerLookDirection();
        return this.getDefaultState().with(FACING, playerFacing);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new IntakeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient()) return null;
        return type == SmartSorter.INTAKE_BE_TYPE
                ? (world1, pos, state1, be) -> IntakeBlockEntity.tick(world1, pos, state1, (IntakeBlockEntity) be)
                : null;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) return ActionResult.SUCCESS;

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof IntakeBlockEntity intake)) {
            return ActionResult.PASS;
        }

        ItemStack heldStack = player.getMainHandStack();

        if (player.isSneaking() && !heldStack.isEmpty()) return ActionResult.PASS;
        if (!heldStack.isEmpty() && heldStack.getItem() instanceof LinkingToolItem) return ActionResult.PASS;

        String facing = state.get(FACING).asString();
        String bufferText = intake.getBuffer().isEmpty()
                ? "§8Empty"
                : "§e" + intake.getBuffer().getCount() + "x §f" + intake.getBuffer().getItem().getName(intake.getBuffer()).getString();
        String outputsText = intake.getOutputs().isEmpty() ? "§c0" : "§a" + intake.getOutputs().size();

        player.sendMessage(Text.literal(
                "§7Intake §8[§b" + facing + "§8] §7| Buffer: " + bufferText + " §7| Outputs: " + outputsText
        ), true);

        return ActionResult.SUCCESS;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    public void onRemove(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof IntakeBlockEntity intake && !intake.getBuffer().isEmpty()) {
                ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), intake.getBuffer());
            }
        }
    }
}
