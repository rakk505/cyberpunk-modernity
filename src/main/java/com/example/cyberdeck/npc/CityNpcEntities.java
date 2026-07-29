package com.example.cyberdeck.npc;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entity registration for passive city pedestrians. */
public final class CityNpcEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Cyberdeck.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<CityNpc>> CITY_NPC =
            ENTITY_TYPES.register("city_npc", () -> EntityType.Builder
                    .of(CityNpc::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    .clientTrackingRange(10)
                    .noLootTable()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "city_npc"))));

    private CityNpcEntities() {
    }
}
