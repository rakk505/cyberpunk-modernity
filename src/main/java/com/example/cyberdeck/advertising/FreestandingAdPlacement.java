package com.example.cyberdeck.advertising;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Flat-floor validation and transactional construction for generated street ads. */
public final class FreestandingAdPlacement {
    private FreestandingAdPlacement() {
    }

    public static boolean place(
            Level level,
            BlockPos origin,
            FreestandingAdType type,
            Direction.Axis longAxis) {
        if (matchesExisting(level, origin, type, longAxis)) {
            return true;
        }
        if (!validate(level, origin, type, longAxis)) {
            return false;
        }

        List<BlockPos> targets = targets(origin, type, longAxis);
        List<BlockState> previousStates = new ArrayList<>(targets.size());
        for (BlockPos target : targets) {
            previousStates.add(level.getBlockState(target));
        }

        BlockState controller = AdvertisingContent.FREESTANDING_AD_CONTROLLER.get()
                .defaultBlockState();
        BlockState frame = AdvertisingContent.FREESTANDING_AD_FRAME.get().defaultBlockState();
        for (int index = 0; index < targets.size(); index++) {
            BlockState replacement = index == 0 ? controller : frame;
            if (!level.setBlock(targets.get(index), replacement, Block.UPDATE_ALL)) {
                rollback(level, targets, previousStates, index);
                return false;
            }
        }

        if (!(level.getBlockEntity(origin) instanceof AdDisplayBlockEntity display)) {
            rollback(level, targets, previousStates, targets.size() - 1);
            return false;
        }
        display.configureFreestanding(type, longAxis);
        return true;
    }

    public static boolean validate(
            Level level,
            BlockPos origin,
            FreestandingAdType type,
            Direction.Axis longAxis) {
        if (longAxis == Direction.Axis.Y) {
            return false;
        }
        if (matchesExisting(level, origin, type, longAxis)) {
            return true;
        }

        int sizeX = type.sizeX(longAxis);
        int sizeZ = type.sizeZ(longAxis);
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                BlockPos floor = origin.offset(x, -1, z);
                if (!inBounds(level, floor)
                        || !level.getBlockState(floor).isFaceSturdy(
                                level, floor, Direction.UP)) {
                    return false;
                }
                for (int y = 0; y < type.height(); y++) {
                    BlockPos target = origin.offset(x, y, z);
                    BlockState state = level.getBlockState(target);
                    if (!inBounds(level, target)
                            || !state.canBeReplaced()
                            || !state.getFluidState().isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static List<BlockPos> targets(
            BlockPos origin,
            FreestandingAdType type,
            Direction.Axis longAxis) {
        int sizeX = type.sizeX(longAxis);
        int sizeZ = type.sizeZ(longAxis);
        List<BlockPos> targets = new ArrayList<>(sizeX * sizeZ * type.height());
        for (int y = 0; y < type.height(); y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    targets.add(origin.offset(x, y, z).immutable());
                }
            }
        }
        return List.copyOf(targets);
    }

    private static boolean matchesExisting(
            Level level,
            BlockPos origin,
            FreestandingAdType type,
            Direction.Axis longAxis) {
        if (!(level.getBlockEntity(origin) instanceof AdDisplayBlockEntity display)
                || display.freestandingType().orElse(null) != type
                || display.longAxis() != longAxis) {
            return false;
        }
        List<BlockPos> targets = targets(origin, type, longAxis);
        for (int index = 0; index < targets.size(); index++) {
            BlockState state = level.getBlockState(targets.get(index));
            if (index == 0) {
                if (!state.is(AdvertisingContent.FREESTANDING_AD_CONTROLLER.get())) {
                    return false;
                }
            } else if (!state.is(AdvertisingContent.FREESTANDING_AD_FRAME.get())) {
                return false;
            }
        }
        return true;
    }

    private static boolean inBounds(Level level, BlockPos position) {
        return level.isInWorldBounds(position)
                && level.getWorldBorder().isWithinBounds(position);
    }

    private static void rollback(
            Level level, List<BlockPos> targets, List<BlockState> states, int lastPlaced) {
        for (int index = 0; index <= lastPlaced; index++) {
            level.setBlock(targets.get(index), states.get(index), Block.UPDATE_ALL);
        }
    }
}
