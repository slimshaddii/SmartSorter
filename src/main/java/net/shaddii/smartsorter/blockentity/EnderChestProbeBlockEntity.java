package net.shaddii.smartsorter.blockentity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.shaddii.smartsorter.SmartSorter;
import org.jetbrains.annotations.Nullable;

//? if >=1.21.8 {
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
//?} else {
/*import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
*///?}

import java.util.UUID;

/**
 * Simpler ender chest probe - only works when owner is online
 */
public class EnderChestProbeBlockEntity extends OutputProbeBlockEntity {

    @Nullable
    private UUID ownerUUID = null;

    public EnderChestProbeBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    public void setOwner(UUID playerUUID) {
        this.ownerUUID = playerUUID;
        markDirty();
    }

    public void setOwner(PlayerEntity player) {
        setOwner(player.getUuid());
    }

    @Nullable
    public UUID getOwner() {
        return ownerUUID;
    }

    /**
     * Override to return ender chest inventory if owner is online
     */
    @Override
    public Inventory getTargetInventory() {
        if (world == null || world.isClient()) return null;
        if (!(world instanceof ServerWorld serverWorld)) return null;
        if (ownerUUID == null) return null;

        // Try to get online player
        ServerPlayerEntity player = serverWorld.getServer().getPlayerManager().getPlayer(ownerUUID);

        if (player != null) {
            // Player is online - return their ender chest inventory
            return player.getEnderChestInventory();
        }

        // Player offline - can't access
        return null;
    }

    // ========================================
    // NBT SERIALIZATION
    // ========================================

    //? if >=1.21.8 {
    @Override
    public void writeData(WriteView view) {
        super.writeData(view);

        if (ownerUUID != null) {
            view.putBoolean("hasOwner", true);
            view.putLong("ownerMost", ownerUUID.getMostSignificantBits());
            view.putLong("ownerLeast", ownerUUID.getLeastSignificantBits());
        } else {
            view.putBoolean("hasOwner", false);
        }
    }

    @Override
    public void readData(ReadView view) {
        super.readData(view);

        if (view.getBoolean("hasOwner", false)) {
            long most = view.getLong("ownerMost", 0);
            long least = view.getLong("ownerLeast", 0);
            if (most != 0 || least != 0) {
                this.ownerUUID = new UUID(most, least);
            }
        }
    }
    //?} else {
    /*@Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);

        if (ownerUUID != null) {
            nbt.putUuid("ownerUUID", ownerUUID);
        }
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);

        if (nbt.containsUuid("ownerUUID")) {
            this.ownerUUID = nbt.getUuid("ownerUUID");
        }
    }
    *///?}
}