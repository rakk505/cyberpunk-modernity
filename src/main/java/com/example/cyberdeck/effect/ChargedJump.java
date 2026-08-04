package com.example.cyberdeck.effect;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-owned press/hold/release mechanic for Fortified Ankles. */
public final class ChargedJump {
    public static final int MAX_CHARGE_TICKS = 24;
    private static final int RELEASE_COOLDOWN_TICKS = 6;
    private static final double MIN_VERTICAL_VELOCITY = 0.56;
    private static final double MAX_VERTICAL_VELOCITY = 1.22;
    private static final double MIN_FORWARD_VELOCITY = 0.08;
    private static final double MAX_FORWARD_VELOCITY = 0.48;

    private static final Map<UUID, Long> CHARGE_STARTED = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private ChargedJump() {
    }

    public static boolean start(ServerPlayer player) {
        long now = player.level().getGameTime();
        if (!canCharge(player) || now < COOLDOWN_UNTIL.getOrDefault(player.getUUID(), 0L)) {
            return false;
        }
        CHARGE_STARTED.putIfAbsent(player.getUUID(), now);
        return true;
    }

    public static boolean release(ServerPlayer player) {
        Long started = CHARGE_STARTED.remove(player.getUUID());
        if (started == null || !canCharge(player)) {
            return false;
        }
        long now = player.level().getGameTime();
        int chargeTicks = (int) Math.max(0L, Math.min(MAX_CHARGE_TICKS, now - started));
        double progress = chargeProgress(chargeTicks);
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() > 1.0e-6) {
            horizontal = horizontal.normalize().scale(forwardVelocity(progress));
        }
        Vec3 current = player.getDeltaMovement();
        player.setDeltaMovement(
                current.x + horizontal.x,
                verticalVelocity(progress),
                current.z + horizontal.z);
        player.hurtMarked = true;
        player.fallDistance = 0.0;
        COOLDOWN_UNTIL.put(player.getUUID(), now + RELEASE_COOLDOWN_TICKS);

        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CLOUD,
                    player.getX(), player.getY(), player.getZ(),
                    10 + (int) Math.round(progress * 18.0), 0.4, 0.08, 0.4, 0.04);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS,
                    0.55f, (float) (1.35 - progress * 0.45));
        }
        return true;
    }

    public static void tick(ServerPlayer player) {
        UUID id = player.getUUID();
        long now = player.level().getGameTime();
        Long started = CHARGE_STARTED.get(id);
        if (started != null
                && (CyberwareEffects.findFlag(player, "charged_jump") == null
                        || !player.isAlive())) {
            CHARGE_STARTED.remove(id);
        }
        if (now >= COOLDOWN_UNTIL.getOrDefault(id, 0L)) {
            COOLDOWN_UNTIL.remove(id);
        }
    }

    public static void cancel(ServerPlayer player) {
        CHARGE_STARTED.remove(player.getUUID());
    }

    public static void forget(UUID playerId) {
        CHARGE_STARTED.remove(playerId);
        COOLDOWN_UNTIL.remove(playerId);
    }

    public static void clearAll() {
        CHARGE_STARTED.clear();
        COOLDOWN_UNTIL.clear();
    }

    public static double chargeProgress(int chargeTicks) {
        return Math.max(0.0, Math.min(1.0, chargeTicks / (double) MAX_CHARGE_TICKS));
    }

    public static double verticalVelocity(double progress) {
        double eased = easeOutCubic(progress);
        return MIN_VERTICAL_VELOCITY
                + (MAX_VERTICAL_VELOCITY - MIN_VERTICAL_VELOCITY) * eased;
    }

    public static double forwardVelocity(double progress) {
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        return MIN_FORWARD_VELOCITY
                + (MAX_FORWARD_VELOCITY - MIN_FORWARD_VELOCITY) * clamped;
    }

    private static boolean canCharge(ServerPlayer player) {
        return CyberwareEffects.findFlag(player, "charged_jump") != null
                && player.isAlive()
                && !player.isSpectator()
                && !player.isPassenger()
                && !player.isInWater()
                && !player.isInLava()
                && !player.isFallFlying()
                && !player.getAbilities().flying
                && player.onGround();
    }

    private static double easeOutCubic(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return 1.0 - Math.pow(1.0 - clamped, 3.0);
    }
}
