package net.shaddii.smartsorter.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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

public class LinkingToolItem extends Item {
    // ========================================
    // FIELDS
    // ========================================

    private static final Map<UUID, BlockPos> STORED_CONTROLLER = new HashMap<>();
    private static final Map<UUID, BlockPos> STORED_INTAKE = new HashMap<>();

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public LinkingToolItem(Settings settings) {
        super(settings);
    }

    // ========================================
    // USE IN AIR (VERSION-SPECIFIC)
    // ========================================

    //? if >=1.21.8 {
    @Override
    public ActionResult use(World world, PlayerEntity player, net.minecraft.util.Hand hand) {
        if (player.isSneaking()) {
            if (!world.isClient()) {
                boolean hadController = STORED_CONTROLLER.remove(player.getUuid()) != null;
                boolean hadIntake = STORED_INTAKE.remove(player.getUuid()) != null;

                if (hadController && hadIntake) {
                    player.sendMessage(Text.literal("§eCleared controller + intake selection").formatted(Formatting.YELLOW), true);
                } else if (hadController) {
                    player.sendMessage(Text.literal("§eCleared controller selection").formatted(Formatting.YELLOW), true);
                } else if (hadIntake) {
                    player.sendMessage(Text.literal("§eCleared intake selection").formatted(Formatting.YELLOW), true);
                } else {
                    player.sendMessage(Text.literal("§7Nothing to clear").formatted(Formatting.GRAY), true);
                }
            }

            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }
    //?} else {
    /*@Override
    public net.minecraft.util.TypedActionResult<ItemStack> use(World world, PlayerEntity player, net.minecraft.util.Hand hand) {
        if (player.isSneaking()) {
            if (!world.isClient()) {
                boolean hadController = STORED_CONTROLLER.remove(player.getUuid()) != null;
                boolean hadIntake = STORED_INTAKE.remove(player.getUuid()) != null;

                if (hadController && hadIntake) {
                    player.sendMessage(Text.literal("§eCleared controller + intake selection").formatted(Formatting.YELLOW), true);
                } else if (hadController) {
                    player.sendMessage(Text.literal("§eCleared controller selection").formatted(Formatting.YELLOW), true);
                } else if (hadIntake) {
                    player.sendMessage(Text.literal("§eCleared intake selection").formatted(Formatting.YELLOW), true);
                } else {
                    player.sendMessage(Text.literal("§7Nothing to clear").formatted(Formatting.GRAY), true);
                }
            }

            return net.minecraft.util.TypedActionResult.success(player.getStackInHand(hand), world.isClient());
        }
        return net.minecraft.util.TypedActionResult.pass(player.getStackInHand(hand));
    }
    *///?}

    // ========================================
    // USE ON BLOCK
    // ========================================

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        BlockPos pos = context.getBlockPos();
        PlayerEntity player = context.getPlayer();

        if (player == null) return ActionResult.PASS;

        var blockState = world.getBlockState(pos);

        // ========================================
        // SHIFT-CLICK HANDLING
        // ========================================

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
            if (!world.isClient()) {
                boolean hadController = STORED_CONTROLLER.remove(player.getUuid()) != null;
                boolean hadIntake = STORED_INTAKE.remove(player.getUuid()) != null;

                if (hadController && hadIntake) {
                    player.sendMessage(Text.literal("§eCleared controller + intake selection").formatted(Formatting.YELLOW), true);
                } else if (hadController) {
                    player.sendMessage(Text.literal("§eCleared controller selection").formatted(Formatting.YELLOW), true);
                } else if (hadIntake) {
                    player.sendMessage(Text.literal("§eCleared intake selection").formatted(Formatting.YELLOW), true);
                } else {
                    player.sendMessage(Text.literal("§7Nothing to clear").formatted(Formatting.GRAY), true);
                }
            }
            return ActionResult.SUCCESS;
        }

        if (world.isClient()) return ActionResult.SUCCESS;

        // ========================================
        // STORAGE CONTROLLER LINKING
        // ========================================

        if (blockState.getBlock() instanceof StorageControllerBlock) {
            STORED_CONTROLLER.put(player.getUuid(), pos);

            player.sendMessage(
                    Text.literal("§aStorage Controller selected  §7Now right-click probes or intakes to link them"),
                    true
            );
            return ActionResult.SUCCESS;
        }

        // ========================================
        // OUTPUT PROBE LINKING
        // ========================================

        if (blockState.getBlock() instanceof OutputProbeBlock) {
            BlockPos controllerPos = STORED_CONTROLLER.get(player.getUuid());
            BlockPos intakePos = STORED_INTAKE.get(player.getUuid());

            // DIRECT MODE: Link intake directly to probe
            if (intakePos != null && controllerPos == null) {
                var intakeBE = world.getBlockEntity(intakePos);
                if (!(intakeBE instanceof IntakeBlockEntity intake)) {
                    player.sendMessage(Text.literal("§cIntake no longer exists!").formatted(Formatting.RED), true);
                    STORED_INTAKE.remove(player.getUuid());
                    return ActionResult.FAIL;
                }

                var probeBE = world.getBlockEntity(pos);
                if (!(probeBE instanceof OutputProbeBlockEntity probe)) {
                    player.sendMessage(Text.literal("§cProbe not found!").formatted(Formatting.RED), true);
                    return ActionResult.FAIL;
                }

                boolean intakeAdded = intake.addOutput(pos);
                boolean probeAdded = probe.addLinkedBlock(intakePos);

                if (intakeAdded && probeAdded) {
                    String modeColor = switch (probe.mode) {
                        case FILTER -> "§9";
                        case ACCEPT_ALL -> "§a";
                        case PRIORITY -> "§6";
                    };

                    player.sendMessage(
                            Text.literal("§a✓ Direct Mode | " + modeColor + probe.getModeName() + " §7(Click intake again to add more)"),
                            true
                    );

                    STORED_INTAKE.remove(player.getUuid());

                    return ActionResult.SUCCESS;
                } else {
                    player.sendMessage(Text.literal("§eAlready linked!").formatted(Formatting.YELLOW), true);
                    return ActionResult.FAIL;
                }
            }

            // MANAGED MODE: Link controller to probe
            if (controllerPos != null) {
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
                    return ActionResult.SUCCESS;
                } else {
                    player.sendMessage(Text.literal("§eAlready linked!").formatted(Formatting.YELLOW), true);
                    return ActionResult.FAIL;
                }
            }

            // Nothing stored
            player.sendMessage(Text.literal("§eSelect a Storage Controller or Intake first!").formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }

        // ========================================
        // PROCESS PROBE LINKING
        // ========================================

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

        // ========================================
        // INTAKE LINKING
        // ========================================

        if (blockState.getBlock() instanceof IntakeBlock) {
            BlockPos controllerPos = STORED_CONTROLLER.get(player.getUuid());

            // If no controller selected, store this intake for direct linking
            if (controllerPos == null) {
                STORED_INTAKE.put(player.getUuid(), pos);
                player.sendMessage(
                        Text.literal("§bIntake selected  §7Right-click Output Probes for Direct Mode or select Storage Controller for Managed Mode"),
                        true
                );
                return ActionResult.SUCCESS;
            }

            // MANAGED MODE: Controller is selected
            var controllerBE = world.getBlockEntity(controllerPos);
            if (!(controllerBE instanceof StorageControllerBlockEntity controller)) {
                player.sendMessage(Text.literal("§cController no longer exists!").formatted(Formatting.RED), true);
                STORED_CONTROLLER.remove(player.getUuid());
                return ActionResult.FAIL;
            }

            var intakeBE = world.getBlockEntity(pos);
            if (!(intakeBE instanceof IntakeBlockEntity intake)) {
                player.sendMessage(Text.literal("§cIntake not found!").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            boolean intakeLinked = intake.setController(controllerPos);
            boolean controllerLinked = controller.addIntake(pos);

            if (intakeLinked && controllerLinked) {
                int probeCount = controller.getLinkedProbes().size();
                String probeStatus = probeCount == 0
                        ? "§e(No output probes yet)"
                        : "§a(" + probeCount + " probes)";

                player.sendMessage(
                        Text.literal("§a✓ Intake → Managed Mode " + probeStatus),
                        true
                );
                return ActionResult.SUCCESS;
            } else {
                player.sendMessage(Text.literal("§eAlready linked!").formatted(Formatting.YELLOW), true);
                return ActionResult.FAIL;
            }
        }

        // ========================================
        // FALLBACK
        // ========================================

        player.sendMessage(Text.literal("§7Click Storage Controller first, then click probes/intakes to link").formatted(Formatting.GRAY), true);
        return ActionResult.PASS;
    }

    // ========================================
    // UTILITY
    // ========================================

    private String formatPos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}