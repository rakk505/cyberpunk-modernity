package com.example.cyberdeck.faction;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Makes a {@link FactionEnemy} periodically lob a grenade at its current target. Only usable once
 * the soldier is triggered, has a living target it can see within throwing range, and still has
 * grenades in stock. A cooldown between throws keeps them from spamming.
 */
public final class ThrowGrenadeGoal extends Goal {
    private static final double MIN_RANGE = 5.5;   // blast radius plus a self-damage margin
    private static final double MAX_RANGE = 16.0;  // realistic lob distance
    private static final double MIN_RANGE_SQR = MIN_RANGE * MIN_RANGE;
    private static final double MAX_RANGE_SQR = MAX_RANGE * MAX_RANGE;
    private static final int WINDUP_TICKS = 25;    // aim before releasing
    private static final int COOLDOWN_TICKS = 120; // ~6s between grenades

    private final FactionEnemy soldier;
    private LivingEntity target;
    private int windup;
    private int cooldown;

    public ThrowGrenadeGoal(FactionEnemy soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (soldier.isWeaponGlitching()
                || !soldier.canUseConventionalCombat()
                || soldier.getGrenadeCount() <= 0
                || !soldier.isTriggered()) {
            return false;
        }
        LivingEntity candidate = soldier.getTarget();
        if (candidate == null || !candidate.isAlive()) {
            return false;
        }
        double distSqr = soldier.distanceToSqr(candidate);
        if (distSqr < MIN_RANGE_SQR || distSqr > MAX_RANGE_SQR) {
            return false;
        }
        if (!soldier.hasLineOfSight(candidate)) {
            return false;
        }
        // Don't even wind up a throw that a squadmate is blocking (friendly-fire prevention).
        if (soldier.hasAllyInLineOfFire(candidate)) {
            return false;
        }
        if (soldier.hasCombatAllyNear(
                candidate.getBoundingBox().getCenter(), soldier.getGrenadeType().radius())) {
            return false;
        }
        this.target = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !soldier.isWeaponGlitching()
                && soldier.canUseConventionalCombat()
                && windup > 0 && target != null && target.isAlive()
                && soldier.getGrenadeCount() > 0;
    }

    @Override
    public void start() {
        this.windup = WINDUP_TICKS;
    }

    @Override
    public void stop() {
        this.target = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        soldier.getLookControl().setLookAt(target, 30.0f, 30.0f);
        if (windup > 0) {
            windup--;
            if (windup == 0) {
                soldier.throwGrenadeAt(target);
                cooldown = COOLDOWN_TICKS;
            }
        }
    }
}
