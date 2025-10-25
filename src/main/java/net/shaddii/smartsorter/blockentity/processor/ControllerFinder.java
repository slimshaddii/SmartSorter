package net.shaddii.smartsorter.blockentity.processor;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.shaddii.smartsorter.blockentity.StorageControllerBlockEntity;

import java.util.*;

/**
 * Finds storage controllers through redstone network using staged BFS.
 * Optimized with distance-based stages and manhattan distance checks.
 */
public class ControllerFinder {
    private static final int[] SEARCH_STAGES = {8, 16, 32, 64, 128};

    // Cache for recent searches (position -> controller position)
    private static final Map<BlockPos, CachedResult> cache = new HashMap<>();
    private static final long CACHE_DURATION = 200L;

    private static class CachedResult {
        final BlockPos controllerPos;
        final long timestamp;

        CachedResult(BlockPos pos, long time) {
            this.controllerPos = pos;
            this.timestamp = time;
        }

        boolean isValid(long currentTime) {
            return currentTime - timestamp < CACHE_DURATION;
        }
    }

    /**
     * Finds a storage controller connected via redstone network.
     * Uses staged search (8 → 16 → 32 → 64 → 128 blocks) for optimization.
     */
    public static BlockPos findController(ServerWorld world, BlockPos start) {
        // Check cache first
        CachedResult cached = cache.get(start);
        if (cached != null && cached.isValid(world.getTime())) {
            // Validate cached result
            if (cached.controllerPos != null) {
                BlockEntity be = world.getBlockEntity(cached.controllerPos);
                if (be instanceof StorageControllerBlockEntity) {
                    return cached.controllerPos;
                }
            }
            // Cache says no controller
            return null;
        }

        // Staged search
        BlockPos result = null;
        for (int radius : SEARCH_STAGES) {
            result = searchRadius(world, start, radius);
            if (result != null) break;
        }

        // Cache result
        cache.put(start, new CachedResult(result, world.getTime()));

        // Clean old cache entries periodically
        if (cache.size() > 100) {
            cleanCache(world.getTime());
        }

        return result;
    }

    /**
     * Searches for controller within given radius using BFS.
     */
    private static BlockPos searchRadius(ServerWorld world, BlockPos start, int radius) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();

        queue.add(start);
        for (Direction dir : Direction.values()) {
            queue.add(start.offset(dir));
        }

        int blocksChecked = 0;
        int maxBlocks = radius * radius * 4;

        BlockPos closestController = null;
        double closestDistance = Double.MAX_VALUE;

        while (!queue.isEmpty() && blocksChecked < maxBlocks) {
            BlockPos current = queue.poll();

            if (!visited.add(current)) continue;

            // Check Manhattan distance
            int manhattanDist = getManhattanDistance(current, start);
            if (manhattanDist > radius) continue;

            blocksChecked++;

            // Check if this is a controller
            BlockEntity be = world.getBlockEntity(current);
            if (be instanceof StorageControllerBlockEntity) {
                double dist = start.getSquaredDistance(current);
                if (dist < closestDistance) {
                    closestDistance = dist;
                    closestController = current;
                }
                continue;
            }

            // Expand through redstone components
            BlockState state = world.getBlockState(current);
            if (isRedstoneComponent(state)) {
                expandSearch(queue, visited, current, state);
            }
        }

        return closestController;
    }

    /**
     * Adds adjacent positions to search queue based on redstone type.
     */
    private static void expandSearch(Queue<BlockPos> queue, Set<BlockPos> visited,
                                     BlockPos current, BlockState state) {
        // Repeaters have directional priority
        if (state.isOf(Blocks.REPEATER)) {
            Direction facing = state.get(net.minecraft.block.RepeaterBlock.FACING);
            queue.add(current.offset(facing));
            queue.add(current.offset(facing.getOpposite()));

            // Check sides for T-junctions
            for (Direction side : Direction.Type.HORIZONTAL) {
                if (side != facing && side != facing.getOpposite()) {
                    BlockPos sidePos = current.offset(side);
                    if (!visited.contains(sidePos)) {
                        queue.add(sidePos);
                    }
                }
            }
        } else {
            // Check all adjacent blocks
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.offset(dir);
                if (!visited.contains(neighbor)) {
                    queue.add(neighbor);
                }
            }

            // Check diagonals for redstone wire
            if (state.isOf(Blocks.REDSTONE_WIRE)) {
                for (Direction horizontal : Direction.Type.HORIZONTAL) {
                    for (Direction vertical : new Direction[]{Direction.UP, Direction.DOWN}) {
                        BlockPos diagonal = current.offset(horizontal).offset(vertical);
                        if (!visited.contains(diagonal)) {
                            queue.add(diagonal);
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if block is a redstone component.
     */
    private static boolean isRedstoneComponent(BlockState state) {
        return state.isOf(Blocks.REDSTONE_WIRE) ||
                state.isOf(Blocks.LEVER) ||
                state.isOf(Blocks.REDSTONE_TORCH) ||
                state.isOf(Blocks.REDSTONE_WALL_TORCH) ||
                state.isOf(Blocks.REDSTONE_BLOCK) ||
                state.isOf(Blocks.REPEATER) ||
                state.isOf(Blocks.COMPARATOR) ||
                state.emitsRedstonePower();
    }

    private static int getManhattanDistance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) +
                Math.abs(a.getY() - b.getY()) +
                Math.abs(a.getZ() - b.getZ());
    }

    private static void cleanCache(long currentTime) {
        cache.entrySet().removeIf(entry ->
                !entry.getValue().isValid(currentTime)
        );
    }

    /**
     * Clears the entire cache (call on world unload).
     */
    public static void clearCache() {
        cache.clear();
    }
}