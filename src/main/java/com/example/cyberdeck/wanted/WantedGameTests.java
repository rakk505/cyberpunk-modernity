package com.example.cyberdeck.wanted;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.weapon.GunItem;
import com.mojang.authlib.GameProfile;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MegacityLayout;
import io.netty.channel.embedded.EmbeddedChannel;
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
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Deterministic wanted-system transition, agent, and Aerodyne coverage. */
public final class WantedGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Cyberdeck.MODID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            WANTED_RULES = register("wanted_rules", WantedGameTests::wantedRules);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            EXCISION_AGENT = register("excision_agent", WantedGameTests::excisionAgent);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            EXCISION_AERODYNE = register("excision_aerodyne", WantedGameTests::excisionAerodyne);

    private WantedGameTests() {
    }

    public static void bootstrap(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(WantedGameTests::registerGameTests);
    }

    private static DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> register(
            String name, Consumer<GameTestHelper> test) {
        return TEST_FUNCTIONS.register(name, () -> test);
    }

    private static void wantedRules(GameTestHelper helper) {
        WantedState state = WantedState.NONE
                .recordNpcKill(District.A_CORP.ordinal())
                .recordNpcKill(District.A_CORP.ordinal());
        helper.assertTrue(state.stars() == 0,
                "fewer than three NPC kills must not start a wanted level");
        state = state.recordNpcKill(District.A_CORP.ordinal());
        helper.assertTrue(state.stars() == 1
                        && state.districtOrdinal() == District.A_CORP.ordinal(),
                "the third NPC kill must bind a one-star pursuit to its district");
        state = state.recordExcisionKill();
        helper.assertTrue(state.stars() == 1,
                "one Excision kill must not escalate the pursuit");
        state = state.recordExcisionKill();
        helper.assertTrue(state.stars() == 3 && state.excisionKills() == 2,
                "two Excision kills must escalate directly to three stars");

        MegacityLayout layout = MegacityLayout.create(0x4558434953494F4EL);
        MegacityLayout.Node node = layout.node(District.A_CORP);
        int edgeX = node.x();
        int limit = node.x() + node.radiusX() * 2;
        while (edgeX < limit
                && layout.normalizedDistanceTo(node, edgeX, node.z())
                        <= WantedSystem.DISTRICT_EDGE_SCORE) {
            edgeX++;
        }
        helper.assertTrue(!WantedSystem.isBeyondDistrictEdge(
                        layout, District.A_CORP, edgeX + 54, node.z(), 55),
                "the pursuit must remain active inside the 55-block district-edge grace band");
        helper.assertTrue(WantedSystem.isBeyondDistrictEdge(
                        layout, District.A_CORP, edgeX + 57, node.z(), 55),
                "the pursuit must clear after moving at least 55 blocks beyond the district edge");
        helper.assertTrue(WantedSystem.isOutsideView(
                        new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 0.0, -30.0))
                        && !WantedSystem.isOutsideView(
                                new Vec3(0.0, 0.0, 1.0), new Vec3(0.0, 0.0, 30.0)),
                "Excision spawn visibility must distinguish behind-player and visible positions");
        helper.succeed();
    }

    private static void excisionAgent(GameTestHelper helper) {
        for (int x = 1; x <= 8; x++) {
            for (int z = 1; z <= 5; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        ServerPlayer player = makeSurvivalServerPlayerInLevel(helper);
        BlockPos playerPos = helper.absolutePos(new BlockPos(2, 2, 2));
        player.snapTo(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5,
                0.0F, 0.0F);
        BlockPos agentPos = helper.absolutePos(new BlockPos(6, 2, 2));
        FactionEnemy agent = WantedSystem.spawnExcisionAgentForTest(
                helper.getLevel(), agentPos, player);
        helper.assertTrue(agent != null, "Excision agent factory failed");
        if (agent == null) {
            return;
        }
        helper.assertTrue(agent.isExcisionTarget(player.getUUID())
                        && agent.getTarget() == player,
                "Excision identity must retain its assigned wanted player");
        helper.assertTrue(agent.getMainHandItem().getItem() instanceof GunItem,
                "Excision agents must deploy with a firearm");
        helper.assertTrue(agent.getAttributeValue(Attributes.MAX_HEALTH) == 32.0
                        && agent.getAttributeValue(Attributes.ARMOR) == 7.0,
                "Excision cyberware durability attributes changed");
        helper.assertTrue(agent.hasEffect(MobEffects.SPEED)
                        && agent.hasEffect(MobEffects.RESISTANCE),
                "Excision agents must carry reflex and subdermal cyberware effects");
        agent.discard();
        player.connection.disconnect(Component.literal("GameTest complete"));
        helper.succeed();
    }

    private static void excisionAerodyne(GameTestHelper helper) {
        for (int x = 1; x <= 31; x++) {
            for (int z = 1; z <= 19; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.CREATIVE);
        BlockPos playerPos = helper.absolutePos(new BlockPos(1, 2, 1));
        player.snapTo(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5,
                0.0F, 0.0F);
        BlockPos landing = helper.absolutePos(new BlockPos(16, 2, 10));
        UUID playerId = player.getUUID();
        helper.assertTrue(WantedSystem.hasAerodyneClearance(helper.getLevel(), landing),
                "prepared Excision arena must fit the full Aerodyne and two-block hover gap");
        helper.assertTrue(WantedSystem.requestAerodyneAtForTest(
                        helper.getLevel(), player, landing, 2),
                "black Excision Aerodyne failed to start");

        helper.runAfterDelay(12, () -> {
            helper.assertTrue(WantedSystem.aerodynePhaseFor(helper.getLevel(), playerId)
                            == WantedSystem.AerodynePhase.LANDED,
                    "Excision Aerodyne did not reach its two-block hover position");
            helper.assertTrue(WantedSystem.aerodyneAgentCount(helper.getLevel(), playerId)
                            == WantedSystem.AERODYNE_WAVE_SIZE,
                    "Excision Aerodyne must deploy exactly four agents");
            List<FactionEnemy> agents = helper.getLevel().getEntitiesOfClass(
                    FactionEnemy.class, new AABB(landing).inflate(32.0),
                    enemy -> enemy.isExcisionTarget(playerId));
            helper.assertTrue(agents.size() == WantedSystem.AERODYNE_WAVE_SIZE,
                    "all four Aerodyne passengers must carry Excision identity");
        });

        helper.runAfterDelay(112, () -> {
            helper.assertTrue(WantedSystem.aerodynePhaseFor(helper.getLevel(), playerId) == null,
                    "Excision Aerodyne did not lift off after deployment");
            for (int x = 5; x <= 27; x++) {
                for (int y = 4; y <= 14; y++) {
                    for (int z = 5; z <= 15; z++) {
                        helper.assertTrue(helper.getBlockState(new BlockPos(x, y, z)).isAir(),
                                "Excision Aerodyne left a block at " + x + "," + y + "," + z);
                    }
                }
            }
            player.connection.disconnect(Component.literal("GameTest complete"));
            helper.succeed();
        });
    }

    private static ServerPlayer makeSurvivalServerPlayerInLevel(GameTestHelper helper) {
        UUID playerId = UUID.randomUUID();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(playerId, "wanted-" + playerId.toString().substring(0, 7)),
                false);
        ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                cookie.gameProfile(),
                cookie.clientInformation()) {
            @Override
            public GameType gameMode() {
                return GameType.SURVIVAL;
            }
        };
        GameType.SURVIVAL.updatePlayerAbilities(player.getAbilities());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void registerGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "wanted_pure"),
                new TestEnvironmentDefinition.AllOf(List.of()));
        TestData<Holder<TestEnvironmentDefinition<?>>> rules = new TestData<>(
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
        TestData<Holder<TestEnvironmentDefinition<?>>> arena = new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath("minecraft", "empty"),
                180,
                0,
                true,
                Rotation.NONE,
                false,
                1,
                1,
                true,
                24);
        registerInstance(event, "wanted_rules", WANTED_RULES, rules);
        registerInstance(event, "excision_agent", EXCISION_AGENT, arena);
        registerInstance(event, "excision_aerodyne", EXCISION_AERODYNE, arena);
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
