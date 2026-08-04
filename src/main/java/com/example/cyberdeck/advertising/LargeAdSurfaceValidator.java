package com.example.cyberdeck.advertising;

import java.util.ArrayList;
import java.util.List;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded placement validation for one large 8x4 advertising surface. */
public final class LargeAdSurfaceValidator {
    public static final int WIDTH = 8;
    public static final int HEIGHT = 4;
    public static final int CELL_COUNT = WIDTH * HEIGHT;

    private LargeAdSurfaceValidator() {
    }

    public static Result validate(Level level, BlockPos anchor, Direction facing) {
        if (facing.getAxis().isVertical()) {
            return Result.failure(Failure.VERTICAL_FACE, anchor);
        }

        for (BlockPos target : targets(anchor, facing)) {
            if (!level.isInWorldBounds(target)
                    || !level.getWorldBorder().isWithinBounds(target)) {
                return Result.failure(Failure.OUT_OF_BOUNDS, target);
            }
            if (!level.getBlockState(target).canBeReplaced()) {
                return Result.failure(Failure.BLOCKED, target);
            }

            BlockPos support = target.relative(facing.getOpposite());
            BlockState supportState = level.getBlockState(support);
            if (isGlass(supportState)) {
                return Result.failure(Failure.GLASS, support);
            }
            if (!supportState.isFaceSturdy(level, support, facing)) {
                return Result.failure(Failure.UNSUPPORTED, support);
            }
        }
        return Result.success();
    }

    public static List<BlockPos> targets(BlockPos anchor, Direction facing) {
        Direction right = rightOf(facing);
        List<BlockPos> targets = new ArrayList<>(CELL_COUNT);
        for (int row = 0; row < HEIGHT; row++) {
            for (int column = 0; column < WIDTH; column++) {
                targets.add(anchor.relative(right, column).above(row).immutable());
            }
        }
        return List.copyOf(targets);
    }

    public static Direction rightOf(Direction facing) {
        if (facing.getAxis().isVertical()) {
            throw new IllegalArgumentException("Advertising displays require a horizontal face");
        }
        return facing.getCounterClockWise();
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
        OUT_OF_BOUNDS("message.cyberdeck.ad_display.out_of_bounds"),
        BLOCKED("message.cyberdeck.ad_display.blocked"),
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
