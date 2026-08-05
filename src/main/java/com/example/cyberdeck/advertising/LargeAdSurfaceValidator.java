package com.example.cyberdeck.advertising;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded placement validation for manual displays and generated megascreens. */
public final class LargeAdSurfaceValidator {
    /** Full-face air gap required in front of every generated display. */
    public static final int GENERATED_FRONT_CLEARANCE = 3;
    /** Bounded outward search used to distinguish an exterior facade from an indoor wall. */
    public static final int GENERATED_EXTERIOR_SEARCH = 16;
    public static final int MIN_WIDTH = 8;
    public static final int MIN_HEIGHT = 4;
    /**
     * Wide enough for a highway megascreen to span a tower face across three chunks. Offline
     * catalog surfaces are still authored at 16 or less, since they are scoped to one Arnis tile.
     */
    public static final int MAX_WIDTH = 48;
    /** Tall enough to cover the audited District A spawn facade without unbounded scans. */
    public static final int MAX_HEIGHT = 256;
    public static final int WIDTH = 8;
    public static final int HEIGHT = 4;
    public static final int CELL_COUNT = WIDTH * HEIGHT;

    private LargeAdSurfaceValidator() {
    }

    public static Result validate(Level level, BlockPos anchor, Direction facing) {
        return validate(level, anchor, facing, WIDTH, HEIGHT);
    }

    public static Result validate(
            Level level, BlockPos anchor, Direction facing, int width, int height) {
        return validate(level, anchor, facing, width, height, false, false, Set.of());
    }

    /** Generated panel grids must face a clear, sky-connected exterior volume. */
    public static Result validateGenerated(
            Level level, BlockPos anchor, Direction facing, int width, int height) {
        return validate(level, anchor, facing, width, height, false, true, Set.of());
    }

    /** Re-audits the open volume around an already-persisted generated display grid. */
    public static Result validateGeneratedExterior(
            Level level, BlockPos anchor, Direction facing, int width, int height) {
        if (!validDimensions(width, height)) {
            return Result.failure(Failure.INVALID_SIZE, anchor);
        }
        return validateExterior(level, anchor, facing, width, height);
    }

    /**
     * Generated overlays may mount over full luminous facade blocks, but never window glass, and
     * must face a clear, sky-connected exterior volume.
     */
    public static Result validateOverlay(
            Level level, BlockPos anchor, Direction facing, int width, int height) {
        return validateOverlay(level, anchor, facing, width, height, 0);
    }

    /**
     * As {@link #validateOverlay(Level, BlockPos, Direction, int, int)}, but tolerating a facade
     * that steps back by up to {@code supportTolerance} blocks across the rectangle. Real building
     * faces are rarely one perfect plane; a screen mounted on the frontmost course reads as a
     * single flat billboard while a step of one or two blocks hides behind it. Each column still
     * needs a real wall within the tolerance, and that wall still may not be window glass, so this
     * never lets a display float over an alley or cover a window.
     */
    public static Result validateOverlay(
            Level level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height,
            int supportTolerance) {
        return validate(
                level, anchor, facing, width, height, true, true, Set.of(),
                Math.max(0, supportTolerance));
    }

    /**
     * Validates a new catalog rectangle over an older display at the same anchor. Legacy panel
     * grids and newer single-anchor overlays are accepted only when their stored representation is
     * complete, allowing migration without deleting the old display before validation succeeds.
     */
    public static Result validateOverlayReplacement(
            Level level, BlockPos anchor, Direction facing, int width, int height) {
        BlockState anchorState = level.getBlockState(anchor);
        if (!anchorState.is(AdvertisingContent.AD_DISPLAY_ANCHOR.get())
                || anchorState.getValue(AdDisplayBlock.FACING) != facing
                || !(level.getBlockEntity(anchor) instanceof AdDisplayBlockEntity display)
                || !validDimensions(display.displayWidth(), display.displayHeight())) {
            return Result.failure(Failure.BLOCKED, anchor);
        }

        Set<BlockPos> replaceableDisplayCells = new HashSet<>();
        replaceableDisplayCells.add(anchor.immutable());
        if (!display.generatedPlacement()) {
            List<BlockPos> oldTargets = targets(
                    anchor, facing, display.displayWidth(), display.displayHeight());
            for (int index = 1; index < oldTargets.size(); index++) {
                BlockPos target = oldTargets.get(index);
                BlockState state = level.getBlockState(target);
                if (!state.is(AdvertisingContent.AD_DISPLAY_PANEL.get())
                        || state.getValue(AdPanelBlock.FACING) != facing) {
                    return Result.failure(Failure.BLOCKED, target);
                }
                replaceableDisplayCells.add(target);
            }
        }
        return validate(
                level,
                anchor,
                facing,
                width,
                height,
                true,
                true,
                Set.copyOf(replaceableDisplayCells));
    }

    private static Result validate(
            Level level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height,
            boolean allowLuminousGlass,
            boolean requireExterior,
            Set<BlockPos> replaceableDisplayCells) {
        return validate(level, anchor, facing, width, height, allowLuminousGlass,
                requireExterior, replaceableDisplayCells, 0);
    }

    private static Result validate(
            Level level,
            BlockPos anchor,
            Direction facing,
            int width,
            int height,
            boolean allowLuminousGlass,
            boolean requireExterior,
            Set<BlockPos> replaceableDisplayCells,
            int supportTolerance) {
        if (facing.getAxis().isVertical()) {
            return Result.failure(Failure.VERTICAL_FACE, anchor);
        }
        if (!validDimensions(width, height)) {
            return Result.failure(Failure.INVALID_SIZE, anchor);
        }

        for (BlockPos target : targets(anchor, facing, width, height)) {
            if (!level.isInWorldBounds(target)
                    || !level.getWorldBorder().isWithinBounds(target)) {
                return Result.failure(Failure.OUT_OF_BOUNDS, target);
            }
            if (requireExterior && !level.hasChunkAt(target)) {
                return Result.failure(Failure.CHUNK_UNLOADED, target);
            }
            BlockState targetState = level.getBlockState(target);
            if ((!targetState.canBeReplaced() && !replaceableDisplayCells.contains(target))
                    || (requireExterior && !targetState.getFluidState().isEmpty())) {
                return Result.failure(Failure.BLOCKED, target);
            }

            // Walk back to the first real block behind this cell. Without a tolerance that is
            // simply the block touching the display; with one, a stepped-back course still counts.
            BlockPos support = null;
            BlockState supportState = null;
            for (int back = 1; back <= supportTolerance + 1; back++) {
                BlockPos probe = target.relative(facing.getOpposite(), back);
                if (requireExterior && !level.hasChunkAt(probe)) {
                    return Result.failure(Failure.CHUNK_UNLOADED, probe);
                }
                BlockState probeState = level.getBlockState(probe);
                if (!probeState.canBeReplaced()) {
                    support = probe;
                    supportState = probeState;
                    break;
                }
            }
            if (support == null) {
                return Result.failure(Failure.UNSUPPORTED, target);
            }
            if (isGlass(supportState)
                    && !(allowLuminousGlass && isLuminousFacadeBlock(supportState))) {
                return Result.failure(Failure.GLASS, support);
            }
            if (!supportState.isFaceSturdy(level, support, facing)) {
                return Result.failure(Failure.UNSUPPORTED, support);
            }

        }
        return requireExterior
                ? validateExterior(level, anchor, facing, width, height)
                : Result.success();
    }

    private static Result validateExterior(
            Level level, BlockPos anchor, Direction facing, int width, int height) {
        for (BlockPos target : targets(anchor, facing, width, height)) {
            for (int depth = 1; depth <= GENERATED_FRONT_CLEARANCE; depth++) {
                BlockPos clearance = target.relative(facing, depth);
                if (!level.isInWorldBounds(clearance)
                        || !level.getWorldBorder().isWithinBounds(clearance)) {
                    return Result.failure(Failure.OUT_OF_BOUNDS, clearance);
                }
                if (!level.hasChunkAt(clearance)) {
                    return Result.failure(Failure.CHUNK_UNLOADED, clearance);
                }
                BlockState clearanceState = level.getBlockState(clearance);
                if (!clearanceState.canBeReplaced()
                        || !clearanceState.getFluidState().isEmpty()) {
                    return Result.failure(Failure.FRONT_BLOCKED, clearance);
                }
            }
        }
        Exposure exposure = exteriorExposure(level, anchor, facing, width, height);
        return switch (exposure) {
            case EXTERIOR -> Result.success();
            case ENCLOSED -> Result.failure(Failure.ENCLOSED, anchor);
            case UNLOADED -> Result.failure(Failure.CHUNK_UNLOADED, anchor);
        };
    }

    /**
     * Samples both ends and the center of the top edge. Each sample must reach a sky-visible cell
     * along an unobstructed outward ray. A closed room therefore cannot masquerade as a facade,
     * while a shallow awning or cornice can still be cleared by the bounded search.
     */
    private static Exposure exteriorExposure(
            Level level, BlockPos anchor, Direction facing, int width, int height) {
        if (facing == Direction.DOWN) {
            return undersideExposure(level, anchor, width, height);
        }

        Direction right = rightOf(facing);
        Direction up = upOf(facing);
        int[] columns = width <= 2
                ? new int[] {0, width - 1}
                : new int[] {0, width / 2, width - 1};
        BlockPos topLeft = anchor.relative(up, height - 1);
        boolean unloaded = false;
        for (int column : columns) {
            BlockPos edge = topLeft.relative(right, column);
            Exposure exposure = rayExposure(level, edge, facing);
            if (exposure == Exposure.ENCLOSED) {
                return Exposure.ENCLOSED;
            }
            unloaded |= exposure == Exposure.UNLOADED;
        }
        return unloaded ? Exposure.UNLOADED : Exposure.EXTERIOR;
    }

    /**
     * A downward-facing display necessarily has a roof above it, so it can never see the sky by
     * probing straight out from its face. Instead, probe laterally from the open volume below all
     * four edges. At least one edge must connect to daylight; an indoor ceiling remains enclosed.
     */
    private static Exposure undersideExposure(
            Level level, BlockPos anchor, int width, int height) {
        Direction right = rightOf(Direction.DOWN);
        Direction up = upOf(Direction.DOWN);
        BlockPos front = anchor.relative(Direction.DOWN, GENERATED_FRONT_CLEARANCE);
        BlockPos[] edges = {
                front.relative(right, width / 2),
                front.relative(right, width / 2).relative(up, height - 1),
                front.relative(up, height / 2),
                front.relative(up, height / 2).relative(right, width - 1)
        };
        Direction[] outward = {
                up.getOpposite(), up, right.getOpposite(), right
        };
        boolean unloaded = false;
        for (int index = 0; index < edges.length; index++) {
            Exposure exposure = rayExposure(level, edges[index], outward[index]);
            if (exposure == Exposure.EXTERIOR) {
                return Exposure.EXTERIOR;
            }
            unloaded |= exposure == Exposure.UNLOADED;
        }
        return unloaded ? Exposure.UNLOADED : Exposure.ENCLOSED;
    }

    private static Exposure rayExposure(Level level, BlockPos edge, Direction facing) {
        for (int depth = 1; depth <= GENERATED_EXTERIOR_SEARCH; depth++) {
            BlockPos probe = edge.relative(facing, depth);
            if (!level.isInWorldBounds(probe)
                    || !level.getWorldBorder().isWithinBounds(probe)) {
                return Exposure.ENCLOSED;
            }
            if (!level.hasChunkAt(probe)) {
                return Exposure.UNLOADED;
            }
            BlockState state = level.getBlockState(probe);
            if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                return Exposure.ENCLOSED;
            }
            if (hasOpenSkyColumn(level, probe)) {
                return Exposure.EXTERIOR;
            }
        }
        return Exposure.ENCLOSED;
    }

    /** Synchronous sky check; unlike light/heightmap queries, this observes same-tick roofs. */
    private static boolean hasOpenSkyColumn(Level level, BlockPos probe) {
        BlockPos.MutableBlockPos cursor = probe.mutable();
        for (int y = probe.getY() + 1; y < level.getMaxY(); y++) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private enum Exposure {
        EXTERIOR,
        ENCLOSED,
        UNLOADED
    }

    /** Sea-lantern facades read as glass by sound type but are valid generated ad supports. */
    public static boolean isLuminousFacadeBlock(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return id.equals("minecraft:sea_lantern")
                || id.equals("cyberdeck:camouflaged_sea_lantern");
    }

    public static List<BlockPos> targets(BlockPos anchor, Direction facing) {
        return targets(anchor, facing, WIDTH, HEIGHT);
    }

    public static List<BlockPos> targets(
            BlockPos anchor, Direction facing, int width, int height) {
        if (!validDimensions(width, height)) {
            throw new IllegalArgumentException(
                    "Large ad dimensions must be between " + MIN_WIDTH + "x" + MIN_HEIGHT
                            + " and " + MAX_WIDTH + "x" + MAX_HEIGHT);
        }
        Direction right = rightOf(facing);
        Direction up = upOf(facing);
        List<BlockPos> targets = new ArrayList<>(width * height);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                targets.add(anchor.relative(right, column).relative(up, row).immutable());
            }
        }
        return List.copyOf(targets);
    }

    public static boolean validDimensions(int width, int height) {
        return width >= MIN_WIDTH && width <= MAX_WIDTH
                && height >= MIN_HEIGHT && height <= MAX_HEIGHT;
    }

    public static Direction rightOf(Direction facing) {
        return facing.getAxis().isVertical()
                ? Direction.EAST
                : facing.getCounterClockWise();
    }

    /** Image-space up chosen so {@code rightOf(facing) x upOf(facing) == facing}. */
    public static Direction upOf(Direction facing) {
        return switch (facing) {
            case DOWN -> Direction.SOUTH;
            case UP -> Direction.NORTH;
            default -> Direction.UP;
        };
    }

    public static boolean isGlass(BlockState state) {
        if (state.getSoundType() == SoundType.GLASS) {
            return true;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.contains("glass");
    }

    public enum Failure {
        NONE(""),
        VERTICAL_FACE("message.cyberdeck.ad_display.vertical_face"),
        INVALID_SIZE("message.cyberdeck.ad_display.invalid_size"),
        OUT_OF_BOUNDS("message.cyberdeck.ad_display.out_of_bounds"),
        BLOCKED("message.cyberdeck.ad_display.blocked"),
        CHUNK_UNLOADED("message.cyberdeck.ad_display.chunk_unloaded"),
        FRONT_BLOCKED("message.cyberdeck.ad_display.front_blocked"),
        ENCLOSED("message.cyberdeck.ad_display.enclosed"),
        GLASS("message.cyberdeck.ad_display.glass"),
        UNSUPPORTED("message.cyberdeck.ad_display.unsupported");

        private final String translationKey;

        Failure(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    public record Result(Failure failure, BlockPos position) {
        private static Result success() {
            return new Result(Failure.NONE, BlockPos.ZERO);
        }

        private static Result failure(Failure failure, BlockPos position) {
            Cyberdeck.LOGGER.debug("Large ad surface rejected at {}: {}", position, failure);
            return new Result(failure, position.immutable());
        }

        public boolean valid() {
            return failure == Failure.NONE;
        }
    }
}
