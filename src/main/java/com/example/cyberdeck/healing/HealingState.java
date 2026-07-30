package com.example.cyberdeck.healing;

import com.example.cyberdeck.Cyberdeck;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Server-owned cooldown and Bounce Back regeneration timestamps synchronized to the HUD. */
public record HealingState(
        long bounceBackReadyTick,
        long maxDocReadyTick,
        long regenerationEndTick,
        long nextRegenerationTick) {

    public static final HealingState NONE = new HealingState(0L, 0L, 0L, 0L);

    public static final StreamCodec<ByteBuf, HealingState> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, HealingState::bounceBackReadyTick,
            ByteBufCodecs.VAR_LONG, HealingState::maxDocReadyTick,
            ByteBufCodecs.VAR_LONG, HealingState::regenerationEndTick,
            ByteBufCodecs.VAR_LONG, HealingState::nextRegenerationTick,
            HealingState::new);

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<HealingState>> STATE =
            ATTACHMENT_TYPES.register("healing", () -> AttachmentType
                    .builder(() -> NONE)
                    .sync(STREAM_CODEC)
                    .build());

    public long readyTick(HealingConsumable consumable) {
        return consumable == HealingConsumable.BOUNCE_BACK
                ? bounceBackReadyTick
                : maxDocReadyTick;
    }

    public long cooldownRemaining(HealingConsumable consumable, long gameTick) {
        return Math.max(0L, readyTick(consumable) - gameTick);
    }

    public boolean ready(HealingConsumable consumable, long gameTick) {
        return cooldownRemaining(consumable, gameTick) == 0L;
    }

    public HealingState afterUse(HealingConsumable consumable, long gameTick) {
        long readyTick = gameTick + consumable.cooldownTicks();
        if (consumable == HealingConsumable.BOUNCE_BACK) {
            return new HealingState(
                    readyTick,
                    maxDocReadyTick,
                    gameTick + consumable.regenerationDurationTicks(),
                    gameTick + HealingConsumable.REGENERATION_INTERVAL_TICKS);
        }
        return new HealingState(
                bounceBackReadyTick,
                readyTick,
                regenerationEndTick,
                nextRegenerationTick);
    }

    public boolean regenerationPulseDue(long gameTick) {
        return nextRegenerationTick > 0L
                && nextRegenerationTick <= regenerationEndTick
                && gameTick >= nextRegenerationTick;
    }

    public HealingState afterRegenerationPulse() {
        long followingTick = nextRegenerationTick
                + HealingConsumable.REGENERATION_INTERVAL_TICKS;
        if (followingTick > regenerationEndTick) {
            return new HealingState(bounceBackReadyTick, maxDocReadyTick, 0L, 0L);
        }
        return new HealingState(
                bounceBackReadyTick,
                maxDocReadyTick,
                regenerationEndTick,
                followingTick);
    }

    public static HealingState get(Player player) {
        return player.getData(STATE.get());
    }

    public static void set(Player player, HealingState state) {
        player.setData(STATE.get(), state);
    }
}
