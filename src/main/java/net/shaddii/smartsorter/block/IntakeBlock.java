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

/**
 * Intake Block - Pulls items from inventories and pushes them to linked output probes.
 * Features:
 * - Has a FACING property to determine which direction it pulls from
 * - Stores a single-slot buffer for items being transferred
 * - Can be linked to multiple output probes using the linking tool
 * - Shows status info when right-clicked with empty hand
 */
public class IntakeBlock extends BlockWithEntity {
    public static final EnumProperty<Direction> FACING = Properties.FACING;
    
    // CODEC for serialization - required by Minecraft's data generation and world save/load systems
    // Uses createCodec() which accepts a function that takes Settings and returns a block instance
    public static final MapCodec<IntakeBlock> CODEC = createCodec(IntakeBlock::new);

    public IntakeBlock(AbstractBlock.Settings settings) {
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
        return new IntakeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient()) return null;
        return type == SmartSorter.INTAKE_BE_TYPE
                ? (world1, pos, state1, be) -> IntakeBlockEntity.tick(world1, pos, state1, (IntakeBlockEntity) be)
                : null;
    }

    // 1.21.9: onUse method signature changed
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) return ActionResult.SUCCESS;

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof IntakeBlockEntity intake)) {
            return ActionResult.PASS;
        }

        ItemStack heldStack = player.getMainHandStack();

        // Sneak-right-click with item: allow placement
        if (player.isSneaking() && !heldStack.isEmpty()) {
            return ActionResult.PASS;
        }

        // Linking Tool: let it handle its own logic
        if (!heldStack.isEmpty() && heldStack.getItem() instanceof LinkingToolItem) {
            return ActionResult.PASS;
        }

        // === EMPTY HAND INTERACTION ===
        String facing = state.get(FACING).asString();
        String bufferText;
        if (intake.getBuffer().isEmpty()) {
            bufferText = "§8Empty";
        } else {
            ItemStack buffer = intake.getBuffer();
            bufferText = "§e" + buffer.getCount() + "x §f" + buffer.getItem().getName(buffer).getString();
        }

        int outputs = intake.getOutputs().size();
        String outputsText = outputs > 0 ? "§a" + outputs : "§c0";

        player.sendMessage(
                Text.literal("§7Intake §8[§b" + facing + "§8] §7| Buffer: " + bufferText + " §7| Outputs: " + outputsText),
                true
        );

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
            if (blockEntity instanceof IntakeBlockEntity intake) {
                if (!intake.getBuffer().isEmpty()) {
                    ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), intake.getBuffer());
                }
            }
            // Note: super.onRemove() doesn't exist in 1.21.9
        }
    }
}
