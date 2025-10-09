package net.shaddii.smartsorter.screen;

import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;

/**
 * A lightweight, interactive placeholder slot representing a free slot
 * in the storage network. It behaves visually like a regular slot but
 * has no backing inventory container.
 */
public class VirtualFreeSlot extends Slot {

    private ItemStack displayStack = ItemStack.EMPTY;

    public VirtualFreeSlot(int index, int x, int y) {
        super(new DummyInventory(), index, x, y);
    }

    @Override
    public boolean hasStack() {
        return !displayStack.isEmpty();
    }

    @Override
    public ItemStack getStack() {
        return displayStack;
    }

    @Override
    public void setStack(ItemStack stack) {
        this.displayStack = stack;
        this.markDirty();
    }

    @Override
    public void markDirty() {
        // optional hook for UI refresh
    }

    @Override
    public boolean canInsert(ItemStack stack) {
        // allow deposit interaction from player
        return true;
    }

    @Override
    public ItemStack takeStack(int amount) {
        if (displayStack.isEmpty()) return ItemStack.EMPTY;

        ItemStack taken = displayStack.split(amount);
        if (displayStack.getCount() <= 0) displayStack = ItemStack.EMPTY;
        return taken;
    }

    // simple static dummy inventory backing these free slots
    private static class DummyInventory implements Inventory {
        @Override public int size() { return 0; }
        @Override public boolean isEmpty() { return true; }
        @Override public ItemStack getStack(int slot) { return ItemStack.EMPTY; }
        @Override public ItemStack removeStack(int slot, int amount) { return ItemStack.EMPTY; }
        @Override public ItemStack removeStack(int slot) { return ItemStack.EMPTY; }
        @Override public void setStack(int slot, ItemStack stack) {}
        @Override public void markDirty() {}
        @Override public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity player) { return true; }
        @Override public void clear() {}
    }
}
