package com.example.cyberdeck.weapon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The physical thrown grenade. Detonates on the first impact (block or entity), producing an area
 * effect determined by the carried {@link GrenadeType}:
 * <ul>
 *   <li><b>Incendiary</b> - ignites entities in radius and scatters fire on exposed ground.</li>
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
        AABB area = new AABB(center, center).inflate(r);
        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e.isAlive() && e.distanceToSqr(center) <= r * r);

        switch (type) {
            case INCENDIARY -> {
                level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 1, 0, 0, 0, 0);
                level.sendParticles(ParticleTypes.FLAME, center.x, center.y, center.z,
                        60, r * 0.4, 0.4, r * 0.4, 0.05);
                level.playSound(null, center.x, center.y, center.z,
                        SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1.2f, 0.8f);
                for (LivingEntity victim : victims) {
                    victim.igniteForSeconds(type.effectDurationTicks() / 20);
                    victim.hurtServer(level, this.damageSources().onFire(), 3.0f);
                }
                scatterFire(level, BlockPos.containing(center), (int) r);
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
                    victim.addEffect(new MobEffectInstance(
                            MobEffects.POISON, type.effectDurationTicks(), 1, false, true, true));
                    victim.addEffect(new MobEffectInstance(
                            MobEffects.SLOWNESS, type.effectDurationTicks() / 2, 0, false, true, true));
                }
            }
        }
    }

    private void scatterFire(ServerLevel level, BlockPos center, int radius) {
        for (int i = 0; i < 12; i++) {
            int dx = level.getRandom().nextInt(radius * 2 + 1) - radius;
            int dz = level.getRandom().nextInt(radius * 2 + 1) - radius;
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos ground = center.offset(dx, dy, dz);
                BlockPos above = ground.above();
                BlockState groundState = level.getBlockState(ground);
                if (!groundState.isAir() && groundState.isSolidRender()
                        && level.getBlockState(above).isAir()) {
                    level.setBlockAndUpdate(above, Blocks.FIRE.defaultBlockState());
                    break;
                }
            }
        }
    }
}
