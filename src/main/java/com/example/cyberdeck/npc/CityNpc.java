package com.example.cyberdeck.npc;

import com.example.cyberdeck.economy.MoneyShardItem;
import com.example.cyberdeck.faction.FilteredRangedAttackGoal;
import com.example.cyberdeck.trauma.TraumaTeamEvents;
import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.weapon.GunFiring;
import com.example.cyberdeck.weapon.GunItem;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** A city pedestrian whose synchronized role controls combat, durability, and rewards. */
public final class CityNpc extends PathfinderMob implements RangedAttackMob {
    public static final int SKIN_COUNT = 9;
    public static final int MISSION_TARGET_SKIN = 8;
    public static final int PANIC_TICKS = 100;
    public static final int CORPO_DEFENSE_PERCENT = 30;
    public static final double EXEC_TRAUMA_HEALTH_FRACTION = 0.5;
    public static final double EXEC_MAX_HIT_FRACTION = 0.18;

    private static final EntityDataAccessor<Integer> DATA_SKIN =
            SynchedEntityData.defineId(CityNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ROLE =
            SynchedEntityData.defineId(CityNpc.class, EntityDataSerializers.INT);

    private Vec3 threatSource;
    private long panicUntilTick = -1L;
    private boolean populationManaged;
    private BlockPos populationHome;
    private boolean defenseRolled;
    private boolean fightingBack;
    private boolean traumaRequested;
    private BlockPos evacuationTarget;

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
                .add(Attributes.MAX_HEALTH, NpcRole.RESIDENT.maxHealth())
                .add(Attributes.ARMOR, NpcRole.RESIDENT.armor())
                .add(Attributes.ARMOR_TOUGHNESS, NpcRole.RESIDENT.armorToughness())
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, 0);
        builder.define(DATA_ROLE, NpcRole.RESIDENT.ordinal());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new EvacuateToAerodyneGoal(this, 1.32));
        this.goalSelector.addGoal(2, new FleeGunshotGoal(this, 1.38));
        this.goalSelector.addGoal(3, new FilteredRangedAttackGoal(
                this, 1.0, 24, 16.0F, this::isFightingBack));
        this.goalSelector.addGoal(5, new CityStreetStrollGoal(this, 0.82));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 7.0F, 0.04F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    public NpcRole getRole() {
        return NpcRole.byOrdinal(this.getEntityData().get(DATA_ROLE));
    }

    public void setRole(NpcRole role) {
        this.getEntityData().set(DATA_ROLE, role.ordinal());
        applyRoleAttributes(role);
        this.setHealth(this.getMaxHealth());
    }

    private void applyRoleAttributes(NpcRole role) {
        setAttributeBase(Attributes.MAX_HEALTH, role.maxHealth());
        setAttributeBase(Attributes.ARMOR, role.armor());
        setAttributeBase(Attributes.ARMOR_TOUGHNESS, role.armorToughness());
    }

    private void setAttributeBase(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                  double value) {
        AttributeInstance instance = this.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    @Override
    public Component getName() {
        if (this.hasCustomName()) {
            return super.getName();
        }
        return Component.translatable("entity.cyberdeck.city_npc." + getRole().id());
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
        fleeFrom(level, source);
    }

    public void fleeFrom(ServerLevel level, Vec3 source) {
        if (isEvacuating() || fightingBack) {
            return;
        }
        this.threatSource = source;
        this.panicUntilTick = Math.max(this.panicUntilTick, level.getGameTime() + PANIC_TICKS);
        this.getNavigation().stop();
    }

    public boolean isFleeingGunfire() {
        return this.level().getGameTime() < panicUntilTick && threatSource != null;
    }

    public Vec3 gunshotSource() {
        return threatSource;
    }

    public boolean isFightingBack() {
        return fightingBack && getRole() == NpcRole.CORPO;
    }

    public boolean hasTraumaRequested() {
        return traumaRequested;
    }

    public void beginEvacuation(BlockPos target) {
        this.evacuationTarget = target.immutable();
        this.fightingBack = false;
        this.threatSource = null;
        this.panicUntilTick = -1L;
        this.setTarget(null);
        this.getNavigation().stop();
    }

    public boolean isEvacuating() {
        return evacuationTarget != null;
    }

    public BlockPos evacuationTarget() {
        return evacuationTarget;
    }

    public void clearEvacuationTarget() {
        evacuationTarget = null;
        this.getNavigation().stop();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        float limitedDamage = limitIncomingDamage(getRole(), this.getMaxHealth(), damage);
        boolean hurt = super.hurtServer(level, source, limitedDamage);
        if (!hurt) {
            return false;
        }

        Entity sourceEntity = source.getEntity();
        if (sourceEntity instanceof LivingEntity attacker && attacker != this && this.isAlive()) {
            respondToAttack(level, attacker);
        }
        if (getRole() == NpcRole.EXEC && this.isAlive() && !traumaRequested
                && shouldRequestTrauma(this.getHealth(), this.getMaxHealth())) {
            traumaRequested = TraumaTeamEvents.request(level, this, sourceEntity);
        }
        return true;
    }

    private void respondToAttack(ServerLevel level, LivingEntity attacker) {
        if (getRole() == NpcRole.CORPO && attacker instanceof Player player
                && !player.isCreative() && !player.isSpectator()) {
            if (!defenseRolled) {
                defenseRolled = true;
                fightingBack = corpoDefends(this.getRandom().nextInt(100));
                if (fightingBack) {
                    drawDefensiveWeapon();
                }
            }
            if (fightingBack) {
                this.setTarget(player);
                this.getNavigation().stop();
                return;
            }
        }
        fleeFrom(level, attacker.position());
    }

    private void drawDefensiveWeapon() {
        GunType sidearm = this.getRandom().nextBoolean() ? GunType.YUKIMURA : GunType.UNITY;
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WeaponItems.gun(sidearm).get()));
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    public static boolean corpoDefends(int percentileRoll) {
        return Math.floorMod(percentileRoll, 100) < CORPO_DEFENSE_PERCENT;
    }

    public static float limitIncomingDamage(NpcRole role, float maxHealth, float damage) {
        if (role != NpcRole.EXEC) {
            return damage;
        }
        return Math.min(damage, Math.max(1.0F, maxHealth * (float) EXEC_MAX_HIT_FRACTION));
    }

    public static boolean shouldRequestTrauma(float health, float maxHealth) {
        return maxHealth > 0.0F && health > 0.0F
                && health < maxHealth * (float) EXEC_TRAUMA_HEALTH_FRACTION;
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        if (!isFightingBack() || target != this.getTarget() || !this.canAttack(target)
                || !this.hasLineOfSight(target)
                || !(this.level() instanceof ServerLevel level)
                || !(this.getMainHandItem().getItem() instanceof GunItem gun)) {
            return;
        }
        this.getLookControl().setLookAt(target, 30.0F, 30.0F);
        GunFiring.fire(level, this, gun.gun());
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide()) {
            if (!isFleeingGunfire()) {
                threatSource = null;
            }
            LivingEntity target = this.getTarget();
            if (target != null && (!target.isAlive()
                    || target instanceof Player player && (player.isCreative() || player.isSpectator()))) {
                this.setTarget(null);
                fightingBack = false;
            }
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
        return isFightingBack() && target instanceof Player player
                && !player.isCreative() && !player.isSpectator()
                && super.canAttack(target);
    }

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity target) {
        return false;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        this.spawnAtLocation(level, MoneyShardItem.create(getRole().rollCredits(this.getRandom())));
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
        output.putInt("NpcRole", getRole().ordinal());
        output.putFloat("RoleHealth", getHealth());
        output.putBoolean("PopulationManaged", populationManaged);
        output.putBoolean("DefenseRolled", defenseRolled);
        output.putBoolean("FightingBack", fightingBack);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setSkinVariant(input.getIntOr("SkinVariant", 0));
        NpcRole role = NpcRole.byOrdinal(input.getIntOr("NpcRole", NpcRole.RESIDENT.ordinal()));
        this.getEntityData().set(DATA_ROLE, role.ordinal());
        applyRoleAttributes(role);
        this.setHealth(input.getFloatOr("RoleHealth", this.getHealth()));
        populationManaged = input.getBooleanOr("PopulationManaged", !this.hasCustomName());
        defenseRolled = input.getBooleanOr("DefenseRolled", false);
        fightingBack = role == NpcRole.CORPO && input.getBooleanOr("FightingBack", false);
        traumaRequested = false;
        evacuationTarget = null;
    }
}
