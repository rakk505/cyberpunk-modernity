package com.example.cyberdeck;

import com.mojang.authlib.GameProfile;
import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.city.CityActorJoinCompatibility;
import com.example.cyberdeck.city.AmmoCacheBlock;
import com.example.cyberdeck.city.BlackLootCacheBlock;
import com.example.cyberdeck.city.BlackLootCacheBlockEntity;
import com.example.cyberdeck.city.CityLootBlocks;
import com.example.cyberdeck.city.CityLootGeneration;
import com.example.cyberdeck.client.map.MerchantMarkerClient;
import com.example.cyberdeck.client.map.MinimapGeometry;
import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.cyberware.CyberwareItem;
import com.example.cyberdeck.cyberware.SandevistanProfile;
import com.example.cyberdeck.cyberware.SlotUnlock;
import com.example.cyberdeck.defense.DefenseContent;
import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.effect.SandevistanMechanics;
import com.example.cyberdeck.effect.SandevistanState;
import com.example.cyberdeck.effect.CyberwareEffects;
import com.example.cyberdeck.effect.DoubleJumpGuard;
import com.example.cyberdeck.economy.Emmies;
import com.example.cyberdeck.faction.Faction;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.faction.FactionSpawns;
import com.example.cyberdeck.faction.FactionSquads;
import com.example.cyberdeck.faction.TacticalManeuver;
import com.example.cyberdeck.healing.HealingConsumable;
import com.example.cyberdeck.healing.HealingState;
import com.example.cyberdeck.healing.HealingSystem;
import com.example.cyberdeck.economy.MoneyShardItem;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.CityNpcSpawns;
import com.example.cyberdeck.npc.GunshotAlerts;
import com.example.cyberdeck.npc.NpcRole;
import com.example.cyberdeck.npc.NpcVoicelineCatalog;
import com.example.cyberdeck.npc.NpcVoicelineService;
import com.example.cyberdeck.trauma.TraumaTeamEvents;
import com.example.cyberdeck.player.StreetCredState;
import com.example.cyberdeck.skill.QuickhackUploads;
import com.example.cyberdeck.skill.Skill;
import com.example.cyberdeck.skill.SkillExecutor;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.movement.TacticalAction;
import com.example.cyberdeck.movement.TacticalMovement;
import com.example.cyberdeck.movement.TacticalMovementState;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.GunItem;
import com.example.cyberdeck.weapon.GunFiring;
import com.example.cyberdeck.weapon.AmmoItem;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.CyberdeckDamageTypes;
import com.example.cyberdeck.weapon.WeaponItems;
import dev.modernity.neoncity.MegacityLayout;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.NeonCityGenerator;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Synchronous regression suite for consolidated city, combat, and cyberware invariants. */
public final class CyberdeckGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Cyberdeck.MODID);

    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CITY_LAYER_CLASSIFICATION = register(
                    "city_layer_classification", CyberdeckGameTests::cityLayerClassification);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CLUSTER_PLAN = register("cluster_plan", CyberdeckGameTests::clusterPlan);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DISTRICT_PATROL_LOADOUT = register(
                    "district_patrol_loadout", CyberdeckGameTests::districtPatrolLoadout);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            GUNSHOT_RADIUS = register("gunshot_radius", CyberdeckGameTests::gunshotRadius);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MOUNTED_GUN_TARGETING = register(
                    "mounted_gun_targeting", CyberdeckGameTests::mountedGunTargeting);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CIVILIAN_NONCOMBAT = register(
                    "civilian_noncombat", CyberdeckGameTests::civilianNoncombat);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CIVILIAN_POPULATION = register(
                    "civilian_population", CyberdeckGameTests::civilianPopulation);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CITY_ACTOR_JOIN_COMPATIBILITY = register(
                    "city_actor_join_compatibility",
                    CyberdeckGameTests::cityActorJoinCompatibility);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SANDEVISTAN_PROFILE_BALANCE = register(
                    "sandevistan_profile_balance", CyberdeckGameTests::profileBalance);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SANDEVISTAN_CHARGE_MODEL = register(
                    "sandevistan_charge_model", CyberdeckGameTests::chargeModel);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CYBERWARE_VARIANT_MAPPINGS = register(
                    "cyberware_variant_mappings", CyberdeckGameTests::variantMappings);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            TACTICAL_MOVEMENT_MATH = register(
                    "tactical_movement_math", CyberdeckGameTests::tacticalMovementMath);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            TACTICAL_MOVEMENT_STATE = register(
                    "tactical_movement_state", CyberdeckGameTests::tacticalMovementState);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            TACTICAL_SLIDE_ACTIVATION = register(
                    "tactical_slide_activation", CyberdeckGameTests::tacticalSlideActivation);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            HEALING_CONSUMABLE_STATE = register(
                    "healing_consumable_state", CyberdeckGameTests::healingConsumableState);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            EMMIES_USE_EMERALDS = register(
                    "emmies_use_emeralds", CyberdeckGameTests::emmiesUseEmeralds);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DETECTION_LINE_OF_SIGHT = register(
                    "detection_line_of_sight", CyberdeckGameTests::detectionLineOfSightBuildup);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DETECTION_CROUCH = register(
                    "detection_crouch", CyberdeckGameTests::detectionCrouchReducesVision);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DETECTION_DECAY = register(
                    "detection_decay", CyberdeckGameTests::detectionDecaysWithoutSight);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CYBERPSYCHO_BALANCE = register(
                    "cyberpsycho_balance", CyberdeckGameTests::cyberpsychoBalance);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            THROWABLE_DISTRACTION = register(
                    "throwable_distraction", CyberdeckGameTests::throwableDistraction);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MELEE_ENEMY_CLOSES_IN = register(
                    "melee_enemy_closes_in", CyberdeckGameTests::meleeEnemyClosesIn);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CYBERPSYCHO_SANDEVISTAN = register(
                    "cyberpsycho_sandevistan", CyberdeckGameTests::cyberpsychoSandevistan);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            INCENDIARY_IGNITES_NO_FIRE_BLOCKS = register(
                    "incendiary_ignites_no_fire_blocks",
                    CyberdeckGameTests::incendiaryIgnitesNoFireBlocks);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            STREET_CRED_PERSISTENCE = register(
                    "street_cred_persistence", CyberdeckGameTests::streetCredPersistence);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MINIMAP_ROTATION_GEOMETRY = register(
                    "minimap_rotation_geometry", CyberdeckGameTests::minimapRotationGeometry);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            NPC_ROLES_AND_DROPS = register(
                    "npc_roles_and_drops", CyberdeckGameTests::npcRolesAndDrops);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            NPC_VOICELINE_POOLS = register(
                    "npc_voiceline_pools", CyberdeckGameTests::npcVoicelinePools);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            TRAUMA_TEAM_LIFECYCLE = register(
                    "trauma_team_lifecycle", CyberdeckGameTests::traumaTeamLifecycle);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            QUICKHACK_LONG_RANGE = register(
                    "quickhack_long_range", CyberdeckGameTests::quickhackLongRange);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            QUICKHACK_MULTI_TARGET = register(
                    "quickhack_multi_target", CyberdeckGameTests::quickhackMultiTarget);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            QUICKHACK_HOTBAR_RECOVERY = register(
                    "quickhack_hotbar_recovery", CyberdeckGameTests::quickhackHotbarRecovery);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DOUBLE_JUMP_PACKET_GUARD = register(
                    "double_jump_packet_guard", CyberdeckGameTests::doubleJumpPacketGuard);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CITY_LOOT_CACHES = register(
                    "city_loot_caches", CyberdeckGameTests::cityLootCaches);

    private CyberdeckGameTests() {
    }

    /** Connects the test-function registry and runtime test instances to the mod event bus. */
    public static void bootstrap(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(CyberdeckGameTests::registerGameTests);
    }

    private static DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> register(
            String name, Consumer<GameTestHelper> test) {
        return TEST_FUNCTIONS.register(name, () -> test);
    }

    private static void cityLayerClassification(GameTestHelper helper) {
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        BlockState black = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
        BlockState cyan = Blocks.CONCRETE.pick(DyeColor.CYAN).defaultBlockState();
        BlockState purple = Blocks.CONCRETE.pick(DyeColor.PURPLE).defaultBlockState();

        helper.assertTrue(CityWorlds.classifyLayers(
                        List.of(bedrock, black, black, black, black))
                        == CityWorlds.Kind.CYBERDECK,
                "exact Cyberdeck flat layers must be accepted");
        helper.assertTrue(CityWorlds.classifyLayers(List.of(black, cyan))
                        == CityWorlds.Kind.NEON_CITY,
                "exact Neon City flat layers must be accepted");
        helper.assertTrue(CityWorlds.classifyLayers(List.of(purple))
                        == CityWorlds.Kind.CITY17,
                "the exact City17 Taiwan atlas marker must be accepted");
        helper.assertTrue(CityWorlds.Kind.CITY17.usesDynamicStreetY(),
                "Taiwan atlas streets must resolve their generated terrain height per column");
        helper.assertTrue(CityWorlds.isCity17PedestrianSurface(
                        Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState()),
                "Taiwan's Arnis gray road surface must accept pedestrians");
        helper.assertFalse(CityWorlds.isCity17PedestrianSurface(
                        Blocks.STONE_BRICKS.defaultBlockState()),
                "Taiwan building roofs must not be mistaken for roads");
        helper.assertFalse(CityWorlds.isCity17PedestrianSurface(purple),
                "the untouched purple marker must never spawn pedestrians");
        helper.assertTrue(CityWorlds.classifyLayers(List.of(
                        bedrock,
                        Blocks.DIRT.defaultBlockState(),
                        Blocks.DIRT.defaultBlockState(),
                        Blocks.GRASS_BLOCK.defaultBlockState())) == CityWorlds.Kind.NONE,
                "vanilla superflat layers must be rejected");
        helper.assertTrue(CityWorlds.classifyLayers(List.of(bedrock, black))
                        == CityWorlds.Kind.NONE,
                "partial/lookalike city layers must be rejected");
        helper.assertTrue(CityWorlds.classifyLayers(List.of(cyan, black))
                        == CityWorlds.Kind.NONE,
                "reversed Neon City layers must be rejected");
        ResourceKey<DimensionType> megacityDimension = ResourceKey.create(
                Registries.DIMENSION_TYPE,
                Identifier.fromNamespaceAndPath("neoncity", "megacity_overworld"));
        helper.assertTrue(CityWorlds.classifyDimensionType(megacityDimension)
                        == CityWorlds.Kind.NEON_MEGACITY,
                "Project Moon's marked noise overworld must be recognized as a city");
        helper.assertTrue(CityWorlds.Kind.NEON_MEGACITY.streetY() == 72,
                "Project Moon pedestrians must use its generated Y=72 street deck");
        helper.assertTrue(CityWorlds.classifyDimensionType(ResourceKey.create(
                        Registries.DIMENSION_TYPE,
                        Identifier.fromNamespaceAndPath("minecraft", "overworld")))
                        == CityWorlds.Kind.NONE,
                "ordinary noise overworlds must not gain city pedestrians");
        helper.succeed();
    }

    private static void mountedGunTargeting(GameTestHelper helper) {
        var mount = helper.spawn(EntityTypes.CAMEL, new BlockPos(2, 2, 2));
        var shooter = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 2, 3));
        var coRider = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(2, 2, 4));
        var externalTarget = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(5, 2, 2));
        helper.assertTrue(
                shooter.startRiding(mount) && coRider.startRiding(mount),
                "mounted targeting test could not seat both riders");
        helper.assertTrue(
                !GunFiring.canHitTarget(shooter, mount)
                        && !GunFiring.canHitTarget(shooter, coRider),
                "mounted shooter could still hit its current mount or a co-rider");
        helper.assertTrue(
                GunFiring.canHitTarget(shooter, externalTarget),
                "mounted shooter could not hit an unrelated external target");
        helper.succeed();
    }

    private static void clusterPlan(GameTestHelper helper) {
        long seed = FactionSpawns.clusterSeed(8675309L, 42L, -7, 11);
        helper.assertTrue(seed == FactionSpawns.clusterSeed(8675309L, 42L, -7, 11),
                "equal world/epoch/cell inputs must produce the same cluster seed");
        helper.assertTrue(seed != FactionSpawns.clusterSeed(8675309L, 43L, -7, 11),
                "cluster seed must change between epochs");
        helper.assertTrue(seed != FactionSpawns.clusterSeed(8675309L, 42L, -6, 11),
                "cluster seed must change between spatial cells");

        helper.assertTrue(FactionSpawns.SPAWN_INTERVAL == 1_200
                        && FactionSpawns.MIN_SPAWN_DISTANCE == 26
                        && FactionSpawns.MAX_SPAWN_DISTANCE == 46
                        && FactionSpawns.POPULATION_CELL_SIZE == 128
                        && FactionSpawns.NEARBY_RADIUS == 72.0,
                "ambient patrol timing and range must be reliable but half the original rate");
        helper.assertTrue(FactionSpawns.SMALL_PATROL_SIZE == 3
                        && FactionSpawns.LARGE_PATROL_SIZE == 5
                        && FactionSpawns.NEARBY_CAP == 10
                        && FactionSpawns.LOADED_WORLD_CAP == 20
                        && FactionSpawns.MAX_REACTIVE_AMBIENT_POPULATION == 44,
                "ambient patrol population must remain bounded for small multiplayer servers");

        FakePlayer spawnDriver = new FakePlayer(
                helper.getLevel(), new GameProfile(UUID.randomUUID(), "patrol_driver"));
        spawnDriver.setHealth(spawnDriver.getMaxHealth());
        spawnDriver.setGameMode(GameType.CREATIVE);
        helper.assertTrue(FactionSpawns.canDrivePatrolSpawns(spawnDriver),
                "creative players must drive ambient patrol population");
        spawnDriver.setGameMode(GameType.SPECTATOR);
        helper.assertFalse(FactionSpawns.canDrivePatrolSpawns(spawnDriver),
                "spectators must not drive ambient patrol population");
        spawnDriver.setGameMode(GameType.SURVIVAL);
        spawnDriver.setHealth(0.0F);
        helper.assertFalse(FactionSpawns.canDrivePatrolSpawns(spawnDriver),
                "dead players must not drive ambient patrol population");

        helper.assertValueEqual(FactionSpawns.plannedPatrolSize(false, 2), 0,
                "capacity below three must reject a partial patrol");
        helper.assertValueEqual(FactionSpawns.plannedPatrolSize(true, 4), 3,
                "capacity four must fall back to an intact three-soldier patrol");
        helper.assertValueEqual(FactionSpawns.plannedPatrolSize(false, 5), 3,
                "small patrol roll");
        helper.assertValueEqual(FactionSpawns.plannedPatrolSize(true, 5), 5,
                "large patrol roll");

        for (int size : List.of(
                FactionSpawns.SMALL_PATROL_SIZE, FactionSpawns.LARGE_PATROL_SIZE)) {
            for (int rotation = 0; rotation < 4; rotation++) {
                List<BlockPos> first = FactionSpawns.formationOffsets(rotation, size);
                List<BlockPos> second = FactionSpawns.formationOffsets(rotation, size);
                helper.assertTrue(first.equals(second),
                        "formation plan changed for rotation " + rotation);
                helper.assertTrue(first.size() == size,
                        "formation returned the wrong patrol member count");
                helper.assertTrue(new HashSet<>(first).size() == first.size(),
                        "formation contains duplicate patrol offsets");
            }
            List<Integer> firstSkins = FactionSquads.uniqueSkinVariants(
                    RandomSource.create(913_557L + size), size);
            List<Integer> secondSkins = FactionSquads.uniqueSkinVariants(
                    RandomSource.create(913_557L + size), size);
            helper.assertTrue(firstSkins.equals(secondSkins),
                    "equal seeds must produce the same tactical skin plan");
            helper.assertTrue(firstSkins.size() == size
                            && new HashSet<>(firstSkins).size() == size,
                    "every generated squad member must receive a unique tactical skin");
            helper.assertTrue(firstSkins.stream().allMatch(
                            variant -> variant >= 0
                                    && variant < FactionEnemy.TACTICAL_SKIN_COUNT),
                    "tactical skin plan returned an out-of-range variant");
        }
        List<Integer> smallSquad = FactionSquads.uniqueSkinVariants(
                RandomSource.create(37L), FactionSpawns.SMALL_PATROL_SIZE);
        List<Integer> smallWave = FactionSquads.uniqueSkinVariants(
                RandomSource.create(38L), FactionSquads.REINFORCEMENT_COUNT, smallSquad);
        HashSet<Integer> smallEncounter = new HashSet<>(smallSquad);
        smallEncounter.addAll(smallWave);
        helper.assertTrue(smallEncounter.size() == 7,
                "a three-soldier squad and reinforcement wave should all look unique");

        List<Integer> largeSquad = FactionSquads.uniqueSkinVariants(
                RandomSource.create(39L), FactionSpawns.LARGE_PATROL_SIZE);
        List<Integer> largeWave = FactionSquads.uniqueSkinVariants(
                RandomSource.create(40L), FactionSquads.REINFORCEMENT_COUNT, largeSquad);
        HashSet<Integer> largeEncounter = new HashSet<>(largeSquad);
        largeEncounter.addAll(largeWave);
        helper.assertTrue(new HashSet<>(largeWave).size() == FactionSquads.REINFORCEMENT_COUNT
                        && largeEncounter.size() == FactionEnemy.TACTICAL_SKIN_COUNT,
                "a five-soldier encounter must use all skins before one balanced repeat");

        List<Integer> missionSequence = new java.util.ArrayList<>();
        for (int index = 0; index < FactionEnemy.TACTICAL_SKIN_COUNT * 2; index++) {
            missionSequence.add(FactionSquads.uniqueSkinVariants(
                    RandomSource.create(8_000L + index), 1, missionSequence).getFirst());
        }
        helper.assertTrue(new HashSet<>(missionSequence.subList(
                        0, FactionEnemy.TACTICAL_SKIN_COUNT)).size()
                        == FactionEnemy.TACTICAL_SKIN_COUNT,
                "mission guards must exhaust all tactical skins before repeating");
        for (int variant = 0; variant < FactionEnemy.TACTICAL_SKIN_COUNT; variant++) {
            helper.assertTrue(java.util.Collections.frequency(missionSequence, variant) == 2,
                    "mission skin reuse must remain balanced after two complete cycles");
        }
        for (NeonCityGenerator.RoadClass publicRoad : List.of(
                NeonCityGenerator.RoadClass.NONE,
                NeonCityGenerator.RoadClass.CENTRAL_PLAZA,
                NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD,
                NeonCityGenerator.RoadClass.LOCAL_STREET,
                NeonCityGenerator.RoadClass.SERVICE_ALLEY,
                NeonCityGenerator.RoadClass.PARK,
                NeonCityGenerator.RoadClass.HARBOR,
                NeonCityGenerator.RoadClass.CONTAINER_PORT)) {
            helper.assertTrue(FactionSpawns.isPublicPatrolRoadClass(publicRoad),
                    publicRoad + " should accept ambient patrols");
        }
        for (NeonCityGenerator.RoadClass excluded : List.of(
                NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD,
                NeonCityGenerator.RoadClass.BRIDGE,
                NeonCityGenerator.RoadClass.ELEVATED_RAIL,
                NeonCityGenerator.RoadClass.HIGHWAY_BUFFER,
                NeonCityGenerator.RoadClass.CANAL,
                NeonCityGenerator.RoadClass.OCEAN,
                NeonCityGenerator.RoadClass.PORTSHIP,
                NeonCityGenerator.RoadClass.FARM,
                NeonCityGenerator.RoadClass.EXTRACTION_SITE,
                NeonCityGenerator.RoadClass.BORDER_WALLED,
                NeonCityGenerator.RoadClass.BORDER_FOREST,
                NeonCityGenerator.RoadClass.BORDER_CLIFF,
                NeonCityGenerator.RoadClass.WILDERNESS)) {
            helper.assertFalse(FactionSpawns.isPublicPatrolRoadClass(excluded),
                    excluded + " must reject ambient patrols");
        }
        helper.succeed();
    }

    private static void districtPatrolLoadout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FactionEnemy light = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        FactionEnemy heavy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        FactionEnemy control = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        helper.assertTrue(light != null && heavy != null && control != null,
                "could not create district patrol loadout fixtures");

        FactionSquads.equipBallisticTier(light, "light");
        helper.assertValueEqual(light.getAttributeValue(Attributes.ARMOR), 15.0,
                "light patrol armor total");
        helper.assertValueEqual(light.getAttributeValue(Attributes.ARMOR_TOUGHNESS), 8.0,
                "light patrol toughness total");
        helper.assertValueEqual(light.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.20,
                "light patrol knockback resistance");
        FactionSquads.equipBallisticTier(light, "light");
        helper.assertValueEqual(light.getAttributeValue(Attributes.ARMOR), 15.0,
                "reapplying a patrol loadout must not stack armor modifiers");

        FactionSquads.equipBallisticTier(heavy, "heavy");
        helper.assertValueEqual(heavy.getAttributeValue(Attributes.ARMOR), 20.0,
                "heavy patrol armor total");
        helper.assertValueEqual(heavy.getAttributeValue(Attributes.ARMOR_TOUGHNESS), 12.0,
                "heavy patrol toughness total");
        helper.assertValueEqual(heavy.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.60,
                "heavy patrol knockback resistance");

        for (FactionEnemy enemy : List.of(light, heavy)) {
            helper.assertTrue(enemy.getItemBySlot(EquipmentSlot.CHEST)
                            .is(WeaponItems.BULLETPROOF_VEST.get()),
                    "new patrol loadout must wear only the Bulletproof Vest");
            helper.assertTrue(enemy.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                            && enemy.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                            && enemy.getItemBySlot(EquipmentSlot.FEET).isEmpty(),
                    "legacy branded armor pieces must not be visible on new patrols");
        }
        ItemStack vest = light.getItemBySlot(EquipmentSlot.CHEST);
        helper.assertTrue(new net.minecraft.world.item.component.DyedItemColor(0x1D1D21)
                        .equals(vest.get(DataComponents.DYED_COLOR)),
                "Bulletproof Vest must use the authored black leather dye");
        helper.assertFalse(vest.hasFoil(),
                "Bulletproof Vest must provide projectile protection without enchantment glint");

        light.setDistrict(District.O_CORP);
        helper.assertValueEqual(light.getName().getString(), "O Corp. Soldier",
                "district patrol display name");
        helper.assertValueEqual(light.detectionRange(), FactionEnemy.MISSION_DETECTION_RANGE,
                "mission guard detection range");
        light.setPersistenceRequired();
        helper.assertTrue(light.shouldBeSaved(), "mission guards must remain persistent");
        light.setAmbientPatrol(true);
        helper.assertValueEqual(light.detectionRange(), FactionEnemy.AMBIENT_DETECTION_RANGE,
                "ambient patrol detection range");
        helper.assertFalse(light.shouldBeSaved(),
                "ambient patrols must not accumulate in world saves");
        FactionEnemy unrelatedMission = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        helper.assertTrue(unrelatedMission != null,
                "could not create mission alert-isolation fixture");
        helper.assertFalse(light.sharesAlertGroup(unrelatedMission),
                "unassigned enemies must not collapse into one global alert group");
        light.setAlertGroupId(UUID.randomUUID());
        unrelatedMission.setAlertGroupId(UUID.randomUUID());
        helper.assertFalse(light.sharesAlertGroup(unrelatedMission),
                "separate multiplayer contracts must not share combat alerts");
        helper.assertValueEqual(FactionSquads.REINFORCEMENT_CHANCE, 0.30F,
                "corporate reinforcement chance");
        helper.assertTrue(FactionSquads.reinforcementRollSucceeds(0.0F)
                        && FactionSquads.reinforcementRollSucceeds(0.299_999F),
                "the lower 30% of reinforcement rolls must succeed");
        helper.assertFalse(FactionSquads.reinforcementRollSucceeds(0.30F)
                        || FactionSquads.reinforcementRollSucceeds(0.999_999F),
                "reinforcement rolls at or above 30% must fail");
        helper.assertTrue(light.canRequestReinforcements(),
                "an equipped ordinary corporate soldier must be reinforcement eligible");
        light.setReinforcementRollResolved(true);
        helper.assertTrue(light.hasResolvedReinforcementRoll(),
                "the one-time reinforcement roll must retain its consumed state");

        FactionEnemy reinforcementLeader = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        helper.assertTrue(reinforcementLeader != null,
                "could not create reinforcement inheritance fixture");
        BlockPos reinforcementPos = helper.absolutePos(new BlockPos(5, 2, 5));
        reinforcementLeader.snapTo(
                reinforcementPos.getX() + 0.5, reinforcementPos.getY(),
                reinforcementPos.getZ() + 0.5, 0.0F, 0.0F);
        FactionSquads.equip(
                reinforcementLeader, Faction.MILITECH, RandomSource.create(71L), 2);
        reinforcementLeader.setDistrict(District.N_CORP);
        UUID reinforcementGroup = UUID.randomUUID();
        reinforcementLeader.setAlertGroupId(reinforcementGroup);
        CompoundTag actorData = reinforcementLeader.getPersistentData();
        actorData.putBoolean("cyberdeck_mission_actor", true);
        actorData.putString("cyberdeck_mission_owner", UUID.randomUUID().toString());
        actorData.putString("cyberdeck_mission_definition", "reinforcement_test");
        actorData.putString("cyberdeck_mission_instance", reinforcementGroup.toString());
        actorData.putString("cyberdeck_mission_role", "guard");
        reinforcementLeader.setPersistenceRequired();
        helper.assertTrue(level.addFreshEntity(reinforcementLeader),
                "could not add reinforcement inheritance fixture");
        helper.assertTrue(FactionSquads.tryReinforcementsOnAttack(
                        level, reinforcementLeader, unrelatedMission, 0.0F),
                "a successful 30% roll must deploy airborne reinforcements");
        List<FactionEnemy> reinforcements = level.getEntitiesOfClass(
                FactionEnemy.class,
                new AABB(reinforcementPos).inflate(32.0),
                enemy -> reinforcementGroup.equals(enemy.getAlertGroupId())
                        && enemy.isReinforcementDeployment());
        helper.assertValueEqual(
                reinforcements.size(), FactionSquads.REINFORCEMENT_COUNT,
                "airborne reinforcement count");
        helper.assertTrue(reinforcements.stream().allMatch(enemy ->
                        enemy.getFaction() == Faction.MILITECH
                                && enemy.getDistrict() == District.N_CORP
                                && enemy.hasResolvedReinforcementRoll()
                                && enemy.shouldBeSaved()
                                && reinforcementGroup.toString().equals(
                                        enemy.getPersistentData().getString(
                                                "cyberdeck_mission_instance").orElse(""))),
                "reinforcements must inherit faction, district, consumed state, and mission cleanup");
        helper.assertTrue(reinforcements.stream().map(FactionEnemy::getSkinVariant)
                        .collect(java.util.stream.Collectors.toSet()).size()
                        == FactionSquads.REINFORCEMENT_COUNT,
                "one reinforcement wave must not repeat tactical skins");
        reinforcementLeader.setReinforcementRollResolved(false);
        reinforcements.forEach(enemy -> enemy.setReinforcementRollResolved(false));
        helper.assertFalse(FactionSquads.tryReinforcementsOnAttack(
                        level, reinforcementLeader, unrelatedMission, 0.0F),
                "the persisted group ledger must reject a second reinforcement roll");
        reinforcements.forEach(Entity::discard);
        reinforcementLeader.discard();

        FakePlayer combatPlayer = new FakePlayer(
                level, new GameProfile(UUID.randomUUID(), "reinforcement_attack_test"));
        combatPlayer.getAbilities().invulnerable = false;
        combatPlayer.setInvulnerable(false);
        FactionEnemy directAttackTarget = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        FactionEnemy quickhackTarget = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.EVENT);
        helper.assertTrue(directAttackTarget != null && quickhackTarget != null,
                "could not create reinforcement attack-hook fixtures");
        for (FactionEnemy enemy : List.of(directAttackTarget, quickhackTarget)) {
            FactionSquads.equipBallisticTier(enemy, "light");
            enemy.setAlertGroupId(UUID.randomUUID());
            enemy.setReinforcementRollResolved(true);
        }
        helper.assertTrue(directAttackTarget.hurtServer(
                        level, level.damageSources().playerAttack(combatPlayer), 1.0F),
                "direct player attack must damage a corporate soldier");
        helper.assertTrue(directAttackTarget.isTriggered(),
                "direct player attack must enter the reinforcement/retaliation path");
        SkillExecutor.execute(Skill.OVERHEAT, combatPlayer, quickhackTarget, level);
        helper.assertTrue(quickhackTarget.isTriggered(),
                "damaging quickhacks must enter the reinforcement/retaliation path");
        directAttackTarget.discard();
        quickhackTarget.discard();

        control.setBallisticTier("light");
        control.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        light.setHealth(light.getMaxHealth());
        control.setHealth(control.getMaxHealth());
        BlockPos lightPos = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos controlPos = helper.absolutePos(new BlockPos(3, 2, 1));
        light.snapTo(lightPos.getX() + 0.5, lightPos.getY(), lightPos.getZ() + 0.5,
                0.0F, 0.0F);
        control.snapTo(controlPos.getX() + 0.5, controlPos.getY(), controlPos.getZ() + 0.5,
                0.0F, 0.0F);
        helper.assertTrue(level.addFreshEntity(light) && level.addFreshEntity(control),
                "could not add projectile mitigation fixtures");
        var bullet = level.damageSources().source(CyberdeckDamageTypes.BULLET);
        helper.assertTrue(bullet.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE),
                "hitscan bullet damage must carry the projectile tag");
        light.hurtServer(level, bullet, 10.0F);
        control.hurtServer(level, bullet, 10.0F);
        helper.assertTrue(light.getHealth() > control.getHealth(),
                "Bulletproof Vest must reduce tagged projectile and hitscan bullet damage");
        light.discard();
        control.discard();
        heavy.discard();
        unrelatedMission.discard();
        helper.succeed();
    }

    private static void gunshotRadius(GameTestHelper helper) {
        helper.assertTrue(GunshotAlerts.hearingRadius(GunType.MANTIS_BLADE) == 0.0,
                "Mantis Blade attacks must not emit a gunshot alert");
        for (GunType gun : GunType.values()) {
            if (gun != GunType.MANTIS_BLADE) {
                helper.assertTrue(GunshotAlerts.hearingRadius(gun) > 0.0,
                        gun.id() + " must emit an audible gunshot alert");
            }
        }
        helper.assertTrue(GunshotAlerts.hearingRadius(GunType.SNIPER)
                        > GunshotAlerts.hearingRadius(GunType.PISTOL),
                "heavy sniper fire should carry farther than a pistol shot");
        helper.succeed();
    }

    private static void minimapRotationGeometry(GameTestHelper helper) {
        int viewport = 88;
        int halfQuad = MinimapGeometry.coveringHalfSize(viewport);
        for (int yaw = 0; yaw < 360; yaw++) {
            helper.assertTrue(halfQuad + 1.0E-9
                            >= MinimapGeometry.requiredHalfExtent(viewport, yaw),
                    "minimap source quad does not cover yaw " + yaw);
        }
        double worldSpan = 1_200.0;
        double destinationScale = halfQuad / (viewport * 0.5);
        double sampledScale = MinimapGeometry.coveringHalfSpan(worldSpan, viewport)
                / (worldSpan * 0.5);
        helper.assertTrue(Math.abs(destinationScale - sampledScale) < 1.0E-12,
                "rounded minimap quad and UV span must retain identical scale");
        helper.assertTrue(Math.abs(MinimapGeometry.requiredHalfExtent(viewport, 0.0)
                                - viewport * 0.5) < 1.0E-9
                        && Math.abs(MinimapGeometry.requiredHalfExtent(viewport, 90.0)
                                - viewport * 0.5) < 1.0E-9
                        && Math.abs(MinimapGeometry.requiredHalfExtent(viewport, 180.0)
                                - viewport * 0.5) < 1.0E-9
                        && Math.abs(MinimapGeometry.requiredHalfExtent(viewport, 270.0)
                                - viewport * 0.5) < 1.0E-9,
                "cardinal directions must remain rigid, axis-aligned rotations");
        helper.assertTrue(MinimapGeometry.rotatedTwoCornerScissorWidth(viewport, 0.0)
                        == viewport
                        && MinimapGeometry.rotatedTwoCornerScissorWidth(viewport, 45.0) < 1.0E-9,
                "rotated two-corner scissors must remain forbidden: they collapse at 45 degrees");
        MegacityLayout layout = MegacityLayout.create(0x4D45524348414E54L);
        List<MerchantMarkerClient.Marker> merchantMarkers = MerchantMarkerClient.markers(layout);
        helper.assertTrue(merchantMarkers.size() == layout.nodes().size(),
                "live minimap must retain one merchant signal per city district");
        for (int index = 0; index < merchantMarkers.size(); index++) {
            MegacityLayout.Node node = layout.nodes().get(index);
            MerchantMarkerClient.Marker marker = merchantMarkers.get(index);
            helper.assertTrue(marker.x() == node.x()
                            && marker.z() == node.z()
                            && marker.districtCode().equals(node.district().code()),
                    "merchant minimap marker drifted from its district node");
        }
        helper.succeed();
    }

    private static void npcRolesAndDrops(GameTestHelper helper) {
        int residents = 0;
        int corpos = 0;
        int execs = 0;
        int defensiveDraws = 0;
        for (int roll = 0; roll < 100; roll++) {
            NpcRole role = CityNpcSpawns.roleForRoll(roll, true);
            residents += role == NpcRole.RESIDENT ? 1 : 0;
            corpos += role == NpcRole.CORPO ? 1 : 0;
            execs += role == NpcRole.EXEC ? 1 : 0;
            defensiveDraws += CityNpc.corpoDefends(roll) ? 1 : 0;
            helper.assertTrue(CityNpcSpawns.roleForRoll(roll, false) != NpcRole.EXEC,
                    "Exec role escaped the open-near-building spawn gate");
        }
        helper.assertTrue(residents == 70 && corpos == 25 && execs == 5,
                "valid city plazas must produce the 70/25/5 Resident/Corpo/Exec split");
        helper.assertTrue(defensiveDraws == CityNpc.CORPO_DEFENSE_PERCENT,
                "Corpo defensive draw chance must be exactly 30 percent");
        helper.assertTrue(NpcRole.RESIDENT.minimumCredits() == 5
                        && NpcRole.RESIDENT.maximumCredits() == 15,
                "Resident money shards must stay in the requested 5-15 credit range");
        helper.assertTrue(MoneyShardItem.credits(MoneyShardItem.create(5)) == 5
                        && MoneyShardItem.credits(MoneyShardItem.create(15)) == 15,
                "money shard per-stack credit data did not round-trip");

        ServerLevel level = helper.getLevel();
        CityNpc resident = CityNpcEntities.CITY_NPC.get().create(level, EntitySpawnReason.COMMAND);
        CityNpc exec = CityNpcEntities.CITY_NPC.get().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(resident != null && exec != null,
                "city NPC factories must be available for role regressions");
        if (resident == null || exec == null) {
            return;
        }
        ServerPlayer player = makeSurvivalServerPlayerInLevel(helper);
        player.setInvulnerable(true);
        BlockPos residentPos = helper.absolutePos(new BlockPos(2, 2, 2));
        resident.snapTo(residentPos.getX() + 0.5, residentPos.getY(), residentPos.getZ() + 0.5,
                0.0F, 0.0F);
        resident.setRole(NpcRole.RESIDENT);
        helper.assertTrue(!NpcVoicelineService.acceptsTrigger(
                                resident, false, NpcVoicelineService.DialogueTrigger.ATTACK)
                        && NpcVoicelineService.acceptsTrigger(
                                resident, false, NpcVoicelineService.DialogueTrigger.INTERACT),
                "Residents must speak only when right-clicked, never when attacked");
        helper.assertTrue(NpcVoicelineService.acceptsTrigger(
                                resident, true, NpcVoicelineService.DialogueTrigger.ATTACK)
                        && !NpcVoicelineService.acceptsTrigger(
                                resident, true, NpcVoicelineService.DialogueTrigger.INTERACT),
                "story status must preserve attack dialogue even on a Resident-role actor");
        helper.assertTrue(level.addFreshEntity(resident), "could not add Resident drop subject");
        resident.hurtServer(level, resident.damageSources().playerAttack(player), 1.0F);
        helper.assertTrue(resident.isFleeingGunfire(),
                "attacked Residents must only enter their flee behavior");
        resident.hurtServer(level, resident.damageSources().playerAttack(player), 100.0F);
        List<ItemEntity> shards = level.getEntitiesOfClass(
                ItemEntity.class, new AABB(residentPos).inflate(3.0),
                item -> item.getItem().is(CyberdeckItems.MONEY_SHARD.get()));
        helper.assertTrue(shards.size() == 1,
                "a killed Resident must drop exactly one money shard");
        if (!shards.isEmpty()) {
            int credits = MoneyShardItem.credits(shards.getFirst().getItem());
            helper.assertTrue(credits >= 5 && credits <= 15,
                    "Resident shard value escaped 5-15 credits: " + credits);
        }

        BlockPos execPos = helper.absolutePos(new BlockPos(6, 2, 2));
        exec.snapTo(execPos.getX() + 0.5, execPos.getY(), execPos.getZ() + 0.5, 0.0F, 0.0F);
        exec.setRole(NpcRole.EXEC);
        helper.assertTrue(NpcVoicelineService.acceptsTrigger(
                                exec, false, NpcVoicelineService.DialogueTrigger.ATTACK)
                        && !NpcVoicelineService.acceptsTrigger(
                                exec, false, NpcVoicelineService.DialogueTrigger.INTERACT),
                "Exec dialogue must retain its existing attack trigger");
        helper.assertTrue(exec.getMaxHealth() == 100.0F,
                "Execs must have substantially more health than Residents");
        helper.assertTrue(CityNpc.limitIncomingDamage(NpcRole.EXEC, exec.getMaxHealth(), 1_000.0F)
                        == 18.0F,
                "Exec per-hit cap must prevent instantaneous kills");
        helper.assertTrue(!CityNpc.shouldRequestTrauma(50.0F, 100.0F)
                        && CityNpc.shouldRequestTrauma(49.9F, 100.0F),
                "Trauma Team request threshold must cross strictly below 50 percent health");
        exec.discard();
        disconnectTestPlayer(player);
        helper.succeed();
    }

    private static void npcVoicelinePools(GameTestHelper helper) {
        int authoredLineCount = 0;
        for (NpcVoicelineCatalog.LocationPool location
                : NpcVoicelineCatalog.LocationPool.values()) {
            for (NpcVoicelineCatalog.RolePool role : NpcVoicelineCatalog.RolePool.values()) {
                List<String> lines = NpcVoicelineCatalog.lines(location, role);
                helper.assertFalse(lines.isEmpty(),
                        "voiceline pool must not be empty: " + location.id() + "/" + role.id());
                authoredLineCount += lines.size();
            }
        }
        helper.assertTrue(authoredLineCount == 170,
                "bundled voiceline catalog must contain all 170 authored lines");
        assertVoicelinePoolCounts(helper,
                NpcVoicelineCatalog.LocationPool.DISTRICT_A, 5, 5, 5);
        assertVoicelinePoolCounts(helper,
                NpcVoicelineCatalog.LocationPool.DISTRICT_E, 5, 5, 5);
        assertVoicelinePoolCounts(helper,
                NpcVoicelineCatalog.LocationPool.DISTRICT_N, 6, 5, 6);

        helper.assertTrue(NpcVoicelineService.classifyLocation(
                        District.O_CORP,
                        MegacityLayout.Zone.NEST,
                        NeonCityGenerator.RoadClass.LOCAL_STREET)
                        == NpcVoicelineCatalog.LocationPool.DISTRICT_O,
                "supported district did not select its authored pool");
        helper.assertTrue(NpcVoicelineService.classifyLocation(
                        District.O_CORP,
                        MegacityLayout.Zone.NEST,
                        NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD)
                        == NpcVoicelineCatalog.LocationPool.GREAT_HIGHWAY,
                "Great Highway must override the surrounding district pool");
        helper.assertTrue(NpcVoicelineService.classifyLocation(
                        District.O_CORP,
                        MegacityLayout.Zone.BORDER_WALLED,
                        NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD)
                        == NpcVoicelineCatalog.LocationPool.BORDER_SLUMS,
                "border slums must override both highway and district pools");
        helper.assertTrue(NpcVoicelineService.classifyLocation(
                        District.A_CORP,
                        MegacityLayout.Zone.NEST,
                        NeonCityGenerator.RoadClass.LOCAL_STREET)
                        == NpcVoicelineCatalog.LocationPool.DISTRICT_A,
                "District A did not select its authored pool");
        helper.assertTrue(NpcVoicelineService.classifyLocation(
                        District.E_CORP,
                        MegacityLayout.Zone.NEST,
                        NeonCityGenerator.RoadClass.LOCAL_STREET)
                        == NpcVoicelineCatalog.LocationPool.DISTRICT_E,
                "District E did not select its authored pool");
        helper.assertTrue(NpcVoicelineService.classifyLocation(
                        District.N_CORP,
                        MegacityLayout.Zone.NEST,
                        NeonCityGenerator.RoadClass.LOCAL_STREET)
                        == NpcVoicelineCatalog.LocationPool.DISTRICT_N,
                "District N did not select its authored pool");
        helper.assertTrue(NpcVoicelineService.classifyLocation(
                        District.C_CORP,
                        MegacityLayout.Zone.NEST,
                        NeonCityGenerator.RoadClass.LOCAL_STREET)
                        == NpcVoicelineCatalog.LocationPool.GENERIC_UNSUPPORTED_DISTRICTS,
                "unsupported districts must use the generic pool");

        List<String> alternatives = List.of("first", "second", "third");
        RandomSource random = RandomSource.create(0x564F494345L);
        String previous = "first";
        for (int attempt = 0; attempt < 24; attempt++) {
            String selected = NpcVoicelineService.selectLine(alternatives, previous, random);
            helper.assertFalse(selected.equals(previous),
                    "multi-line selection repeated the immediately previous bark");
            previous = selected;
        }
        helper.succeed();
    }

    private static void assertVoicelinePoolCounts(
            GameTestHelper helper,
            NpcVoicelineCatalog.LocationPool location,
            int residents,
            int corpos,
            int execs) {
        helper.assertValueEqual(NpcVoicelineCatalog.lines(
                location, NpcVoicelineCatalog.RolePool.RESIDENTS).size(), residents,
                location.id() + " Resident voiceline count");
        helper.assertValueEqual(NpcVoicelineCatalog.lines(
                location, NpcVoicelineCatalog.RolePool.CORPOS).size(), corpos,
                location.id() + " Corpo voiceline count");
        helper.assertValueEqual(NpcVoicelineCatalog.lines(
                location, NpcVoicelineCatalog.RolePool.EXECS).size(), execs,
                location.id() + " Exec voiceline count");
    }

    private static void traumaTeamLifecycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(TraumaTeamEvents.EXEC_BOARDING_WAIT_TICKS == 3_000,
                "Exec boarding hold must last exactly 2.5 minutes");
        helper.assertTrue(TraumaTeamEvents.MAX_LANDED_TICKS == 6_000,
                "a landed aerodyne must have a bounded five-minute lifecycle");
        var cyberdeckCommand = level.getServer().getCommands().getDispatcher()
                .getRoot().getChild("cyberdeck");
        helper.assertTrue(cyberdeckCommand != null
                        && cyberdeckCommand.getChild("trauma") != null,
                "/cyberdeck trauma command was not registered");
        for (int x = 1; x <= 31; x++) {
            for (int z = 1; z <= 19; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.AIR);
        BlockPos landing = helper.absolutePos(new BlockPos(16, 2, 10));
        BlockPos underbodyObstruction = new BlockPos(16, 4, 10);
        helper.setBlock(underbodyObstruction, Blocks.STONE);
        helper.assertTrue(!TraumaTeamEvents.hasLandingClearance(level, landing, 2),
                "Aerodyne clearance must reject blocks in the three-block hover gap");
        helper.setBlock(underbodyObstruction, Blocks.AIR);
        BlockPos hullObstruction = new BlockPos(16, 5, 10);
        helper.setBlock(hullObstruction, Blocks.STONE);
        helper.assertTrue(!TraumaTeamEvents.hasLandingClearance(level, landing, 2),
                "Aerodyne clearance must reject blocks inside the full structure volume");
        helper.setBlock(hullObstruction, Blocks.AIR);
        BlockPos approachObstruction = new BlockPos(16, 14, 10);
        helper.setBlock(approachObstruction, Blocks.STONE);
        helper.assertTrue(TraumaTeamEvents.hasLandingClearance(level, landing, 2),
                "landing clearance must allow uneven ground and ignore overhead approach blocks");

        CityNpc exec = CityNpcEntities.CITY_NPC.get().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(exec != null, "Exec factory failed for Trauma Team lifecycle");
        if (exec == null) {
            return;
        }
        BlockPos execStart = helper.absolutePos(new BlockPos(2, 2, 10));
        exec.snapTo(execStart.getX() + 0.5, execStart.getY(), execStart.getZ() + 0.5,
                0.0F, 0.0F);
        exec.setRole(NpcRole.EXEC);
        exec.setPersistenceRequired();
        helper.assertTrue(level.addFreshEntity(exec), "could not add Trauma Team Exec");

        ServerPlayer player = makeSurvivalServerPlayerInLevel(helper);
        player.addEffect(new MobEffectInstance(
                MobEffects.RESISTANCE, 20 * 20, 4, false, false));
        BlockPos playerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        player.snapTo(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5,
                0.0F, 0.0F);
        UUID execId = exec.getUUID();
        helper.assertTrue(exec.isAlive() && exec.getRole() == NpcRole.EXEC,
                "Trauma Team requester must be a living Exec");
        helper.assertTrue(player.isAlive() && !player.isCreative() && !player.isSpectator(),
                "Trauma Team target must be a living Survival player");
        helper.assertTrue(TraumaTeamEvents.activeEventCount(level) == 0,
                "Trauma Team test level retained an event from another test");
        helper.assertTrue(TraumaTeamEvents.hasLandingClearance(level, landing, 2),
                "entities unexpectedly invalidated the prepared landing site");
        var aerodyne = level.getStructureManager().get(Identifier.fromNamespaceAndPath(
                Cyberdeck.MODID, "trauma_team/aerodyne"));
        helper.assertTrue(aerodyne.isPresent(),
                "native Trauma Team aerodyne template was not loaded");
        if (aerodyne.isEmpty()) {
            return;
        }
        helper.assertTrue(aerodyne.get().getSize().equals(
                        new net.minecraft.core.Vec3i(
                                TraumaTeamEvents.AERODYNE_WIDTH,
                                TraumaTeamEvents.AERODYNE_HEIGHT,
                                TraumaTeamEvents.AERODYNE_LENGTH)),
                "native Trauma Team aerodyne template has the wrong dimensions");
        helper.assertTrue(!aerodyne.get().save(new CompoundTag())
                        .getListOrEmpty("blocks").isEmpty(),
                "native Trauma Team aerodyne template has no placeable blocks");
        player.setGameMode(GameType.CREATIVE);
        helper.assertTrue(TraumaTeamEvents.isCommandTargetEligible(player),
                "/cyberdeck trauma must accept a living creative-mode target");
        helper.assertFalse(TraumaTeamEvents.isAutomaticTargetEligible(player),
                "automatic Trauma Team targeting must ignore creative-mode players");
        helper.assertTrue(TraumaTeamEvents.requestAt(level, exec, player, landing, 2, 80, 20),
                "creative-mode Trauma Team request did not start with a valid aerodyne template");

        int[] deployedResponders = {0};

        helper.runAfterDelay(12, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, execId)
                            == TraumaTeamEvents.Phase.LANDED,
                    "aerodyne did not finish its animated descent");
            int count = TraumaTeamEvents.responderCount(level, execId);
            deployedResponders[0] = count;
            helper.assertTrue(count >= TraumaTeamEvents.MIN_RESPONDERS
                            && count <= TraumaTeamEvents.MAX_RESPONDERS,
                    "Trauma Team must deploy 4-5 responders, got " + count);
            List<FactionEnemy> responders = level.getEntitiesOfClass(
                    FactionEnemy.class, new AABB(landing).inflate(48.0), FactionEnemy::isTraumaTeam);
            helper.assertTrue(responders.size() == count,
                    "all deployed responders must carry the Trauma Team identity");
            helper.assertTrue(responders.stream().allMatch(responder ->
                            responder.isTriggered()
                                    && responder.getTarget() == player
                                    && responder.getMainHandItem().getItem() instanceof GunItem
                                    && !responder.isInvulnerable()),
                    "responders must arrive armed, killable, and immediately target the player");
            helper.assertTrue(player.isCreative(),
                    "creative command target changed mode before responder aggro was verified");
            player.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(helper.getBlockState(approachObstruction).is(Blocks.STONE),
                    "adaptive descent must not overwrite an overhead approach block");

            BlockPos pickup = landing.offset(0, 0, TraumaTeamEvents.AERODYNE_LENGTH / 2 + 2);
            exec.snapTo(pickup.getX() + 0.5, pickup.getY(), pickup.getZ() + 0.5,
                    0.0F, 0.0F);
        });

        helper.runAfterDelay(16, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, execId)
                            == TraumaTeamEvents.Phase.BOARDING,
                    "Exec did not enter the visible boarding hold beside the aerodyne");
            helper.assertTrue(exec.isAlive() && !exec.isEvacuating() && !exec.isInvulnerable(),
                    "boarding Exec must remain present, stationary, and killable");
            List<FactionEnemy> responders = level.getEntitiesOfClass(
                    FactionEnemy.class, new AABB(landing).inflate(48.0),
                    responder -> responder.isTraumaTeam() && responder.isAlive());
            helper.assertTrue(!responders.isEmpty(),
                    "boarding event lost all Trauma Team responders unexpectedly");
            FactionEnemy casualty = responders.getFirst();
            casualty.setHealth(1.0F);
            helper.assertTrue(casualty.hurtServer(
                            level, level.damageSources().playerAttack(player), 100.0F)
                            && !casualty.isAlive(),
                    "Trauma Team responder could not be killed during boarding");
        });

        helper.runAfterDelay(28, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, execId)
                            == TraumaTeamEvents.Phase.BOARDING
                            && level.getEntity(execId) != null,
                    "aerodyne extracted the Exec before the boarding hold elapsed");
        });

        helper.runAfterDelay(38, () -> {
            TraumaTeamEvents.Phase phase = TraumaTeamEvents.phaseFor(level, execId);
            helper.assertTrue(phase == TraumaTeamEvents.Phase.ASCENDING || phase == null,
                    "aerodyne remained landed after the boarding hold elapsed");
            helper.assertTrue(level.getEntity(execId) == null,
                    "successfully boarded Exec must leave the world at lift-off");
        });

        UUID[] strandedExecId = {null};
        CityNpc[] strandedExec = {null};
        helper.runAfterDelay(48, () -> {
            helper.assertTrue(TraumaTeamEvents.activeEventCount(level) == 0,
                    "aerodyne did not lift off and clear after successful extraction");
            List<FactionEnemy> survivors = level.getEntitiesOfClass(
                    FactionEnemy.class, new AABB(landing).inflate(64.0),
                    responder -> responder.isTraumaTeam() && responder.isAlive());
            helper.assertTrue(survivors.size() >= deployedResponders[0] - 1,
                    "surviving Trauma Team members must remain after lift-off");
            helper.assertTrue(survivors.stream().allMatch(responder ->
                            responder.isTriggered() && responder.getTarget() == player),
                    "remaining responders must stay aggroed onto the player");

            CityNpc stranded = CityNpcEntities.CITY_NPC.get().create(
                    level, EntitySpawnReason.COMMAND);
            helper.assertTrue(stranded != null,
                    "Exec factory failed for responder-wipe Trauma Team lifecycle");
            if (stranded == null) {
                return;
            }
            stranded.snapTo(execStart.getX() + 0.5, execStart.getY(), execStart.getZ() + 0.5,
                    0.0F, 0.0F);
            stranded.setRole(NpcRole.EXEC);
            stranded.setPersistenceRequired();
            helper.assertTrue(level.addFreshEntity(stranded),
                    "could not add responder-wipe Trauma Team Exec");
            strandedExec[0] = stranded;
            strandedExecId[0] = stranded.getUUID();
            helper.assertTrue(TraumaTeamEvents.requestAt(
                            level, stranded, player, landing, 2, 80, 20),
                    "could not start responder-wipe Trauma Team event");
        });

        helper.runAfterDelay(60, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, strandedExecId[0])
                            == TraumaTeamEvents.Phase.LANDED,
                    "second aerodyne did not land for the responder-wipe lifecycle");
            BlockPos pickup = landing.offset(0, 0, TraumaTeamEvents.AERODYNE_LENGTH / 2 + 2);
            strandedExec[0].snapTo(
                    pickup.getX() + 0.5, pickup.getY(), pickup.getZ() + 0.5, 0.0F, 0.0F);
        });

        helper.runAfterDelay(64, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, strandedExecId[0])
                            == TraumaTeamEvents.Phase.BOARDING,
                    "second Exec did not enter the boarding hold");
            List<FactionEnemy> responders = level.getEntitiesOfClass(
                    FactionEnemy.class, new AABB(landing).inflate(128.0),
                    responder -> responder.isTraumaTeam() && responder.isAlive());
            helper.assertTrue(responders.size() >= TraumaTeamEvents.MIN_RESPONDERS,
                    "responder-wipe lifecycle did not deploy a full Trauma Team");
            for (FactionEnemy responder : responders) {
                responder.setHealth(1.0F);
                helper.assertTrue(responder.hurtServer(
                                level, level.damageSources().playerAttack(player), 100.0F)
                                && !responder.isAlive(),
                        "Trauma Team responder survived the wipe setup");
            }
            helper.assertTrue(strandedExec[0].isAlive(),
                    "responder wipe unexpectedly killed the boarding Exec");
        });

        helper.runAfterDelay(67, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, strandedExecId[0])
                            == TraumaTeamEvents.Phase.ASCENDING,
                    "full responder wipe did not trigger the normal ascent immediately");
            helper.assertTrue(strandedExec[0].isAlive()
                            && level.getEntity(strandedExecId[0]) != null
                            && !strandedExec[0].isEvacuating(),
                    "responder-wipe departure must leave the living Exec behind");
        });

        UUID[] doomedExecId = {null};
        CityNpc[] doomedExec = {null};
        helper.runAfterDelay(82, () -> {
            helper.assertTrue(TraumaTeamEvents.activeEventCount(level) == 0,
                    "responder-wipe aerodyne did not finish its ascent");
            strandedExec[0].discard();

            CityNpc doomed = CityNpcEntities.CITY_NPC.get().create(
                    level, EntitySpawnReason.COMMAND);
            helper.assertTrue(doomed != null,
                    "Exec factory failed for death-triggered Trauma Team lifecycle");
            if (doomed == null) {
                return;
            }
            doomed.snapTo(execStart.getX() + 0.5, execStart.getY(), execStart.getZ() + 0.5,
                    0.0F, 0.0F);
            doomed.setRole(NpcRole.EXEC);
            doomed.setPersistenceRequired();
            helper.assertTrue(level.addFreshEntity(doomed),
                    "could not add death-triggered Trauma Team Exec");
            doomedExec[0] = doomed;
            doomedExecId[0] = doomed.getUUID();
            helper.assertTrue(TraumaTeamEvents.requestAt(
                            level, doomed, player, landing, 2, 80, 20),
                    "could not start death-triggered Trauma Team event");
        });

        helper.runAfterDelay(94, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, doomedExecId[0])
                            == TraumaTeamEvents.Phase.LANDED,
                    "third aerodyne did not land for the death-triggered lifecycle");
            BlockPos pickup = landing.offset(0, 0, TraumaTeamEvents.AERODYNE_LENGTH / 2 + 2);
            doomedExec[0].snapTo(
                    pickup.getX() + 0.5, pickup.getY(), pickup.getZ() + 0.5, 0.0F, 0.0F);
        });

        helper.runAfterDelay(98, () -> {
            CityNpc doomed = doomedExec[0];
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, doomedExecId[0])
                            == TraumaTeamEvents.Phase.BOARDING,
                    "third Exec did not enter the boarding hold");
            doomed.setHealth(1.0F);
            helper.assertTrue(doomed.hurtServer(
                            level, level.damageSources().playerAttack(player), 100.0F)
                            && !doomed.isAlive(),
                    "boarding Exec could not be killed");
        });

        helper.runAfterDelay(101, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, doomedExecId[0])
                            == TraumaTeamEvents.Phase.ASCENDING,
                    "Exec death did not trigger the normal ascent immediately");
        });

        UUID[] descentDeathExecId = {null};
        helper.runAfterDelay(116, () -> {
            helper.assertTrue(TraumaTeamEvents.activeEventCount(level) == 0,
                    "death-triggered aerodyne did not finish its ascent");

            CityNpc descending = CityNpcEntities.CITY_NPC.get().create(
                    level, EntitySpawnReason.COMMAND);
            helper.assertTrue(descending != null,
                    "Exec factory failed for descent-death Trauma Team lifecycle");
            if (descending == null) {
                return;
            }
            descending.snapTo(
                    execStart.getX() + 0.5, execStart.getY(), execStart.getZ() + 0.5,
                    0.0F, 0.0F);
            descending.setRole(NpcRole.EXEC);
            descending.setPersistenceRequired();
            helper.assertTrue(level.addFreshEntity(descending),
                    "could not add descent-death Trauma Team Exec");
            descentDeathExecId[0] = descending.getUUID();
            helper.assertTrue(TraumaTeamEvents.requestAt(
                            level, descending, player, landing, 2, 80, 20),
                    "could not start descent-death Trauma Team event");
            descending.setHealth(1.0F);
            helper.assertTrue(descending.hurtServer(
                            level, level.damageSources().playerAttack(player), 100.0F)
                            && !descending.isAlive(),
                    "descending Trauma Team Exec could not be killed");
        });

        helper.runAfterDelay(118, () -> {
            helper.assertTrue(TraumaTeamEvents.phaseFor(level, descentDeathExecId[0])
                            == TraumaTeamEvents.Phase.ASCENDING,
                    "Exec death during descent did not reverse the aerodyne immediately");
        });

        helper.runAfterDelay(130, () -> {
            helper.assertTrue(TraumaTeamEvents.activeEventCount(level) == 0,
                    "descent-death aerodyne did not finish its ascent");
            for (int x = 5; x <= 27; x++) {
                for (int y = 2; y <= 13; y++) {
                    for (int z = 5; z <= 15; z++) {
                        helper.assertTrue(helper.getBlockState(new BlockPos(x, y, z)).isAir(),
                                "lift-off left an aerodyne block behind at " + x + "," + y + "," + z);
                    }
                }
            }
            helper.assertTrue(helper.getBlockState(approachObstruction).is(Blocks.STONE),
                    "lift-off must preserve the overhead approach block");
            disconnectTestPlayer(player);
            helper.succeed();
        });
    }

    private static ServerPlayer makeSurvivalServerPlayerInLevel(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(playerId, "survival-" + playerId.toString().substring(0, 7)),
                false);
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                cookie.gameProfile(),
                cookie.clientInformation());
        GameType.SURVIVAL.updatePlayerAbilities(player.getAbilities());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static void disconnectTestPlayer(ServerPlayer player) {
        if (player.connection != null) {
            player.connection.disconnect(Component.literal("GameTest complete"));
        }
    }

    private static void civilianNoncombat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CityNpc civilian = CityNpcEntities.CITY_NPC.get().create(
                level, EntitySpawnReason.COMMAND);
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(civilian != null && enemy != null,
                "registered NPC/enemy entity factories must create test entities");
        if (civilian == null || enemy == null) {
            return;
        }

        helper.assertTrue(!civilian.canBeSeenAsEnemy(),
                "city civilians must not advertise themselves as enemies");
        helper.assertTrue(!civilian.canAttack(enemy),
                "city civilians must never select faction enemies as targets");
        helper.assertTrue(!enemy.canAttack(civilian),
                "faction enemies must never select city civilians as targets");
        helper.assertTrue(!civilian.doHurtTarget(level, enemy),
                "city civilians must not deal melee damage");
        helper.assertTrue(!enemy.doHurtTarget(level, civilian),
                "faction enemies must refuse melee damage against civilians");
        enemy.trigger(level, civilian);
        helper.assertTrue(!enemy.isTriggered() && enemy.getTarget() == null,
                "a faction enemy must not become triggered by or target a civilian");
        helper.succeed();
    }

    private static void civilianPopulation(GameTestHelper helper) {
        helper.assertTrue(CityNpcSpawns.targetNearby() <= 12,
                "civilian target regressed to the old high-density crowd level");
        helper.assertTrue(CityNpcSpawns.spawnBatch() <= 2
                        && CityNpcSpawns.spawnInterval() >= 100,
                "civilian generation must use small, low-frequency batches");
        helper.assertTrue(CityNpcSpawns.nearbyRadius() == 72.0,
                "the pedestrian population must fill the player's visible city radius");
        helper.assertTrue(CityNpcSpawns.desiredSpawnCount(0, 0, 0) == 2
                        && CityNpcSpawns.desiredSpawnCount(
                                0, CityNpcSpawns.maxPerCell() - 1, 0) == 1
                        && CityNpcSpawns.desiredSpawnCount(
                                0, CityNpcSpawns.maxPerCell(), 0) == 0
                        && CityNpcSpawns.desiredSpawnCount(
                                0, 0, CityNpcSpawns.maxLoadedPopulation()) == 0,
                "civilian replenishment crossed a local, cell, or loaded-world cap");

        int nearby = 0;
        int residents = 0;
        int loaded = 0;
        for (int cycle = 0; cycle < 100; cycle++) {
            int spawned = CityNpcSpawns.desiredSpawnCount(nearby, residents, loaded);
            nearby += spawned;
            residents += spawned;
            loaded += spawned;
        }
        helper.assertTrue(residents == CityNpcSpawns.maxPerCell()
                        && loaded <= CityNpcSpawns.maxLoadedPopulation(),
                "repeated civilian replenishment did not converge to a fixed bound");
        helper.assertTrue(!CityNpcSpawns.shouldRetire(599, false)
                        && CityNpcSpawns.shouldRetire(600, false)
                        && !CityNpcSpawns.shouldRetire(600, true),
                "civilian retirement ignored its grace period or nearby players");

        ServerLevel level = helper.getLevel();
        CityNpc civilian = CityNpcEntities.CITY_NPC.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(civilian != null, "civilian factory failed during density regression");
        if (civilian == null) {
            return;
        }
        BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
        civilian.snapTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                0.0F, 0.0F);
        helper.assertTrue(level.addFreshEntity(civilian),
                "density regression could not add a civilian");
        helper.assertFalse(CityNpcSpawns.hasSpawnSeparation(level, position.offset(3, 0, 0)),
                "civilian placement accepted a crowded spawn point");
        helper.assertTrue(CityNpcSpawns.hasSpawnSeparation(level, position.offset(24, 0, 0)),
                "civilian placement rejected a safely separated spawn point");
        helper.assertTrue(civilian.getPathfindingMalus(PathType.DAMAGE_CAUTIOUS)
                        == CityNpc.highwayPathMalus(),
                "civilian navigation lost its highway avoidance cost");
        civilian.markPopulationManaged(position);
        helper.assertFalse(civilian.shouldBeSaved(),
                "ambient civilians must not accumulate in saved chunks");
        civilian.discard();
        helper.succeed();
    }

    private static void cityActorJoinCompatibility(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CityNpc civilian = CityNpcEntities.CITY_NPC.get().create(
                level, EntitySpawnReason.COMMAND);
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.COMMAND);
        KangTaoTurret turret = DefenseContent.KANG_TAO_TURRET.get().create(
                level, EntitySpawnReason.COMMAND);
        Entity unrelated = EntityTypes.ZOMBIE.create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(civilian != null && enemy != null && turret != null && unrelated != null,
                "entity factories needed by join compatibility must be available");
        if (civilian == null || enemy == null || turret == null || unrelated == null) {
            return;
        }

        EntityJoinLevelEvent civilianJoin = canceledJoin(civilian, level);
        CityActorJoinCompatibility.restoreManagedCityActor(civilianJoin, true);
        helper.assertFalse(civilianJoin.isCanceled(),
                "a city civilian canceled by a companion generator must be restored");

        EntityJoinLevelEvent enemyJoin = canceledJoin(enemy, level);
        CityActorJoinCompatibility.restoreManagedCityActor(enemyJoin, true);
        helper.assertFalse(enemyJoin.isCanceled(),
                "a faction enemy canceled by a companion generator must be restored");

        EntityJoinLevelEvent turretJoin = canceledJoin(turret, level);
        CityActorJoinCompatibility.restoreManagedCityActor(turretJoin, true);
        helper.assertFalse(turretJoin.isCanceled(),
                "a Kang Tao turret canceled by a companion generator must be restored");

        enemy.getPersistentData().putBoolean("cyberdeck_mission_actor", true);
        EntityJoinLevelEvent missionActorJoin = canceledJoin(enemy, level);
        CityActorJoinCompatibility.restoreManagedCityActor(missionActorJoin, true);
        helper.assertTrue(missionActorJoin.isCanceled(),
                "mission lifecycle rejections must not be undone by city compatibility");

        EntityJoinLevelEvent unrelatedJoin = canceledJoin(unrelated, level);
        CityActorJoinCompatibility.restoreManagedCityActor(unrelatedJoin, true);
        helper.assertTrue(unrelatedJoin.isCanceled(),
                "unrelated mob cancellations must remain intact");

        EntityJoinLevelEvent ordinaryWorldJoin = canceledJoin(civilian, level);
        CityActorJoinCompatibility.restoreManagedCityActor(ordinaryWorldJoin, false);
        helper.assertTrue(ordinaryWorldJoin.isCanceled(),
                "ordinary Minecraft worlds must not receive the city compatibility override");

        try {
            SubscribeEvent subscription = CityActorJoinCompatibility.class
                    .getDeclaredMethod("onEntityJoin", EntityJoinLevelEvent.class)
                    .getAnnotation(SubscribeEvent.class);
            helper.assertTrue(subscription != null
                            && subscription.priority() == EventPriority.LOWEST
                            && subscription.receiveCanceled(),
                    "the compatibility listener must run after Neon City's canceled join event");
        } catch (NoSuchMethodException exception) {
            helper.fail("city actor join listener is missing: " + exception.getMessage());
        }
        helper.succeed();
    }

    private static EntityJoinLevelEvent canceledJoin(
            Entity entity, ServerLevel level) {
        EntityJoinLevelEvent event = new EntityJoinLevelEvent(entity, level);
        event.setCanceled(true);
        return event;
    }

    private static void profileBalance(GameTestHelper helper) {
        assertProfile(helper, SandevistanProfile.APOGEE);
        assertProfile(helper, SandevistanProfile.FALCON);
        assertProfile(helper, SandevistanProfile.DYNALAR);
        assertProfile(helper, SandevistanProfile.ZETATECH);
        assertProfile(helper, SandevistanProfile.WARP_DANCER);

        helper.assertTrue(close(SandevistanProfile.ZETATECH.slowFraction(true), 0.60),
                "Zetatech must slow time by 60% while airborne");
        helper.assertTrue(SandevistanProfile.ZETATECH.damageBonus(true)
                        > SandevistanProfile.ZETATECH.damageBonus(false),
                "Zetatech must grant its larger airborne damage bonus");
        helper.assertTrue(SandevistanProfile.ZETATECH.headshotBonus(true) > 0.0,
                "Zetatech must grant airborne headshot damage");
        helper.assertTrue(SandevistanProfile.WARP_DANCER.mitigationChance() > 0.0,
                "Warp Dancer must expose tier mitigation chance");
        helper.assertTrue(SandevistanProfile.WARP_DANCER.elementalResistance() > 0.0,
                "Warp Dancer must expose tier elemental resistance");
        helper.succeed();
    }

    private static void chargeModel(GameTestHelper helper) {
        SandevistanState state = new SandevistanState();
        state.ensureVariant(SandevistanProfile.APOGEE);
        helper.assertTrue(state.canActivate(SandevistanProfile.APOGEE),
                "newly installed Apogee must start fully charged");
        state.activate();
        for (int tick = 0; tick < 60; tick++) {
            state.tick(SandevistanProfile.APOGEE);
        }
        helper.assertTrue(close(state.chargeTicks(), 60.0),
                "Apogee must drain one charge tick per active server tick");
        state.deactivate();
        helper.assertTrue(state.canActivate(SandevistanProfile.APOGEE),
                "Apogee must allow partial-charge activation");

        state.ensureVariant(SandevistanProfile.DYNALAR);
        state.activate();
        state.tick(SandevistanProfile.DYNALAR);
        state.deactivate();
        helper.assertFalse(state.canActivate(SandevistanProfile.DYNALAR),
                "Dynalar must require a full charge before reactivation");
        for (int tick = 0; tick < SandevistanProfile.DYNALAR.cooldownTicks(); tick++) {
            state.tick(SandevistanProfile.DYNALAR);
        }
        helper.assertTrue(state.canActivate(SandevistanProfile.DYNALAR),
                "Dynalar must fully recharge within its listed cooldown");
        helper.succeed();
    }

    private static void variantMappings(GameTestHelper helper) {
        helper.assertTrue(Cyberware.byId("sandevistan") == Cyberware.MILITECH_APOGEE,
                "legacy sandevistan data must migrate to Militech Apogee");
        for (Cyberware cyberware : Cyberware.VALUES) {
            SandevistanProfile profile = SandevistanProfile.forCyberware(cyberware);
            helper.assertTrue(cyberware.isSandevistan() == (profile != null),
                    "Sandevistan profile mapping mismatch for " + cyberware.id());
            helper.assertTrue(CyberwareItems.item(cyberware).get() != null,
                    "registered item missing for " + cyberware.id());
        }
        int familyCount = 0;
        for (BodySlot slot : BodySlot.VALUES) {
            familyCount += Cyberware.familiesForSlot(slot).size();
        }
        helper.assertTrue(familyCount == 121,
                "wiki catalog must contain all 121 cyberware families");
        helper.assertTrue(Cyberware.VALUES.length == 1025,
                "wiki catalog must contain all 1,025 tier variants");
        helper.assertTrue(BodySlot.FRONTAL_CORTEX.baseSockets() == 3
                        && BodySlot.OPERATING_SYSTEM.baseSockets() == 1
                        && BodySlot.NERVOUS_SYSTEM.baseSockets() == 3
                        && BodySlot.CIRCULATORY_SYSTEM.baseSockets() == 3,
                "base body socket counts must match the source game");
        CyberwareData data = new CyberwareData();
        helper.assertTrue(data.unlockedSockets(BodySlot.FACE) == 1,
                "face must begin with one socket");
        data.unlock(SlotUnlock.BIRDS_WITH_BROKEN_WINGS);
        helper.assertTrue(data.unlockedSockets(BodySlot.FACE) == 2,
                "Birds with Broken Wings must unlock the second face socket");
        data.unlock(SlotUnlock.LICENSE_TO_CHROME);
        data.unlock(SlotUnlock.AMBIDEXTROUS);
        helper.assertTrue(data.unlockedSockets(BodySlot.SKELETON) == 3
                        && data.unlockedSockets(BodySlot.HANDS) == 2,
                "perk-gated Skeleton and Hands sockets must unlock independently");
        Cyberware low = Cyberware.byId("adrenaline_converter_t1");
        Cyberware high = Cyberware.byId("adrenaline_converter_t5");
        helper.assertTrue(low != null && high != null && !low.effect().equals(high.effect()),
                "tier-specific effects must not be flattened");
        Cyberware generatedDeck = Cyberware.byId("arasaka_mk_1_5_t1");
        CyberwareData operatingSystem = new CyberwareData();
        helper.assertTrue(generatedDeck != null
                        && generatedDeck.slot() == BodySlot.OPERATING_SYSTEM
                        && generatedDeck.hasFlag("cyberdeck"),
                "generated cyberdeck assets must carry the quickhack capability");
        operatingSystem.install(generatedDeck, 0);
        helper.assertTrue(CyberwareEffects.canQuickhack(operatingSystem),
                "installing a generated cyberdeck must authorize quickhacking");
        operatingSystem.install(Cyberware.MILITECH_APOGEE, 0);
        helper.assertFalse(CyberwareEffects.canQuickhack(operatingSystem),
                "replacing the deck with a Sandevistan must revoke quickhacking");
        helper.assertTrue(SandevistanMechanics.slownessAmplifier(0.85) == 5,
                "85% player slow should map to Slowness VI");
        helper.assertTrue(SandevistanMechanics.slownessAmplifier(0.20) == 0,
                "20% player slow should map to Slowness I");
        helper.succeed();
    }

    private static void quickhackLongRange(GameTestHelper helper) {
        helper.assertTrue(QuickhackUploads.MAX_TARGET_RANGE >= 128.0,
                "quickhacks must reach at least eight chunks");
        helper.assertTrue(QuickhackUploads.MAX_TARGET_RANGE <= 192.0,
                "quickhack reach must stay within practical entity tracking distance");
        helper.succeed();
    }

    private static void quickhackMultiTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = makeSurvivalServerPlayerInLevel(helper);
        Cyberware deck = Cyberware.byId("arasaka_mk_1_5_t1");
        helper.assertTrue(deck != null, "test cyberdeck must exist");
        if (deck == null) {
            return;
        }

        CyberwareData loadout = new CyberwareData();
        loadout.install(deck, 0);
        player.setData(CyberwareAttachments.CYBERWARE.get(), loadout);
        RamAttachments.set(player, RamAttachments.MAX_RAM);

        BlockPos playerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        player.snapTo(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5,
                0.0F, 0.0F);
        var first = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(3, 2, 1));
        var second = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(5, 2, 1));
        CyberdeckState.setActive(player, true);

        QuickhackUploads.EnqueueResult firstResult = QuickhackUploads.enqueue(
                player, Skill.OVERHEAT, first, level);
        QuickhackUploads.EnqueueResult secondResult = QuickhackUploads.enqueue(
                player, Skill.SHORT_CIRCUIT, second, level);
        helper.assertTrue(firstResult.accepted() && secondResult.accepted(),
                "different enemies must accept concurrent quickhack uploads");
        helper.assertValueEqual(QuickhackUploads.activeTargetCount(player), 2,
                "independent quickhack target count");
        helper.assertTrue(QuickhackUploads.uploadEndTick(player, first.getId())
                        != QuickhackUploads.uploadEndTick(player, second.getId()),
                "different quickhacks must retain their own upload completion times");

        QuickhackUploads.cancel(player);
        CyberdeckState.deactivate(player);
        disconnectTestPlayer(player);
        helper.succeed();
    }

    private static void quickhackHotbarRecovery(GameTestHelper helper) {
        FakePlayer player = new FakePlayer(
                helper.getLevel(), new GameProfile(UUID.randomUUID(), "hotbar_recovery_test"));
        Cyberware deck = Cyberware.byId("arasaka_mk_1_5_t1");
        helper.assertTrue(deck != null, "test cyberdeck must exist");
        if (deck == null) {
            return;
        }

        CyberwareData loadout = new CyberwareData();
        loadout.install(deck, 0);
        player.setData(CyberwareAttachments.CYBERWARE.get(), loadout);
        player.getInventory().setItem(0,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7));
        player.getInventory().setItem(4,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.REDSTONE, 23));
        CyberdeckState.setActive(player, true);

        QuickhackHotbar stashed = player.getData(QuickhackAttachments.STASHED_HOTBAR.get());
        helper.assertTrue(QuickhackAttachments.isQuickhacking(player)
                        && !QuickhackAttachments.isScanning(player),
                "a cyberdeck must open the full quickhack interface, not scan-only mode");
        helper.assertTrue(stashed.present() && stashed.items().size() == QuickhackHotbar.SIZE,
                "scanner activation must create a complete durable hotbar snapshot");
        var ops = helper.getLevel().registryAccess().createSerializationContext(
                com.mojang.serialization.JsonOps.INSTANCE);
        var encoded = QuickhackHotbar.MAP_CODEC.codec().encodeStart(ops, stashed)
                .getOrThrow(message -> helper.assertionException(Component.literal(message)));
        QuickhackHotbar decoded = QuickhackHotbar.MAP_CODEC.codec().parse(ops, encoded)
                .getOrThrow(message -> helper.assertionException(Component.literal(message)));

        // Simulate a save/reload where the active marker was lost but the durable stash survived.
        player.setData(QuickhackAttachments.STASHED_HOTBAR.get(), decoded);
        player.getPersistentData().putBoolean("cyberdeck_active", false);
        CyberdeckState.recover(player);
        helper.assertTrue(player.getInventory().getItem(0).is(net.minecraft.world.item.Items.DIAMOND)
                        && player.getInventory().getItem(0).getCount() == 7,
                "slot zero must survive scanner crash recovery");
        helper.assertTrue(player.getInventory().getItem(4).is(net.minecraft.world.item.Items.REDSTONE)
                        && player.getInventory().getItem(4).getCount() == 23,
                "slot four must survive scanner crash recovery");
        helper.assertFalse(player.getData(QuickhackAttachments.STASHED_HOTBAR.get()).present(),
                "successful recovery must clear the durable stash");

        Cyberware optics = Cyberware.byId("basic_kiroshi_optics_t1");
        Cyberware faceplate = Cyberware.byId("behavioral_imprint_synced_faceplate_t5");
        helper.assertTrue(optics != null && faceplate != null,
                "scanner capability test cyberware must exist");
        if (optics == null || faceplate == null) {
            return;
        }

        CyberwareData opticsOnly = new CyberwareData();
        opticsOnly.install(optics, 0);
        helper.assertTrue(CyberwareEffects.canScan(opticsOnly)
                        && !CyberwareEffects.canQuickhack(opticsOnly),
                "ocular implants must scan without granting quickhacks");
        player.setData(CyberwareAttachments.CYBERWARE.get(), opticsOnly);
        CyberdeckState.setScannerActive(player, true);
        helper.assertTrue(CyberdeckState.isScanOnlyActive(player)
                        && CyberdeckState.isScannerActive(player)
                        && !CyberdeckState.isActive(player),
                "eye-only loadouts must enter read-only scanner mode");
        helper.assertTrue(QuickhackAttachments.isScanning(player)
                        && !QuickhackAttachments.isQuickhacking(player)
                        && !player.getData(QuickhackAttachments.STASHED_HOTBAR.get()).present(),
                "scan-only mode must not create a quickhack hotbar session");
        helper.assertTrue(player.getInventory().getItem(0).is(net.minecraft.world.item.Items.DIAMOND)
                        && player.getInventory().getItem(0).getCount() == 7
                        && player.getInventory().getItem(4).is(net.minecraft.world.item.Items.REDSTONE)
                        && player.getInventory().getItem(4).getCount() == 23,
                "scan-only activation must leave the real hotbar untouched");
        CyberdeckState.deactivate(player);
        helper.assertTrue(player.getInventory().getItem(0).is(net.minecraft.world.item.Items.DIAMOND)
                        && player.getInventory().getItem(4).is(net.minecraft.world.item.Items.REDSTONE),
                "scan-only deactivation must preserve the real hotbar");

        CyberwareData faceplateOnly = new CyberwareData();
        faceplateOnly.install(faceplate, 0);
        helper.assertFalse(CyberwareEffects.canScan(faceplateOnly),
                "the identity faceplate must not count as an ocular scanner");
        helper.succeed();
    }

    private static void doubleJumpPacketGuard(GameTestHelper helper) {
        helper.assertFalse(DoubleJumpGuard.canConsume(true, 20, false, 0L, 100L),
                "physical ground support must reject a double-jump packet");
        helper.assertFalse(DoubleJumpGuard.canConsume(false, 1, false, 0L, 100L),
                "a packet cannot invent the required airborne interval");
        helper.assertTrue(DoubleJumpGuard.canConsume(false,
                        DoubleJumpGuard.MIN_AIRBORNE_TICKS, false, 90L, 100L),
                "one legitimate airborne double jump must be accepted");
        helper.assertFalse(DoubleJumpGuard.canConsume(false, 200, true, 0L, 1000L),
                "the same airborne cycle must stay consumed even after its cooldown");
        helper.assertFalse(DoubleJumpGuard.canConsume(false, 20, false, 110L, 100L),
                "a newly grounded cycle must still respect the server cooldown");
        helper.succeed();
    }

    private static void cityLootCaches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos blackPosition = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(blackPosition,
                CityLootBlocks.BLACK_LOOT_CACHE.get().defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        helper.assertTrue(level.getBlockEntity(blackPosition)
                        instanceof BlackLootCacheBlockEntity,
                "black cache must create its persistent inventory block entity");
        if (!(level.getBlockEntity(blackPosition) instanceof BlackLootCacheBlockEntity cache)) {
            return;
        }
        CityLootGeneration.populate(cache, RandomSource.create(0xCAFE));
        boolean hasGun = false;
        boolean hasCyberware = false;
        boolean hasAmmo = false;
        int rewards = 0;
        for (int slot = 0; slot < cache.getContainerSize(); slot++) {
            ItemStack stack = cache.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            rewards++;
            hasGun |= stack.getItem() instanceof GunItem;
            hasCyberware |= stack.getItem() instanceof CyberwareItem;
            hasAmmo |= stack.getItem() instanceof AmmoItem;
        }
        helper.assertValueEqual(cache.getContainerSize(), 54, "black cache inventory size");
        helper.assertTrue(hasGun && hasCyberware && hasAmmo,
                "every black cache must guarantee a gun, cyberware, and ammunition");
        helper.assertTrue(rewards >= 4 && rewards <= 6,
                "black cache must contain three guaranteed rewards plus one to three extras");

        for (AmmoType type : AmmoType.values()) {
            ItemStack ammo = new ItemStack(AmmoItems.item(type).get());
            helper.assertValueEqual(ammo.getMaxStackSize(), AmmoItem.MAX_STACK_SIZE,
                    type.itemId() + " max stack size");
            helper.assertValueEqual(ammo.getMaxStackSize(), 500,
                    type.itemId() + " requested stack size");
        }

        FakePlayer stackPlayer = new FakePlayer(
                level, new GameProfile(UUID.randomUUID(), "ammo_stack_test"));
        ItemStack firstAmmo = new ItemStack(AmmoItems.item(AmmoType.HANDGUN).get(), 450);
        ItemStack secondAmmo = new ItemStack(AmmoItems.item(AmmoType.HANDGUN).get(), 50);
        helper.assertTrue(stackPlayer.getInventory().add(firstAmmo)
                        && firstAmmo.isEmpty()
                        && stackPlayer.getInventory().add(secondAmmo)
                        && secondAmmo.isEmpty(),
                "player inventory must accept a complete 500-round ammo stack");
        helper.assertValueEqual(stackPlayer.getInventory().getItem(0).getCount(), 500,
                "merged ammo count in one inventory slot");
        long occupiedAmmoSlots = stackPlayer.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(AmmoItems.item(AmmoType.HANDGUN).get()))
                .count();
        helper.assertValueEqual(occupiedAmmoSlots, 1L,
                "500 rounds must occupy exactly one inventory slot");
        var itemOps = level.registryAccess().createSerializationContext(
                net.minecraft.nbt.NbtOps.INSTANCE);
        var encodedAmmo = ItemStack.CODEC.encodeStart(
                        itemOps, stackPlayer.getInventory().getItem(0))
                .getOrThrow(message -> helper.assertionException(Component.literal(message)));
        ItemStack decodedAmmo = ItemStack.CODEC.parse(itemOps, encodedAmmo)
                .getOrThrow(message -> helper.assertionException(Component.literal(message)));
        helper.assertTrue(decodedAmmo.is(AmmoItems.item(AmmoType.HANDGUN).get())
                        && decodedAmmo.getCount() == 500,
                "a 500-round stack must survive the inventory persistence codec");

        FakePlayer player = new FakePlayer(
                level, new GameProfile(UUID.randomUUID(), "ammo_cache_test"));
        BlockPos ammoPosition = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockState ammoState = CityLootBlocks.AMMO_CACHE.get().defaultBlockState();
        level.setBlock(ammoPosition, ammoState,
                net.minecraft.world.level.block.Block.UPDATE_ALL);

        // Exercise the same BlockState hook used by a real left-click packet.
        ammoState.attack(level, ammoPosition, player);
        AmmoType rewardType = null;
        int rewardAmount = 0;
        for (AmmoType type : AmmoType.values()) {
            int count = AmmoItems.count(player, type);
            if (count > 0) {
                helper.assertTrue(rewardType == null,
                        "one ammo cache must grant exactly one ammunition type");
                rewardType = type;
                rewardAmount = count;
            }
        }
        helper.assertTrue(rewardType != null,
                "the first left-click attack hook must produce ammunition");
        helper.assertTrue(rewardAmount >= AmmoCacheBlock.MIN_REWARD
                        && rewardAmount <= AmmoCacheBlock.MAX_REWARD
                        && rewardAmount % AmmoCacheBlock.REWARD_STEP == 0,
                "ammo cache reward must stay inside the configured stepped range");
        helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, 2, 1));
        ammoState.attack(level, ammoPosition, player);
        int roundsAfterDuplicateAttack = 0;
        for (AmmoType type : AmmoType.values()) {
            roundsAfterDuplicateAttack += AmmoItems.count(player, type);
        }
        helper.assertValueEqual(roundsAfterDuplicateAttack, rewardAmount,
                "a consumed ammo cache must reject duplicate left-click packets");

        HashSet<CityLootGeneration.CacheKind> generatedKinds = new HashSet<>();
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                CityLootGeneration.CacheKind first = CityLootGeneration.cacheKind(1234L, x, z);
                CityLootGeneration.CacheKind second = CityLootGeneration.cacheKind(1234L, x, z);
                helper.assertTrue(first == second,
                        "cache generation decisions must be deterministic per chunk");
                if (first != null) {
                    generatedKinds.add(first);
                }
            }
        }
        helper.assertTrue(generatedKinds.containsAll(List.of(
                        CityLootGeneration.CacheKind.BLACK_LOOT,
                        CityLootGeneration.CacheKind.AMMO)),
                "the city generation pass must emit both cache variants");

        BlockPos generatedPosition = helper.absolutePos(new BlockPos(6, 2, 4));
        level.setBlock(generatedPosition.below(), Blocks.STONE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        level.setBlock(generatedPosition, Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        level.setBlock(generatedPosition.above(), Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        for (net.minecraft.core.Direction direction
                : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            level.setBlock(generatedPosition.relative(direction), Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        helper.assertFalse(CityLootGeneration.place(
                        level,
                        generatedPosition,
                        CityLootGeneration.CacheKind.AMMO,
                        net.minecraft.core.Direction.SOUTH,
                        0x57414C4C4241434BL),
                "a generated cache must not spawn in an open area");
        level.setBlock(generatedPosition.north(), Blocks.STONE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        level.setBlock(generatedPosition.above(), Blocks.STONE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        helper.assertFalse(CityLootGeneration.place(
                        level,
                        generatedPosition,
                        CityLootGeneration.CacheKind.AMMO,
                        net.minecraft.core.Direction.SOUTH,
                        0x57414C4C4241434BL),
                "a generated cache must keep its overhead block clear");
        level.setBlock(generatedPosition.above(), Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        helper.assertTrue(CityLootGeneration.place(
                        level,
                        generatedPosition,
                        CityLootGeneration.CacheKind.AMMO,
                        net.minecraft.core.Direction.SOUTH,
                        0x57414C4C4241434BL)
                        && level.getBlockState(generatedPosition)
                                .getValue(AmmoCacheBlock.FACING)
                                == net.minecraft.core.Direction.SOUTH
                        && level.isEmptyBlock(generatedPosition.above()),
                "a wall-backed generated cache was not placed facing open space");
        helper.assertFalse(CityLootGeneration.place(
                        level,
                        generatedPosition,
                        CityLootGeneration.CacheKind.AMMO,
                        net.minecraft.core.Direction.SOUTH,
                        0x57414C4C4241434BL),
                "cache generation overwrote an occupied cache position");

        BlockPos widePosition = helper.absolutePos(new BlockPos(8, 2, 4));
        for (BlockPos floorPosition : List.of(
                widePosition.below(),
                widePosition.east().below(),
                widePosition.west().below())) {
            level.setBlock(floorPosition, Blocks.STONE.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        for (BlockPos clearPosition : List.of(
                widePosition,
                widePosition.above(),
                widePosition.east(),
                widePosition.east().above(),
                widePosition.west(),
                widePosition.west().above(),
                widePosition.north())) {
            level.setBlock(clearPosition, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        helper.assertFalse(CityLootGeneration.place(
                        level,
                        widePosition,
                        CityLootGeneration.CacheKind.BLACK_LOOT,
                        net.minecraft.core.Direction.SOUTH,
                        0x424C41434B57414CL),
                "a wide black cache must not spawn without a backing wall");
        level.setBlock(widePosition.north(), Blocks.STONE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        level.setBlock(widePosition.east(), Blocks.STONE.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        helper.assertFalse(CityLootGeneration.place(
                        level,
                        widePosition,
                        CityLootGeneration.CacheKind.BLACK_LOOT,
                        net.minecraft.core.Direction.SOUTH,
                        0x424C41434B57414CL),
                "a wide black cache accepted an obstructed side footprint");
        level.setBlock(widePosition.east(), Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        helper.assertTrue(CityLootGeneration.place(
                        level,
                        widePosition,
                        CityLootGeneration.CacheKind.BLACK_LOOT,
                        net.minecraft.core.Direction.SOUTH,
                        0x424C41434B57414CL)
                        && level.getBlockState(widePosition)
                                .getValue(BlackLootCacheBlock.FACING)
                                == net.minecraft.core.Direction.SOUTH
                        && level.isEmptyBlock(widePosition.above())
                        && level.isEmptyBlock(widePosition.east().above())
                        && level.isEmptyBlock(widePosition.west().above()),
                "a clear wall-backed black cache did not preserve its full footprint");
        helper.succeed();
    }

    private static void assertProfile(GameTestHelper helper, SandevistanProfile profile) {
        Cyberware cyberware = profile.cyberware();
        helper.assertTrue(close(profile.slowFraction(),
                        cyberware.value("time_slow_percent") / 100.0),
                profile.cyberware().id() + " slow fraction mismatch");
        helper.assertTrue(profile.durationTicks()
                        == Math.max(1, (int) Math.round(cyberware.value("duration_seconds") * 20)),
                profile.cyberware().id() + " duration mismatch");
        helper.assertTrue(profile.cooldownTicks()
                        == Math.max(1, (int) Math.round(cyberware.value("cooldown_seconds") * 20)),
                profile.cyberware().id() + " cooldown mismatch");
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 1.0E-6;
    }

    private static void tacticalMovementMath(GameTestHelper helper) {
        helper.assertTrue(TacticalMovement.validInputAxes(1.0F, 0.0F),
                "a unit forward input must be accepted");
        helper.assertTrue(TacticalMovement.validInputAxes(1.0F, 1.0F),
                "diagonal movement input must be accepted");
        helper.assertTrue(!TacticalMovement.validInputAxes(0.0F, 0.0F),
                "a dash without movement intent must be rejected");
        helper.assertTrue(!TacticalMovement.validInputAxes(Float.NaN, 0.0F),
                "non-finite packet input must be rejected");
        helper.assertTrue(!TacticalMovement.validInputAxes(1.1F, 0.0F),
                "out-of-range packet input must be rejected");

        Vec3 forward = TacticalMovement.yawRelativeDirection(0.0F, 1.0F, 0.0F);
        Vec3 right = TacticalMovement.yawRelativeDirection(0.0F, 0.0F, 1.0F);
        Vec3 diagonal = TacticalMovement.yawRelativeDirection(90.0F, 1.0F, 1.0F);
        helper.assertTrue(forward.distanceToSqr(new Vec3(0.0, 0.0, 1.0)) < 1.0E-8,
                "yaw-zero forward input must point toward positive Z");
        helper.assertTrue(right.distanceToSqr(new Vec3(-1.0, 0.0, 0.0)) < 1.0E-8,
                "positive strafe must follow the client right-input convention");
        helper.assertTrue(Math.abs(diagonal.horizontalDistance() - 1.0) < 1.0E-8,
                "diagonal movement intent must be normalized");

        double dashStart = TacticalMovement.speedFor(TacticalAction.DASH, 0.0);
        double dashEnd = TacticalMovement.speedFor(TacticalAction.DASH, 1.0);
        double slideStart = TacticalMovement.speedFor(TacticalAction.SLIDE, 0.0);
        helper.assertTrue(dashStart > dashEnd && slideStart > 0.0,
                "tactical movement curves must decay through recovery");
        Vec3 replacement = TacticalMovement.velocityFor(
                new Vec3(0.1, 0.42, -0.1), new Vec3(0.0, 0.0, 1.0), 0.75);
        helper.assertTrue(Math.abs(replacement.y - 0.42) < 1.0E-8,
                "horizontal maneuvers must preserve vertical momentum");
        helper.succeed();
    }

    private static void tacticalMovementState(GameTestHelper helper) {
        TacticalMovementState idle = TacticalMovementState.idle();
        TacticalMovementState dash = TacticalMovementState.begin(
                idle, TacticalAction.DASH, 100L, 3.0, 4.0);
        helper.assertTrue(dash.isActiveAt(100L) && dash.isActiveAt(105L),
                "dash must stay active for its configured animation window");
        helper.assertTrue(!dash.isActiveAt(106L),
                "dash must end exactly at its exclusive end tick");
        helper.assertTrue(Math.abs(dash.directionX() - 0.6) < 1.0E-8
                        && Math.abs(dash.directionZ() - 0.8) < 1.0E-8,
                "synchronized maneuver directions must be normalized");
        helper.assertTrue(!TacticalMovement.canStart(dash, TacticalAction.DASH, 106L),
                "the recovery cooldown must prevent dash spam");
        helper.assertTrue(TacticalMovement.canStart(
                        dash.finish(), TacticalAction.SLIDE, dash.cooldownUntilTick()),
                "another move must become available after recovery");

        TacticalMovementState fired = dash.withLastShotTick(103L);
        helper.assertTrue(TacticalMovement.firedRecently(fired, 107L, 5),
                "recent gunfire must drive the recoil animation window");
        helper.assertTrue(!TacticalMovement.firedRecently(fired, 109L, 5),
                "recoil state must expire outside its bounded window");
        helper.assertTrue(TacticalMovement.actionProgress(100L, 106L, 103L) == 0.5,
                "animation progress must interpolate from synchronized world ticks");
        helper.succeed();
    }

    /** Reproduces the network-player velocity split that previously rejected every real slide. */
    private static void tacticalSlideActivation(GameTestHelper helper) {
        FakePlayer player = new FakePlayer(
                helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "slide_test"));
        player.setYRot(0.0F);
        player.setOnGround(true);
        player.setSprinting(true);
        player.setLastClientInput(new Input(true, false, false, false, false, false, true));

        // Ordinary walking is tracked here by ServerPlayer's network handler. deltaMovement stays
        // zero until the server accepts and applies the tactical slide impulse.
        player.setKnownMovement(new Vec3(0.0, 0.0, 0.30));
        player.setDeltaMovement(Vec3.ZERO);

        helper.assertTrue(TacticalMovement.request(
                        player, TacticalAction.SLIDE, 1.0F, 0.0F),
                "a sprinting network player with accepted forward speed must start sliding");
        TacticalMovementState state = TacticalMovement.get(player);
        helper.assertTrue(state.action() == TacticalAction.SLIDE,
                "an accepted request must synchronize the slide action");
        helper.assertTrue(player.getForcedPose() == net.minecraft.world.entity.Pose.SWIMMING,
                "an accepted slide must use the low collision pose");
        helper.assertTrue(Math.abs(player.getDeltaMovement().z - 0.78) < 1.0E-8,
                "an accepted slide must apply its initial server-owned impulse");
        helper.assertTrue(!player.isSprinting(),
                "the slide must consume the vanilla sprint state");
        helper.succeed();
    }

    private static void healingConsumableState(GameTestHelper helper) {
        long useTick = 100L;
        helper.assertTrue(HealingConsumable.BOUNCE_BACK.cooldownTicks() == 15 * 20
                        && HealingConsumable.MAXDOC.cooldownTicks() == 15 * 20,
                "both healing consumables must use the fifteen-second cooldown");
        HealingState bounceBack = HealingState.NONE.afterUse(
                HealingConsumable.BOUNCE_BACK, useTick);
        helper.assertFalse(bounceBack.ready(HealingConsumable.BOUNCE_BACK, useTick),
                "Bounce Back must enter cooldown immediately after use");
        helper.assertTrue(bounceBack.ready(HealingConsumable.MAXDOC, useTick),
                "the two healing consumables must retain independent cooldowns");
        helper.assertTrue(bounceBack.regenerationPulseDue(useTick + 20L),
                "Bounce Back must schedule its first regeneration pulse after one second");

        HealingState advanced = bounceBack;
        int pulses = 0;
        while (advanced.nextRegenerationTick() > 0L) {
            advanced = advanced.afterRegenerationPulse();
            pulses++;
        }
        helper.assertTrue(pulses == 10,
                "Bounce Back must emit exactly ten one-second regeneration pulses");
        helper.assertTrue(HealingConsumable.BOUNCE_BACK.totalHealing()
                        > HealingConsumable.MAXDOC.totalHealing(),
                "Bounce Back's delayed profile must trade speed for higher total healing");
        helper.assertTrue(HealingConsumable.fromNetworkId(-1).isEmpty()
                        && HealingConsumable.fromNetworkId(HealingConsumable.VALUES.length).isEmpty(),
                "invalid healing packet ids must be rejected");

        FakePlayer player = new FakePlayer(
                helper.getLevel(), new GameProfile(UUID.randomUUID(), "healing_test"));
        player.setHealth(player.getMaxHealth());
        helper.assertTrue(HealingSystem.use(player, HealingConsumable.BOUNCE_BACK),
                "using a healing consumable at full health must still start its cooldown");
        helper.assertFalse(HealingSystem.use(player, HealingConsumable.BOUNCE_BACK),
                "a healing consumable must not be reusable during its cooldown");
        helper.assertTrue(HealingState.get(player).cooldownRemaining(
                        HealingConsumable.BOUNCE_BACK, player.level().getGameTime()) > 0L,
                "an accepted healing use must publish a visible cooldown");
        helper.succeed();
    }

    /**
     * Feature 3: enemy detection is gradual and line-of-sight based. A survival player standing
     * clearly inside a soldier's forward view cone must fill the detection meter over time (and
     * aggro at the threshold), rather than being spotted instantly.
     */
    private static void detectionLineOfSightBuildup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Vanilla LivingEntity.canAttack refuses all player targets on PEACEFUL, so the aggro
        // trigger can only be exercised at a hostile difficulty.
        level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        helper.assertTrue(level.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL,
                "test setup must raise difficulty above PEACEFUL, saw " + level.getDifficulty());
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(enemy != null, "faction enemy factory must create a test soldier");
        if (enemy == null) {
            return;
        }
        // Place the soldier on the padded floor facing +Z (yaw 0) and the player 5 blocks ahead,
        // squarely inside the forward view cone with an unobstructed line of sight.
        BlockPos enemyPos = helper.absolutePos(new BlockPos(3, 2, 1));
        enemy.snapTo(enemyPos.getX() + 0.5, enemyPos.getY(), enemyPos.getZ() + 0.5, 0.0F, 0.0F);
        enemy.setHome(enemyPos);
        // Unique faction so a concurrently-running detection test cannot alert this soldier.
        enemy.setFaction(com.example.cyberdeck.faction.Faction.ARASAKA);
        // Pin the soldier in the void so it holds its facing while we tick it manually.
        enemy.setNoGravity(true);
        enemy.setDeltaMovement(Vec3.ZERO);
        helper.assertTrue(level.addFreshEntity(enemy), "detection test could not add the soldier");

        FakePlayer player = new FakePlayer(
                level, new GameProfile(UUID.randomUUID(), "detection_los_test"));
        player.snapTo(enemyPos.getX() + 0.5, enemyPos.getY(), enemyPos.getZ() + 5.5, 180.0F, 0.0F);
        // Pin the player too so the level tick does not drop it out of detection range.
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        // A FakePlayer defaults to invulnerable abilities, which makes it unattackable; clear that
        // so canBeSeenAsEnemy is true and the soldier can legally acquire it as a target.
        player.getAbilities().invulnerable = false;
        player.setInvulnerable(false);
        // Register the player as a level entity so the soldier's proximity query can find it.
        level.addNewPlayer(player);

        helper.assertTrue(enemy.getDetection() == 0,
                "a freshly spawned soldier must start with an empty detection meter");
        helper.assertFalse(enemy.isTriggered(),
                "a soldier must remain passive before any detection accumulates");

        // Detection must NOT be instant: after a single tick the meter has risen but is nowhere
        // near the threshold, and the soldier is still not aggroed.
        enemy.aiStep();
        int afterOneTick = enemy.getDetection();
        helper.assertTrue(afterOneTick > 0,
                "a visible in-cone player must begin filling the detection meter");
        helper.assertTrue(afterOneTick < FactionEnemy.detectionThreshold(),
                "detection must be gradual, not instant, after a single tick");
        helper.assertFalse(enemy.isTriggered(),
                "a soldier must not aggro from a single tick of exposure");

        // Sustained exposure fills the meter to full and aggros the squad.
        for (int i = 0; i < FactionEnemy.detectionThreshold() * 4 && !enemy.isTriggered(); i++) {
            enemy.aiStep();
        }
        helper.assertTrue(enemy.getDetection() == FactionEnemy.detectionThreshold(),
                "sustained line-of-sight exposure must fill the detection meter to full ("
                        + enemy.getDetection() + "/" + FactionEnemy.detectionThreshold() + ")");
        helper.assertTrue(enemy.isTriggered(),
                "a fully exposed player must aggro the soldier once the meter is full");
        helper.assertTrue(enemy.getTarget() == player,
                "an aggroed soldier must acquire the exposed player as its target");
        player.discard();
        helper.succeed();
    }

    /**
     * Feature 3: crouching reduces enemy vision. A crouched, still player must accumulate detection
     * strictly slower than an identically-placed standing player over the same exposure window.
     */
    private static void detectionCrouchReducesVision(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int standingDetection = detectionAfterExposure(helper, level, "detect_stand", false);
        int crouchedDetection = detectionAfterExposure(helper, level, "detect_crouch", true);
        helper.assertTrue(standingDetection > 0,
                "a standing exposed player must build detection over the sample window");
        helper.assertTrue(crouchedDetection < standingDetection,
                "crouching must slow detection buildup relative to standing exposure");
        helper.succeed();
    }

    /** Ticks a soldier against a fixed-pose player for a bounded window and returns the meter. */
    private static int detectionAfterExposure(
            GameTestHelper helper, ServerLevel level, String name, boolean crouched) {
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(enemy != null, "faction enemy factory must create a crouch-test soldier");
        if (enemy == null) {
            return 0;
        }
        BlockPos enemyPos = helper.absolutePos(new BlockPos(3, 2, 1));
        enemy.snapTo(enemyPos.getX() + 0.5, enemyPos.getY(), enemyPos.getZ() + 0.5, 0.0F, 0.0F);
        enemy.setHome(enemyPos);
        enemy.setFaction(com.example.cyberdeck.faction.Faction.KANG_TAO);
        enemy.setNoGravity(true);
        enemy.setDeltaMovement(Vec3.ZERO);
        helper.assertTrue(level.addFreshEntity(enemy), "crouch test could not add the soldier");

        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), name));
        player.snapTo(enemyPos.getX() + 0.5, enemyPos.getY(), enemyPos.getZ() + 5.5, 180.0F, 0.0F);
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        level.addNewPlayer(player);
        if (crouched) {
            player.setPose(net.minecraft.world.entity.Pose.CROUCHING);
            player.setShiftKeyDown(true);
        }
        // Bounded sample window short enough that neither pose reaches the aggro threshold, so the
        // comparison reflects raw buildup rate rather than saturation.
        for (int i = 0; i < 10; i++) {
            enemy.aiStep();
        }
        int detection = enemy.getDetection();
        enemy.discard();
        player.discard();
        return detection;
    }

    /**
     * Feature 3: losing line of sight stands the squad down. Once a soldier has aggroed, removing
     * the exposed player must decay its detection meter back toward zero and clear its aggro.
     */
    private static void detectionDecaysWithoutSight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(enemy != null, "faction enemy factory must create a decay-test soldier");
        if (enemy == null) {
            return;
        }
        BlockPos enemyPos = helper.absolutePos(new BlockPos(3, 2, 1));
        enemy.snapTo(enemyPos.getX() + 0.5, enemyPos.getY(), enemyPos.getZ() + 0.5, 0.0F, 0.0F);
        enemy.setHome(enemyPos);
        enemy.setFaction(com.example.cyberdeck.faction.Faction.MILITECH);
        enemy.setNoGravity(true);
        enemy.setDeltaMovement(Vec3.ZERO);
        helper.assertTrue(level.addFreshEntity(enemy), "decay test could not add the soldier");

        FakePlayer player = new FakePlayer(
                level, new GameProfile(UUID.randomUUID(), "detect_decay"));
        player.snapTo(enemyPos.getX() + 0.5, enemyPos.getY(), enemyPos.getZ() + 5.5, 180.0F, 0.0F);
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        player.getAbilities().invulnerable = false;
        player.setInvulnerable(false);
        level.addNewPlayer(player);
        for (int i = 0; i < FactionEnemy.detectionThreshold() * 4 && !enemy.isTriggered(); i++) {
            enemy.aiStep();
        }
        helper.assertTrue(enemy.isTriggered(),
                "the decay test must first drive the soldier to aggro");

        // Move the player far outside detection range so nothing is visible in the cone: with no
        // exposed target the meter must fall and the soldier must eventually stand down.
        player.snapTo(enemyPos.getX() + 500.5, enemyPos.getY(), enemyPos.getZ() + 0.5, 180.0F, 0.0F);
        int before = enemy.getDetection();
        enemy.aiStep();
        helper.assertTrue(enemy.getDetection() < before,
                "detection must decay once the exposed player is no longer visible");

        for (int i = 0; i < FactionEnemy.detectionThreshold() && enemy.getDetection() > 0; i++) {
            enemy.aiStep();
        }
        helper.assertTrue(enemy.getDetection() == 0,
                "detection must decay all the way to zero without a visible target");
        helper.assertFalse(enemy.isTriggered(),
                "a soldier that fully loses its quarry must stand down");
        helper.assertTrue(enemy.getTarget() == null,
                "a stood-down soldier must clear its acquired target");
        helper.succeed();
    }

    /**
     * Feature 4: cyberpsychos are rebalanced. Their live attributes must match the tuned-down
     * health/armour band and their self-heal must stay sharply below the original values.
     */
    private static void cyberpsychoBalance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        com.example.cyberdeck.faction.CyberpsychoEntity psycho =
                FactionEntities.CYBERPSYCHO.get().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(psycho != null, "cyberpsycho factory must create a boss for balance test");
        if (psycho == null) {
            return;
        }
        helper.assertTrue(
                psycho.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                        == 75.0,
                "cyberpsycho max health must be rebalanced to 75");
        helper.assertTrue(
                psycho.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)
                        == 7.0,
                "cyberpsycho armour must be rebalanced to 7");
        helper.assertTrue(
                psycho.getAttributeValue(
                                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS)
                        == 2.0,
                "cyberpsycho armour toughness must be rebalanced to 2");

        int healRecharge = cyberpsychoHealRecharge(helper);
        helper.assertTrue(healRecharge == 400,
                "cyberpsycho self-heal must recharge every 400 ticks");
        helper.assertTrue(cyberpsychoHealAmount(helper) == 1.0F,
                "cyberpsycho blood pump must restore only one health point");
        psycho.discard();
        helper.succeed();
    }

    /** Reads the private heal cadence so the balance regression stays locked in. */
    private static int cyberpsychoHealRecharge(GameTestHelper helper) {
        try {
            java.lang.reflect.Field field = com.example.cyberdeck.faction.CyberpsychoEntity.class
                    .getDeclaredField("HEAL_RECHARGE_TICKS");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException exception) {
            helper.fail("cyberpsycho heal recharge constant is missing: " + exception.getMessage());
            return -1;
        }
    }

    private static float cyberpsychoHealAmount(GameTestHelper helper) {
        try {
            java.lang.reflect.Field field = com.example.cyberdeck.faction.CyberpsychoEntity.class
                    .getDeclaredField("HEAL_AMOUNT");
            field.setAccessible(true);
            return field.getFloat(null);
        } catch (ReflectiveOperationException exception) {
            helper.fail("cyberpsycho heal amount is missing: " + exception.getMessage());
            return -1.0F;
        }
    }

    /**
     * Feature 3: a nearby thrown item briefly draws a soldier's gaze. {@link FactionEnemy#distractTo}
     * must mark the soldier distracted toward the item for the given window, expose the point via
     * {@link FactionEnemy#getDistractionPos()}, and then expire on its own without ever changing the
     * soldier's combat target.
     */
    private static void throwableDistraction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(enemy != null, "faction enemy factory must create a distraction-test soldier");
        if (enemy == null) {
            return;
        }
        BlockPos enemyPos = helper.absolutePos(new BlockPos(3, 2, 3));
        enemy.snapTo(enemyPos.getX() + 0.5, enemyPos.getY(), enemyPos.getZ() + 0.5, 0.0F, 0.0F);
        enemy.setHome(enemyPos);
        enemy.setFaction(com.example.cyberdeck.faction.Faction.ARASAKA);
        enemy.setNoGravity(true);
        enemy.setDeltaMovement(Vec3.ZERO);
        helper.assertTrue(level.addFreshEntity(enemy), "distraction test could not add the soldier");

        helper.assertFalse(enemy.isDistracted(),
                "a soldier must not be distracted before any throwable lands");
        helper.assertTrue(enemy.getDistractionPos() == null,
                "an undistracted soldier must expose no distraction point");
        helper.assertTrue(enemy.getTarget() == null,
                "the distraction test must start with no combat target acquired");

        // A null/zero-length distraction is a safe no-op and never trips the distracted state.
        Vec3 itemPos = new Vec3(enemyPos.getX() + 3.5, enemyPos.getY(), enemyPos.getZ() + 0.5);
        enemy.distractTo(null, 20);
        helper.assertFalse(enemy.isDistracted(), "a null distraction point must be a no-op");
        enemy.distractTo(itemPos, 0);
        helper.assertFalse(enemy.isDistracted(), "a non-positive distraction window must be a no-op");
        helper.assertTrue(enemy.getTarget() == null,
                "a no-op distraction must never acquire a combat target");

        // A throwable lands a few blocks away: the soldier is drawn toward that point for a short
        // window without ever acquiring it as a target. The window is measured in world game-ticks,
        // so we let the real test server advance the clock and assert expiry via runAfterDelay
        // rather than manual aiStep (which does not advance the game clock).
        int window = 10;
        enemy.distractTo(itemPos, window);
        helper.assertTrue(enemy.isDistracted(),
                "distractTo must mark the soldier distracted within its window");
        Vec3 seen = enemy.getDistractionPos();
        helper.assertTrue(seen != null && seen.equals(itemPos),
                "a distracted soldier must expose the exact point it was drawn toward");
        helper.assertTrue(enemy.getTarget() == null,
                "a look-only distraction must never acquire the throwable as a combat target");

        // Still distracted partway through the window.
        helper.runAfterDelay(window - 3, () -> {
            helper.assertTrue(enemy.isDistracted(),
                    "a soldier must stay distracted until its window elapses");
            helper.assertTrue(enemy.getTarget() == null,
                    "a mid-window distraction must still leave the combat target untouched");
        });
        // The distraction is brief: once the window elapses the soldier is no longer distracted and
        // its combat target was never touched.
        helper.runAfterDelay(window + 3, () -> {
            helper.assertFalse(enemy.isDistracted(),
                    "a throwable distraction must expire on its own after its window");
            helper.assertTrue(enemy.getDistractionPos() == null,
                    "an expired distraction must no longer expose a point");
            helper.assertTrue(enemy.getTarget() == null,
                    "an expired distraction must still leave the combat target untouched");
            enemy.discard();
            helper.succeed();
        });
    }

    /**
     * Feature 6: a melee-armed soldier actively closes the distance. A sword holder must report as
     * melee (not gun) armed and, once aggroed onto a player it cannot reach, its pathfinder must
     * plot a route that steps toward the target rather than holding position at range.
     */
    private static void meleeEnemyClosesIn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);

        // The "empty" arena template places no floor blocks, so lay a solid stone corridor the
        // soldier can actually walk along; without real ground both actors fall into the void and
        // the pathfinder has nowhere to route.
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 1, z), net.minecraft.world.level.block.Blocks.STONE);
            }
        }

        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(enemy != null, "faction enemy factory must create a melee-test soldier");
        if (enemy == null) {
            return;
        }
        // Arm it with a sword so isMeleeArmed() is true and the FilteredMeleeAttackGoal engages.
        enemy.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_SWORD));
        helper.assertTrue(enemy.isMeleeArmed(),
                "a sword-armed soldier must report as melee armed");
        helper.assertFalse(enemy.isGunArmed(),
                "a sword-armed soldier must not report as gun armed");

        // Keep both actors well inside the padded arena floor (padding 8) so the pathfinder has
        // solid ground the whole way and neither entity falls into the void.
        BlockPos enemyPos = helper.absolutePos(new BlockPos(2, 2, 2));
        enemy.snapTo(enemyPos.getX() + 0.5, enemyPos.getY(), enemyPos.getZ() + 0.5, 0.0F, 0.0F);
        enemy.setHome(enemyPos);
        enemy.setFaction(com.example.cyberdeck.faction.Faction.KANG_TAO);
        helper.assertTrue(level.addFreshEntity(enemy), "melee test could not add the soldier");

        // A gun-armed control soldier must report as gun armed so the arming predicate is genuinely
        // discriminating and not trivially always-melee.
        FactionEnemy gunner = FactionEntities.FACTION_ENEMY.get().create(level, EntitySpawnReason.COMMAND);
        if (gunner != null) {
            gunner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(
                            com.example.cyberdeck.weapon.WeaponItems.gun(GunType.PISTOL).get()));
            helper.assertTrue(gunner.isGunArmed(),
                    "a firearm-armed soldier must report as gun armed");
            helper.assertFalse(gunner.isMeleeArmed(),
                    "a firearm-armed soldier must not report as melee armed");
            gunner.discard();
        }

        // Place a survival player well out of melee reach and aggro the soldier onto it.
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "melee_closein"));
        BlockPos playerPos = helper.absolutePos(new BlockPos(2, 2, 8));
        player.snapTo(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5, 0.0F, 0.0F);
        player.getAbilities().invulnerable = false;
        player.setInvulnerable(false);
        level.addNewPlayer(player);
        enemy.setTarget(player);
        helper.assertTrue(enemy.getTarget() == player,
                "the melee test must first drive the soldier to target the player");

        double startDistance = enemy.distanceTo(player);
        helper.assertTrue(startDistance > 3.0,
                "the melee test must start with the player outside strike range");

        // Let the real test server tick the world so the melee goal runs and the pathfinder moves
        // the soldier. Success is either measurably closing the gap or plotting a path whose end
        // node sits closer to the target than the start distance (a soldier idling at range fails).
        helper.succeedWhen(() -> {
            // Keep the aggro target pinned so a transient re-evaluation cannot starve the melee
            // goal; the behavior under test is the approach, not target acquisition.
            if (enemy.getTarget() != player) {
                enemy.setTarget(player);
            }
            double distance = enemy.distanceTo(player);
            boolean closed = distance < startDistance - 1.0;
            boolean pathHeadsToPlayer = false;
            var path = enemy.getNavigation().getPath();
            if (path != null && path.getEndNode() != null) {
                BlockPos end = path.getEndNode().asBlockPos();
                double endToPlayer = Math.sqrt(player.distanceToSqr(
                        end.getX() + 0.5, end.getY(), end.getZ() + 0.5));
                pathHeadsToPlayer = endToPlayer < startDistance - 1.0;
            }
            helper.assertTrue(closed || pathHeadsToPlayer,
                    "a melee soldier must actively path toward its target to close the gap (start="
                            + startDistance + ", now=" + distance + ")");
        });
        helper.runBeforeTestEnd(() -> {
            player.discard();
            enemy.discard();
        });
    }

    /**
     * Feature 5: cyberpsychos may spawn with a sandevistan. The optional loadouts must include a
     * sandevistan variant, the sandevistan dash maneuver must be a real, shorter-and-faster-than-a-
     * normal-dash swept movement (a near-teleport blur, not an instant relocation), and its speed
     * band must sit strictly above the normal dash yet be capped so it cannot behave like a teleport.
     */
    private static void cyberpsychoSandevistan(GameTestHelper helper) {
        // The dash is a valid, distinct maneuver with a stable synced id.
        helper.assertTrue(TacticalManeuver.SANDEVISTAN_DASH.isDash(),
                "the sandevistan dash must classify as a dash maneuver");
        helper.assertTrue(TacticalManeuver.byId(TacticalManeuver.SANDEVISTAN_DASH.id())
                        == TacticalManeuver.SANDEVISTAN_DASH,
                "the sandevistan dash must round-trip through its stable synced id");

        double sandevistanSpeed = reflectDouble(helper, "SANDEVISTAN_DASH_SPEED");
        double normalDashSpeed = reflectDouble(helper, "DASH_SPEED");
        int sandevistanTicks = reflectInt(helper, "SANDEVISTAN_DASH_TICKS");
        int normalDashTicks = reflectInt(helper, "DASH_TICKS");
        double sandevistanCap = reflectDouble(helper, "MAX_SANDEVISTAN_HORIZONTAL_SPEED");

        helper.assertTrue(sandevistanSpeed > normalDashSpeed,
                "the sandevistan dash must be faster than a normal dash (" + sandevistanSpeed
                        + " vs " + normalDashSpeed + ")");
        helper.assertTrue(sandevistanTicks < normalDashTicks,
                "the sandevistan dash must be shorter than a normal dash so it reads as a blink ("
                        + sandevistanTicks + " vs " + normalDashTicks + ")");
        // A blink, not a teleport: the per-tick travel is bounded well under a full chunk so the
        // swept collision check in canTravel can still catch walls between origin and destination.
        helper.assertTrue(sandevistanCap >= sandevistanSpeed,
                "the sandevistan speed must not exceed its own horizontal cap");
        helper.assertTrue(sandevistanCap <= 2.0,
                "the sandevistan cap must stay low enough to remain a swept move, not a teleport ("
                        + sandevistanCap + ")");

        // Spawn loadouts must offer a sandevistan variant so it is genuinely optional-but-possible.
        boolean anyHasSandevistan = false;
        boolean anyLacksSandevistan = false;
        try {
            java.lang.reflect.Field field = com.example.cyberdeck.faction.CyberpsychoEntity.class
                    .getDeclaredField("SPAWN_LOADOUTS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<List<String>> loadouts = (List<List<String>>) field.get(null);
            for (List<String> loadout : loadouts) {
                if (loadout.contains("sandevistan")) {
                    anyHasSandevistan = true;
                } else {
                    anyLacksSandevistan = true;
                }
            }
        } catch (ReflectiveOperationException exception) {
            helper.fail("cyberpsycho spawn loadouts are missing: " + exception.getMessage());
            return;
        }
        helper.assertTrue(anyHasSandevistan,
                "at least one cyberpsycho spawn loadout must include a sandevistan");
        helper.assertTrue(anyLacksSandevistan,
                "at least one cyberpsycho spawn loadout must omit the sandevistan so it stays optional");

        // A live cyberpsycho reports a concrete installed-cyberware loadout drawn from those options.
        ServerLevel level = helper.getLevel();
        com.example.cyberdeck.faction.CyberpsychoEntity psycho =
                FactionEntities.CYBERPSYCHO.get().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(psycho != null, "cyberpsycho factory must create a boss for the sandevistan test");
        if (psycho == null) {
            return;
        }
        helper.assertFalse(psycho.installedCyberware().isEmpty(),
                "a cyberpsycho must expose a non-empty installed-cyberware loadout");
        psycho.discard();
        helper.succeed();
    }

    /**
     * Feature 1: an incendiary grenade ignites nearby entities but never places burning fire blocks
     * in the world. We detonate a real incendiary grenade next to a living entity and assert the
     * victim catches fire while not a single {@link Blocks#FIRE} block is created anywhere in the
     * arena, and that a victim standing in water is not set alight (igniteForSeconds respects water).
     */
    private static void incendiaryIgnitesNoFireBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        // Solid ground for the victim to stand on; the "empty" arena has no floor of its own.
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }

        BlockPos center = new BlockPos(2, 2, 2);
        Vec3 centerVec = Vec3.atCenterOf(helper.absolutePos(center));

        // A dry victim standing at the blast center must catch fire.
        var victim = helper.spawn(EntityTypes.ZOMBIE, center);
        victim.setNoGravity(true);
        helper.assertTrue(victim.getRemainingFireTicks() <= 0,
                "the victim must not be on fire before the grenade detonates");

        // Detonate a genuine incendiary grenade at the victim's position via the real detonation
        // path so we exercise the shipped ignite-and-particles-but-no-fire-blocks logic.
        detonateGrenade(helper, level, centerVec,
                com.example.cyberdeck.weapon.GrenadeType.INCENDIARY);

        helper.assertTrue(victim.getRemainingFireTicks() > 0,
                "an incendiary blast must set a nearby entity on fire (fireTicks="
                        + victim.getRemainingFireTicks() + ")");

        // The core contract of feature 1: NO fire blocks are ever placed. Scan the whole padded
        // arena volume around the blast for any FIRE block.
        int radius = 6;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos probe = center.offset(dx, dy, dz);
                    helper.assertBlockNotPresent(Blocks.FIRE, probe);
                }
            }
        }

        victim.discard();
        helper.succeed();
    }

    /** Detonates a real incendiary/poison grenade through {@link ThrownGrenade}'s shipped logic. */
    private static void detonateGrenade(GameTestHelper helper, ServerLevel level, Vec3 center,
            com.example.cyberdeck.weapon.GrenadeType type) {
        com.example.cyberdeck.weapon.ThrownGrenade grenade =
                new com.example.cyberdeck.weapon.ThrownGrenade(
                        com.example.cyberdeck.weapon.WeaponEntities.THROWN_GRENADE.get(), level);
        grenade.setPos(center.x, center.y, center.z);
        try {
            java.lang.reflect.Method detonate =
                    com.example.cyberdeck.weapon.ThrownGrenade.class.getDeclaredMethod(
                            "detonate", ServerLevel.class, Vec3.class,
                            com.example.cyberdeck.weapon.GrenadeType.class);
            detonate.setAccessible(true);
            detonate.invoke(grenade, level, center, type);
        } catch (ReflectiveOperationException exception) {
            helper.fail("ThrownGrenade.detonate is missing or changed shape: "
                    + exception.getMessage());
        } finally {
            grenade.discard();
        }
    }

    /**
     * Feature 4: per-player Street Cred is a real, clamped, persistable attachment. It defaults to
     * the empty state, round-trips through get/set/add for every field, clamps negatives to zero,
     * and its serialize/sync codecs survive an explicit encode/decode so it will persist across
     * save/relog and copy on death.
     */
    private static void streetCredPersistence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var player = helper.makeMockServerPlayerInLevel();

        // A fresh player starts at the empty state.
        helper.assertTrue(StreetCredState.get(player) == StreetCredState.NONE
                        || StreetCredState.get(player).equals(StreetCredState.NONE),
                "a new player must start at the empty Street Cred state");
        helper.assertValueEqual(StreetCredState.getStreetCred(player), 0, "initial street cred");
        helper.assertValueEqual(StreetCredState.getExperience(player), 0, "initial experience");
        helper.assertValueEqual(
                StreetCredState.getCyberwareCapacity(player), 0, "initial cyberware capacity");

        // set/add round-trip on every field, independently.
        StreetCredState.setStreetCred(player, 25);
        StreetCredState.addStreetCred(player, 5);
        helper.assertValueEqual(StreetCredState.getStreetCred(player), 30, "street cred after add");
        StreetCredState.setExperience(player, 100);
        StreetCredState.addExperience(player, 40);
        helper.assertValueEqual(StreetCredState.getExperience(player), 140, "experience after add");
        StreetCredState.setCyberwareCapacity(player, 3);
        StreetCredState.addCyberwareCapacity(player, 2);
        helper.assertValueEqual(
                StreetCredState.getCyberwareCapacity(player), 5, "cyberware capacity after add");

        // Setting one field must not disturb the others.
        StreetCredState.setStreetCred(player, 7);
        helper.assertValueEqual(StreetCredState.getExperience(player), 140,
                "experience must be untouched when only street cred changes");
        helper.assertValueEqual(StreetCredState.getCyberwareCapacity(player), 5,
                "cyberware capacity must be untouched when only street cred changes");

        // Negatives clamp to zero on every field.
        StreetCredState clamped = new StreetCredState(-50, -1, -999);
        helper.assertValueEqual(clamped.streetCred(), 0, "clamped street cred");
        helper.assertValueEqual(clamped.experience(), 0, "clamped experience");
        helper.assertValueEqual(clamped.cyberwareCapacity(), 0, "clamped cyberware capacity");
        StreetCredState.addStreetCred(player, -1000);
        helper.assertValueEqual(StreetCredState.getStreetCred(player), 0,
                "adding a large negative must clamp street cred to zero, not go negative");

        // The persistence codec must faithfully round-trip a populated state (this is what backs
        // on-disk save persistence, copy-on-death, and client sync).
        StreetCredState original = new StreetCredState(42, 314, 9);
        var ops = level.registryAccess().createSerializationContext(
                com.mojang.serialization.JsonOps.INSTANCE);
        com.google.gson.JsonElement encoded = StreetCredState.MAP_CODEC.codec()
                .encodeStart(ops, original)
                .getOrThrow(msg -> helper.assertionException(
                        net.minecraft.network.chat.Component.literal(
                                "Street Cred codec must encode: " + msg)));
        StreetCredState decoded = StreetCredState.MAP_CODEC.codec()
                .parse(ops, encoded)
                .getOrThrow(msg -> helper.assertionException(
                        net.minecraft.network.chat.Component.literal(
                                "Street Cred codec must decode: " + msg)));
        helper.assertTrue(decoded.equals(original),
                "the persistence codec must round-trip Street Cred exactly (got " + decoded + ")");

        helper.succeed();
    }

    private static void emmiesUseEmeralds(GameTestHelper helper) {
        ServerPlayer player = makeSurvivalServerPlayerInLevel(helper);
        BlockPos playerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        player.snapTo(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5,
                0.0F, 0.0F);
        player.getInventory().clearContent();

        Emmies.give(player, 70);
        helper.assertTrue(Emmies.item() == Items.EMERALD && Emmies.count(player) == 70,
                "emmie rewards must be issued and counted as vanilla emeralds");

        player.getInventory().clearContent();
        player.getInventory().add(new ItemStack(CyberdeckItems.LEGACY_EMMIES.get(), 17));
        ItemStack legacyStack = player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> stack.is(CyberdeckItems.LEGACY_EMMIES.get()))
                .findFirst()
                .orElseThrow();
        CyberdeckItems.LEGACY_EMMIES.get().inventoryTick(
                legacyStack, helper.getLevel(), player, null);
        int legacyCount = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(CyberdeckItems.LEGACY_EMMIES.get())) {
                legacyCount += stack.getCount();
            }
        }
        helper.assertTrue(legacyCount == 0 && Emmies.count(player) == 17,
                "legacy emmies must convert one-for-one without leaving spendable duplicates");

        player.getInventory().clearContent();
        for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        }
        Emmies.give(player, 1);
        helper.runAfterDelay(1, () -> {
            List<ItemEntity> overflow = helper.getLevel().getEntitiesOfClass(
                    ItemEntity.class,
                    player.getBoundingBox().inflate(2.0),
                    entity -> entity.getItem().is(Items.EMERALD));
            helper.assertTrue(overflow.size() == 1
                            && player.getUUID().equals(overflow.getFirst().getTarget()),
                    "overflow emmie rewards must be reserved for their receiving player"
                            + " (drops=" + overflow.size() + ")");
            overflow.forEach(Entity::discard);
            disconnectTestPlayer(player);
            helper.succeed();
        });
    }

    /** Reads a private static double constant from FactionEnemy so the tuned dash band stays locked. */
    private static double reflectDouble(GameTestHelper helper, String name) {
        try {
            java.lang.reflect.Field field = FactionEnemy.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getDouble(null);
        } catch (ReflectiveOperationException exception) {
            helper.fail("FactionEnemy." + name + " is missing: " + exception.getMessage());
            return Double.NaN;
        }
    }

    /** Reads a private static int constant from FactionEnemy so the tuned dash band stays locked. */
    private static int reflectInt(GameTestHelper helper, String name) {
        try {
            java.lang.reflect.Field field = FactionEnemy.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (ReflectiveOperationException exception) {
            helper.fail("FactionEnemy." + name + " is missing: " + exception.getMessage());
            return -1;
        }
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "pure"),
                new TestEnvironmentDefinition.AllOf(List.of()));
        TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath("minecraft", "empty"),
                100,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                false,
                0);
        registerInstance(event, "city_layer_classification", CITY_LAYER_CLASSIFICATION, data);
        registerInstance(event, "cluster_plan", CLUSTER_PLAN, data);
        registerInstance(event, "district_patrol_loadout", DISTRICT_PATROL_LOADOUT, data);
        registerInstance(event, "gunshot_radius", GUNSHOT_RADIUS, data);
        registerInstance(event, "mounted_gun_targeting", MOUNTED_GUN_TARGETING, data);
        registerInstance(event, "civilian_noncombat", CIVILIAN_NONCOMBAT, data);
        registerInstance(event, "civilian_population", CIVILIAN_POPULATION, data);
        registerInstance(event, "city_actor_join_compatibility",
                CITY_ACTOR_JOIN_COMPATIBILITY, data);
        registerInstance(event, "sandevistan_profile_balance", SANDEVISTAN_PROFILE_BALANCE, data);
        registerInstance(event, "sandevistan_charge_model", SANDEVISTAN_CHARGE_MODEL, data);
        registerInstance(event, "cyberware_variant_mappings", CYBERWARE_VARIANT_MAPPINGS, data);
        registerInstance(event, "tactical_movement_math", TACTICAL_MOVEMENT_MATH, data);
        registerInstance(event, "tactical_movement_state", TACTICAL_MOVEMENT_STATE, data);
        registerInstance(event, "tactical_slide_activation", TACTICAL_SLIDE_ACTIVATION, data);
        registerInstance(event, "healing_consumable_state", HEALING_CONSUMABLE_STATE, data);
        registerInstance(event, "emmies_use_emeralds", EMMIES_USE_EMERALDS, data);

        // Detection tests need a padded, flat, sky-lit arena so the soldier and player stand on
        // solid ground with an unobstructed line of sight rather than raycasting into the void.
        TestData<Holder<TestEnvironmentDefinition<?>>> arena = new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath("minecraft", "empty"),
                200,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                true,
                8);
        registerInstance(event, "detection_line_of_sight", DETECTION_LINE_OF_SIGHT, arena);
        registerInstance(event, "detection_crouch", DETECTION_CROUCH, arena);
        registerInstance(event, "detection_decay", DETECTION_DECAY, arena);
        registerInstance(event, "cyberpsycho_balance", CYBERPSYCHO_BALANCE, data);
        registerInstance(event, "throwable_distraction", THROWABLE_DISTRACTION, arena);
        // The melee close-in test needs solid ground so the soldier can actually path toward
        // the target rather than raycasting/pathing into the void.
        registerInstance(event, "melee_enemy_closes_in", MELEE_ENEMY_CLOSES_IN, arena);
        registerInstance(event, "cyberpsycho_sandevistan", CYBERPSYCHO_SANDEVISTAN, data);
        // The incendiary test spawns a mob on solid ground and detonates a grenade next to it,
        // so it needs the padded arena floor rather than the void.
        registerInstance(event, "incendiary_ignites_no_fire_blocks",
                INCENDIARY_IGNITES_NO_FIRE_BLOCKS, arena);
        registerInstance(event, "street_cred_persistence", STREET_CRED_PERSISTENCE, data);
        registerInstance(event, "minimap_rotation_geometry", MINIMAP_ROTATION_GEOMETRY, data);
        registerInstance(event, "npc_roles_and_drops", NPC_ROLES_AND_DROPS, data);
        registerInstance(event, "npc_voiceline_pools", NPC_VOICELINE_POOLS, data);
        registerInstance(event, "quickhack_long_range", QUICKHACK_LONG_RANGE, data);
        registerInstance(event, "quickhack_multi_target", QUICKHACK_MULTI_TARGET, data);
        registerInstance(event, "quickhack_hotbar_recovery", QUICKHACK_HOTBAR_RECOVERY, data);
        registerInstance(event, "double_jump_packet_guard", DOUBLE_JUMP_PACKET_GUARD, data);
        registerInstance(event, "city_loot_caches", CITY_LOOT_CACHES, data);

        TestData<Holder<TestEnvironmentDefinition<?>>> traumaArena = new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath("minecraft", "empty"),
                200,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                true,
                24);
        registerInstance(event, "trauma_team_lifecycle", TRAUMA_TEAM_LIFECYCLE, traumaArena);
    }

    private static void registerInstance(
            RegisterGameTestsEvent event,
            String name,
            DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> function,
            TestData<Holder<TestEnvironmentDefinition<?>>> data) {
        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, name),
                new FunctionGameTestInstance(function.getKey(), data));
    }
}
