package com.example.cyberdeck.npc;

import com.example.cyberdeck.city.CityWorlds;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

/** Low-frequency, street-constrained wandering instead of unrestricted random roaming. */
final class CityStreetStrollGoal extends Goal {
    private final CityNpc npc;
    private final double speed;
    private BlockPos destination;
    private Path route;

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
            BlockPos candidate = CityWorlds.findPedestrianAreaNear(
                    level, npc.blockPosition(), 6, 24, 16, npc.getRandom());
            if (candidate != null && (!npc.hasHome()
                    || candidate.distSqr(npc.getHomePosition()) <= 28.0 * 28.0)
                    && destinationIsClear(level, candidate)) {
                Path candidateRoute = npc.getNavigation().createPath(candidate, 0);
                if (candidateRoute != null && candidateRoute.canReach()) {
                    destination = candidate;
                    route = candidateRoute;
                    return true;
                }
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
        if (route != null && !npc.getNavigation().moveTo(route, speed)) {
            destination = null;
            route = null;
        }
    }

    @Override
    public void stop() {
        destination = null;
        route = null;
    }

    private boolean destinationIsClear(ServerLevel level, BlockPos candidate) {
        AABB area = new AABB(candidate).inflate(5.0, 2.0, 5.0);
        return level.getEntitiesOfClass(
                CityNpc.class, area, other -> other != npc && other.isAlive()).isEmpty();
    }
}
