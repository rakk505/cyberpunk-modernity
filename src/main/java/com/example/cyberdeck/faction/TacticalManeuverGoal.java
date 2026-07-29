package com.example.cyberdeck.faction;

import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.weapon.GunItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Periodically asks a triggered gunner to make a short evasive movement. This goal intentionally
 * owns no {@link Flag}: it may run beside the vanilla ranged goal, which remains responsible for
 * aiming, navigation and firing while the entity-level maneuver state applies the brief impulse.
 */
public final class TacticalManeuverGoal extends Goal {
    private static final int INITIAL_DELAY_MIN = 35;
    private static final int INITIAL_DELAY_VARIANCE = 35;
    private static final int SUCCESS_COOLDOWN_MIN = 55;
    private static final int SUCCESS_COOLDOWN_VARIANCE = 46;
    private static final int RETRY_DELAY_MIN = 16;
    private static final int RETRY_DELAY_VARIANCE = 15;
    private static final double MIN_DISTANCE_SQR = 5.0 * 5.0;
    private static final double MAX_DISTANCE_SQR = 18.0 * 18.0;

    private final FactionEnemy soldier;
    private long nextDecisionTick = Long.MIN_VALUE;

    public TacticalManeuverGoal(FactionEnemy soldier) {
        this.soldier = soldier;
    }

    @Override
    public boolean canUse() {
        return hasCombatTarget() && soldier.getMainHandItem().getItem() instanceof GunItem;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        schedule(INITIAL_DELAY_MIN, INITIAL_DELAY_VARIANCE);
    }

    @Override
    public void stop() {
        soldier.endTacticalManeuver();
        nextDecisionTick = Long.MIN_VALUE;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (soldier.isTacticalManeuvering()
                || soldier.isWeaponGlitching()
                || soldier.isGunReloading()) {
            return;
        }

        long now = soldier.level().getGameTime();
        if (now < nextDecisionTick) {
            return;
        }

        LivingEntity target = soldier.getTarget();
        if (!isValidTarget(target)) {
            schedule(RETRY_DELAY_MIN, RETRY_DELAY_VARIANCE);
            return;
        }

        double distanceSqr = soldier.distanceToSqr(target);
        if (distanceSqr < MIN_DISTANCE_SQR
                || distanceSqr > MAX_DISTANCE_SQR
                || !soldier.hasLineOfSight(target)) {
            schedule(RETRY_DELAY_MIN, RETRY_DELAY_VARIANCE);
            return;
        }

        // Sliding is less common and only used while there is enough room to close the gap.
        TacticalManeuver maneuver;
        if (distanceSqr >= 8.0 * 8.0 && soldier.getRandom().nextFloat() < 0.30F) {
            maneuver = TacticalManeuver.SLIDE_FORWARD;
        } else {
            maneuver = soldier.getRandom().nextBoolean()
                    ? TacticalManeuver.DASH_LEFT
                    : TacticalManeuver.DASH_RIGHT;
        }

        if (soldier.tryStartTacticalManeuver(maneuver, target)) {
            schedule(SUCCESS_COOLDOWN_MIN, SUCCESS_COOLDOWN_VARIANCE);
        } else {
            schedule(RETRY_DELAY_MIN, RETRY_DELAY_VARIANCE);
        }
    }

    private boolean hasCombatTarget() {
        return soldier.isTriggered()
                && !soldier.isWeaponGlitching()
                && !soldier.isGunReloading()
                && isValidTarget(soldier.getTarget());
    }

    private boolean isValidTarget(LivingEntity target) {
        return target != null
                && target.isAlive()
                && !(target instanceof CityNpc)
                && soldier.canAttack(target);
    }

    private void schedule(int minimum, int variance) {
        nextDecisionTick = soldier.level().getGameTime()
                + minimum
                + soldier.getRandom().nextInt(variance);
    }
}
