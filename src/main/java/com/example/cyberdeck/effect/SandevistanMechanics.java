package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.SandevistanProfile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative Sandevistan state, time dilation, and weapon damage helpers. */
public final class SandevistanMechanics {
    public static final double EFFECT_RADIUS = 24.0;
    private static final double EFFECT_RADIUS_SQR = EFFECT_RADIUS * EFFECT_RADIUS;
    private static final ThreadLocal<Boolean> PREMODIFIED_GUN_DAMAGE =
            ThreadLocal.withInitial(() -> false);

    public enum ToggleResult {
        ACTIVATED,
        DEACTIVATED,
        RECHARGING,
        INVALID,
        DEBOUNCED
    }

    private SandevistanMechanics() {
    }

    public static SandevistanProfile installedProfile(ServerPlayer player) {
        Cyberware operatingSystem = CyberwareAttachments.get(player).get(BodySlot.OPERATING_SYSTEM);
        return SandevistanProfile.forCyberware(operatingSystem);
    }

    public static SandevistanProfile activeProfile(ServerPlayer player) {
        SandevistanProfile profile = installedProfile(player);
        if (profile == null) {
            return null;
        }
        SandevistanState state = CyberwareAttachments.getSandevistanState(player);
        state.ensureVariant(profile);
        return state.active() ? profile : null;
    }

    public static boolean isActive(ServerPlayer player) {
        return activeProfile(player) != null;
    }

    public static ToggleResult toggle(ServerPlayer player) {
        SandevistanProfile profile = installedProfile(player);
        if (profile == null) {
            return ToggleResult.INVALID;
        }
        SandevistanState state = CyberwareAttachments.getSandevistanState(player);
        state.ensureVariant(profile);
        long now = player.level().getGameTime();
        if (!state.canToggle(now)) {
            return ToggleResult.DEBOUNCED;
        }
        state.markToggled(now);
        if (state.active()) {
            state.deactivate();
            return ToggleResult.DEACTIVATED;
        }
        if (!state.canActivate(profile)) {
            return ToggleResult.RECHARGING;
        }
        state.activate();
        return ToggleResult.ACTIVATED;
    }

    public static void tick(ServerPlayer player) {
        SandevistanProfile profile = installedProfile(player);
        SandevistanState state = CyberwareAttachments.getSandevistanState(player);
        if (profile == null) {
            if (state.active() || state.chargeTicks() > 0.0) {
                state.clear();
            }
            return;
        }
        state.tick(profile);
    }

    /** Installing a new OS starts it full; replacing/removing one cannot leave an old effect active. */
    public static void onOperatingSystemChanged(ServerPlayer player, Cyberware installed) {
        SandevistanState state = CyberwareAttachments.getSandevistanState(player);
        SandevistanProfile profile = SandevistanProfile.forCyberware(installed);
        if (profile == null) {
            state.clear();
        } else {
            state.clear();
            state.ensureVariant(profile);
        }
    }

    /** Login/logout/respawn ends the active run while retaining its persisted recharge progress. */
    public static void deactivateForSessionBoundary(ServerPlayer player) {
        CyberwareAttachments.getSandevistanState(player).deactivate();
    }

    public static double slowFractionAffecting(Entity target) {
        if (!(target.level() instanceof ServerLevel level)) {
            return 0.0;
        }
        if (target instanceof ServerPlayer targetPlayer && isActive(targetPlayer)) {
            return 0.0;
        }
        if (target instanceof Projectile projectile
                && projectile.getOwner() instanceof ServerPlayer owner
                && isActive(owner)) {
            return 0.0;
        }

        double strongest = 0.0;
        for (ServerPlayer owner : level.players()) {
            SandevistanProfile profile = activeProfile(owner);
            if (profile == null || owner == target
                    || owner.distanceToSqr(target) > EFFECT_RADIUS_SQR) {
                continue;
            }
            strongest = Math.max(strongest, profile.slowFraction(isAirborne(owner)));
        }
        return strongest;
    }

    public static int slownessAmplifier(double slowFraction) {
        if (slowFraction <= 0.0) {
            return -1;
        }
        return Math.max(0, (int) Math.round(slowFraction / 0.15) - 1);
    }

    public static boolean isAirborne(ServerPlayer player) {
        return !player.onGround();
    }

    public static double outgoingDamageBonus(ServerPlayer player) {
        SandevistanProfile profile = activeProfile(player);
        return profile == null ? 0.0 : profile.damageBonus(isAirborne(player));
    }

    /**
     * Applies gun-only headshot and critical logic, then invokes damage synchronously. The thread-local
     * marker prevents the general incoming-damage event from applying the OS damage bonus twice.
     */
    public static boolean hurtWithGunModifiers(ServerLevel level, ServerPlayer shooter,
                                               LivingEntity target, DamageSource source,
                                               float baseDamage, Vec3 impact) {
        float damage = baseDamage;
        SandevistanProfile profile = activeProfile(shooter);
        if (profile != null) {
            boolean airborne = isAirborne(shooter);
            damage *= (float) (1.0 + profile.damageBonus(airborne));
            if (isHeadshot(target, impact)) {
                damage *= (float) (1.0 + profile.headshotBonus(airborne));
            }
            if (profile.critChance() > 0.0
                    && shooter.getRandom().nextDouble() < profile.critChance()) {
                damage *= (float) (1.5 + profile.critDamageBonus());
            }
        }

        PREMODIFIED_GUN_DAMAGE.set(true);
        try {
            return target.hurtServer(level, source, damage);
        } finally {
            PREMODIFIED_GUN_DAMAGE.remove();
        }
    }

    public static boolean gunDamageAlreadyModified() {
        return PREMODIFIED_GUN_DAMAGE.get();
    }

    private static boolean isHeadshot(LivingEntity target, Vec3 impact) {
        double headHeight = Math.min(0.6, Math.max(0.2, target.getBbHeight() * 0.25));
        return impact.y >= target.getBoundingBox().maxY - headHeight;
    }
}
