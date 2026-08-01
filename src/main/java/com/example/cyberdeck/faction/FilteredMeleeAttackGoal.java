package com.example.cyberdeck.faction;

import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * A {@link MeleeAttackGoal} that only activates while {@code active} is true. Used so a melee-armed
 * {@link FactionEnemy} (e.g. a sword specialist) actively paths in and strikes, while a gun holder
 * defers to its ranged behavior instead. The higher speed modifier passed at construction makes the
 * melee unit sprint to close the gap rather than shuffle forward.
 */
public final class FilteredMeleeAttackGoal extends MeleeAttackGoal {
    private final BooleanSupplier active;

    public FilteredMeleeAttackGoal(PathfinderMob mob, double speedModifier,
                                   boolean followingTargetEvenIfNotSeen, BooleanSupplier active) {
        super(mob, speedModifier, followingTargetEvenIfNotSeen);
        this.active = active;
    }

    @Override
    public boolean canUse() {
        return active.getAsBoolean() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return active.getAsBoolean() && super.canContinueToUse();
    }
}
