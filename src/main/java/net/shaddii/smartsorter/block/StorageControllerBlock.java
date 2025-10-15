package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
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
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter; // DEBUG: For debug logging
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

/**
 * Storage Controller Block - Central hub that manages a network of linked inventories.
 * Features:
 * - Opens a GUI when right-clicked (shows all items across linked inventories)
 * - Shift+right-click shows capacity info (free slots, total slots, inventory count)
 * - Can be linked to chests/inventories using the linking tool
 * - Aggregates items from all linked inventories for easy access
 * - Supports searching, extracting, and depositing items
 */
public class StorageControllerBlock extends BlockWithEntity {
    // CODEC for serialization - required by Minecraft's data generation and world save/load systems
    // Uses createCodec() which accepts a function that takes Settings and returns a block instance
    public static final MapCodec<StorageControllerBlock> CODEC = createCodec(StorageControllerBlock::new);

    public StorageControllerBlock(Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new StorageControllerBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    // 1.21.9: onUse method signature changed
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        // DEBUG: SmartSorter.LOGGER.info("Storage Controller clicked!");

        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof StorageControllerBlockEntity controller)) {
            return ActionResult.PASS;
        }

        ItemStack heldItem = player.getMainHandStack();

        // Let the linking tool handle its own interactions
        if (heldItem.getItem() instanceof net.shaddii.smartsorter.item.LinkingToolItem) {
            return ActionResult.PASS;
        }

        // SHIFT + RIGHT CLICK — Show info
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

        // NORMAL RIGHT CLICK — open GUI
        NamedScreenHandlerFactory screenHandlerFactory = state.createScreenHandlerFactory(world, pos);
        if (screenHandlerFactory != null) {
            player.openHandledScreen(screenHandlerFactory);
        }

        return ActionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient() ? null : validateTicker(type, SmartSorter.STORAGE_CONTROLLER_BE_TYPE, StorageControllerBlockEntity::tick);
    }

    // 1.21.9: onRemove method signature changed - removed @Override and super call
    /**
     * 1.21.10: onStateReplaced signature - takes ServerWorld, no newState parameter
     */
    /**
     * 1.21.10: onStateReplaced now takes ServerWorld instead of World
     */
    //? if >= 1.21.8 {
    
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        // Check if block actually changed
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
        // Check if block actually changed
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
}