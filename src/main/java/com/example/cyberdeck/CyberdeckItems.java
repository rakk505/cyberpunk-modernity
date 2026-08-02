package com.example.cyberdeck;

import dev.modernity.neoncity.QuicktimeBlocks;
import dev.modernity.neoncity.MissionBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class CyberdeckItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cyberdeck.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Cyberdeck.MODID);

    // Legacy cosmetic helmet retained for existing worlds; scanner access comes from installed OS assets.
    public static final DeferredItem<Item> CYBERDECK = ITEMS.registerItem("cyberdeck",
            props -> new Item(props
                    .humanoidArmor(ArmorMaterials.IRON, ArmorType.HELMET)
                    .stacksTo(1)));

    public static final DeferredItem<Item> SLOP = ITEMS.registerItem("slop",
            props -> new SlopItem(props.food(new FoodProperties.Builder()
                    .nutrition(6)
                    .saturationModifier(0.5F)
                    .alwaysEdible()
                    .build())));

    /** Hidden compatibility item; player-held stacks migrate one-for-one into emeralds. */
    public static final DeferredItem<Item> LEGACY_EMMIES = ITEMS.registerItem("emmies",
            com.example.cyberdeck.economy.EmmiesItem::new);

    /** Permanently raises the holder's maximum cyberware capacity on use. */
    public static final DeferredItem<Item> CYBERWARE_SHARD = ITEMS.registerItem("cyberware_shard",
            props -> new com.example.cyberdeck.economy.CyberwareShardItem(props.stacksTo(16)));

    /** Cracks open into a stack of emmies, increasing the player's balance. */
    public static final DeferredItem<Item> MONEY_SHARD = ITEMS.registerItem("money_shard",
            props -> new com.example.cyberdeck.economy.MoneyShardItem(props.stacksTo(16)));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CYBERDECK_TAB =
            CREATIVE_MODE_TABS.register("cyberdeck_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cyberdeck"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> QuickhackItems.QUICKHACK_HEAD.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(CYBERDECK.get());
                        output.accept(SLOP.get());
                        output.accept(MONEY_SHARD.get());
                        output.accept(CYBERWARE_SHARD.get());
                        output.accept(QuicktimeBlocks.QUICKTIME_STATION_ITEM.get());
                        output.accept(MissionBlocks.DATA_TERMINAL_ITEM.get());
                        output.accept(QuickhackItems.QUICKHACK_HEAD.get());
                        for (com.example.cyberdeck.skill.Skill skill
                                : com.example.cyberdeck.skill.Skill.VALUES) {
                            net.minecraft.world.item.Item item = QuickhackItems.item(skill);
                            if (item != null) {
                                output.accept(item);
                            }
                        }
                    })
                    .build());

    private CyberdeckItems() {
    }

    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(CYBERDECK);
        }
    }
}
