package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
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
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;
import net.shaddii.smartsorter.util.ChestConfig;
import org.jetbrains.annotations.Nullable;

public class OutputProbeBlock extends BlockWithEntity {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final EnumProperty<Direction> FACING = Properties.FACING;
    public static final MapCodec<OutputProbeBlock> CODEC = createCodec(OutputProbeBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public OutputProbeBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    // ========================================
    // BLOCK SETUP
    // ========================================

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

    // ========================================
    // BLOCK ENTITY
    // ========================================

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

    // ========================================
    // INTERACTION
    // ========================================

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);

        // Initialize chest config
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof OutputProbeBlockEntity probe) {
            probe.onPlaced(world);
        }
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) return ActionResult.SUCCESS;

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof OutputProbeBlockEntity probe)) {
            return ActionResult.PASS;
        }

        ItemStack heldStack = player.getMainHandStack();
        boolean hasLinkingTool = heldStack.getItem() instanceof LinkingToolItem;

        if (hasLinkingTool) {
            return ActionResult.PASS; // Let linking tool handle it
        }

        // Shift-click with empty hand to cycle mode
        if (player.isSneaking() && heldStack.isEmpty()) {
            probe.cycleMode();

            String modeName = probe.getModeName();
            String modeColor = switch (probe.mode) {
                case FILTER -> "§9";
                case ACCEPT_ALL -> "§a";
                case PRIORITY -> "§6";
            };

            player.sendMessage(Text.literal(modeColor + "Mode: " + modeName), true);
            return ActionResult.SUCCESS;
        }

        // Normal click with empty hand opens the GUI
        if (!player.isSneaking() && heldStack.isEmpty()) {
            // ALWAYS open GUI, no controller check needed
            player.openHandledScreen(probe);
            return ActionResult.SUCCESS;
        }

        // Using an item on the probe tests if it's accepted
        if (!player.isSneaking() && !heldStack.isEmpty()) {
            ItemVariant heldVariant = ItemVariant.of(heldStack);
            boolean accepted = probe.accepts(heldVariant);
            String itemName = heldStack.getItem().getName(heldStack).getString();
            String status = accepted ? "§aAccepted" : "§cRejected";

            player.sendMessage(Text.literal(itemName + ": " + status), true);
            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    // ========================================
    // RENDERING
    // ========================================

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    // ========================================
    // CLEANUP
    // ========================================

    @Override
    public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        // Get block entity BEFORE calling super (which removes it)
        if (!world.isClient()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            if (blockEntity instanceof OutputProbeBlockEntity probe) {
                probe.onRemoved(world);
            }
        }

        return super.onBreak(world, pos, state, player);
    }

    // Keep onStateReplaced for other cases (like explosions, pistons, etc.)
//? if >=1.21.8 {
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        // Only handle if not moved by piston
        if (!moved && !world.isClient()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            if (blockEntity instanceof OutputProbeBlockEntity probe) {
                probe.onRemoved(world);
            }
        }

        super.onStateReplaced(state, world, pos, moved);
    }
    //?} else {
    /*@Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        // Only run if actually changing to a different block type
        if (!state.isOf(newState.getBlock()) && !world.isClient()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);

            if (blockEntity instanceof OutputProbeBlockEntity probe && world instanceof ServerWorld serverWorld) {
                probe.onRemoved(serverWorld);
            }
        }

        super.onStateReplaced(state, world, pos, newState, moved);
    }
    *///?}

    // ========================================
    // COMPATIBILITY
    // ========================================

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        // Define the actual shape of your block for proper culling
        // This example uses full cube, adjust if your blocks are smaller
        return VoxelShapes.fullCube();
    }
}