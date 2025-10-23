package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;
import org.jetbrains.annotations.Nullable;

public class StorageControllerBlock extends BlockWithEntity {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final MapCodec<StorageControllerBlock> CODEC = createCodec(StorageControllerBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public StorageControllerBlock(Settings settings) {
        super(settings);
    }

    // ========================================
    // BLOCK SETUP
    // ========================================

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    // ========================================
    // BLOCK ENTITY
    // ========================================

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StorageControllerBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient() ? null : validateTicker(type, SmartSorter.STORAGE_CONTROLLER_BE_TYPE, StorageControllerBlockEntity::tick);
    }

    // ========================================
    // INTERACTION
    // ========================================

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof StorageControllerBlockEntity controller)) {
            return ActionResult.PASS;
        }

        ItemStack heldItem = player.getMainHandStack();

        // Let linking tool handle its own logic
        if (heldItem.getItem() instanceof net.shaddii.smartsorter.item.LinkingToolItem) {
            return ActionResult.PASS;
        }

        // Shift-click: show capacity info
        if (player.isSneaking()) {
            int free = controller.calculateTotalFreeSlots();
            int total = controller.calculateTotalCapacity();
            int inventories = controller.getLinkedInventoryCount();

            float percentFree = total > 0 ? (free / (float) total) * 100 : 0;
            String color = percentFree > 50 ? "§a" : percentFree > 25 ? "§e" : percentFree > 10 ? "§6" : "§c";

            player.sendMessage(
                    Text.literal(String.format(
                            "%sFree: §f%d§7/§f%d §8(§f%.0f%%§8) §7in §f%d §7inventories",
                            color, free, total, percentFree, inventories
                    )),
                    true
            );

            return ActionResult.SUCCESS;
        }

        // Normal click: open GUI
        NamedScreenHandlerFactory screenHandlerFactory = state.createScreenHandlerFactory(world, pos);
        if (screenHandlerFactory != null) {
            player.openHandledScreen(screenHandlerFactory);
        }

        return ActionResult.SUCCESS;
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

    //? if >= 1.21.8 {
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        BlockState currentState = world.getBlockState(pos);
        if (state.getBlock() != currentState.getBlock()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof StorageControllerBlockEntity controller) {
                ItemScatterer.spawn(world, pos, controller);
                controller.onRemoved();
                world.updateComparators(pos, this);
            }
        }
        super.onStateReplaced(state, world, pos, moved);
    }
    //?} else {
    /*@Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof StorageControllerBlockEntity controller) {
                ItemScatterer.spawn(world, pos, controller);
                controller.onRemoved();
                world.updateComparators(pos, this);
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }
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