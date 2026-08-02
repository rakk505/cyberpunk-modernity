package dev.modernity.neoncity;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.city.CityActorJoinCompatibility;
import com.example.cyberdeck.network.DistrictAtmospherePacket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Project Moon world generation packaged as an internal Cyberdeck module. */
public final class ProjectMoonCityModule {
    public static final String DATA_NAMESPACE = "neoncity";

    private static final ProjectMoonCityModule INSTANCE = new ProjectMoonCityModule();
    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, Cyberdeck.MODID);

    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ALLEY_DFS = register("alley_dfs", ExampleGameTests::alleyDepthFirst);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ALLEY_SEAMS = register("alley_seams", ExampleGameTests::alleySeams);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DISTRICT_COVERAGE = register("district_coverage", ExampleGameTests::districtCoverage);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ORGANIC_ROADS = register("organic_roads", ExampleGameTests::organicRoads);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            PARK_TREE_LIBRARY = register("park_tree_library", ExampleGameTests::parkTreeLibrary);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MERCHANT_STALLS = register("merchant_stalls", ExampleGameTests::merchantStalls);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MISSION_SYSTEM = register("mission_system", ExampleGameTests::missionSystem);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            STORY_MISSION_DAG = register("story_mission_dag", MissionFeatureGameTests::storyDag);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            PARTY_REWARDS = register("party_rewards", MissionFeatureGameTests::partyRewards);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            GIG_BOARD_LIFECYCLE = register(
                    "gig_board_lifecycle", MissionFeatureGameTests::gigBoardLifecycle);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            MISSION_BUILDING_PLANNER = register(
                    "mission_building_planner", ExampleGameTests::missionBuildingPlanner);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            FACTION_PATROL_ROUTES = register(
                    "faction_patrol_routes", ExampleGameTests::factionPatrolRoutes);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            SKYLINE_HIERARCHY = register(
                    "skyline_hierarchy", ExampleGameTests::skylineHierarchy);
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
            URBAN_FOOTPRINT_CONTINUITY = register(
                    "urban_footprint_continuity", ExampleGameTests::urbanFootprintContinuity);
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
                    "special_district_infrastructure",
                    ExampleGameTests::specialDistrictInfrastructure);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            U_CORP_PORT_GENERATION = register(
                    "u_corp_port_generation", ExampleGameTests::uCorpPortGeneration);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DISTRICT_ENVIRONMENT = register(
                    "district_environment", ExampleGameTests::districtEnvironment);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ARNIS_PATCH_SELECTION = register(
                    "arnis_patch_selection", ExampleGameTests::arnisPatchSelection);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            ARNIS_FACADE_REPAIR = register(
                    "arnis_facade_repair", ExampleGameTests::arnisFacadeRepair);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DISTRICT_ENTRY_NOTIFICATION = register(
                    "district_entry_notification", ExampleGameTests::districtEntryNotification);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            QUICKTIME_ROUTING = register(
                    "quicktime_routing", ExampleGameTests::quicktimeRouting);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            CITY_MAP_PLAN = register(
                    "city_map_plan", ExampleGameTests::cityMapPlan);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            PEDESTRIAN_POLICY = register(
                    "pedestrian_policy", ExampleGameTests::pedestrianPolicy);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            DISTRICT_ATMOSPHERE = register(
                    "district_atmosphere", UrbanSystemsGameTests::districtAtmosphere);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            URBAN_SUPPLY_CRATES = register(
                    "urban_supply_crates", UrbanSystemsGameTests::urbanSupplyCrates);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>
            BUILDING_INSPECTION = register(
                    "building_inspection", BuildingInspectionGameTests::commandAndOverlayPlan);

    private volatile boolean generationEnabled;
    private int mainlineSites;
    private final DistrictEntryNotifier districtEntryNotifier = new DistrictEntryNotifier();
    private final Map<UUID, Integer> atmosphereDistricts = new HashMap<>();
    private long vendorRevision = -1L;

    private ProjectMoonCityModule() {
    }

    public static void bootstrap(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(INSTANCE::registerGameTests);
        NeoForge.EVENT_BUS.register(INSTANCE);
    }

    private static DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> register(
            String name, Consumer<GameTestHelper> test) {
        return TEST_FUNCTIONS.register("project_moon_" + name, () -> test);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        generationEnabled = false;
        mainlineSites = 0;
        ArnisBuildingAtlas.clear();
        districtEntryNotifier.clear();
        atmosphereDistricts.clear();
        vendorRevision = -1L;
        int missionCount = MissionCatalog.reloadConfiguration();
        int storyMissionCount = StoryMissionCatalog.reloadConfiguration();
        Cyberdeck.LOGGER.info(
                "[ProjectMoonCity] loaded {} gigs from {} and {} story missions from {}",
                missionCount,
                MissionCatalog.configurationPath().toAbsolutePath(),
                storyMissionCount,
                StoryMissionCatalog.configurationPath().toAbsolutePath());
        ServerLevel overworld = event.getServer().overworld();
        if (overworld == null || !NeonCityGenerator.initialize(overworld)) {
            NeonCityGenerator.reset();
            Cyberdeck.LOGGER.info(
                    "[ProjectMoonCity] overworld is not the dedicated megacity preset");
            return;
        }
        // Descriptors are data-only; atlas chunks remain untouched until a mission is accepted.
        mainlineSites = MainlineQuestService.restoreFixedWorldPlans(overworld);
        finishStartup(overworld);
    }

    @SubscribeEvent
    public void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        ServerLevel overworld = event.getServer().overworld();
        if (overworld == null) {
            return;
        }
        BuildingInspectionService.tick(overworld);
        if (!generationEnabled) {
            return;
        }
        if (event.getServer().getTickCount() % 10 == 0) {
            for (net.minecraft.server.level.ServerPlayer player
                    : event.getServer().getPlayerList().getPlayers()) {
                AmbientGigService.recordPresence(player);
            }
            Set<UUID> activePlayers = new HashSet<>();
            for (net.minecraft.server.level.ServerPlayer player : overworld.players()) {
                activePlayers.add(player.getUUID());
                NeonCityGenerator.enqueueAround(player.getBlockX(), player.getBlockZ());
                ChunkPos playerChunk = new ChunkPos(
                        player.getBlockX() >> 4, player.getBlockZ() >> 4);
                if (NeonCityGenerator.isGenerated(playerChunk)) {
                    QuicktimeTravelService.installCanonicalStations(overworld, playerChunk);
                }
                NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(
                        player.getBlockX(), player.getBlockZ());
                MegacityLayout.Location location = NeonCityGenerator.effectiveLocation(sample);
                if (location.insideCity()
                        && sample.zone() != MegacityLayout.Zone.WILDERNESS) {
                    VendorService.ensureDistrictVendors(overworld, location.district());
                    MainlineQuestService.maintainQuestNpcs(overworld, location.district());
                }
                MissionService.tickPlayer(player, location);
                AmbientGigService.tick(player);
                boolean uCorpMarine = UCorpPortGeneration.plan(NeonCityGenerator.layout())
                        .isManagedAt(player.getBlockX(), player.getBlockZ());
                District notificationDistrict = uCorpMarine
                        ? District.U_CORP
                        : DistrictEntryNotifier.inhabitedDistrict(
                                sample.district(), sample.zone());
                districtEntryNotifier.updatePlayer(player, notificationDistrict);
                District atmosphereDistrict = sample.zone() != MegacityLayout.Zone.WILDERNESS
                        ? sample.district()
                        : null;
                updateAtmosphere(overworld, player, atmosphereDistrict);
            }
            MissionService.tickCompletedSites(overworld);
            VendorService.maintainAnchors(overworld);
            long currentVendorRevision = VendorAnchorData.get(overworld).revision();
            if (currentVendorRevision != vendorRevision) {
                vendorRevision = currentVendorRevision;
                for (net.minecraft.server.level.ServerPlayer player : overworld.players()) {
                    CityMapService.open(player, false);
                }
            }
            districtEntryNotifier.retainPlayers(activePlayers);
            atmosphereDistricts.keySet().retainAll(activePlayers);
        }
        NeonCityGenerator.tick(overworld);
    }

    private void finishStartup(ServerLevel overworld) {
        int prewarmed = NeonCityGenerator.prewarmSpawn(overworld);
        BlockPos spawn = overworld.getRespawnData().pos();
        int queued = NeonCityGenerator.enqueueAround(spawn.getX(), spawn.getZ());
        generationEnabled = true;
        Cyberdeck.LOGGER.info(
                "[ProjectMoonCity] finite {}-district generator enabled immediately; restored {} "
                        + "persisted mainline sites, prewarmed {} and queued {} chunks at {}",
                District.values().length, mainlineSites, prewarmed, queued, spawn);
    }

    /** Reject ambient spawn placement inside the generated city. */
    @SubscribeEvent
    public void onSpawnPlacement(MobSpawnEvent.SpawnPlacementCheck event) {
        ServerLevel level = event.getLevel().getLevel();
        if (NeonCityGenerator.isInsideCity(
                level, event.getPos().getX(), event.getPos().getZ())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /** Block ambient mobs while preserving Cyberdeck-managed civilians and faction actors. */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && MissionService.removeIfTerminal(level, event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        if (event.getEntity() instanceof net.minecraft.world.entity.item.ItemEntity item
                && event.getLevel() instanceof ServerLevel level
                && MissionService.isExpiredCargo(level, item.getItem())) {
            event.setCanceled(true);
            return;
        }
        if (event.getLevel() instanceof ServerLevel level
                && MerchantTruckLibrary.isMerchant(event.getEntity())) {
            VendorService.registerLoadedMerchant(level, event.getEntity());
            return;
        }
        if (!(event.getEntity() instanceof Mob)
                || MissionService.isMissionActor(event.getEntity())
                || CityActorJoinCompatibility.isManagedCityActor(event.getEntity())
                || DistrictWorldFeatures.isSCorpFarmer(event.getEntity())
                || MerchantTruckLibrary.isMerchant(event.getEntity())
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (NeonCityGenerator.isInsideCity(
                level, event.getEntity().getBlockX(), event.getEntity().getBlockZ())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onMerchantInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (MainlineQuestService.isQuestNpc(event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                MissionService.interactStoryNpc(player, event.getTarget());
            }
            return;
        }
        if (MerchantTruckLibrary.merchantRole(event.getTarget()).orElse(null)
                != MerchantTruckLibrary.MerchantRole.QUEST) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            MissionService.open(player, event.getTarget());
        }
    }

    @SubscribeEvent
    public void onMissionActorDeath(LivingDeathEvent event) {
        MissionService.onEntityDeath(event);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            MissionService.onPlayerLogin(player);
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        MissionService.onPlayerClone(event.getOriginal(), event.getEntity());
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            BuildingInspectionService.forget(player.getUUID());
            MissionService.forgetPlayer(player);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent ignoredEvent) {
        generationEnabled = false;
        districtEntryNotifier.clear();
        atmosphereDistricts.clear();
        vendorRevision = -1L;
        BuildingInspectionService.reset();
        QuicktimeTravelService.clearRuntimeState();
        MissionService.reset();
        NeonCityGenerator.reset();
    }

    private void updateAtmosphere(
            ServerLevel level,
            net.minecraft.server.level.ServerPlayer player,
            District district) {
        int ordinal = district == null ? -1 : district.ordinal();
        Integer previous = atmosphereDistricts.put(player.getUUID(), ordinal);
        if (previous == null || previous != ordinal) {
            PacketDistributor.sendToPlayer(player, new DistrictAtmospherePacket(ordinal));
        }
        if (district != null) {
            DistrictAtmosphere.tickPlayer(level, player, district);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        NeonCityCommand.register(event.getDispatcher());
        MissionCommands.register(event.getDispatcher());
        PartyService.registerCommands(event.getDispatcher());
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "project_moon_pure"),
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
        registerInstance(event, "park_tree_library", PARK_TREE_LIBRARY, data);
        registerInstance(event, "merchant_stalls", MERCHANT_STALLS, data);
        registerInstance(event, "mission_system", MISSION_SYSTEM, data);
        registerInstance(event, "story_mission_dag", STORY_MISSION_DAG, data);
        registerInstance(event, "party_rewards", PARTY_REWARDS, data);
        registerInstance(event, "gig_board_lifecycle", GIG_BOARD_LIFECYCLE, data);
        registerInstance(event, "mission_building_planner", MISSION_BUILDING_PLANNER, data);
        registerInstance(event, "faction_patrol_routes", FACTION_PATROL_ROUTES, data);
        registerInstance(event, "skyline_hierarchy", SKYLINE_HIERARCHY, data);
        registerInstance(event, "negative_determinism", NEGATIVE_DETERMINISM, data);
        registerInstance(event, "deterministic_seed_layouts", DETERMINISTIC_SEED_LAYOUTS, data);
        registerInstance(event, "connected_travel_graph", CONNECTED_TRAVEL_GRAPH, data);
        registerInstance(
                event,
                "urban_footprint_continuity",
                URBAN_FOOTPRINT_CONTINUITY,
                data);
        registerInstance(event, "finite_city_wilderness", FINITE_CITY_WILDERNESS, data);
        registerInstance(event, "district_zones_and_culture", DISTRICT_ZONES_AND_CULTURE, data);
        registerInstance(event, "connection_continuity", CONNECTION_CONTINUITY, data);
        registerInstance(
                event,
                "special_district_infrastructure",
                SPECIAL_DISTRICT_INFRASTRUCTURE,
                data);
        registerInstance(event, "u_corp_port_generation", U_CORP_PORT_GENERATION, data);
        registerInstance(event, "district_environment", DISTRICT_ENVIRONMENT, data);
        registerInstance(event, "arnis_patch_selection", ARNIS_PATCH_SELECTION, data);
        registerInstance(event, "arnis_facade_repair", ARNIS_FACADE_REPAIR, data);
        registerInstance(
                event,
                "district_entry_notification",
                DISTRICT_ENTRY_NOTIFICATION,
                data);
        registerInstance(event, "quicktime_routing", QUICKTIME_ROUTING, data);
        registerInstance(event, "city_map_plan", CITY_MAP_PLAN, data);
        registerInstance(event, "pedestrian_policy", PEDESTRIAN_POLICY, data);
        registerInstance(event, "district_atmosphere", DISTRICT_ATMOSPHERE, data);
        registerInstance(event, "urban_supply_crates", URBAN_SUPPLY_CRATES, data);
        registerInstance(event, "building_inspection", BUILDING_INSPECTION, data);
    }

    private static void registerInstance(
            RegisterGameTestsEvent event,
            String name,
            DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> function,
            TestData<Holder<TestEnvironmentDefinition<?>>> data) {
        event.registerTest(
                Identifier.fromNamespaceAndPath(Cyberdeck.MODID, "project_moon_" + name),
                new FunctionGameTestInstance(function.getKey(), data));
    }
}
