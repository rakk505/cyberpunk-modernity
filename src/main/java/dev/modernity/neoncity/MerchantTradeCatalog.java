package dev.modernity.neoncity;

import com.example.cyberdeck.CyberdeckItems;
import com.example.cyberdeck.economy.Emmies;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.cyberware.CyberwareTier;
import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import com.modernity.vehicle_mod.vehicle_mod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.ItemLike;

/** Complete, stable offer lists for every trading merchant role. */
final class MerchantTradeCatalog {
    private static final int MAX_USES = 32_000;
    private static final ArmorType[] ARMOR_TYPES = {
            ArmorType.HELMET, ArmorType.CHESTPLATE, ArmorType.LEGGINGS, ArmorType.BOOTS
    };

    private MerchantTradeCatalog() {
    }

    static List<MerchantOffer> offers(MerchantTruckLibrary.MerchantRole role) {
        return switch (role) {
            case GUN -> gunOffers();
            case CYBERWARE -> cyberwareOffers();
            case CLOTHING -> clothingOffers();
            case CONSUMABLE -> consumableOffers();
            case QUEST -> List.of();
            case VEHICLE -> vehicleOffers();
        };
    }

    static List<Item> resultItems(MerchantTruckLibrary.MerchantRole role) {
        return offers(role).stream().map(offer -> offer.getResult().getItem()).toList();
    }

    private static List<MerchantOffer> gunOffers() {
        List<MerchantOffer> offers = new ArrayList<>();
        for (GunType gun : GunType.values()) {
            int price = gun.isTech() ? 38 : 22;
            if (gun == GunType.SNIPER || gun == GunType.GRAD || gun == GunType.TECH_SNIPER
                    || gun == GunType.TECH_GRAD) {
                price += 10;
            }
            offers.add(offer(WeaponItems.gun(gun).get(), 1, price));
        }
        for (AmmoType ammo : AmmoType.values()) {
            offers.add(offer(AmmoItems.item(ammo).get(), 32, ammo == AmmoType.HEAVY ? 6 : 4));
        }
        return List.copyOf(offers);
    }

    private static List<MerchantOffer> cyberwareOffers() {
        List<MerchantOffer> offers = new ArrayList<>();
        for (Cyberware cyberware : Cyberware.VALUES) {
            if (cyberware.tier().rank() >= CyberwareTier.T4.rank()) {
                continue;
            }
            int price = 8 + cyberware.tier().rank() * 5;
            offers.add(offer(CyberwareItems.item(cyberware).get(), 1, price));
        }
        return List.copyOf(offers);
    }

    private static List<MerchantOffer> clothingOffers() {
        List<MerchantOffer> offers = new ArrayList<>();
        offers.add(offer(CyberdeckItems.CYBERDECK.get(), 1, 12));
        for (String tier : List.of("light", "heavy")) {
            int price = tier.equals("heavy") ? 28 : 16;
            for (Faction faction : Faction.VALUES) {
                for (ArmorType type : ARMOR_TYPES) {
                    offers.add(offer(WeaponItems.armor(tier, faction, type).get(), 1, price));
                }
            }
        }
        return List.copyOf(offers);
    }

    private static List<MerchantOffer> consumableOffers() {
        return List.of(
                offer(CyberdeckItems.SLOP.get(), 4, 3),
                offer(Items.BREAD, 6, 2),
                offer(Items.BAKED_POTATO, 8, 2),
                offer(Items.COOKED_BEEF, 4, 4),
                offer(Items.GOLDEN_CARROT, 3, 5),
                offer(Items.HONEY_BOTTLE, 2, 4),
                offer(Items.MILK_BUCKET, 1, 3),
                offer(Items.GOLDEN_APPLE, 1, 14));
    }

    private static List<MerchantOffer> vehicleOffers() {
        return List.of(
                offer(vehicle_mod.GASOLINE.get(), 8, 4),
                offer(vehicle_mod.MOTORBIKE_ITEM.get(), 1, 18),
                offer(vehicle_mod.CYBERPUNK_MOTORBIKE_ITEM.get(), 1, 24),
                offer(vehicle_mod.HARLEY_MOTORCYCLE_ITEM.get(), 1, 28),
                offer(vehicle_mod.DATSUN_240Z_ITEM.get(), 1, 32),
                offer(vehicle_mod.TURBOWAGON_ITEM.get(), 1, 38),
                offer(vehicle_mod.JEEP_WRANGLER_ITEM.get(), 1, 42),
                offer(vehicle_mod.DUNE_BUGGY_ITEM.get(), 1, 46),
                offer(vehicle_mod.ROAD_ROLLER_ITEM.get(), 1, 50),
                offer(vehicle_mod.BMW_M3_GTR_ITEM.get(), 1, 56),
                offer(vehicle_mod.ORANGE_HYPERCAR_ITEM.get(), 1, 64));
    }

    private static MerchantOffer offer(ItemLike result, int count, int price) {
        return new MerchantOffer(
                new ItemCost(Emmies.item(), price),
                new ItemStack(result, count),
                MAX_USES,
                1,
                0.0F);
    }
}
