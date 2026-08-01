package com.example.cyberdeck.npc;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/** Highest-priority pathing used by an Exec while a Trauma Team aerodyne is waiting. */
final class EvacuateToAerodyneGoal extends Goal {
    private final CityNpc npc;
    private final double speed;
    private int repathTicks;

    EvacuateToAerodyneGoal(CityNpc npc, double speed) {
        this.npc = npc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return npc.isEvacuating() && npc.evacuationTarget() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return npc.isAlive() && npc.isEvacuating() && npc.evacuationTarget() != null;
    }

    @Override
    public void start() {
        repathTicks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        BlockPos target = npc.evacuationTarget();
        if (target == null) {
            return;
        }
        npc.getLookControl().setLookAt(
                target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
        if (--repathTicks <= 0 || npc.getNavigation().isDone()) {
            repathTicks = adjustedTickDelay(10);
            npc.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
        }
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
    }
}
