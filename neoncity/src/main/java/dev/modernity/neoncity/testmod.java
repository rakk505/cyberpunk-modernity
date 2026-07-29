package dev.modernity.neoncity;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/** NeoForge entry point for the finite Project Moon Megacity generator. */
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
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DETERMINISTIC_SEED_LAYOUTS = register(
                    "deterministic_seed_layouts", ExampleGameTests::deterministicSeedLayouts);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CONNECTED_TRAVEL_GRAPH = register(
                    "connected_travel_graph", ExampleGameTests::connectedTravelGraph);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            FINITE_CITY_WILDERNESS = register(
                    "finite_city_wilderness", ExampleGameTests::finiteCityWilderness);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DISTRICT_ZONES_AND_CULTURE = register(
                    "district_zones_and_culture", ExampleGameTests::districtZonesAndCulture);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CONNECTION_CONTINUITY = register(
                    "connection_continuity", ExampleGameTests::connectionContinuity);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SPECIAL_DISTRICT_INFRASTRUCTURE = register(
                    "special_district_infrastructure", ExampleGameTests::specialDistrictInfrastructure);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ARNIS_PATCH_SELECTION = register(
                    "arnis_patch_selection", ExampleGameTests::arnisPatchSelection);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DISTRICT_ENTRY_NOTIFICATION = register(
                    "district_entry_notification", ExampleGameTests::districtEntryNotification);

    private volatile boolean generationEnabled;
    private final DistrictEntryNotifier districtEntryNotifier = new DistrictEntryNotifier();

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
        districtEntryNotifier.clear();
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
                "[NeonCity] finite 26-district generator enabled; prewarmed {} and queued {} chunks at {}",
                prewarmed, queued, spawn);
    }

    @SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!generationEnabled) return;
        ServerLevel overworld = event.getServer().overworld();
        if (overworld == null) return;
        if (event.getServer().getTickCount() % 10 == 0) {
            Set<UUID> activePlayers = new HashSet<>();
            for (net.minecraft.server.level.ServerPlayer player : overworld.players()) {
                activePlayers.add(player.getUUID());
                NeonCityGenerator.enqueueAround(player.getBlockX(), player.getBlockZ());
                MegacityLayout.Location location = NeonCityGenerator.layout().locate(
                        player.getBlockX(), player.getBlockZ());
                District notificationDistrict = DistrictEntryNotifier.inhabitedDistrict(
                        location.district(), location.zone());
                districtEntryNotifier.updatePlayer(player, notificationDistrict);
                if (location.district() == District.Y_CORP && location.insideCity()) {
                    overworld.sendParticles(
                            ParticleTypes.SNOWFLAKE,
                            player.getX(), player.getY() + 8.0, player.getZ(),
                            18, 9.0, 5.0, 9.0, 0.01);
                }
            }
            districtEntryNotifier.retainPlayers(activePlayers);
        }
        NeonCityGenerator.tick(overworld);
    }

    /** Reject natural spawn placement before a mob is constructed. */
    @SubscribeEvent
    public void onSpawnPlacement(MobSpawnEvent.SpawnPlacementCheck event) {
        ServerLevel level = event.getLevel().getLevel();
        if (NeonCityGenerator.isInsideCity(
                level, event.getPos().getX(), event.getPos().getZ())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /**
     * Strict fallback for spawners, commands, other mods, and entities loaded
     * from disk: no Mob is allowed to join the city at all.
     */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Mob)
                || !(event.getLevel() instanceof ServerLevel level)) return;
        if (NeonCityGenerator.isInsideCity(
                level, event.getEntity().getBlockX(), event.getEntity().getBlockZ())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent ignoredEvent) {
        generationEnabled = false;
        districtEntryNotifier.clear();
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
        registerInstance(event, "deterministic_seed_layouts", DETERMINISTIC_SEED_LAYOUTS, data);
        registerInstance(event, "connected_travel_graph", CONNECTED_TRAVEL_GRAPH, data);
        registerInstance(event, "finite_city_wilderness", FINITE_CITY_WILDERNESS, data);
        registerInstance(event, "district_zones_and_culture", DISTRICT_ZONES_AND_CULTURE, data);
        registerInstance(event, "connection_continuity", CONNECTION_CONTINUITY, data);
        registerInstance(event, "special_district_infrastructure", SPECIAL_DISTRICT_INFRASTRUCTURE, data);
        registerInstance(event, "arnis_patch_selection", ARNIS_PATCH_SELECTION, data);
        registerInstance(event, "district_entry_notification", DISTRICT_ENTRY_NOTIFICATION, data);
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
