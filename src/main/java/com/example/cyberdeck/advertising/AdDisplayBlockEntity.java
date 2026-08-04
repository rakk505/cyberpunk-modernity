package com.example.cyberdeck.advertising;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Client playback clock shared by the ad renderer and its streamed positional audio. */
public final class AdDisplayBlockEntity extends BlockEntity {
    private static final AdClip[] CLIPS = AdClip.values();
    private static final double AUDIO_RANGE = 64.0;

    private int clipIndex;
    private int playbackTicks;
    private boolean soundStartedForClip;

    public AdDisplayBlockEntity(BlockPos position, BlockState state) {
        super(AdvertisingContent.AD_DISPLAY_ENTITY.get(), position, state);
        clipIndex = Math.floorMod(Long.hashCode(position.asLong()), CLIPS.length);
    }

    public static void clientTick(
            Level level, BlockPos position, BlockState state, AdDisplayBlockEntity display) {
        boolean audible = display.isAudible(level, position, state);
        if (audible && !display.soundStartedForClip) {
            display.playbackTicks = 0;
            display.playCurrentAudio(level, position, state);
            display.soundStartedForClip = true;
            return;
        }

        display.playbackTicks++;
        if (display.playbackTicks >= display.currentClip().durationTicks()) {
            display.playbackTicks = 0;
            display.clipIndex = (display.clipIndex + 1) % CLIPS.length;
            display.soundStartedForClip = false;
            if (audible) {
                display.playCurrentAudio(level, position, state);
                display.soundStartedForClip = true;
            }
        }
    }

    public AdClip currentClip() {
        return CLIPS[clipIndex];
    }

    public int playbackTicks() {
        return playbackTicks;
    }

    private void playCurrentAudio(Level level, BlockPos position, BlockState state) {
        Direction right = rightDirection(state);
        level.playLocalSound(centerX(position, right), centerY(position), centerZ(position, right),
                AdvertisingContent.sound(currentClip()), SoundSource.BLOCKS,
                1.0F, 1.0F, false);
    }

    private boolean isAudible(Level level, BlockPos position, BlockState state) {
        Direction right = rightDirection(state);
        double x = centerX(position, right);
        double y = centerY(position);
        double z = centerZ(position, right);
        for (var player : level.players()) {
            if (player.isLocalPlayer()
                    && player.distanceToSqr(x, y, z) <= AUDIO_RANGE * AUDIO_RANGE) {
                return true;
            }
        }
        return false;
    }

    private static Direction rightDirection(BlockState state) {
        Direction facing = state.getValue(AdDisplayBlock.FACING);
        return LargeAdSurfaceValidator.rightOf(facing);
    }

    private static double centerX(BlockPos position, Direction right) {
        return position.getX() + 0.5 + right.getStepX()
                * (LargeAdSurfaceValidator.WIDTH - 1) * 0.5;
    }

    private static double centerY(BlockPos position) {
        return position.getY() + LargeAdSurfaceValidator.HEIGHT * 0.5;
    }

    private static double centerZ(BlockPos position, Direction right) {
        return position.getZ() + 0.5 + right.getStepZ()
                * (LargeAdSurfaceValidator.WIDTH - 1) * 0.5;
    }
}
