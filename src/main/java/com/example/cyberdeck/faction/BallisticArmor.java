package com.example.cyberdeck.faction;

import com.example.cyberdeck.Cyberdeck;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.EquipmentAsset;

import java.util.EnumMap;
import java.util.Map;

/**
 * Custom armor materials for the two ballistic armor tiers. Both render as leather (so they accept a
 * faction dye tint) but carry netherite-grade stats. Heavy Ballistic Armor is strictly tougher than
 * Light. Materials reuse {@link EquipmentAssets#LEATHER} for the worn model and the leather repair
 * tag so they can be mended with leather.
 */
public final class BallisticArmor {
    public static final ResourceKey<EquipmentAsset> VEST_ASSET = ResourceKey.create(
            EquipmentAssets.ROOT_ID,
            Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "bulletproof_vest"));
    private BallisticArmor() {
    }

    /** Light Ballistic Armor: netherite-level protection with slightly reduced defense values. */
    public static final ArmorMaterial LIGHT = new ArmorMaterial(
            37,
            defense(2, 5, 6, 2),
            15,
            SoundEvents.ARMOR_EQUIP_NETHERITE,
            2.0f,
            0.05f,
            ItemTags.REPAIRS_LEATHER_ARMOR,
            EquipmentAssets.LEATHER);

    /** Heavy Ballistic Armor: full netherite defense and toughness, and higher knockback resistance. */
    public static final ArmorMaterial HEAVY = new ArmorMaterial(
            48,
            defense(3, 6, 8, 3),
            15,
            SoundEvents.ARMOR_EQUIP_NETHERITE,
            3.0f,
            0.15f,
            ItemTags.REPAIRS_LEATHER_ARMOR,
            EquipmentAssets.LEATHER);

    /**
     * Visible black patrol vest. The remaining light/heavy protection is carried by entity
     * attributes so tactical skins are not buried under a full four-piece armor model.
     */
    public static final ArmorMaterial VEST = new ArmorMaterial(
            37,
            defense(0, 0, 0, 0),
            15,
            SoundEvents.ARMOR_EQUIP_LEATHER,
            0.0f,
            0.0f,
            ItemTags.REPAIRS_LEATHER_ARMOR,
            VEST_ASSET);

    /** Damage retained after a Bulletproof Vest intercepts a gunshot or projectile. */
    public static final float PROJECTILE_DAMAGE_MULTIPLIER = 0.70F;

    private static Map<ArmorType, Integer> defense(int boots, int leggings, int chestplate, int helmet) {
        Map<ArmorType, Integer> map = new EnumMap<>(ArmorType.class);
        map.put(ArmorType.BOOTS, boots);
        map.put(ArmorType.LEGGINGS, leggings);
        map.put(ArmorType.CHESTPLATE, chestplate);
        map.put(ArmorType.HELMET, helmet);
        return map;
    }
}
