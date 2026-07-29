package com.example.cyberdeck.cyberware;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckItems;

import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers one holdable {@link CyberwareItem} per {@link Cyberware} augmentation and exposes them
 * in the mod's creative tab. The items are the way players obtain and install cyberware.
 */
public final class CyberwareItems {
    public static final DeferredRegister.Items CYBERWARE_ITEMS =
            DeferredRegister.createItems(Cyberdeck.MODID);

    private static final Map<Cyberware, DeferredItem<Item>> ITEMS = new EnumMap<>(Cyberware.class);

    static {
        for (Cyberware cw : Cyberware.VALUES) {
            DeferredItem<Item> item = CYBERWARE_ITEMS.registerItem(
                    cw.id(),
                    props -> new CyberwareItem(props, cw));
            ITEMS.put(cw, item);
        }
    }

    private CyberwareItems() {
    }

    public static DeferredItem<Item> item(Cyberware cyberware) {
        return ITEMS.get(cyberware);
    }

    /** Adds all cyberware items to the mod's dedicated creative tab. */
    public static void addToTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CyberdeckItems.CYBERDECK_TAB.getKey()) {
            for (Cyberware cw : Cyberware.VALUES) {
                event.accept(ITEMS.get(cw).get());
            }
        }
    }
}
