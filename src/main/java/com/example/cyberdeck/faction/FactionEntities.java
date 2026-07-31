package com.example.cyberdeck.faction;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers the faction enemy entity type. A single {@link FactionEnemy} type is used for all three
 * factions; the specific faction is stored per-entity, which keeps registration and rendering simple
 * (it renders with the vanilla zombie renderer and shows its faction via its dyed armor).
 */
public final class FactionEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Cyberdeck.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<FactionEnemy>> FACTION_ENEMY =
            ENTITY_TYPES.register("faction_enemy", () -> EntityType.Builder
                    .of(FactionEnemy::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.95f)
                    .clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "faction_enemy"))));

    public static final DeferredHolder<EntityType<?>, EntityType<CyberpsychoEntity>> CYBERPSYCHO =
            ENTITY_TYPES.register("cyberpsycho", () -> EntityType.Builder
                    .of(CyberpsychoEntity::new, MobCategory.MONSTER)
                    .sized(0.68F, 2.08F)
                    .clientTrackingRange(12)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "cyberpsycho"))));

    private FactionEntities() {
    }
}
