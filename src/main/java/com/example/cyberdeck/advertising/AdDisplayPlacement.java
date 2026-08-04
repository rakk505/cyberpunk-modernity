package com.example.cyberdeck.advertising;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Transactional placement shared by the item and catalog-driven city generation. */
public final class AdDisplayPlacement {
    private AdDisplayPlacement() {
    }

    public static boolean place(
            Level level, BlockPos anchor, Direction facing, int width, int height) {
        if (!LargeAdSurfaceValidator.validate(
                level, anchor, facing, width, height).valid()) {
            return false;
        }

        List<BlockPos> targets = LargeAdSurfaceValidator.targets(
                anchor, facing, width, height);
        List<BlockState> previousStates = new ArrayList<>(targets.size());
        for (BlockPos target : targets) {
            previousStates.add(level.getBlockState(target));
        }

        BlockState anchorState = AdvertisingContent.AD_DISPLAY_ANCHOR.get()
                .defaultBlockState().setValue(AdDisplayBlock.FACING, facing);
        BlockState panelState = AdvertisingContent.AD_DISPLAY_PANEL.get()
                .defaultBlockState().setValue(AdPanelBlock.FACING, facing);
        for (int index = 0; index < targets.size(); index++) {
            BlockState newState = index == 0 ? anchorState : panelState;
            if (!level.setBlock(targets.get(index), newState, Block.UPDATE_ALL)) {
                rollback(level, targets, previousStates, index);
                return false;
            }
        }

        if (!(level.getBlockEntity(anchor) instanceof AdDisplayBlockEntity display)) {
            rollback(level, targets, previousStates, targets.size() - 1);
            return false;
        }
        display.configure(width, height);
        return true;
    }

    private static void rollback(
            Level level, List<BlockPos> targets, List<BlockState> states, int lastPlaced) {
        for (int index = 0; index <= lastPlaced; index++) {
            level.setBlock(targets.get(index), states.get(index), Block.UPDATE_ALL);
        }
    }
}
