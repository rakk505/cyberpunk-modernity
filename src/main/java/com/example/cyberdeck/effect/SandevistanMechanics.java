package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.SandevistanProfile;
import com.example.cyberdeck.weapon.GunType;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
            CyberwareAttachments.setSandevistanActive(player, false);
            ACTIVE_OWNERS.remove(player.getUUID());
            return ToggleResult.DEACTIVATED;
        }
        if (!state.canActivate(profile)) {
            return ToggleResult.RECHARGING;
        }
        state.activate();
        CyberwareAttachments.setSandevistanActive(player, true);
        ACTIVE_OWNERS.add(player.getUUID());
        return ToggleResult.ACTIVATED;
    }

    public static void tick(ServerPlayer player) {
        SandevistanProfile profile = installedProfile(player);
        SandevistanState state = CyberwareAttachments.getSandevistanState(player);
        if (profile == null) {
            if (state.active() || state.chargeTicks() > 0.0) {
                state.clear();
            }
            CyberwareAttachments.setSandevistanActive(player, false);
            ACTIVE_OWNERS.remove(player.getUUID());
            return;
        }
        state.tick(profile);
        // Mirror the authoritative active flag to tracking clients for the afterimage trail.
        CyberwareAttachments.setSandevistanActive(player, state.active());
        if (state.active()) {
            ACTIVE_OWNERS.add(player.getUUID());
        } else {
            ACTIVE_OWNERS.remove(player.getUUID());
        }
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
        CyberwareAttachments.setSandevistanActive(player, false);
        ACTIVE_OWNERS.remove(player.getUUID());
    }

    /** Login/logout/respawn ends the active run while retaining its persisted recharge progress. */
    public static void deactivateForSessionBoundary(ServerPlayer player) {
        CyberwareAttachments.getSandevistanState(player).deactivate();
        CyberwareAttachments.setSandevistanActive(player, false);
        ACTIVE_OWNERS.remove(player.getUUID());
    }

    /**
     * Entities currently inside somebody's dilation field, refreshed at most once per tick.
     *
     * <p>This used to be answered by asking, for every entity on every tick, whether any player
     * was slowing it - a scan of the whole player list per entity, paid even when nobody owned a
     * sandevistan. The relationship is inverted here: each active wearer collects the entities
     * near it once per tick with a single bounding-box query, and the per-entity question becomes
     * one map lookup. With nobody dilating, {@link #ACTIVE_OWNERS} is empty and the whole path
     * costs a single emptiness check.</p>
     */
    private static final Map<Integer, Double> SLOWED_TARGETS = new HashMap<>();
    private static final Set<UUID> ACTIVE_OWNERS = new HashSet<>();
    private static long slowRefreshTick = Long.MIN_VALUE;

    public static double slowFractionAffecting(Entity target) {
        if (ACTIVE_OWNERS.isEmpty() || !(target.level() instanceof ServerLevel level)) {
            return 0.0;
        }
        refreshSlowTargets(level);
        Double fraction = SLOWED_TARGETS.get(target.getId());
        return fraction == null ? 0.0 : fraction;
    }

    private static void refreshSlowTargets(ServerLevel level) {
        long now = level.getGameTime();
        if (now == slowRefreshTick) {
            return;
        }
        slowRefreshTick = now;
        SLOWED_TARGETS.clear();
        for (ServerPlayer owner : level.players()) {
            SandevistanProfile profile = activeProfile(owner);
            if (profile == null) {
                continue;
            }
            double fraction = profile.slowFraction(isAirborne(owner));
            if (fraction <= 0.0) {
                continue;
            }
            for (Entity nearby : level.getEntities(
                    owner, owner.getBoundingBox().inflate(EFFECT_RADIUS))) {
                // A wearer, and anything a wearer fired, keeps running at full speed.
                if (nearby instanceof ServerPlayer other && isActive(other)) {
                    continue;
                }
                if (nearby instanceof Projectile projectile
                        && projectile.getOwner() instanceof ServerPlayer shooter
                        && isActive(shooter)) {
                    continue;
                }
                SLOWED_TARGETS.merge(nearby.getId(), fraction, Math::max);
            }
        }
    }

    /** Server shutdown and level unload must not leave stale owners pinning the fast path open. */
    public static void clearAll() {
        ACTIVE_OWNERS.clear();
        SLOWED_TARGETS.clear();
        slowRefreshTick = Long.MIN_VALUE;
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
                                               float baseDamage, Vec3 impact, GunType gun) {
        boolean headshot = isHeadshot(target, impact);
        boolean smartShot = gun == GunType.YUKIMURA;
        float damage = CyberwareWeaponEffects.modifyGunDamage(
                shooter, target, gun, baseDamage, headshot, smartShot);
        SandevistanProfile profile = activeProfile(shooter);
        if (profile != null) {
            boolean airborne = isAirborne(shooter);
            damage *= (float) (1.0 + profile.damageBonus(airborne));
            if (headshot) {
                damage *= (float) (1.0 + profile.headshotBonus(airborne));
            }
            if (profile.critChance() > 0.0
                    && shooter.getRandom().nextDouble() < profile.critChance()) {
                damage *= (float) (1.5 + profile.critDamageBonus());
            }
        }

        PREMODIFIED_GUN_DAMAGE.set(true);
        try {
            boolean hurt = target.hurtServer(level, source, damage);
            if (hurt) {
                CyberwareWeaponEffects.onGunHit(
                        level, shooter, target, gun, damage, impact);
            }
            return hurt;
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
