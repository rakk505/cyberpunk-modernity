package com.example.cyberdeck.npc;

import com.example.cyberdeck.city.CityWorlds;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/** Low-frequency, street-constrained wandering instead of unrestricted random roaming. */
final class CityStreetStrollGoal extends Goal {
    private final CityNpc npc;
    private final double speed;
    private BlockPos destination;

    CityStreetStrollGoal(CityNpc npc, double speed) {
        this.npc = npc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (npc.isFleeingGunfire() || !npc.getNavigation().isDone()
                || npc.getRandom().nextInt(reducedTickDelay(70)) != 0
                || !(npc.level() instanceof ServerLevel level)) {
            return false;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            BlockPos candidate = CityWorlds.findStreetNear(
                    level, npc.blockPosition(), 6, 20, 12, npc.getRandom());
            if (candidate != null && (!npc.hasHome()
                    || candidate.distSqr(npc.getHomePosition()) <= 48.0 * 48.0)) {
                destination = candidate;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !npc.isFleeingGunfire() && !npc.getNavigation().isDone();
    }

    @Override
    public void start() {
        if (destination != null) {
            npc.getNavigation().moveTo(
                    destination.getX() + 0.5,
                    destination.getY(),
                    destination.getZ() + 0.5,
                    speed);
        }
    }

    @Override
    public void stop() {
        destination = null;
    }
}
