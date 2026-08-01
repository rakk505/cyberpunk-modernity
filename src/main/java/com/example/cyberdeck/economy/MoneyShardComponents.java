package com.example.cyberdeck.economy;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Per-stack data for variable-value money shards. */
public final class MoneyShardComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Cyberdeck.MODID);

    public static final Supplier<DataComponentType<Integer>> CREDITS =
            COMPONENTS.register("money_shard_credits", () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.intRange(1, 10_000))
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    private MoneyShardComponents() {
    }
}
