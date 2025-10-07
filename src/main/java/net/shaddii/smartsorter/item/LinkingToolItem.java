package net.shaddii.smartsorter.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.block.StorageControllerBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LinkingToolItem extends Item {
    // Per-player memory for linking
    private static final Map<UUID, BlockPos> PENDING_INTAKE = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_CONTROLLER = new HashMap<>();

    public LinkingToolItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        if (world.isClient) return ActionResult.SUCCESS;

        PlayerEntity player = ctx.getPlayer();
        if (player == null) return ActionResult.PASS;

        BlockPos pos = ctx.getBlockPos();
        var blockState = world.getBlockState(pos);

        // NOTE: No distance limit currently - players can link across any distance
        // Future feature: Cross-dimensional access with "chunk not loaded" notifications
        // Players using chunk loaders will benefit from unlimited range

        // === SHIFT + RIGHT-CLICK ON OUTPUT PROBE: Cycle Mode ===
        if (player.isSneaking() && blockState.getBlock() instanceof OutputProbeBlock) {
            var probeBE = world.getBlockEntity(pos);
            if (probeBE instanceof OutputProbeBlockEntity probe) {
                probe.cycleMode();

                String modeName = probe.getModeName();
                String modeColor = switch (probe.mode) {
                    case FILTER -> "§9"; // Blue
                    case ACCEPT_ALL -> "§a"; // Green
                    case PRIORITY -> "§6"; // Gold
                };

                // ACTION BAR message (true = action bar, false = chat)
                player.sendMessage(Text.literal(modeColor + "Mode: " + modeName), true);
                return ActionResult.SUCCESS;
            }
        }

        // === Storage Controller Mode ===
        if (blockState.getBlock() instanceof StorageControllerBlock) {
            PENDING_CONTROLLER.put(player.getUuid(), pos);
            PENDING_INTAKE.remove(player.getUuid());

            // ACTION BAR
            player.sendMessage(Text.literal("§aStorage Controller selected"), true);
            return ActionResult.CONSUME;
        }

        // === Output Probe - Link to Controller ===
        if (blockState.getBlock() instanceof OutputProbeBlock) {
            BlockPos controllerPos = PENDING_CONTROLLER.get(player.getUuid());

            if (controllerPos != null) {
                var controllerBE = world.getBlockEntity(controllerPos);
                if (controllerBE instanceof StorageControllerBlockEntity controller) {
                    boolean added = controller.addProbe(pos);

                    // Show link status + current mode
                    var probeBE = world.getBlockEntity(pos);
                    if (probeBE instanceof OutputProbeBlockEntity probe) {
                        probe.setLinkedController(controllerPos);

                        String modeColor = switch (probe.mode) {
                            case FILTER -> "§9";
                            case ACCEPT_ALL -> "§a";
                            case PRIORITY -> "§6";
                        };
                        String modeName = probe.getModeName();
                        String status = added ? "§aLinked" : "§eAlready linked";

                        // ACTION BAR
                        player.sendMessage(Text.literal(status + " | " + modeColor + modeName), true);
                    } else {
                        player.sendMessage(Text.literal(added ? "§aProbe linked" : "§eAlready linked"), true);
                    }
                    return ActionResult.CONSUME;
                } else {
                    player.sendMessage(Text.literal("§cController not found"), true);
                    PENDING_CONTROLLER.remove(player.getUuid());
                    return ActionResult.CONSUME;
                }
            }

            // === Old Mode: Link to Intake ===
            BlockPos intakePos = PENDING_INTAKE.get(player.getUuid());
            if (intakePos != null) {
                var intakeBE = world.getBlockEntity(intakePos);
                if (intakeBE instanceof IntakeBlockEntity intake) {
                    boolean added = intake.addOutput(pos);

                    // ACTION BAR
                    player.sendMessage(Text.literal(added ? "§aProbe linked to Intake" : "§eAlready linked"), true);
                    return ActionResult.CONSUME;
                } else {
                    player.sendMessage(Text.literal("§cIntake not found"), true);
                    PENDING_INTAKE.remove(player.getUuid());
                    return ActionResult.CONSUME;
                }
            }

            // No controller or intake selected - show current mode
            var probeBE = world.getBlockEntity(pos);
            if (probeBE instanceof OutputProbeBlockEntity probe) {
                String modeColor = switch (probe.mode) {
                    case FILTER -> "§9";
                    case ACCEPT_ALL -> "§a";
                    case PRIORITY -> "§6";
                };
                String modeName = probe.getModeName();

                // ACTION BAR
                player.sendMessage(Text.literal(modeColor + modeName + " §7| Select controller first"), true);
            } else {
                player.sendMessage(Text.literal("§eSelect controller first"), true);
            }
            return ActionResult.CONSUME;
        }

        // === Intake Block Mode (Old System) ===
        if (blockState.getBlock() instanceof net.shaddii.smartsorter.block.IntakeBlock) {
            PENDING_INTAKE.put(player.getUuid(), pos);
            PENDING_CONTROLLER.remove(player.getUuid());

            // ACTION BAR
            player.sendMessage(Text.literal("§aIntake selected"), true);
            return ActionResult.CONSUME;
        }

        return ActionResult.PASS;
    }
}