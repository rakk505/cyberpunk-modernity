package com.example.cyberdeck.weapon;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

/** Damage types used when a physical projectile entity would be inappropriate, such as hitscan. */
public final class CyberdeckDamageTypes {
    public static final ResourceKey<DamageType> BULLET = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "bullet"));

    private CyberdeckDamageTypes() {
    }
}
