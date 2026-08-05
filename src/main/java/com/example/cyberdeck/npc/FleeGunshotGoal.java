package com.example.cyberdeck.npc;

import com.example.cyberdeck.city.CityWorlds;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

/** Highest-priority movement that fans pedestrians away from the last gunshot. */
final class FleeGunshotGoal extends Goal {
    private final CityNpc npc;
    private final double speed;
    private BlockPos destination;
    private Path route;
    private int repathTicks;
    /** Gate on {@link CityNpc#tickCount} to cap how often we attempt a (costly) flee pathfind. */
    private int nextAttemptTick;

    FleeGunshotGoal(CityNpc npc, double speed) {
        this.npc = npc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (npc.isEvacuating() || !npc.isFleeingGunfire()) {
            return false;
        }
        // A flee pathfind is expensive (and a failed one explores the most nodes). Without this
        // gate every fleeing-but-stuck NPC would run a full A* every tick; under a crowd that alone
        // can stall the server. Retry at most a few times per second per NPC.
        if (npc.tickCount < nextAttemptTick) {
            return false;
        }
        nextAttemptTick = npc.tickCount + adjustedTickDelay(10);
        return chooseDestination();
    }

    @Override
    public boolean canContinueToUse() {
        return !npc.isEvacuating() && npc.isFleeingGunfire()
                && (destination != null || !npc.getNavigation().isDone());
    }

    @Override
    public void start() {
        repathTicks = 0;
        moveToDestination();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (--repathTicks <= 0 || npc.getNavigation().isDone()) {
            repathTicks = adjustedTickDelay(12);
            if (chooseDestination()) {
                moveToDestination();
            }
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        destination = null;
        route = null;
    }

    private boolean chooseDestination() {
        if (!(npc.level() instanceof ServerLevel level) || npc.gunshotSource() == null) {
            return false;
        }
        BlockPos candidate = CityWorlds.findPedestrianAreaAway(
                level, npc.blockPosition(), npc.gunshotSource(), 9, 24, 24, npc.getRandom());
        if (candidate == null) {
            destination = null;
            route = null;
            return false;
        }
        Path candidateRoute = npc.getNavigation().createPath(candidate, 0);
        if (candidateRoute == null || !candidateRoute.canReach()) {
            destination = null;
            route = null;
            return false;
        }
        destination = candidate;
        route = candidateRoute;
        return true;
    }

    private void moveToDestination() {
        if (destination != null && route != null
                && !npc.getNavigation().moveTo(route, speed)) {
            destination = null;
            route = null;
        }
    }
}
