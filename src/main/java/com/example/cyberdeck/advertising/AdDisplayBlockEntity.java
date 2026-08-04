package com.example.cyberdeck.advertising;

import java.util.Optional;

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
    private static final LogoAd[] LOGOS = LogoAd.values();
    private static final double AUDIO_RANGE = 64.0;
    public static final int LOGO_DURATION_TICKS = 120;

    private int clipIndex;
    private int playbackTicks;
    private boolean soundStartedForClip;
    private int width = LargeAdSurfaceValidator.WIDTH;
    private int height = LargeAdSurfaceValidator.HEIGHT;
    private @Nullable FreestandingAdType freestandingType;
    private Direction.Axis longAxis = Direction.Axis.X;

    public AdDisplayBlockEntity(BlockPos position, BlockState state) {
        super(AdvertisingContent.AD_DISPLAY_ENTITY.get(), position, state);
        clipIndex = Math.floorMod(Long.hashCode(position.asLong()), CLIPS.length);
    }

    public static void clientTick(
            Level level, BlockPos position, BlockState state, AdDisplayBlockEntity display) {
        if (!display.hasConfiguredLayout(state)) {
            return;
        }
        if (display.usesLogoAds()) {
            display.playbackTicks = (display.playbackTicks + 1)
                    % (LOGO_DURATION_TICKS * LOGOS.length);
            return;
        }

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

    public Optional<FreestandingAdType> freestandingType() {
        return Optional.ofNullable(freestandingType);
    }

    public Direction.Axis longAxis() {
        return longAxis;
    }

    public boolean usesLogoAds() {
        return freestandingType != null && freestandingType.logoOnly();
    }

    public boolean hasConfiguredLayout(BlockState state) {
        return freestandingType != null || state.hasProperty(AdDisplayBlock.FACING);
    }

    public LogoAd currentLogo() {
        int offset = Math.floorMod(Long.hashCode(worldPosition.asLong()), LOGOS.length);
        int elapsed = playbackTicks / LOGO_DURATION_TICKS;
        return LOGOS[(offset + elapsed) % LOGOS.length];
    }

    public void configure(int width, int height) {
        if (!LargeAdSurfaceValidator.validDimensions(width, height)) {
            throw new IllegalArgumentException("Invalid large ad dimensions " + width + "x" + height);
        }
        freestandingType = null;
        this.width = width;
        this.height = height;
        syncConfiguration();
    }

    public void configureFreestanding(
            FreestandingAdType type, Direction.Axis longAxis) {
        if (longAxis == Direction.Axis.Y) {
            throw new IllegalArgumentException("Freestanding ads require a horizontal long axis");
        }
        freestandingType = type;
        this.longAxis = longAxis;
        width = type.faceLength();
        height = type.height();
        playbackTicks = 0;
        soundStartedForClip = false;
        syncConfiguration();
    }

    private void syncConfiguration() {
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
        if (freestandingType != null) {
            output.putString("FreestandingType", freestandingType.id());
            output.putString("LongAxis", longAxis.getName());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        freestandingType = FreestandingAdType.byId(
                input.getStringOr("FreestandingType", "")).orElse(null);
        longAxis = "z".equals(input.getStringOr("LongAxis", "x"))
                ? Direction.Axis.Z
                : Direction.Axis.X;
        if (freestandingType != null) {
            width = freestandingType.faceLength();
            height = freestandingType.height();
            return;
        }
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
        if (freestandingType != null && !freestandingType.audioEnabled()) {
            return;
        }
        level.playLocalSound(centerX(position, state), centerY(position), centerZ(position, state),
                AdvertisingContent.sound(currentClip()), SoundSource.BLOCKS,
                1.0F, 1.0F, false);
    }

    private boolean isAudible(Level level, BlockPos position, BlockState state) {
        if (freestandingType != null && !freestandingType.audioEnabled()) {
            return false;
        }
        double x = centerX(position, state);
        double y = centerY(position);
        double z = centerZ(position, state);
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

    private double centerX(BlockPos position, BlockState state) {
        if (freestandingType != null) {
            return position.getX() + freestandingType.sizeX(longAxis) * 0.5;
        }
        Direction right = rightDirection(state);
        return position.getX() + 0.5 + right.getStepX()
                * (width - 1) * 0.5;
    }

    private double centerY(BlockPos position) {
        return position.getY() + height * 0.5;
    }

    private double centerZ(BlockPos position, BlockState state) {
        if (freestandingType != null) {
            return position.getZ() + freestandingType.sizeZ(longAxis) * 0.5;
        }
        Direction right = rightDirection(state);
        return position.getZ() + 0.5 + right.getStepZ()
                * (width - 1) * 0.5;
    }
}
