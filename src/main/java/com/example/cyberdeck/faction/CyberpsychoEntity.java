package com.example.cyberdeck.faction;

import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Dedicated gun-and-grenade boss entity with configurable installed cyberware. */
public final class CyberpsychoEntity extends FactionEnemy {
    // Balance: previously 160 HP with 14 armour / 6 toughness made the psycho a bullet sponge.
    // Player guns are hitscan and only deal 5.5-22 base per hit (assault rifle 5.5, sniper 18,
    // grad 22) before armour reduction, so a fair miniboss should die to roughly a full magazine
    // of aimed hits or ~8-9 sniper rounds. 110 HP with 10 armour / 4 toughness lands in that band.
    // (The mission datapack also spawns the psycho at 110 HP via configure().)
    private static final float DEFAULT_HEALTH = 110.0F;
    // Balance: blood_pump self-heal now recharges 3x slower (was every 100 ticks / 5s) so the
    // psycho heals roughly a third as often; the heal amount is unchanged.
    private static final int HEAL_RECHARGE_TICKS = 300;
    private final ServerBossEvent bossEvent = new ServerBossEvent(
            UUID.randomUUID(),
            Component.literal("CYBERPSYCHO").withStyle(ChatFormatting.RED),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10);
    private List<String> installedCyberware = List.of(
            "sandevistan", "subdermal_armor", "blood_pump");

    public CyberpsychoEntity(EntityType<? extends CyberpsychoEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        // Balance: armour lowered from 14/6 to 10/4 so hitscan bullets retain more of their listed
        // damage against the psycho while it still shrugs off chip damage from weak sidearms.
        return createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, DEFAULT_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 0.31)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.ARMOR, 10.0)
                .add(Attributes.ARMOR_TOUGHNESS, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.55)
                .add(Attributes.FOLLOW_RANGE, 56.0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(1, new CyberpsychoCoverGoal(this));
    }

    public void configure(int health, List<String> cyberware) {
        double maximum = Math.max(40, health);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(maximum);
        setHealth((float) maximum);
        installedCyberware = List.copyOf(cyberware);
        getAttribute(Attributes.ARMOR).setBaseValue(
                installedCyberware.contains("subdermal_armor") ? 10.0 : 5.0);
        getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(
                installedCyberware.contains("subdermal_armor") ? 4.0 : 2.0);
        bossEvent.setName(getDisplayName());
    }

    public List<String> installedCyberware() {
        return installedCyberware;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(level() instanceof ServerLevel level)) return;
        bossEvent.setProgress(getHealth() / getMaxHealth());

        LivingEntity target = getTarget();
        if (target != null && target.isAlive() && isTriggered()
                && installedCyberware.contains("sandevistan")
                && tickCount % 45 == 0 && hasLineOfSight(target)) {
            TacticalManeuver maneuver = getRandom().nextBoolean()
                    ? TacticalManeuver.DASH_LEFT : TacticalManeuver.DASH_RIGHT;
            tryStartTacticalManeuver(maneuver, target);
        }
        if (installedCyberware.contains("blood_pump")
                && getHealth() < getMaxHealth() && tickCount % HEAL_RECHARGE_TICKS == 0) {
            heal(3.0F);
            level.sendParticles(ParticleTypes.HEART,
                    getX(), getY() + getBbHeight() * 0.7, getZ(),
                    4, 0.25, 0.3, 0.25, 0.02);
        }
        if (installedCyberware.contains("optical_camo")
                && getHealth() <= getMaxHealth() * 0.45F && tickCount % 20 == 0) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    getX(), getY() + getBbHeight() * 0.55, getZ(),
                    8, 0.35, 0.7, 0.35, 0.04);
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        bossEvent.addPlayer(player);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        bossEvent.removePlayer(player);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("InstalledCyberware", String.join(",", installedCyberware));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        String saved = input.getStringOr("InstalledCyberware", "");
        installedCyberware = saved.isBlank()
                ? List.of("sandevistan", "subdermal_armor", "blood_pump")
                : List.of(saved.split(","));
        bossEvent.setName(getDisplayName());
    }
}
