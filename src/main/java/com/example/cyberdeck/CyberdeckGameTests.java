package com.example.cyberdeck;

import com.example.cyberdeck.city.CityWorlds;
import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.cyberware.SandevistanProfile;
import com.example.cyberdeck.cyberware.SlotUnlock;
import com.example.cyberdeck.effect.SandevistanMechanics;
import com.example.cyberdeck.effect.SandevistanState;
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
            SANDEVISTAN_PROFILE_BALANCE = register(
                    "sandevistan_profile_balance", CyberdeckGameTests::profileBalance);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SANDEVISTAN_CHARGE_MODEL = register(
                    "sandevistan_charge_model", CyberdeckGameTests::chargeModel);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CYBERWARE_VARIANT_MAPPINGS = register(
                    "cyberware_variant_mappings", CyberdeckGameTests::variantMappings);

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
        registerInstance(event, "sandevistan_profile_balance", SANDEVISTAN_PROFILE_BALANCE, data);
        registerInstance(event, "sandevistan_charge_model", SANDEVISTAN_CHARGE_MODEL, data);
        registerInstance(event, "cyberware_variant_mappings", CYBERWARE_VARIANT_MAPPINGS, data);
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
