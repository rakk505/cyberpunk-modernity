package dev.modernity.neoncity;

import com.example.cyberdeck.advertising.AdCampaign;
import com.example.cyberdeck.advertising.AdDisplayBlock;
import com.example.cyberdeck.advertising.AdDisplayBlockEntity;
import com.example.cyberdeck.advertising.AdDisplayPlacement;
import com.example.cyberdeck.advertising.AdvertisingContent;
import com.example.cyberdeck.advertising.LargeAdSurfaceValidator;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

/**
 * Live megascreen placement for the first row of buildings along a highway corridor.
 *
 * <p>The offline {@code GeneratedAdSurfaceCatalog} only knows rectangles that exist inside a single
 * audited Arnis template, so the large blank walls that face the inter-district connections are
 * frequently missed: they are produced by the runtime setback and infrastructure carving rather
 * than by the source atlas. This pass scans the one building face in a chunk that actually looks at
 * the highway and covers the largest flat, window-free rectangle on it.
 *
 * <p>Everything here is derived from the finished world state plus the pure {@link MegacityLayout},
 * so it is deterministic for a given seed and safe to replay: a chunk that already carries its
 * display re-validates and keeps it instead of placing a second one.</p>
 */
final class HighwayFacadeAdGeneration {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Chunks whose centre sits further than this from the connection centreline are considered
     * back-row buildings. The corridor itself is {@code CONNECTION_CLEARANCE_RADIUS} wide, so this
     * keeps roughly the first building depth beyond the setback.
     */
    static final double MAX_CENTER_DISTANCE =
            MegacityLayout.CONNECTION_CLEARANCE_RADIUS + 32.0;
    /** Lowest row a highway megascreen may occupy, keeping displays off buried foundations. */
    static final int MIN_DISPLAY_Y = NeonCityGenerator.CITY_GROUND_Y + 1;
    /** How deep into the chunk the outward-facing wall search may travel. */
    private static final int MAX_FACE_DEPTH = 15;
    /** Ranked alternatives tried when the best rectangle is blocked by live geometry. */
    private static final int MAX_PLACEMENT_ATTEMPTS = 8;

    /** Outcome of one chunk pass; {@code RETRYABLE} means neighbouring city chunks are not final. */
    enum Result {
        NOT_APPLICABLE,
        PLACED,
        NONE_FOUND,
        RETRYABLE
    }

    /** One candidate rectangle in chunk-local face coordinates. */
    record Candidate(int column, int row, int width, int height, int depth) {
        int area() {
            return width * height;
        }

        /** Bigger first, then taller, then closer to the highway, then a stable scan order. */
        boolean betterThan(Candidate other) {
            if (other == null) return true;
            if (area() != other.area()) return area() > other.area();
            if (height != other.height) return height > other.height;
            if (depth != other.depth) return depth < other.depth;
            if (column != other.column) return column < other.column;
            return row < other.row;
        }
    }

    private HighwayFacadeAdGeneration() {
    }

    /**
     * Places at most one highway-facing megascreen in {@code chunk}. Returns {@code RETRYABLE} only
     * when the answer genuinely depends on city chunks that have not been generated yet.
     */
    static Result decorateChunk(ServerLevel level, ChunkPos chunk) {
        MegacityLayout activeLayout = NeonCityGenerator.layout();
        if (activeLayout == null || !NeonCityGenerator.isMegacityWorld(level)) {
            return Result.NOT_APPLICABLE;
        }
        Direction facing = highwayFacing(activeLayout, chunk).orElse(null);
        if (facing == null) {
            return Result.NOT_APPLICABLE;
        }
        // Story and gig buildings are authored down to their interior variants, so they are left
        // exactly as the mission planner produced them, like the catalog facade pass does.
        if (NeonCityGenerator.isReservedMainlineBuildingChunk(level, chunk)) {
            return Result.NOT_APPLICABLE;
        }
        if (!frontNeighborReady(level, chunk, facing)) {
            return Result.RETRYABLE;
        }
        // Replaying this pass — a regenerated chunk, or the backfill queue revisiting an older
        // save — must adopt the screen that is already on this wall instead of adding a second
        // one beside it. A catalog facade pointing at the highway counts as already served.
        if (hasHighwayFacingDisplay(level, chunk, facing)) {
            return Result.PLACED;
        }

        List<Placement> candidates = candidatePlacements(level, chunk, facing, MIN_DISPLAY_Y);
        if (candidates.isEmpty()) {
            return Result.NONE_FOUND;
        }

        List<BoundingBox> existing = existingDisplayBounds(level, chunk, facing);
        for (Placement candidate : candidates) {
            BlockPos anchor = candidate.anchor();
            if (intersectsAny(
                    bounds(anchor, facing, candidate.width(), candidate.height()), existing)) {
                continue;
            }
            LargeAdSurfaceValidator.Result validation = LargeAdSurfaceValidator.validateOverlay(
                    level, anchor, facing, candidate.width(), candidate.height());
            if (!validation.valid()) {
                if (validation.failure() == LargeAdSurfaceValidator.Failure.CHUNK_UNLOADED) {
                    return Result.RETRYABLE;
                }
                continue;
            }
            // Highway megascreens run their own campaign rather than the district rotation, so
            // the corridor reads as a continuous run of roadside advertising.
            if (AdDisplayPlacement.placeOverlay(
                    level,
                    anchor,
                    facing,
                    candidate.width(),
                    candidate.height(),
                    AdCampaign.HIGHWAY)) {
                LOGGER.debug(
                        "[NeonCity] placed {}x{} highway megascreen facing {} at {}",
                        candidate.width(), candidate.height(), facing, anchor);
                return Result.PLACED;
            }
        }
        return Result.NONE_FOUND;
    }

    /** A world-space rectangle proposal: the rendering anchor plus its face dimensions. */
    record Placement(BlockPos anchor, Direction facing, int width, int height) {
    }

    /**
     * Ranked rectangles on the chunk's {@code facing} wall, largest first, before any live-world
     * clearance validation. {@code floorY} is the lowest row a display may occupy.
     */
    static List<Placement> candidatePlacements(
            ServerLevel level, ChunkPos chunk, Direction facing, int floorY) {
        FaceScan scan = scanFace(level, chunk, facing, floorY);
        if (scan == null) {
            return List.of();
        }
        List<Placement> placements = new ArrayList<>();
        for (Candidate candidate
                : rankedCandidates(scan.rows(), scan.depth(), scan.valid())) {
            placements.add(new Placement(
                    scan.anchor(candidate), facing, candidate.width(), candidate.height()));
        }
        return List.copyOf(placements);
    }

    /**
     * Horizontal direction from the chunk centre to the nearest connection centreline, or empty
     * when the chunk is not part of the highway-facing band.
     */
    static Optional<Direction> highwayFacing(MegacityLayout activeLayout, ChunkPos chunk) {
        double centerX = chunk.getMinBlockX() + 7.5;
        double centerZ = chunk.getMinBlockZ() + 7.5;
        MegacityLayout.Location location = activeLayout.locate(
                Math.round((float) centerX), Math.round((float) centerZ));
        if (!location.insideCity()) {
            return Optional.empty();
        }
        MegacityLayout.ConnectionProjection projection =
                activeLayout.nearestConnection(centerX, centerZ).orElse(null);
        if (projection == null
                || projection.distance() > MAX_CENTER_DISTANCE
                || projection.distance() < MegacityLayout.CONNECTION_HALF_WIDTH) {
            return Optional.empty();
        }
        double deltaX = projection.x() - centerX;
        double deltaZ = projection.z() - centerZ;
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return Optional.of(deltaX >= 0.0 ? Direction.EAST : Direction.WEST);
        }
        return Optional.of(deltaZ >= 0.0 ? Direction.SOUTH : Direction.NORTH);
    }

    /**
     * The chunk immediately toward the highway supplies the front clearance and the outward
     * exposure ray, so its city blocks must already be final before any answer is meaningful.
     */
    private static boolean frontNeighborReady(
            ServerLevel level, ChunkPos chunk, Direction facing) {
        ChunkPos front = new ChunkPos(
                chunk.x() + facing.getStepX(), chunk.z() + facing.getStepZ());
        if (NeonCityGenerator.chunkTouchesCity(front.x(), front.z())
                && !NeonCityGenerator.isGenerated(front)) {
            return false;
        }
        // The scan reads up to three blocks past the border, so the neighbour must also be
        // resident; otherwise the probe would either force a load or read void air.
        return level.getChunkSource().getChunkNow(front.x(), front.z()) != null;
    }

    /**
     * Outermost wall surface of every chunk-local face cell, in {@code (column, row)} coordinates
     * aligned with {@link LargeAdSurfaceValidator#rightOf(Direction)} and world up.
     */
    private record FaceScan(
            BlockPos base,
            Direction facing,
            Direction right,
            int rows,
            int[][] depth,
            boolean[][] valid) {
        /** Bottom-left air cell of a candidate, which is the display's rendering anchor. */
        BlockPos anchor(Candidate candidate) {
            return base
                    .relative(right, candidate.column())
                    .relative(facing.getOpposite(), candidate.depth() - 1)
                    .above(candidate.row());
        }
    }

    private static FaceScan scanFace(
            ServerLevel level, ChunkPos chunk, Direction facing, int floorY) {
        Direction right = LargeAdSurfaceValidator.rightOf(facing);
        int baseX = right == Direction.WEST || facing == Direction.EAST
                ? chunk.getMaxBlockX()
                : chunk.getMinBlockX();
        int baseZ = right == Direction.NORTH || facing == Direction.SOUTH
                ? chunk.getMaxBlockZ()
                : chunk.getMinBlockZ();

        int topY = Integer.MIN_VALUE;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                topY = Math.max(topY, level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        chunk.getMinBlockX() + localX,
                        chunk.getMinBlockZ() + localZ) - 1);
            }
        }
        topY = Math.min(topY, NeonCityGenerator.MAX_BUILD_Y);
        int rows = topY - floorY + 1;
        if (rows < LargeAdSurfaceValidator.MIN_HEIGHT) {
            return null;
        }

        BlockPos base = new BlockPos(baseX, floorY, baseZ);
        int[][] depth = new int[16][rows];
        boolean[][] valid = new boolean[16][rows];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean anyValid = false;
        for (int column = 0; column < 16; column++) {
            BlockPos columnBase = base.relative(right, column);
            for (int row = 0; row < rows; row++) {
                int y = floorY + row;
                int firstSolid = -1;
                for (int step = 0; step <= MAX_FACE_DEPTH; step++) {
                    cursor.set(
                            columnBase.getX() + facing.getOpposite().getStepX() * step,
                            y,
                            columnBase.getZ() + facing.getOpposite().getStepZ() * step);
                    if (!level.getBlockState(cursor).canBeReplaced()) {
                        firstSolid = step;
                        break;
                    }
                }
                // step 0 is the chunk's own outer cell; its air cell would fall outside the chunk.
                if (firstSolid < 1) {
                    depth[column][row] = -1;
                    continue;
                }
                depth[column][row] = firstSolid;
                BlockState support = level.getBlockState(cursor);
                boolean sturdy = support.isFaceSturdy(level, cursor, facing);
                boolean opaqueEnough = !LargeAdSurfaceValidator.isGlass(support)
                        || LargeAdSurfaceValidator.isLuminousFacadeBlock(support);
                // Front clearance decides the rectangle rather than rejecting it afterwards:
                // a street light or awning across the bottom rows should shrink the screen, not
                // cancel it.
                valid[column][row] = sturdy && opaqueEnough
                        && frontClear(level, cursor, facing);
                anyValid |= valid[column][row];
            }
        }
        return anyValid
                ? new FaceScan(base, facing, right, rows, depth, valid)
                : null;
    }

    /**
     * True when the display cell in front of {@code support} and the whole clearance volume beyond
     * it are open, matching {@link LargeAdSurfaceValidator#GENERATED_FRONT_CLEARANCE}. The volume
     * reaches at most three blocks past the chunk border, which the caller has already confirmed is
     * both generated and loaded.
     */
    private static boolean frontClear(ServerLevel level, BlockPos support, Direction facing) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int depth = 1; depth <= LargeAdSurfaceValidator.GENERATED_FRONT_CLEARANCE + 1;
                depth++) {
            probe.set(
                    support.getX() + facing.getStepX() * depth,
                    support.getY(),
                    support.getZ() + facing.getStepZ() * depth);
            BlockState state = level.getBlockState(probe);
            if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Largest window-free rectangles per coplanar wall depth, best first. Rectangles are found with
     * a running-height sweep so a face broken up by windows still yields its biggest blank panel.
     * Only cells sharing one wall depth may form a rectangle, so a stepped facade never produces a
     * display that floats away from part of its own wall.
     */
    static List<Candidate> rankedCandidates(int rows, int[][] depth, boolean[][] valid) {
        List<Candidate> best = new ArrayList<>(MAX_PLACEMENT_ATTEMPTS);
        // run[column] counts the usable rows ending at the current one; runDepth[column] is the
        // wall plane they all share, so a column that steps in or out restarts its run.
        int[] run = new int[16];
        int[] runDepth = new int[16];
        Arrays.fill(runDepth, -1);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < 16; column++) {
                int cellDepth = depth[column][row];
                boolean usable = valid[column][row]
                        && cellDepth >= 1
                        && cellDepth <= MAX_FACE_DEPTH;
                if (!usable) {
                    run[column] = 0;
                    runDepth[column] = -1;
                } else if (runDepth[column] == cellDepth) {
                    run[column]++;
                } else {
                    run[column] = 1;
                    runDepth[column] = cellDepth;
                }
            }
            for (int left = 0; left < 16; left++) {
                if (run[left] < LargeAdSurfaceValidator.MIN_HEIGHT) {
                    continue;
                }
                int wallDepth = runDepth[left];
                int minRun = Integer.MAX_VALUE;
                for (int rightEdge = left; rightEdge < 16; rightEdge++) {
                    if (runDepth[rightEdge] != wallDepth) {
                        break;
                    }
                    minRun = Math.min(minRun, run[rightEdge]);
                    if (minRun < LargeAdSurfaceValidator.MIN_HEIGHT) {
                        break;
                    }
                    int width = rightEdge - left + 1;
                    if (width < LargeAdSurfaceValidator.MIN_WIDTH) {
                        continue;
                    }
                    int height = Math.min(minRun, LargeAdSurfaceValidator.MAX_HEIGHT);
                    offer(best, new Candidate(
                            left, row - height + 1, width, height, wallDepth));
                }
            }
        }
        return best;
    }

    /** Keeps the best {@value #MAX_PLACEMENT_ATTEMPTS} distinct rectangles in ranked order. */
    private static void offer(List<Candidate> best, Candidate candidate) {
        // The sweep proposes on the order of a million rectangles per face, so reject the common
        // case before paying for the linear membership scan.
        if (best.size() >= MAX_PLACEMENT_ATTEMPTS
                && !candidate.betterThan(best.get(best.size() - 1))) {
            return;
        }
        if (best.contains(candidate)) {
            return;
        }
        int index = 0;
        while (index < best.size() && !candidate.betterThan(best.get(index))) {
            index++;
        }
        best.add(index, candidate);
        if (best.size() > MAX_PLACEMENT_ATTEMPTS) {
            best.remove(best.size() - 1);
        }
    }

    /** True when this chunk already anchors a generated screen pointing at the highway. */
    private static boolean hasHighwayFacingDisplay(
            ServerLevel level, ChunkPos chunk, Direction facing) {
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (loaded == null) {
            return false;
        }
        for (BlockEntity blockEntity : loaded.getBlockEntities().values()) {
            if (blockEntity instanceof AdDisplayBlockEntity display
                    && display.generatedPlacement()
                    && blockEntity.getBlockState()
                            .is(AdvertisingContent.AD_DISPLAY_ANCHOR.get())
                    && blockEntity.getBlockState().getValue(AdDisplayBlock.FACING) == facing) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rendered rectangles of displays already anchored in this chunk or its horizontal neighbours,
     * so a catalog facade and a highway megascreen never draw over each other.
     */
    private static List<BoundingBox> existingDisplayBounds(
            ServerLevel level, ChunkPos chunk, Direction facing) {
        List<BoundingBox> bounds = new ArrayList<>();
        collectDisplayBounds(level, chunk, bounds);
        for (Direction neighbor : Direction.Plane.HORIZONTAL) {
            collectDisplayBounds(
                    level,
                    new ChunkPos(
                            chunk.x() + neighbor.getStepX(), chunk.z() + neighbor.getStepZ()),
                    bounds);
        }
        // Displays mounted on the same wall always anchor in the air layer, which the facing
        // neighbour can own when a building sits flush against the chunk border.
        collectDisplayBounds(
                level,
                new ChunkPos(chunk.x() + facing.getStepX(), chunk.z() + facing.getStepZ()),
                bounds);
        return bounds;
    }

    private static void collectDisplayBounds(
            ServerLevel level, ChunkPos chunk, List<BoundingBox> bounds) {
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (loaded == null) {
            return;
        }
        for (BlockEntity blockEntity : loaded.getBlockEntities().values()) {
            if (!(blockEntity instanceof AdDisplayBlockEntity display)) {
                continue;
            }
            BlockState state = blockEntity.getBlockState();
            if (!state.is(AdvertisingContent.AD_DISPLAY_ANCHOR.get())) {
                continue;
            }
            bounds.add(bounds(
                    blockEntity.getBlockPos(),
                    state.getValue(AdDisplayBlock.FACING),
                    display.displayWidth(),
                    display.displayHeight()));
        }
    }

    private static BoundingBox bounds(
            BlockPos anchor, Direction facing, int width, int height) {
        BlockPos far = anchor
                .relative(LargeAdSurfaceValidator.rightOf(facing), width - 1)
                .above(height - 1);
        return BoundingBox.fromCorners(anchor, far);
    }

    private static boolean intersectsAny(BoundingBox candidate, List<BoundingBox> occupied) {
        for (BoundingBox box : occupied) {
            if (candidate.intersects(box)
                    && candidate.minY() <= box.maxY()
                    && candidate.maxY() >= box.minY()) {
                return true;
            }
        }
        return false;
    }
}
