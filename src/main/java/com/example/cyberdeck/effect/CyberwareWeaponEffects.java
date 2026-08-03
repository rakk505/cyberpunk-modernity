package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareStats;
import com.example.cyberdeck.weapon.CyberdeckDamageTypes;
import com.example.cyberdeck.weapon.GunFiring;
import com.example.cyberdeck.weapon.GunType;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Weapon hooks for hand, circulatory, nervous-system, and cellular cyberware. */
public final class CyberwareWeaponEffects {
    private static final Map<UUID, Integer> MICROGENERATOR_CHARGE = new HashMap<>();

    private CyberwareWeaponEffects() {
    }

    public static void onShotFired(ServerPlayer player, GunType gun) {
        CyberwareStats stats = CyberwareStats.from(CyberwareAttachments.get(player));
        double staminaMultiplier = stats.rangedStaminaCostMultiplier()
                * (1.0 - CyberwareEffects.activeStaminaReduction(player));
        player.causeFoodExhaustion((float) (0.1 * staminaMultiplier));
        if (com.example.cyberdeck.movement.TacticalMovement.get(player).action()
                != com.example.cyberdeck.movement.TacticalAction.NONE) {
            ReactiveCyberware.triggerKerenzikov(player);
        }
    }

    public static float modifyGunDamage(
            ServerPlayer player,
            LivingEntity target,
            GunType gun,
            float damage,
            boolean headshot,
            boolean smartShot) {
        CyberwareStats stats = CyberwareStats.from(CyberwareAttachments.get(player));
        if (gun.isTech()) {
            damage *= (float) (1.0 + stats.techWeaponDamageBonus());
        }
        if (headshot) {
            damage *= (float) (1.0 + ReactiveCyberware.takedownHeadshotBonus(player));
        }
        double criticalChance = stats.criticalChance();
        Cyberware distanceCrit = CyberwareAttachments.get(player).findFlag("distance_crit");
        if (distanceCrit != null) {
            double distance = player.distanceTo(target);
            criticalChance += distanceCrit.value("distance_crit_percent") / 100.0
                    * Math.min(1.0,
                            distance / Math.max(1.0, distanceCrit.value("distance_crit_range")));
        }
        if (criticalChance > 0.0
                && player.getRandom().nextDouble() < Math.min(1.0, criticalChance)) {
            double criticalDamage = 0.5 + (smartShot ? stats.smartCriticalDamageBonus() : 0.0);
            damage *= (float) (1.0 + criticalDamage);
        }
        return damage;
    }

    public static void onGunHit(
            ServerLevel level,
            ServerPlayer player,
            LivingEntity target,
            GunType gun,
            float dealtDamage,
            Vec3 impact) {
        if (gun.isTech() && gun.reloadTicks() > 0) {
            applyTechHitRecovery(player);
        }
        dischargeMicrogenerator(level, player, impact);
        tryRicochet(level, player, target, gun, dealtDamage);
    }

    public static void armMicrogenerator(ServerPlayer player, int roundsLoaded) {
        if (roundsLoaded <= 0
                || CyberwareAttachments.get(player).findFlag("reload_shock") == null) {
            return;
        }
        MICROGENERATOR_CHARGE.put(player.getUUID(), Math.min(5, roundsLoaded));
    }

    public static boolean rangedWeaponsBlocked(ServerPlayer player) {
        Cyberware berserk = CyberwareAttachments.get(player).findFlag("berserk");
        return berserk != null
                && berserk.hasFlag("melee_only")
                && CyberwareEffects.isBerserkActive(player);
    }

    public static double effectiveSpreadReduction(ServerPlayer player) {
        CyberwareStats stats = CyberwareStats.from(CyberwareAttachments.get(player));
        return Math.min(0.9, stats.spreadReduction() + stats.recoilReduction() * 0.5);
    }

    public static void forget(UUID playerId) {
        MICROGENERATOR_CHARGE.remove(playerId);
    }

    public static void clearAll() {
        MICROGENERATOR_CHARGE.clear();
    }

    private static void applyTechHitRecovery(ServerPlayer player) {
        CyberwareDataView view = new CyberwareDataView(player);
        if (view.healthFraction > 0.0) {
            player.heal((float) (player.getMaxHealth() * view.healthFraction));
        }
        if (view.staminaFraction > 0.0) {
            int food = Math.max(1, (int) Math.round(20.0 * view.staminaFraction));
            player.getFoodData().setFoodLevel(Math.min(20,
                    player.getFoodData().getFoodLevel() + food));
            player.getFoodData().setSaturation(Math.min(
                    player.getFoodData().getFoodLevel(),
                    player.getFoodData().getSaturationLevel() + food));
        }
    }

    private static void dischargeMicrogenerator(
            ServerLevel level, ServerPlayer player, Vec3 impact) {
        Integer rounds = MICROGENERATOR_CHARGE.remove(player.getUUID());
        Cyberware generator = CyberwareAttachments.get(player).findFlag("reload_shock");
        if (rounds == null || generator == null) {
            return;
        }
        float damage = (float) Math.max(1.0,
                generator.value("reload_shock_damage") / 20.0 * rounds / 5.0);
        AABB area = new AABB(impact, impact).inflate(3.5);
        for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, area,
                entity -> entity != player && entity.isAlive())) {
            victim.hurtServer(level, level.damageSources().magic(), damage);
        }
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                impact.x, impact.y, impact.z, 28, 1.4, 0.8, 1.4, 0.12);
    }

    private static void tryRicochet(
            ServerLevel level,
            ServerPlayer player,
            LivingEntity primary,
            GunType gun,
            float dealtDamage) {
        Cyberware coprocessor = CyberwareAttachments.get(player).findFlag("ricochet");
        if (coprocessor == null || gun.isTech() || gun == GunType.YUKIMURA
                || player.getRandom().nextDouble()
                        >= coprocessor.value("ricochet_chance_percent") / 100.0) {
            return;
        }
        LivingEntity ricochetTarget = level.getEntitiesOfClass(
                        LivingEntity.class,
                        primary.getBoundingBox().inflate(7.0),
                        entity -> entity != primary
                                && GunFiring.canHitTarget(player, entity)
                                && primary.hasLineOfSight(entity))
                .stream()
                .min(Comparator.comparingDouble(primary::distanceToSqr))
                .orElse(null);
        if (ricochetTarget == null) {
            return;
        }
        float multiplier = (float) (0.35
                + coprocessor.value("ricochet_damage_percent") / 100.0);
        ricochetTarget.hurtServer(level,
                level.damageSources().source(CyberdeckDamageTypes.BULLET, player, player),
                dealtDamage * multiplier);
        level.sendParticles(ParticleTypes.CRIT,
                ricochetTarget.getX(), ricochetTarget.getY(0.5), ricochetTarget.getZ(),
                6, 0.2, 0.2, 0.2, 0.05);
    }

    private static final class CyberwareDataView {
        private final double healthFraction;
        private final double staminaFraction;

        private CyberwareDataView(ServerPlayer player) {
            Cyberware health = CyberwareAttachments.get(player).findFlag("health_on_tech_hit");
            Cyberware both = CyberwareAttachments.get(player)
                    .findFlag("health_stamina_on_tech_hit");
            double healthOnly = health == null
                    ? 0.0 : health.value("health_on_tech_hit_percent") / 100.0;
            double bothValue = both == null
                    ? 0.0 : both.value("health_stamina_on_tech_hit_percent") / 100.0;
            this.healthFraction = healthOnly + bothValue;
            this.staminaFraction = bothValue;
        }
    }
}
