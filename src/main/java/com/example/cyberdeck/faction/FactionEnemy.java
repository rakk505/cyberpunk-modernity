package com.example.cyberdeck.faction;

import com.example.cyberdeck.weapon.GunFiring;
import com.example.cyberdeck.weapon.GunItem;
import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.npc.CityNpc;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.NeonCityGenerator;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
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
import java.util.UUID;

/**
 * A corporation soldier. It is its own hostile entity (a {@link Monster}) — not a zombie — so it
 * never burns in sunlight, ignores villagers, has no baby form and makes generic hostile sounds
 * rather than zombie groans. A faction enemy stays passive until the player lingers nearby long
 * enough to build up "detection"; when detection crosses {@link #DETECTION_THRESHOLD} it turns
 * hostile and alerts the nearby members of its deployment group so a whole squad activates
 * together.
 *
 * <p>It fights with whatever weapon it holds: a primary gun (hitscan via {@link GunFiring}), an
     * stowed secondary sidearm, a melee sword, and — if issued grenades — it lobs {@link
 * com.example.cyberdeck.weapon.ThrownGrenade}s at the player. The first player attack against an
 * ordinary corporate squad also resolves one persisted 30% airborne reinforcement roll (see
 * {@link FactionSquads}).
 */
public class FactionEnemy extends Monster implements RangedAttackMob {
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
    /** Current detection level, synced so the client HUD can render a detection meter. */
    private static final EntityDataAccessor<Integer> DATA_DETECTION =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_TRAUMA_TEAM =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_EXCISION =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_AMBIENT_PATROL =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_DISTRICT =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_SKIN =
            SynchedEntityData.defineId(FactionEnemy.class, EntityDataSerializers.INT);

    public static final int TACTICAL_SKIN_COUNT = 8;
    public static final double MISSION_DETECTION_RANGE = 24.0;
    public static final double AMBIENT_DETECTION_RANGE = 14.0;
    private static final double MISSION_ALERT_RADIUS = 20.0;
    private static final double AMBIENT_ALERT_RADIUS = 10.0;
    private static final Identifier BALLISTIC_ARMOR_MODIFIER =
            Identifier.fromNamespaceAndPath("cyberdeck", "ballistic_armor");
    private static final Identifier BALLISTIC_TOUGHNESS_MODIFIER =
            Identifier.fromNamespaceAndPath("cyberdeck", "ballistic_toughness");
    private static final Identifier BALLISTIC_KNOCKBACK_MODIFIER =
            Identifier.fromNamespaceAndPath("cyberdeck", "ballistic_knockback_resistance");
    private static final String LEGACY_MISSION_INSTANCE_TAG = "cyberdeck_mission_instance";

    private static final int WEAPON_GLITCH_NONE = 0;
    private static final int WEAPON_GLITCH_FIDDLING = 1;
    private static final int WEAPON_GLITCH_DRAWING_SECONDARY = 2;

    private static final int DASH_TICKS = 5;
    private static final int SLIDE_TICKS = 10;
    private static final double DASH_SPEED = 0.66;
    private static final double SLIDE_SPEED = 0.48;
    /**
     * Sandevistan near-teleport dash: much shorter and faster than a normal dash so it reads as a
     * blurred blink toward the target, but still a real swept movement (not an instant relocation).
     */
    private static final int SANDEVISTAN_DASH_TICKS = 3;
    private static final double SANDEVISTAN_DASH_SPEED = 1.5;
    /**
     * Higher horizontal cap only for the sandevistan dash so it can travel faster than the normal
     * maneuver cap while still being clamped low enough that the swept collision check in
     * {@link #canTravel} prevents tunnelling through walls.
     */
    private static final double MAX_SANDEVISTAN_HORIZONTAL_SPEED = 1.5;
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
    /** Radius (blocks) a soldier patrols around its spawn point when idle. */
    private static final double PATROL_RADIUS = 12.0;
    /**
     * Half-angle (degrees) of the forward view cone. A player outside this cone is not seen even
     * with clear line of sight, so soldiers can be flanked from behind. Wide enough to feel fair.
     */
    private static final double VIEW_CONE_HALF_ANGLE_DEG = 75.0;
    private static final double VIEW_CONE_COS = Math.cos(Math.toRadians(VIEW_CONE_HALF_ANGLE_DEG));
    /** Detection lost per tick while the player is not currently visible in the view cone. */
    private static final int DETECTION_DECAY = 2;
    /** Ideal spacing (blocks) between same-faction soldiers so squads don't stack on one tile. */
    private static final double TEAMMATE_SPACING = 2.4;
    private static final double TEAMMATE_SPACING_SQR = TEAMMATE_SPACING * TEAMMATE_SPACING;
    /** How hard a soldier is nudged away from a too-close ally each tick. */
    private static final double TEAMMATE_SEPARATION_STRENGTH = 0.02;
    /** Half-width (blocks) of the corridor kept clear of allies when shooting or throwing. */
    private static final double FRIENDLY_FIRE_CLEARANCE = 0.9;

    /** Fractional buildup retained so crouch visibility can reduce detection smoothly. */
    private float detectionRemainder;
    /** The point this soldier patrols around; set on spawn. Null falls back to the current position. */
    private net.minecraft.core.BlockPos homePos;
    private List<BlockPos> patrolRoute = List.of();
    /** Grenades remaining to throw; 0 means this soldier was not issued any. */
    private int grenadeCount;
    /** Which grenade variant this soldier lobs. */
    private com.example.cyberdeck.weapon.GrenadeType grenadeType =
            com.example.cyberdeck.weapon.GrenadeType.INCENDIARY;
    private UUID traumaTargetId;
    private boolean traumaAllowsCreative;
    private UUID excisionTargetId;
    private UUID alertGroupId;
    private String ballisticTier = "";
    private int ambientWithoutPlayerTicks;
    private boolean reinforcementRollResolved;
    private boolean reinforcementDeployment;

    public FactionEnemy(EntityType<? extends FactionEnemy> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.MOVEMENT_SPEED, 0.26)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.ARMOR, 0.0)
                .add(Attributes.ARMOR_TOUGHNESS, 0.0)
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
        entityData.define(DATA_DETECTION, 0);
        entityData.define(DATA_TRAUMA_TEAM, false);
        entityData.define(DATA_EXCISION, false);
        entityData.define(DATA_AMBIENT_PATROL, false);
        entityData.define(DATA_DISTRICT, -1);
        entityData.define(DATA_SKIN, 0);
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
        // A gun holder shoots at range; a melee holder must actively path in and strike instead. The
        // ranged goal is gated to gun holders so a sword unit is never held at range doing nothing,
        // and a melee-priority attack goal (faster speed so it sprints to close the gap) sits above
        // the ranged slot for melee holders. The plain melee goal remains as a fallback finisher.
        this.goalSelector.addGoal(2, new FilteredRangedAttackGoal(
                this, 1.0, 20, 15.0f, this::isGunArmed));
        this.goalSelector.addGoal(2, new FilteredMeleeAttackGoal(
                this, 1.35, true, this::isMeleeArmed));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0, false));
        // Authored mission routes take precedence; ordinary squads retain their bounded area patrol.
        this.goalSelector.addGoal(6, new PatrolRouteGoal(this, 0.8));
        this.goalSelector.addGoal(7, new PatrolAreaGoal(
                this, 0.8, this::getHome, PATROL_RADIUS, () -> patrolRoute.isEmpty(),
                this::isAllowedPatrolPosition));
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

    public boolean isAmbientPatrol() {
        return this.getEntityData().get(DATA_AMBIENT_PATROL);
    }

    public void setAmbientPatrol(boolean ambientPatrol) {
        this.getEntityData().set(DATA_AMBIENT_PATROL, ambientPatrol);
    }

    public double detectionRange() {
        return isAmbientPatrol() ? AMBIENT_DETECTION_RANGE : MISSION_DETECTION_RANGE;
    }

    public District getDistrict() {
        int ordinal = this.getEntityData().get(DATA_DISTRICT);
        return ordinal >= 0 && ordinal < District.values().length
                ? District.values()[ordinal] : null;
    }

    public void setDistrict(District district) {
        this.getEntityData().set(DATA_DISTRICT, district == null ? -1 : district.ordinal());
    }

    public void assignDistrictFromPosition() {
        if (this.level() instanceof ServerLevel level
                && CityWorlds.kind(level) == CityWorlds.Kind.NEON_MEGACITY) {
            setDistrict(NeonCityGenerator.sample(getBlockX(), getBlockZ()).district());
        }
    }

    public int getSkinVariant() {
        return Math.floorMod(this.getEntityData().get(DATA_SKIN), TACTICAL_SKIN_COUNT);
    }

    public void setSkinVariant(int variant) {
        this.getEntityData().set(DATA_SKIN, Math.floorMod(variant, TACTICAL_SKIN_COUNT));
    }

    public UUID getAlertGroupId() {
        return alertGroupId;
    }

    public void setAlertGroupId(UUID alertGroupId) {
        this.alertGroupId = alertGroupId;
    }

    public boolean sharesAlertGroup(FactionEnemy other) {
        return other != null && alertGroupId != null && alertGroupId.equals(other.alertGroupId);
    }

    public boolean hasResolvedReinforcementRoll() {
        return reinforcementRollResolved;
    }

    public void setReinforcementRollResolved(boolean resolved) {
        reinforcementRollResolved = resolved;
    }

    public boolean isReinforcementDeployment() {
        return reinforcementDeployment;
    }

    public void setReinforcementDeployment(boolean reinforcement) {
        reinforcementDeployment = reinforcement;
    }

    /** Story-specific hostile actors do not summon ordinary corporate airborne troops. */
    public boolean canRequestReinforcements() {
        return !ballisticTier.isEmpty()
                && !(this instanceof CyberpsychoEntity)
                && !isTraumaTeam()
                && !isExcision();
    }

    @Override
    public Component getName() {
        if (this.hasCustomName() || this instanceof CyberpsychoEntity || isExcision()) {
            return super.getName();
        }
        if (isTraumaTeam()) {
            return Component.translatable("entity.cyberdeck.trauma_team_responder");
        }
        District district = getDistrict();
        return district == null
                ? super.getName()
                : Component.translatable("entity.cyberdeck.faction_enemy.district", district.code());
    }

    public String getBallisticTier() {
        return ballisticTier;
    }

    /** Preserves the former four-piece light/heavy defense as invisible entity modifiers. */
    public void setBallisticTier(String tier) {
        ballisticTier = "heavy".equals(tier) ? "heavy" : "light".equals(tier) ? "light" : "";
        double armor = "heavy".equals(ballisticTier) ? 20.0
                : "light".equals(ballisticTier) ? 15.0 : 0.0;
        double toughness = "heavy".equals(ballisticTier) ? 12.0
                : "light".equals(ballisticTier) ? 8.0 : 0.0;
        double knockback = "heavy".equals(ballisticTier) ? 0.60
                : "light".equals(ballisticTier) ? 0.20 : 0.0;
        replaceBallisticModifier(Attributes.ARMOR, BALLISTIC_ARMOR_MODIFIER, armor);
        replaceBallisticModifier(
                Attributes.ARMOR_TOUGHNESS, BALLISTIC_TOUGHNESS_MODIFIER, toughness);
        replaceBallisticModifier(
                Attributes.KNOCKBACK_RESISTANCE, BALLISTIC_KNOCKBACK_MODIFIER, knockback);
    }

    private void replaceBallisticModifier(
            net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
            Identifier id,
            double amount) {
        AttributeInstance instance = getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(id);
        if (amount != 0.0) {
            instance.addTransientModifier(
                    new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    public boolean isAllowedPatrolPosition(BlockPos position) {
        if (!isAmbientPatrol() || !(this.level() instanceof ServerLevel level)
                || CityWorlds.kind(level) != CityWorlds.Kind.NEON_MEGACITY) {
            return true;
        }
        NeonCityGenerator.UrbanSample sample =
                NeonCityGenerator.sample(position.getX(), position.getZ());
        return sample.district() == getDistrict() && FactionSpawns.isPublicPatrolArea(sample);
    }

    /** The point this soldier patrols around. Falls back to its current position if unset. */
    public net.minecraft.core.BlockPos getHome() {
        return homePos != null ? homePos : this.blockPosition();
    }

    /** Sets the patrol anchor; called on spawn so the squad guards where it appeared. */
    public void setHome(net.minecraft.core.BlockPos pos) {
        this.homePos = pos;
    }

    public List<BlockPos> getPatrolRoute() {
        return patrolRoute;
    }

    public void setPatrolRoute(List<BlockPos> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            patrolRoute = List.of();
            this.getNavigation().stop();
            return;
        }
        patrolRoute = waypoints.stream()
                .filter(java.util.Objects::nonNull)
                .limit(32)
                .map(BlockPos::immutable)
                .toList();
        this.getNavigation().stop();
    }

    public boolean isTriggered() {
        return this.getEntityData().get(DATA_TRIGGERED);
    }

    public boolean isTraumaTeam() {
        return this.getEntityData().get(DATA_TRAUMA_TEAM);
    }

    public boolean isExcision() {
        return this.getEntityData().get(DATA_EXCISION);
    }

    public boolean isExcisionTarget(UUID playerId) {
        return isExcision() && playerId.equals(excisionTargetId);
    }

    @Override
    protected LivingEntity asValidTarget(LivingEntity target) {
        if (target instanceof Player player
                && player.isAlive()
                && !player.isSpectator()
                && (isExcisionTarget(player.getUUID())
                || isTraumaTeam() && traumaAllowsCreative
                        && player.getUUID().equals(traumaTargetId))) {
            return target;
        }
        return super.asValidTarget(target);
    }

    /** Makes this responder persistent and permanently hostile to the requesting player's attacker. */
    public void deployAsTraumaTeam(
            net.minecraft.server.level.ServerPlayer target, boolean allowCreative) {
        this.getEntityData().set(DATA_TRAUMA_TEAM, true);
        this.getEntityData().set(DATA_EXCISION, false);
        this.traumaTargetId = target.getUUID();
        this.traumaAllowsCreative = allowCreative;
        this.excisionTargetId = null;
        this.setPersistenceRequired();
        this.setTriggered(true);
        this.setDetection(DETECTION_THRESHOLD);
        this.setTarget(target);
        this.setAggressive(true);
    }

    /** Marks this soldier as an Excision agent assigned to one wanted player. */
    public void deployAsExcision(net.minecraft.server.level.ServerPlayer target) {
        this.getEntityData().set(DATA_EXCISION, true);
        this.getEntityData().set(DATA_TRAUMA_TEAM, false);
        this.excisionTargetId = target.getUUID();
        this.traumaTargetId = null;
        this.traumaAllowsCreative = false;
        this.setPersistenceRequired();
        this.setTriggered(true);
        this.setDetection(DETECTION_THRESHOLD);
        this.setTarget(target);
        this.setAggressive(true);
    }

    private void setTriggered(boolean value) {
        this.getEntityData().set(DATA_TRIGGERED, value);
    }

    /** Current detection level (0..{@link #DETECTION_THRESHOLD}). Synced for the client HUD. */
    public int getDetection() {
        return this.getEntityData().get(DATA_DETECTION);
    }

    private void setDetection(int value) {
        this.getEntityData().set(DATA_DETECTION, Math.max(0, Math.min(DETECTION_THRESHOLD, value)));
    }

    /** Points needed to become hostile, exposed so the client HUD can compute a fill ratio. */
    public static int detectionThreshold() {
        return DETECTION_THRESHOLD;
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

    /** True when this soldier's main-hand weapon is a firearm (drives ranged behavior). */
    public boolean isGunArmed() {
        return this.getMainHandItem().getItem() instanceof GunItem;
    }

    /**
     * True when this soldier fights in melee: its main hand is not a firearm. Sword specialists and
     * any other non-gun holder path in and strike rather than being held at range.
     */
    public boolean isMeleeArmed() {
        return !isGunArmed();
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
        // Don't lob into a teammate: skip the throw if an ally sits in the grenade's flight path.
        if (allyInLineOfFire(target.getBoundingBox().getCenter())) {
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
        if (isExcision()) {
            maintainAssignedAggro(level, excisionTargetId, true);
        } else if (isTraumaTeam()) {
            maintainAssignedAggro(level, traumaTargetId, traumaAllowsCreative);
        } else {
            accumulateDetection(level);
        }
        if (tickAmbientRetirement(level)) {
            return;
        }
        applyTeammateSpacing(level);
        // --- BEGIN throwable-distraction hook (self-contained; see distraction block below) ---
        applyDistractionLook();
        // --- END throwable-distraction hook ---
    }

    private boolean tickAmbientRetirement(ServerLevel level) {
        if (!isAmbientPatrol()) {
            return false;
        }
        Player nearby = level.getNearestPlayer(this, 128.0);
        ambientWithoutPlayerTicks = nearby == null ? ambientWithoutPlayerTicks + 1 : 0;
        if (ambientWithoutPlayerTicks >= 600) {
            discard();
            return true;
        }
        return false;
    }

    private void maintainAssignedAggro(ServerLevel level, UUID assignedTargetId,
                                       boolean allowCreative) {
        LivingEntity current = this.getTarget();
        if (current != null && assignedTargetId != null
                && current.getUUID().equals(assignedTargetId) && current.isAlive()
                && (!(current instanceof Player player)
                || (allowCreative || !player.isCreative()) && !player.isSpectator())) {
            setTriggered(true);
            setDetection(DETECTION_THRESHOLD);
            this.setAggressive(true);
            return;
        }
        if (assignedTargetId == null) {
            return;
        }
        net.minecraft.server.level.ServerPlayer target =
                level.getServer().getPlayerList().getPlayer(assignedTargetId);
        if (target != null && target.isAlive()
                && (allowCreative || !target.isCreative()) && !target.isSpectator()) {
            this.setTarget(target);
            setTriggered(true);
            setDetection(DETECTION_THRESHOLD);
            this.setAggressive(true);
        } else {
            this.setTarget(null);
            this.setAggressive(false);
        }
    }

    /**
     * Nudges this soldier away from any same-faction ally that is standing too close, so a squad
     * spreads out into a loose line instead of stacking on a single tile. This is a light steering
     * impulse layered on top of navigation; it never fully overrides pathing.
     */
    private void applyTeammateSpacing(ServerLevel level) {
        if (isTacticalManeuvering() || isWeaponGlitching() || !this.onGround()) {
            return;
        }
        Faction faction = getFaction();
        List<FactionEnemy> allies = level.getEntitiesOfClass(FactionEnemy.class,
                this.getBoundingBox().inflate(TEAMMATE_SPACING),
                e -> e != this && e.isAlive() && e.getFaction() == faction);
        if (allies.isEmpty()) {
            return;
        }
        double pushX = 0.0;
        double pushZ = 0.0;
        for (FactionEnemy ally : allies) {
            double dx = this.getX() - ally.getX();
            double dz = this.getZ() - ally.getZ();
            double distSqr = dx * dx + dz * dz;
            if (distSqr >= TEAMMATE_SPACING_SQR || distSqr < MIN_DIRECTION_LENGTH_SQR) {
                continue;
            }
            double dist = Math.sqrt(distSqr);
            // Stronger push the closer the ally is (linear falloff to zero at the spacing radius).
            double weight = (TEAMMATE_SPACING - dist) / TEAMMATE_SPACING;
            pushX += (dx / dist) * weight;
            pushZ += (dz / dist) * weight;
        }
        if (pushX == 0.0 && pushZ == 0.0) {
            return;
        }
        Vec3 movement = this.getDeltaMovement();
        this.setDeltaMovement(
                movement.x + pushX * TEAMMATE_SEPARATION_STRENGTH,
                movement.y,
                movement.z + pushZ * TEAMMATE_SEPARATION_STRENGTH);
        this.hurtMarked = true;
    }

    /**
     * True if a same-faction ally sits between this soldier's eyes and {@code target}. Exposed for
     * goals (e.g. {@link ThrowGrenadeGoal}) so they can hold fire before committing.
     */
    public boolean hasAllyInLineOfFire(LivingEntity target) {
        return target != null && allyInLineOfFire(target.getBoundingBox().getCenter());
    }

    /**
     * True if a same-faction ally sits inside the corridor between this soldier's eyes and
     * {@code targetPos}. Used to withhold shooting and grenades that would hit a teammate.
     */
    private boolean allyInLineOfFire(Vec3 targetPos) {
        Vec3 eye = this.getEyePosition();
        Vec3 toTarget = targetPos.subtract(eye);
        double shotLength = toTarget.length();
        if (shotLength < MIN_DIRECTION_LENGTH_SQR) {
            return false;
        }
        Vec3 dir = toTarget.scale(1.0 / shotLength);
        Faction faction = getFaction();
        AABB corridor = new AABB(eye, targetPos).inflate(FRIENDLY_FIRE_CLEARANCE);
        List<FactionEnemy> allies = this.level().getEntitiesOfClass(
                FactionEnemy.class, corridor,
                e -> e != this && e.isAlive() && e.getFaction() == faction);
        for (FactionEnemy ally : allies) {
            Vec3 toAlly = ally.getBoundingBox().getCenter().subtract(eye);
            double along = toAlly.dot(dir);
            // Ally must be in front of the muzzle and nearer than the target to block the shot.
            if (along <= 0.0 || along >= shotLength) {
                continue;
            }
            Vec3 closest = eye.add(dir.scale(along));
            if (ally.getBoundingBox().inflate(FRIENDLY_FIRE_CLEARANCE * 0.5).contains(closest)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vanilla checks eye-to-eye visibility, which can see over a one-block wall even though a
     * crouched player's torso is protected. This override is consumed by sensing, detection,
     * ranged attacks, and grenade decisions, so all enemy combat agrees on low cover.
     */
    @Override
    public boolean hasLineOfSight(Entity target) {
        if (!super.hasLineOfSight(target)) {
            return false;
        }
        return !(target instanceof Player player && CrouchCombat.hasLowCover(this, player));
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
                || !canManeuverAgainst(target, maneuver)
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
            case SLIDE_FORWARD, SANDEVISTAN_DASH -> forward;
            case NONE -> Vec3.ZERO;
        };
        double speed = tacticalSpeedFor(maneuver);
        if (!canTravel(level, direction, speed, maneuver)) {
            return false;
        }

        long now = level.getGameTime();
        this.getEntityData().set(DATA_TACTICAL_MANEUVER, maneuver.id());
        this.getEntityData().set(DATA_TACTICAL_MANEUVER_START_TICK, now);
        this.getEntityData().set(DATA_TACTICAL_MANEUVER_END_TICK,
                now + tacticalDurationFor(maneuver));
        this.getEntityData().set(DATA_TACTICAL_DIRECTION_X, (float) direction.x);
        this.getEntityData().set(DATA_TACTICAL_DIRECTION_Z, (float) direction.z);
        this.getNavigation().stop();
        applyTacticalVelocity(direction, speed, maneuver);
        emitManeuverTrail(level, maneuver);
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

    private boolean canManeuverAgainst(LivingEntity target, TacticalManeuver maneuver) {
        boolean baseOk = this.isAlive()
                && this.isTriggered()
                && target != null
                && target.isAlive()
                && !(target instanceof CityNpc)
                && this.canAttack(target)
                && !this.isWeaponGlitching()
                && !this.isGunReloading()
                && this.onGround()
                && !this.horizontalCollision
                && !this.isPassenger()
                && !this.isInWater()
                && !this.isInLava();
        if (!baseOk) {
            return false;
        }
        // The gunner evasion maneuvers (lateral dashes / forward slide) are only meaningful for a
        // ranged soldier and stay gated on holding a gun. The sandevistan dash is a cyberware
        // ability that does not depend on the held weapon.
        if (maneuver == TacticalManeuver.SANDEVISTAN_DASH) {
            return true;
        }
        return this.getMainHandItem().getItem() instanceof GunItem;
    }

    private void tickTacticalManeuver(ServerLevel level) {
        TacticalManeuver maneuver = getTacticalManeuver();
        if (maneuver == TacticalManeuver.NONE) {
            return;
        }
        LivingEntity target = this.getTarget();
        long now = level.getGameTime();
        if (now >= getTacticalManeuverEndTick()
                || !canManeuverAgainst(target, maneuver)) {
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
        double speed = switch (maneuver) {
            // Keep the sandevistan dash near full speed for its whole brief window so it reads as a
            // blink rather than a decelerating lunge.
            case SANDEVISTAN_DASH -> SANDEVISTAN_DASH_SPEED * (1.0 - 0.10 * progress);
            case DASH_LEFT, DASH_RIGHT -> DASH_SPEED * (1.0 - 0.22 * progress);
            case SLIDE_FORWARD -> SLIDE_SPEED * (1.0 - 0.55 * progress);
            case NONE -> 0.0;
        };
        if (!canTravel(level, direction, speed, maneuver)) {
            endTacticalManeuver();
            return;
        }
        emitManeuverTrail(level, maneuver);

        // Navigation resumes naturally after the short action; no goal flag is held, so shooting
        // and look control continue throughout the maneuver.
        this.getNavigation().stop();
        applyTacticalVelocity(direction, speed, maneuver);
    }

    private boolean canTravel(ServerLevel level, Vec3 direction, double speed, TacticalManeuver maneuver) {
        double cappedSpeed = Math.min(speed, maxHorizontalSpeedFor(maneuver));
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

    private void applyTacticalVelocity(Vec3 direction, double requestedSpeed, TacticalManeuver maneuver) {
        double speed = Math.min(requestedSpeed, maxHorizontalSpeedFor(maneuver));
        Vec3 movement = this.getDeltaMovement();
        this.setDeltaMovement(direction.x * speed, movement.y, direction.z * speed);
        this.hurtMarked = true;
    }

    /** Per-maneuver base speed. */
    private static double tacticalSpeedFor(TacticalManeuver maneuver) {
        return switch (maneuver) {
            case SANDEVISTAN_DASH -> SANDEVISTAN_DASH_SPEED;
            case DASH_LEFT, DASH_RIGHT -> DASH_SPEED;
            case SLIDE_FORWARD -> SLIDE_SPEED;
            case NONE -> 0.0;
        };
    }

    /** Per-maneuver duration in ticks. */
    private static int tacticalDurationFor(TacticalManeuver maneuver) {
        return switch (maneuver) {
            case SANDEVISTAN_DASH -> SANDEVISTAN_DASH_TICKS;
            case DASH_LEFT, DASH_RIGHT -> DASH_TICKS;
            case SLIDE_FORWARD -> SLIDE_TICKS;
            case NONE -> 0;
        };
    }

    /** Per-maneuver horizontal speed cap; the sandevistan dash is allowed to travel faster. */
    private static double maxHorizontalSpeedFor(TacticalManeuver maneuver) {
        return maneuver == TacticalManeuver.SANDEVISTAN_DASH
                ? MAX_SANDEVISTAN_HORIZONTAL_SPEED
                : MAX_TACTICAL_HORIZONTAL_SPEED;
    }

    /**
     * Emits a brief motion-blur particle trail behind a sandevistan dash so it visually reads as a
     * fast blur. Only the sandevistan dash gets a trail; other maneuvers stay unadorned.
     */
    private void emitManeuverTrail(ServerLevel level, TacticalManeuver maneuver) {
        if (maneuver != TacticalManeuver.SANDEVISTAN_DASH) {
            return;
        }
        Vec3 center = this.position().add(0.0, this.getBbHeight() * 0.5, 0.0);
        level.sendParticles(ParticleTypes.CRIT,
                center.x, center.y, center.z,
                3, 0.18, 0.28, 0.18, 0.0);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                center.x, center.y, center.z,
                2, 0.16, 0.24, 0.16, 0.01);
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

    /**
     * Detection is only built while the player is inside this soldier's forward view cone AND has an
     * unobstructed line of sight AND is within range; it decays otherwise. Crouching shrinks the
     * effective range and slows the buildup ({@link CrouchCombat}). When detection reaches the
     * threshold the squad aggros; when a triggered soldier's target slips out of view and detection
     * decays to zero, it stands down and returns to its peaceful patrol.
     */
    private void accumulateDetection(ServerLevel level) {
        Player exposedPlayer = null;
        double bestExposureScore = Double.MAX_VALUE;
        double detectionRange = detectionRange();
        List<Player> candidates = level.getEntitiesOfClass(
                Player.class,
                this.getBoundingBox().inflate(detectionRange),
                player -> !player.isCreative() && !player.isSpectator());
        for (Player candidate : candidates) {
            double distance = this.distanceTo(candidate);
            double acquisitionRange = detectionRange
                    * CrouchCombat.detectionRangeMultiplier(candidate);
            // Proximity alone is not enough: require a real line of sight and that the player sits
            // inside the forward view cone so a soldier can be approached from behind unseen.
            if (distance > acquisitionRange
                    || !this.hasLineOfSight(candidate)
                    || !isWithinViewCone(candidate)) {
                continue;
            }

            // Choose the most exposed candidate rather than letting a hidden nearby player mask a
            // standing player elsewhere in the same multiplayer fight.
            double score = distance / CrouchCombat.visibility(candidate);
            if (score < bestExposureScore) {
                bestExposureScore = score;
                exposedPlayer = candidate;
            }
        }

        int detection = getDetection();
        if (exposedPlayer != null) {
            // Detect faster the closer the player is.
            double distance = this.distanceTo(exposedPlayer);
            int baseGain = distance < 4.0 ? 3 : (distance < 7.0 ? 2 : 1);
            detectionRemainder += baseGain * CrouchCombat.visibility(exposedPlayer);
            int gain = (int) detectionRemainder;
            detectionRemainder -= gain;
            int previous = detection;
            detection = Math.min(DETECTION_THRESHOLD, detection + gain);
            setDetection(detection);
            // Small warning cue as detection climbs.
            if (previous < DETECTION_THRESHOLD / 2
                    && detection >= DETECTION_THRESHOLD / 2) {
                level.playSound(null, this, SoundEvents.VILLAGER_NO, SoundSource.HOSTILE, 0.6f, 1.4f);
            }
            if (detection >= DETECTION_THRESHOLD) {
                trigger(level, exposedPlayer);
            }
        } else {
            // Player is out of range / behind cover / outside the view cone: forget over time.
            detectionRemainder = 0.0F;
            if (detection > 0) {
                detection = Math.max(0, detection - DETECTION_DECAY);
                setDetection(detection);
            }
            // A triggered soldier that has fully lost its quarry stands down and returns to peace.
            if (detection <= 0 && isTriggered() && !hasVisibleHostileTarget()) {
                standDown();
            }
        }
    }

    /** True if {@code target} lies within this soldier's forward horizontal view cone. */
    private boolean isWithinViewCone(Entity target) {
        Vec3 look = this.getViewVector(1.0f);
        Vec3 flatLook = new Vec3(look.x, 0.0, look.z);
        if (flatLook.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
            return true;
        }
        Vec3 toTarget = new Vec3(
                target.getX() - this.getX(), 0.0, target.getZ() - this.getZ());
        if (toTarget.lengthSqr() < MIN_DIRECTION_LENGTH_SQR) {
            return true;
        }
        return flatLook.normalize().dot(toTarget.normalize()) >= VIEW_CONE_COS;
    }

    /** True while this soldier still has a living, attackable target it can currently see. */
    private boolean hasVisibleHostileTarget() {
        LivingEntity target = this.getTarget();
        return target != null
                && target.isAlive()
                && !(target instanceof CityNpc)
                && this.canAttack(target)
                && this.hasLineOfSight(target);
    }

    /** Returns a fully-decayed soldier to its peaceful patrol state (clears aggro + target). */
    private void standDown() {
        setTriggered(false);
        this.setTarget(null);
        this.setAggressive(false);
        endTacticalManeuver();
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
        double alertRadius = isAmbientPatrol() ? AMBIENT_ALERT_RADIUS : MISSION_ALERT_RADIUS;
        List<FactionEnemy> allies = level.getEntitiesOfClass(FactionEnemy.class,
                new AABB(this.blockPosition()).inflate(alertRadius),
                e -> e != this && e.isAlive() && e.getFaction() == faction
                        && e.isAmbientPatrol() == isAmbientPatrol()
                        && sharesAlertGroup(e)
                        && !e.isTriggered());

        for (FactionEnemy ally : allies) {
            ally.setTriggered(true);
            ally.setTarget(target);
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        if (target instanceof CityNpc || !canAttack(target)
                || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        // Never shoot unprovoked: only a triggered soldier engages, and only a real acquired target
        // with clear line of sight. This stops random/idle firing into empty space.
        if (!isTriggered() || target != this.getTarget()) {
            return;
        }
        if (isWeaponGlitching() || !this.hasLineOfSight(target)) {
            return;
        }
        // Hold fire if a squadmate is standing in the shot corridor (friendly-fire prevention).
        if (allyInLineOfFire(target.getBoundingBox().getCenter())) {
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
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        Player attacker = source.getEntity() instanceof Player player
                && player.isAlive() && !player.isCreative() && !player.isSpectator()
                ? player : null;
        boolean hurt = super.hurtServer(level, source, damage);
        if (hurt && attacker != null) {
            onSuccessfulPlayerAttack(level, attacker);
        }
        return hurt;
    }

    /** Shared entry point for direct damage and damaging quickhacks attributed to a player. */
    public void onSuccessfulPlayerAttack(ServerLevel level, Player attacker) {
        if (attacker == null || !attacker.isAlive()
                || attacker.isCreative() || attacker.isSpectator()) {
            return;
        }
        trigger(level, attacker);
        FactionSquads.tryReinforcementsOnAttack(level, this, attacker);
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof CityNpc) && super.canAttack(target);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("Faction", getFaction().id());
        output.putInt("Detection", getDetection());
        output.putBoolean("Triggered", isTriggered());
        output.putBoolean("TraumaTeam", isTraumaTeam());
        output.putBoolean("TraumaAllowsCreative", traumaAllowsCreative);
        output.putBoolean("Excision", isExcision());
        output.putBoolean("AmbientPatrol", isAmbientPatrol());
        District district = getDistrict();
        if (district != null) {
            output.putString("District", district.name());
        }
        output.putInt("SkinVariant", getSkinVariant());
        if (!ballisticTier.isEmpty()) {
            output.putString("BallisticTier", ballisticTier);
        }
        if (alertGroupId != null) {
            output.putString("AlertGroup", alertGroupId.toString());
        }
        output.putBoolean("ReinforcementRollResolved", reinforcementRollResolved);
        output.putBoolean("ReinforcementDeployment", reinforcementDeployment);
        if (traumaTargetId != null) {
            output.putString("TraumaTarget", traumaTargetId.toString());
        }
        if (excisionTargetId != null) {
            output.putString("ExcisionTarget", excisionTargetId.toString());
        }
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
        if (!patrolRoute.isEmpty()) {
            output.putString("PatrolRoute", encodePatrolRoute(patrolRoute));
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
        setDetection(input.getIntOr("Detection", 0));
        setTriggered(input.getBooleanOr("Triggered", false));
        boolean traumaTeam = input.getBooleanOr("TraumaTeam", false);
        this.getEntityData().set(DATA_TRAUMA_TEAM, traumaTeam);
        traumaAllowsCreative = traumaTeam
                && input.getBooleanOr("TraumaAllowsCreative", false);
        boolean excision = input.getBooleanOr("Excision", false);
        this.getEntityData().set(DATA_EXCISION, excision);
        boolean legacyDeployment = input.read(
                "AmbientPatrol", com.mojang.serialization.Codec.BOOL).isEmpty();
        setAmbientPatrol(legacyDeployment
                ? !this.isPersistenceRequired() && !traumaTeam && !excision
                        && !(this instanceof CyberpsychoEntity)
                : input.getBooleanOr("AmbientPatrol", false));
        String districtId = input.getStringOr("District", "");
        try {
            setDistrict(districtId.isEmpty() ? null : District.valueOf(districtId));
        } catch (IllegalArgumentException ignored) {
            setDistrict(null);
        }
        if (districtId.isEmpty()) {
            assignDistrictFromPosition();
        }
        setSkinVariant(input.getInt("SkinVariant").isPresent()
                ? input.getIntOr("SkinVariant", 0)
                : Math.floorMod(getUUID().hashCode(), TACTICAL_SKIN_COUNT));
        String alertGroup = input.getStringOr("AlertGroup", "");
        if (alertGroup.isEmpty()) {
            alertGroup = this.getPersistentData().getString(LEGACY_MISSION_INSTANCE_TAG)
                    .orElse("");
        }
        try {
            alertGroupId = alertGroup.isEmpty() ? null : UUID.fromString(alertGroup);
        } catch (IllegalArgumentException ignored) {
            alertGroupId = null;
        }
        reinforcementRollResolved = input.getBooleanOr(
                "ReinforcementRollResolved", false);
        reinforcementDeployment = input.getBooleanOr("ReinforcementDeployment", false);
        String traumaTarget = input.getStringOr("TraumaTarget", "");
        try {
            traumaTargetId = traumaTarget.isEmpty() ? null : UUID.fromString(traumaTarget);
        } catch (IllegalArgumentException ignored) {
            traumaTargetId = null;
        }
        String excisionTarget = input.getStringOr("ExcisionTarget", "");
        try {
            excisionTargetId = excisionTarget.isEmpty() ? null : UUID.fromString(excisionTarget);
        } catch (IllegalArgumentException ignored) {
            excisionTargetId = null;
        }
        if (traumaTeam || excision) {
            this.setPersistenceRequired();
        }
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
        patrolRoute = decodePatrolRoute(input.getStringOr("PatrolRoute", ""));
        FactionSquads.restoreBallisticLoadout(
                this, input.getStringOr("BallisticTier", ""));
    }

    @Override
    public boolean shouldBeSaved() {
        return !isAmbientPatrol() && super.shouldBeSaved();
    }

    public static String encodePatrolRoute(List<BlockPos> route) {
        return route.stream()
                .map(pos -> pos.getX() + "," + pos.getY() + "," + pos.getZ())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    public static List<BlockPos> decodePatrolRoute(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        java.util.ArrayList<BlockPos> route = new java.util.ArrayList<>();
        for (String waypoint : encoded.split(";")) {
            if (route.size() >= 32) {
                break;
            }
            String[] coordinates = waypoint.split(",", -1);
            if (coordinates.length != 3) {
                continue;
            }
            try {
                route.add(new BlockPos(
                        Integer.parseInt(coordinates[0]),
                        Integer.parseInt(coordinates[1]),
                        Integer.parseInt(coordinates[2])));
            } catch (NumberFormatException ignored) {
                // Ignore one malformed waypoint without discarding the remaining saved route.
            }
        }
        return List.copyOf(route);
    }

    // =====================================================================================
    // BEGIN throwable-distraction block (self-contained; safe to merge independently).
    // A thrown item (any ThrowableItemProjectile) briefly draws this soldier's gaze toward the
    // item's position. This only rotates the head/look; it never changes goals, target selection
    // or tactical movement, so it composes cleanly with the combat AI owned elsewhere.
    // =====================================================================================
    /** World position this soldier is momentarily distracted toward, or null when not distracted. */
    private net.minecraft.world.phys.Vec3 distractionPos;
    /** Game-tick at which the current distraction expires. */
    private long distractionEndTick;

    /**
     * Draw this soldier's attention to {@code pos} for {@code ticks} ticks. A brief look-only
     * override used when a throwable lands nearby; does not alter the combat target.
     */
    public void distractTo(net.minecraft.world.phys.Vec3 pos, int ticks) {
        if (pos == null || ticks <= 0) {
            return;
        }
        this.distractionPos = pos;
        this.distractionEndTick = this.level().getGameTime() + ticks;
    }

    /** True while a throwable distraction is still active. */
    public boolean isDistracted() {
        return distractionPos != null && this.level().getGameTime() < distractionEndTick;
    }

    /** The point this soldier is currently distracted toward, or null when not distracted. */
    public net.minecraft.world.phys.Vec3 getDistractionPos() {
        return isDistracted() ? distractionPos : null;
    }

    /**
     * While distracted, turn the head toward the distraction point. Enemies already locked onto the
     * player in melee still glance over, but their look snaps back next tick once the combat AI
     * runs, so this remains a brief look and never steals a hard-aggro target.
     */
    private void applyDistractionLook() {
        if (!isDistracted()) {
            distractionPos = null;
            return;
        }
        this.getLookControl().setLookAt(
                distractionPos.x, distractionPos.y, distractionPos.z,
                (float) this.getMaxHeadYRot(), (float) this.getMaxHeadXRot());
    }
    // =====================================================================================
    // END throwable-distraction block.
    // =====================================================================================
}
