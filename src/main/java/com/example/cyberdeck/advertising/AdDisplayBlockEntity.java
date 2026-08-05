package com.example.cyberdeck.advertising;

import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
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
    private static final LogoAd[] LOGOS = LogoAd.values();
    private static final double MANUAL_AUDIO_RANGE = 64.0;
    private static final double GENERATED_AUDIO_RANGE = 24.0;
    public static final int LOGO_DURATION_TICKS = 120;

    private AdCampaign campaign = AdCampaign.GENERAL;
    private boolean campaignConfigured;
    private int clipIndex;
    private int playbackTicks;
    private boolean soundStartedForClip;
    private int width = LargeAdSurfaceValidator.WIDTH;
    private int height = LargeAdSurfaceValidator.HEIGHT;
    private boolean generatedPlacement;
    private @Nullable FreestandingAdType freestandingType;
    private Direction.Axis longAxis = Direction.Axis.X;

    public AdDisplayBlockEntity(BlockPos position, BlockState state) {
        super(AdvertisingContent.AD_DISPLAY_ENTITY.get(), position, state);
        clipIndex = deterministicClipIndex();
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

        boolean inAudioRange = display.isAudible(level, position, state);
        boolean audible = display.currentClip().audioEnabled() && inAudioRange;
        if (audible && !display.soundStartedForClip) {
            display.playbackTicks = 0;
            display.playCurrentAudio(level, position, state);
            display.soundStartedForClip = true;
            return;
        }

        display.playbackTicks++;
        if (display.playbackTicks >= display.currentClip().durationTicks()) {
            display.playbackTicks = 0;
            display.clipIndex = (display.clipIndex + 1) % display.campaign.clips().size();
            display.soundStartedForClip = false;
            if (display.currentClip().audioEnabled() && inAudioRange) {
                display.playCurrentAudio(level, position, state);
                display.soundStartedForClip = true;
            }
        }
    }

    public AdClip currentClip() {
        return campaign.clipAt(clipIndex);
    }

    public AdCampaign campaign() {
        return campaign;
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

    public boolean generatedPlacement() {
        return generatedPlacement;
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
        configure(width, height, campaign);
    }

    public void configure(int width, int height, AdCampaign campaign) {
        configureWall(width, height, campaign, false);
    }

    public void configureGenerated(int width, int height) {
        configureGenerated(width, height, campaign);
    }

    public void configureGenerated(int width, int height, AdCampaign campaign) {
        configureWall(width, height, campaign, true);
    }

    private void configureWall(
            int width,
            int height,
            AdCampaign campaign,
            boolean generatedPlacement) {
        if (!LargeAdSurfaceValidator.validDimensions(width, height)) {
            throw new IllegalArgumentException("Invalid large ad dimensions " + width + "x" + height);
        }
        if (campaign == null) {
            throw new IllegalArgumentException("Ad campaign cannot be null");
        }
        freestandingType = null;
        this.width = width;
        this.height = height;
        this.campaign = campaign;
        this.campaignConfigured = true;
        this.generatedPlacement = generatedPlacement;
        resetPlayback();
        syncConfiguration();
    }

    public void configureFreestanding(
            FreestandingAdType type, Direction.Axis longAxis) {
        if (longAxis == Direction.Axis.Y) {
            throw new IllegalArgumentException("Freestanding ads require a horizontal long axis");
        }
        freestandingType = type;
        generatedPlacement = false;
        campaign = AdCampaign.GENERAL;
        campaignConfigured = true;
        this.longAxis = longAxis;
        width = type.faceLength();
        height = type.height();
        resetPlayback();
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
        output.putString("Campaign", campaign.id());
        output.putBoolean("GeneratedPlacement", generatedPlacement);
        if (freestandingType != null) {
            output.putString("FreestandingType", freestandingType.id());
            output.putString("LongAxis", longAxis.getName());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        var loadedCampaign = AdCampaign.byId(input.getStringOr("Campaign", ""));
        campaign = loadedCampaign.orElse(AdCampaign.GENERAL);
        campaignConfigured = loadedCampaign.isPresent();
        freestandingType = FreestandingAdType.byId(
                input.getStringOr("FreestandingType", "")).orElse(null);
        campaignConfigured = campaignConfigured || freestandingType != null;
        longAxis = "z".equals(input.getStringOr("LongAxis", "x"))
                ? Direction.Axis.Z
                : Direction.Axis.X;
        if (freestandingType != null) {
            generatedPlacement = false;
            width = freestandingType.faceLength();
            height = freestandingType.height();
        } else {
            int loadedWidth = input.getIntOr("Width", LargeAdSurfaceValidator.WIDTH);
            int loadedHeight = input.getIntOr("Height", LargeAdSurfaceValidator.HEIGHT);
            if (LargeAdSurfaceValidator.validDimensions(loadedWidth, loadedHeight)) {
                width = loadedWidth;
                height = loadedHeight;
            }
            generatedPlacement = input.getBooleanOr("GeneratedPlacement", false);
        }
        resetPlayback();
        if (!campaignConfigured && level instanceof ServerLevel serverLevel) {
            migrateLegacyCampaign(serverLevel);
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (!campaignConfigured && level instanceof ServerLevel serverLevel) {
            migrateLegacyCampaign(serverLevel);
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
        AdvertisingContent.sound(currentClip()).ifPresent(sound -> level.playLocalSound(
                centerX(position, state), centerY(position), centerZ(position, state),
                sound, SoundSource.BLOCKS, 1.0F, 1.0F, false));
    }

    private boolean isAudible(Level level, BlockPos position, BlockState state) {
        if (freestandingType != null && !freestandingType.audioEnabled()) {
            return false;
        }
        double x = centerX(position, state);
        double y = centerY(position);
        double z = centerZ(position, state);
        double audioRange = generatedPlacement ? GENERATED_AUDIO_RANGE : MANUAL_AUDIO_RANGE;
        for (var player : level.players()) {
            if (player.isLocalPlayer()
                    && player.distanceToSqr(x, y, z) <= audioRange * audioRange) {
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

    private int deterministicClipIndex() {
        return Math.floorMod(
                Long.hashCode(worldPosition.asLong()), campaign.clips().size());
    }

    private void resetPlayback() {
        clipIndex = deterministicClipIndex();
        playbackTicks = 0;
        soundStartedForClip = false;
    }

    private void migrateLegacyCampaign(ServerLevel serverLevel) {
        campaign = AdCampaign.forLevel(serverLevel, worldPosition);
        campaignConfigured = true;
        resetPlayback();
        setChanged();
        serverLevel.sendBlockUpdated(
                worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }
}
