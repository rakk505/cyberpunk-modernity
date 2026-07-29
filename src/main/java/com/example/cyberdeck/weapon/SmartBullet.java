package com.example.cyberdeck.weapon;

import com.example.cyberdeck.effect.SandevistanMechanics;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Physical Yukimura smart round. It leaves the muzzle on an upward tangent, then progressively
 * steers toward the server-authoritative locked target, producing a visible curved flight path.
 */
public final class SmartBullet extends Projectile {
    private static final double SPEED = 1.65;
    private static final int MAX_LIFETIME_TICKS = 60;
    private static final double MAX_TRAVEL = GunType.YUKIMURA.range() * 1.4;

    private int targetId = -1;
    private double distanceTravelled;

    public SmartBullet(EntityType<? extends SmartBullet> type, Level level) {
        super(type, level);
    }

    public SmartBullet(ServerLevel level, ServerPlayer owner, LivingEntity target) {
        this(WeaponEntities.SMART_BULLET.get(), level);
        this.setOwner(owner);
        this.targetId = target.getId();
        Vec3 look = owner.getLookAngle().normalize();
        Vec3 muzzle = owner.getEyePosition().add(look.scale(1.0)).add(0.0, -0.12, 0.0);
        this.setPos(muzzle.x, muzzle.y, muzzle.z);
        // A shallow sideways hook remains visible in the particle trail without making smart
        // rounds clip ordinary two-block ceilings. Alternate the side for a little visual variety.
        Vec3 side = look.cross(new Vec3(0.0, 1.0, 0.0));
        if (side.lengthSqr() > 1.0E-6) {
            side = side.normalize().scale((target.getId() & 1) == 0 ? 0.24 : -0.24);
        }
        this.setDeltaMovement(look.scale(1.45).add(side).add(0.0, 0.08, 0.0));
        this.updateRotation();
    }

    public static SmartBullet spawn(ServerLevel level, ServerPlayer owner, LivingEntity target) {
        SmartBullet bullet = new SmartBullet(level, owner, target);
        level.addFreshEntity(bullet);
        return bullet;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.tickCount > MAX_LIFETIME_TICKS || this.distanceTravelled > MAX_TRAVEL) {
            this.discard();
            return;
        }

        Entity entity = level.getEntity(this.targetId);
        if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
            this.discard();
            return;
        }

        Vec3 movement = this.steeredMovement(target);
        this.setDeltaMovement(movement);
        this.needsSync = true;

        Vec3 previous = this.position();
        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit.getType() != HitResult.Type.MISS && !EventHooks.onProjectileImpact(this, hit)) {
            Vec3 impact = hit.getLocation();
            this.distanceTravelled += previous.distanceTo(impact);
            this.setPos(impact.x, impact.y, impact.z);
            this.hitTargetOrDeflectSelf(hit);
            if (this.isRemoved()) {
                return;
            }
            // Deflections may replace the velocity. Continue from the impact point next tick
            // instead of tunnelling through with the stale pre-impact movement.
            this.needsSync = true;
            this.updateRotation();
            return;
        }

        Vec3 next = previous.add(movement);
        this.setPos(next.x, next.y, next.z);
        this.distanceTravelled += movement.length();
        this.updateRotation();

        Vec3 midpoint = previous.add(movement.scale(0.5));
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                previous.x, previous.y, previous.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.sendParticles(ParticleTypes.END_ROD,
                midpoint.x, midpoint.y, midpoint.z, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private Vec3 steeredMovement(LivingEntity target) {
        Vec3 current = this.getDeltaMovement();
        Vec3 targetPoint = target.getBoundingBox().getCenter();
        double distance = this.position().distanceTo(targetPoint);
        double leadTicks = Math.min(5.0, distance / SPEED);
        targetPoint = targetPoint.add(target.getDeltaMovement().scale(leadTicks * 0.55));
        Vec3 desired = targetPoint.subtract(this.position());
        if (desired.lengthSqr() < 1.0E-6) {
            return current;
        }
        desired = desired.normalize().scale(SPEED);
        double steering = Mth.clamp(0.10 + this.tickCount * 0.015, 0.10, 0.42);
        Vec3 blended = current.lerp(desired, steering);
        return blended.lengthSqr() < 1.0E-6 ? desired : blended.normalize().scale(SPEED);
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return entity != this.getOwner()
                && entity instanceof LivingEntity living
                && living.isAlive()
                && super.canHitEntity(entity);
    }

    @Override
    protected void onHitEntity(EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (this.level() instanceof ServerLevel level
                && hitResult.getEntity() instanceof LivingEntity target) {
            float damage = GunType.YUKIMURA.damageAtDistance(this.distanceTravelled);
            Vec3 impact = hitResult.getLocation();
            var source = this.damageSources().source(DamageTypes.ARROW, this, this.getOwner());
            if (this.getOwner() instanceof ServerPlayer player) {
                SandevistanMechanics.hurtWithGunModifiers(
                        level, player, target, source, damage, impact);
            } else {
                target.hurtServer(level, source, damage);
            }
            level.sendParticles(ParticleTypes.CRIT,
                    impact.x, impact.y, impact.z, 5, 0.08, 0.08, 0.08, 0.02);
        }
        this.discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult hitResult) {
        super.onHitBlock(hitResult);
        if (this.level() instanceof ServerLevel level) {
            Vec3 impact = hitResult.getLocation();
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    impact.x, impact.y, impact.z, 5, 0.05, 0.05, 0.05, 0.02);
        }
        this.discard();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("Target", this.targetId);
        output.putDouble("DistanceTravelled", this.distanceTravelled);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.targetId = input.getIntOr("Target", -1);
        this.distanceTravelled = input.getDoubleOr("DistanceTravelled", 0.0);
    }
}
