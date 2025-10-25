package net.shaddii.smartsorter.blockentity;

import com.mojang.serialization.DataResult;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.shaddii.smartsorter.SmartSorter;
import net.shaddii.smartsorter.block.OutputProbeBlock;
import net.shaddii.smartsorter.screen.OutputProbeScreenHandler;
import net.shaddii.smartsorter.util.Category;
import net.shaddii.smartsorter.util.CategoryManager;
import net.shaddii.smartsorter.util.ChestConfig;
import net.shaddii.smartsorter.util.SortUtil;
//? if >=1.21.8 {
import net.minecraft.nbt.NbtOps;
import net.minecraft.text.TextCodecs;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?}
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class OutputProbeBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    // ========================================
    // DATA RECORD
    // ========================================
    public record ProbeData(BlockPos chestPos, @Nullable ChestConfig config) {
        public static final PacketCodec<RegistryByteBuf, ProbeData> CODEC = PacketCodec.of(
                (value, buf) -> {
                    buf.writeBlockPos(value.chestPos);
                    buf.writeBoolean(value.config != null);
                    if (value.config != null) {
                        buf.writeBlockPos(value.config.position);
                        buf.writeString(value.config.customName != null ? value.config.customName : "");
                        buf.writeString(value.config.filterCategory.asString());
                        buf.writeVarInt(value.config.priority);
                        buf.writeString(value.config.filterMode.name());
                        buf.writeBoolean(value.config.strictNBTMatch);
                        buf.writeBoolean(value.config.autoItemFrame);

                        if (value.config.simplePrioritySelection != null) {
                            buf.writeBoolean(true);
                            buf.writeString(value.config.simplePrioritySelection.name());
                        } else {
                            buf.writeBoolean(false);
                        }
                    }
                },
                (buf) -> {
                    BlockPos chestPos = buf.readBlockPos();
                    boolean hasConfig = buf.readBoolean();
                    ChestConfig config = null;

                    if (hasConfig) {
                        BlockPos configPos = buf.readBlockPos();
                        String customName = buf.readString();
                        String categoryId = buf.readString();
                        int priority = buf.readVarInt();
                        String filterMode = buf.readString();
                        boolean strictNBT = buf.readBoolean();
                        boolean autoFrame = buf.readBoolean();

                        config = new ChestConfig(configPos);
                        config.customName = customName;
                        config.filterCategory = CategoryManager.getInstance().getCategory(categoryId);
                        config.priority = priority;
                        config.filterMode = ChestConfig.FilterMode.valueOf(filterMode);
                        config.strictNBTMatch = strictNBT;
                        config.autoItemFrame = autoFrame;

                        boolean hasSimplePriority = buf.readBoolean();
                        if (hasSimplePriority) {
                            String simplePriorityStr = buf.readString();
                            try {
                                config.simplePrioritySelection = ChestConfig.SimplePriority.valueOf(simplePriorityStr);
                            } catch (Exception e) {
                                config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                            }
                        } else {
                            config.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                        }
                    }

                    return new ProbeData(chestPos, config);
                }
        );
    }

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

    private static final long VALIDATION_INTERVAL = 200L;

    // ========================================
    // FIELDS
    // ========================================

    // Configuration
    public boolean ignoreComponents = true;
    public boolean useTags = false;
    public boolean requireAllTags = false;
    public ProbeMode mode = ProbeMode.FILTER;

    // Local chest configuration storage
    private ChestConfig localChestConfig = null;

    // Multi-block linking
    private final List<BlockPos> linkedBlocks = new ArrayList<>();
    private List<BlockPos> linkedBlocksCopy = null;
    private boolean linkedBlocksCopyDirty = true;

    // Cache
    private BlockPos cachedChestPos = null;
    private ChestConfig cachedConfig = null;
    private long cacheValidUntil = 0;
    private static final long CACHE_DURATION = 20; // 1 second
    private Boolean cachedHasSpace = null;
    private long spaceCheckTime = 0;

    // ========================================
    // CONSTRUCTOR
    // ========================================

    public OutputProbeBlockEntity(BlockPos pos, BlockState state) {
        super(SmartSorter.PROBE_BE_TYPE, pos, state);
    }

    // ========================================
    // INITIALIZATION
    // ========================================

    // Initialize chest config when probe is placed
    public void onPlaced(World world) {
        if (world.isClient()) return;

        BlockPos targetPos = getTargetPos();
        if (targetPos != null && localChestConfig == null) {
            // Create default config for this chest
            localChestConfig = new ChestConfig(targetPos);

            // Set default SimplePriority based on FilterMode
            if (localChestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
                localChestConfig.priority = 999;
            } else {
                // Default is MEDIUM for all non-overflow chests
                localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                localChestConfig.priority = 5; // Middle priority
            }

            // Try to read existing name from chest
            BlockEntity be = world.getBlockEntity(targetPos);
            if (be != null) {
                NbtCompound nbt = be.createNbt(world.getRegistryManager());
                if (nbt.contains("CustomName")) {
                    try {
                        //? if >=1.21.8 {
                        DataResult<Text> result = TextCodecs.CODEC.parse(
                                world.getRegistryManager().getOps(NbtOps.INSTANCE),
                                nbt.get("CustomName")
                        );
                        result.result().ifPresent(text ->
                                localChestConfig.customName = text.getString()
                        );
                        //?} else {
                /*Text customName = Text.Serialization.fromJson(nbt.getString("CustomName"), world.getRegistryManager());
                if (customName != null) {
                    localChestConfig.customName = customName.getString();
                }
                *///?}
                    } catch (Exception ignored) {}
                }
            }

            localChestConfig.updateHiddenPriority();
            markDirty();
        }
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
    // CHEST CONFIG MANAGEMENT
    // ========================================

    /**
     * Get the chest configuration.
     * Priority: Controller's config > Local config > Create new
     */
    public ChestConfig getChestConfig() {
        BlockPos targetPos = getTargetPos();
        if (targetPos == null) return null;

        long currentTime = world != null ? world.getTime() : 0;

        // Return cached if still valid
        if (cachedConfig != null && currentTime < cacheValidUntil) {
            return cachedConfig;
        }

        // Try controller first
        for (BlockPos blockPos : linkedBlocks) {
            if (world == null) break;
            BlockEntity be = world.getBlockEntity(blockPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                ChestConfig controllerConfig = controller.getChestConfig(targetPos);
                if (controllerConfig != null) {
                    cachedConfig = controllerConfig;
                    cacheValidUntil = currentTime + CACHE_DURATION;
                    return controllerConfig;
                }
            }
        }

        // Use local config
        if (localChestConfig == null) {
            localChestConfig = new ChestConfig(targetPos);
            localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;

            if (localChestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
            }
        } else if (!localChestConfig.position.equals(targetPos)) {
            localChestConfig = new ChestConfig(targetPos);
            localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;

            if (localChestConfig.filterMode == ChestConfig.FilterMode.OVERFLOW) {
                localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.LOWEST;
            }
        }

        cachedConfig = localChestConfig;
        cacheValidUntil = currentTime + CACHE_DURATION;
        return localChestConfig;
    }

    public void invalidateConfigCache() {
        cachedConfig = null;
        cacheValidUntil = 0;
        cachedHasSpace = null;
        spaceCheckTime = 0;
    }

    /**
     * Update the local chest configuration.
     * Also syncs to controller if linked.
     */
    public void setChestConfig(ChestConfig config) {
        if (config == null) return;

        this.localChestConfig = config.copy();

        if (this.localChestConfig.simplePrioritySelection == null) {
            this.localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
        }

        this.localChestConfig.updateHiddenPriority();

        invalidateConfigCache(); // ← ADD THIS
        markDirty();

        if (world != null) {
            BlockState state = getCachedState();
            world.updateListeners(pos, state, state, 3);
        }

        syncConfigToController();
    }

    /**
     * Sync local config to linked controller
     */
    private void syncConfigToController() {
        if (world == null || world.isClient() || localChestConfig == null) return;

        for (BlockPos blockPos : linkedBlocks) {
            BlockEntity be = world.getBlockEntity(blockPos);
            if (be instanceof StorageControllerBlockEntity controller) {
                // Controller's updateChestConfig handles priority shifting
                controller.updateChestConfig(localChestConfig.position, localChestConfig);
                controller.markDirty();
            }
        }
    }

    // ========================================
    // LINKING MANAGEMENT
    // ========================================

    public boolean addLinkedBlock(BlockPos blockPos) {
        if (world == null) return false;

        BlockEntity newBE = world.getBlockEntity(blockPos);

        // If it's a controller, REMOVE ALL STALE CONTROLLER LINKS FIRST
        if (newBE instanceof StorageControllerBlockEntity) {
            // Remove any position that CLAIMS to be a controller but isn't valid anymore
            linkedBlocks.removeIf(existingPos -> {
                BlockEntity existingBE = world.getBlockEntity(existingPos);

                // Remove if:
                // 1. No block entity at that position
                // 2. Block entity is a controller (remove ALL old controllers)
                return existingBE == null || existingBE instanceof StorageControllerBlockEntity;
            });
        }

        // Now add the new controller
        if (!linkedBlocks.contains(blockPos)) {
            linkedBlocks.add(blockPos);
            linkedBlocksCopyDirty = true;
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);

                // Sync config
                if (newBE instanceof StorageControllerBlockEntity controller && localChestConfig != null) {
                    controller.updateChestConfig(localChestConfig.position, localChestConfig);
                }
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


    public void updateLocalConfig(ChestConfig config) {
        if (config == null) return;

        this.localChestConfig = config.copy();

        // Ensure SimplePriority is set
        if (this.localChestConfig.simplePrioritySelection == null) {
            this.localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
        }

        markDirty();

        // Sync to world for client updates
        if (world != null) {
            BlockState state = getCachedState();
            world.updateListeners(pos, state, state, 3);
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

    // ========================================
    // ITEM ACCEPTANCE LOGIC
    // ========================================

    public boolean accepts(ItemVariant incoming) {
        if (world == null) return false;

        // OPTIMIZATION: Check space cache first
        long currentTime = world.getTime();
        if (cachedHasSpace != null && currentTime == spaceCheckTime) {
            if (!cachedHasSpace) return false;
        }

        Inventory inv = getTargetInventory();
        if (inv == null) {
            cachedHasSpace = false;
            spaceCheckTime = currentTime;
            return false;
        }

        // Quick space check with early exit
        boolean hasSpace = hasSpaceInInventoryFast(inv, incoming, 1);
        if (!hasSpace) {
            cachedHasSpace = false;
            spaceCheckTime = currentTime;
            return false;
        }

        cachedHasSpace = true;
        spaceCheckTime = currentTime;

        // Now check filter rules
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
                    return itemCategory.equals(chestConfig.filterCategory) ||
                            chestConfig.filterCategory.equals(Category.ALL);

                case BLACKLIST:
                    return !itemCategory.equals(chestConfig.filterCategory);

                case CUSTOM:
                    return acceptsByChestContents(inv, incoming, chestConfig.strictNBTMatch);

                default:
                    return false;
            }
        }

        // Fallback to probe mode
        if (mode == ProbeMode.ACCEPT_ALL || mode == ProbeMode.PRIORITY) {
            return true;
        }

        if (mode == ProbeMode.FILTER) {
            if (useTags) {
                return SortUtil.acceptsByInventoryTags(inv, incoming, requireAllTags);
            }

            // OPTIMIZED: Early exit on first match
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

    private boolean hasSpaceInInventoryFast(Inventory inv, ItemVariant variant, int amount) {
        if (inv == null) return false;

        int invSize = inv.size();
        ItemStack variantStack = variant.toStack(1);

        // SINGLE PASS - check for matching stacks OR empty slots
        for (int i = 0; i < invSize; i++) {
            ItemStack stack = inv.getStack(i);

            if (stack.isEmpty()) {
                return true; // Found empty slot
            } else if (ItemStack.areItemsAndComponentsEqual(stack, variantStack)) {
                int maxStack = Math.min(stack.getMaxCount(), inv.getMaxCountPerStack());
                if (stack.getCount() < maxStack) {
                    return true; // Can stack
                }
            }
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
                // Exact match including NBT
                ItemVariant existingVariant = ItemVariant.of(stack);
                if (existingVariant.equals(incoming)) {
                    foundMatch = true;
                    break;
                }
            } else {
                // Only match item type, ignore NBT
                if (stack.getItem() == incoming.getItem()) {
                    foundMatch = true;
                    break;
                }
            }
        }

        // Empty chest in CUSTOM mode rejects everything
        if (!foundAnyItem) {
            return false; // Custom filter mode: empty chest accepts nothing
        }

        // Found match - now check if there's space
        return foundMatch && hasSpaceInInventory(inv, incoming, 1);
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

    // ========================================
    // SCREEN HANDLER FACTORY
    // ========================================

    @Override
    public Text getDisplayName() {
        return Text.literal("Output Probe");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new OutputProbeScreenHandler(syncId, playerInventory, this);
    }

    @Override
    public ProbeData getScreenOpeningData(ServerPlayerEntity player) {
        BlockPos targetPos = getTargetPos();
        ChestConfig config = getChestConfig();

        return new ProbeData(
                targetPos != null ? targetPos : BlockPos.ORIGIN,
                config
        );
    }

    // ========================================
    // CLEANUP
    // ========================================

    public void onRemoved(World world) {
        if (world == null || world.isClient()) return;

        BlockPos targetPos = getTargetPos();

        // Make a copy to avoid concurrent modification
        List<BlockPos> linkedBlocksCopy = new ArrayList<>(linkedBlocks);

        for (BlockPos blockPos : linkedBlocksCopy) {
            BlockEntity be = world.getBlockEntity(blockPos);

            if (be instanceof StorageControllerBlockEntity controller) {
                // Remove this probe from controller
                controller.removeProbe(pos);

                // Check if we should remove the chest config
                if (targetPos != null) {
                    boolean stillHasProbe = false;

                    // Check remaining probes in the controller
                    List<BlockPos> remainingProbes = controller.getLinkedProbes();

                    for (BlockPos otherProbePos : remainingProbes) {
                        // Skip if it's this probe (shouldn't happen, but safety check)
                        if (otherProbePos.equals(pos)) {
                            continue;
                        }

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
        localChestConfig = null;
    }

    public void clearController() {
        linkedBlocks.removeIf(blockPos -> {
            return true; // Remove everything when clearing
        });
        linkedBlocksCopyDirty = true;

        markDirty();

        if (world != null) {
            BlockState state = world.getBlockState(pos);
            world.updateListeners(pos, state, state, 3);
        }
    }

    public void removeController(BlockPos controllerPos) {
        if (linkedBlocks.remove(controllerPos)) {
            linkedBlocksCopyDirty = true;
            markDirty();

            if (world != null) {
                BlockState state = world.getBlockState(pos);
                world.updateListeners(pos, state, state, 3);
            }
        }
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

        // Save local chest config
        if (localChestConfig != null) {
            view.putBoolean("has_local_config", true);
            view.putLong("local_chest_pos", localChestConfig.position.asLong());
            view.putString("local_chest_name", localChestConfig.customName != null ? localChestConfig.customName : "");
            view.putString("local_chest_category", localChestConfig.filterCategory.asString());
            view.putInt("local_chest_priority", localChestConfig.priority);
            view.putString("local_chest_mode", localChestConfig.filterMode.name());
            view.putBoolean("local_chest_nbt", localChestConfig.strictNBTMatch);
            view.putBoolean("local_chest_frame", localChestConfig.autoItemFrame);

            if (localChestConfig.simplePrioritySelection != null) {
                view.putString("local_simple_priority", localChestConfig.simplePrioritySelection.name());
            }
        } else {
            view.putBoolean("has_local_config", false);
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

        // Save local chest config
        if (localChestConfig != null) {
            nbt.putBoolean("has_local_config", true);
            nbt.putLong("local_chest_pos", localChestConfig.position.asLong());
            nbt.putString("local_chest_name", localChestConfig.customName != null ? localChestConfig.customName : "");
            nbt.putString("local_chest_category", localChestConfig.filterCategory.asString());
            nbt.putInt("local_chest_priority", localChestConfig.priority);
            nbt.putString("local_chest_mode", localChestConfig.filterMode.name());
            nbt.putBoolean("local_chest_nbt", localChestConfig.strictNBTMatch);
            nbt.putBoolean("local_chest_frame", localChestConfig.autoItemFrame);
            if (localChestConfig.simplePrioritySelection != null) {
                nbt.putString("local_simple_priority", localChestConfig.simplePrioritySelection.name());
            }
        } else {
            nbt.putBoolean("has_local_config", false);
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

        // Load local chest config
        if (view.getBoolean("has_local_config", false)) {
            view.getOptionalLong("local_chest_pos").ifPresent(posLong -> {
                BlockPos chestPos = BlockPos.fromLong(posLong);
                String name = view.getString("local_chest_name", "");
                String categoryStr = view.getString("local_chest_category", "smartsorter:all");
                int priority = view.getInt("local_chest_priority", 1);
                String modeStr = view.getString("local_chest_mode", "NONE");
                boolean strictNBT = view.getBoolean("local_chest_nbt", false);
                boolean autoFrame = view.getBoolean("local_chest_frame", false);

                Category category = CategoryManager.getInstance().getCategory(categoryStr);
                ChestConfig.FilterMode filterMode = ChestConfig.FilterMode.valueOf(modeStr);

                localChestConfig = new ChestConfig(chestPos, name, category, priority, filterMode, autoFrame);
                localChestConfig.strictNBTMatch = strictNBT;

                String simplePriorityStr = view.getString("local_simple_priority", null);
                if (simplePriorityStr != null && !simplePriorityStr.isEmpty()) {
                    try {
                        localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.valueOf(simplePriorityStr);
                    } catch (Exception e) {
                        localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                    }
                } else {
                    // Default to MEDIUM if not saved
                    localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                }
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

        // Save local chest config
        if (localChestConfig != null) {
            nbt.putBoolean("has_local_config", true);
            nbt.putLong("local_chest_pos", localChestConfig.position.asLong());
            nbt.putString("local_chest_name", localChestConfig.customName != null ? localChestConfig.customName : "");
            nbt.putString("local_chest_category", localChestConfig.filterCategory.asString());
            nbt.putInt("local_chest_priority", localChestConfig.priority);
            nbt.putString("local_chest_mode", localChestConfig.filterMode.name());
            nbt.putBoolean("local_chest_nbt", localChestConfig.strictNBTMatch);
            nbt.putBoolean("local_chest_frame", localChestConfig.autoItemFrame);

            // Save SimplePriority for <1.21.8
            if (localChestConfig.simplePrioritySelection != null) {
                nbt.putString("local_simple_priority", localChestConfig.simplePrioritySelection.name());
            }
        } else {
            nbt.putBoolean("has_local_config", false);
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

        // Load local chest config
        if (nbt.getBoolean("has_local_config")) {
            if (nbt.contains("local_chest_pos")) {
                BlockPos chestPos = BlockPos.fromLong(nbt.getLong("local_chest_pos"));
                String name = nbt.getString("local_chest_name");
                String categoryStr = nbt.getString("local_chest_category");
                int priority = nbt.getInt("local_chest_priority");
                String modeStr = nbt.getString("local_chest_mode");
                boolean strictNBT = nbt.getBoolean("local_chest_nbt");
                boolean autoFrame = nbt.getBoolean("local_chest_frame");

                Category category = CategoryManager.getInstance().getCategory(categoryStr);
                ChestConfig.FilterMode filterMode = ChestConfig.FilterMode.valueOf(modeStr);

                localChestConfig = new ChestConfig(chestPos, name, category, priority, filterMode, autoFrame);
                localChestConfig.strictNBTMatch = strictNBT;

                // Load SimplePriority for <1.21.8
                if (nbt.contains("local_simple_priority")) {
                    try {
                        localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.valueOf(
                            nbt.getString("local_simple_priority")
                        );
                    } catch (Exception e) {
                        localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                    }
                } else {
                    localChestConfig.simplePrioritySelection = ChestConfig.SimplePriority.MEDIUM;
                }
            }
        }
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