package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registers the native Project Moon chunk generator codec. */
public final class MegacityChunkGenerators {
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> TYPES =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, Cyberdeck.MODID);

    static {
        TYPES.register("megacity", () -> MegacityChunkGenerator.CODEC);
    }

    private MegacityChunkGenerators() {
    }

    public static void register(IEventBus eventBus) {
        TYPES.register(eventBus);
    }
}
