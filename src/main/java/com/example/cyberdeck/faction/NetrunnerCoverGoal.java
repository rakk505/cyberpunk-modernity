package com.example.cyberdeck.faction;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Moves an uploading netrunner behind nearby, same-floor geometry while the trace stays active. */
final class NetrunnerCoverGoal extends Goal {
    private static final int SEARCH_ATTEMPTS = 28;
    private static final int MIN_RADIUS = 4;
    private static final int MAX_RADIUS = 10;

    private final FactionEnemy netrunner;
    private Path route;
    private int searchCooldown;

    NetrunnerCoverGoal(FactionEnemy netrunner) {
        this.netrunner = netrunner;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!netrunner.isEnemyQuickhackUploading()) {
            return false;
        }
        LivingEntity target = netrunner.getTarget();
        if (target == null || !target.isAlive()
                || !(netrunner.level() instanceof ServerLevel level)) {
            return false;
        }
        if (isObstructed(level, target, netrunner.getEyePosition())) {
            route = null;
        } else {
            route = findCover(level, target);
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = netrunner.getTarget();
        return netrunner.isEnemyQuickhackUploading()
                && target != null && target.isAlive();
    }

    @Override
    public void start() {
        searchCooldown = adjustedTickDelay(20);
        if (route == null) {
            netrunner.getNavigation().stop();
        } else {
            netrunner.getNavigation().moveTo(route, 1.15);
        }
    }

    @Override
    public void tick() {
        LivingEntity target = netrunner.getTarget();
        if (target != null) {
            netrunner.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        if (target == null || !(netrunner.level() instanceof ServerLevel level)) {
            return;
        }
        if (isObstructed(level, target, netrunner.getEyePosition())) {
            route = null;
            netrunner.getNavigation().stop();
            searchCooldown = adjustedTickDelay(20);
            return;
        }
        if (route != null && !netrunner.getNavigation().isDone()) {
            return;
        }
        if (--searchCooldown > 0) {
            netrunner.getNavigation().stop();
            return;
        }
        searchCooldown = adjustedTickDelay(20);
        route = findCover(level, target);
        if (route != null) {
            netrunner.getNavigation().moveTo(route, 1.15);
        }
    }

    @Override
    public void stop() {
        netrunner.getNavigation().stop();
        route = null;
        searchCooldown = 0;
    }

    private Path findCover(ServerLevel level, LivingEntity target) {
        BlockPos origin = netrunner.blockPosition();
        Path best = null;
        double bestScore = Double.MAX_VALUE;
        for (int attempt = 0; attempt < SEARCH_ATTEMPTS; attempt++) {
            int radius = MIN_RADIUS + netrunner.getRandom().nextInt(MAX_RADIUS - MIN_RADIUS + 1);
            double angle = netrunner.getRandom().nextDouble() * Math.PI * 2.0;
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos feet = new BlockPos(x, origin.getY() + dy, z);
                if (!isSafeSameFloor(level, origin, feet)) {
                    continue;
                }
                Vec3 destinationEye = Vec3.atBottomCenterOf(feet).add(0.0, 1.45, 0.0);
                if (!isObstructed(level, target, destinationEye)) {
                    continue;
                }
                Path candidate = netrunner.getNavigation().createPath(feet, 0);
                if (candidate == null || !candidate.canReach()) {
                    continue;
                }
                double travel = netrunner.distanceToSqr(Vec3.atBottomCenterOf(feet));
                double targetDistance = target.distanceToSqr(Vec3.atBottomCenterOf(feet));
                double score = travel - Math.min(targetDistance, 144.0) * 0.12;
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private boolean isObstructed(ServerLevel level, LivingEntity target, Vec3 destinationEye) {
        HitResult obstruction = level.clip(new ClipContext(
                target.getEyePosition(), destinationEye,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, netrunner));
        return obstruction.getType() == HitResult.Type.BLOCK;
    }

    static boolean isSafeSameFloor(ServerLevel level, BlockPos origin, BlockPos feet) {
        return Math.abs(feet.getY() - origin.getY()) <= 1
                && level.isLoaded(feet)
                && level.getBlockState(feet.below()).blocksMotion()
                && level.isEmptyBlock(feet)
                && level.isEmptyBlock(feet.above());
    }
}
