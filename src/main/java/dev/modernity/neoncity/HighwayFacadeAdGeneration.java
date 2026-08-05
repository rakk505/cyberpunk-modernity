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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
    /**
     * Chunks scanned either side of the owner along the wall. Arnis tiles are chunk-aligned, so a
     * single tower face routinely runs past its own chunk; scanning one neighbour each way lets one
     * screen cover the whole face instead of stopping at an invisible seam.
     */
    private static final int WINDOW_CHUNKS = 1;
    private static final int WINDOW_COLUMNS = (WINDOW_CHUNKS * 2 + 1) * 16;
    /** Window-relative column range owned by the scanning chunk itself. */
    private static final int OWNED_COLUMN_MIN = WINDOW_CHUNKS * 16;
    private static final int OWNED_COLUMN_MAX = OWNED_COLUMN_MIN + 15;
    /** Lateral reach used when looking for displays that could overlap this window. */
    private static final int OVERLAP_SEARCH_CHUNKS = WINDOW_CHUNKS + 2;
    /**
     * How far a facade may step back behind the screen's mounting plane. Real building faces are
     * rarely one perfect plane, and requiring exact coplanarity chops a lightly stepped face into
     * slivers below the minimum display width, so nothing gets placed at all. Two blocks spans the
     * usual staircase detailing while staying tight enough that the screen still reads as mounted
     * on the wall rather than floating in front of it.
     */
    static final int STEP_TOLERANCE = 2;
    /**
     * Widest a rectangle may be and still be treated as a vertical slot. Narrow slices of a
     * building cannot show a 16:9 clip at any useful size, but they suit the 9:16 sources exactly,
     * so they get the portrait campaign instead of a letterboxed wide one.
     */
    private static final int MAX_PORTRAIT_WIDTH = 10;
    private static final int MAX_STACKED_SCREENS = 4;
    /** Blank wall left between stacked screens so they read as separate billboards. */
    private static final int SCREEN_GAP = 1;
    /**
     * How far past the clip aspect a persisted screen must be before the rescan deletes it as
     * legacy damage. {@link #stack} splits at twice the ideal height, so this leaves a wide margin
     * in which a freshly stacked panel is left alone.
     */
    private static final int OVERSTRETCH_FACTOR = 4;

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

        /** A slot wide enough to carry the landscape campaign rather than the vertical one. */
        boolean wide() {
            return width > MAX_PORTRAIT_WIDTH;
        }

        /**
         * Wide slots first, then bigger, then taller, then snuggest against the wall, then a
         * stable scan order.
         *
         * <p>Width outranks area deliberately. Ranking on area alone lets a tall sliver beat a
         * genuine wide facade — four columns over two hundred rows is a larger rectangle than
         * sixteen over forty — which would quietly replace the wide megascreens with narrow ones
         * wherever a building offers both. Narrow slots exist to use walls that could not carry a
         * screen at all, not to displace the ones that can.
         *
         * <p>The same rectangle is offered once per mounting plane it tolerates, so preferring the
         * deepest one keeps the screen on the frontmost real course instead of floating it out.</p>
         */
        boolean betterThan(Candidate other) {
            if (other == null) return true;
            if (wide() != other.wide()) return wide();
            if (area() != other.area()) return area() > other.area();
            if (height != other.height) return height > other.height;
            if (depth != other.depth) return depth > other.depth;
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
        if (!windowReady(level, chunk, facing)) {
            return Result.RETRYABLE;
        }
        // Replaying this pass — a regenerated chunk, or the backfill queue revisiting an older
        // save — must adopt the screen that is already on this wall instead of adding a second
        // one beside it. A catalog facade pointing at the highway counts as already served.
        // Screens left over-stretched by an earlier version are cleared first so the rescan can
        // replace them with a properly proportioned stack.
        clearOverstretchedScreens(level, chunk, facing);
        if (hasHighwayFacingDisplay(level, chunk, facing)) {
            return Result.PLACED;
        }

        List<Placement> candidates = candidatePlacements(level, chunk, facing, MIN_DISPLAY_Y);
        if (candidates.isEmpty()) {
            return Result.NONE_FOUND;
        }

        List<BoundingBox> existing = existingDisplayBounds(level, chunk, facing);
        for (Placement candidate : candidates) {
            if (intersectsAny(
                    bounds(candidate.anchor(), facing, candidate.width(), candidate.height()),
                    existing)) {
                continue;
            }
            int placed = 0;
            boolean unloaded = false;
            for (Placement panel : stack(candidate)) {
                LargeAdSurfaceValidator.Result validation =
                        LargeAdSurfaceValidator.validateOverlay(
                                level, panel.anchor(), facing, panel.width(), panel.height(),
                                STEP_TOLERANCE);
                if (!validation.valid()) {
                    unloaded |= validation.failure()
                            == LargeAdSurfaceValidator.Failure.CHUNK_UNLOADED;
                    continue;
                }
                // Roadside screens run their own campaigns rather than the district rotation, so
                // the corridor reads as a continuous run of advertising; the shape of the slot
                // decides whether it shows the wide or the vertical one.
                if (AdDisplayPlacement.placeOverlay(
                        level,
                        panel.anchor(),
                        facing,
                        panel.width(),
                        panel.height(),
                        campaignFor(panel.width()))) {
                    placed++;
                    LOGGER.debug(
                            "[NeonCity] placed {}x{} highway megascreen facing {} at {}",
                            panel.width(), panel.height(), facing, panel.anchor());
                }
            }
            if (placed > 0) {
                return Result.PLACED;
            }
            if (unloaded) {
                return Result.RETRYABLE;
            }
        }
        return Result.NONE_FOUND;
    }

    /**
     * Splits a tall screen into a centred stack of near-16:9 panels. A clip is a 16:9 sprite sheet
     * stretched to fill its display, so one screen covering a whole tower face would smear a single
     * frame over the entire building. Short screens are returned unchanged, and the stack is capped
     * so a very tall tower gets a few readable billboards rather than dozens of slots. Each panel
     * picks its own clip, because the rotation is seeded from the anchor position.
     */
    static List<Placement> stack(Placement screen) {
        int ideal = idealHeight(screen.width());
        if (screen.height() < ideal * 2) {
            return List.of(screen);
        }
        int panels = Math.min(MAX_STACKED_SCREENS, screen.height() / ideal);
        int panelHeight = (screen.height() - (panels - 1) * SCREEN_GAP) / panels;
        if (panels < 2 || panelHeight < LargeAdSurfaceValidator.MIN_HEIGHT) {
            return List.of(screen);
        }
        int span = panels * panelHeight + (panels - 1) * SCREEN_GAP;
        int start = (screen.height() - span) / 2;
        List<Placement> stacked = new ArrayList<>(panels);
        for (int index = 0; index < panels; index++) {
            stacked.add(new Placement(
                    screen.anchor().above(start + index * (panelHeight + SCREEN_GAP)),
                    screen.facing(),
                    screen.width(),
                    panelHeight));
        }
        return List.copyOf(stacked);
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
        for (Candidate candidate : rankedCandidates(
                WINDOW_COLUMNS, scan.rows(), scan.depth(), scan.valid())) {
            // Exactly one chunk owns each screen: the one holding its anchor column. A rectangle
            // anchored in a neighbour is that neighbour's to place, so the same wall never
            // collects two overlapping displays from two passes.
            if (candidate.column() < OWNED_COLUMN_MIN
                    || candidate.column() > OWNED_COLUMN_MAX) {
                continue;
            }
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
    private static boolean windowReady(ServerLevel level, ChunkPos chunk, Direction facing) {
        Direction right = LargeAdSurfaceValidator.rightOf(facing);
        for (int offset = -WINDOW_CHUNKS; offset <= WINDOW_CHUNKS; offset++) {
            ChunkPos lateral = new ChunkPos(
                    chunk.x() + right.getStepX() * offset,
                    chunk.z() + right.getStepZ() * offset);
            // Both the scanned column and the open volume in front of it must be final.
            if (!chunkReady(level, lateral)
                    || !chunkReady(level, new ChunkPos(
                            lateral.x() + facing.getStepX(), lateral.z() + facing.getStepZ()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A chunk is usable once the city has finished stamping it and it is resident. Wilderness
     * chunks the generator never touches only need to be resident.
     */
    private static boolean chunkReady(ServerLevel level, ChunkPos chunk) {
        if (NeonCityGenerator.chunkTouchesCity(chunk.x(), chunk.z())
                && !NeonCityGenerator.isGenerated(chunk)) {
            return false;
        }
        return level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null;
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
        Direction inward = facing.getOpposite();
        int baseX = right == Direction.WEST || facing == Direction.EAST
                ? chunk.getMaxBlockX()
                : chunk.getMinBlockX();
        int baseZ = right == Direction.NORTH || facing == Direction.SOUTH
                ? chunk.getMaxBlockZ()
                : chunk.getMinBlockZ();
        // Column 0 sits one chunk before the owner along the wall; the owner keeps columns
        // OWNED_COLUMN_MIN..OWNED_COLUMN_MAX.
        BlockPos base = new BlockPos(baseX, floorY, baseZ).relative(right, -OWNED_COLUMN_MIN);

        // Per-column ceilings keep the sweep off empty sky, which otherwise dominates the cost of
        // a 48-column window on a tall district.
        int[] columnTop = new int[WINDOW_COLUMNS];
        int topY = Integer.MIN_VALUE;
        for (int column = 0; column < WINDOW_COLUMNS; column++) {
            BlockPos columnBase = base.relative(right, column);
            int highest = Integer.MIN_VALUE;
            for (int step = 0; step <= MAX_FACE_DEPTH; step++) {
                highest = Math.max(highest, level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        columnBase.getX() + inward.getStepX() * step,
                        columnBase.getZ() + inward.getStepZ() * step) - 1);
            }
            columnTop[column] = Math.min(highest, NeonCityGenerator.MAX_BUILD_Y);
            topY = Math.max(topY, columnTop[column]);
        }
        int rows = topY - floorY + 1;
        if (rows < LargeAdSurfaceValidator.MIN_HEIGHT) {
            return null;
        }

        int[][] depth = new int[WINDOW_COLUMNS][rows];
        boolean[][] valid = new boolean[WINDOW_COLUMNS][rows];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        boolean anyValid = false;
        for (int column = 0; column < WINDOW_COLUMNS; column++) {
            BlockPos columnBase = base.relative(right, column);
            int columnRows = Math.min(rows, columnTop[column] - floorY + 1);
            for (int row = 0; row < rows; row++) {
                depth[column][row] = -1;
            }
            for (int row = 0; row < columnRows; row++) {
                int y = floorY + row;
                int firstSolid = -1;
                for (int step = 0; step <= MAX_FACE_DEPTH; step++) {
                    cursor.set(
                            columnBase.getX() + inward.getStepX() * step,
                            y,
                            columnBase.getZ() + inward.getStepZ() * step);
                    if (!level.getBlockState(cursor).canBeReplaced()) {
                        firstSolid = step;
                        break;
                    }
                }
                if (firstSolid < 0) {
                    continue;
                }
                // Depth 0 is a wall flush with the chunk border, which Arnis tiles produce
                // constantly. Its display cell lives one block into the chunk toward the highway,
                // which windowReady has already confirmed is generated and resident.
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
        // A stepped-back cell can be mounted up to STEP_TOLERANCE blocks in front of its own wall,
        // so demand that much extra open air here rather than discovering it during validation.
        int reach = LargeAdSurfaceValidator.GENERATED_FRONT_CLEARANCE + 1 + STEP_TOLERANCE;
        for (int depth = 1; depth <= reach; depth++) {
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
    static List<Candidate> rankedCandidates(
            int columns, int rows, int[][] depth, boolean[][] valid) {
        List<Candidate> best = new ArrayList<>(MAX_PLACEMENT_ATTEMPTS);
        // One sweep per mounting plane. A cell joins the sweep for plane `mount` when its own wall
        // sits at that plane or steps back from it by no more than STEP_TOLERANCE, so a lightly
        // stepped face yields one wide screen on its frontmost course instead of narrow slivers.
        int[] run = new int[columns];
        for (int mount = 0; mount <= MAX_FACE_DEPTH; mount++) {
            Arrays.fill(run, 0);
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int cellDepth = depth[column][row];
                    boolean usable = valid[column][row]
                            && cellDepth >= mount
                            && cellDepth <= mount + STEP_TOLERANCE
                            && cellDepth <= MAX_FACE_DEPTH;
                    run[column] = usable ? run[column] + 1 : 0;
                }
                for (int left = 0; left < columns; left++) {
                    if (run[left] < LargeAdSurfaceValidator.MIN_HEIGHT) {
                        continue;
                    }
                    int minRun = Integer.MAX_VALUE;
                    int lastEdge = Math.min(columns, left + LargeAdSurfaceValidator.MAX_WIDTH);
                    for (int rightEdge = left; rightEdge < lastEdge; rightEdge++) {
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
                                left, row - height + 1, width, height, mount));
                    }
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

    /**
     * Removes highway screens this chunk anchors that {@link #stack} would now break up. An
     * earlier version could leave a single display covering a whole tower face, which smears one
     * 16:9 frame over two hundred blocks; deleting it lets the rescan lay down a proper stack.
     * Only this mod's own roadside screens are touched, never a catalog facade or a player build.
     */
    private static void clearOverstretchedScreens(
            ServerLevel level, ChunkPos chunk, Direction facing) {
        LevelChunk loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
        if (loaded == null) {
            return;
        }
        List<BlockPos> stale = new ArrayList<>();
        for (BlockEntity blockEntity : loaded.getBlockEntities().values()) {
            if (!(blockEntity instanceof AdDisplayBlockEntity display)
                    || !isRoadsideCampaign(display.campaign())
                    || !display.generatedPlacement()) {
                continue;
            }
            BlockState state = blockEntity.getBlockState();
            if (!state.is(AdvertisingContent.AD_DISPLAY_ANCHOR.get())
                    || state.getValue(AdDisplayBlock.FACING) != facing) {
                continue;
            }
            if (isOverstretched(display.displayWidth(), display.displayHeight())) {
                stale.add(blockEntity.getBlockPos().immutable());
            }
        }
        for (BlockPos position : stale) {
            LOGGER.debug("[NeonCity] clearing over-stretched highway megascreen at {}", position);
            level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * True when a screen is so much taller than the clip aspect that one frame is smeared beyond
     * recognition. The bar is deliberately far above what {@link #stack} itself splits at, so a
     * correctly stacked panel is never mistaken for legacy damage and deleted out of a live stack.
     */
    static boolean isOverstretched(int width, int height) {
        return height > idealHeight(width) * OVERSTRETCH_FACTOR;
    }

    /**
     * Campaign a rectangle of this shape should carry. A slice too narrow to show a wide clip at
     * any useful size is given the vertical campaign, whose sources are 9:16 to begin with, so a
     * thin sliver of a building becomes a proper vertical billboard rather than a letterboxed
     * smear of a widescreen frame.
     */
    static AdCampaign campaignFor(int width) {
        return width <= MAX_PORTRAIT_WIDTH ? AdCampaign.HIGHWAY_TALL : AdCampaign.HIGHWAY;
    }

    /** Campaigns this pass owns, and may therefore rebuild or remove. */
    private static boolean isRoadsideCampaign(AdCampaign campaign) {
        return campaign == AdCampaign.HIGHWAY || campaign == AdCampaign.HIGHWAY_TALL;
    }

    /** Undistorted height for a screen of {@code width}, in whichever campaign it will carry. */
    private static int idealHeight(int width) {
        return Math.max(
                LargeAdSurfaceValidator.MIN_HEIGHT,
                campaignFor(width).orientation().idealHeight(width));
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
        Direction right = LargeAdSurfaceValidator.rightOf(facing);
        // A screen up to MAX_WIDTH wide can be anchored several chunks along the wall and still
        // reach this window, and its anchor row sits in the chunk toward the highway whenever the
        // wall is flush with a border. Sweep both axes far enough to see all of them.
        for (int offset = -OVERLAP_SEARCH_CHUNKS; offset <= OVERLAP_SEARCH_CHUNKS; offset++) {
            ChunkPos lateral = new ChunkPos(
                    chunk.x() + right.getStepX() * offset,
                    chunk.z() + right.getStepZ() * offset);
            collectDisplayBounds(level, lateral, bounds);
            collectDisplayBounds(level, new ChunkPos(
                    lateral.x() + facing.getStepX(), lateral.z() + facing.getStepZ()), bounds);
            collectDisplayBounds(level, new ChunkPos(
                    lateral.x() - facing.getStepX(), lateral.z() - facing.getStepZ()), bounds);
        }
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

    /**
     * A display's rectangle is one block thick, so two screens mounted on the same wall a block or
     * two apart in depth do not intersect geometrically — yet both render, and the viewer sees two
     * videos fighting in the same space. Inflating along the facing axis makes near-parallel
     * screens count as a conflict so only one survives.
     */
    private static BoundingBox bounds(
            BlockPos anchor, Direction facing, int width, int height) {
        BlockPos far = anchor
                .relative(LargeAdSurfaceValidator.rightOf(facing), width - 1)
                .above(height - 1);
        int reach = STEP_TOLERANCE + LargeAdSurfaceValidator.GENERATED_FRONT_CLEARANCE;
        return BoundingBox.fromCorners(
                anchor.relative(facing, reach), far.relative(facing.getOpposite(), reach));
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
