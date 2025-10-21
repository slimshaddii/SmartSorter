package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.ProcessProbeBlockEntity;
import net.shaddii.smartsorter.item.LinkingToolItem;

import org.jetbrains.annotations.Nullable;

public class ProcessProbeBlock extends BlockWithEntity {
    // ========================================
    // CONSTANTS
    // ========================================

    public static final EnumProperty<Direction> FACING = Properties.FACING;
    public static final MapCodec<ProcessProbeBlock> CODEC = createCodec(ProcessProbeBlock::new);

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public ProcessProbeBlock(AbstractBlock.Settings settings) {
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
        Direction side = ctx.getSide();
        return this.getDefaultState().with(FACING, side.getOpposite());
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);

        if (world.isClient() || !(world instanceof ServerWorld)) return;

        Direction facing = state.get(FACING);
        BlockPos targetPos = pos.offset(facing);
        BlockEntity targetEntity = world.getBlockEntity(targetPos);
        BlockState targetState = world.getBlockState(targetPos);

        // Determine machine type
        String machineType = null;
        boolean isValid = false;

        if (targetEntity instanceof AbstractFurnaceBlockEntity) {
            if (targetState.isOf(Blocks.FURNACE)) {
                machineType = "Furnace";
                isValid = true;
            } else if (targetState.isOf(Blocks.BLAST_FURNACE)) {
                machineType = "Blast Furnace";
                isValid = true;
            } else if (targetState.isOf(Blocks.SMOKER)) {
                machineType = "Smoker";
                isValid = true;
            }
        }

        // Send feedback to placer
        if (placer instanceof ServerPlayerEntity player) {
            if (isValid) {
                Text message = Text.literal("Linked to " + machineType).formatted(Formatting.GREEN);
                player.sendMessage(message, true);
            } else {
                Text message = Text.literal("No valid processing machine found").formatted(Formatting.RED);
                player.sendMessage(message, true);
            }
        }
    }

    // ========================================
    // BLOCK ENTITY
    // ========================================

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ProcessProbeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world == null || world.isClient()) return null;
        return type == SmartSorter.PROCESS_PROBE_BE_TYPE
                ? (world1, pos, state1, be) -> ProcessProbeBlockEntity.tick(world1, pos, state1, (ProcessProbeBlockEntity) be)
                : null;
    }

    // ========================================
    // INTERACTION
    // ========================================

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world == null) return ActionResult.PASS;
        if (world.isClient()) return ActionResult.SUCCESS;

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof ProcessProbeBlockEntity probe)) {
            return ActionResult.PASS;
        }

        ItemStack held = player.getMainHandStack();
        if (held.getItem() instanceof LinkingToolItem) {
            return ActionResult.PASS;
        }

        // Show status
        String machineType = probe.getMachineType();
        String status = probe.getStatusText();
        int processed = probe.getProcessedCount();
        boolean enabled = probe.isEnabled();

        Text message = Text.literal(
                String.format("§7Process Probe §8[§b%s§8]\n", state.get(FACING).asString()) +
                        String.format("§7Machine: §f%s\n", machineType) +
                        String.format("§7Status: %s\n", status) +
                        String.format("§7Processed: §f%d items\n", processed) +
                        String.format("§7Mode: %s", enabled ? "§aEnabled" : "§cDisabled")
        );

        player.sendMessage(message, false);
        return ActionResult.SUCCESS;
    }

    protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world != null && !world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ProcessProbeBlockEntity probe) {
                boolean powered = world.isReceivingRedstonePower(pos);
                probe.setEnabled(powered);
            }
        }
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
        if (state.isOf(currentState.getBlock())) return;

        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ProcessProbeBlockEntity probe) {
            probe.onRemoved();
        }

        super.onStateReplaced(state, world, pos, moved);
    }
    //?} else {
    /*@Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (state.isOf(newState.getBlock())) return;

        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ProcessProbeBlockEntity probe) {
            probe.onRemoved();
        }

        super.onStateReplaced(state, world, pos, newState, moved);
    }
    *///?}
}