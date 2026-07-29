package com.example.cyberdeck;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
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

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CYBERDECK_TAB =
            CREATIVE_MODE_TABS.register("cyberdeck_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cyberdeck"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> QuickhackItems.QUICKHACK_HEAD.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(CYBERDECK.get());
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
