package dev.modernity.neoncity;

import com.example.cyberdeck.economy.Emmies;
import com.example.cyberdeck.network.GigJournalPacket;
import com.example.cyberdeck.player.StreetCredState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Focused progression invariants kept separate from the four objective end-to-end tests. */
final class MissionFeatureGameTests {
    private MissionFeatureGameTests() {
    }

    static void storyDag(GameTestHelper helper) {
        CityRoutePlanner.Route navigationRoute = new CityRoutePlanner.Route(
                List.of(
                        new CityRoutePlanner.Point(0.0, 0.0),
                        new CityRoutePlanner.Point(10.0, 0.0),
                        new CityRoutePlanner.Point(10.0, 10.0)),
                List.of(),
                20.0);
        helper.assertTrue(NavigationTrailService.sampleRoute(
                                navigationRoute, 12.0, 4.0)
                        .equals(List.of(
                                new CityRoutePlanner.Point(4.0, 0.0),
                                new CityRoutePlanner.Point(8.0, 0.0),
                                new CityRoutePlanner.Point(10.0, 2.0)))
                        && NavigationTrailService.sampleRoute(
                                navigationRoute, 9.0, 4.0).size() == 2
                        && NavigationTrailService.sampleRoute(
                                navigationRoute, 12.0, 0.0).isEmpty(),
                "navigation particles are not evenly spaced and distance-bounded");

        ServerLevel navigationLevel = helper.getLevel();
        net.minecraft.core.BlockPos navigationBase = helper.absolutePos(
                new net.minecraft.core.BlockPos(1, 3, 1));
        int navigationFeetY = navigationBase.getY() + 1;
        for (int offsetX = 0; offsetX <= 10; offsetX++) {
            for (int offsetZ = 0; offsetZ <= 8; offsetZ++) {
                net.minecraft.core.BlockPos floor = navigationBase.offset(offsetX, 0, offsetZ);
                navigationLevel.setBlock(
                        floor,
                        net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_ALL);
                for (int clearY = 1; clearY <= 3; clearY++) {
                    navigationLevel.setBlock(
                            floor.above(clearY),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                            net.minecraft.world.level.block.Block.UPDATE_ALL);
                }
            }
        }
        net.minecraft.core.BlockPos navigationStart = navigationBase.offset(1, 1, 4);
        net.minecraft.core.BlockPos navigationTarget = navigationBase.offset(9, 1, 4);
        net.minecraft.core.BlockPos navigationWall = navigationBase.offset(5, 1, 4);
        navigationLevel.setBlock(
                navigationWall,
                net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        navigationLevel.setBlock(
                navigationWall.above(),
                net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        ServerPlayer navigationPlayer = makeUniquePlayer(helper, "navpath");
        navigationPlayer.setPos(
                navigationStart.getX() + 0.5,
                navigationFeetY,
                navigationStart.getZ() + 0.5);
        navigationPlayer.setOnGround(true);
        List<net.minecraft.world.phys.Vec3> openPath = NavigationTrailService.findOpenPath(
                navigationLevel, navigationPlayer, navigationTarget);
        double directZ = navigationStart.getZ() + 0.5;
        boolean pathTurns = openPath.stream().anyMatch(
                point -> Math.abs(point.z() - directZ) >= 0.75);
        boolean pathCrossesWall = openPath.stream().anyMatch(point ->
                net.minecraft.core.BlockPos.containing(point).getX() == navigationWall.getX()
                        && net.minecraft.core.BlockPos.containing(point).getZ()
                                == navigationWall.getZ());
        boolean pathReachesTarget = !openPath.isEmpty()
                && Math.hypot(
                        openPath.getLast().x() - (navigationTarget.getX() + 0.5),
                        openPath.getLast().z() - (navigationTarget.getZ() + 0.5)) <= 1.5;
        disconnect(navigationPlayer);
        helper.assertTrue(openPath.size() >= 3
                        && pathTurns
                        && !pathCrossesWall
                        && pathReachesTarget,
                "navigation did not follow a collision-safe open path around a wall: "
                        + openPath);
        List<net.minecraft.core.BlockPos> guardCandidates = List.of(
                new net.minecraft.core.BlockPos(0, 0, 0),
                new net.minecraft.core.BlockPos(3, 0, 0),
                new net.minecraft.core.BlockPos(6, 0, 0));
        List<net.minecraft.core.BlockPos> guardSelection = MissionService.selectGuardPositions(
                guardCandidates, 2, 1);
        helper.assertTrue(guardSelection.size() == 2
                        && guardSelection.contains(guardCandidates.getFirst())
                        && guardSelection.contains(guardCandidates.getLast()),
                "guard placement relaxed spacing before trying a valid whole-floor layout");
        MegacityLayout fixedLayout = NeonCityGenerator.fixedLayout();
        MegacityLayout recreatedFixedLayout = MegacityLayout.create(50_520_260_801L);
        helper.assertTrue(NeonCityGenerator.contentSeed() == 50_520_260_801L
                        && fixedLayout.seed() == recreatedFixedLayout.seed()
                        && fixedLayout.nodes().equals(recreatedFixedLayout.nodes())
                        && fixedLayout.edges().equals(recreatedFixedLayout.edges()),
                "city and mainline content no longer use canonical seed 50520260801");
        Map<String, MissionBuildingPlanner.Site> fixedSites = MainlineQuestData.fixedSites();
        Map<String, String> expectedSiteIds = Map.of(
                "m01_deliver_datashards", "g:71:12:e67adada6fea42bf",
                "m02_assassinate_g_exec", "g:72:11:e7227c874cf5a54e",
                "m03_steal_weights", "o:-76:192:9be67862fd808952",
                "m04_assassinate_fixer", "d:-197:-59:1cb4b96cfc3905f0");
        Map<String, District> expectedSiteDistricts = Map.of(
                "m01_deliver_datashards", District.G_CORP,
                "m02_assassinate_g_exec", District.G_CORP,
                "m03_steal_weights", District.O_CORP,
                "m04_assassinate_fixer", District.D_CORP);
        Map<String, Integer> expectedSiteFloors = Map.of(
                "m01_deliver_datashards", 3,
                "m02_assassinate_g_exec", 4,
                "m03_steal_weights", 5,
                "m04_assassinate_fixer", 3);
        Map<String, String> expectedBuildingIds = Map.of(
                "m01_deliver_datashards", "g:atlas:8460eeb8c1fb224b",
                "m02_assassinate_g_exec", "g:atlas:9188fc4a183218f",
                "m03_steal_weights", "o:atlas:afe7bc2905497aad",
                "m04_assassinate_fixer", "d:atlas:66d1b11e13fd33de");
        Map<String, String> expectedBuildingBounds = Map.of(
                "m01_deliver_datashards", "1097,72,196..1146,152,233",
                "m02_assassinate_g_exec", "1097,72,150..1146,152,187",
                "m03_steal_weights", "-1236,72,3077..-1172,140,3137",
                "m04_assassinate_fixer", "-3169,72,-966..-3040,152,-939");
        Set<String> expectedMissionIds = Set.of(
                "m01_deliver_datashards", "m02_assassinate_g_exec",
                "m03_steal_weights", "m04_assassinate_fixer",
                "m05_kill_cyberpsycho");
        helper.assertTrue(fixedSites.keySet().equals(expectedMissionIds)
                        && expectedSiteIds.entrySet().stream().allMatch(expected -> {
                            MissionBuildingPlanner.Site site = fixedSites.get(expected.getKey());
                            return site != null
                                        && site.id().equals(expected.getValue())
                                        && site.district()
                                                == expectedSiteDistricts.get(expected.getKey())
                                        && site.floorYs().size()
                                                == expectedSiteFloors.get(expected.getKey())
                                        && site.buildingId().equals(
                                                expectedBuildingIds.get(expected.getKey()))
                                        && boundsKey(site.buildingBounds()).equals(
                                                expectedBuildingBounds.get(expected.getKey()))
                                        && fixedLayout.locateDistrict(
                                                        site.target().getX(), site.target().getZ())
                                                .district() == site.district()
                                        && fixedLayout.locateDistrict(
                                                        site.target().getX(), site.target().getZ())
                                                .insideCity()
                                        && site.floorMasks().size() == site.floorYs().size()
                                        && site.stairs().size() == site.floorYs().size() - 1
                                        && site.entrance().position().getY()
                                                == site.floorYs().getFirst()
                                        && site.target().getY() == site.floorYs().getLast()
                                        && !site.buildingId().equals(site.id())
                                        && site.buildingId().contains(":atlas:")
                                        && site.floorMasks().stream()
                                                .flatMap(mask -> mask.cells().stream())
                                                .allMatch(cell -> contains(
                                                        site.buildingBounds(), cell))
                                        && site.decorations().isEmpty()
                                        && ArnisPatchLibrary.select(
                                                        fixedLayout,
                                                        Math.floorDiv(site.target().getX(), 16),
                                                        Math.floorDiv(site.target().getZ(), 16))
                                                .map(placement -> placement.patch().district()
                                                        == site.district())
                                                .orElse(false)
                                        && NeonCityGenerator.isFixedMainlineBuildingChunk(
                                                new net.minecraft.world.level.ChunkPos(
                                                        Math.floorDiv(site.target().getX(), 16),
                                                        Math.floorDiv(site.target().getZ(), 16)));
                        })
                        && java.util.Optional.ofNullable(
                                        fixedSites.get("m05_kill_cyberpsycho"))
                                .filter(PublicEncounterPlanner::isPublicSite)
                                .filter(site -> site.district() == District.D_CORP)
                                .filter(site -> site.floorYs().size() == 1)
                                .filter(site -> PublicEncounterPlanner.isPublicTarget(
                                        fixedLayout, District.D_CORP,
                                        site.target().getX(), site.target().getZ()))
                                .filter(site -> !NeonCityGenerator.isHighwayAt(
                                        fixedLayout, site.target().getX(), site.target().getZ()))
                                .filter(site -> NeonCityGenerator.isFixedMainlineBuildingChunk(
                                        new net.minecraft.world.level.ChunkPos(
                                                Math.floorDiv(site.target().getX(), 16),
                                                Math.floorDiv(site.target().getZ(), 16))))
                                .isPresent()
                        && !NeonCityGenerator.isFixedMainlineBuildingChunk(
                                new net.minecraft.world.level.ChunkPos(0, 0)),
                "fixed mainline catalog lost an exact G/G/O/D building or public D encounter");
        MissionBuildingPlanner.Site fogMotherSite = fixedSites.get(
                "m05_kill_cyberpsycho");
        MissionBuildingPlanner.Site alternatePublicSite = PublicEncounterPlanner.plan(
                        fixedLayout, District.D_CORP,
                        NeonCityGenerator.contentSeed() ^ 0x5055424C49434CL,
                        "m05_public_test", fixedSites.values())
                .orElseThrow();
        helper.assertTrue(MissionBuildingPlanner.Site.load(fogMotherSite.save())
                        .map(decoded -> decoded.save().equals(fogMotherSite.save()))
                        .orElse(false)
                        && PublicEncounterPlanner.isPublicSite(alternatePublicSite)
                        && !MainlineQuestData.buildingConflicts(
                                fogMotherSite, alternatePublicSite),
                "public encounter reservation did not round-trip or avoid existing sites");
        int generatedBeforeRestore = NeonCityGenerator.generatedChunks();
        long scansBeforeRestore = ArnisBuildingAtlas.compilationRequests();
        helper.assertTrue(fixedSites.values().stream().allMatch(site ->
                        helper.getLevel().getChunkSource().getChunkNow(
                                Math.floorDiv(site.target().getX(), 16),
                                Math.floorDiv(site.target().getZ(), 16)) == null),
                "a fixed mainline site chunk was loaded before descriptor restore");
        helper.assertTrue(MainlineQuestService.restoreFixedWorldPlans(helper.getLevel()) == 5
                        && MainlineQuestService.restoreFixedWorldPlans(helper.getLevel()) == 5
                        && NeonCityGenerator.generatedChunks() == generatedBeforeRestore
                        && ArnisBuildingAtlas.compilationRequests() == scansBeforeRestore
                        && fixedSites.entrySet().stream().allMatch(entry ->
                                MainlineQuestService.reservedSite(
                                                helper.getLevel(), entry.getKey())
                                        .map(entry.getValue()::equals).orElse(false))
                        && fixedSites.values().stream().allMatch(site ->
                                helper.getLevel().getChunkSource().getChunkNow(
                                        Math.floorDiv(site.target().getX(), 16),
                                        Math.floorDiv(site.target().getZ(), 16)) == null),
                "fixed-site restore loaded remote chunks, scanned the atlas, or was not idempotent");
        MissionBuildingPlanner.Site syntheticRecovery = MainlineBuildingGenerator.createSite(
                District.G_CORP,
                "m01_deliver_datashards",
                new net.minecraft.core.BlockPos(
                        30_000, NeonCityGenerator.CITY_GROUND_Y + 1, 30_000),
                3,
                77L);
        MissionBuildingPlanner.Site structuralRecovery =
                MissionBuildingPlanner.withoutMissionInteriorPlan(syntheticRecovery);
        MainlineQuestData.get(helper.getLevel()).putSite(
                "m01_deliver_datashards", syntheticRecovery);
        helper.assertTrue(MainlineQuestService.restoreFixedWorldPlans(helper.getLevel()) == 5
                        && MainlineQuestService.reservedSite(
                                        helper.getLevel(), "m01_deliver_datashards")
                                .map(fixedSites.get("m01_deliver_datashards")::equals)
                                .orElse(false),
                "synthetic recovery descriptor was not migrated back to the bundled Arnis site");
        MainlineQuestService.commitWorldPlan(
                helper.getLevel(), "m01_deliver_datashards", syntheticRecovery);
        helper.assertTrue(MainlineQuestService.restoreFixedWorldPlans(helper.getLevel()) == 5
                        && MainlineQuestData.get(helper.getLevel())
                                .isCommittedRecovery("m01_deliver_datashards")
                        && MainlineQuestService.reservedSite(
                                        helper.getLevel(), "m01_deliver_datashards")
                                .map(structuralRecovery::equals).orElse(false)
                        && MainlineQuestService.permanentInterior(
                                        helper.getLevel(), "m01_deliver_datashards")
                                .map(syntheticRecovery::equals).orElse(false),
                "successfully committed recovery site was discarded during descriptor restore");
        helper.assertTrue(NeonCityGenerator.isReservedMainlineBuildingChunk(
                        helper.getLevel(),
                        new net.minecraft.world.level.ChunkPos(
                                Math.floorDiv(structuralRecovery.target().getX(), 16),
                                Math.floorDiv(structuralRecovery.target().getZ(), 16))),
                "animated ads did not respect a committed recovery mainline building");
        MainlineQuestData.get(helper.getLevel()).putSite(
                "m01_deliver_datashards", fixedSites.get("m01_deliver_datashards"));
        MissionBuildingPlanner.Site kaitoBuilding = fixedSites.get("m01_deliver_datashards");
        MissionBuildingPlanner.Site seleneBuilding = fixedSites.get("m02_assassinate_g_exec");
        helper.assertTrue(!kaitoBuilding.buildingId().equals(seleneBuilding.buildingId())
                        && !MainlineQuestData.buildingConflicts(kaitoBuilding, seleneBuilding),
                "Kaito's drop building and Selene's arcology share a physical reservation");
        // Reserved volumes on different floors never meet, so the volume test alone let two
        // actors share one tower while the map tools reported two buildings. Identity is the
        // footprint: overlapping footprints are the same structure whatever floors are used.
        helper.assertTrue(!MainlineQuestData.sharesBuilding(kaitoBuilding, seleneBuilding),
                "Kaito and Selene must not be placed in the same physical building");
        BoundingBox kaitoFootprint = kaitoBuilding.buildingBounds();
        BoundingBox seleneFootprint = seleneBuilding.buildingBounds();
        helper.assertTrue(
                kaitoFootprint.maxX() < seleneFootprint.minX()
                        || seleneFootprint.maxX() < kaitoFootprint.minX()
                        || kaitoFootprint.maxZ() < seleneFootprint.minZ()
                        || seleneFootprint.maxZ() < kaitoFootprint.minZ(),
                "the two mainline buildings must not overlap in plan, only in height");

        StoryMissionCatalog.StoryMission gExecutive =
                StoryMissionCatalog.definition("m02_assassinate_g_exec");
        UUID locationProgressId = UUID.fromString(
                "c0de0000-0000-0000-0000-000000000021");
        MissionService.ContractContext locationContext = new MissionService.ContractContext(
                MissionService.ContractKind.STORY_MISSION,
                gExecutive.requiredStreetCred(),
                locationProgressId,
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(), List.of(UUID.fromString(
                                "c0de0000-0000-0000-0000-000000000022"))),
                false,
                false);
        MissionCatalog.MissionDefinition gEncounter = gExecutive.encounter();
        MissionService.ActiveMission unresolvedGExecutive = new MissionService.ActiveMission(
                gEncounter.id(), gEncounter.type(), gEncounter.title(), gEncounter.briefing(),
                gEncounter.objectiveText(), gExecutive.primaryDistrict(),
                net.minecraft.core.BlockPos.ZERO, gEncounter.rewardMin(), "", "", 0, 0L);
        MainlineQuestService.begin(helper.getLevel(), locationContext, gExecutive.id());
        MissionService.ActiveMission talkToKaito = MainlineQuestService.retarget(
                helper.getLevel(), unresolvedGExecutive, locationContext);
        helper.assertTrue(MainlineQuestService.currentNode(helper.getLevel(), locationContext)
                                .map(StoryMissionCatalog.StoryNode::id)
                                .filter("m02_talk_kaito"::equals).isPresent()
                        && talkToKaito.target().equals(
                                MissionBuildingPlanner.navigationTarget(kaitoBuilding))
                        && helper.getLevel().getChunkSource().getChunkNow(
                                Math.floorDiv(talkToKaito.target().getX(), 16),
                                Math.floorDiv(talkToKaito.target().getZ(), 16)) == null
                        && !contains(seleneBuilding.buildingBounds(), talkToKaito.target()),
                "Mission 2 navigation did not lead to Kaito's separate building entrance");
        MainlineQuestData locationProgress = MainlineQuestData.get(helper.getLevel());
        helper.assertTrue(locationProgress.completeNode(
                                locationProgressId, "m02_talk_kaito")
                        && locationProgress.completeNode(
                                locationProgressId, "m02_infiltrate_arcology"),
                "could not advance the G executive location fixture");
        MissionService.ActiveMission assassinateSelene = MainlineQuestService.retarget(
                helper.getLevel(), unresolvedGExecutive, locationContext);
        helper.assertTrue(MainlineQuestService.currentNode(helper.getLevel(), locationContext)
                                .map(StoryMissionCatalog.StoryNode::id)
                                .filter("m02_assassinate_selene"::equals).isPresent()
                        && assassinateSelene.target().equals(seleneBuilding.target())
                        && seleneBuilding.missionCells(assassinateSelene.target().getY())
                                .contains(assassinateSelene.target())
                        && !contains(kaitoBuilding.buildingBounds(), assassinateSelene.target()),
                "Selene did not resolve into her separate Mission 2 arcology");
        MainlineQuestService.end(helper.getLevel(), locationProgressId);
        List<StoryMissionCatalog.StoryMission> definitions = StoryMissionCatalog.definitions();
        helper.assertTrue(definitions.size() == 5, "story catalog lost its five-mission mainline");
        Map<String, List<String>> expectedFloorPrograms = Map.of(
                "m01_deliver_datashards", List.of("LOBBY", "LOUNGE", "STORAGE"),
                "m02_assassinate_g_exec",
                        List.of("LOBBY", "OPEN_OFFICE", "OPERATIONS", "EXECUTIVE"),
                "m03_steal_weights",
                        List.of("OPERATIONS", "OPEN_OFFICE", "OPERATIONS", "STORAGE",
                                "OPERATIONS"),
                "m04_assassinate_fixer", List.of("STORAGE", "OPERATIONS", "STORAGE"),
                "m05_kill_cyberpsycho", List.of("OPERATIONS"));
        helper.assertTrue(definitions.stream().allMatch(mission ->
                        MissionBuildingPlanner.floorProgram(
                                mission.encounter().type(), mission.id(),
                                mission.requestedFloors())
                                .equals(expectedFloorPrograms.get(mission.id()))),
                "authored mainline buildings lost their mission-specific floor programs");
        List<StoryMissionCatalog.StoryMission> roots = StoryMissionCatalog.available(Set.of(), 0);
        helper.assertTrue(roots.size() == 1
                        && roots.getFirst().id().equals("m01_deliver_datashards"),
                "story DAG must begin at one deterministic root");
        List<String> expectedOrder = List.of(
                "m01_deliver_datashards",
                "m02_assassinate_g_exec",
                "m03_steal_weights",
                "m04_assassinate_fixer",
                "m05_kill_cyberpsycho");
        java.util.HashSet<String> completed = new java.util.HashSet<>();
        for (String expected : expectedOrder) {
            List<StoryMissionCatalog.StoryMission> available =
                    StoryMissionCatalog.available(Set.copyOf(completed), 0);
            helper.assertTrue(available.size() == 1 && available.getFirst().id().equals(expected),
                    "completing one mainline mission did not unlock exactly its successor");
            completed.add(expected);
        }
        helper.assertTrue(StoryMissionCatalog.available(Set.copyOf(completed), 0).isEmpty(),
                "completed mainline still exposed an available mission");
        StoryMissionCatalog.StoryMission oFortress =
                StoryMissionCatalog.definition("m03_steal_weights");
        StoryMissionCatalog.StoryMission fogMother =
                StoryMissionCatalog.definition("m05_kill_cyberpsycho");
        helper.assertTrue(oFortress.requestedFloors() == 5
                        && oFortress.enemiesPerFloor().equals(List.of(4, 5, 5, 4, 2))
                        && definitions.stream().allMatch(value -> value.requiredStreetCred() == 0),
                "mainline floor scale or always-available unlock policy regressed");
        helper.assertTrue(fogMother.requestedFloors() == 1
                        && fogMother.enemiesPerFloor().equals(List.of(0))
                        && fogMother.encounter().guards() == 0
                        && fogMother.nodes().stream().allMatch(node -> node.floor() == 1),
                "cyberpsycho story mission regained floor progression or guard waves");
        java.util.EnumSet<NeonCityGenerator.RoadClass> publicEncounterRoads =
                java.util.EnumSet.of(
                        NeonCityGenerator.RoadClass.CENTRAL_PLAZA,
                        NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD,
                        NeonCityGenerator.RoadClass.LOCAL_STREET,
                        NeonCityGenerator.RoadClass.SERVICE_ALLEY,
                        NeonCityGenerator.RoadClass.PARK);
        helper.assertTrue(java.util.Arrays.stream(NeonCityGenerator.RoadClass.values())
                        .allMatch(road -> PublicEncounterPlanner.isPublicRoad(road)
                                == publicEncounterRoads.contains(road))
                        && publicEncounterRoads.stream()
                                .noneMatch(NeonCityGenerator::isHighwayRoadClass),
                "cyberpsycho public-space policy admitted highways or rejected a public road");
        helper.assertTrue(StoryMissionCatalog.characters().size() == 7
                        && StoryMissionCatalog.character("fog_mother").skinVariant() == 1,
                "mainline character/skin catalog is incomplete");
        for (int floors = 3; floors <= 5; floors++) {
            MissionBuildingPlanner.Site tower = MainlineBuildingGenerator.createSite(
                    District.O_CORP, "tower_test_" + floors,
                    helper.absolutePos(new net.minecraft.core.BlockPos(floors * 20, 3, 0)),
                    floors, 1000L + floors);
            helper.assertTrue(tower.floorYs().size() == floors
                            && tower.floorMasks().stream().allMatch(mask -> mask.cells().size() == 100)
                            && tower.stairs().size() == floors - 1
                            && tower.target().getY() == tower.floorYs().getLast(),
                    "purpose-built mainline tower lost authored floor topology");
        }

        MainlineQuestData progress = MainlineQuestData.get(helper.getLevel());
        UUID progressId = UUID.randomUUID();
        MissionService.ContractContext progressContext = new MissionService.ContractContext(
                MissionService.ContractKind.STORY_MISSION, 15, progressId,
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(), List.of(UUID.randomUUID())),
                false, false);
        helper.assertTrue(MainlineQuestService.ensureProgress(
                        helper.getLevel(), progressContext, "m01_deliver_datashards")
                        && !MainlineQuestService.ensureProgress(
                                helper.getLevel(), progressContext, "m01_deliver_datashards"),
                "mainline save recovery was not idempotent");
        StoryMissionCatalog.StoryNode first = MainlineQuestService.currentNode(
                helper.getLevel(), progressContext).orElseThrow();
        helper.assertTrue(first.id().equals("m01_talk_jerry")
                        && progress.completeNode(progressId, first.id())
                        && !progress.completeNode(progressId, first.id())
                        && MainlineQuestService.currentNode(helper.getLevel(), progressContext)
                                .map(StoryMissionCatalog.StoryNode::id)
                                .filter("m01_travel_highway"::equals).isPresent(),
                "mainline node progress was not atomic and idempotent");
        progress.removeProgress(progressId);

        ServerPlayer stagedPlayer = makeUniquePlayer(helper, "staged_story_deployment");
        StoryMissionCatalog.StoryMission deliveryStory =
                StoryMissionCatalog.definition("m01_deliver_datashards");
        MissionCatalog.MissionDefinition deliveryDefinition = deliveryStory.encounter();
        UUID stagedInstance = UUID.randomUUID();
        PartyService.ParticipantSnapshot stagedParticipants =
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(), List.of(stagedPlayer.getUUID()));
        MissionService.ContractContext stagedContext = new MissionService.ContractContext(
                MissionService.ContractKind.STORY_MISSION,
                deliveryDefinition.streetCred(),
                stagedInstance,
                stagedParticipants,
                false,
                false);
        net.minecraft.core.BlockPos stagedTarget = helper.absolutePos(
                new net.minecraft.core.BlockPos(2, 3, 2));
        MissionService.ActiveMission stagedMission = new MissionService.ActiveMission(
                deliveryDefinition.id(), deliveryDefinition.type(), deliveryDefinition.title(),
                deliveryDefinition.briefing(), deliveryDefinition.objectiveText(),
                deliveryStory.primaryDistrict(), stagedTarget, deliveryDefinition.rewardMin(),
                "", deliveryDefinition.cargoItem().toString(), deliveryDefinition.cargoCount(),
                helper.getLevel().getGameTime());
        MissionService.save(stagedPlayer, stagedMission);
        MissionService.saveContext(stagedPlayer, stagedContext);
        PartyService.registerContract(helper.getLevel(), stagedInstance, stagedParticipants);
        MainlineQuestService.begin(helper.getLevel(), stagedContext, deliveryStory.id());
        MainlineQuestData stagedProgress = MainlineQuestData.get(helper.getLevel());
        helper.assertTrue(stagedProgress.completeNode(stagedInstance, "m01_talk_jerry")
                        && stagedProgress.completeNode(stagedInstance, "m01_travel_highway"),
                "could not stage the Kaito delivery node");
        StoryMissionCatalog.StoryNode deliveryNode = deliveryStory.node("m01_deliver_kaito");
        MissionJournalData.get(helper.getLevel()).accept(
                stagedParticipants, stagedContext, stagedMission, stagedTarget,
                helper.getLevel().getGameTime());
        helper.assertTrue(!MainlineQuestService.questNodeNeeded(
                                helper.getLevel(), deliveryStory, deliveryNode),
                "Kaito was exposed outside before his building deployed");

        net.minecraft.world.entity.Entity deployingActor =
                net.minecraft.world.entity.EntityTypes.MARKER.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.EVENT);
        helper.assertTrue(deployingActor != null, "could not create deploying actor fixture");
        deployingActor.getPersistentData().putBoolean("cyberdeck_mission_actor", true);
        deployingActor.getPersistentData().putString(
                "cyberdeck_mission_instance", stagedInstance.toString());
        deployingActor.getPersistentData().putString("cyberdeck_mission_role", "guard");
        deployingActor.snapTo(
                stagedTarget.getX() + 0.5, stagedTarget.getY(), stagedTarget.getZ() + 0.5,
                0.0F, 0.0F);
        helper.assertTrue(MissionService.duringDeployment(
                                stagedInstance,
                                () -> helper.getLevel().addFreshEntity(deployingActor))
                        && helper.getLevel().getEntity(deployingActor.getUUID()) != null,
                "staged actor join was canceled during its deployment transaction");
        deployingActor.discard();

        net.minecraft.world.entity.Entity staleActor =
                net.minecraft.world.entity.EntityTypes.MARKER.create(
                        helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.EVENT);
        helper.assertTrue(staleActor != null, "could not create suspended actor fixture");
        staleActor.getPersistentData().putBoolean("cyberdeck_mission_actor", true);
        staleActor.getPersistentData().putString(
                "cyberdeck_mission_instance", stagedInstance.toString());
        staleActor.getPersistentData().putString("cyberdeck_mission_role", "guard");
        staleActor.snapTo(
                stagedTarget.getX() + 1.5, stagedTarget.getY(), stagedTarget.getZ() + 0.5,
                0.0F, 0.0F);
        helper.assertTrue(!helper.getLevel().addFreshEntity(staleActor),
                "actor join outside deployment bypassed suspended-contract cleanup");
        net.minecraft.core.BlockPos testSiteXZ = helper.absolutePos(
                new net.minecraft.core.BlockPos(24, 0, 24));
        MissionBuildingPlanner.Site generatedKaitoSite = MainlineBuildingGenerator.createSite(
                District.G_CORP,
                "m01_deliver_datashards",
                new net.minecraft.core.BlockPos(
                        testSiteXZ.getX(), NeonCityGenerator.CITY_GROUND_Y + 1,
                        testSiteXZ.getZ()),
                3,
                91L);
        MissionBuildingPlanner.Site stagedKaitoSite = new MissionBuildingPlanner.Site(
                "test:m01-delivery-e2e",
                generatedKaitoSite.district(), generatedKaitoSite.bounds(),
                generatedKaitoSite.floorYs(), generatedKaitoSite.target(),
                generatedKaitoSite.entrance(), generatedKaitoSite.stairs(),
                generatedKaitoSite.patrolRoutes(), generatedKaitoSite.decorations(),
                generatedKaitoSite.floorMasks(), generatedKaitoSite.planSeed(),
                "test:m01-delivery-building", generatedKaitoSite.buildingBounds());
        MainlineBuildingGenerator.buildTower(
                helper.getLevel(), stagedKaitoSite, District.G_CORP);
        stagedProgress.putSite(deliveryStory.id(), stagedKaitoSite);
        helper.assertTrue(MissionService.issueCargo(
                                helper.getLevel(), stagedPlayer,
                                deliveryDefinition, stagedMission) != null,
                "Jerry's contract-tagged cargo could not be staged");
        MissionService.ActiveMission deployedDelivery = MissionService.activate(
                stagedPlayer, deliveryDefinition, stagedMission, stagedContext);
        MissionBuildingPlanner.Site deployedSite = MissionService.site(stagedPlayer).orElse(null);
        com.example.cyberdeck.npc.CityNpc kaito = deployedSite == null ? null
                : helper.getLevel().getEntitiesOfClass(
                                com.example.cyberdeck.npc.CityNpc.class,
                                new net.minecraft.world.phys.AABB(
                                        deployedSite.buildingBounds().minX(),
                                        deployedSite.buildingBounds().minY(),
                                        deployedSite.buildingBounds().minZ(),
                                        deployedSite.buildingBounds().maxX() + 1.0,
                                        deployedSite.buildingBounds().maxY() + 1.0,
                                        deployedSite.buildingBounds().maxZ() + 1.0),
                                npc -> "kaito_park".equals(
                                        MainlineQuestService.characterId(npc)))
                        .stream().findFirst().orElse(null);
        helper.assertTrue(deployedDelivery != null
                        && MissionService.contractContext(stagedPlayer)
                                .map(MissionService.ContractContext::deployed).orElse(false)
                        && MissionJournalData.get(helper.getLevel())
                                .deploymentState(stagedInstance).orElse(false)
                        && deployedSite != null
                        && kaito != null
                        && kaito.getBlockY() == deployedSite.floorYs().get(1)
                        && deployedSite.missionCells(kaito.getBlockY())
                                .contains(kaito.blockPosition())
                        && contains(deployedSite.buildingBounds(), kaito.blockPosition()),
                "m01 did not deploy its building, journal, guards, and floor-two Kaito atomically");
        net.minecraft.world.phys.AABB deployedArea = new net.minecraft.world.phys.AABB(
                deployedSite.bounds().minX(), deployedSite.bounds().minY(),
                deployedSite.bounds().minZ(), deployedSite.bounds().maxX() + 1.0,
                deployedSite.bounds().maxY() + 1.0, deployedSite.bounds().maxZ() + 1.0);
        List<com.example.cyberdeck.faction.FactionEnemy> deliveryGuards =
                MissionService.missionActors(
                        helper.getLevel(), com.example.cyberdeck.faction.FactionEnemy.class,
                        deployedArea, actor -> MissionService.isMissionActor(actor, stagedInstance));
        helper.assertTrue(deployedSite.floorYs().stream()
                                .map(floorY -> deliveryGuards.stream()
                                        .filter(guard -> guard.getBlockY() == floorY).count())
                                .toList().equals(List.of(3L, 2L, 2L)),
                "m01 did not deploy its authored 3/2/2 guard distribution across all floors");
        stagedPlayer.snapTo(
                kaito.getX(), kaito.getY(), kaito.getZ(), kaito.getYRot(), kaito.getXRot());
        helper.assertTrue(MissionService.interactStoryNpc(stagedPlayer, kaito)
                        && MissionService.activeMission(stagedPlayer).isEmpty()
                        && MissionPlayerData.completedStory(stagedPlayer)
                                .contains(deliveryStory.id())
                        && StoryMissionCatalog.available(
                                        MissionPlayerData.completedStory(stagedPlayer), 0)
                                .stream().map(StoryMissionCatalog.StoryMission::id)
                                .toList().equals(List.of("m02_assassinate_g_exec")),
                "Kaito handoff did not complete m01 and unlock m02");
        MissionBuildingPlanner.BlockSnapshot changedInterior = MissionSiteData.get(
                        helper.getLevel()).restoration(stagedInstance)
                .flatMap(tag -> MissionBuildingPlanner.loadRestorationSnapshot(
                        helper.getLevel(), tag))
                .stream().flatMap(snapshot -> snapshot.blocks().stream())
                .filter(block -> !helper.getLevel().getBlockState(block.position())
                        .equals(block.state()))
                .findFirst().orElseThrow();
        net.minecraft.world.level.block.state.BlockState retainedInteriorState =
                helper.getLevel().getBlockState(changedInterior.position());
        MegacityLayout.Node cleanupDistrict = NeonCityGenerator.layout().node(District.A_CORP);
        stagedPlayer.snapTo(
                cleanupDistrict.x() + 0.5,
                NeonCityGenerator.CITY_GROUND_Y + 1,
                cleanupDistrict.z() + 0.5,
                0.0F,
                0.0F);
        AmbientGigService.recordPresence(stagedPlayer);
        MissionService.tickCompletedSites(helper.getLevel());
        helper.assertTrue(!MissionSiteData.get(helper.getLevel()).hasReservation(stagedInstance)
                        && !kaito.isRemoved()
                        && helper.getLevel().getBlockState(changedInterior.position())
                                .equals(retainedInteriorState)
                        && !retainedInteriorState.equals(changedInterior.state()),
                "completed mainline cleanup removed Kaito or restored his permanent building");
        stagedProgress.removeSite(deliveryStory.id());
        MainlineQuestService.restoreFixedWorldPlans(helper.getLevel());
        disconnect(stagedPlayer);

        MissionBuildingPlanner.Site reservedWindow = MainlineBuildingGenerator.createSite(
                District.G_CORP, "reservation_test",
                new net.minecraft.core.BlockPos(20_000, 73, 20_000), 3, 2001L);
        MissionBuildingPlanner.Site overlapping = MainlineBuildingGenerator.createSite(
                District.G_CORP, "overlap_test",
                new net.minecraft.core.BlockPos(20_008, 73, 20_008), 3, 2002L);
        MissionBuildingPlanner.Site separate = MainlineBuildingGenerator.createSite(
                District.G_CORP, "separate_test",
                new net.minecraft.core.BlockPos(20_080, 73, 20_080), 3, 2003L);
        MissionBuildingPlanner.Site reserved = MissionBuildingPlanner.withBuildingReservation(
                reservedWindow, "test:shared-physical-building", reservedWindow.bounds());
        MissionBuildingPlanner.Site sameBuildingSeparateWindow =
                MissionBuildingPlanner.withBuildingReservation(
                        separate, "test:shared-physical-building", separate.bounds());
        progress.commitSite("__gametest_reservation", reserved, true, reserved);
        helper.assertTrue(progress.conflicts(overlapping, null)
                        && progress.conflicts(sameBuildingSeparateWindow, null)
                        && !progress.conflicts(overlapping, "__gametest_reservation")
                        && !progress.conflicts(separate, null),
                "permanent mainline reservation did not exclude an overlapping or shared building");
        var ops = helper.getLevel().registryAccess().createSerializationContext(
                com.mojang.serialization.JsonOps.INSTANCE);
        com.google.gson.JsonElement encodedPlans = MainlineQuestData.TYPE.codec()
                .encodeStart(ops, progress)
                .getOrThrow(message -> helper.assertionException(Component.literal(
                        "mainline plans must encode: " + message)));
        MainlineQuestData decodedPlans = MainlineQuestData.TYPE.codec()
                .parse(ops, encodedPlans)
                .getOrThrow(message -> helper.assertionException(Component.literal(
                        "mainline plans must decode: " + message)));
        helper.assertTrue(decodedPlans.site("__gametest_reservation")
                        .map(site -> site.id().equals(reserved.id())
                                && site.planSeed() == reserved.planSeed()
                                && site.buildingId().equals(reserved.buildingId())
                                && site.buildingBounds().equals(reserved.buildingBounds())
                                && decodedPlans.isCommittedRecovery("__gametest_reservation")
                                && decodedPlans.permanentInterior("__gametest_reservation")
                                        .map(reserved::equals).orElse(false))
                        .orElse(false),
                "fixed mainline selection or building identity was not retained by saved data");

        helper.assertTrue(rejected(storyRoot("a", List.of("missing"))),
                "story parser accepted a dangling prerequisite");
        JsonObject cycle = storyRoot("a", List.of("b"));
        cycle.getAsJsonArray("missions").add(storyEntry("b", List.of("a")));
        helper.assertTrue(rejected(cycle), "story parser accepted a dependency cycle");
        JsonObject legacyCyberRoot = bundledStoryRoot();
        JsonObject legacyCyber = missionEntry(
                legacyCyberRoot, "m05_kill_cyberpsycho");
        legacyCyber.addProperty("guards", 4);
        JsonObject legacyScale = legacyCyber.getAsJsonObject("scale");
        legacyScale.addProperty("floor_count", 3);
        JsonArray legacyEnemies = new JsonArray();
        legacyEnemies.add(1);
        legacyEnemies.add(2);
        legacyEnemies.add(1);
        legacyScale.add("enemies_per_floor", legacyEnemies);
        JsonArray legacyNodes = legacyCyber.getAsJsonObject("dag").getAsJsonArray("nodes");
        legacyNodes.get(1).getAsJsonObject().addProperty("floor", 2);
        legacyNodes.get(2).getAsJsonObject().addProperty("floor", 3);
        StoryMissionCatalog.StoryMission migratedCyber = StoryMissionCatalog.parse(
                        legacyCyberRoot).stream()
                .filter(mission -> mission.id().equals("m05_kill_cyberpsycho"))
                .findFirst().orElseThrow();
        helper.assertTrue(migratedCyber.requestedFloors() == 1
                        && migratedCyber.enemiesPerFloor().equals(List.of(0))
                        && migratedCyber.encounter().guards() == 0
                        && migratedCyber.nodes().stream().allMatch(node -> node.floor() == 1),
                "legacy cyberpsycho scale did not migrate to a single public encounter");
        helper.succeed();
    }

    static void partyRewards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer online = makeUniquePlayer(helper, "rewards");
        StreetCredState.setStreetCred(online, 0);
        UUID offline = UUID.fromString("c0de0000-0000-0000-0000-000000000001");
        PartyService.ParticipantSnapshot participants = new PartyService.ParticipantSnapshot(
                java.util.Optional.empty(), List.of(online.getUUID(), offline));
        int before = inventoryCount(online);
        PartyService.RewardDistribution distribution = PartyService.splitEmmieReward(
                level, participants, 11);
        helper.assertTrue(distribution.shares().stream()
                        .mapToInt(PartyService.RewardShare::amount).sum() == 11
                        && distribution.shares().stream()
                        .mapToInt(PartyService.RewardShare::amount).max().orElseThrow()
                                - distribution.shares().stream()
                                .mapToInt(PartyService.RewardShare::amount).min().orElseThrow() <= 1,
                "party reward split lost currency or distributed remainder unevenly");
        int onlineShare = distribution.shares().stream()
                .filter(value -> value.playerId().equals(online.getUUID()))
                .findFirst().orElseThrow().amount();
        int offlineShare = distribution.shares().stream()
                .filter(value -> value.playerId().equals(offline))
                .findFirst().orElseThrow().amount();
        helper.assertTrue(inventoryCount(online) == before + onlineShare
                        && PartySavedData.get(level).pendingEmmies(offline) == offlineShare,
                "online/offline reward shares were not delivered through their proper paths");
        PartySavedData.get(level).takePendingEmmies(offline);

        PartyService.awardSharedStreetCred(level, "", participants, 37);
        helper.assertTrue(StreetCredState.getStreetCred(online) == 37,
                "party participant did not receive the full unsplit Street Cred award");
        PartySavedData.get(level).takePendingStreetCred(offline);
        UUID contract = UUID.randomUUID();
        helper.assertTrue(PartyService.markContractCompleted(level, contract)
                        && !PartyService.markContractCompleted(level, contract)
                        && PartyService.isContractCompleted(level, contract),
                "completed-contract ledger did not reject duplicate completion");

        UUID deferredPlayer = UUID.randomUUID();
        PartyService.ParticipantSnapshot settlementParticipants =
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(), List.of(online.getUUID(), deferredPlayer));
        UUID settlement = UUID.randomUUID();
        int emmieBeforeSettlement = inventoryCount(online);
        int credBeforeSettlement = StreetCredState.getStreetCred(online);
        PartyService.registerContract(level, settlement, settlementParticipants);
        helper.assertTrue(PartyService.settleContract(
                        level, settlement, settlementParticipants, 13, 9,
                        "atomic_settlement_test"),
                "contract settlement was not recorded");
        int onlineSettlementShare = inventoryCount(online) - emmieBeforeSettlement;
        int deferredSettlementShare = PartySavedData.get(level).pendingEmmies(deferredPlayer);
        helper.assertTrue(onlineSettlementShare + deferredSettlementShare == 13
                        && Math.abs(onlineSettlementShare - deferredSettlementShare) <= 1,
                "atomic settlement did not conserve its split reward");
        helper.assertTrue(StreetCredState.getStreetCred(online) == credBeforeSettlement + 9
                        && PartySavedData.get(level).takePendingStreetCred(deferredPlayer) == 9,
                "atomic settlement did not grant full Street Cred to each solo participant");
        helper.assertTrue(MissionPlayerData.completedStory(online)
                                .contains("atomic_settlement_test")
                        && PartySavedData.get(level).takeStoryCompletions(deferredPlayer)
                                .equals(List.of("atomic_settlement_test")),
                "atomic settlement did not preserve online and deferred story progression");
        helper.assertTrue(!PartyService.settleContract(
                        level, settlement, settlementParticipants, 13, 9,
                        "atomic_settlement_test")
                        && inventoryCount(online) - emmieBeforeSettlement
                                == onlineSettlementShare,
                "duplicate settlement paid the same contract twice");
        helper.assertTrue(PartyService.requiresContractClear(
                                level, settlement, online.getUUID())
                        && PartyService.requiresContractClear(
                                level, settlement, deferredPlayer),
                "terminal contract did not retain per-participant clear acknowledgements");
        PartyService.acknowledgeContractClear(level, settlement, online.getUUID());
        helper.assertTrue(!PartyService.requiresContractClear(
                                level, settlement, online.getUUID())
                        && PartyService.requiresContractClear(
                                level, settlement, deferredPlayer),
                "one participant acknowledgement cleared another participant's tombstone");
        PartyService.acknowledgeContractClear(level, settlement, deferredPlayer);
        PartySavedData.get(level).takePendingEmmies(deferredPlayer);
        disconnect(online);
        helper.succeed();
    }

    static ServerPlayer makeUniquePlayer(GameTestHelper helper, String prefix) {
        UUID playerId = UUID.randomUUID();
        String name = (prefix + "-" + playerId.toString().substring(0, 7));
        if (name.length() > 16) name = name.substring(0, 16);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(playerId, name), false);
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

    static void disconnect(ServerPlayer player) {
        if (player.connection != null) {
            player.connection.disconnect(Component.literal("GameTest complete"));
        }
    }

    static void gigBoardLifecycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MegacityLayout layout = MegacityLayout.create(0xC1A0B04DL);
        UUID ownerId = UUID.randomUUID();
        AmbientGigData.OwnerKey owner = new AmbientGigData.OwnerKey(false, ownerId);
        UUID partyMember = UUID.randomUUID();
        AmbientGigData.OwnerKey firstPartyOwner = AmbientGigService.ownerForMembers(
                List.of(ownerId, partyMember));
        AmbientGigData.OwnerKey recreatedPartyOwner = AmbientGigService.ownerForMembers(
                List.of(partyMember, ownerId));
        helper.assertTrue(firstPartyOwner.equals(recreatedPartyOwner)
                        && firstPartyOwner.party()
                        && AmbientGigService.ownerForMembers(List.of(ownerId)).equals(owner),
                "gig board ownership changed with party identity/order or failed solo normalization");
        PartySavedData partyData = PartySavedData.get(level);
        UUID firstPartyId = UUID.randomUUID();
        PartySavedData.PartySnapshot firstSoloParty = partyData.create(firstPartyId, ownerId, 0);
        AmbientGigData.OwnerKey firstSoloPartyOwner = AmbientGigService.ownerForMembers(
                firstSoloParty.members());
        partyData.disband(firstPartyId).orElseThrow();
        UUID recreatedPartyId = UUID.randomUUID();
        PartySavedData.PartySnapshot recreatedSoloParty =
                partyData.create(recreatedPartyId, ownerId, 0);
        AmbientGigData.OwnerKey recreatedSoloPartyOwner = AmbientGigService.ownerForMembers(
                recreatedSoloParty.members());
        partyData.disband(recreatedPartyId).orElseThrow();
        helper.assertTrue(!firstPartyId.equals(recreatedPartyId)
                        && firstSoloPartyOwner.equals(owner)
                        && recreatedSoloPartyOwner.equals(owner),
                "recreating a one-member party minted a new gig board owner");
        List<MissionBuildingPlanner.Site> aSites = gigTestSites(
                layout, District.A_CORP, "a");
        List<MissionBuildingPlanner.Site> bSites = gigTestSites(
                layout, District.B_CORP, "b");
        List<AmbientGigData.StoredOffer> first = AmbientGigService.generateStoredOffers(
                layout, level.getSeed(), owner, District.A_CORP, 0L, aSites);
        List<AmbientGigData.StoredOffer> repeated = AmbientGigService.generateStoredOffers(
                layout, level.getSeed(), owner, District.A_CORP, 0L, aSites);
        List<AmbientGigData.StoredOffer> refreshed = AmbientGigService.generateStoredOffers(
                layout, level.getSeed(), owner, District.A_CORP, 1L, aSites);
        helper.assertTrue(first.size() == AmbientGigService.OFFERS_PER_DISTRICT
                        && first.equals(repeated)
                        && first.stream().map(AmbientGigData.StoredOffer::id).distinct().count()
                                == AmbientGigService.OFFERS_PER_DISTRICT
                        && first.stream().map(AmbientGigData.StoredOffer::plannedSite)
                                .flatMap(java.util.Optional::stream)
                                .map(MissionBuildingPlanner.Site::id).distinct().count()
                                == AmbientGigService.OFFERS_PER_DISTRICT
                        && first.stream().allMatch(offer -> offer.plannedSite()
                                .map(site -> site.entrance().position().getY()
                                                == NeonCityGenerator.CITY_GROUND_Y + 1
                                        && MissionBuildingPlanner.navigationTarget(site).getX()
                                                == offer.targetX()
                                        && MissionBuildingPlanner.navigationTarget(site).getZ()
                                                == offer.targetZ())
                                .orElse(false)),
                "district board was not a stable set of five unique offers");
        helper.assertTrue(first.stream().allMatch(offer -> {
                    MegacityLayout.Location location = layout.locateDistrict(
                            offer.targetX(), offer.targetZ());
                    return location.insideCity() && location.district() == District.A_CORP;
                }),
                "generated gig escaped its owning district");
        helper.assertTrue(!first.stream().map(AmbientGigData.StoredOffer::id).toList()
                        .equals(refreshed.stream().map(AmbientGigData.StoredOffer::id).toList()),
                "eligible board refresh reused the previous offer identities");

        AmbientGigData data = AmbientGigData.get(level);
        data.replace(owner, District.A_CORP, 0L, first);
        data.replace(owner, District.B_CORP, 0L, AmbientGigService.generateStoredOffers(
                layout, level.getSeed(), owner, District.B_CORP, 0L, bSites));
        helper.assertTrue(!data.pool(owner, District.A_CORP).orElseThrow().refreshEligible(),
                "new district board began refresh-eligible");
        AmbientGigData.StoredOffer accepted = first.getFirst();
        helper.assertTrue(data.removeOffer(owner, District.A_CORP, accepted.id())
                        && !data.removeOffer(owner, District.A_CORP, accepted.id())
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().size()
                                == AmbientGigService.OFFERS_PER_DISTRICT - 1,
                "stable offer acceptance was not idempotent");
        List<UUID> remainingIds = data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                .map(AmbientGigData.StoredOffer::id).toList();
        helper.assertTrue(!AmbientGigService.ensureBoard(level, owner, District.A_CORP, aSites)
                        && data.pool(owner, District.A_CORP).orElseThrow().generation() == 0L
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                                .map(AmbientGigData.StoredOffer::id).toList().equals(remainingIds),
                "re-entering before an external completion replenished accepted gigs");
        remainingIds.forEach(id -> helper.assertTrue(
                data.removeOffer(owner, District.A_CORP, id),
                "could not consume the remaining stable gig offers"));
        helper.assertTrue(data.pool(owner, District.A_CORP).orElseThrow().offers().isEmpty()
                        && !AmbientGigService.ensureBoard(
                                level, owner, District.A_CORP, aSites)
                        && data.pool(owner, District.A_CORP).orElseThrow().generation() == 0L
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().isEmpty(),
                "an exhausted board replenished before a qualified external completion");

        PartyService.ParticipantSnapshot solo = new PartyService.ParticipantSnapshot(
                java.util.Optional.empty(), List.of(ownerId));
        data.setLastDistrict(ownerId, District.A_CORP);
        UUID refreshCompletion = UUID.randomUUID();
        helper.assertTrue(AmbientGigService.recordCompletion(
                        level, refreshCompletion, solo, District.B_CORP),
                "first external completion was rejected");
        helper.assertTrue(data.pool(owner, District.A_CORP).orElseThrow().refreshPending()
                        && !data.pool(owner, District.A_CORP).orElseThrow().refreshEligible(),
                "external completion was not held pending while an offline owner remained inside");
        data.setLastDistrict(ownerId, null);
        AmbientGigService.promotePendingRefreshes(level, owner, solo.playerIds());
        helper.assertTrue(data.pool(owner, District.A_CORP).orElseThrow().refreshEligible()
                        && !data.pool(owner, District.A_CORP).orElseThrow().refreshPending()
                        && !data.pool(owner, District.B_CORP).orElseThrow().refreshEligible(),
                "completion did not arm only boards outside the completed district");
        helper.assertTrue(AmbientGigService.ensureBoard(level, owner, District.A_CORP, aSites)
                        && data.pool(owner, District.A_CORP).orElseThrow().generation() == 1L
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().size()
                                == AmbientGigService.OFFERS_PER_DISTRICT
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                                .map(AmbientGigData.StoredOffer::id)
                                .noneMatch(remainingIds::contains)
                        && !data.pool(owner, District.A_CORP).orElseThrow().refreshEligible(),
                "qualified re-entry did not advance and restore the district board");
        List<UUID> refreshedIds = data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                .map(AmbientGigData.StoredOffer::id).toList();
        helper.assertTrue(!AmbientGigService.recordCompletion(
                                level, refreshCompletion, solo, District.B_CORP)
                        && !data.pool(owner, District.A_CORP).orElseThrow().refreshEligible()
                        && !AmbientGigService.ensureBoard(
                                level, owner, District.A_CORP, aSites)
                        && data.pool(owner, District.A_CORP).orElseThrow().generation() == 1L
                        && data.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                                .map(AmbientGigData.StoredOffer::id).toList().equals(refreshedIds),
                "duplicate completion refreshed a later board generation");
        var ops = level.registryAccess().createSerializationContext(
                com.mojang.serialization.JsonOps.INSTANCE);
        com.google.gson.JsonElement encodedBoards = AmbientGigData.TYPE.codec()
                .encodeStart(ops, data)
                .getOrThrow(message -> helper.assertionException(
                        net.minecraft.network.chat.Component.literal(
                                "gig boards must encode: " + message)));
        AmbientGigData decodedBoards = AmbientGigData.TYPE.codec()
                .parse(ops, encodedBoards)
                .getOrThrow(message -> helper.assertionException(
                        net.minecraft.network.chat.Component.literal(
                                "gig boards must decode: " + message)));
        helper.assertTrue(!decodedBoards.claimCompletion(refreshCompletion),
                "completion idempotency was not preserved by the saved-data codec");
        helper.assertTrue(decodedBoards.pool(owner, District.A_CORP).orElseThrow().offers().stream()
                        .allMatch(offer -> offer.plannedSite().isPresent()),
                "saved gig board lost its pre-analyzed building descriptors");

        UUID churnLeader = UUID.randomUUID();
        UUID churnMember = UUID.randomUUID();
        UUID churnPartyId = UUID.randomUUID();
        partyData.create(churnPartyId, churnLeader, 0);
        PartySavedData.PartySnapshot churnParty = partyData.addMember(
                churnPartyId, churnMember, 0).orElseThrow();
        PartyService.ParticipantSnapshot churnParticipants =
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.of(churnPartyId), churnParty.members());
        AmbientGigData.OwnerKey oldGroupOwner = AmbientGigService.ownerForMembers(
                churnParty.members());
        AmbientGigData.OwnerKey churnLeaderOwner = AmbientGigService.ownerForMembers(
                List.of(churnLeader));
        AmbientGigData.OwnerKey churnMemberOwner = AmbientGigService.ownerForMembers(
                List.of(churnMember));
        data.replace(oldGroupOwner, District.A_CORP, 0L, List.of());
        partyData.disband(churnPartyId).orElseThrow();
        data.replace(churnLeaderOwner, District.A_CORP, 0L, List.of());
        data.replace(churnMemberOwner, District.A_CORP, 0L, List.of());
        UUID churnCompletion = UUID.randomUUID();
        helper.assertTrue(AmbientGigService.recordCompletion(
                                level, churnCompletion, churnParticipants, District.B_CORP)
                        && data.pool(churnLeaderOwner, District.A_CORP)
                                .orElseThrow().refreshEligible()
                        && data.pool(churnMemberOwner, District.A_CORP)
                                .orElseThrow().refreshEligible()
                        && !data.pool(oldGroupOwner, District.A_CORP)
                                .orElseThrow().refreshEligible(),
                "completion after disband refreshed the orphaned group instead of current owners");

        UUID secondParticipant = UUID.randomUUID();
        PartyService.ParticipantSnapshot journalParticipants =
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(), List.of(ownerId, secondParticipant));
        UUID contractId = UUID.randomUUID();
        MissionService.ContractContext context = new MissionService.ContractContext(
                MissionService.ContractKind.GIG, 17, contractId,
                journalParticipants, false, false);
        MissionService.ActiveMission mission = new MissionService.ActiveMission(
                accepted.definitionId(), MissionCatalog.definition(accepted.definitionId()).type(),
                "Journal Test", "Persistent briefing", "Complete the test",
                District.A_CORP,
                new net.minecraft.core.BlockPos(
                        accepted.targetX(), NeonCityGenerator.CITY_GROUND_Y + 1,
                        accepted.targetZ()),
                accepted.reward(), "", "", 0, 100L);
        MissionJournalData journal = MissionJournalData.get(level);
        journal.accept(journalParticipants, context, mission, 100L);
        journal.status(contractId, MissionService.JournalStatus.COMPLETED, 200L);
        journal.accept(journalParticipants, context, mission, 300L);
        helper.assertTrue(journal.entries(ownerId).getFirst().status()
                                == MissionService.JournalStatus.COMPLETED
                        && journal.entries(secondParticipant).getFirst().status()
                                == MissionService.JournalStatus.COMPLETED,
                "offline journal status was lost or overwritten by active reconciliation");

        ServerPlayer recovering = makeUniquePlayer(helper, "exact_site_recovery");
        UUID offlineParticipant = UUID.randomUUID();
        UUID recoveryInstance = UUID.randomUUID();
        PartyService.ParticipantSnapshot recoveryParticipants =
                new PartyService.ParticipantSnapshot(
                        java.util.Optional.empty(),
                        List.of(recovering.getUUID(), offlineParticipant));
        MissionBuildingPlanner.Site recoverySite = MainlineBuildingGenerator.createSite(
                District.A_CORP,
                "offline_exact_site_" + recoveryInstance,
                helper.absolutePos(new net.minecraft.core.BlockPos(32, 3, 32)),
                2,
                recoveryInstance.getMostSignificantBits());
        MissionCatalog.MissionDefinition recoveryDefinition = MissionCatalog.definitions().stream()
                .filter(definition -> definition.type()
                        == MissionCatalog.MissionType.ASSASSINATE_TARGET)
                .findFirst().orElseThrow();
        MissionService.ActiveMission recoveryMission = new MissionService.ActiveMission(
                recoveryDefinition.id(), recoveryDefinition.type(), "Offline Site Recovery",
                "Recover the exact reserved building.", "Reach the reserved building.",
                District.A_CORP, recoverySite.target(), 1, "", "", 0, 400L);
        MissionService.ContractContext offlineContext = new MissionService.ContractContext(
                MissionService.ContractKind.GIG, recoveryDefinition.streetCred(), recoveryInstance,
                recoveryParticipants, false, false);
        MissionService.save(recovering, recoveryMission);
        MissionService.saveContext(recovering, offlineContext);
        PartyService.registerContract(level, recoveryInstance, recoveryParticipants);
        MissionSiteData recoverySites = MissionSiteData.get(level);
        helper.assertTrue(recoverySites.reserve(
                                "test:offline-exact-site:" + recoveryInstance,
                                recoverySite, recoveryInstance)
                        && recoverySites.reservedSite(recoveryInstance)
                                .map(recoverySite::equals).orElse(false),
                "server reservation did not retain its versioned exact-site descriptor");

        UUID legacyInstance = UUID.randomUUID();
        MissionBuildingPlanner.Site legacySite = MainlineBuildingGenerator.createSite(
                District.A_CORP,
                "legacy_exact_site_" + legacyInstance,
                helper.absolutePos(new net.minecraft.core.BlockPos(64, 3, 32)),
                2,
                legacyInstance.getLeastSignificantBits());
        helper.assertTrue(recoverySites.reserve(
                                "test:legacy-exact-site:" + legacyInstance, legacyInstance)
                        && recoverySites.reservedSite(legacyInstance).isEmpty(),
                "legacy reservation unexpectedly required an exact-site field");

        com.google.gson.JsonElement encodedReservations = MissionSiteData.TYPE.codec()
                .encodeStart(ops, recoverySites)
                .getOrThrow(message -> helper.assertionException(
                        Component.literal("mission reservations must encode: " + message)));
        MissionSiteData decodedReservations = MissionSiteData.TYPE.codec()
                .parse(ops, encodedReservations)
                .getOrThrow(message -> helper.assertionException(
                        Component.literal("mission reservations must decode: " + message)));
        helper.assertTrue(decodedReservations.reservedSite(recoveryInstance)
                                .map(recoverySite::equals).orElse(false)
                        && decodedReservations.hasReservation(legacyInstance)
                        && decodedReservations.reservedSite(legacyInstance).isEmpty()
                        && recoverySites.storeSite(legacyInstance, legacySite)
                        && recoverySites.reservedSite(legacyInstance)
                                .map(legacySite::equals).orElse(false),
                "exact-site persistence was not copy-safe or backward-compatible");
        recoverySites.releaseOwned(legacyInstance);

        MissionPlayerData.persisted(recovering).remove("cyberdeck_mission_site");
        MissionJournalData.get(level).accept(
                recoveryParticipants, offlineContext.withDeployed(true), recoveryMission,
                MissionBuildingPlanner.navigationTarget(recoverySite), 500L);
        MissionService.onPlayerLogin(recovering);
        helper.assertTrue(MissionService.contractContext(recovering)
                                .map(MissionService.ContractContext::deployed).orElse(false)
                        && MissionService.site(recovering).map(recoverySite::equals).orElse(false)
                        && recoverySites.hasReservation(recoveryInstance),
                "offline participant recovery discarded the server-owned exact site");

        MissionPlayerData.persisted(recovering).remove("cyberdeck_mission_site");
        recoverySites.markEntered(recoveryInstance, List.of(recovering.getUUID()));
        MegacityLayout.Node outside = NeonCityGenerator.fixedLayout().node(District.B_CORP);
        recovering.setPos(
                outside.x(), NeonCityGenerator.CITY_GROUND_Y + 1, outside.z());
        MissionService.tickPlayer(
                recovering,
                NeonCityGenerator.fixedLayout().locate(outside.x(), outside.z()));
        helper.assertTrue(MissionService.contractContext(recovering)
                                .map(contract -> !contract.deployed()).orElse(false)
                        && MissionService.site(recovering).map(recoverySite::equals).orElse(false)
                        && recoverySites.hasReservation(recoveryInstance)
                        && recoverySites.reservedSite(recoveryInstance)
                                .map(recoverySite::equals).orElse(false),
                "suspension released an exact site after the representative lost its local copy");
        MissionJournalData.get(level).status(
                recoveryInstance, MissionService.JournalStatus.ABANDONED, 600L);
        PartyService.markContractCompleted(level, recoveryInstance);
        recoverySites.releaseOwned(recoveryInstance);
        disconnect(recovering);
        helper.succeed();
    }

    static void fixedGigCatalogReads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<District, List<MissionBuildingPlanner.Site>> catalog = GigSiteData.fixedCatalog();
        List<MissionBuildingPlanner.Site> mainline = MainlineQuestData.fixedSites().values().stream()
                .toList();
        helper.assertTrue(catalog.size() == District.values().length
                        && catalog.entrySet().stream().allMatch(entry ->
                                entry.getValue().size() >= GigSiteData.MIN_FIXED_SITES_PER_DISTRICT
                                        && entry.getValue().size()
                                                <= GigSiteData.CANDIDATES_PER_DISTRICT
                                        && entry.getValue().stream().allMatch(
                                                site -> site.district() == entry.getKey()
                                                        && GigSiteData.belongsToDeclaredDistrict(site)
                                                        && site.decorations().isEmpty()
                                                        && !site.floorMasks().isEmpty()
                                                        && site.floorMasks().size()
                                                                == site.floorYs().size()
                                                        && site.floorMasks().stream().allMatch(mask ->
                                                                !mask.cells().isEmpty())
                                                        && site.floorMasks().stream()
                                                                .map(MissionBuildingPlanner.FloorMask
                                                                        ::floorY)
                                                                .collect(java.util.stream.Collectors
                                                                        .toSet())
                                                                .equals(Set.copyOf(site.floorYs()))
                                                        && site.patrolRoutes().size()
                                                                == site.floorYs().size()
                                                        && site.patrolRoutes().stream()
                                                                .map(MissionBuildingPlanner.PatrolRoute
                                                                        ::floorY)
                                                                .collect(java.util.stream.Collectors
                                                                        .toSet())
                                                                .equals(Set.copyOf(site.floorYs()))
                                                        && site.stairs().size()
                                                                == site.floorYs().size() - 1
                                                        && mainline.stream().noneMatch(reserved ->
                                                                MainlineQuestData.buildingConflicts(
                                                                        reserved, site))))
                        && catalog.values().stream().flatMap(List::stream)
                                .map(MissionBuildingPlanner.Site::id).distinct().count()
                                == catalog.values().stream().mapToLong(List::size).sum(),
                "bundled fixed gig catalog violates its structural descriptor contract");

        District district = District.C_CORP;
        List<MissionBuildingPlanner.Site> candidates = catalog.get(district);
        Set<Long> candidateChunks = new java.util.HashSet<>();
        for (MissionBuildingPlanner.Site site : candidates) {
            for (int chunkZ = Math.floorDiv(site.bounds().minZ(), 16);
                    chunkZ <= Math.floorDiv(site.bounds().maxZ(), 16); chunkZ++) {
                for (int chunkX = Math.floorDiv(site.bounds().minX(), 16);
                        chunkX <= Math.floorDiv(site.bounds().maxX(), 16); chunkX++) {
                    candidateChunks.add(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
                }
            }
        }
        Set<Long> loadedBefore = candidateChunks.stream().filter(packed ->
                        level.getChunkSource().getChunkNow(
                                net.minecraft.world.level.ChunkPos.getX(packed),
                                net.minecraft.world.level.ChunkPos.getZ(packed)) != null)
                .collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(loadedBefore.isEmpty(),
                "startup fixed-catalog hydration loaded remote District C candidate chunks");
        int generatedBefore = NeonCityGenerator.generatedChunks();
        long scansBefore = ArnisBuildingAtlas.compilationRequests();
        helper.assertTrue(GigSiteData.restoreFixedCatalog(level)
                        >= District.values().length * GigSiteData.MIN_FIXED_SITES_PER_DISTRICT,
                "fixed gig descriptors did not hydrate at server scope");

        ServerPlayer player = makeUniquePlayer(helper, "gigcatalog");
        MegacityLayout.Node districtNode = NeonCityGenerator.fixedLayout().node(district);
        player.setPos(districtNode.x(), NeonCityGenerator.CITY_GROUND_Y + 1, districtNode.z());
        AmbientGigData.OwnerKey owner = AmbientGigService.ownerForMembers(
                List.of(player.getUUID()));
        AmbientGigService.ensureBoard(level, owner, district);
        List<AmbientGigService.DiscoveredGig> direct = AmbientGigService.availableOffers(
                level, owner, district);
        var map = CityMapService.snapshot(level, player, false);
        GigJournalPacket journal = GigJournalPacket.snapshot(player);

        Set<Long> newlyLoaded = candidateChunks.stream().filter(packed ->
                        !loadedBefore.contains(packed)
                                && level.getChunkSource().getChunkNow(
                                        net.minecraft.world.level.ChunkPos.getX(packed),
                                        net.minecraft.world.level.ChunkPos.getZ(packed)) != null)
                .collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(direct.size() == AmbientGigService.OFFERS_PER_DISTRICT
                        && journal.availableGigs().size() == direct.size()
                        && map.markers().stream().filter(marker -> marker.kind()
                                == com.example.cyberdeck.network.OpenCityMapPacket.MarkerKind
                                        .AVAILABLE_GIG).count() == direct.size()
                        && ArnisBuildingAtlas.compilationRequests() == scansBefore
                        && NeonCityGenerator.generatedChunks() == generatedBefore
                        && newlyLoaded.isEmpty(),
                "startup/board/map/journal gig reads scanned Arnis or touched candidate chunks");

        MissionBuildingPlanner.Site selected = direct.getFirst().site();
        Set<Long> selectedChunks = new java.util.HashSet<>();
        for (int chunkZ = Math.floorDiv(selected.bounds().minZ(), 16);
                chunkZ <= Math.floorDiv(selected.bounds().maxZ(), 16); chunkZ++) {
            for (int chunkX = Math.floorDiv(selected.bounds().minX(), 16);
                    chunkX <= Math.floorDiv(selected.bounds().maxX(), 16); chunkX++) {
                selectedChunks.add(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
            }
        }
        Set<Long> selectedGenerationHalo = new java.util.HashSet<>();
        for (long packed : selectedChunks) {
            int centerX = net.minecraft.world.level.ChunkPos.getX(packed);
            int centerZ = net.minecraft.world.level.ChunkPos.getZ(packed);
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    selectedGenerationHalo.add(net.minecraft.world.level.ChunkPos.pack(
                            centerX + dx, centerZ + dz));
                }
            }
        }
        helper.assertTrue(AmbientGigService.accept(
                                player, district, direct.getFirst().offerId())
                        && MissionService.activeMission(player).isPresent(),
                "accepting a verified bundled gig did not reserve its exact site");
        Set<Long> loadedAfterAcceptance = candidateChunks.stream().filter(packed ->
                        level.getChunkSource().getChunkNow(
                                net.minecraft.world.level.ChunkPos.getX(packed),
                                net.minecraft.world.level.ChunkPos.getZ(packed)) != null)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> unrelatedLoaded = new java.util.HashSet<>(loadedAfterAcceptance);
        unrelatedLoaded.removeAll(loadedBefore);
        unrelatedLoaded.removeAll(selectedGenerationHalo);
        helper.assertTrue(loadedAfterAcceptance.stream().anyMatch(
                                selectedGenerationHalo::contains)
                        && unrelatedLoaded.isEmpty()
                        && ArnisBuildingAtlas.compilationRequests() == scansBefore,
                "acceptance scanned Arnis or loaded a non-selected gig building");
        helper.assertTrue(MissionService.abandon(player),
                "catalog acceptance proof did not release its exact-site reservation");
        disconnect(player);
        helper.succeed();
    }

    private static List<MissionBuildingPlanner.Site> gigTestSites(
            MegacityLayout layout, District district, String prefix) {
        MegacityLayout.Node node = layout.node(district);
        return java.util.stream.IntStream.range(0, AmbientGigService.OFFERS_PER_DISTRICT)
                .mapToObj(index -> MainlineBuildingGenerator.createSite(
                        district,
                        "gig_board_" + prefix + "_" + index,
                        new net.minecraft.core.BlockPos(
                                node.x() - 80 + index * 40,
                                NeonCityGenerator.CITY_GROUND_Y + 1,
                                node.z()),
                        2,
                        0x474947L + index))
                .toList();
    }

    private static String boundsKey(BoundingBox bounds) {
        return bounds.minX() + "," + bounds.minY() + "," + bounds.minZ()
                + ".." + bounds.maxX() + "," + bounds.maxY() + "," + bounds.maxZ();
    }

    private static boolean contains(BoundingBox bounds, net.minecraft.core.BlockPos position) {
        return position.getX() >= bounds.minX() && position.getX() <= bounds.maxX()
                && position.getY() >= bounds.minY() && position.getY() <= bounds.maxY()
                && position.getZ() >= bounds.minZ() && position.getZ() <= bounds.maxZ();
    }

    private static boolean rejected(JsonObject root) {
        try {
            StoryMissionCatalog.parse(root);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static JsonObject bundledStoryRoot() {
        try (var stream = StoryMissionCatalog.class.getResourceAsStream(
                        StoryMissionCatalog.RESOURCE);
                var reader = new InputStreamReader(
                        java.util.Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new IllegalStateException("could not read bundled story catalog", exception);
        }
    }

    private static JsonObject missionEntry(JsonObject root, String id) {
        for (var element : root.getAsJsonArray("missions")) {
            JsonObject mission = element.getAsJsonObject();
            if (mission.get("id").getAsString().equals(id)) return mission;
        }
        throw new IllegalArgumentException("missing story mission " + id);
    }

    private static JsonObject storyRoot(String id, List<String> prerequisites) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", 1);
        JsonArray missions = new JsonArray();
        missions.add(storyEntry(id, prerequisites));
        root.add("missions", missions);
        return root;
    }

    private static JsonObject storyEntry(String id, List<String> prerequisites) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("type", "assassinate_target");
        value.addProperty("title", "Test " + id);
        value.addProperty("briefing", "Test story mission " + id);
        value.addProperty("target_name", "Target " + id);
        JsonArray districts = new JsonArray();
        districts.add("A");
        value.add("target_districts", districts);
        JsonObject reward = new JsonObject();
        reward.addProperty("min", 1);
        reward.addProperty("max", 1);
        value.add("reward_emmies", reward);
        value.addProperty("street_cred", 1);
        value.addProperty("activation_radius", 32);
        value.addProperty("guards", 1);
        value.addProperty("objective_radius", 16);
        JsonArray parents = new JsonArray();
        prerequisites.forEach(parents::add);
        value.add("prerequisites", parents);
        value.addProperty("required_street_cred", 0);
        return value;
    }

    private static int inventoryCount(ServerPlayer player) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(Emmies.item())) count += stack.getCount();
        }
        return count;
    }
}
