package com.example.cyberdeck.npc;

import com.example.cyberdeck.city.CityWorlds;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A non-combatant city pedestrian. It has no attack or target goals, never retaliates, follows
 * street-level waypoints, and scatters away from gunfire reported by {@link GunshotAlerts}.
 */
public final class CityNpc extends PathfinderMob {
    public static final int SKIN_COUNT = 8;
    public static final int PANIC_TICKS = 100;

    private static final EntityDataAccessor<Integer> DATA_SKIN =
            SynchedEntityData.defineId(CityNpc.class, EntityDataSerializers.INT);

    private Vec3 gunshotSource;
    private long panicUntilTick = -1L;
    private boolean populationManaged;
    private BlockPos populationHome;

    public CityNpc(EntityType<? extends CityNpc> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(false);
        this.getNavigation().setCanOpenDoors(true);
        this.setPathfindingMalus(PathType.DAMAGE_CAUTIOUS, 48.0F);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new CityPedestrianNavigation(this, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, 0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new FleeGunshotGoal(this, 1.38));
        this.goalSelector.addGoal(5, new CityStreetStrollGoal(this, 0.82));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 7.0f, 0.04f));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // Deliberately no target selector and no attack goal.
    }

    public int getSkinVariant() {
        return Math.floorMod(this.getEntityData().get(DATA_SKIN), SKIN_COUNT);
    }

    public void setSkinVariant(int variant) {
        this.getEntityData().set(DATA_SKIN, Math.floorMod(variant, SKIN_COUNT));
    }

    public void markPopulationManaged(BlockPos home) {
        this.populationManaged = true;
        this.populationHome = home.immutable();
    }

    public boolean isPopulationManaged() {
        return populationManaged && !this.isPersistenceRequired() && !this.hasCustomName();
    }

    public BlockPos populationHome() {
        return populationHome != null ? populationHome : this.blockPosition();
    }

    public static float highwayPathMalus() {
        return 48.0F;
    }

    public void hearGunshot(ServerLevel level, Vec3 source) {
        this.gunshotSource = source;
        this.panicUntilTick = Math.max(this.panicUntilTick, level.getGameTime() + PANIC_TICKS);
        this.getNavigation().stop();
    }

    public boolean isFleeingGunfire() {
        return this.level().getGameTime() < panicUntilTick && gunshotSource != null;
    }

    public Vec3 gunshotSource() {
        return gunshotSource;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide() && !isFleeingGunfire()) {
            gunshotSource = null;
        }
    }

    @Override
    public boolean checkSpawnRules(LevelAccessor level, EntitySpawnReason reason) {
        return level instanceof ServerLevel server
                && CityWorlds.isCity(server)
                && super.checkSpawnRules(level, reason);
    }

    @Override
    public boolean canBeSeenAsEnemy() {
        return false;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return false;
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return !isPopulationManaged() && super.shouldBeSaved();
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.VILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.VILLAGER_DEATH;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("SkinVariant", getSkinVariant());
        output.putBoolean("PopulationManaged", populationManaged);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setSkinVariant(input.getIntOr("SkinVariant", 0));
        // Civilians saved by older versions were all ambient spawns. Migrate them into the
        // bounded population unless a player deliberately named the entity.
        populationManaged = input.getBooleanOr("PopulationManaged", !this.hasCustomName());
    }
}
