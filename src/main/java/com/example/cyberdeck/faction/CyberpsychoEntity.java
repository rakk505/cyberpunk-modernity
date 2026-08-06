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
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** Dedicated gun-and-grenade boss entity with configurable installed cyberware. */
public final class CyberpsychoEntity extends FactionEnemy {
    private static final float DEFAULT_HEALTH = 75.0F;
    private static final int MAX_CONFIGURED_HEALTH = 180;
    /**
     * Possible spawn loadouts. On natural spawn one is picked at random so the sandevistan is an
     * optional variant rather than guaranteed. Subdermal_armor and blood_pump remain common; a
     * variant may also carry optical_camo. An explicit datapack {@code configure()} overrides this.
     */
    private static final List<List<String>> SPAWN_LOADOUTS = List.of(
            List.of(EnemyCyberware.SANDEVISTAN, EnemyCyberware.SUBDERMAL_ARMOR,
                    EnemyCyberware.BLOOD_PUMP),
            List.of(EnemyCyberware.SANDEVISTAN, EnemyCyberware.BLOOD_PUMP,
                    EnemyCyberware.OPTICAL_CAMO),
            List.of(EnemyCyberware.SANDEVISTAN, EnemyCyberware.MANTIS_BLADES,
                    EnemyCyberware.BLOOD_PUMP),
            List.of(EnemyCyberware.SUBDERMAL_ARMOR, EnemyCyberware.BLOOD_PUMP),
            List.of(EnemyCyberware.SUBDERMAL_ARMOR, EnemyCyberware.BLOOD_PUMP,
                    EnemyCyberware.OPTICAL_CAMO),
            List.of(EnemyCyberware.ARM_CANNON, EnemyCyberware.BLOOD_PUMP,
                    EnemyCyberware.OPTICAL_CAMO));
    static final List<String> DEFAULT_LOADOUT = List.of(
            EnemyCyberware.SANDEVISTAN, EnemyCyberware.SUBDERMAL_ARMOR,
            EnemyCyberware.BLOOD_PUMP);

    /** Set true once {@link #configure} is called so a mission datapack loadout is never overwritten. */
    private boolean explicitlyConfigured;
    private final ServerBossEvent bossEvent = new ServerBossEvent(
            UUID.randomUUID(),
            Component.literal("CYBERPSYCHO").withStyle(ChatFormatting.RED),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10);

    public CyberpsychoEntity(EntityType<? extends CyberpsychoEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        installCyberware(DEFAULT_LOADOUT);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, DEFAULT_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 0.31)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.ARMOR, 7.0)
                .add(Attributes.ARMOR_TOUGHNESS, 2.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.55)
                .add(Attributes.FOLLOW_RANGE, 56.0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        goalSelector.addGoal(1, new CyberpsychoCoverGoal(this));
    }

    public void configure(int health, List<String> cyberware) {
        explicitlyConfigured = true;
        double maximum = balancedHealth(health);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(maximum);
        setHealth((float) maximum);
        installCyberware(cyberware);
        bossEvent.setName(getDisplayName());
    }

    /**
     * A boss keeps its own plating curve rather than the elite floor, so an unarmoured
     * cyberpsycho is meaningfully softer than an armoured one.
     */
    private void installCyberware(List<String> cyberware) {
        setInstalledCyberware(cyberware);
        boolean armored = hasCyberware(EnemyCyberware.SUBDERMAL_ARMOR);
        getAttribute(Attributes.ARMOR).setBaseValue(armored ? 7.0 : 4.0);
        getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(armored ? 2.0 : 1.0);
    }

    public static int balancedHealth(int requestedHealth) {
        return Math.max(40, Math.min(MAX_CONFIGURED_HEALTH, requestedHealth));
    }

    /**
     * On natural spawn, roll one of the {@link #SPAWN_LOADOUTS} so the sandevistan is an optional
     * variant. A mission datapack that has already called {@link #configure} keeps its explicit
     * list untouched.
     */
    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        EntitySpawnReason reason, @Nullable SpawnGroupData spawnData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, reason, spawnData);
        if (!explicitlyConfigured) {
            installCyberware(SPAWN_LOADOUTS.get(getRandom().nextInt(SPAWN_LOADOUTS.size())));
            bossEvent.setName(getDisplayName());
        }
        return result;
    }

    @Override
    public void aiStep() {
        // The sandevistan blink, blood pump regeneration and optical camo discharge all live in
        // EnemyCyberware and run from FactionEnemy.aiStep, so a boss and an augmented mook use one
        // implementation and read identically in play.
        super.aiStep();
        if (!(level() instanceof ServerLevel)) return;
        bossEvent.setProgress(getHealth() / getMaxHealth());
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
    protected void readAdditionalSaveData(ValueInput input) {
        // FactionEnemy already restored InstalledCyberware; a boss saved before it carried that
        // tag falls back to the classic loadout rather than waking up with no chrome at all.
        super.readAdditionalSaveData(input);
        if (installedCyberware().isEmpty()) setInstalledCyberware(DEFAULT_LOADOUT);
        double maximum = Math.max(40.0, Math.min(MAX_CONFIGURED_HEALTH,
                getAttribute(Attributes.MAX_HEALTH).getBaseValue()));
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(maximum);
        boolean armored = hasCyberware(EnemyCyberware.SUBDERMAL_ARMOR);
        getAttribute(Attributes.ARMOR).setBaseValue(armored ? 7.0 : 4.0);
        getAttribute(Attributes.ARMOR_TOUGHNESS).setBaseValue(armored ? 2.0 : 1.0);
        setHealth(Math.min(getHealth(), (float) maximum));
        bossEvent.setName(getDisplayName());
    }
}
