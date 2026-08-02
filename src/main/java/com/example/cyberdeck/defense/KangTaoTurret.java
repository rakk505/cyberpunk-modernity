package com.example.cyberdeck.defense;

import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import com.example.cyberdeck.weapon.GunFiring;
import com.example.cyberdeck.weapon.GunType;

/** A stationary automatic turret with a 270-degree horizontal firing arc. */
public final class KangTaoTurret extends Mob {
    static final float HALF_ROTATION_RANGE = 135.0F;
    static final GunType WEAPON_PROFILE = GunType.ASSAULT_RIFLE;
    static final int FIRE_INTERVAL_TICKS = WEAPON_PROFILE.cooldownTicks();
    static final int MIN_BURST_TICKS = 5 * 20;
    static final int MAX_BURST_TICKS = 6 * 20;
    static final int RELOAD_TICKS = WEAPON_PROFILE.reloadTimeTicks();
    static final double GUN_PIVOT_HEIGHT = 25.0 / 16.0;
    static final double MUZZLE_REACH = 24.5 / 16.0;
    private static final double DETECTION_RANGE = 32.0;
    private static final float YAW_SPEED = 12.0F;
    private static final float PITCH_SPEED = 8.0F;
    private static final float IDLE_SCAN_SPEED = 1.25F;
    private static final float MAX_PITCH = 45.0F;
    private static final int WRECK_LIFETIME_TICKS = 100;
    private static final float DEATH_EXPLOSION_RADIUS = 2.5F;

    private static final EntityDataAccessor<Float> DATA_BASE_YAW =
            SynchedEntityData.defineId(KangTaoTurret.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_DESTROYED =
            SynchedEntityData.defineId(KangTaoTurret.class, EntityDataSerializers.BOOLEAN);

    private long nextShotTick;
    private long burstEndTick;
    private long reloadEndTick;
    private int burstSequence;
    private boolean scanIncreasing = true;

    record BurstSchedule(long burstStartTick, long burstEndTick, long reloadEndTick) {
    }

    public KangTaoTurret(EntityType<? extends KangTaoTurret> entityType, Level level) {
        super(entityType, level);
        this.setPersistenceRequired();
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0)
                .add(Attributes.ARMOR, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.FOLLOW_RANGE, DETECTION_RANGE);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BASE_YAW, 0.0F);
        builder.define(DATA_DESTROYED, false);
    }

    @Override
    @SuppressWarnings("deprecation")
    public @Nullable SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            EntitySpawnReason spawnReason,
            @Nullable SpawnGroupData groupData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnReason, groupData);
        this.setBaseYaw(this.getYRot());
        this.yRotO = this.getYRot();
        return result;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(this.level() instanceof ServerLevel level) || this.isDeadOrDying()) {
            return;
        }

        long now = level.getGameTime();
        this.expireFireCycle(now);
        Vec3 movement = this.getDeltaMovement();
        this.setDeltaMovement(0.0, movement.y, 0.0);
        Player target = this.findTarget(level);
        this.setTarget(target);
        this.setAggressive(target != null);
        if (target == null) {
            this.scanIdle();
            return;
        }

        Vec3 targetPoint = target.getEyePosition();
        Vec3 gunPivot = this.position().add(0.0, GUN_PIVOT_HEIGHT, 0.0);
        float desiredYaw = yawTo(gunPivot, targetPoint);
        float desiredPitch = pitchTo(gunPivot, targetPoint);
        float clampedYaw = clampAimYaw(this.getBaseYaw(), desiredYaw);
        this.setYRot(Mth.approachDegrees(this.getYRot(), clampedYaw, YAW_SPEED));
        this.setXRot(Mth.approach(this.getXRot(), desiredPitch, PITCH_SPEED));
        this.setYHeadRot(this.getYRot());

        boolean aimed = Math.abs(Mth.degreesDifference(this.getYRot(), desiredYaw)) <= 3.0F
                && Math.abs(this.getXRot() - desiredPitch) <= 4.0F;
        if (!aimed) {
            return;
        }
        if (this.burstEndTick == 0L) {
            this.beginBurst(now);
        }
        if (now < this.burstEndTick && now >= this.nextShotTick) {
            Vec3 barrelDirection = aimDirection(this.getYRot(), this.getXRot());
            Vec3 muzzle = this.position().add(muzzleOffset(barrelDirection));
            Vec3 shotDirection = targetPoint.subtract(muzzle).normalize();
            GunFiring.fire(level, this, WEAPON_PROFILE, muzzle, shotDirection);
            this.nextShotTick = now + FIRE_INTERVAL_TICKS;
        }
    }

    private void beginBurst(long now) {
        long identity = this.getUUID().getMostSignificantBits()
                ^ Long.rotateLeft(this.getUUID().getLeastSignificantBits(), 17);
        BurstSchedule schedule = burstSchedule(now, identity, this.burstSequence++);
        this.burstEndTick = schedule.burstEndTick();
        this.reloadEndTick = schedule.reloadEndTick();
        this.nextShotTick = now;
    }

    private void expireFireCycle(long now) {
        if (this.burstEndTick != 0L && now >= this.reloadEndTick) {
            this.burstEndTick = 0L;
            this.reloadEndTick = 0L;
            this.nextShotTick = now;
        }
    }

    static BurstSchedule burstSchedule(long startTick, long identity, int sequence) {
        int burstTicks = burstDurationTicks(identity, sequence);
        long burstEndTick = startTick + burstTicks;
        return new BurstSchedule(startTick, burstEndTick, burstEndTick + RELOAD_TICKS);
    }

    static int burstDurationTicks(long identity, int sequence) {
        return ((identity ^ sequence) & 1L) == 0L ? MIN_BURST_TICKS : MAX_BURST_TICKS;
    }

    static Vec3 aimDirection(float yaw, float pitch) {
        return Vec3.directionFromRotation(pitch, yaw).normalize();
    }

    static Vec3 muzzleOffset(float yaw, float pitch) {
        return muzzleOffset(aimDirection(yaw, pitch));
    }

    private static Vec3 muzzleOffset(Vec3 direction) {
        return new Vec3(0.0, GUN_PIVOT_HEIGHT, 0.0)
                .add(direction.scale(MUZZLE_REACH));
    }

    private @Nullable Player findTarget(ServerLevel level) {
        List<Player> players = level.getEntitiesOfClass(
                Player.class,
                this.getBoundingBox().inflate(DETECTION_RANGE),
                player -> player.isAlive()
                        && !player.isCreative()
                        && !player.isSpectator()
                        && this.distanceToSqr(player) <= DETECTION_RANGE * DETECTION_RANGE
                        && isWithinFiringArc(
                                this.getBaseYaw(), yawTo(this.getEyePosition(), player.getEyePosition()))
                        && this.hasLineOfSight(player));
        return players.stream()
                .min(Comparator.comparingDouble(this::distanceToSqr))
                .orElse(null);
    }

    private void scanIdle() {
        float relativeYaw = Mth.degreesDifference(this.getBaseYaw(), this.getYRot());
        float nextYaw = relativeYaw + (this.scanIncreasing ? IDLE_SCAN_SPEED : -IDLE_SCAN_SPEED);
        if (nextYaw >= HALF_ROTATION_RANGE) {
            nextYaw = HALF_ROTATION_RANGE;
            this.scanIncreasing = false;
        } else if (nextYaw <= -HALF_ROTATION_RANGE) {
            nextYaw = -HALF_ROTATION_RANGE;
            this.scanIncreasing = true;
        }

        this.setYRot(Mth.wrapDegrees(this.getBaseYaw() + nextYaw));
        this.setXRot(Mth.approach(this.getXRot(), 0.0F, PITCH_SPEED * 0.5F));
        this.setYHeadRot(this.getYRot());
    }

    static boolean isWithinFiringArc(float baseYaw, float desiredYaw) {
        return Math.abs(Mth.degreesDifference(baseYaw, desiredYaw)) <= HALF_ROTATION_RANGE;
    }

    static float clampAimYaw(float baseYaw, float desiredYaw) {
        float relative = Mth.degreesDifference(baseYaw, desiredYaw);
        return baseYaw + Mth.clamp(relative, -HALF_ROTATION_RANGE, HALF_ROTATION_RANGE);
    }

    private static float yawTo(Vec3 origin, Vec3 target) {
        double dx = target.x - origin.x;
        double dz = target.z - origin.z;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    private static float pitchTo(Vec3 origin, Vec3 target) {
        Vec3 offset = target.subtract(origin);
        double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
        return Mth.clamp((float) -Math.toDegrees(Math.atan2(offset.y, horizontal)),
                -MAX_PITCH, MAX_PITCH);
    }

    public float getBaseYaw() {
        return this.entityData.get(DATA_BASE_YAW);
    }

    public void setBaseYaw(float baseYaw) {
        float wrapped = Mth.wrapDegrees(baseYaw);
        this.entityData.set(DATA_BASE_YAW, wrapped);
        this.setYRot(wrapped);
        this.setYHeadRot(wrapped);
        this.setYBodyRot(wrapped);
    }

    public boolean isDestroyed() {
        return this.entityData.get(DATA_DESTROYED);
    }

    @Override
    public void die(DamageSource source) {
        boolean firstDestruction = !this.isDestroyed();
        super.die(source);
        if (!firstDestruction) {
            return;
        }

        this.entityData.set(DATA_DESTROYED, true);
        this.setDeltaMovement(Vec3.ZERO);
        if (this.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    this.getX(), this.getY() + 1.2, this.getZ(),
                    14, 0.45, 0.6, 0.45, 0.04);
            level.explode(this,
                    this.getX(), this.getY() + 0.9, this.getZ(),
                    DEATH_EXPLOSION_RADIUS, Level.ExplosionInteraction.NONE);
        }
    }

    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.level() instanceof ServerLevel level && this.deathTime % 10 == 0) {
            level.sendParticles(ParticleTypes.SMOKE,
                    this.getX(), this.getY() + 1.4, this.getZ(),
                    2, 0.18, 0.25, 0.18, 0.01);
        }
        if (this.deathTime >= WRECK_LIFETIME_TICKS
                && !this.level().isClientSide()
                && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(Entity.RemovalReason.KILLED);
        }
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.HOSTILE;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat("BaseYaw", this.getBaseYaw());
        output.putBoolean("Destroyed", this.isDestroyed());
        output.putLong("NextShotTick", this.nextShotTick);
        output.putLong("BurstEndTick", this.burstEndTick);
        output.putLong("ReloadEndTick", this.reloadEndTick);
        output.putInt("BurstSequence", this.burstSequence);
        output.putBoolean("ScanIncreasing", this.scanIncreasing);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_BASE_YAW, input.getFloatOr("BaseYaw", this.getYRot()));
        this.entityData.set(DATA_DESTROYED, input.getBooleanOr("Destroyed", false));
        this.nextShotTick = input.getLongOr("NextShotTick", 0L);
        this.burstEndTick = input.getLongOr("BurstEndTick", 0L);
        this.reloadEndTick = input.getLongOr("ReloadEndTick", 0L);
        this.burstSequence = input.getIntOr("BurstSequence", 0);
        this.scanIncreasing = input.getBooleanOr("ScanIncreasing", true);
    }
}
