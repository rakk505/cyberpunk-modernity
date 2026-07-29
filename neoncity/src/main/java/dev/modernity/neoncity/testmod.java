package dev.modernity.neoncity;

import com.mojang.logging.LogUtils;
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
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/** NeoForge entry point for the infinite Neon Megacity generator. */
@Mod(testmod.MODID)
public final class testmod {
    public static final String MODID = "neoncity";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, MODID);

    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ALLEY_DFS = register("alley_dfs", ExampleGameTests::alleyDepthFirst);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ALLEY_SEAMS = register("alley_seams", ExampleGameTests::alleySeams);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DISTRICT_COVERAGE = register("district_coverage", ExampleGameTests::districtCoverage);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ORGANIC_ROADS = register("organic_roads", ExampleGameTests::organicRoads);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SKYLINE_HIERARCHY = register("skyline_hierarchy", ExampleGameTests::skylineHierarchy);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            NEGATIVE_DETERMINISM = register(
                    "negative_determinism", ExampleGameTests::negativeDeterminism);

    private volatile boolean generationEnabled;

    public testmod(IEventBus modEventBus, ModContainer ignoredContainer) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(this::registerGameTests);
        NeoForge.EVENT_BUS.register(this);
    }

    private static DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> register(
            String name, Consumer<GameTestHelper> test) {
        return TEST_FUNCTIONS.register(name, () -> test);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        generationEnabled = false;
        ServerLevel overworld = event.getServer().overworld();
        if (overworld == null || !NeonCityGenerator.initialize(overworld)) {
            NeonCityGenerator.reset();
            LOGGER.info("[NeonCity] overworld is not the dedicated Neon Megacity preset");
            return;
        }
        int prewarmed = NeonCityGenerator.prewarmSpawn(overworld);
        BlockPos spawn = overworld.getRespawnData().pos();
        int queued = NeonCityGenerator.enqueueAround(spawn.getX(), spawn.getZ());
        generationEnabled = true;
        LOGGER.info(
                "[NeonCity] infinite generator enabled; prewarmed {} and queued {} chunks at {}",
                prewarmed, queued, spawn);
    }

    @SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!generationEnabled) return;
        ServerLevel overworld = event.getServer().overworld();
        if (overworld == null) return;
        if (event.getServer().getTickCount() % 10 == 0) {
            for (net.minecraft.server.level.ServerPlayer player : overworld.players()) {
                NeonCityGenerator.enqueueAround(player.getBlockX(), player.getBlockZ());
            }
        }
        NeonCityGenerator.tick(overworld);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent ignoredEvent) {
        generationEnabled = false;
        NeonCityGenerator.reset();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        NeonCityCommand.register(event.getDispatcher());
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(MODID, "pure"),
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
        registerInstance(event, "alley_dfs", ALLEY_DFS, data);
        registerInstance(event, "alley_seams", ALLEY_SEAMS, data);
        registerInstance(event, "district_coverage", DISTRICT_COVERAGE, data);
        registerInstance(event, "organic_roads", ORGANIC_ROADS, data);
        registerInstance(event, "skyline_hierarchy", SKYLINE_HIERARCHY, data);
        registerInstance(event, "negative_determinism", NEGATIVE_DETERMINISM, data);
    }

    private static void registerInstance(
            RegisterGameTestsEvent event,
            String name,
            DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> function,
            TestData<Holder<TestEnvironmentDefinition<?>>> data) {
        event.registerTest(
                Identifier.fromNamespaceAndPath(MODID, name),
                new FunctionGameTestInstance(function.getKey(), data));
    }
}
