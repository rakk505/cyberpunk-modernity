package com.example.cyberdeck.defense;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.faction.FactionEnemy;
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
    private static final int MAX_PERSISTED_REMOTE_HOSTILES = 64;
    public static final int MIN_SECURITY_LEVEL = 1;
    public static final int MAX_SECURITY_LEVEL = 5;

    private static final EntityDataAccessor<Float> DATA_BASE_YAW =
            SynchedEntityData.defineId(KangTaoTurret.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_DESTROYED =
            SynchedEntityData.defineId(KangTaoTurret.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_SECURITY_LEVEL =
            SynchedEntityData.defineId(KangTaoTurret.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_DEACTIVATED =
            SynchedEntityData.defineId(KangTaoTurret.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_REMOTE_CONTROLLED =
            SynchedEntityData.defineId(KangTaoTurret.class, EntityDataSerializers.BOOLEAN);

    private long nextShotTick;
    private long burstEndTick;
    private long reloadEndTick;
    private long nextControlledShotTick;
    private long nextHostilityMaintenanceTick;
    private int burstSequence;
    private boolean scanIncreasing = true;
    private @Nullable UUID remoteControllerId;
    private final Set<UUID> remoteHostileIds = new HashSet<>();

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
        // Zero is an uninitialized sentinel. Newly finalized entities receive a random level.
        builder.define(DATA_SECURITY_LEVEL, 0);
        builder.define(DATA_DEACTIVATED, false);
        builder.define(DATA_REMOTE_CONTROLLED, false);
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
        if (this.entityData.get(DATA_SECURITY_LEVEL) == 0) {
            this.setSecurityLevel(MIN_SECURITY_LEVEL
                    + this.getRandom().nextInt(MAX_SECURITY_LEVEL - MIN_SECURITY_LEVEL + 1));
        }
        this.yRotO = this.getYRot();
        return result;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(this.level() instanceof ServerLevel level) || this.isDeadOrDying()) {
            return;
        }

        Vec3 movement = this.getDeltaMovement();
        this.setDeltaMovement(0.0, movement.y, 0.0);
        long now = level.getGameTime();
        if (now >= this.nextHostilityMaintenanceTick) {
            this.nextHostilityMaintenanceTick = now + 20L;
            this.maintainRemoteHostility(level);
        }
        if (this.isDeactivated()) {
            this.setTarget(null);
            this.setAggressive(false);
            this.resetFireCycle();
            return;
        }

        if (this.resolveRemoteController(level) != null) {
            this.setTarget(null);
            this.setAggressive(false);
            return;
        }

        this.expireFireCycle(now);
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

    private void resetFireCycle() {
        this.burstEndTick = 0L;
        this.reloadEndTick = 0L;
        this.nextShotTick = 0L;
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

    public static float clampAimYaw(float baseYaw, float desiredYaw) {
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

    /** Synced device security level, mapped one-to-one to cyberdeck tiers 1 through 5. */
    public int getSecurityLevel() {
        return Mth.clamp(this.entityData.get(DATA_SECURITY_LEVEL),
                MIN_SECURITY_LEVEL, MAX_SECURITY_LEVEL);
    }

    public void setSecurityLevel(int securityLevel) {
        this.entityData.set(DATA_SECURITY_LEVEL,
                Mth.clamp(securityLevel, MIN_SECURITY_LEVEL, MAX_SECURITY_LEVEL));
    }

    public boolean isDeactivated() {
        return this.entityData.get(DATA_DEACTIVATED);
    }

    public boolean isRemotelyControlled() {
        return this.entityData.get(DATA_REMOTE_CONTROLLED);
    }

    public @Nullable UUID getRemoteControllerId() {
        return this.remoteControllerId;
    }

    /** Converts T1/T1+ through T5/T5++ cyberdecks to the five device security levels. */
    public static int cyberdeckSecurityLevel(@Nullable CyberwareData data) {
        Cyberware cyberdeck = data == null ? null : data.findFlag("cyberdeck");
        if (cyberdeck == null) {
            return 0;
        }
        return Mth.clamp(1 + cyberdeck.tier().rank() / 2,
                MIN_SECURITY_LEVEL, MAX_SECURITY_LEVEL);
    }

    public boolean hasSufficientCyberdeck(ServerPlayer player) {
        return player != null
                && cyberdeckSecurityLevel(CyberwareAttachments.get(player))
                        >= this.getSecurityLevel();
    }

    /** Server-side authorization shared by every turret quickhack action. */
    public boolean canAcceptQuickhack(ServerPlayer player) {
        return player != null
                && player.isAlive()
                && !player.isSpectator()
                && player.level() == this.level()
                && this.isAlive()
                && !this.isDestroyed()
                && this.hasSufficientCyberdeck(player);
    }

    /** Deactivates targeting and firing without destroying the turret. */
    public boolean tryDeactivate(ServerPlayer player) {
        if (!this.canAcceptQuickhack(player)) {
            return false;
        }
        this.setDeactivated(true);
        return true;
    }

    public void setDeactivated(boolean deactivated) {
        this.entityData.set(DATA_DEACTIVATED, deactivated);
        if (deactivated) {
            this.endRemoteControlInternal();
            this.setTarget(null);
            this.setAggressive(false);
            this.resetFireCycle();
        }
    }

    /** Lethal quickhack entry point; the existing death path supplies the no-block-damage blast. */
    public boolean tryDetonate(ServerPlayer player) {
        if (!this.canAcceptQuickhack(player)
                || !(this.level() instanceof ServerLevel level)) {
            return false;
        }
        this.endRemoteControlInternal();
        return this.hurtServer(level, this.damageSources().playerAttack(player), Float.MAX_VALUE);
    }

    /** Starts an exclusive camera/control session after validating the player's cyberdeck tier. */
    public boolean tryBeginRemoteControl(ServerPlayer player) {
        if (!this.canAcceptQuickhack(player) || this.isDeactivated()
                || this.remoteControllerId != null
                        && !this.remoteControllerId.equals(player.getUUID())) {
            return false;
        }
        this.remoteControllerId = player.getUUID();
        this.entityData.set(DATA_REMOTE_CONTROLLED, true);
        this.resetFireCycle();
        this.nextControlledShotTick = this.level().getGameTime();
        this.updateRemoteAim(player, player.getYRot(), player.getXRot());
        return true;
    }

    /** Releases control only when invoked by the owning player. */
    public boolean endRemoteControl(ServerPlayer player) {
        if (player == null || this.remoteControllerId == null
                || !this.remoteControllerId.equals(player.getUUID())) {
            return false;
        }
        this.endRemoteControlInternal();
        return true;
    }

    /** Applies an owner-authenticated aim update from the remote-control input packet. */
    public boolean updateRemoteAim(ServerPlayer player, float yaw, float pitch) {
        if (!this.isController(player)) {
            return false;
        }
        float controlledYaw = clampAimYaw(this.getBaseYaw(), yaw);
        this.setYRot(Mth.wrapDegrees(controlledYaw));
        this.setXRot(Mth.clamp(pitch, -MAX_PITCH, MAX_PITCH));
        this.setYHeadRot(this.getYRot());
        this.setYBodyRot(this.getBaseYaw());
        return true;
    }

    /** Fires one cadence-limited shot from the controlled turret and provokes the aimed enemy. */
    public boolean fireControlled(ServerPlayer player, float yaw, float pitch) {
        if (!(this.level() instanceof ServerLevel level)
                || !this.updateRemoteAim(player, yaw, pitch)
                || this.isDeactivated() || this.isDeadOrDying()) {
            return false;
        }
        long now = level.getGameTime();
        if (now < this.nextControlledShotTick) {
            return false;
        }
        this.nextControlledShotTick = now + FIRE_INTERVAL_TICKS;

        Vec3 direction = aimDirection(this.getYRot(), this.getXRot());
        Vec3 muzzle = this.position().add(muzzleOffset(direction));
        LivingEntity aimedTarget = this.findControlledTarget(level, muzzle, direction);
        if (aimedTarget != null) {
            this.registerRemoteHostile(level, aimedTarget);
        }
        GunFiring.fire(level, this, WEAPON_PROFILE, muzzle, direction);
        return true;
    }

    private boolean isController(@Nullable ServerPlayer player) {
        return player != null
                && this.remoteControllerId != null
                && this.remoteControllerId.equals(player.getUUID())
                && player.level() == this.level()
                && player.isAlive()
                && !player.isSpectator()
                && this.hasSufficientCyberdeck(player);
    }

    private @Nullable ServerPlayer resolveRemoteController(ServerLevel level) {
        if (this.remoteControllerId == null) {
            this.entityData.set(DATA_REMOTE_CONTROLLED, false);
            return null;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(this.remoteControllerId);
        if (!this.isController(player)) {
            this.endRemoteControlInternal();
            return null;
        }
        return player;
    }

    private void endRemoteControlInternal() {
        this.remoteControllerId = null;
        this.entityData.set(DATA_REMOTE_CONTROLLED, false);
        // Camera switching is client-only. ServerPlayer#setCamera teleports the player's body to
        // the viewed entity, so the networking layer must use Minecraft#setCameraEntity instead.
    }

    private @Nullable LivingEntity findControlledTarget(
            ServerLevel level, Vec3 muzzle, Vec3 direction) {
        Vec3 end = muzzle.add(direction.scale(WEAPON_PROFILE.range()));
        BlockHitResult blockHit = level.clip(new ClipContext(
                muzzle, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
        Vec3 rayEnd = blockHit.getType() == HitResult.Type.MISS
                ? end : blockHit.getLocation();
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                this,
                muzzle,
                rayEnd,
                new AABB(muzzle, rayEnd).inflate(1.0),
                entity -> entity instanceof Enemy && GunFiring.canHitTarget(this, entity),
                muzzle.distanceToSqr(rayEnd));
        return entityHit != null && entityHit.getEntity() instanceof LivingEntity living
                ? living : null;
    }

    private void registerRemoteHostile(ServerLevel level, LivingEntity target) {
        if (!(target instanceof Mob mob) || !mob.canAttack(this)) {
            return;
        }
        if (!this.remoteHostileIds.contains(target.getUUID())
                && this.remoteHostileIds.size() >= MAX_PERSISTED_REMOTE_HOSTILES) {
            this.remoteHostileIds.remove(this.remoteHostileIds.iterator().next());
        }
        this.remoteHostileIds.add(target.getUUID());
        this.forceHostile(level, mob);
    }

    private void maintainRemoteHostility(ServerLevel level) {
        this.remoteHostileIds.removeIf(id -> {
            Entity target = level.getEntityInAnyDimension(id);
            if (target == null) {
                // Keep unresolved IDs across chunk unloads so persisted enemies resume retaliation
                // after both entities load again. The bounded set prevents stale IDs accumulating.
                return false;
            }
            if (!(target instanceof Mob mob) || !mob.isAlive()) {
                return true;
            }
            if (mob.level() == this.level() && mob.canAttack(this)) {
                this.forceHostile(level, mob);
            }
            return false;
        });
    }

    private void forceHostile(ServerLevel level, Mob mob) {
        if (mob instanceof FactionEnemy enemy && !enemy.isTriggered()) {
            enemy.trigger(level, this);
        }
        mob.setTarget(this);
        mob.setAggressive(true);
    }

    @Override
    public void die(DamageSource source) {
        boolean firstDestruction = !this.isDestroyed();
        this.endRemoteControlInternal();
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
    public void remove(Entity.RemovalReason reason) {
        this.endRemoteControlInternal();
        super.remove(reason);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putFloat("BaseYaw", this.getBaseYaw());
        output.putBoolean("Destroyed", this.isDestroyed());
        output.putInt("SecurityLevel", this.getSecurityLevel());
        output.putBoolean("Deactivated", this.isDeactivated());
        output.putLong("NextShotTick", this.nextShotTick);
        output.putLong("BurstEndTick", this.burstEndTick);
        output.putLong("ReloadEndTick", this.reloadEndTick);
        output.putLong("NextControlledShotTick", this.nextControlledShotTick);
        output.putInt("BurstSequence", this.burstSequence);
        output.putBoolean("ScanIncreasing", this.scanIncreasing);
        output.store("RemoteHostiles", UUIDUtil.CODEC_SET, this.remoteHostileIds);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_BASE_YAW, input.getFloatOr("BaseYaw", this.getYRot()));
        this.entityData.set(DATA_DESTROYED, input.getBooleanOr("Destroyed", false));
        this.setSecurityLevel(input.getIntOr("SecurityLevel", MIN_SECURITY_LEVEL));
        this.entityData.set(DATA_DEACTIVATED, input.getBooleanOr("Deactivated", false));
        this.entityData.set(DATA_REMOTE_CONTROLLED, false);
        this.remoteControllerId = null;
        this.nextShotTick = input.getLongOr("NextShotTick", 0L);
        this.burstEndTick = input.getLongOr("BurstEndTick", 0L);
        this.reloadEndTick = input.getLongOr("ReloadEndTick", 0L);
        this.nextControlledShotTick = input.getLongOr("NextControlledShotTick", 0L);
        this.burstSequence = input.getIntOr("BurstSequence", 0);
        this.scanIncreasing = input.getBooleanOr("ScanIncreasing", true);
        this.remoteHostileIds.clear();
        this.remoteHostileIds.addAll(input.read("RemoteHostiles", UUIDUtil.CODEC_SET)
                .orElse(Set.of()));
    }
}
