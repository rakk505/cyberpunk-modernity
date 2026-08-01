package com.example.cyberdeck.faction;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/** Cycles a passive faction enemy through an authored sequence of patrol waypoints. */
public final class PatrolRouteGoal extends Goal {
    private static final double ARRIVAL_DISTANCE_SQUARED = 2.25;
    private static final int REPATH_INTERVAL_TICKS = 20;

    private final FactionEnemy enemy;
    private final double speedModifier;
    private int waypointIndex;
    private int repathTicks;

    public PatrolRouteGoal(FactionEnemy enemy, double speedModifier) {
        this.enemy = enemy;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return canPatrol();
    }

    @Override
    public boolean canContinueToUse() {
        return canPatrol();
    }

    @Override
    public void start() {
        List<BlockPos> route = enemy.getPatrolRoute();
        waypointIndex = nearestWaypoint(route);
        repathTicks = 0;
        moveToCurrent(route);
    }

    @Override
    public void stop() {
        enemy.getNavigation().stop();
    }

    @Override
    public void tick() {
        List<BlockPos> route = enemy.getPatrolRoute();
        if (route.isEmpty()) {
            return;
        }
        waypointIndex = Math.floorMod(waypointIndex, route.size());
        BlockPos waypoint = route.get(waypointIndex);
        if (enemy.distanceToSqr(
                waypoint.getX() + 0.5,
                waypoint.getY(),
                waypoint.getZ() + 0.5) <= ARRIVAL_DISTANCE_SQUARED) {
            waypointIndex = (waypointIndex + 1) % route.size();
            repathTicks = 0;
            moveToCurrent(route);
            return;
        }
        if (--repathTicks <= 0 || enemy.getNavigation().isDone()) {
            moveToCurrent(route);
        }
    }

    private boolean canPatrol() {
        return !enemy.getPatrolRoute().isEmpty()
                && !enemy.isTriggered()
                && enemy.getTarget() == null;
    }

    private int nearestWaypoint(List<BlockPos> route) {
        int nearest = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < route.size(); index++) {
            double distance = route.get(index).distToCenterSqr(enemy.position());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = index;
            }
        }
        return nearest;
    }

    private void moveToCurrent(List<BlockPos> route) {
        if (route.isEmpty()) {
            return;
        }
        BlockPos waypoint = route.get(Math.floorMod(waypointIndex, route.size()));
        enemy.getNavigation().moveTo(
                waypoint.getX() + 0.5,
                waypoint.getY(),
                waypoint.getZ() + 0.5,
                speedModifier);
        repathTicks = REPATH_INTERVAL_TICKS;
    }
}
