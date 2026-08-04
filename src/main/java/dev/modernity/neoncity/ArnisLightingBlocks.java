package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Generated-city lighting blocks; intentionally omitted from the creative inventory. */
public final class ArnisLightingBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Cyberdeck.MODID);

    public static final DeferredBlock<CamouflagedSeaLanternBlock> CAMOUFLAGED_SEA_LANTERN =
            BLOCKS.registerBlock(
                    "camouflaged_sea_lantern",
                    CamouflagedSeaLanternBlock::new,
                    () -> BlockBehaviour.Properties.ofFullCopy(Blocks.SEA_LANTERN));

    private ArnisLightingBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
