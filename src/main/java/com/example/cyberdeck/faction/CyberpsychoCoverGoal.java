package com.example.cyberdeck.faction;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Moves a pressured cyberpsycho behind real world geometry before it re-engages. */
final class CyberpsychoCoverGoal extends Goal {
    private final CyberpsychoEntity psycho;
    private BlockPos cover;
    private int cooldown;

    CyberpsychoCoverGoal(CyberpsychoEntity psycho) {
        this.psycho = psycho;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        LivingEntity target = psycho.getTarget();
        if (!psycho.isTriggered() || target == null || !target.isAlive()
                || !(psycho.level() instanceof ServerLevel level)) {
            return false;
        }
        boolean pressured = psycho.isGunReloading()
                || psycho.getHealth() <= psycho.getMaxHealth() * 0.62F
                || psycho.getRandom().nextInt(reducedTickDelay(90)) == 0;
        if (!pressured) return false;
        cover = findCover(level, target);
        return cover != null;
    }

    @Override
    public boolean canContinueToUse() {
        return cover != null && !psycho.getNavigation().isDone()
                && psycho.distanceToSqr(Vec3.atCenterOf(cover)) > 2.0;
    }

    @Override
    public void start() {
        if (cover != null) psycho.getNavigation().moveTo(cover.getX() + 0.5,
                cover.getY(), cover.getZ() + 0.5, 1.3);
    }

    @Override
    public void tick() {
        LivingEntity target = psycho.getTarget();
        if (target != null) psycho.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }

    @Override
    public void stop() {
        cover = null;
        cooldown = reducedTickDelay(45);
    }

    private BlockPos findCover(ServerLevel level, LivingEntity target) {
        BlockPos origin = psycho.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int attempt = 0; attempt < 24; attempt++) {
            int radius = 5 + psycho.getRandom().nextInt(9);
            double angle = psycho.getRandom().nextDouble() * Math.PI * 2.0;
            int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
            BlockPos feet = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, origin.getY(), z));
            if (!level.isLoaded(feet)
                    || !level.getBlockState(feet.below()).blocksMotion()
                    || !level.isEmptyBlock(feet)
                    || !level.isEmptyBlock(feet.above())) {
                continue;
            }
            Vec3 destinationEye = Vec3.atBottomCenterOf(feet).add(0.0, 1.45, 0.0);
            HitResult obstruction = level.clip(new ClipContext(
                    target.getEyePosition(), destinationEye,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, psycho));
            if (obstruction.getType() != HitResult.Type.BLOCK) continue;
            double distance = psycho.distanceToSqr(Vec3.atCenterOf(feet));
            if (distance < bestDistance) {
                best = feet;
                bestDistance = distance;
            }
        }
        return best;
    }
}
