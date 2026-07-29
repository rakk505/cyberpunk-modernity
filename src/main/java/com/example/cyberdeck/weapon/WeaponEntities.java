package com.example.cyberdeck.weapon;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers projectile entity types used by weapons.
 */
public final class WeaponEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Cyberdeck.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<ThrownGrenade>> THROWN_GRENADE =
            ENTITY_TYPES.register("thrown_grenade", () -> EntityType.Builder
                    .<ThrownGrenade>of(ThrownGrenade::new, MobCategory.MISC)
                    .sized(0.25f, 0.25f)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "thrown_grenade"))));

    public static final DeferredHolder<EntityType<?>, EntityType<SmartBullet>> SMART_BULLET =
            ENTITY_TYPES.register("smart_bullet", () -> EntityType.Builder
                    .<SmartBullet>of(SmartBullet::new, MobCategory.MISC)
                    .sized(0.12f, 0.12f)
                    .noSave()
                    .noSummon()
                    .clientTrackingRange(8)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "smart_bullet"))));

    private WeaponEntities() {
    }
}
