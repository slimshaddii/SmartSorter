package net.shaddii.smartsorter.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LinkingToolItem extends Item {
    // Simple per-player memory for origin; avoids item NBT in 1.21
    private static final Map<UUID, BlockPos> PENDING = new HashMap<>();

    public LinkingToolItem(Settings settings) { super(settings); }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        if (world.isClient) return ActionResult.SUCCESS;
        PlayerEntity player = ctx.getPlayer();
        if (player == null) return ActionResult.PASS;

        BlockPos pos = ctx.getBlockPos();

        if (world.getBlockState(pos).getBlock() instanceof net.shaddii.smartsorter.block.IntakeBlock) {
            PENDING.put(player.getUuid(), pos);
            player.sendMessage(Text.literal("SmartSorter: Origin set at " + pos), true);
            return ActionResult.CONSUME;
        }

        if (world.getBlockState(pos).getBlock() instanceof OutputProbeBlock) {
            BlockPos origin = PENDING.get(player.getUuid());
            if (origin == null) {
                player.sendMessage(Text.literal("SmartSorter: Right-click an Intake first to set origin."), true);
                return ActionResult.CONSUME;
            }
            var be = world.getBlockEntity(origin);
            if (be instanceof IntakeBlockEntity intake) {
                boolean added = intake.addOutput(pos);
                player.sendMessage(Text.literal(added
                        ? "SmartSorter: Linked probe " + pos + " to " + origin
                        : "SmartSorter: Probe already linked."), true);
                return ActionResult.CONSUME;
            } else {
                player.sendMessage(Text.literal("SmartSorter: Origin block is missing."), true);
                PENDING.remove(player.getUuid());
                return ActionResult.CONSUME;
            }
        }

        return ActionResult.PASS;
    }
}