package com.example.cyberdeck.faction;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Gives the synchronized Weapon Glitch state exclusive control of movement and looking. This stops
 * ranged, melee, and grenade goals from progressing while the soldier fiddles with or replaces its
 * weapon; the explicit attack guards on {@link FactionEnemy} remain as defense in depth.
 */
final class WeaponMalfunctionGoal extends Goal {
    private final FactionEnemy soldier;

    WeaponMalfunctionGoal(FactionEnemy soldier) {
        this.soldier = soldier;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return soldier.isWeaponGlitching();
    }

    @Override
    public boolean canContinueToUse() {
        return soldier.isWeaponGlitching();
    }

    @Override
    public void start() {
        soldier.getNavigation().stop();
    }

    @Override
    public void tick() {
        soldier.getNavigation().stop();
    }
}
