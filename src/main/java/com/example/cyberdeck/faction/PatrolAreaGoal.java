package com.example.cyberdeck.faction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a mob strolling within a fixed radius of a home position instead of wandering the whole
 * map. Walk targets are biased back toward home, and any candidate outside {@code radius} of home
 * is rejected so the mob stays on station like a guard patrolling its post.
 */
public final class PatrolAreaGoal extends RandomStrollGoal {
    private final java.util.function.Supplier<BlockPos> home;
    private final double radius;

    public PatrolAreaGoal(PathfinderMob mob, double speedModifier,
                          java.util.function.Supplier<BlockPos> home, double radius) {
        super(mob, speedModifier);
        this.home = home;
        this.radius = radius;
    }

    @Override
    protected @Nullable Vec3 getPosition() {
        BlockPos anchor = home.get();
        if (anchor == null) {
            anchor = this.mob.blockPosition();
        }
        Vec3 anchorVec = Vec3.atCenterOf(anchor);

        // If we've drifted outside the patrol area, always head back toward home.
        if (this.mob.position().distanceToSqr(anchorVec) > radius * radius) {
            return LandRandomPos.getPosTowards(this.mob, 10, 7, anchorVec);
        }

        // Otherwise pick a wander target, but only accept it if it stays within the patrol radius.
        Vec3 candidate = LandRandomPos.getPosTowards(this.mob, 10, 7, anchorVec);
        if (candidate != null && candidate.distanceToSqr(anchorVec) <= radius * radius) {
            return candidate;
        }
        return null;
    }
}
