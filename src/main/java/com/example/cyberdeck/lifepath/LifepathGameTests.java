package com.example.cyberdeck.lifepath;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareCapacity;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Starter loadout, one-time claim, capacity, and per-player isolation coverage. */
public final class LifepathGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Cyberdeck.MODID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            LIFEPATH_LOADOUTS = register(
                    "lifepath_loadouts", LifepathGameTests::lifepathLoadouts);

    private LifepathGameTests() {
    }

    public static void bootstrap(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(LifepathGameTests::registerGameTests);
    }

    private static DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> register(
            String name, Consumer<GameTestHelper> test) {
        return TEST_FUNCTIONS.register(name, () -> test);
    }

    private static void lifepathLoadouts(GameTestHelper helper) {
        FakePlayer netrunner = player(helper, "netrunner");
        FakePlayer brawler = player(helper, "brawler");
        FakePlayer merc = player(helper, "merc");
        FakePlayer veteran = player(helper, "veteran");
        FakePlayer overflow = player(helper, "overflow");

        helper.assertTrue(LifepathService.select(netrunner, Lifepath.NETRUNNER.id()),
                "Netrunner starter claim failed");
        assertState(helper, netrunner, Lifepath.NETRUNNER);
        assertCyberware(helper, netrunner,
                "jenkins_tendons_t2",
                "basic_kiroshi_optics_t2",
                "smart_link_t1",
                "paraline_mk_1_5_t1");
        assertCount(helper, netrunner, WeaponItems.gun(GunType.YUKIMURA).get(), 1,
                "Netrunner Yukimura count");
        assertCount(helper, netrunner, AmmoItems.item(AmmoType.HANDGUN).get(), 250,
                "Netrunner handgun ammo");
        assertValidCapacity(helper, netrunner);

        int netrunnerItems = occupiedSlots(netrunner);
        int netrunnerImplants = CyberwareAttachments.get(netrunner).installedCount();
        helper.assertTrue(LifepathService.select(netrunner, Lifepath.NETRUNNER.id()),
                "repeated identical selection should be idempotent");
        helper.assertTrue(!LifepathService.select(netrunner, Lifepath.BRAWLER.id()),
                "a player must not change an already-selected lifepath");
        helper.assertValueEqual(occupiedSlots(netrunner), netrunnerItems,
                "duplicate claim inventory slots");
        helper.assertValueEqual(CyberwareAttachments.get(netrunner).installedCount(),
                netrunnerImplants, "duplicate claim cyberware count");

        forceEightCapacityLegRoll(brawler);
        helper.assertTrue(LifepathService.select(brawler, Lifepath.BRAWLER.id()),
                "Brawler starter claim failed");
        assertState(helper, brawler, Lifepath.BRAWLER);
        String brawlerLeg = LifepathState.get(brawler).startingLegId();
        helper.assertTrue(LifepathService.isStartingLeg(brawlerLeg),
                "Brawler leg roll was outside the five minimum-tier leg families");
        assertCyberware(helper, brawler,
                brawlerLeg,
                "basic_kiroshi_optics_t2",
                "nano_plating_t2",
                "gorilla_arms_t2");
        assertCount(helper, brawler, WeaponItems.gun(GunType.TECH_SHOTGUN).get(), 1,
                "Brawler Tech Shotgun count");
        CyberwareData brawlerData = CyberwareAttachments.get(brawler);
        int expectedBrawlerBonus = Math.max(0,
                brawlerData.capacityUsed() - CyberwareCapacity.baseMaximum(brawler));
        helper.assertValueEqual(expectedBrawlerBonus, 13,
                "blank-player Brawler starter shortfall");
        helper.assertValueEqual(CyberwareAttachments.getBonusCapacity(brawler),
                expectedBrawlerBonus, "Brawler capacity shortfall grant");
        assertValidCapacity(helper, brawler);

        helper.assertTrue(LifepathService.select(merc, Lifepath.MERC.id()),
                "Merc starter claim failed");
        assertState(helper, merc, Lifepath.MERC);
        String mercLeg = LifepathState.get(merc).startingLegId();
        helper.assertTrue(LifepathService.isStartingLeg(mercLeg),
                "Merc leg roll was outside the five minimum-tier leg families");
        assertCyberware(helper, merc,
                mercLeg,
                "basic_kiroshi_optics_t2",
                "mantis_blades_t2");
        assertCount(helper, merc, WeaponItems.gun(GunType.ASSAULT_RIFLE).get(), 1,
                "Merc Assault Rifle count");
        assertCount(helper, merc, AmmoItems.item(AmmoType.HANDGUN).get(), 300,
                "Merc handgun ammo");
        assertValidCapacity(helper, merc);

        CyberwareData veteranData = new CyberwareData();
        veteranData.install(Cyberware.byId("scar_coalescer_t1"), 0);
        veteran.setData(CyberwareAttachments.CYBERWARE.get(), veteranData);
        helper.assertTrue(CyberwareCapacity.isValid(veteran, veteranData),
                "veteran test loadout must begin within normal capacity");
        helper.assertFalse(LifepathService.select(veteran, Lifepath.NETRUNNER.id()),
                "starter bonus must not subsidize unrelated existing cyberware");
        helper.assertFalse(LifepathState.get(veteran).selected(),
                "a rejected veteran claim must remain unselected");
        helper.assertValueEqual(CyberwareAttachments.getBonusCapacity(veteran), 0,
                "rejected veteran capacity bonus");
        helper.assertValueEqual(CyberwareAttachments.get(veteran).installedCount(), 1,
                "rejected veteran cyberware count");
        helper.assertValueEqual(occupiedSlots(veteran), 0,
                "rejected veteran inventory slots");

        BlockPos overflowPosition = helper.absolutePos(new BlockPos(1, 2, 1));
        overflow.snapTo(
                overflowPosition.getX() + 0.5, overflowPosition.getY(),
                overflowPosition.getZ() + 0.5, 0.0F, 0.0F);
        for (int slot = 0; slot < overflow.getInventory().getContainerSize(); slot++) {
            overflow.getInventory().setItem(slot, new ItemStack(Blocks.COBBLESTONE, 64));
        }
        helper.assertTrue(LifepathService.select(overflow, Lifepath.MERC.id()),
                "full-inventory starter claim failed");
        helper.runAtTickTime(2, () -> {
            List<ItemEntity> overflowDrops = helper.getLevel().getEntitiesOfClass(
                    ItemEntity.class, overflow.getBoundingBox().inflate(2.0),
                    drop -> drop.getItem().is(WeaponItems.gun(GunType.ASSAULT_RIFLE).get())
                            || drop.getItem().is(AmmoItems.item(AmmoType.HANDGUN).get()));
            helper.assertValueEqual(overflowDrops.size(), 2,
                    "full-inventory starter drop count");
            helper.assertTrue(overflowDrops.stream().allMatch(
                            drop -> overflow.getUUID().equals(drop.getTarget())),
                    "starter overflow drops must remain exclusive to their selecting player");

            // Claims are attachment-backed per player: selecting Brawler and Merc must not mutate
            // the first player's path or add either archetype's equipment to their inventory.
            assertState(helper, netrunner, Lifepath.NETRUNNER);
            helper.assertFalse(CyberwareAttachments.get(netrunner)
                            .hasFamily("gorilla_arms"),
                    "Brawler state leaked into the Netrunner player");
            assertCount(helper, netrunner, WeaponItems.gun(GunType.TECH_SHOTGUN).get(), 0,
                    "cross-player Tech Shotgun count");
            helper.succeed();
        });
    }

    private static FakePlayer player(GameTestHelper helper, String role) {
        UUID id = UUID.randomUUID();
        return new FakePlayer(helper.getLevel(),
                new GameProfile(id, role + "-" + id.toString().substring(0, 7)));
    }

    /** Forces the random package onto Leeroy or Reinforced Tendons (8 capacity => +13 total). */
    private static void forceEightCapacityLegRoll(FakePlayer player) {
        for (long seed = 0; seed < 100; seed++) {
            player.getRandom().setSeed(seed);
            int index = player.getRandom().nextInt(5);
            if (index == 2 || index == 4) {
                player.getRandom().setSeed(seed);
                return;
            }
        }
        throw new IllegalStateException("Could not seed an eight-capacity starter leg roll");
    }

    private static void assertState(
            GameTestHelper helper, FakePlayer player, Lifepath expected) {
        LifepathState state = LifepathState.get(player);
        helper.assertTrue(state.selected() && state.lifepath() == expected,
                "expected " + expected.id() + " state for " + player.getName().getString());
    }

    private static void assertCyberware(
            GameTestHelper helper, FakePlayer player, String... variantIds) {
        CyberwareData data = CyberwareAttachments.get(player);
        for (String variantId : variantIds) {
            Cyberware expected = Cyberware.byId(variantId);
            helper.assertTrue(expected != null && data.hasExact(expected),
                    "missing exact starter cyberware " + variantId);
        }
    }

    private static void assertValidCapacity(GameTestHelper helper, FakePlayer player) {
        CyberwareData data = CyberwareAttachments.get(player);
        helper.assertTrue(CyberwareCapacity.isValid(player, data),
                "starter loadout exceeded capacity: " + data.capacityUsed() + "/"
                        + CyberwareCapacity.maximum(player, data));
    }

    private static void assertCount(
            GameTestHelper helper, FakePlayer player, Item item, int expected, String label) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        helper.assertValueEqual(count, expected, label);
    }

    private static int occupiedSlots(FakePlayer player) {
        int occupied = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (!player.getInventory().getItem(slot).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "lifepath_pure"),
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
        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "lifepath_loadouts"),
                new FunctionGameTestInstance(LIFEPATH_LOADOUTS.getKey(), data));
    }
}
