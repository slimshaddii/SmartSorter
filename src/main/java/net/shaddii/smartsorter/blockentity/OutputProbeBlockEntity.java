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
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.SortUtil;
//? if >=1.21.8 {
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?}
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class OutputProbeBlockEntity extends BlockEntity {
    // ========================================
    // ENUMS
    // ========================================

    public enum ProbeMode {
        FILTER,
        ACCEPT_ALL,
        PRIORITY
    }

    // ========================================
    // CONSTANTS
    // ========================================

    private static final long VALIDATION_INTERVAL = 100L;

    // ========================================
    // FIELDS
    // ========================================

    // Configuration
    public boolean ignoreComponents = true;
    public boolean useTags = false;
    public boolean requireAllTags = false;
    public ProbeMode mode = ProbeMode.FILTER;

    // Multi-block linking
    private final List<BlockPos> linkedBlocks = new ArrayList<>();
    private List<BlockPos> linkedBlocksCopy = null;
    private boolean linkedBlocksCopyDirty = true;

    // Cache
    private BlockPos cachedChestPos = null;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public OutputProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROBE_BE_TYPE, pos, state);
    }

    // ========================================
    // TICK LOGIC
    // ========================================

    public static void tick(World world, BlockPos pos, BlockState state, OutputProbeBlockEntity be) {
        if (world.isClient()) return;

        // Validate linked blocks periodically
        if (world.getTime() % VALIDATION_INTERVAL == 0) {
            be.validateLinkedBlocks();
        }
    }

    private void validateLinkedBlocks() {
        if (world == null) return;

        linkedBlocks.removeIf(blockPos -> {
            BlockEntity be = world.getBlockEntity(blockPos);
            return !(be instanceof StorageControllerBlockEntity || be instanceof IntakeBlockEntity);
        });
    }

    // ========================================
    // LINKING MANAGEMENT
    // ========================================

    public boolean addLinkedBlock(BlockPos blockPos) {
        if (!linkedBlocks.contains(blockPos)) {
            linkedBlocks.add(blockPos);
            linkedBlocksCopyDirty = true;
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }

            return true;
        }
        return false;
    }

    public boolean removeLinkedBlock(BlockPos blockPos) {
        boolean removed = linkedBlocks.remove(blockPos);
        if (removed) {
            linkedBlocksCopyDirty = true;
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }
        }
        return removed;
    }

    public List<BlockPos> getLinkedBlocks() {
        if (linkedBlocksCopyDirty || linkedBlocksCopy == null) {
            linkedBlocksCopy = new ArrayList<>(linkedBlocks);
            linkedBlocksCopyDirty = false;
        }
        return linkedBlocksCopy;
    }

    public boolean hasLinkedBlocks() {
        return !linkedBlocks.isEmpty();
    }

    public void notifyLinkedBlocks() {
        if (world == null || world.isClient()) return;

        for (BlockPos blockPos : new ArrayList<>(linkedBlocks)) {
            BlockEntity be = world.getBlockEntity(blockPos);

            if (be instanceof StorageControllerBlockEntity controller) {
                controller.onProbeInventoryChanged(this);
            }
        }
    }

    // ========================================
    // LEGACY COMPATIBILITY
    // ========================================

    @Deprecated
    public void setLinkedController(BlockPos controllerPos) {
        addLinkedBlock(controllerPos);
    }

    @Deprecated
    public BlockPos getLinkedController() {
        if (world == null) return null;

        for (BlockPos blockPos : linkedBlocks) {
            BlockEntity be = world.getBlockEntity(blockPos);
            if (be instanceof StorageControllerBlockEntity) {
                return blockPos;
            }
        }
        return null;
    }

    // ========================================
    // STORAGE ACCESS
    // ========================================

    public BlockPos getTargetPos() {
        if (world == null) return null;
        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        return pos.offset(face);
    }

    public Storage<ItemVariant> getTargetStorage() {
        if (world == null) return null;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);

        Storage<ItemVariant> sidedStorage = ItemStorage.SIDED.find(world, targetPos, face.getOpposite());
        if (sidedStorage != null) return sidedStorage;

        Storage<ItemVariant> storage = ItemStorage.SIDED.find(world, targetPos, null);
        if (storage != null) return storage;

        Inventory inv = getTargetInventory();
        if (inv != null) return InventoryStorage.of(inv, null);

        return null;
    }

    public Inventory getTargetInventory() {
        if (world == null) return null;

        Direction face = getCachedState().get(OutputProbeBlock.FACING);
        BlockPos targetPos = pos.offset(face);
        BlockState targetState = world.getBlockState(targetPos);

        if (targetState.getBlock() instanceof net.minecraft.block.ChestBlock chestBlock) {
            Inventory chestInv = net.minecraft.block.ChestBlock.getInventory(chestBlock, targetState, world, targetPos, true);
            if (chestInv != null) {
                return chestInv;
            }
        }

        BlockEntity be = world.getBlockEntity(targetPos);
        if (be instanceof Inventory inv) {
            return inv;
        }

        return null;
    }

    public ChestConfig getChestConfig() {
        if (world == null) return null;

        BlockPos chestPos = getTargetPos();
        if (chestPos == null) return null;

        // Find linked controller and get config
        for (BlockPos blockPos : linkedBlocks) {
            BlockEntity be = world.getBlockEntity(blockPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                return controller.getChestConfig(chestPos);
            }
        }

        return null;
    }

    // ========================================
    // ITEM ACCEPTANCE LOGIC
    // ========================================

    public boolean accepts(ItemVariant incoming) {
        if (world == null) return false;

        // Check space first
        Inventory inv = getTargetInventory();
        if (inv == null || !hasSpaceInInventory(inv, incoming, 1)) {
            return false;
        }

        // Check filter rules
        ChestConfig chestConfig = getChestConfig();
        if (chestConfig != null) {
            CategoryManager categoryManager = CategoryManager.getInstance();
            Category itemCategory = categoryManager.categorize(incoming.getItem());

            switch (chestConfig.filterMode) {
                case NONE:
                case PRIORITY:
                    return true;

                case CATEGORY:
                case CATEGORY_AND_PRIORITY:
                case OVERFLOW:
                    return itemCategory.equals(chestConfig.filterCategory) || chestConfig.filterCategory.equals(Category.ALL);

                case BLACKLIST:
                    return !itemCategory.equals(chestConfig.filterCategory);

                case CUSTOM:
                    return acceptsByChestContents(inv, incoming, chestConfig.strictNBTMatch);

                default:
                    return false;
            }
        }

        // Fallback to probe's own mode
        if (mode == ProbeMode.ACCEPT_ALL || mode == ProbeMode.PRIORITY) {
            return true;
        }

        if (mode == ProbeMode.FILTER) {
            if (useTags) {
                return SortUtil.acceptsByInventoryTags(inv, incoming, requireAllTags);
            }
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack.isEmpty()) continue;
                ItemVariant present = ItemVariant.of(stack);
                if (ignoreComponents) {
                    if (present.isOf(incoming.getItem())) return true;
                } else {
                    if (present.equals(incoming)) return true;
                }
            }
            return false;
        }

        return false;
    }

    public boolean contains(ItemVariant variant) {
        if (world == null) return false;

        Inventory inv = getTargetInventory();
        if (inv == null) return false;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            ItemVariant stackVariant = ItemVariant.of(stack);
            if (stackVariant.equals(variant)) {
                return true;
            }
        }

        return false;
    }

    private boolean acceptsByChestContents(Inventory inv, ItemVariant incoming, boolean strictNBT) {
        if (inv == null) return false;

        boolean foundAnyItem = false;
        boolean foundMatch = false;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;

            foundAnyItem = true;

            if (strictNBT) {
                ItemVariant existingVariant = ItemVariant.of(stack);
                if (existingVariant.equals(incoming)) {
                    foundMatch = true;
                    break;
                }
            } else {
                if (stack.getItem() == incoming.getItem()) {
                    foundMatch = true;
                    break;
                }
            }
        }

        // Empty chest accepts anything
        if (!foundAnyItem) {
            return hasSpaceInInventory(inv, incoming, 1);
        }

        // Found match, check space
        if (foundMatch) {
            return hasSpaceInInventory(inv, incoming, 1);
        }

        return false;
    }

    private boolean hasSpaceInInventory(Inventory inv, ItemVariant variant, int amount) {
        if (inv == null) return false;

        int invSize = inv.size();
        for (int i = 0; i < invSize; i++) {
            ItemStack stack = inv.getStack(i);

            if (stack.isEmpty()) {
                return true;
            } else if (ItemStack.areItemsAndComponentsEqual(stack, variant.toStack(1))) {
                int maxStack = Math.min(stack.getMaxCount(), inv.getMaxCountPerStack());
                int canAdd = maxStack - stack.getCount();
                if (canAdd > 0) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean hasSpace(ItemVariant variant, int amount) {
        Inventory inv = getTargetInventory();
        return hasSpaceInInventory(inv, variant, amount);
    }

    // ========================================
    // MODE MANAGEMENT
    // ========================================

    public void cycleMode() {
        mode = switch (mode) {
            case FILTER -> ProbeMode.ACCEPT_ALL;
            case ACCEPT_ALL -> ProbeMode.FILTER;
            case PRIORITY -> ProbeMode.FILTER;
        };
        markDirty();
    }

    public String getModeName() {
        return switch (mode) {
            case FILTER -> "Filter Mode";
            case ACCEPT_ALL -> "Accept All";
            case PRIORITY -> "Priority Mode";
        };
    }

    public int getModeColor() {
        return switch (mode) {
            case FILTER -> 0x4A90E2;
            case ACCEPT_ALL -> 0x7ED321;
            case PRIORITY -> 0xF5A623;
        };
    }

    // ========================================
    // CLEANUP
    // ========================================

    public void onRemoved(World world) {
        if (world.isClient()) return;

        BlockPos targetPos = getTargetPos();

        for (BlockPos blockPos : new ArrayList<>(linkedBlocks)) {
            BlockEntity be = world.getBlockEntity(blockPos);

            if (be instanceof StorageControllerBlockEntity controller) {
                controller.removeProbe(pos);

                if (targetPos != null) {
                    boolean stillHasProbe = false;

                    for (BlockPos otherProbePos : controller.getLinkedProbes()) {
                        BlockEntity otherBe = world.getBlockEntity(otherProbePos);
                        if (otherBe instanceof OutputProbeBlockEntity otherProbe) {
                            BlockPos otherTarget = otherProbe.getTargetPos();
                            if (targetPos.equals(otherTarget)) {
                                stillHasProbe = true;
                                break;
                            }
                        }
                    }

                    if (!stillHasProbe) {
                        controller.removeChestConfig(targetPos);
                    }
                }
            }
        }

        linkedBlocks.clear();
    }

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    //? if >= 1.21.8 {
    private void writeProbeData(WriteView view) {
        view.putBoolean("ignoreComponents", ignoreComponents);
        view.putBoolean("useTags", useTags);
        view.putBoolean("requireAllTags", requireAllTags);
        view.putString("mode", mode.name());

        view.putInt("linked_blocks_count", linkedBlocks.size());
        for (int i = 0; i < linkedBlocks.size(); i++) {
            view.putLong("linked_block_" + i, linkedBlocks.get(i).asLong());
        }
    }

    private void writeProbeData(NbtCompound nbt) {
        nbt.putBoolean("ignoreComponents", ignoreComponents);
        nbt.putBoolean("useTags", useTags);
        nbt.putBoolean("requireAllTags", requireAllTags);
        nbt.putString("mode", mode.name());

        nbt.putInt("linked_blocks_count", linkedBlocks.size());
        for (int i = 0; i < linkedBlocks.size(); i++) {
            nbt.putLong("linked_block_" + i, linkedBlocks.get(i).asLong());
        }
    }

    private void readProbeData(ReadView view) {
        ignoreComponents = view.getBoolean("ignoreComponents", true);
        useTags = view.getBoolean("useTags", false);
        requireAllTags = view.getBoolean("requireAllTags", false);

        try {
            mode = ProbeMode.valueOf(view.getString("mode", "FILTER"));
        } catch (IllegalArgumentException e) {
            mode = ProbeMode.FILTER;
        }

        linkedBlocks.clear();
        int count = view.getInt("linked_blocks_count", 0);
        for (int i = 0; i < count; i++) {
            view.getOptionalLong("linked_block_" + i).ifPresent(posLong -> {
                linkedBlocks.add(BlockPos.fromLong(posLong));
            });
        }
    }

    @Override
    public void writeData(WriteView view) {
        super.writeData(view);
        writeProbeData(view);
    }

    @Override
    public void readData(ReadView view) {
        super.readData(view);
        readProbeData(view);
    }
    //?} else {
    /*private void writeProbeData(NbtCompound nbt) {
        nbt.putBoolean("ignoreComponents", ignoreComponents);
        nbt.putBoolean("useTags", useTags);
        nbt.putBoolean("requireAllTags", requireAllTags);
        nbt.putString("mode", mode.name());

        nbt.putInt("linked_blocks_count", linkedBlocks.size());
        for (int i = 0; i < linkedBlocks.size(); i++) {
            nbt.putLong("linked_block_" + i, linkedBlocks.get(i).asLong());
        }
    }

    private void readProbeData(NbtCompound nbt) {
        ignoreComponents = nbt.getBoolean("ignoreComponents");
        useTags = nbt.getBoolean("useTags");
        requireAllTags = nbt.getBoolean("requireAllTags");

        try {
            mode = ProbeMode.valueOf(nbt.getString("mode"));
        } catch (IllegalArgumentException e) {
            mode = ProbeMode.FILTER;
        }

        linkedBlocks.clear();
        int count = nbt.getInt("linked_blocks_count");
        for (int i = 0; i < count; i++) {
            String key = "linked_block_" + i;
            if (nbt.contains(key)) {
                linkedBlocks.add(BlockPos.fromLong(nbt.getLong(key)));
            }
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        writeProbeData(nbt);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        readProbeData(nbt);
    }
    *///?}

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
        return createNbt(registryLookup);
    }
}