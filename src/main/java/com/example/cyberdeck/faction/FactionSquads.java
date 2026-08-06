package com.example.cyberdeck.faction;

import com.example.cyberdeck.weapon.GrenadeType;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MissionService;

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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Faction squad helpers: equipping a soldier's weapon + ballistic profile when it spawns, and the
 * corporate airborne reinforcement drop. Kept separate from {@link FactionEnemy} so spawn logic
 * and combat behavior stay decoupled.
 */
public final class FactionSquads {
    public static final float REINFORCEMENT_CHANCE = 0.30F;
    public static final int REINFORCEMENT_COUNT = 4;
    private static final double SKIN_DIVERSITY_RADIUS = 48.0;

    /** Chance a grenade-capable soldier actually spawns carrying grenades. */
    private static final int GRENADE_SPAWN_CHANCE = 2;    // 1-in-2 for grenade factions
    private static final int MIN_GRENADES = 1;
    private static final int MAX_GRENADES = 2;
    private static final int GENERIC_NETRUNNER_CHANCE = 6;
    /** 1-in-8 non-netrunner soldiers spawn with an {@link EnemyCyberware} elite loadout. */
    private static final int AUGMENTED_SOLDIER_CHANCE = 8;

    private FactionSquads() {
    }

    /** Rolls and applies a weapon loadout plus exact-stat, vest-only ballistic profile. */
    public static void equip(FactionEnemy enemy, Faction faction, RandomSource rng) {
        equip(enemy, faction, rng, locallyDistinctSkinVariant(enemy, rng));
    }

    /** Applies a loadout with an explicitly planned skin, used for duplicate-free patrol squads. */
    public static void equip(
            FactionEnemy enemy, Faction faction, RandomSource rng, int skinVariant) {
        equip(enemy, faction, rng, skinVariant, null);
    }

    /** Applies a loadout while reusing an already sampled spawn district when available. */
    public static void equip(FactionEnemy enemy, Faction faction, RandomSource rng,
                             int skinVariant, District sampledDistrict) {
        enemy.setArchetype(EnemyArchetype.CORPORATE);
        enemy.setCombatRole(EnemyCombatRole.STANDARD);
        enemy.setEnemyQuickhack(EnemyQuickhack.NONE);
        enemy.setFaction(faction);
        if (sampledDistrict == null) {
            enemy.assignDistrictFromPosition();
        } else {
            enemy.setDistrict(sampledDistrict);
        }
        enemy.setSkinVariant(skinVariant);
        enemy.setGrenadeCount(0);
        enemy.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        enemy.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);

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

        if (!(enemy instanceof CyberpsychoEntity)
                && rng.nextInt(GENERIC_NETRUNNER_CHANCE) == 0) {
            configureGenericNetrunner(enemy, faction, rng);
            return;
        }
        // A minority of ordinary corporate soldiers are chromed. Rare enough that a squad still
        // reads as conscripts with rifles, common enough that the player cannot assume every
        // silhouette fights the same way.
        if (!(enemy instanceof CyberpsychoEntity) && rng.nextInt(AUGMENTED_SOLDIER_CHANCE) == 0) {
            enemy.setInstalledCyberware(EnemyCyberware.rollEliteLoadout(rng));
        }
    }

    private static void configureGenericNetrunner(
            FactionEnemy enemy, Faction faction, RandomSource rng) {
        if (!(enemy.getMainHandItem().getItem() instanceof com.example.cyberdeck.weapon.GunItem)) {
            List<GunType> guns = faction.weapons();
            GunType gun = guns.get(rng.nextInt(guns.size()));
            enemy.setItemSlot(EquipmentSlot.MAINHAND,
                    new ItemStack(WeaponItems.gun(gun).get()));
        }
        enemy.setGrenadeCount(0);
        enemy.setCombatRole(EnemyCombatRole.NETRUNNER);
        enemy.setEnemyQuickhack(EnemyQuickhack.randomHostile(rng));
        enemy.setEnemyQuickhackCooldownEndTick(
                enemy.level().getGameTime() + 40L + rng.nextInt(61));
    }

    /** Applies one of the three exact R Corp paramilitary role kits. */
    public static void equipRCorp(
            FactionEnemy enemy, EnemyCombatRole role, RandomSource rng, int skinVariant) {
        equipRCorp(enemy, role, rng, skinVariant, null);
    }

    /** Applies an R Corp kit while reusing an already sampled spawn district when available. */
    public static void equipRCorp(FactionEnemy enemy, EnemyCombatRole role, RandomSource rng,
                                  int skinVariant, District sampledDistrict) {
        if (role == EnemyCombatRole.STANDARD) {
            role = EnemyCombatRole.ASSAULT;
        }
        enemy.setArchetype(EnemyArchetype.R_CORP);
        // The legacy faction remains an internal combat value only; R Corp has its own ally checks.
        enemy.setFaction(Faction.ARASAKA);
        if (sampledDistrict == null) {
            enemy.assignDistrictFromPosition();
        } else {
            enemy.setDistrict(sampledDistrict);
        }
        enemy.setSkinVariant(skinVariant);
        enemy.setCombatRole(role);
        enemy.setEnemyQuickhack(EnemyQuickhack.NONE);
        enemy.setGrenadeCount(0);
        enemy.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        enemy.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);

        switch (role) {
            case ASSAULT -> {
                enemy.setItemSlot(EquipmentSlot.MAINHAND,
                        new ItemStack(WeaponItems.gun(GunType.SARATOGA).get()));
                enemy.setItemSlot(EquipmentSlot.OFFHAND,
                        new ItemStack(WeaponItems.gun(GunType.UNITY).get()));
            }
            case SAPPER -> {
                enemy.setItemSlot(EquipmentSlot.MAINHAND,
                        new ItemStack(WeaponItems.gun(GunType.UNITY).get()));
                enemy.setGrenadeType(GrenadeType.INCENDIARY);
                enemy.setGrenadeCount(2);
            }
            case NETRUNNER -> {
                enemy.setItemSlot(EquipmentSlot.MAINHAND,
                        new ItemStack(WeaponItems.gun(GunType.YUKIMURA).get()));
                enemy.setEnemyQuickhack(EnemyQuickhack.BLIND);
                enemy.setEnemyQuickhackCooldownEndTick(
                        enemy.level().getGameTime() + 40L + rng.nextInt(61));
            }
            case STANDARD -> throw new IllegalStateException("standard R Corp role was normalized");
        }
        enemy.setDropChance(EquipmentSlot.MAINHAND, 0.15F);
        if (!enemy.getOffhandItem().isEmpty()) {
            enemy.setDropChance(EquipmentSlot.OFFHAND, 0.12F);
        }
        equipBallisticTier(enemy, rng.nextInt(3) == 0 ? "heavy" : "light");
    }

    /** Exact role composition for an authored three/five-member R Corp patrol. */
    public static List<EnemyCombatRole> rCorpRolePlan(int size) {
        return switch (size) {
            case 3 -> List.of(
                    EnemyCombatRole.ASSAULT,
                    EnemyCombatRole.SAPPER,
                    EnemyCombatRole.NETRUNNER);
            case 5 -> List.of(
                    EnemyCombatRole.ASSAULT,
                    EnemyCombatRole.ASSAULT,
                    EnemyCombatRole.ASSAULT,
                    EnemyCombatRole.SAPPER,
                    EnemyCombatRole.NETRUNNER);
            case REINFORCEMENT_COUNT -> List.of(
                    EnemyCombatRole.ASSAULT,
                    EnemyCombatRole.ASSAULT,
                    EnemyCombatRole.SAPPER,
                    EnemyCombatRole.NETRUNNER);
            default -> throw new IllegalArgumentException("unsupported R Corp squad size: " + size);
        };
    }

    /** Returns a deterministic shuffled subset, guaranteeing no repeated skin inside one squad. */
    public static List<Integer> uniqueSkinVariants(RandomSource rng, int requested) {
        return uniqueSkinVariants(rng, requested, List.of());
    }

    /**
     * Returns unique variants while preferring the least-used skins from an existing deployment.
     * This keeps each reinforcement wave unique and balances mission groups larger than eight.
     */
    public static List<Integer> uniqueSkinVariants(
            RandomSource rng, int requested, List<Integer> alreadyUsed) {
        int count = Math.min(Math.max(0, requested), FactionEnemy.TACTICAL_SKIN_COUNT);
        int[] usage = new int[FactionEnemy.TACTICAL_SKIN_COUNT];
        for (int variant : alreadyUsed) {
            if (variant >= 0 && variant < usage.length) {
                usage[variant]++;
            }
        }
        List<Integer> variants = new ArrayList<>(FactionEnemy.TACTICAL_SKIN_COUNT);
        for (int variant = 0; variant < FactionEnemy.TACTICAL_SKIN_COUNT; variant++) {
            variants.add(variant);
        }
        for (int index = variants.size() - 1; index > 0; index--) {
            int swap = rng.nextInt(index + 1);
            int value = variants.get(index);
            variants.set(index, variants.get(swap));
            variants.set(swap, value);
        }
        variants.sort(Comparator.comparingInt(variant -> usage[variant]));
        return List.copyOf(variants.subList(0, count));
    }

    private static int locallyDistinctSkinVariant(FactionEnemy enemy, RandomSource rng) {
        List<Integer> alreadyUsed = new ArrayList<>();
        UUID groupId = enemy.getAlertGroupId();
        if (groupId != null && enemy.level() instanceof ServerLevel serverLevel) {
            for (net.minecraft.world.entity.Entity entity : serverLevel.getAllEntities()) {
                if (entity instanceof FactionEnemy member
                        && member != enemy && member.isAlive()
                        && groupId.equals(member.getAlertGroupId())
                        && !(member instanceof CyberpsychoEntity)
                        && !member.isTraumaTeam() && !member.isExcision()) {
                    alreadyUsed.add(member.getSkinVariant());
                }
            }
        } else {
            for (FactionEnemy nearby : enemy.level().getEntitiesOfClass(
                    FactionEnemy.class,
                    enemy.getBoundingBox().inflate(SKIN_DIVERSITY_RADIUS),
                    candidate -> candidate != enemy && candidate.isAlive()
                            && !(candidate instanceof CyberpsychoEntity)
                            && !candidate.isTraumaTeam() && !candidate.isExcision())) {
                alreadyUsed.add(nearby.getSkinVariant());
            }
        }
        return uniqueSkinVariants(rng, 1, alreadyUsed).getFirst();
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

    /** Attempts the one persisted 30% airborne reinforcement roll for this soldier's whole squad. */
    public static boolean tryReinforcementsOnAttack(
            ServerLevel level, FactionEnemy leader, LivingEntity target) {
        return tryReinforcementsOnAttack(
                level, leader, target, level.getRandom().nextFloat());
    }

    /** Explicit-roll overload used by deterministic server simulations and regression tests. */
    public static boolean tryReinforcementsOnAttack(
            ServerLevel level, FactionEnemy leader, LivingEntity target, float roll) {
        if (!leader.canRequestReinforcements()) {
            return false;
        }
        UUID groupId = leader.getAlertGroupId();
        if (groupId == null) {
            groupId = leader.getUUID();
            leader.setAlertGroupId(groupId);
        }

        List<FactionEnemy> group = reinforcementGroup(level, leader, groupId);
        boolean entityResolved = group.stream()
                .anyMatch(FactionEnemy::hasResolvedReinforcementRoll);
        if (entityResolved) {
            if (!leader.isAmbientPatrol()) {
                ReinforcementSavedData.get(level).resolve(groupId);
            }
            group.forEach(member -> member.setReinforcementRollResolved(true));
            return false;
        }
        if (!leader.isAmbientPatrol()
                && !ReinforcementSavedData.get(level).resolve(groupId)) {
            group.forEach(member -> member.setReinforcementRollResolved(true));
            return false;
        }
        // Consume the roll before RNG or spawning so simultaneous hits and spawned members cannot
        // recursively create more drops.
        group.forEach(member -> member.setReinforcementRollResolved(true));
        if (!reinforcementRollSucceeds(roll)) {
            return false;
        }

        RandomSource rng = level.getRandom();
        Vec3 center = leader.position();
        List<Integer> waveSkins = uniqueSkinVariants(
                rng,
                REINFORCEMENT_COUNT,
                group.stream().map(FactionEnemy::getSkinVariant).toList());
        int spawned = 0;
        for (int i = 0; i < REINFORCEMENT_COUNT; i++) {
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
            reinforcement.setAlertGroupId(groupId);
            reinforcement.setAmbientPatrol(leader.isAmbientPatrol());
            reinforcement.setReinforcementRollResolved(true);
            reinforcement.setReinforcementDeployment(true);
            if (leader.isRCorp()) {
                equipRCorp(reinforcement,
                        rCorpRolePlan(REINFORCEMENT_COUNT).get(i), rng, waveSkins.get(i));
            } else {
                equip(reinforcement, leader.getFaction(), rng, waveSkins.get(i));
            }
            reinforcement.setDistrict(leader.getDistrict());
            MissionService.inheritGuardActor(leader, reinforcement);
            // Arrive already hostile so the drop is an immediate threat.
            reinforcement.trigger(level, target);
            if (level.addFreshEntity(reinforcement)) {
                spawned++;
            }
        }
        return spawned > 0;
    }

    private static List<FactionEnemy> reinforcementGroup(
            ServerLevel level, FactionEnemy leader, UUID groupId) {
        List<FactionEnemy> members = new ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity instanceof FactionEnemy member
                    && groupId.equals(member.getAlertGroupId())) {
                members.add(member);
            }
        }
        if (!members.contains(leader)) {
            members.add(leader);
        }
        return members;
    }

    public static boolean reinforcementRollSucceeds(float roll) {
        return roll >= 0.0F && roll < REINFORCEMENT_CHANCE;
    }
}
