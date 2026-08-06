package dev.modernity.neoncity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Opens a street door into Arnis ground floors that were imported without one.
 *
 * <p>The atlas is real mapped geometry, so a tile routinely contains a building whose only real
 * entrance was outside the mapped area, or was a feature the importer could not represent. Sealing
 * passes make that worse: {@link ArnisFacadeRepair} closes cross-sections where procedural terrain
 * cut a structure open, and one of those cuts is often the only hole a player could walk through.
 * The result is a skyline of buildings with no way in.</p>
 *
 * <p>The pass is deliberately confined to one chunk. Street classification comes from the layout
 * and the imported road ribbons, which are pure functions of the coordinate, and every block read
 * or written stays inside the chunk that is currently being generated, so the result does not
 * depend on which neighbours happen to exist yet. A building whose sealed ground floor spans
 * several chunks is opened by whichever of those chunks also contains the street it faces.</p>
 */
final class ArnisEntranceRepair {
    /** Rooms smaller than this are closets, machine spaces, and shafts - not places to walk into. */
    private static final int MIN_SEALED_ROOM_CELLS = 10;
    /** Thickest wall worth punching through; beyond this the "outside" is another room. */
    private static final int MAX_WALL_DEPTH = 3;
    /**
     * A chunk only ever sees its own slice of a building, so several chunks can each decide the
     * same structure needs a door. A low cap keeps that from turning a facade into an arcade.
     */
    private static final int MAX_DOORWAYS_PER_CHUNK = 2;
    private static final int PLACE_FLAGS =
            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS;
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private ArnisEntranceRepair() {
    }

    /**
     * @return the number of doorways opened in this chunk.
     */
    static int openStreetEntrances(
            ServerLevelAccessor level,
            ChunkPos chunk,
            NeonCityGenerator.UrbanSample[][] samples,
            District selectedDistrict) {
        int groundY = NeonCityGenerator.CITY_GROUND_Y + 1;
        boolean[] walkable = new boolean[256];
        boolean[] reachable = new boolean[256];
        ArrayDeque<Integer> frontier = new ArrayDeque<>();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int index = index(localX, localZ);
                int x = chunk.getMinBlockX() + localX;
                int z = chunk.getMinBlockZ() + localZ;
                walkable[index] = isWalkable(level, x, groundY, z);
                if (walkable[index]
                        && isStreetColumn(samples[localZ + 1][localX + 1], selectedDistrict, x, z)) {
                    reachable[index] = true;
                    frontier.add(index);
                }
            }
        }
        if (frontier.isEmpty()) return 0;
        flood(frontier, walkable, reachable);

        List<List<Integer>> sealedRooms = sealedRooms(walkable, reachable);
        int opened = 0;
        for (List<Integer> room : sealedRooms) {
            if (opened >= MAX_DOORWAYS_PER_CHUNK) break;
            if (openDoorway(level, chunk, groundY, room, reachable)) opened++;
        }
        return opened;
    }

    /** Expands the street-connected set over connected walkable ground inside the chunk. */
    private static void flood(
            ArrayDeque<Integer> frontier, boolean[] walkable, boolean[] reachable) {
        while (!frontier.isEmpty()) {
            int current = frontier.poll();
            int localX = current & 15;
            int localZ = current >> 4;
            for (Direction direction : HORIZONTAL) {
                int nextX = localX + direction.getStepX();
                int nextZ = localZ + direction.getStepZ();
                if (nextX < 0 || nextX > 15 || nextZ < 0 || nextZ > 15) continue;
                int next = index(nextX, nextZ);
                if (!walkable[next] || reachable[next]) continue;
                reachable[next] = true;
                frontier.add(next);
            }
        }
    }

    /**
     * Groups the walkable ground the street flood never reached into rooms, largest first so a
     * chunk that can only afford a couple of doorways spends them on the spaces that matter.
     */
    private static List<List<Integer>> sealedRooms(boolean[] walkable, boolean[] reachable) {
        boolean[] grouped = new boolean[256];
        List<List<Integer>> rooms = new ArrayList<>();
        for (int start = 0; start < 256; start++) {
            if (!walkable[start] || reachable[start] || grouped[start]) continue;
            List<Integer> room = new ArrayList<>();
            ArrayDeque<Integer> frontier = new ArrayDeque<>();
            frontier.add(start);
            grouped[start] = true;
            while (!frontier.isEmpty()) {
                int current = frontier.poll();
                room.add(current);
                int localX = current & 15;
                int localZ = current >> 4;
                for (Direction direction : HORIZONTAL) {
                    int nextX = localX + direction.getStepX();
                    int nextZ = localZ + direction.getStepZ();
                    if (nextX < 0 || nextX > 15 || nextZ < 0 || nextZ > 15) continue;
                    int next = index(nextX, nextZ);
                    if (!walkable[next] || reachable[next] || grouped[next]) continue;
                    grouped[next] = true;
                    frontier.add(next);
                }
            }
            if (room.size() >= MIN_SEALED_ROOM_CELLS) rooms.add(room);
        }
        rooms.sort(Comparator.comparingInt((List<Integer> room) -> room.size()).reversed()
                .thenComparingInt(room -> room.stream().mapToInt(Integer::intValue).min().orElse(0)));
        return rooms;
    }

    /** Punches the thinnest wall between one sealed room and the street it already touches. */
    private static boolean openDoorway(
            ServerLevelAccessor level,
            ChunkPos chunk,
            int groundY,
            List<Integer> room,
            boolean[] reachable) {
        List<Integer> ordered = new ArrayList<>(room);
        ordered.sort(Comparator.naturalOrder());
        for (int depth = 1; depth <= MAX_WALL_DEPTH; depth++) {
            for (int cell : ordered) {
                for (Direction direction : HORIZONTAL) {
                    if (carveDoorway(
                            level, chunk, groundY, cell & 15, cell >> 4, direction, depth,
                            reachable)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean carveDoorway(
            ServerLevelAccessor level,
            ChunkPos chunk,
            int groundY,
            int localX,
            int localZ,
            Direction direction,
            int wallDepth,
            boolean[] reachable) {
        int outsideX = localX + direction.getStepX() * (wallDepth + 1);
        int outsideZ = localZ + direction.getStepZ() * (wallDepth + 1);
        if (outsideX < 0 || outsideX > 15 || outsideZ < 0 || outsideZ > 15) return false;
        if (!reachable[index(outsideX, outsideZ)]) return false;
        for (int step = 1; step <= wallDepth; step++) {
            int wallLocalX = localX + direction.getStepX() * step;
            int wallLocalZ = localZ + direction.getStepZ() * step;
            BlockPos wall = new BlockPos(
                    chunk.getMinBlockX() + wallLocalX, groundY,
                    chunk.getMinBlockZ() + wallLocalZ);
            if (!isCarvableWall(level, wall) || !isCarvableWall(level, wall.above())) return false;
        }
        BlockPos threshold = new BlockPos(
                chunk.getMinBlockX() + localX + direction.getStepX() * wallDepth, groundY,
                chunk.getMinBlockZ() + localZ + direction.getStepZ() * wallDepth);
        for (int step = 1; step <= wallDepth; step++) {
            BlockPos opening = new BlockPos(
                    chunk.getMinBlockX() + localX + direction.getStepX() * step, groundY,
                    chunk.getMinBlockZ() + localZ + direction.getStepZ() * step);
            level.setBlock(opening, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
            level.setBlock(opening.above(), Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
        }
        BlockState lower = Blocks.COPPER_DOOR.waxed().weathered().defaultBlockState()
                .setValue(DoorBlock.FACING, direction)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.OPEN, false);
        level.setBlock(threshold, lower, PLACE_FLAGS);
        level.setBlock(threshold.above(),
                lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), PLACE_FLAGS);
        return true;
    }

    /**
     * A wall block may be replaced only when it is plain structure. Anything holding state - a
     * chest, a sign, a spawner an imported tile placed deliberately - is left alone even if that
     * costs this room its doorway.
     */
    private static boolean isCarvableWall(ServerLevelAccessor level, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return !state.isAir() && state.blocksMotion() && !state.hasBlockEntity()
                && state.getFluidState().isEmpty();
    }

    private static boolean isWalkable(ServerLevelAccessor level, int x, int groundY, int z) {
        BlockPos feet = new BlockPos(x, groundY, z);
        return level.getBlockState(feet.below()).blocksMotion()
                && !level.getBlockState(feet).blocksMotion()
                && !level.getBlockState(feet.above()).blocksMotion();
    }

    /**
     * Street classification, taken from the layout rather than the world so it never depends on
     * generation order. A retained atlas column is street only where the imported map puts a road
     * ribbon; every other column in an Arnis chunk was built by the procedural infrastructure pass,
     * which produces roads, plazas and parks rather than buildings.
     */
    private static boolean isStreetColumn(
            NeonCityGenerator.UrbanSample sample, District selectedDistrict, int x, int z) {
        return NeonCityGenerator.keepsArnisColumn(sample, selectedDistrict)
                ? NeonCityGenerator.isAtlasRoadSurfaceAt(x, z)
                : sample.roadClass() != NeonCityGenerator.RoadClass.WILDERNESS;
    }

    private static int index(int localX, int localZ) {
        return (localZ << 4) | localX;
    }
}
