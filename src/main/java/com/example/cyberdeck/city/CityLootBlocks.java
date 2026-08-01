package com.example.cyberdeck.city;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Generated city loot blocks. They intentionally have no block items or recipes. */
public final class CityLootBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Cyberdeck.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cyberdeck.MODID);

    public static final DeferredBlock<BlackLootCacheBlock> BLACK_LOOT_CACHE =
            BLOCKS.registerBlock(
                    "black_loot_cache",
                    BlackLootCacheBlock::new,
                    properties -> properties
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(4.0F, 12.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .pushReaction(PushReaction.BLOCK));

    public static final DeferredBlock<AmmoCacheBlock> AMMO_CACHE =
            BLOCKS.registerBlock(
                    "ammo_cache",
                    AmmoCacheBlock::new,
                    properties -> properties
                            .mapColor(MapColor.COLOR_GRAY)
                            .strength(3.0F, 8.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .pushReaction(PushReaction.BLOCK));

    public static final DeferredHolder<BlockEntityType<?>,
            BlockEntityType<BlackLootCacheBlockEntity>> BLACK_LOOT_CACHE_ENTITY =
            BLOCK_ENTITY_TYPES.register(
                    "black_loot_cache",
                    () -> new BlockEntityType<>(
                            BlackLootCacheBlockEntity::new, BLACK_LOOT_CACHE.get()));

    private CityLootBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
