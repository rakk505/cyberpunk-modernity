package com.example.cyberdeck;

import com.mojang.authlib.GameProfile;
import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.city.CityActorJoinCompatibility;
import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.cyberware.SandevistanProfile;
import com.example.cyberdeck.cyberware.SlotUnlock;
import com.example.cyberdeck.effect.SandevistanMechanics;
import com.example.cyberdeck.effect.SandevistanState;
import com.example.cyberdeck.effect.CyberwareEffects;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.faction.FactionSpawns;
import com.example.cyberdeck.healing.HealingConsumable;
import com.example.cyberdeck.healing.HealingState;
import com.example.cyberdeck.healing.HealingSystem;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.CityNpcSpawns;
import com.example.cyberdeck.npc.GunshotAlerts;
import com.example.cyberdeck.movement.TacticalAction;
import com.example.cyberdeck.movement.TacticalMovement;
import com.example.cyberdeck.movement.TacticalMovementState;
import com.example.cyberdeck.weapon.GunType;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
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
            GUNSHOT_RADIUS = register("gunshot_radius", CyberdeckGameTests::gunshotRadius);
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

    private static void clusterPlan(GameTestHelper helper) {
        long seed = FactionSpawns.clusterSeed(8675309L, 42L, -7, 11);
        helper.assertTrue(seed == FactionSpawns.clusterSeed(8675309L, 42L, -7, 11),
                "equal world/epoch/cell inputs must produce the same cluster seed");
        helper.assertTrue(seed != FactionSpawns.clusterSeed(8675309L, 43L, -7, 11),
                "cluster seed must change between epochs");
        helper.assertTrue(seed != FactionSpawns.clusterSeed(8675309L, 42L, -6, 11),
                "cluster seed must change between spatial cells");

        for (int rotation = 0; rotation < 4; rotation++) {
            List<BlockPos> first = FactionSpawns.formationOffsets(
                    rotation, FactionSpawns.CLUSTER_SIZE);
            List<BlockPos> second = FactionSpawns.formationOffsets(
                    rotation, FactionSpawns.CLUSTER_SIZE);
            helper.assertTrue(first.equals(second),
                    "formation plan changed for rotation " + rotation);
            helper.assertTrue(first.size() == FactionSpawns.CLUSTER_SIZE,
                    "formation returned the wrong member count for rotation " + rotation);
            helper.assertTrue(new HashSet<>(first).size() == first.size(),
                    "formation contains duplicate member offsets for rotation " + rotation);
        }
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
        Entity unrelated = EntityTypes.ZOMBIE.create(
                level, EntitySpawnReason.COMMAND);
        helper.assertTrue(civilian != null && enemy != null && unrelated != null,
                "entity factories needed by join compatibility must be available");
        if (civilian == null || enemy == null || unrelated == null) {
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
     * health/armour band and their self-heal must recharge 3x slower than the original cadence.
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
                        == 110.0,
                "cyberpsycho max health must be rebalanced to 110");
        helper.assertTrue(
                psycho.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)
                        == 10.0,
                "cyberpsycho armour must be rebalanced to 10");
        helper.assertTrue(
                psycho.getAttributeValue(
                                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS)
                        == 4.0,
                "cyberpsycho armour toughness must be rebalanced to 4");

        int healRecharge = cyberpsychoHealRecharge(helper);
        helper.assertTrue(healRecharge == 300,
                "cyberpsycho self-heal must recharge every 300 ticks (3x the original 100)");
        psycho.discard();
        helper.succeed();
    }

    /** Reads the private HEAL_RECHARGE_TICKS constant so the 3x nerf stays locked in. */
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
        registerInstance(event, "gunshot_radius", GUNSHOT_RADIUS, data);
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
