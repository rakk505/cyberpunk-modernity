package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Mission-owned world blocks and inert objective items. */
public final class MissionBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cyberdeck.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cyberdeck.MODID);

    public static final DeferredBlock<DataTerminalBlock> DATA_TERMINAL = BLOCKS.registerBlock(
            "data_terminal",
            DataTerminalBlock::new,
            properties -> properties
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(-1.0F, 3_600_000.0F)
                    .sound(SoundType.METAL)
                    .lightLevel(state -> 9)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK));

    public static final DeferredBlock<DeliveryTerminalBlock> DELIVERY_TERMINAL =
            BLOCKS.registerBlock(
                    "delivery_terminal",
                    DeliveryTerminalBlock::new,
                    properties -> properties
                            .mapColor(MapColor.COLOR_YELLOW)
                            .strength(-1.0F, 3_600_000.0F)
                            .sound(SoundType.METAL)
                            .lightLevel(state -> 7)
                            .noOcclusion()
                            .pushReaction(PushReaction.BLOCK));

    public static final DeferredItem<BlockItem> DATA_TERMINAL_ITEM =
            ITEMS.registerSimpleBlockItem(DATA_TERMINAL);

    /** Inert, recipe-less proof of custody for delivery contracts. */
    public static final DeferredItem<Item> CONTRACT_CARGO =
            ITEMS.registerItem("contract_cargo", Item::new);

    private MissionBlocks() {
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
    }
}
