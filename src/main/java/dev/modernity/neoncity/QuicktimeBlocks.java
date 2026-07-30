package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Deferred registrations for the Quicktime station block and its inventory item. */
public final class QuicktimeBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cyberdeck.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cyberdeck.MODID);

    public static final DeferredBlock<QuicktimeStationBlock> QUICKTIME_STATION = BLOCKS.registerBlock(
            "quicktime_station",
            QuicktimeStationBlock::new,
            properties -> properties
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(4.0F, 8.0F)
                    .sound(SoundType.METAL)
                    .lightLevel(state -> 8)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)
    );

    public static final DeferredItem<BlockItem> QUICKTIME_STATION_ITEM =
            ITEMS.registerSimpleBlockItem(QUICKTIME_STATION);

    private QuicktimeBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
