package net.shaddii.smartsorter.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
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
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;

public class IntakeBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = Properties.FACING; // direction the front faces
    public static final MapCodec<IntakeBlock> CODEC = createCodec(IntakeBlock::new);

    public IntakeBlock(AbstractBlock.Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }
    public IntakeBlock() {
        this(AbstractBlock.Settings.create().strength(0.6F).requiresTool());
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() { return CODEC; }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    // Face the direction the player is looking when placing
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction playerFacing = ctx.getPlayerLookDirection();
        return getDefaultState().with(FACING, playerFacing);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return new IntakeBlockEntity(pos, state); }

    @Override
    public BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : validateTicker(type, SmartSorter.INTAKE_BE_TYPE, IntakeBlockEntity::tick);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;

        var held = player.getMainHandStack();
        var be = world.getBlockEntity(pos);

        if (!(be instanceof IntakeBlockEntity intake)) {
            return ActionResult.PASS;
        }

        // Allow block placement
        if (player.isSneaking() && !held.isEmpty()) {
            return ActionResult.PASS;
        }

        // Skip for linking tool
        if (!held.isEmpty() && held.getItem() instanceof net.shaddii.smartsorter.item.LinkingToolItem) {
            return ActionResult.PASS;
        }

        // Build colored status message
        String facing = state.get(FACING).getName();
        String buffer;

        if (intake.getBuffer().isEmpty()) {
            buffer = "§8Empty"; // Dark gray for empty
        } else {
            ItemStack bufferStack = intake.getBuffer();
            String itemName = bufferStack.getItem().getName().getString();
            buffer = "§e" + bufferStack.getCount() + "x §f" + itemName; // Yellow count + white item name
        }

        int outputCount = intake.getOutputs().size();
        String outputs = outputCount > 0 ? "§a" + outputCount : "§c0"; // Green if has outputs, red if none

        // Colorful action bar message
        player.sendMessage(Text.literal(
                "§7Intake §8[§b" + facing + "§8] §7| Buffer: " + buffer + " §7| Outputs: " + outputs
        ), true);

        return ActionResult.SUCCESS;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (state.getBlock() != newState.getBlock()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity instanceof IntakeBlockEntity intake) {
                // Drop any buffered items
                if (!intake.getBuffer().isEmpty()) {
                    ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), intake.getBuffer());
                }
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }
    }
}