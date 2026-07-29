package com.example.cyberdeck.weapon;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.CyberdeckItems;
import com.example.cyberdeck.faction.BallisticArmor;
import com.example.cyberdeck.faction.Faction;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registers all weapons, grenades and faction ballistic armor, and exposes them in the mod's
 * creative tab. Armor is generated as a full set (helmet/chestplate/leggings/boots) per tier per
 * faction, each dyed to its faction color but carrying netherite-grade stats.
 */
public final class WeaponItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cyberdeck.MODID);

    // --- Guns ---
    private static final Map<GunType, DeferredItem<Item>> GUNS = new EnumMap<>(GunType.class);

    static {
        for (GunType gun : GunType.values()) {
            GUNS.put(gun, ITEMS.registerItem(gun.id(), props -> new GunItem(props, gun)));
        }
    }

    // --- Grenades ---
    public static final DeferredItem<Item> INCENDIARY_GRENADE =
            ITEMS.registerItem(GrenadeType.INCENDIARY.id(),
                    props -> new GrenadeItem(props, GrenadeType.INCENDIARY));
    public static final DeferredItem<Item> POISON_GRENADE =
            ITEMS.registerItem(GrenadeType.POISON.id(),
                    props -> new GrenadeItem(props, GrenadeType.POISON));

    // --- Ballistic armor ---
    /** Armor lookup: tier ("light"/"heavy") x faction x ArmorType -> item. */
    private static final Map<String, DeferredItem<Item>> ARMOR = new java.util.HashMap<>();

    static {
        registerArmorTier("light", BallisticArmor.LIGHT);
        registerArmorTier("heavy", BallisticArmor.HEAVY);
    }

    private static void registerArmorTier(String tier, ArmorMaterial material) {
        for (Faction faction : Faction.VALUES) {
            for (ArmorType type : new ArmorType[]{
                    ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS}) {
                String id = tier + "_ballistic_" + type.getName() + "_" + faction.id();
                int color = faction.color();
                DeferredItem<Item> item = ITEMS.registerItem(id, props -> new Item(props
                        .humanoidArmor(material, type)
                        .component(DataComponents.DYED_COLOR, new DyedItemColor(color))));
                ARMOR.put(armorKey(tier, faction, type), item);
            }
        }
    }

    private static String armorKey(String tier, Faction faction, ArmorType type) {
        return tier + ":" + faction.id() + ":" + type.getName();
    }

    private WeaponItems() {
    }

    public static DeferredItem<Item> gun(GunType gun) {
        return GUNS.get(gun);
    }

    public static DeferredItem<Item> armor(String tier, Faction faction, ArmorType type) {
        return ARMOR.get(armorKey(tier, faction, type));
    }

    /** All registered items in a stable order, for the creative tab. */
    public static List<DeferredItem<Item>> all() {
        List<DeferredItem<Item>> list = new ArrayList<>();
        for (GunType gun : GunType.values()) {
            list.add(GUNS.get(gun));
        }
        list.add(INCENDIARY_GRENADE);
        list.add(POISON_GRENADE);
        list.addAll(ARMOR.values());
        return list;
    }

    public static void addToTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CyberdeckItems.CYBERDECK_TAB.getKey()) {
            for (DeferredItem<Item> item : all()) {
                event.accept(item.get());
            }
            for (AmmoType ammo : AmmoType.values()) {
                event.accept(AmmoItems.item(ammo).get());
            }
        }
    }
}
