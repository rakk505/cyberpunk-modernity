package com.example.cyberdeck;

import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.faction.FactionSpawns;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.CityNpcEntities;
import com.example.cyberdeck.npc.GunshotAlerts;
import com.example.cyberdeck.weapon.GunType;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Small synchronous regression suite for city NPC and clustered-enemy invariants. */
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

        helper.assertTrue(CityWorlds.classifyLayers(
                        List.of(bedrock, black, black, black, black))
                        == CityWorlds.Kind.CYBERDECK,
                "exact Cyberdeck flat layers must be accepted");
        helper.assertTrue(CityWorlds.classifyLayers(List.of(black, cyan))
                        == CityWorlds.Kind.NEON_CITY,
                "exact Neon City flat layers must be accepted");
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
