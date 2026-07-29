package com.example.cyberdeck.cyberware;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckItems;

import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers one holdable {@link CyberwareItem} per {@link Cyberware} augmentation and exposes them
 * in the mod's creative tab. The items are the way players obtain and install cyberware.
 */
public final class CyberwareItems {
    public static final DeferredRegister.Items CYBERWARE_ITEMS =
            DeferredRegister.createItems(Cyberdeck.MODID);

    private static final Map<Cyberware, DeferredItem<Item>> ITEMS = new HashMap<>();

    static {
        for (Cyberware cw : Cyberware.VALUES) {
            DeferredItem<Item> item = CYBERWARE_ITEMS.registerItem(
                    cw.id(),
                    props -> new CyberwareItem(props, cw));
            ITEMS.put(cw, item);
        }
        addLegacyAlias("sandevistan", Cyberware.MILITECH_APOGEE);
        addLegacyAlias("militech_apogee", Cyberware.MILITECH_APOGEE);
        addLegacyAlias("militech_falcon", Cyberware.MILITECH_FALCON);
        addLegacyAlias("dynalar_sandevistan", Cyberware.DYNALAR_SANDEVISTAN);
        addLegacyAlias("zetatech_sandevistan", Cyberware.ZETATECH_SANDEVISTAN);
        addLegacyAlias("qiant_warp_dancer", Cyberware.QIANT_WARP_DANCER);
        addLegacyAlias("cyberdeck_os", Cyberware.CYBERDECK_OS);
        addLegacyAlias("gorilla_arms", Cyberware.GORILLA_ARMS);
        addLegacyAlias("mantis_blades", Cyberware.MANTIS_BLADES);
        addLegacyAlias("arm_cannon", Cyberware.ARM_CANNON);
        addLegacyAlias("smart_link", Cyberware.SMART_LINK);
        addLegacyAlias("frog_legs", Cyberware.FROG_LEGS);
        addLegacyAlias("hyena_legs", Cyberware.HYENA_LEGS);
        addLegacyAlias("thretevac", Cyberware.THRETEVAC);
        addLegacyAlias("nano_plating", Cyberware.NANO_PLATING);
        addLegacyAlias("optical_camo", Cyberware.OPTICAL_CAMO);
    }

    private CyberwareItems() {
    }

    public static DeferredItem<Item> item(Cyberware cyberware) {
        return ITEMS.get(cyberware);
    }

    private static void addLegacyAlias(String oldId, Cyberware target) {
        CYBERWARE_ITEMS.addAlias(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, oldId),
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, target.id()));
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
