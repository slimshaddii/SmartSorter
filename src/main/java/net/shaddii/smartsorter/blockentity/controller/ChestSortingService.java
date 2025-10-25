package net.shaddii.smartsorter.blockentity.controller;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shaddii.smartsorter.blockentity.OutputProbeBlockEntity;
import net.shaddii.smartsorter.util.InventoryOrganizer;

import java.util.*;

/**
 * Handles chest sorting operations with overflow tracking.
 */
public class ChestSortingService {
    private final ItemRoutingService routingService;
    private final ProbeRegistry probeRegistry;
    private final NetworkInventoryManager networkManager;

    public ChestSortingService(ItemRoutingService routingService,
                               ProbeRegistry probeRegistry,
                               NetworkInventoryManager networkManager) {
        this.routingService = routingService;
        this.probeRegistry = probeRegistry;
        this.networkManager = networkManager;
    }

    /**
     * Sorts multiple chests in order.
     */
    public void sortChests(World world, List<BlockPos> chestPositions,
                           ServerPlayerEntity player) {
        if (world == null || world.isClient()) return;

        Map<ItemVariant, Long> overflowCounts = new HashMap<>();
        Map<ItemVariant, String> overflowDestinations = new HashMap<>();

        for (BlockPos chestPos : chestPositions) {
            sortChest(world, chestPos, overflowCounts, overflowDestinations);
        }

        // Send overflow notification
        if (player != null && !overflowCounts.isEmpty()) {
            sendOverflowNotification(player, overflowCounts, overflowDestinations);
        }
    }

    /**
     * Sorts a single chest into the network.
     * Optimized: Organizes inventory first, tracks modified chests.
     */
    public void sortChest(World world, BlockPos chestPos,
                          Map<ItemVariant, Long> overflowCounts,
                          Map<ItemVariant, String> overflowDestinations) {

        OutputProbeBlockEntity sourceProbe = findProbeForChest(world, chestPos);
        if (sourceProbe == null) return;

        Inventory sourceInv = sourceProbe.getTargetInventory();
        if (sourceInv == null) return;

        // Pre-organize for optimal extraction
        InventoryOrganizer.organize(sourceInv, InventoryOrganizer.Strategy.ADAPTIVE);

        // Extract all items
        List<ItemStack> itemsToSort = extractAllItems(sourceInv);
        if (itemsToSort.isEmpty()) return;

        // Track which chests we modify (for post-organization)
        Set<BlockPos> modifiedChests = new HashSet<>();

        // Insert items into network
        List<ItemStack> unsortedItems = new ArrayList<>();

        for (ItemStack stack : itemsToSort) {
            ItemVariant variant = ItemVariant.of(stack);
            int originalCount = stack.getCount();

            ItemRoutingService.InsertionResult result =
                    routingService.insertItem(world, stack);

            // Track destination
            if (result.destination() != null) {
                modifiedChests.add(result.destination());
            }

            // Track overflow
            if (result.overflowed()) {
                long overflowed = originalCount - result.remainder().getCount();
                if (overflowed > 0) {
                    overflowCounts.merge(variant, overflowed, Long::sum);
                    if (result.destinationName() != null) {
                        overflowDestinations.put(variant, result.destinationName());
                    }
                }
            }

            // Collect unsorted remainder
            if (!result.remainder().isEmpty()) {
                unsortedItems.add(result.remainder());
            }
        }

        // Return unsorted items to source
        for (ItemStack stack : unsortedItems) {
            insertIntoInventory(sourceProbe, stack);
        }

        // Organize modified destination chests
        organizeModifiedChests(world, modifiedChests);
    }

    private List<ItemStack> extractAllItems(Inventory inv) {
        List<ItemStack> items = new ArrayList<>();

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                items.add(inv.removeStack(i));
            }
        }

        inv.markDirty();
        return items;
    }

    private void organizeModifiedChests(World world, Set<BlockPos> chestPositions) {
        for (BlockPos chestPos : chestPositions) {
            OutputProbeBlockEntity probe = findProbeForChest(world, chestPos);
            if (probe != null) {
                Inventory inv = probe.getTargetInventory();
                if (inv != null) {
                    InventoryOrganizer.organize(inv, InventoryOrganizer.Strategy.ADAPTIVE);
                }
            }
        }
    }

    private OutputProbeBlockEntity findProbeForChest(World world, BlockPos chestPos) {
        for (BlockPos probePos : probeRegistry.getLinkedProbes()) {
            BlockEntity be = world.getBlockEntity(probePos);
            if (be instanceof OutputProbeBlockEntity probe) {
                if (chestPos.equals(probe.getTargetPos())) {
                    return probe;
                }
            }
        }
        return null;
    }

    private void insertIntoInventory(OutputProbeBlockEntity probe, ItemStack stack) {
        Inventory inv = probe.getTargetInventory();
        if (inv == null) return;

        for (int i = 0; i < inv.size() && !stack.isEmpty(); i++) {
            ItemStack slotStack = inv.getStack(i);

            if (slotStack.isEmpty()) {
                inv.setStack(i, stack.copy());
                stack.setCount(0);
            } else if (ItemStack.areItemsAndComponentsEqual(slotStack, stack)) {
                int maxStack = Math.min(slotStack.getMaxCount(), inv.getMaxCountPerStack());
                int canAdd = maxStack - slotStack.getCount();

                if (canAdd > 0) {
                    int toAdd = Math.min(canAdd, stack.getCount());
                    slotStack.increment(toAdd);
                    stack.decrement(toAdd);
                }
            }
        }

        inv.markDirty();
    }

    private void sendOverflowNotification(ServerPlayerEntity player,
                                          Map<ItemVariant, Long> overflowCounts,
                                          Map<ItemVariant, String> destinations) {
    }
}