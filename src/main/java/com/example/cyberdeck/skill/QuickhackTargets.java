package com.example.cyberdeck.skill;

import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.vehicle.VehicleQuickhackService;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Shared target classification used by scanner rendering and server packet validation. */
public final class QuickhackTargets {
    private QuickhackTargets() {
    }

    public static boolean isScannable(@Nullable Entity entity) {
        if (entity == null || entity.isRemoved() || !entity.isAlive()
                || !entity.isPickable()) {
            return false;
        }
        return entity instanceof Enemy
                || entity instanceof CityNpc
                || entity instanceof KangTaoTurret
                || VehicleQuickhackService.isCar(entity);
    }

    public static boolean isActionable(@Nullable Entity entity) {
        return entity instanceof LivingEntity living && living instanceof Enemy
                && living.isAlive()
                || isDevice(entity);
    }

    public static boolean isDevice(@Nullable Entity entity) {
        return entity instanceof KangTaoTurret turret
                        && turret.isAlive() && !turret.isDestroyed()
                || VehicleQuickhackService.isCar(entity);
    }

    public static int actionCount(Entity entity) {
        return isDevice(entity)
                ? DeviceQuickhack.actionsFor(entity).size()
                : entity instanceof Enemy ? Skill.STANDBY.ordinal() : 0;
    }

    /** Exact server-side counterpart of the client's block-clipped scanner ray. */
    public static boolean isUnderScannerReticle(
            ServerPlayer player, Entity requestedTarget, ServerLevel level) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 reachEnd = eye.add(look.scale(QuickhackUploads.MAX_TARGET_RANGE));
        BlockHitResult blockHit = level.clip(new ClipContext(
                eye, reachEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 lineEnd = blockHit.getType() == HitResult.Type.MISS
                ? reachEnd : blockHit.getLocation();
        double reach = eye.distanceTo(lineEnd);
        AABB search = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eye, lineEnd, search,
                entity -> entity != player && !entity.isSpectator() && isScannable(entity),
                reach * reach);
        return hit != null && hit.getEntity() == requestedTarget;
    }
}
