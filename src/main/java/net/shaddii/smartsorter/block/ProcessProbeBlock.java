package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.*;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
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



/**
 * Process Probe block.
 * - Faces a direction
 * - Shows status on use
 * - Works with LinkingToolItem for controller linking
 * - Listens to redstone neighbor updates
 * This is a refactor preserving your original behavior while cleaning up and
 * avoiding mapping-dependent annotations that produced compile errors previously.
 */
public class ProcessProbeBlock extends BlockWithEntity {
    public static final EnumProperty<Direction> FACING = Properties.FACING;
    public static final MapCodec<ProcessProbeBlock> CODEC = createCodec(ProcessProbeBlock::new);

    public ProcessProbeBlock(AbstractBlock.Settings settings) {
        super(settings);
        this.setDefaultState(this.getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }


    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);

        if (world.isClient() || !(world instanceof ServerWorld serverWorld)) return;

        // Check what the probe is facing
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

        // Send message to nearby players
        if (placer instanceof ServerPlayerEntity player) {
            if (isValid) {
                Text message = Text.literal("Linked to " + machineType)
                        .formatted(Formatting.GREEN);
                player.sendMessage(message, true); // true = action bar
            } else {
                Text message = Text.literal("No valid processing machine found")
                        .formatted(Formatting.RED);
                player.sendMessage(message, true);
            }
        }
    }

    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Face toward the block that was clicked (the furnace)
        Direction side = ctx.getSide();
        // The process block faces the opposite of the side clicked
        // (if you click the back of furnace, the probe faces toward it)
        return this.getDefaultState().with(FACING, side.getOpposite());
    }

    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ProcessProbeBlockEntity(pos, state);
    }

     // Ticker provider for server-side ticking of the block entity.
     // Returns null on client to avoid accidental client-side ticking.
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world == null || world.isClient()) return null;
        return type == SmartSorter.PROCESS_PROBE_BE_TYPE
                ? (world1, pos, state1, be) -> ProcessProbeBlockEntity.tick(world1, pos, state1, (ProcessProbeBlockEntity) be)
                : null;
    }

     // When player right-clicks: non-linking tool -> show status.
     // LinkingToolItem is handled separately by the item (we return PASS so it can run).
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world == null) return ActionResult.PASS;
        if (world.isClient()) return ActionResult.SUCCESS; // avoid duplicate handling - client shows nothing

        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof ProcessProbeBlockEntity probe)) {
            return ActionResult.PASS;
        }

        ItemStack held = player.getMainHandStack();
        if (held.getItem() instanceof LinkingToolItem) {
            // Let the linking tool handle linking logic
            return ActionResult.PASS;
        }

        // Show probe/controller status to player
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

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

     // Detect redstone changes - NO @Override due to mapping variations
    protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world != null && !world.isClient()) {
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof ProcessProbeBlockEntity probe) {
                boolean powered = world.isReceivingRedstonePower(pos);
                probe.setEnabled(powered);
            }
        }
    }

    /**
     * 1.21.10: onStateReplaced now takes ServerWorld instead of World
     */
    //? if >= 1.21.8 {
    
    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        // Check if block actually changed (compare with current state)
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
        // Check if block actually changed
        if (state.isOf(newState.getBlock())) return;

        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ProcessProbeBlockEntity probe) {
            probe.onRemoved();
        }

        super.onStateReplaced(state, world, pos, newState, moved);
    }
    *///?}
}
