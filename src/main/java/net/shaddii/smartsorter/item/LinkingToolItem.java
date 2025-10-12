package net.shaddii.smartsorter.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.block.IntakeBlock;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.block.ProcessProbeBlock;
import net.shaddii.smartsorter.block.StorageControllerBlock;
import net.shaddii.smartsorter.blockentity.IntakeBlockEntity;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.ProcessProbeBlockEntity;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Linking Tool - Controller-First Workflow
 *
 * NEW WORKFLOW:
 * 1. Right-click Storage Controller → Stores it
 * 2. Right-click any probe(s) → Links each to stored controller
 * 3. Shift+Right-click → Clear stored controller
 */
public class LinkingToolItem extends Item {

    // Per-player memory for stored controller
    private static final Map<UUID, BlockPos> STORED_CONTROLLER = new HashMap<>();

    public LinkingToolItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        PlayerEntity player = context.getPlayer();

        if (player == null) return ActionResult.PASS;

        var blockState = world.getBlockState(pos);

        // === SHIFT+RIGHT-CLICK: Clear stored controller ===
        if (player.isSneaking()) {
            // Special case: Cycle mode on Output Probe
            if (blockState.getBlock() instanceof OutputProbeBlock) {
                if (!world.isClient()) {
                    var probeBE = world.getBlockEntity(pos);
                    if (probeBE instanceof OutputProbeBlockEntity probe) {
                        probe.cycleMode();

                        String modeName = probe.getModeName();
                        String modeColor = switch (probe.mode) {
                            case FILTER -> "§9";
                            case ACCEPT_ALL -> "§a";
                            case PRIORITY -> "§6";
                        };

                        player.sendMessage(Text.literal(modeColor + "Mode: " + modeName), true);
                    }
                }
                return ActionResult.SUCCESS;
            }

            // Otherwise, clear stored controller
            STORED_CONTROLLER.remove(player.getUuid());
            if (!world.isClient()) {
                player.sendMessage(Text.literal("Linking tool cleared").formatted(Formatting.YELLOW), true);
            }
            return ActionResult.SUCCESS;
        }

        if (world.isClient()) return ActionResult.SUCCESS;

        // === STEP 1: Click on Storage Controller to store it ===
        if (blockState.getBlock() instanceof StorageControllerBlock) {
            STORED_CONTROLLER.put(player.getUuid(), pos);

            player.sendMessage(
                    Text.literal("§aStorage Controller selected")
                            .append(Text.literal("  §7Now right-click probes to link them")),
                    true
            );
            return ActionResult.SUCCESS;
        }

        // === STEP 2: Click on Output Probe to link ===
        if (blockState.getBlock() instanceof OutputProbeBlock) {
            BlockPos controllerPos = STORED_CONTROLLER.get(player.getUuid());

            if (controllerPos == null) {
                player.sendMessage(Text.literal("§eSelect a Storage Controller first!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            var controllerBE = world.getBlockEntity(controllerPos);
            if (!(controllerBE instanceof StorageControllerBlockEntity controller)) {
                player.sendMessage(Text.literal("§cController no longer exists!").formatted(Formatting.RED), true);
                STORED_CONTROLLER.remove(player.getUuid());
                return ActionResult.FAIL;
            }

            var probeBE = world.getBlockEntity(pos);
            if (!(probeBE instanceof OutputProbeBlockEntity probe)) {
                player.sendMessage(Text.literal("§cProbe not found!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            // BIDIRECTIONAL LINKING
            boolean controllerAdded = controller.addProbe(pos);
            boolean probeAdded = probe.addLinkedBlock(controllerPos);

            if (controllerAdded && probeAdded) {
                String modeColor = switch (probe.mode) {
                    case FILTER -> "§9";
                    case ACCEPT_ALL -> "§a";
                    case PRIORITY -> "§6";
                };
                String modeName = probe.getModeName();

                player.sendMessage(
                        Text.literal("§a✓ Output Probe linked | " + modeColor + modeName),
                        true
                );
                // DON'T clear stored controller - allow linking multiple probes!
                return ActionResult.SUCCESS;
            } else {
                player.sendMessage(Text.literal("§eAlready linked!").formatted(Formatting.YELLOW), true);
                return ActionResult.FAIL;
            }
        }

        // === STEP 3: Click on Process Probe to link ===
        if (blockState.getBlock() instanceof ProcessProbeBlock) {
            BlockPos controllerPos = STORED_CONTROLLER.get(player.getUuid());

            if (controllerPos == null) {
                player.sendMessage(Text.literal("§eSelect a Storage Controller first!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            var controllerBE = world.getBlockEntity(controllerPos);
            if (!(controllerBE instanceof StorageControllerBlockEntity controller)) {
                player.sendMessage(Text.literal("§cController no longer exists!").formatted(Formatting.RED), true);
                STORED_CONTROLLER.remove(player.getUuid());
                return ActionResult.FAIL;
            }

            var probeBE = world.getBlockEntity(pos);
            if (!(probeBE instanceof ProcessProbeBlockEntity probe)) {
                player.sendMessage(Text.literal("§cProcess Probe not found!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            // BIDIRECTIONAL LINKING
            // Note: You may want to add a separate list in StorageController for process probes
            // For now, just link the probe to the controller
            boolean probeAdded = probe.addLinkedBlock(controllerPos);

            if (probeAdded) {
                player.sendMessage(
                        Text.literal("§a✓ Process Probe linked"),
                        true
                );
                return ActionResult.SUCCESS;
            } else {
                player.sendMessage(Text.literal("§eAlready linked!").formatted(Formatting.YELLOW), true);
                return ActionResult.FAIL;
            }
        }

        // === STEP 4: Click on Intake Block to link ===
        if (blockState.getBlock() instanceof IntakeBlock) {
            BlockPos controllerPos = STORED_CONTROLLER.get(player.getUuid());

            if (controllerPos == null) {
                player.sendMessage(Text.literal("§eSelect a Storage Controller first!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            var controllerBE = world.getBlockEntity(controllerPos);
            if (!(controllerBE instanceof StorageControllerBlockEntity)) {
                player.sendMessage(Text.literal("§cController no longer exists!").formatted(Formatting.RED), true);
                STORED_CONTROLLER.remove(player.getUuid());
                return ActionResult.FAIL;
            }

            var intakeBE = world.getBlockEntity(pos);
            if (!(intakeBE instanceof IntakeBlockEntity intake)) {
                player.sendMessage(Text.literal("§cIntake not found!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            // Note: Intake doesn't directly link to controller
            // You'll need to link intake → output probe → controller
            player.sendMessage(
                    Text.literal("§eIntake blocks don't link directly to controllers\n§7Link them to Output Probes instead"),
                    false
            );
            return ActionResult.FAIL;
        }

        player.sendMessage(Text.literal("§7Click Storage Controller first, then click probes to link").formatted(Formatting.GRAY), true);
        return ActionResult.PASS;
    }

    private String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}