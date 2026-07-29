package com.example.cyberdeck.npc;

import com.example.cyberdeck.city.CityWorlds;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/** Highest-priority movement that fans pedestrians away from the last gunshot. */
final class FleeGunshotGoal extends Goal {
    private final CityNpc npc;
    private final double speed;
    private BlockPos destination;
    private int repathTicks;

    FleeGunshotGoal(CityNpc npc, double speed) {
        this.npc = npc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return npc.isFleeingGunfire() && chooseDestination();
    }

    @Override
    public boolean canContinueToUse() {
        return npc.isFleeingGunfire()
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
    }

    private boolean chooseDestination() {
        if (!(npc.level() instanceof ServerLevel level) || npc.gunshotSource() == null) {
            return false;
        }
        destination = CityWorlds.findStreetAway(level, npc.blockPosition(), npc.gunshotSource(),
                9, 22, 18, npc.getRandom());
        return destination != null;
    }

    private void moveToDestination() {
        if (destination != null
                && !npc.getNavigation().moveTo(
                    destination.getX() + 0.5,
                    destination.getY(),
                    destination.getZ() + 0.5,
                    speed)) {
            destination = null;
        }
    }
}
