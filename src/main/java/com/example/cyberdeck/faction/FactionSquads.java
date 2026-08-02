package com.example.cyberdeck.faction;

import com.example.cyberdeck.weapon.GrenadeType;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Faction squad helpers: equipping a soldier's weapon + ballistic profile when it spawns, and the Kang
 * Tao airborne reinforcement drop. Kept separate from {@link FactionEnemy} so spawn logic and combat
 * behavior stay decoupled.
 */
public final class FactionSquads {
    /** Tracks Kang Tao squads (by leader UUID chain) that have already used their one reinforcement. */
    private static final java.util.Set<java.util.UUID> KANG_TAO_REINFORCED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final int KANG_TAO_REINFORCEMENTS = 4;

    /** Chance a grenade-capable soldier actually spawns carrying grenades. */
    private static final int GRENADE_SPAWN_CHANCE = 2;    // 1-in-2 for grenade factions
    private static final int MIN_GRENADES = 1;
    private static final int MAX_GRENADES = 2;

    private FactionSquads() {
    }

    /** Rolls and applies a weapon loadout plus exact-stat, vest-only ballistic profile. */
    public static void equip(FactionEnemy enemy, Faction faction, RandomSource rng) {
        enemy.setFaction(faction);
        enemy.assignDistrictFromPosition();
        enemy.setSkinVariant(rng.nextInt(FactionEnemy.TACTICAL_SKIN_COUNT));

        // Primary weapon: a cyberpunk gun, or (Arasaka only) a melee sword.
        List<GunType> guns = faction.weapons();
        if (faction.canUseSword() && rng.nextInt(3) == 0) {
            enemy.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
        } else {
            GunType gun = guns.get(rng.nextInt(guns.size()));
            enemy.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(WeaponItems.gun(gun).get()));
        }
        enemy.setDropChance(EquipmentSlot.MAINHAND, 0.15f);

        // Every gunner carries a sidearm so Weapon Glitch has a reliable primary-to-secondary
        // transition. Sword specialists stay melee-only rather than conjuring a gun when hacked.
        List<GunType> sidearms = faction.sidearms();
        if (enemy.getMainHandItem().getItem() instanceof com.example.cyberdeck.weapon.GunItem
                && !sidearms.isEmpty()) {
            GunType sidearm = sidearms.get(rng.nextInt(sidearms.size()));
            enemy.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(WeaponItems.gun(sidearm).get()));
            enemy.setDropChance(EquipmentSlot.OFFHAND, 0.12f);
        }

        // Grenades: grenade-capable factions have a chance to spawn with a couple to throw.
        if (faction.usesGrenades() && rng.nextInt(GRENADE_SPAWN_CHANCE) == 0) {
            enemy.setGrenadeType(rng.nextBoolean() ? GrenadeType.POISON : GrenadeType.INCENDIARY);
            enemy.setGrenadeCount(MIN_GRENADES + rng.nextInt(MAX_GRENADES - MIN_GRENADES + 1));
        }

        // Ballistics: heavier tier is rarer, but both tiers render only the common black vest.
        String tier = rng.nextInt(3) == 0 ? "heavy" : "light";
        equipBallisticTier(enemy, tier);
    }

    public static void equipBallisticTier(FactionEnemy enemy, String tier) {
        enemy.setBallisticTier(tier);
        enemy.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        enemy.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        enemy.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        enemy.setItemSlot(EquipmentSlot.CHEST,
                new ItemStack(WeaponItems.BULLETPROOF_VEST.get()));
        enemy.setDropChance(EquipmentSlot.CHEST, 0.28f);
    }

    /** Restores current loadouts and upgrades persistent legacy four-piece faction armor. */
    static void restoreBallisticLoadout(FactionEnemy enemy, String savedTier) {
        if ("heavy".equals(savedTier) || "light".equals(savedTier)) {
            enemy.setBallisticTier(savedTier);
            enemy.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            enemy.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            enemy.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            if (enemy.getItemBySlot(EquipmentSlot.CHEST)
                    .is(WeaponItems.BULLETPROOF_VEST.get())) {
                enemy.setDropChance(EquipmentSlot.CHEST, 0.28f);
            }
            return;
        }
        String legacyTier = inferLegacyTier(enemy);
        if (!legacyTier.isEmpty()) {
            equipBallisticTier(enemy, legacyTier);
        }
    }

    private static String inferLegacyTier(FactionEnemy enemy) {
        for (Faction faction : Faction.VALUES) {
            for (ArmorType type : new ArmorType[]{
                    ArmorType.HELMET, ArmorType.CHESTPLATE,
                    ArmorType.LEGGINGS, ArmorType.BOOTS}) {
                EquipmentSlot slot = type == ArmorType.HELMET ? EquipmentSlot.HEAD
                        : type == ArmorType.CHESTPLATE ? EquipmentSlot.CHEST
                        : type == ArmorType.LEGGINGS ? EquipmentSlot.LEGS
                        : EquipmentSlot.FEET;
                if (enemy.getItemBySlot(slot).is(WeaponItems.armor("heavy", faction, type).get())) {
                    return "heavy";
                }
            }
        }
        for (Faction faction : Faction.VALUES) {
            for (ArmorType type : new ArmorType[]{
                    ArmorType.HELMET, ArmorType.CHESTPLATE,
                    ArmorType.LEGGINGS, ArmorType.BOOTS}) {
                EquipmentSlot slot = type == ArmorType.HELMET ? EquipmentSlot.HEAD
                        : type == ArmorType.CHESTPLATE ? EquipmentSlot.CHEST
                        : type == ArmorType.LEGGINGS ? EquipmentSlot.LEGS
                        : EquipmentSlot.FEET;
                if (enemy.getItemBySlot(slot).is(WeaponItems.armor("light", faction, type).get())) {
                    return "light";
                }
            }
        }
        return "";
    }

    /**
     * Kang Tao only: drop {@value #KANG_TAO_REINFORCEMENTS} additional soldiers from the sky near the
     * fight, once per squad. The squad is identified by the triggering leader so it can never fire
     * more than once.
     */
    public static void tryKangTaoReinforcement(ServerLevel level, FactionEnemy leader,
                                               LivingEntity target, int simultaneous) {
        if (!KANG_TAO_REINFORCED.add(leader.getUUID())) {
            return; // already reinforced for this leader
        }
        RandomSource rng = level.getRandom();
        Vec3 center = leader.position();
        for (int i = 0; i < KANG_TAO_REINFORCEMENTS; i++) {
            double ox = (rng.nextDouble() - 0.5) * 8.0;
            double oz = (rng.nextDouble() - 0.5) * 8.0;
            BlockPos drop = BlockPos.containing(center.x + ox, center.y + 14, center.z + oz);

            FactionEnemy reinforcement = FactionEntities.FACTION_ENEMY.get().create(level,
                    EntitySpawnReason.EVENT);
            if (reinforcement == null) {
                continue;
            }
            reinforcement.snapTo(drop.getX() + 0.5, drop.getY(), drop.getZ() + 0.5,
                    rng.nextFloat() * 360.0f, 0.0f);
            reinforcement.finalizeSpawn(level, level.getCurrentDifficultyAt(drop),
                    EntitySpawnReason.EVENT, null);
            reinforcement.setHome(BlockPos.containing(center.x, center.y, center.z));
            reinforcement.setAlertGroupId(leader.getAlertGroupId());
            equip(reinforcement, Faction.KANG_TAO, rng);
            // Arrive already hostile so the drop is an immediate threat.
            reinforcement.trigger(level, target);
            level.addFreshEntity(reinforcement);
        }
    }

    /** Clears reinforcement bookkeeping (used on world unload to avoid leaking UUIDs). */
    public static void reset() {
        KANG_TAO_REINFORCED.clear();
    }
}
