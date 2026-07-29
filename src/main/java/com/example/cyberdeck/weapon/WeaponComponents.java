package com.example.cyberdeck.weapon;

import com.example.cyberdeck.Cyberdeck;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Item data components for guns. The magazine component stores how many rounds are currently loaded
 * in a specific gun {@link net.minecraft.world.item.ItemStack}, so ammo is tracked per weapon rather
 * than globally.
 */
public final class WeaponComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Cyberdeck.MODID);

    /** Rounds currently loaded in the magazine. Absent => treat as a full magazine. */
    public static final Supplier<DataComponentType<Integer>> MAGAZINE =
            COMPONENTS.register("magazine", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    private WeaponComponents() {
    }
}
