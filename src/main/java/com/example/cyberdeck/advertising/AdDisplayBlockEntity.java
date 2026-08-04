package com.example.cyberdeck.advertising;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** Client playback clock shared by the ad renderer and its streamed positional audio. */
public final class AdDisplayBlockEntity extends BlockEntity {
    private static final AdClip[] CLIPS = AdClip.values();
    private static final double AUDIO_RANGE = 64.0;

    private int clipIndex;
    private int playbackTicks;
    private boolean soundStartedForClip;
    private int width = LargeAdSurfaceValidator.WIDTH;
    private int height = LargeAdSurfaceValidator.HEIGHT;

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

    public int displayWidth() {
        return width;
    }

    public int displayHeight() {
        return height;
    }

    public void configure(int width, int height) {
        if (!LargeAdSurfaceValidator.validDimensions(width, height)) {
            throw new IllegalArgumentException("Invalid large ad dimensions " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
                    Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("Width", width);
        output.putInt("Height", height);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        int loadedWidth = input.getIntOr("Width", LargeAdSurfaceValidator.WIDTH);
        int loadedHeight = input.getIntOr("Height", LargeAdSurfaceValidator.HEIGHT);
        if (LargeAdSurfaceValidator.validDimensions(loadedWidth, loadedHeight)) {
            width = loadedWidth;
            height = loadedHeight;
        }
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
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

    private double centerX(BlockPos position, Direction right) {
        return position.getX() + 0.5 + right.getStepX()
                * (width - 1) * 0.5;
    }

    private double centerY(BlockPos position) {
        return position.getY() + height * 0.5;
    }

    private double centerZ(BlockPos position, Direction right) {
        return position.getZ() + 0.5 + right.getStepZ()
                * (width - 1) * 0.5;
    }
}
