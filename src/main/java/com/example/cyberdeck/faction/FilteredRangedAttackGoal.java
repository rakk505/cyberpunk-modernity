package com.example.cyberdeck.faction;

import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.monster.RangedAttackMob;

/**
 * A {@link RangedAttackGoal} that only activates while {@code active} is true. Used so ranged
 * behavior runs only for a gun-armed {@link FactionEnemy}; a melee-armed soldier never has this goal
 * hold it at range (which would otherwise starve its approach), so it can path into melee instead.
 */
public final class FilteredRangedAttackGoal extends RangedAttackGoal {
    private final BooleanSupplier active;

    public <T extends Mob & RangedAttackMob> FilteredRangedAttackGoal(
            T mob, double speedModifier, int attackInterval, float attackRadius, BooleanSupplier active) {
        super(mob, speedModifier, attackInterval, attackRadius);
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
