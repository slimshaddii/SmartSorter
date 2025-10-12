package net.shaddii.smartsorter.util;

import net.minecraft.util.math.BlockPos;

/**
 * Common interface for blocks or entities that can link to a Storage Controller.
 */
public interface ControllerLinkable {
    void setController(BlockPos controllerPos);
    BlockPos getController();
}
