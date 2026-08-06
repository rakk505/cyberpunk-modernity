package com.example.cyberdeck.faction;

import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Chrome that hostile soldiers actually run, rather than a stat line on a spawn table.
 *
 * <p>Everything here is shared by every {@link FactionEnemy}, so an elite corporate guard blinks
 * with exactly the same sandevistan the boss cyberpsycho uses - same dash, same cooldown, same
 * synced maneuver the renderer already animates - instead of a separate lookalike implementation
 * that drifts away from it.</p>
 */
public final class EnemyCyberware {
    public static final String SANDEVISTAN = "sandevistan";
    public static final String SUBDERMAL_ARMOR = "subdermal_armor";
    public static final String BLOOD_PUMP = "blood_pump";
    public static final String OPTICAL_CAMO = "optical_camo";
    public static final String MANTIS_BLADES = "mantis_blades";
    public static final String ARM_CANNON = "arm_cannon";

    /** Loadouts an elite can roll. Each one changes how the fight has to be played. */
    private static final List<List<String>> ELITE_LOADOUTS = List.of(
            List.of(SANDEVISTAN, SUBDERMAL_ARMOR),
            List.of(SANDEVISTAN, MANTIS_BLADES),
            List.of(MANTIS_BLADES, SUBDERMAL_ARMOR),
            List.of(ARM_CANNON, SUBDERMAL_ARMOR),
            List.of(ARM_CANNON, BLOOD_PUMP),
            List.of(SANDEVISTAN, ARM_CANNON));

    /** ~2.5s at 20 tps, so a sandevistan reads as bursts of blink rather than a teleport lock. */
    private static final int SANDEVISTAN_DASH_COOLDOWN_TICKS = 50;
    private static final int SANDEVISTAN_ATTEMPT_INTERVAL_TICKS = 15;
    private static final int BLOOD_PUMP_RECHARGE_TICKS = 400;
    private static final float BLOOD_PUMP_HEAL = 1.0F;
    private static final int ARM_CANNON_COOLDOWN_TICKS = 140;
    private static final double ARM_CANNON_MIN_RANGE = 6.0;
    private static final double ARM_CANNON_RANGE = 28.0;
    private static final float ARM_CANNON_POWER = 1.6F;
    private static final double MANTIS_BLADE_REACH_BONUS = 1.0;
    private static final double MANTIS_BLADE_DAMAGE_BONUS = 3.0;

    private EnemyCyberware() {
    }

    /** Rolls one elite loadout. Callers pick when an enemy is elite; this picks what that means. */
    public static List<String> rollEliteLoadout(RandomSource random) {
        return ELITE_LOADOUTS.get(random.nextInt(ELITE_LOADOUTS.size()));
    }

    /**
     * Applies the passive half of a loadout: armour plating, the visible blades an enemy fights
     * with, and the reach that makes those blades worth respecting.
     */
    public static void applyPassives(FactionEnemy enemy, List<String> cyberware) {
        if (cyberware.contains(SUBDERMAL_ARMOR)) {
            setBaseValueAtLeast(enemy, Attributes.ARMOR, 8.0);
            setBaseValueAtLeast(enemy, Attributes.ARMOR_TOUGHNESS, 3.0);
        }
        if (!cyberware.contains(MANTIS_BLADES)) return;
        // Mantis blades replace the held weapon outright: they are the arms, not a sidearm, so a
        // blade user closes distance instead of standing off with a rifle it can no longer hold.
        enemy.setItemSlot(EquipmentSlot.MAINHAND,
                new ItemStack(com.example.cyberdeck.weapon.WeaponItems.MANTIS_BLADE.get()));
        enemy.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        enemy.setDropChance(EquipmentSlot.MAINHAND, 0.08F);
        setBaseValueAtLeast(enemy, Attributes.ENTITY_INTERACTION_RANGE,
                baseValue(enemy, Attributes.ENTITY_INTERACTION_RANGE) + MANTIS_BLADE_REACH_BONUS);
        setBaseValueAtLeast(enemy, Attributes.ATTACK_DAMAGE,
                baseValue(enemy, Attributes.ATTACK_DAMAGE) + MANTIS_BLADE_DAMAGE_BONUS);
    }

    /** Runs the active half of a loadout for one tick. Called from {@link FactionEnemy#aiStep}. */
    static void tick(FactionEnemy enemy, ServerLevel level) {
        List<String> cyberware = enemy.installedCyberware();
        if (cyberware.isEmpty()) return;
        long now = level.getGameTime();
        LivingEntity target = enemy.getTarget();
        boolean engaged = target != null && target.isAlive() && enemy.isTriggered();

        if (engaged && cyberware.contains(SANDEVISTAN)
                && enemy.tickCount % SANDEVISTAN_ATTEMPT_INTERVAL_TICKS == 0
                && isCooldownReady(now, enemy.lastSandevistanDashTick(),
                        SANDEVISTAN_DASH_COOLDOWN_TICKS)
                && enemy.hasLineOfSight(target)
                && enemy.tryStartTacticalManeuver(TacticalManeuver.SANDEVISTAN_DASH, target)) {
            enemy.setLastSandevistanDashTick(now);
        }
        if (engaged && cyberware.contains(ARM_CANNON)
                && isCooldownReady(now, enemy.lastArmCannonTick(), ARM_CANNON_COOLDOWN_TICKS)) {
            fireArmCannon(enemy, level, target, now);
        }
        if (cyberware.contains(BLOOD_PUMP)
                && enemy.getHealth() < enemy.getMaxHealth()
                && enemy.tickCount % BLOOD_PUMP_RECHARGE_TICKS == 0) {
            enemy.heal(BLOOD_PUMP_HEAL);
            level.sendParticles(ParticleTypes.HEART,
                    enemy.getX(), enemy.getY() + enemy.getBbHeight() * 0.7, enemy.getZ(),
                    4, 0.25, 0.3, 0.25, 0.02);
        }
        if (cyberware.contains(OPTICAL_CAMO)
                && enemy.getHealth() <= enemy.getMaxHealth() * 0.45F
                && enemy.tickCount % 20 == 0) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    enemy.getX(), enemy.getY() + enemy.getBbHeight() * 0.55, enemy.getZ(),
                    8, 0.35, 0.7, 0.35, 0.04);
        }
    }

    /**
     * A shoulder-mounted launcher shot. It detonates on whatever the enemy is looking at, so cover
     * stops it the way cover stops the player's own launcher, and it never fires point blank -
     * an enemy that blows itself up is a joke, not a threat.
     */
    private static void fireArmCannon(
            FactionEnemy enemy, ServerLevel level, LivingEntity target, long now) {
        double distance = enemy.distanceTo(target);
        if (distance < ARM_CANNON_MIN_RANGE || distance > ARM_CANNON_RANGE
                || !enemy.hasLineOfSight(target)) {
            return;
        }
        enemy.setLastArmCannonTick(now);
        Vec3 eye = enemy.getEyePosition(1.0F);
        Vec3 aim = target.getBoundingBox().getCenter().subtract(eye).normalize();
        Vec3 end = eye.add(aim.scale(ARM_CANNON_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, enemy));
        Vec3 impact = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
        level.explode(enemy, impact.x, impact.y, impact.z, ARM_CANNON_POWER,
                Level.ExplosionInteraction.NONE);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
                impact.x, impact.y, impact.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.playSound(null, impact.x, impact.y, impact.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.6F, 1.1F);
    }

    /** Overflow-safe cooldown check; {@link Long#MIN_VALUE} represents "never used". */
    public static boolean isCooldownReady(long gameTime, long lastUseTick, int cooldownTicks) {
        return lastUseTick == Long.MIN_VALUE
                || gameTime < lastUseTick
                || gameTime - lastUseTick >= cooldownTicks;
    }

    private static double baseValue(
            FactionEnemy enemy,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var instance = enemy.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getBaseValue();
    }

    private static void setBaseValueAtLeast(
            FactionEnemy enemy,
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            double value) {
        var instance = enemy.getAttribute(attribute);
        if (instance != null && instance.getBaseValue() < value) instance.setBaseValue(value);
    }
}
