package com.example.cyberdeck;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.cyberware.SandevistanProfile;
import com.example.cyberdeck.cyberware.SlotUnlock;
import com.example.cyberdeck.effect.SandevistanMechanics;
import com.example.cyberdeck.effect.SandevistanState;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.function.Consumer;

/** Registry-based GameTests for the Sandevistan profile and charge model. */
public final class CyberdeckGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Cyberdeck.MODID);

    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PROFILE_BALANCE =
            TEST_FUNCTIONS.register("sandevistan_profile_balance", () -> CyberdeckGameTests::profileBalance);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CHARGE_MODEL =
            TEST_FUNCTIONS.register("sandevistan_charge_model", () -> CyberdeckGameTests::chargeModel);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> VARIANT_MAPPINGS =
            TEST_FUNCTIONS.register("sandevistan_variant_mappings", () -> CyberdeckGameTests::variantMappings);

    private CyberdeckGameTests() {
    }

    public static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(CyberdeckGameTests::registerInstances);
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

    private static void registerInstances(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "sandevistan"),
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

        event.registerTest(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "sandevistan_profile_balance"),
                new FunctionGameTestInstance(PROFILE_BALANCE.getKey(), data));
        event.registerTest(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "sandevistan_charge_model"),
                new FunctionGameTestInstance(CHARGE_MODEL.getKey(), data));
        event.registerTest(Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "sandevistan_variant_mappings"),
                new FunctionGameTestInstance(VARIANT_MAPPINGS.getKey(), data));
    }
}
