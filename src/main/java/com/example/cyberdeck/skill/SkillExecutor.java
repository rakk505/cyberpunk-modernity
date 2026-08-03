package com.example.cyberdeck.skill;

import com.example.cyberdeck.WeaponGlitchData;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.npc.CityNpc;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Applies each cyberdeck skill to a targeted entity, server-side.
 */
public final class SkillExecutor {
    private static final int INFINITE = -1;

    private SkillExecutor() {
    }

    public static void execute(Skill skill, ServerPlayer caster, LivingEntity target, ServerLevel level) {
        double damageMultiplier = com.example.cyberdeck.effect.CyberwareEffects
                .quickhackDamageMultiplier(caster, skill);
        switch (skill) {
            case OVERHEAT -> overheat(caster, target, level, damageMultiplier);
            case CRIPPLE -> cripple(target);
            case SHORT_CIRCUIT -> shortCircuit(target);
            case CONTAGION -> contagion(caster, target, level, true, damageMultiplier);
            case WEAPON_GLITCH -> weaponGlitch(target, level);
            case CYBERPSYCHOSIS -> cyberpsychosis(caster, target, level);
            case DETONATE -> detonate(caster, target, level, damageMultiplier);
            case STANDBY -> {
                // no-op
            }
        }
    }

    // orange concrete (Overheat): small burn particles + fire damage of 35% of current health.
    private static void overheat(ServerPlayer caster, LivingEntity target, ServerLevel level,
                                 double damageMultiplier) {
        float damage = target.getHealth() * 0.35f * (float) damageMultiplier;
        target.igniteForSeconds((float) (4.0
                * com.example.cyberdeck.effect.CyberwareEffects
                        .quickhackDurationMultiplier(caster)));
        DamageSource source = level.damageSources().onFire();
        boolean hurt = target.hurtServer(level, source, damage);
        if (hurt && target instanceof FactionEnemy enemy) {
            enemy.onSuccessfulPlayerAttack(level, caster);
        }
        level.sendParticles(ParticleTypes.FLAME,
                target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                20, 0.3, 0.4, 0.3, 0.02);
        level.sendParticles(ParticleTypes.SMALL_FLAME,
                target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                15, 0.3, 0.4, 0.3, 0.01);
        playZap(level, target);
    }

    // grey concrete (Cripple Movement): Slowness V for 15 seconds.
    private static void cripple(LivingEntity target) {
        target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 15 * 20, 4, false, true));
    }

    // blue concrete (Short Circuit): infinite Weakness II.
    private static void shortCircuit(LivingEntity target) {
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, INFINITE, 1, false, true));
    }

    // green concrete (Contagion): poison 5s; nearby entities within 5 blocks have 50% chance to also
    // get Contagion (but those cannot spread it further).
    private static void contagion(
            ServerPlayer caster,
            LivingEntity target,
            ServerLevel level,
            boolean canSpread,
            double damageMultiplier) {
        int duration = (int) Math.round(5 * 20
                * com.example.cyberdeck.effect.CyberwareEffects
                        .quickhackDurationMultiplier(caster)
                * damageMultiplier);
        boolean applied = target.addEffect(
                new MobEffectInstance(MobEffects.POISON, duration, 0, false, true));
        if (applied && target instanceof FactionEnemy enemy) {
            enemy.onSuccessfulPlayerAttack(level, caster);
        }
        level.sendParticles(ParticleTypes.SNEEZE,
                target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                12, 0.3, 0.4, 0.3, 0.02);
        if (!canSpread) {
            return;
        }
        double radius = com.example.cyberdeck.effect.CyberwareEffects
                .quickhackSpreadRadius(caster, 5.0);
        AABB area = target.getBoundingBox().inflate(radius);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e != target && e.isAlive());
        for (LivingEntity other : nearby) {
            if (level.getRandom().nextDouble()
                    < com.example.cyberdeck.effect.CyberwareEffects
                            .quickhackSpreadChance(caster)) {
                contagion(caster, other, level, false, damageMultiplier);
            }
        }
    }

    // Purple concrete (Weapon Glitch): faction guns malfunction and switch/recover through their
    // dedicated state machine; other ranged mobs receive a temporary projectile lockout.
    private static void weaponGlitch(LivingEntity target, ServerLevel level) {
        if (target instanceof FactionEnemy enemy) {
            enemy.beginWeaponGlitch(level);
            return;
        }

        WeaponGlitchData.glitch(target);
        target.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.DISPENSER_FAIL, SoundSource.HOSTILE, 0.9f, 1.6f);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                target.getX(), target.getY(0.65), target.getZ(),
                8, 0.25, 0.2, 0.25, 0.03);
    }

    // red concrete (Cyberpsychosis): mob becomes hostile to nearby mobs; if none within 5 blocks, it dies.
    private static void cyberpsychosis(
            ServerPlayer caster, LivingEntity target, ServerLevel level) {
        AABB area = target.getBoundingBox().inflate(5.0);
        List<Mob> nearby = level.getEntitiesOfClass(Mob.class, area,
                e -> e != target && e.isAlive() && !(e instanceof CityNpc));
        if (nearby.isEmpty()) {
            boolean hurt = target.hurtServer(
                    level, level.damageSources().magic(), Float.MAX_VALUE);
            if (hurt && target instanceof FactionEnemy enemy) {
                enemy.onSuccessfulPlayerAttack(level, caster);
            }
            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    20, 0.3, 0.4, 0.3, 0.02);
            return;
        }
        if (target instanceof Mob mob) {
            // Pick the nearest other mob and set it as the attack target.
            Mob nearest = null;
            double best = Double.MAX_VALUE;
            for (Mob candidate : nearby) {
                double d = candidate.distanceToSqr(mob);
                if (d < best) {
                    best = d;
                    nearest = candidate;
                }
            }
            if (nearest != null) {
                mob.setTarget(nearest);
                mob.setLastHurtByMob(nearest);
            }
        }
        level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
                target.getX(), target.getY() + target.getBbHeight() + 0.5, target.getZ(),
                8, 0.3, 0.2, 0.3, 0.0);
    }

    // yellow concrete (Detonate): explosion where the mob stands; creepers explode 2-3x larger.
    private static void detonate(ServerPlayer caster, LivingEntity target, ServerLevel level,
                                 double damageMultiplier) {
        float healthBefore = target.getHealth();
        float radius = 3.0f;
        if (target instanceof Creeper) {
            // Creeper base explosion power is ~3; make it 2-3x larger.
            radius = 3.0f * (2.0f + level.getRandom().nextFloat());
        }
        level.explode(null, target.getX(), target.getY(), target.getZ(),
                radius, Level.ExplosionInteraction.MOB);
        boolean hurt = !target.isAlive() || target.getHealth() < healthBefore;
        if (target.isAlive()) {
            hurt |= target.hurtServer(level,
                    level.damageSources().explosion((net.minecraft.world.entity.Entity) null, null),
                    radius * 2.0f * (float) damageMultiplier);
        }
        if (hurt && target instanceof FactionEnemy enemy) {
            enemy.onSuccessfulPlayerAttack(level, caster);
        }
    }

    private static void playZap(ServerLevel level, Entity target) {
        level.playSound(null, target.blockPosition(),
                SoundEvents.CANDLE_EXTINGUISH, SoundSource.PLAYERS, 0.6f, 1.6f);
    }
}
