package com.example.cyberdeck.weapon;

import com.example.cyberdeck.faction.FactionEnemy;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The physical thrown grenade. Detonates on the first impact (block or entity), producing an area
 * effect determined by the carried {@link GrenadeType}:
 * <ul>
 *   <li><b>Incendiary</b> - ignites entities in radius (no fire blocks are placed in the world).</li>
 *   <li><b>Poison</b> - spawns a lingering toxic cloud that poisons entities in radius.</li>
 * </ul>
 * The type is derived from the item the grenade carries, so no extra synced data is needed.
 */
public final class ThrownGrenade extends ThrowableItemProjectile {
    public ThrownGrenade(EntityType<? extends ThrownGrenade> type, Level level) {
        super(type, level);
    }

    public ThrownGrenade(ServerLevel level, LivingEntity thrower, ItemStack item) {
        super(WeaponEntities.THROWN_GRENADE.get(), thrower, level, item);
    }

    @Override
    protected Item getDefaultItem() {
        return WeaponItems.INCENDIARY_GRENADE.get();
    }

    private GrenadeType grenadeType() {
        if (getItem().getItem() instanceof GrenadeItem grenade) {
            return grenade.type();
        }
        return GrenadeType.INCENDIARY;
    }

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);
        if (this.level().isClientSide()) {
            return;
        }
        if (this.level() instanceof ServerLevel level) {
            detonate(level, this.position(), grenadeType());
        }
        this.discard();
    }

    private void detonate(ServerLevel level, Vec3 center, GrenadeType type) {
        double r = type.radius();
        Player playerOwner = getOwner() instanceof Player player ? player : null;
        double criticalMultiplier = throwableCriticalMultiplier(playerOwner);
        AABB area = new AABB(center, center).inflate(r);
        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e.isAlive() && e.distanceToSqr(center) <= r * r);

        switch (type) {
            case INCENDIARY -> {
                level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 1, 0, 0, 0, 0);
                level.sendParticles(ParticleTypes.FLAME, center.x, center.y, center.z,
                        60, r * 0.4, 0.4, r * 0.4, 0.05);
                // No fire blocks are placed, so add a few purely-visual short-lived flame bursts for
                // a satisfying incendiary flash. These are client-safe particle spawns only.
                level.sendParticles(ParticleTypes.SMALL_FLAME, center.x, center.y, center.z,
                        40, r * 0.5, 0.3, r * 0.5, 0.02);
                level.sendParticles(ParticleTypes.LAVA, center.x, center.y, center.z,
                        8, r * 0.3, 0.2, r * 0.3, 0.0);
                level.playSound(null, center.x, center.y, center.z,
                        SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1.2f, 0.8f);
                for (LivingEntity victim : victims) {
                    // igniteForSeconds already respects water/rain, so victims standing in water
                    // won't catch fire; ignited entities burn out on their own with no world fire.
                    victim.igniteForSeconds(type.effectDurationTicks() / 20);
                    boolean hurt = victim.hurtServer(
                            level, this.damageSources().onFire(),
                            (float) (3.0 * criticalMultiplier));
                    if (hurt && playerOwner != null && victim instanceof FactionEnemy enemy) {
                        enemy.onSuccessfulPlayerAttack(level, playerOwner);
                    }
                }
            }
            case POISON -> {
                level.sendParticles(ParticleTypes.SNEEZE, center.x, center.y, center.z, 1, 0, 0, 0, 0);
                level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, center.x, center.y, center.z,
                        80, r * 0.5, 0.5, r * 0.5, 0.02);
                level.sendParticles(ParticleTypes.ITEM_SLIME, center.x, center.y, center.z,
                        40, r * 0.5, 0.3, r * 0.5, 0.0);
                level.playSound(null, center.x, center.y, center.z,
                        SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 1.2f, 0.7f);
                for (LivingEntity victim : victims) {
                    boolean poisoned = victim.addEffect(new MobEffectInstance(
                            MobEffects.POISON,
                            (int) Math.round(type.effectDurationTicks() * criticalMultiplier),
                            criticalMultiplier > 1.0 ? 2 : 1, false, true, true));
                    victim.addEffect(new MobEffectInstance(
                            MobEffects.SLOWNESS, type.effectDurationTicks() / 2, 0, false, true, true));
                    if (poisoned && playerOwner != null && victim instanceof FactionEnemy enemy) {
                        enemy.onSuccessfulPlayerAttack(level, playerOwner);
                    }
                }
            }
        }
    }

    private static double throwableCriticalMultiplier(Player owner) {
        if (!(owner instanceof ServerPlayer player)) {
            return 1.0;
        }
        double chance = com.example.cyberdeck.effect.CyberwareEffects
                .sumValue(player, "throwable_crit_chance_percent") / 100.0;
        return chance > 0.0 && player.getRandom().nextDouble() < Math.min(1.0, chance)
                ? 1.5
                : 1.0;
    }
}
