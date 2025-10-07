package net.shaddii.smartsorter.blockentity;

import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.util.SortUtil;

public class OutputProbeBlockEntity extends BlockEntity {
    // Configuration options
    public boolean ignoreComponents = true;  // ID-only matching by default
    public boolean useTags = false;
    public boolean requireAllTags = false;

    // Current mode
    public ProbeMode mode = ProbeMode.FILTER; // Default to filter mode

    // Track which controller this probe is linked to
    private BlockPos linkedController = null;

    // TODO: Future features planned:
    // - Priority-based routing (high/medium/low priority chests)
    // - Sorting options (by name, item count, custom order)
    // - Dropdown filtering (building, functional, food, etc.)
    // - Cross-dimensional access with chunk load notifications
    // - Enhanced GUI within Storage Controller

    public enum ProbeMode {
        FILTER,      // Only accepts items matching chest contents
        ACCEPT_ALL,  // Accepts any items (overflow)
        PRIORITY     // TODO: Implement priority-based routing with GUI controls
    }

    public OutputProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROBE_BE_TYPE, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, OutputProbeBlockEntity be) {
        if (world.isClient) return;
    }

    // === Controller Link Management ===

    public void setLinkedController(BlockPos controllerPos) {
        this.linkedController = controllerPos;
        markDirty();
    }

    public BlockPos getLinkedController() {
        return linkedController;
    }

    // === Storage Access ===

    /**
     * Get the storage of the chest this probe is facing
     * Tries multiple methods for maximum mod compatibility
     */
    public Storage<ItemVariant> getTargetStorage() {
        if (world == null) return null;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);

        // METHOD 1: Try Fabric's ItemStorage API first (sided)
        Storage<ItemVariant> sidedStorage = ItemStorage.SIDED.find(world, targetPos, face.getOpposite());
        if (sidedStorage != null) {
            return sidedStorage;
        }

        // METHOD 2: Try unsided storage
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(world, targetPos, null);
        if (storage != null) {
            return storage;
        }

        // METHOD 3: Fallback to Inventory wrapper
        Inventory inv = getTargetInventory();
        if (inv != null) {
            return InventoryStorage.of(inv, null);
        }

        return null;
    }

    /**
     * Get the inventory this probe is facing
     * Handles vanilla double chests and modded inventories
     */
    public Inventory getTargetInventory() {
        if (world == null) return null;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);
        BlockState targetState = world.getBlockState(targetPos);

        // VANILLA CHEST: Use special method that combines double chests
        if (targetState.getBlock() instanceof net.minecraft.block.ChestBlock chestBlock) {
            Inventory chestInv = net.minecraft.block.ChestBlock.getInventory(
                    chestBlock,
                    targetState,
                    world,
                    targetPos,
                    true
            );

            if (chestInv != null) {
                SmartSorter.LOGGER.info("Probe at {} - Found vanilla chest, size: {}", pos, chestInv.size());
                return chestInv;
            }
        }

        // MODDED INVENTORIES: Get BlockEntity directly
        BlockEntity be = world.getBlockEntity(targetPos);
        if (be instanceof Inventory inv) {
            SmartSorter.LOGGER.info("Probe at {} - Found modded inventory ({}), size: {}",
                    pos, inv.getClass().getSimpleName(), inv.size());
            return inv;
        }

        SmartSorter.LOGGER.warn("Probe at {} - No inventory found at {}", pos, targetPos);
        return null;
    }

    // === Item Acceptance Logic ===

    /**
     * Check if this probe accepts the incoming item
     */
    public boolean accepts(ItemVariant incoming) {
        if (world == null) return false;

        // Get fresh inventory (handles chest upgrades and double chests)
        Inventory inv = getTargetInventory();

        if (inv == null) {
            SmartSorter.LOGGER.warn("Probe accepts() - No inventory found");
            return false;
        }

        int invSize = inv.size();
        String itemName = incoming.getItem().getName().getString();

        SmartSorter.LOGGER.info("=== ACCEPT CHECK ===");
        SmartSorter.LOGGER.info("Probe: {}, Mode: {}", pos, mode);
        SmartSorter.LOGGER.info("Item: {}, Inventory Size: {}", itemName, invSize);

        // ACCEPT_ALL mode: always accept if chest has space
        if (mode == ProbeMode.ACCEPT_ALL) {
            boolean hasSpace = hasSpaceInInventory(inv, incoming, 1);

            // Count empty slots for debug
            int emptyCount = 0;
            for (int i = 0; i < invSize; i++) {
                if (inv.getStack(i).isEmpty()) emptyCount++;
            }

            SmartSorter.LOGGER.info("ACCEPT_ALL - Empty slots: {}/{}, Result: {}",
                    emptyCount, invSize, hasSpace);
            return hasSpace;
        }

        // FILTER mode: only accept items matching chest contents
        if (mode == ProbeMode.FILTER) {
            if (useTags) {
                boolean result = SortUtil.acceptsByInventoryTags(inv, incoming, requireAllTags);
                SmartSorter.LOGGER.info("FILTER (tags) - Result: {}", result);
                return result;
            }

            // Check if chest contains matching items (searches ALL slots)
            SmartSorter.LOGGER.info("FILTER - Checking {} slots for matching items...", invSize);

            for (int i = 0; i < invSize; i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) continue;

                ItemVariant present = ItemVariant.of(stack);
                String presentName = present.getItem().getName().getString();

                if (ignoreComponents) {
                    if (present.getItem() == incoming.getItem()) {
                        SmartSorter.LOGGER.info("FILTER - Match found in slot {}: {} == {}",
                                i, presentName, itemName);
                        return true;
                    }
                } else {
                    if (present.equals(incoming)) {
                        SmartSorter.LOGGER.info("FILTER - Exact match found in slot {}: {}",
                                i, presentName);
                        return true;
                    }
                }
            }

            SmartSorter.LOGGER.info("FILTER - No matching items found in {} slots", invSize);
            return false;
        }

        // PRIORITY mode: Future feature - currently behaves like ACCEPT_ALL
        if (mode == ProbeMode.PRIORITY) {
            boolean hasSpace = hasSpaceInInventory(inv, incoming, 1);
            SmartSorter.LOGGER.info("PRIORITY - Result: {}", hasSpace);
            return hasSpace;
        }

        return false;
    }

    /**
     * Check if the given inventory has space for the item
     */
    private boolean hasSpaceInInventory(Inventory inv, ItemVariant variant, int amount) {
        if (inv == null) return false;

        int invSize = inv.size();

        // Check ALL slots for space (handles upgraded chests)
        for (int i = 0; i < invSize; i++) {
            ItemStack stack = inv.getStack(i);

            if (stack.isEmpty()) {
                // Empty slot found - we can fit items
                SmartSorter.LOGGER.info("  → Found empty slot at index {}", i);
                return true;
            } else if (ItemStack.areItemsAndComponentsEqual(stack, variant.toStack(1))) {
                // Matching item - check if we can add more
                int maxStack = Math.min(stack.getMaxCount(), inv.getMaxCountPerStack());
                int canAdd = maxStack - stack.getCount();
                if (canAdd > 0) {
                    SmartSorter.LOGGER.info("  → Can stack {} more in slot {} (current: {}/{})",
                            canAdd, i, stack.getCount(), maxStack);
                    return true;
                }
            }
        }

        SmartSorter.LOGGER.info("  → No space found in {} slots", invSize);
        return false;
    }

    /**
     * Check if the target inventory has space for the given item
     * (Public wrapper - fetches inventory and delegates)
     */
    public boolean hasSpace(ItemVariant variant, int amount) {
        Inventory inv = getTargetInventory();
        return hasSpaceInInventory(inv, variant, amount);
    }

    // === Mode Management ===

    /**
     * Cycle to next mode (for right-click toggle)
     */
    public void cycleMode() {
        mode = switch (mode) {
            case FILTER -> ProbeMode.ACCEPT_ALL;
            case ACCEPT_ALL -> ProbeMode.FILTER; // Can add PRIORITY later
            case PRIORITY -> ProbeMode.FILTER;
        };
        markDirty();
    }

    /**
     * Get mode display name
     */
    public String getModeName() {
        return switch (mode) {
            case FILTER -> "Filter Mode";
            case ACCEPT_ALL -> "Accept All";
            case PRIORITY -> "Priority Mode";
        };
    }

    /**
     * Get mode color for visual feedback
     */
    public int getModeColor() {
        return switch (mode) {
            case FILTER -> 0x4A90E2; // Blue
            case ACCEPT_ALL -> 0x7ED321; // Green
            case PRIORITY -> 0xF5A623; // Orange
        };
    }

    // === Cleanup ===

    /**
     * Called when this probe is removed from the world
     * Remove from the controller that references it
     */
    public void onRemoved(World world) {
        if (linkedController != null) {
            BlockEntity be = world.getBlockEntity(linkedController);
            if (be instanceof StorageControllerBlockEntity controller) {
                if (controller.getLinkedProbes().remove(pos)) {
                    controller.markDirty();
                    SmartSorter.LOGGER.info("Removed probe {} from controller {}", pos, linkedController);
                }
            }
        }
    }

    // === NBT Serialization ===

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.putBoolean("ignoreComponents", ignoreComponents);
        nbt.putBoolean("useTags", useTags);
        nbt.putBoolean("requireAllTags", requireAllTags);
        nbt.putString("mode", mode.name());

        // Save linked controller
        if (linkedController != null) {
            nbt.putLong("linkedController", linkedController.asLong());
        }
    }

    @Override
    public void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        ignoreComponents = nbt.getBoolean("ignoreComponents");
        useTags = nbt.getBoolean("useTags");
        requireAllTags = nbt.getBoolean("requireAllTags");

        try {
            mode = ProbeMode.valueOf(nbt.getString("mode"));
        } catch (IllegalArgumentException e) {
            mode = ProbeMode.FILTER; // Default if invalid
        }

        // Load linked controller
        if (nbt.contains("linkedController")) {
            linkedController = BlockPos.fromLong(nbt.getLong("linkedController"));
        } else {
            linkedController = null;
        }
    }
}