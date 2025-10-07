package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

public class StorageControllerBlock extends BlockWithEntity {
    public static final MapCodec<StorageControllerBlock> CODEC = createCodec(StorageControllerBlock::new);

    public StorageControllerBlock(Settings settings) {
        super(settings);
    }

    public StorageControllerBlock() {
        this(Settings.create().strength(0.6F).requiresTool());
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

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof StorageControllerBlockEntity controller)) {
            return ActionResult.PASS;
        }

        ItemStack held = player.getMainHandStack();

        // EXCEPTION: Linking Tool has its own actions
        if (held.getItem() instanceof net.shaddii.smartsorter.item.LinkingToolItem) {
            // Let linking tool handle its interactions
            return ActionResult.PASS;
        }

        // SHIFT + RIGHT-CLICK: Show capacity info
        if (player.isSneaking()) {
            int free = controller.calculateTotalFreeSlots();
            int total = controller.calculateTotalCapacity();
            int inventories = controller.getLinkedInventoryCount();

            // Calculate percentage for color only (not shown to player)
            float percentFree = total > 0 ? (free / (float) total) * 100 : 0;

            // Color based on capacity
            String color;
            if (percentFree > 50) {
                color = "§a"; // Green - plenty of space
            } else if (percentFree > 25) {
                color = "§e"; // Yellow - getting full
            } else if (percentFree > 10) {
                color = "§6"; // Gold - nearly full
            } else {
                color = "§c"; // Red - critical
            }

            // ACTION BAR message (with percentage)
            player.sendMessage(Text.literal(
                    String.format("%sFree: §f%d§7/§f%d §8(§f%.0f%%§8) §7in §f%d §7inventories",
                            color, free, total, percentFree, inventories)
            ), true);

            return ActionResult.SUCCESS;
        }

        // NORMAL RIGHT-CLICK: Open GUI
        NamedScreenHandlerFactory screenHandlerFactory = state.createScreenHandlerFactory(world, pos);
        if (screenHandlerFactory != null) {
            player.openHandledScreen(screenHandlerFactory);
        }

        return ActionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : validateTicker(type, SmartSorter.STORAGE_CONTROLLER_BE_TYPE, StorageControllerBlockEntity::tick);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (state.getBlock() != newState.getBlock()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof StorageControllerBlockEntity controller) {
                // Drop items from inventory
                ItemScatterer.spawn(world, pos, controller);

                // NEW: Clear links (prevent orphaned references)
                controller.onRemoved();

                world.updateComparators(pos, this);
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }
    }
}