package com.example.cyberdeck.faction;

import com.example.cyberdeck.weapon.GunFiring;
import com.example.cyberdeck.weapon.GunItem;
import com.example.cyberdeck.npc.CityNpc;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A corporation soldier. It is its own hostile entity (a {@link Monster}) — not a zombie — so it
 * never burns in sunlight, ignores villagers, has no baby form and makes generic hostile sounds
 * rather than zombie groans. A faction enemy stays passive until the player lingers nearby long
 * enough to build up "detection"; when detection crosses {@link #DETECTION_THRESHOLD} it turns
 * hostile and alerts every allied faction enemy within {@link #ALERT_RADIUS} so a whole squad
 * activates together.
 *
 * <p>It fights with whatever weapon it holds: a primary gun (hitscan via {@link GunFiring}), an
     * stowed secondary sidearm, a melee sword, and — if issued grenades — it lobs {@link
 * com.example.cyberdeck.weapon.ThrownGrenade}s at the player. Kang Tao squads additionally call in
 * an airborne reinforcement drop the first time three or more members are triggered at once (see
 * {@link FactionSquads}).
 */
public final class FactionEnemy extends Monster implements RangedAttackMob {
    private static final EntityDataAccessor<Integer> DATA_FACTION =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_TRIGGERED =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Long> DATA_GUN_RELOAD_START_TICK =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_GUN_RELOAD_END_TICK =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_LAST_GUN_SHOT_TICK =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Integer> DATA_WEAPON_GLITCH_PHASE =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_WEAPON_GLITCH_START_TICK =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_WEAPON_GLITCH_END_TICK =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Boolean> DATA_WEAPON_GLITCH_SWITCH_PENDING =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_USING_SECONDARY =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_TACTICAL_MANEUVER =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> DATA_TACTICAL_MANEUVER_START_TICK =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Long> DATA_TACTICAL_MANEUVER_END_TICK =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<Float> DATA_TACTICAL_DIRECTION_X =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_TACTICAL_DIRECTION_Z =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.FLOAT);

    private static final int WEAPON_GLITCH_NONE = 0;
    private static final int WEAPON_GLITCH_FIDDLING = 1;
    private static final int WEAPON_GLITCH_DRAWING_SECONDARY = 2;

    private static final int DASH_TICKS = 5;
    private static final int SLIDE_TICKS = 10;
    private static final double DASH_SPEED = 0.66;
    private static final double SLIDE_SPEED = 0.48;
    private static final double MAX_TACTICAL_HORIZONTAL_SPEED = 0.68;
    private static final double EXIT_HORIZONTAL_SPEED_CAP = 0.28;
    private static final double MIN_DIRECTION_LENGTH_SQR = 1.0E-5;

    /** Initial interruption before a glitched primary is abandoned. */
    public static final int PRIMARY_MALFUNCTION_TICKS = 24;
    /** Time spent drawing/readying the sidearm after the actual equipment swap. */
    public static final int SECONDARY_DRAW_TICKS = 18;
    /** A sidearm (or a primary with no backup) recovers in place after this interruption. */
    public static final int WEAPON_RECOVERY_TICKS = 50;

    /** Detection points needed to become hostile. Gained ~1/tick while the player is close + visible. */
    private static final int DETECTION_THRESHOLD = 60; // ~3 seconds of exposure
    /** How far a soldier can notice the player. Wider than before so they engage from range. */
    private static final double DETECTION_RANGE = 24.0;
    private static final double ALERT_RADIUS = 20.0;
    /** Radius (blocks) a soldier patrols around its spawn point when idle. */
    private static final double PATROL_RADIUS = 12.0;

    private int detection;
    /** The point this soldier patrols around; set on spawn. Null falls back to the current position. */
    private net.minecraft.core.BlockPos homePos;
    /** Grenades remaining to throw; 0 means this soldier was not issued any. */
    private int grenadeCount;
    /** Which grenade variant this soldier lobs. */
    private com.example.cyberdeck.weapon.GrenadeType grenadeType =
            com.example.cyberdeck.weapon.GrenadeType.INCENDIARY;

    public FactionEnemy(EntityType<? extends FactionEnemy> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.MOVEMENT_SPEED, 0.26)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_FACTION, Faction.ARASAKA.ordinal());
        entityData.define(DATA_TRIGGERED, false);
        entityData.define(DATA_GUN_RELOAD_START_TICK, -1L);
        entityData.define(DATA_GUN_RELOAD_END_TICK, -1L);
        entityData.define(DATA_LAST_GUN_SHOT_TICK, -1L);
        entityData.define(DATA_WEAPON_GLITCH_PHASE, WEAPON_GLITCH_NONE);
        entityData.define(DATA_WEAPON_GLITCH_START_TICK, -1L);
        entityData.define(DATA_WEAPON_GLITCH_END_TICK, -1L);
        entityData.define(DATA_WEAPON_GLITCH_SWITCH_PENDING, false);
        entityData.define(DATA_USING_SECONDARY, false);
        entityData.define(DATA_TACTICAL_MANEUVER, TacticalManeuver.NONE.id());
        entityData.define(DATA_TACTICAL_MANEUVER_START_TICK, -1L);
        entityData.define(DATA_TACTICAL_MANEUVER_END_TICK, -1L);
        entityData.define(DATA_TACTICAL_DIRECTION_X, 0.0F);
        entityData.define(DATA_TACTICAL_DIRECTION_Z, 0.0F);
    }

    @Override
    protected void registerGoals() {
        // Faction enemies have no default player-targeting goal, so they stay passive until
        // detection triggers them (see accumulateDetection / trigger).
        // Grenade lob takes priority over shooting when a grenade is carried and in range.
        this.goalSelector.addGoal(0, new WeaponMalfunctionGoal(this));
        this.goalSelector.addGoal(1, new ThrowGrenadeGoal(this));
        // No MOVE/LOOK flags: tactical impulses can happen while RangedAttackGoal keeps aiming and
        // firing. The entity owns validation and physics so reload/glitch can cancel immediately.
        this.goalSelector.addGoal(2, new TacticalManeuverGoal(this));
        this.goalSelector.addGoal(2, new RangedAttackGoal(this, 1.0, 20, 15.0f));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0, false));
        // Idle behavior: patrol a fixed area around the spawn point instead of roaming freely.
        this.goalSelector.addGoal(6, new PatrolAreaGoal(this, 0.8, this::getHome, PATROL_RADIUS));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 12.0f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // Retaliate if attacked even before detection completes.
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
    }

    public Faction getFaction() {
        return Faction.VALUES[this.getEntityData().get(DATA_FACTION)];
    }

    public void setFaction(Faction faction) {
        this.getEntityData().set(DATA_FACTION, faction.ordinal());
    }

    /** The point this soldier patrols around. Falls back to its current position if unset. */
    public net.minecraft.core.BlockPos getHome() {
        return homePos != null ? homePos : this.blockPosition();
    }

    /** Sets the patrol anchor; called on spawn so the squad guards where it appeared. */
    public void setHome(net.minecraft.core.BlockPos pos) {
        this.homePos = pos;
    }

    public boolean isTriggered() {
        return this.getEntityData().get(DATA_TRIGGERED);
    }

    private void setTriggered(boolean value) {
        this.getEntityData().set(DATA_TRIGGERED, value);
    }

    /** Number of grenades this soldier can still throw; 0 means it carries none. */
    public int getGrenadeCount() {
        return grenadeCount;
    }

    public void setGrenadeCount(int count) {
        this.grenadeCount = Math.max(0, count);
    }

    /** The grenade type this soldier throws (only meaningful when {@link #getGrenadeCount()} > 0). */
    public com.example.cyberdeck.weapon.GrenadeType getGrenadeType() {
        return grenadeType;
    }

    public void setGrenadeType(com.example.cyberdeck.weapon.GrenadeType type) {
        this.grenadeType = type;
    }

    /**
     * Lobs one grenade at {@code target} along a light arc, consuming it from this soldier's stock.
     * No-op if it has no grenades left or isn't on the server.
     */
    public void throwGrenadeAt(LivingEntity target) {
        if (target instanceof CityNpc || isWeaponGlitching() || grenadeCount <= 0
                || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        var grenadeItem = grenadeType == com.example.cyberdeck.weapon.GrenadeType.POISON
                ? com.example.cyberdeck.weapon.WeaponItems.POISON_GRENADE.get()
                : com.example.cyberdeck.weapon.WeaponItems.INCENDIARY_GRENADE.get();
        var stack = new net.minecraft.world.item.ItemStack(grenadeItem);
        var grenade = new com.example.cyberdeck.weapon.ThrownGrenade(level, this, stack);

        // Aim at the target's chest with an upward arc so the throw carries over cover.
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.5) - grenade.getY();
        double dz = target.getZ() - this.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        grenade.shoot(dx, dy + horizontal * 0.2, dz, 0.9f, 6.0f);

        level.playSound(null, this, SoundEvents.SNOWBALL_THROW, SoundSource.HOSTILE, 0.8f, 0.9f);
        level.addFreshEntity(grenade);
        grenadeCount--;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        tickWeaponGlitch(level);
        if (!isWeaponGlitching()) {
            tickGunReload(level);
        }
        tickTacticalManeuver(level);
        if (!isTriggered()) {
            accumulateDetection(level);
        }
    }

    /** True while this soldier's main-hand gun is waiting for its magazine reload to finish. */
    public boolean isGunReloading() {
        long start = getGunReloadStartTick();
        long end = getGunReloadEndTick();
        return start >= 0L && end > start && this.level().getGameTime() < end;
    }

    public long getGunReloadStartTick() {
        return this.getEntityData().get(DATA_GUN_RELOAD_START_TICK);
    }

    public long getGunReloadEndTick() {
        return this.getEntityData().get(DATA_GUN_RELOAD_END_TICK);
    }

    public long getLastGunShotTick() {
        return this.getEntityData().get(DATA_LAST_GUN_SHOT_TICK);
    }

    /** Current synchronized maneuver, used by both server physics and client animation. */
    public TacticalManeuver getTacticalManeuver() {
        return TacticalManeuver.byId(getTacticalManeuverId());
    }

    public int getTacticalManeuverId() {
        return this.getEntityData().get(DATA_TACTICAL_MANEUVER);
    }

    public long getTacticalManeuverStartTick() {
        return this.getEntityData().get(DATA_TACTICAL_MANEUVER_START_TICK);
    }

    public long getTacticalManeuverEndTick() {
        return this.getEntityData().get(DATA_TACTICAL_MANEUVER_END_TICK);
    }

    public float getTacticalDirectionX() {
        return this.getEntityData().get(DATA_TACTICAL_DIRECTION_X);
    }

    public float getTacticalDirectionZ() {
        return this.getEntityData().get(DATA_TACTICAL_DIRECTION_Z);
    }

    public boolean isTacticalManeuvering() {
        TacticalManeuver maneuver = getTacticalManeuver();
        return maneuver != TacticalManeuver.NONE
                && getTacticalManeuverStartTick() >= 0L
                && this.level().getGameTime() < getTacticalManeuverEndTick();
    }

    /**
     * Begins a short maneuver if the target, surface and projected first step are all safe. The
     * direction is derived server-side from the target rather than trusted from a client.
     */
    public boolean tryStartTacticalManeuver(TacticalManeuver maneuver, LivingEntity target) {
        if (!(this.level() instanceof ServerLevel level)
                || maneuver == TacticalManeuver.NONE
                || isTacticalManeuvering()
                || !canManeuverAgainst(target)
                || !this.hasLineOfSight(target)) {
            return false;
        }

        Vec3 towardTarget = new Vec3(
                target.getX() - this.getX(), 0.0, target.getZ() - this.getZ());
        if (towardTarget.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
            return false;
        }
        Vec3 forward = towardTarget.normalize();
        Vec3 direction = switch (maneuver) {
            case DASH_LEFT -> new Vec3(forward.z, 0.0, -forward.x);
            case DASH_RIGHT -> new Vec3(-forward.z, 0.0, forward.x);
            case SLIDE_FORWARD -> forward;
            case NONE -> Vec3.ZERO;
        };
        double speed = maneuver.isDash() ? DASH_SPEED : SLIDE_SPEED;
        if (!canTravel(level, direction, speed)) {
            return false;
        }

        long now = level.getGameTime();
        this.getEntityData().set(DATA_TACTICAL_MANEUVER, maneuver.id());
        this.getEntityData().set(DATA_TACTICAL_MANEUVER_START_TICK, now);
        this.getEntityData().set(DATA_TACTICAL_MANEUVER_END_TICK,
                now + (maneuver.isDash() ? DASH_TICKS : SLIDE_TICKS));
        this.getEntityData().set(DATA_TACTICAL_DIRECTION_X, (float) direction.x);
        this.getEntityData().set(DATA_TACTICAL_DIRECTION_Z, (float) direction.z);
        this.getNavigation().stop();
        applyTacticalVelocity(direction, speed);
        return true;
    }

    /** Cancels a maneuver and keeps only modest residual momentum. Safe to call repeatedly. */
    public void endTacticalManeuver() {
        if (!(this.level() instanceof ServerLevel)) {
            return;
        }
        if (getTacticalManeuver() == TacticalManeuver.NONE) {
            clearTacticalManeuverData();
            return;
        }
        Vec3 movement = this.getDeltaMovement();
        double horizontal = movement.horizontalDistance();
        if (horizontal > EXIT_HORIZONTAL_SPEED_CAP) {
            double scale = EXIT_HORIZONTAL_SPEED_CAP / horizontal;
            this.setDeltaMovement(movement.x * scale, movement.y, movement.z * scale);
            this.hurtMarked = true;
        }
        clearTacticalManeuverData();
    }

    private boolean canManeuverAgainst(LivingEntity target) {
        return this.isAlive()
                && this.isTriggered()
                && target != null
                && target.isAlive()
                && !(target instanceof CityNpc)
                && this.canAttack(target)
                && this.getMainHandItem().getItem() instanceof GunItem
                && !this.isWeaponGlitching()
                && !this.isGunReloading()
                && this.onGround()
                && !this.horizontalCollision
                && !this.isPassenger()
                && !this.isInWater()
                && !this.isInLava();
    }

    private void tickTacticalManeuver(ServerLevel level) {
        TacticalManeuver maneuver = getTacticalManeuver();
        if (maneuver == TacticalManeuver.NONE) {
            return;
        }
        LivingEntity target = this.getTarget();
        long now = level.getGameTime();
        if (now >= getTacticalManeuverEndTick()
                || !canManeuverAgainst(target)) {
            endTacticalManeuver();
            return;
        }

        Vec3 direction = new Vec3(
                getTacticalDirectionX(), 0.0, getTacticalDirectionZ());
        if (direction.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
            endTacticalManeuver();
            return;
        }
        direction = direction.normalize();

        double duration = Math.max(1.0,
                getTacticalManeuverEndTick() - getTacticalManeuverStartTick());
        double progress = Math.max(0.0,
                Math.min(1.0, (now - getTacticalManeuverStartTick()) / duration));
        double speed = maneuver.isDash()
                ? DASH_SPEED * (1.0 - 0.22 * progress)
                : SLIDE_SPEED * (1.0 - 0.55 * progress);
        if (!canTravel(level, direction, speed)) {
            endTacticalManeuver();
            return;
        }

        // Navigation resumes naturally after the short action; no goal flag is held, so shooting
        // and look control continue throughout the maneuver.
        this.getNavigation().stop();
        applyTacticalVelocity(direction, speed);
    }

    private boolean canTravel(ServerLevel level, Vec3 direction, double speed) {
        double cappedSpeed = Math.min(speed, MAX_TACTICAL_HORIZONTAL_SPEED);
        Vec3 step = direction.scale(cappedSpeed);
        // Check the swept volume, not only the destination, so a fast dash cannot tunnel through
        // panes, fences or another entity between its current and projected boxes.
        if (!level.noCollision(this, this.getBoundingBox().expandTowards(step))) {
            return false;
        }

        // Do not dash blindly off roofs or into an unloaded column. Checking directly below the
        // projected feet works for full blocks, slabs and stairs because all block movement shapes
        // report motion-blocking here.
        Vec3 next = this.position().add(step);
        BlockPos support = BlockPos.containing(
                next.x, this.getBoundingBox().minY - 0.12, next.z);
        return level.isLoaded(support) && level.getBlockState(support).blocksMotion();
    }

    private void applyTacticalVelocity(Vec3 direction, double requestedSpeed) {
        double speed = Math.min(requestedSpeed, MAX_TACTICAL_HORIZONTAL_SPEED);
        Vec3 movement = this.getDeltaMovement();
        this.setDeltaMovement(direction.x * speed, movement.y, direction.z * speed);
        this.hurtMarked = true;
    }

    private void clearTacticalManeuverData() {
        this.getEntityData().set(DATA_TACTICAL_MANEUVER, TacticalManeuver.NONE.id());
        this.getEntityData().set(DATA_TACTICAL_MANEUVER_START_TICK, -1L);
        this.getEntityData().set(DATA_TACTICAL_MANEUVER_END_TICK, -1L);
        this.getEntityData().set(DATA_TACTICAL_DIRECTION_X, 0.0F);
        this.getEntityData().set(DATA_TACTICAL_DIRECTION_Z, 0.0F);
    }

    /** Starts (or restarts) a visible, combat-blocking Weapon Glitch on the held weapon. */
    public void beginWeaponGlitch(ServerLevel level) {
        endTacticalManeuver();
        clearGunReload();

        boolean canSwitchToSecondary = !isUsingSecondary()
                && this.getMainHandItem().getItem() instanceof GunItem
                && this.getOffhandItem().getItem() instanceof GunItem;
        long now = level.getGameTime();
        int duration = canSwitchToSecondary
                ? PRIMARY_MALFUNCTION_TICKS
                : WEAPON_RECOVERY_TICKS;

        setWeaponGlitchPhase(WEAPON_GLITCH_FIDDLING, now, now + duration);
        this.getEntityData().set(DATA_WEAPON_GLITCH_SWITCH_PENDING, canSwitchToSecondary);
        this.getNavigation().stop();
        this.setAggressive(false);
        this.swing(InteractionHand.MAIN_HAND, true);
        playMalfunctionFeedback(level, true);
    }

    /** True for the entire malfunction + sidearm-draw window. Combat must remain blocked. */
    public boolean isWeaponGlitching() {
        return this.getEntityData().get(DATA_WEAPON_GLITCH_PHASE) != WEAPON_GLITCH_NONE;
    }

    /** True while the soldier is repeatedly fiddling with the malfunctioning held weapon. */
    public boolean isWeaponMalfunctioning() {
        return this.getEntityData().get(DATA_WEAPON_GLITCH_PHASE) == WEAPON_GLITCH_FIDDLING;
    }

    /** True after the equipment swap while the replacement sidearm is being readied. */
    public boolean isDrawingSecondary() {
        return this.getEntityData().get(DATA_WEAPON_GLITCH_PHASE)
                == WEAPON_GLITCH_DRAWING_SECONDARY;
    }

    public boolean isUsingSecondary() {
        return this.getEntityData().get(DATA_USING_SECONDARY);
    }

    public long getWeaponGlitchStartTick() {
        return this.getEntityData().get(DATA_WEAPON_GLITCH_START_TICK);
    }

    public long getWeaponGlitchEndTick() {
        return this.getEntityData().get(DATA_WEAPON_GLITCH_END_TICK);
    }

    private void tickWeaponGlitch(ServerLevel level) {
        if (!isWeaponGlitching()) {
            return;
        }

        // The malfunction goal owns MOVE/LOOK, and these guards make the interruption robust even
        // on the transition tick before goal arbitration runs.
        this.getNavigation().stop();
        this.setAggressive(false);
        clearGunReload();

        long now = level.getGameTime();
        if (isWeaponMalfunctioning() && now % 8L == 0L) {
            this.swing(InteractionHand.MAIN_HAND, true);
            playMalfunctionFeedback(level, false);
        }
        if (now < getWeaponGlitchEndTick()) {
            return;
        }

        if (isWeaponMalfunctioning()
                && this.getEntityData().get(DATA_WEAPON_GLITCH_SWITCH_PENDING)
                && this.getOffhandItem().getItem() instanceof GunItem) {
            swapToSecondary(level, now);
            return;
        }

        finishWeaponGlitch();
    }

    private void swapToSecondary(ServerLevel level, long now) {
        ItemStack primary = this.getMainHandItem();
        ItemStack secondary = this.getOffhandItem();
        this.setItemSlot(EquipmentSlot.MAINHAND, secondary);
        this.setItemSlot(EquipmentSlot.OFFHAND, primary);
        this.getEntityData().set(DATA_USING_SECONDARY, true);
        this.getEntityData().set(DATA_WEAPON_GLITCH_SWITCH_PENDING, false);
        setWeaponGlitchPhase(
                WEAPON_GLITCH_DRAWING_SECONDARY,
                now,
                now + SECONDARY_DRAW_TICKS);
        this.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.PISTON_CONTRACT, SoundSource.HOSTILE, 0.8f, 1.35f);
    }

    private void finishWeaponGlitch() {
        this.getEntityData().set(DATA_WEAPON_GLITCH_PHASE, WEAPON_GLITCH_NONE);
        this.getEntityData().set(DATA_WEAPON_GLITCH_START_TICK, -1L);
        this.getEntityData().set(DATA_WEAPON_GLITCH_END_TICK, -1L);
        this.getEntityData().set(DATA_WEAPON_GLITCH_SWITCH_PENDING, false);
        this.setAggressive(this.getTarget() != null);
    }

    private void setWeaponGlitchPhase(int phase, long startTick, long endTick) {
        this.getEntityData().set(DATA_WEAPON_GLITCH_PHASE, phase);
        this.getEntityData().set(DATA_WEAPON_GLITCH_START_TICK, startTick);
        this.getEntityData().set(DATA_WEAPON_GLITCH_END_TICK, endTick);
    }

    private void playMalfunctionFeedback(ServerLevel level, boolean initial) {
        if (initial) {
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.DISPENSER_FAIL, SoundSource.HOSTILE, 0.9f, 1.55f);
        }
        var hand = this.position()
                .add(0.0, this.getBbHeight() * 0.68, 0.0)
                .add(this.getLookAngle().scale(0.35));
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                hand.x, hand.y, hand.z,
                initial ? 9 : 2, 0.16, 0.12, 0.16, 0.025);
    }

    private void startGunReload(ServerLevel level, GunItem gunItem) {
        if (isGunReloading()) {
            return;
        }
        endTacticalManeuver();
        long start = level.getGameTime();
        this.getEntityData().set(DATA_GUN_RELOAD_START_TICK, start);
        this.getEntityData().set(DATA_GUN_RELOAD_END_TICK,
                start + gunItem.gun().reloadTimeTicks());
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.PISTON_CONTRACT, SoundSource.HOSTILE, 0.7f, 1.0f);
    }

    private void clearGunReload() {
        this.getEntityData().set(DATA_GUN_RELOAD_START_TICK, -1L);
        this.getEntityData().set(DATA_GUN_RELOAD_END_TICK, -1L);
    }

    private void tickGunReload(ServerLevel level) {
        long end = getGunReloadEndTick();
        if (end < 0L) {
            return;
        }
        if (!(this.getMainHandItem().getItem() instanceof GunItem gunItem)) {
            clearGunReload();
            return;
        }
        if (level.getGameTime() < end) {
            return;
        }
        gunItem.setMagazine(this.getMainHandItem(), gunItem.gun().magazineSize());
        clearGunReload();
        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.PISTON_EXTEND, SoundSource.HOSTILE, 0.7f, 1.2f);
    }

    private void accumulateDetection(ServerLevel level) {
        Player nearest = level.getNearestPlayer(this, DETECTION_RANGE);
        boolean exposed = nearest != null
                && !nearest.isCreative()
                && !nearest.isSpectator()
                && this.hasLineOfSight(nearest);

        if (exposed) {
            // Detect faster the closer the player is.
            double dist = this.distanceTo(nearest);
            int gain = dist < 4.0 ? 3 : (dist < 7.0 ? 2 : 1);
            detection += gain;
            // Small warning cue as detection climbs.
            if (detection == DETECTION_THRESHOLD / 2) {
                level.playSound(null, this, SoundEvents.VILLAGER_NO, SoundSource.HOSTILE, 0.6f, 1.4f);
            }
            if (detection >= DETECTION_THRESHOLD) {
                trigger(level, nearest);
            }
        } else if (detection > 0) {
            // Slowly forget when the player leaves.
            detection = Math.max(0, detection - 1);
        }
    }

    /** Becomes hostile toward {@code target} and alerts nearby allies of the same faction. */
    public void trigger(ServerLevel level, LivingEntity target) {
        if (target instanceof CityNpc || !canAttack(target) || isTriggered()) {
            return;
        }
        setTriggered(true);
        this.setTarget(target);
        level.playSound(null, this, SoundEvents.PILLAGER_CELEBRATE, SoundSource.HOSTILE, 1.0f, 0.9f);

        Faction faction = getFaction();
        List<FactionEnemy> allies = level.getEntitiesOfClass(FactionEnemy.class,
                new AABB(this.blockPosition()).inflate(ALERT_RADIUS),
                e -> e != this && e.isAlive() && e.getFaction() == faction && !e.isTriggered());

        int simultaneous = 1;
        for (FactionEnemy ally : allies) {
            ally.setTriggered(true);
            ally.setTarget(target);
            simultaneous++;
        }

        // Kang Tao squads call in an airborne reinforcement drop the first time 3+ trigger at once.
        if (faction == Faction.KANG_TAO && simultaneous >= 3) {
            FactionSquads.tryKangTaoReinforcement(level, this, target, simultaneous);
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        if (target instanceof CityNpc || !canAttack(target)
                || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        if (isWeaponGlitching()) {
            return;
        }
        if (this.getMainHandItem().getItem() instanceof GunItem gunItem) {
            if (isGunReloading()) {
                return;
            }
            int rounds = gunItem.magazine(this.getMainHandItem());
            if (rounds <= 0) {
                startGunReload(level, gunItem);
                return;
            }
            // Face the target, then fire the held gun as hitscan.
            this.getLookControl().setLookAt(target, 30.0f, 30.0f);
            GunFiring.fire(level, this, gunItem.gun());
            this.getEntityData().set(DATA_LAST_GUN_SHOT_TICK, level.getGameTime());
            int remaining = rounds - 1;
            gunItem.setMagazine(this.getMainHandItem(), remaining);
            if (remaining <= 0) {
                startGunReload(level, gunItem);
            }
        }
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return !(target instanceof CityNpc)
                && !isWeaponGlitching()
                && super.doHurtTarget(level, target);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof CityNpc) && super.canAttack(target);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("Faction", getFaction().id());
        output.putInt("Detection", detection);
        output.putBoolean("Triggered", isTriggered());
        output.putInt("Grenades", grenadeCount);
        output.putLong("GunReloadStartTick", getGunReloadStartTick());
        output.putLong("GunReloadEndTick", getGunReloadEndTick());
        output.putInt("WeaponGlitchPhase",
                this.getEntityData().get(DATA_WEAPON_GLITCH_PHASE));
        output.putLong("WeaponGlitchStartTick", getWeaponGlitchStartTick());
        output.putLong("WeaponGlitchEndTick", getWeaponGlitchEndTick());
        output.putBoolean("WeaponGlitchSwitchPending",
                this.getEntityData().get(DATA_WEAPON_GLITCH_SWITCH_PENDING));
        output.putBoolean("UsingSecondary", isUsingSecondary());
        output.putString("GrenadeType",
                grenadeType == com.example.cyberdeck.weapon.GrenadeType.POISON ? "poison" : "incendiary");
        if (homePos != null) {
            output.putInt("HomeX", homePos.getX());
            output.putInt("HomeY", homePos.getY());
            output.putInt("HomeZ", homePos.getZ());
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        String factionId = input.getStringOr("Faction", Faction.ARASAKA.id());
        for (Faction f : Faction.VALUES) {
            if (f.id().equals(factionId)) {
                setFaction(f);
                break;
            }
        }
        detection = input.getIntOr("Detection", 0);
        setTriggered(input.getBooleanOr("Triggered", false));
        grenadeCount = input.getIntOr("Grenades", 0);
        this.getEntityData().set(DATA_GUN_RELOAD_START_TICK,
                input.getLongOr("GunReloadStartTick", -1L));
        this.getEntityData().set(DATA_GUN_RELOAD_END_TICK,
                input.getLongOr("GunReloadEndTick", -1L));
        int glitchPhase = input.getIntOr("WeaponGlitchPhase", WEAPON_GLITCH_NONE);
        if (glitchPhase < WEAPON_GLITCH_NONE
                || glitchPhase > WEAPON_GLITCH_DRAWING_SECONDARY) {
            glitchPhase = WEAPON_GLITCH_NONE;
        }
        this.getEntityData().set(DATA_WEAPON_GLITCH_PHASE, glitchPhase);
        this.getEntityData().set(DATA_WEAPON_GLITCH_START_TICK,
                input.getLongOr("WeaponGlitchStartTick", -1L));
        this.getEntityData().set(DATA_WEAPON_GLITCH_END_TICK,
                input.getLongOr("WeaponGlitchEndTick", -1L));
        this.getEntityData().set(DATA_WEAPON_GLITCH_SWITCH_PENDING,
                input.getBooleanOr("WeaponGlitchSwitchPending", false));
        this.getEntityData().set(DATA_USING_SECONDARY,
                input.getBooleanOr("UsingSecondary", false));
        // Maneuvers are brief presentation/combat impulses, not durable entity state. An entity
        // reloaded from disk always resumes normal AI and receives a fresh deterministic cooldown.
        clearTacticalManeuverData();
        grenadeType = "poison".equals(input.getStringOr("GrenadeType", "incendiary"))
                ? com.example.cyberdeck.weapon.GrenadeType.POISON
                : com.example.cyberdeck.weapon.GrenadeType.INCENDIARY;
        if (input.getInt("HomeX").isPresent()) {
            homePos = new net.minecraft.core.BlockPos(
                    input.getIntOr("HomeX", 0),
                    input.getIntOr("HomeY", 0),
                    input.getIntOr("HomeZ", 0));
        }
    }
}
