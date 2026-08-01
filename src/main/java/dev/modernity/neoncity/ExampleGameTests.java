package dev.modernity.neoncity;

import com.example.cyberdeck.CyberdeckItems;
import com.example.cyberdeck.faction.CyberpsychoEntity;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.FactionEntities;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.cyberware.CyberwareTier;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import com.example.cyberdeck.network.OpenCityMapPacket;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntitySpawnReason;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/** Pure regression tests for finite megacity topology, culture, and massing. */
public final class ExampleGameTests {
    private static final long TEST_SEED = 0x4E454F4E43495459L;
    private static final int RADIAL_STEPS = 72;
    private static final long[] ZONE_SEED_OFFSETS = {0L, 20L, 85L, 127L};
    private static final int ARNIS_ATLAS_AXIS = 16;
    private static final int ARNIS_TILES_PER_ATLAS = ARNIS_ATLAS_AXIS * ARNIS_ATLAS_AXIS;
    private static final EnumSet<MegacityLayout.Zone> ARNIS_ZONES =
            EnumSet.of(MegacityLayout.Zone.NEST, MegacityLayout.Zone.BACKSTREETS);

    private record BorderSample(int x, int z, NeonCityGenerator.UrbanSample sample) {}

    private ExampleGameTests() {}

    public static void alleyDepthFirst(GameTestHelper helper) {
        AlleyMaze.Plan plan = AlleyMaze.generate(TEST_SEED, -3, 7);
        helper.assertTrue(plan.edges().size() == AlleyMaze.PERFECT_EDGE_COUNT,
                "DFS maze must contain exactly grid-cells minus one edges");
        helper.assertTrue(plan.portals().size() == 4, "every sector needs four portals");
        helper.assertTrue(plan.edges().stream().allMatch(edge -> edge.width() >= 2 && edge.width() <= 4),
                "all service alleys must be two to four blocks wide");
        helper.assertTrue(plan.isConnected(), "all DFS corridors and portals must be connected");
        helper.assertTrue(plan.alleyCells() > 900 && plan.alleyCells() < 4000,
                "alley coverage must stay dense without consuming the block");
        helper.succeed();
    }

    public static void alleySeams(GameTestHelper helper) {
        assertPortalPair(helper, -2, 5, AlleyMaze.Side.EAST, -1, 5, AlleyMaze.Side.WEST);
        assertPortalPair(helper, -2, 5, AlleyMaze.Side.NORTH, -2, 4, AlleyMaze.Side.SOUTH);
        assertPortalPair(helper, 0, 0, AlleyMaze.Side.WEST, -1, 0, AlleyMaze.Side.EAST);
        helper.succeed();
    }

    public static void districtEntryNotification(GameTestHelper helper) {
        DistrictEntryNotifier notifier = new DistrictEntryNotifier();
        UUID firstPlayer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002");

        helper.assertTrue(
                DistrictEntryNotifier.title(District.A_CORP).getString()
                        .equals("Now Entering District A"),
                "district entry title text changed");
        helper.assertTrue(notifier.transition(firstPlayer, District.A_CORP)
                        .orElseThrow() == District.A_CORP,
                "initial city entry did not announce A Corp");
        helper.assertTrue(notifier.transition(firstPlayer, District.A_CORP).isEmpty(),
                "remaining in one district repeated its notification");
        helper.assertTrue(notifier.transition(firstPlayer, District.H_CORP)
                        .orElseThrow() == District.H_CORP,
                "crossing into a different district did not announce it");
        helper.assertTrue(notifier.transition(firstPlayer, null).isEmpty(),
                "wilderness should not display a district title");
        helper.assertTrue(notifier.transition(firstPlayer, District.H_CORP)
                        .orElseThrow() == District.H_CORP,
                "leaving the city did not rearm the district notification");
        helper.assertTrue(notifier.transition(secondPlayer, District.Z_CORP)
                        .orElseThrow() == District.Z_CORP,
                "district tracking leaked between players");
        for (MegacityLayout.Zone zone : MegacityLayout.Zone.values()) {
            District expected = zone == MegacityLayout.Zone.NEST
                    || zone == MegacityLayout.Zone.BACKSTREETS
                    ? District.A_CORP : null;
            helper.assertTrue(
                    DistrictEntryNotifier.inhabitedDistrict(District.A_CORP, zone) == expected,
                    zone + " has the wrong district-entry notification contract");
        }
        helper.succeed();
    }

    public static void quicktimeRouting(GameTestHelper helper) {
        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        MegacityLayout.Node sourceNode = layout.node(District.A_CORP);
        MegacityLayout.Node targetNode = layout.node(District.B_CORP);
        MegacityLayout.Node decoyNode = layout.node(District.C_CORP);
        BlockPos source = new BlockPos(sourceNode.x(), NeonCityGenerator.CITY_GROUND_Y + 1, sourceNode.z());
        BlockPos nearest = new BlockPos(targetNode.x() + 2,
                NeonCityGenerator.CITY_GROUND_Y + 1, targetNode.z());
        BlockPos farther = new BlockPos(targetNode.x() + 12,
                NeonCityGenerator.CITY_GROUND_Y + 1, targetNode.z());
        BlockPos decoy = new BlockPos(decoyNode.x(),
                NeonCityGenerator.CITY_GROUND_Y + 1, decoyNode.z());

        List<District> destinations = QuicktimeTravelService.destinationDistricts(District.A_CORP);
        helper.assertTrue(destinations.size() == District.values().length - 1,
                "Quicktime destination list must contain every other district");
        helper.assertTrue(!destinations.contains(District.A_CORP),
                "Quicktime destination list included the source district");
        helper.assertTrue(new HashSet<>(destinations).size() == destinations.size(),
                "Quicktime destination list contains duplicates");
        for (District district : District.values()) {
            helper.assertTrue(NeonCityCommand.parseDistrict(district.code()).orElse(null) == district,
                    "district teleport command rejected " + district.code());
            helper.assertTrue(NeonCityCommand.parseDistrict(
                            district.code().toLowerCase(Locale.ROOT)).orElse(null) == district,
                    "district teleport command is not case-insensitive for " + district.code());
        }
        helper.assertTrue(NeonCityCommand.parseDistrict("AA").isEmpty()
                        && NeonCityCommand.parseDistrict("1").isEmpty(),
                "district teleport command accepted an invalid district code");
        helper.assertTrue(
                QuicktimeTravelService.nearestStation(
                                layout,
                                District.B_CORP,
                                source,
                                List.of(farther, decoy, nearest))
                        .orElseThrow()
                        .equals(nearest),
                "Quicktime routing did not choose the nearest station in the requested district");
        helper.assertTrue(
                QuicktimeTravelService.nearestStation(
                                layout,
                                District.Z_CORP,
                                source,
                                List.of(farther, decoy, nearest))
                        .isEmpty(),
                "Quicktime routing crossed district boundaries to satisfy a missing destination");
        for (District district : District.values()) {
            MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
            BlockPos station = QuicktimeTravelService.canonicalStation(district);
            helper.assertTrue((station.getX() >> 4) == (node.x() >> 4)
                            && (station.getZ() >> 4) == (node.z() >> 4),
                    "canonical Quicktime station crossed its center chunk in " + district);
            helper.assertTrue(Math.floorMod(station.getX(), 16) >= 2
                            && Math.floorMod(station.getX(), 16) <= 13
                            && Math.floorMod(station.getZ(), 16) >= 2
                            && Math.floorMod(station.getZ(), 16) <= 13,
                    "canonical Quicktime platform can spill into a neighboring chunk in " + district);
            MegacityLayout.Location stationLocation = NeonCityGenerator.layout().locate(
                    station.getX(), station.getZ());
            helper.assertTrue(DistrictEntryNotifier.inhabitedDistrict(
                            stationLocation.district(), stationLocation.zone()) == district,
                    "canonical Quicktime station left its inhabited district in " + district);
        }
        helper.succeed();
    }

    public static void cityMapPlan(GameTestHelper helper) {
        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        MegacityLayout identical = MegacityLayout.create(TEST_SEED);
        MegacityLayout fromPublicSeed = MegacityLayout.createFromLayoutSeed(layout.seed());
        helper.assertTrue(layout.nodes().equals(fromPublicSeed.nodes())
                        && layout.edges().equals(fromPublicSeed.edges()),
                "public map seed did not reproduce the server layout");
        int extent = CityMapProjection.extent(layout);
        helper.assertTrue(extent > MegacityLayout.NOMINAL_CITY_RADIUS,
                "city map extent clipped the nominal city radius");
        for (MegacityLayout.Node node : layout.nodes()) {
            helper.assertTrue(Math.abs(node.x()) + node.radiusX() < extent
                            && Math.abs(node.z()) + node.radiusZ() < extent,
                    "city map clipped " + node.district());
            helper.assertTrue(layout.locateDistrict(node.x(), node.z()).district() == node.district(),
                    "fast map lookup changed the center of " + node.district());
        }

        List<OpenCityMapPacket.Marker> markers = CityMapService.markers(layout);
        helper.assertTrue(markers.size() == District.values().length,
                "city map must expose one transit node per district without fake job leads");
        long transitCount = markers.stream()
                .filter(marker -> marker.kind() == OpenCityMapPacket.MarkerKind.TRANSIT)
                .count();
        helper.assertTrue(transitCount == District.values().length,
                "city map is missing a district transit node");
        for (OpenCityMapPacket.Marker marker : markers) {
            MegacityLayout.Location location = layout.locateDistrict(marker.x(), marker.z());
            helper.assertTrue(location.insideCity()
                            && location.district().ordinal() == marker.districtOrdinal(),
                    "city map marker escaped its district: " + marker.labelKey());
        }
        MegacityLayout.Node activeNode = layout.node(District.M_CORP);
        OpenCityMapPacket.Marker activeMission = new OpenCityMapPacket.Marker(
                OpenCityMapPacket.MarkerKind.ACTIVE_MISSION,
                activeNode.x(), activeNode.z(), District.M_CORP.ordinal(),
                "literal:Runtime Mission");
        List<OpenCityMapPacket.Marker> activeMarkers = CityMapService.markers(
                layout, Optional.of(activeMission));
        helper.assertTrue(activeMarkers.size() == District.values().length + 1
                        && activeMarkers.getFirst().equals(activeMission),
                "active mission did not replace decorative leads on the city map");

        double[] coordinates = {-extent, -3141.5, 0.0, 2718.25, extent};
        for (double coordinate : coordinates) {
            double unit = CityMapProjection.worldToUnit(coordinate, extent);
            double restored = CityMapProjection.unitToWorld(unit, extent);
            helper.assertTrue(Math.abs(coordinate - restored) < 1.0E-8,
                    "city map projection failed to round-trip " + coordinate);
        }
        helper.assertTrue(CityMapProjection.clampCenter(extent, extent, 1.0) == 0.0,
                "overview panning must keep the entire city visible");
        helper.assertTrue(CityMapProjection.clampCenter(extent, extent, 4.0) == extent * 0.75,
                "zoomed map panning exceeded the texture bounds");

        int longestRoute = 0;
        for (MegacityLayout.Node start : layout.nodes()) {
            for (MegacityLayout.Node target : layout.nodes()) {
                CityRoutePlanner.Route route = CityRoutePlanner.shortest(
                        layout, start.x(), start.z(), target.x(), target.z());
                CityRoutePlanner.Route recreated = CityRoutePlanner.shortest(
                        fromPublicSeed, start.x(), start.z(), target.x(), target.z());
                helper.assertTrue(route.equals(recreated),
                        "public map seed changed the route from " + start.district()
                                + " to " + target.district());
                helper.assertTrue(!route.isEmpty()
                                && route.points().getFirst().equals(
                                        new CityRoutePlanner.Point(start.x(), start.z()))
                                && route.points().getLast().equals(
                                        new CityRoutePlanner.Point(target.x(), target.z())),
                        "route endpoints escaped their requested hubs");
                helper.assertTrue(route.length() + 1.0E-6 >= Math.hypot(
                                start.x() - target.x(), start.z() - target.z()),
                        "route was shorter than the endpoint chord");
                helper.assertTrue(route.districts().size() <= District.values().length
                                && new HashSet<>(route.districts()).size()
                                        == route.districts().size(),
                        "shortest route repeated a district: " + route.districts());
                for (int index = 1; index < route.districts().size(); index++) {
                    District first = route.districts().get(index - 1);
                    District second = route.districts().get(index);
                    helper.assertTrue(layout.edges().stream()
                                    .anyMatch(edge -> edge.connects(first, second)),
                            "route crossed a missing graph edge: " + first + " -> " + second);
                }
                longestRoute = Math.max(longestRoute, route.points().size());
            }
        }
        helper.assertTrue(longestRoute <= District.values().length * 25 + 2,
                "city route exceeded its bounded polyline size: " + longestRoute);
        CityRoutePlanner.Route wildernessRoute = CityRoutePlanner.shortest(
                layout, -extent, -extent, extent, extent);
        helper.assertTrue(wildernessRoute.points().size() == 2
                        && wildernessRoute.districts().isEmpty(),
                "wilderness navigation should use a direct fallback");

        EnumSet<NeonCityGenerator.RoadClass> roads = EnumSet.noneOf(
                NeonCityGenerator.RoadClass.class);
        int proceduralFootprints = 0;
        long digest = 1L;
        double[] sampleRadii = {0.20, 0.34, 0.55, 0.69, 0.90};
        for (MegacityLayout.Node node : layout.nodes()) {
            for (double radius : sampleRadii) {
                for (int angle = 0; angle < 8; angle++) {
                    int[] point = ellipsePoint(node, radius, angle, 8);
                    NeonCityGenerator.UrbanSample first =
                            NeonCityGenerator.mapSample(layout, point[0], point[1]);
                    NeonCityGenerator.UrbanSample second =
                            NeonCityGenerator.mapSample(identical, point[0], point[1]);
                    helper.assertTrue(first.equals(second),
                            "same seed changed its map sample at " + point[0] + "," + point[1]);
                    roads.add(first.roadClass());
                    if (first.insideFootprint() || first.buildingHeight() > 0) {
                        proceduralFootprints++;
                    }
                    digest = digest * 31L + first.roadClass().ordinal();
                    digest = digest * 31L + first.district().ordinal();
                }
            }
            NeonCityGenerator.UrbanSample center =
                    NeonCityGenerator.mapSample(layout, node.x(), node.z());
            roads.add(center.roadClass());
            digest = digest * 31L + center.roadClass().ordinal();
        }
        for (int[] point : new int[][] {
                {-extent, -extent}, {-extent, extent},
                {extent, -extent}, {extent, extent}}) {
            NeonCityGenerator.UrbanSample first =
                    NeonCityGenerator.mapSample(layout, point[0], point[1]);
            NeonCityGenerator.UrbanSample second =
                    NeonCityGenerator.mapSample(identical, point[0], point[1]);
                helper.assertTrue(first.equals(second),
                    "same seed changed its wilderness map sample");
            roads.add(first.roadClass());
            digest = digest * 31L + first.roadClass().ordinal();
        }
        helper.assertTrue(proceduralFootprints == 0,
                "city map exposed removed procedural building massing");
        helper.assertTrue(roads.contains(NeonCityGenerator.RoadClass.WILDERNESS)
                        && roads.contains(NeonCityGenerator.RoadClass.CENTRAL_PLAZA)
                        && roads.contains(NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD),
                "city map sampling omitted required terrain classes: " + roads);
        helper.assertTrue(digest != 0L, "city map digest was empty");
        helper.succeed();
    }

    private static void assertPortalPair(GameTestHelper helper,
                                         int firstX, int firstZ, AlleyMaze.Side firstSide,
                                         int secondX, int secondZ, AlleyMaze.Side secondSide) {
        AlleyMaze.Plan first = AlleyMaze.generate(TEST_SEED, firstX, firstZ);
        AlleyMaze.Plan second = AlleyMaze.generate(TEST_SEED, secondX, secondZ);
        AlleyMaze.Portal left = portal(first, firstSide);
        AlleyMaze.Portal right = portal(second, secondSide);
        helper.assertTrue(left.center() == right.center() && left.width() == right.width(),
                "neighbouring DFS sectors disagree at " + firstSide + "/" + secondSide);
    }

    private static AlleyMaze.Portal portal(AlleyMaze.Plan plan, AlleyMaze.Side side) {
        return plan.portals().stream().filter(candidate -> candidate.side() == side)
                .findFirst().orElseThrow();
    }

    /** Same input seed must reproduce the complete immutable graph, not only node positions. */
    public static void deterministicSeedLayouts(GameTestHelper helper) {
        MegacityLayout first = MegacityLayout.create(TEST_SEED);
        MegacityLayout second = MegacityLayout.create(TEST_SEED);
        helper.assertTrue(first.seed() == second.seed(), "mixed layout seed changed between builds");
        helper.assertTrue(first.nodes().equals(second.nodes()), "same seed produced different district nodes");
        helper.assertTrue(first.edges().equals(second.edges()), "same seed produced different travel edges");
        for (int z = -5400; z <= 5400; z += 337) {
            for (int x = -5400; x <= 5400; x += 353) {
                helper.assertTrue(first.locate(x, z).equals(second.locate(x, z)),
                        "same seed disagrees while locating " + x + "," + z);
            }
        }
        helper.succeed();
    }

    /** Retains the old registration name while checking the new 26-node graph contract. */
    public static void districtCoverage(GameTestHelper helper) {
        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        helper.assertTrue(District.values().length == 26, "A-Z must define exactly 26 cultures");
        helper.assertTrue(layout.nodes().size() == MegacityLayout.DISTRICT_COUNT,
                "layout does not contain exactly one node per culture");

        EnumSet<District> districts = EnumSet.noneOf(District.class);
        Set<Long> coordinates = new HashSet<>();
        Set<Long> identities = new HashSet<>();
        for (MegacityLayout.Node node : layout.nodes()) {
            districts.add(node.district());
            coordinates.add(pack(node.x(), node.z()));
            identities.add(node.identity());
            helper.assertTrue(layout.node(node.district()).equals(node),
                    "district index disagrees for " + node.district());
            helper.assertTrue(node.radiusX() >= 850 && node.radiusZ() >= 850,
                    "district blob is too small for a megacity: " + node);
        }
        helper.assertTrue(districts.equals(EnumSet.allOf(District.class)),
                "layout omitted or duplicated a district: " + districts);
        helper.assertTrue(coordinates.size() == 26, "two districts occupy the same center");
        helper.assertTrue(identities.size() == 26, "two districts share a procedural identity");
        MegacityLayout.Node origin = layout.node(District.A_CORP);
        helper.assertTrue(origin.x() == 0 && origin.z() == 0,
                "A Corp must remain the monumental origin");
        helper.assertTrue(layout.locate(0, 0).district() == District.A_CORP,
                "A Corp does not own the world origin");

        MegacityLayout changed = MegacityLayout.create(TEST_SEED + 1);
        int changedNodes = 0;
        for (District district : District.values()) {
            if (!layout.node(district).equals(changed.node(district))) changedNodes++;
        }
        helper.assertTrue(changedNodes >= District.values().length / 2,
                "different world seeds barely changed the district layout: " + changedNodes);
        helper.assertTrue(!layout.edges().equals(changed.edges()),
                "different world seeds produced the same connection graph");
        helper.succeed();
    }

    public static void connectedTravelGraph(GameTestHelper helper) {
        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        helper.assertTrue(layout.isGroundConnected(), "ground highway graph is disconnected");
        helper.assertTrue(layout.isElevatedConnected(), "elevated backbone is disconnected");
        helper.assertTrue(layout.edges().size() > layout.nodes().size() - 1,
                "travel graph needs loops in addition to its spanning tree");
        helper.assertTrue(layout.groundEdges().equals(layout.edges()),
                "an edge disappeared from the ground highway layer");
        long backboneEdges = layout.edges().stream()
                .filter(MegacityLayout.Edge::elevatedBackbone).count();
        helper.assertTrue(backboneEdges == layout.nodes().size() - 1
                        && layout.elevatedEdges().size() >= backboneEdges,
                "elevated layer lost its spanning-tree backbone");

        EnumMap<District, Integer> degree = new EnumMap<>(District.class);
        Set<Long> pairs = new HashSet<>();
        EnumSet<MegacityLayout.ConnectionKind> kinds =
                EnumSet.noneOf(MegacityLayout.ConnectionKind.class);
        for (District district : District.values()) degree.put(district, 0);
        for (MegacityLayout.Edge edge : layout.edges()) {
            District first = edge.first().district();
            District second = edge.second().district();
            helper.assertTrue(first != second, "self-loop found at " + first);
            helper.assertTrue(layout.node(first).equals(edge.first())
                            && layout.node(second).equals(edge.second()),
                    "edge endpoint is not a canonical layout node");
            long pair = undirectedPair(first, second);
            helper.assertTrue(pairs.add(pair), "duplicate undirected edge " + first + "-" + second);
            degree.merge(first, 1, Integer::sum);
            degree.merge(second, 1, Integer::sum);
            kinds.add(edge.kind());
            helper.assertTrue(Math.abs(edge.bend()) <= 0.2200001,
                    "connection bend escaped its design bound");
        }
        int degreeSum = degree.values().stream().mapToInt(Integer::intValue).sum();
        helper.assertTrue(degreeSum == layout.edges().size() * 2,
                "degree sum does not equal twice the edge count");
        for (Map.Entry<District, Integer> entry : degree.entrySet()) {
            helper.assertTrue(entry.getValue() >= 2,
                    entry.getKey() + " has no alternate route, degree=" + entry.getValue());
            helper.assertTrue(entry.getValue() < District.values().length,
                    entry.getKey() + " was incorrectly connected to every district");
        }
        for (MegacityLayout.Node node : layout.nodes()) {
            helper.assertTrue(layout.elevatedEdges().stream().anyMatch(
                            edge -> edge.first().equals(node) || edge.second().equals(node)),
                    node.district() + " has no elevated-backbone junction");
        }
        helper.assertTrue(kinds.equals(EnumSet.allOf(MegacityLayout.ConnectionKind.class)),
                "travel graph lacks a connection style: " + kinds);
        helper.succeed();
    }

    public static void finiteCityWilderness(GameTestHelper helper) {
        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        for (MegacityLayout.Node node : layout.nodes()) {
            helper.assertTrue(layout.locate(node.x(), node.z()).insideCity(),
                    "district center is outside the city: " + node.district());
        }
        int far = MegacityLayout.NOMINAL_CITY_RADIUS * 3;
        int[][] wilderness = {
                {far, 0}, {-far, 0}, {0, far}, {0, -far},
                {far, far}, {-far, far}, {far, -far}, {-far, -far}
        };
        for (int[] point : wilderness) {
            MegacityLayout.Location location = layout.locate(point[0], point[1]);
            helper.assertTrue(location.zone() == MegacityLayout.Zone.WILDERNESS,
                    "finite city leaked to " + point[0] + "," + point[1]);
            helper.assertTrue(!location.insideCity(), "wilderness was marked as city");
        }

        NeonCityGenerator.reset();
        helper.assertTrue(NeonCityGenerator.chunkTouchesCity(0, 0),
                "origin chunk must touch A Corp");
        helper.assertTrue(!NeonCityGenerator.chunkTouchesCity(far >> 4, far >> 4),
                "far wilderness chunk was queued as city");
        helper.succeed();
    }

    public static void districtZonesAndCulture(GameTestHelper helper) {
        Set<String> codes = new HashSet<>();
        Set<String> labels = new HashSet<>();
        Set<District.CultureSignature> signatures = new HashSet<>();
        Set<DistrictLogoBanners.Design> logos = new HashSet<>();
        EnumSet<District.Architecture> architectures = EnumSet.noneOf(District.Architecture.class);
        EnumSet<District.StreetPattern> streetPatterns = EnumSet.noneOf(District.StreetPattern.class);
        EnumSet<District.RoofStyle> roofStyles = EnumSet.noneOf(District.RoofStyle.class);
        for (District district : District.values()) {
            helper.assertTrue(codes.add(district.code()) && district.code().length() == 1,
                    "district code must be one unique letter: " + district);
            helper.assertTrue(labels.add(district.label()), "duplicate district label: " + district.label());
            helper.assertTrue(!district.flavor().isBlank(), "missing cultural flavor for " + district);
            helper.assertTrue(architectures.add(district.architecture()),
                    "architecture must distinguish each culture: " + district.architecture());
            helper.assertTrue(streetPatterns.add(district.streetPattern()),
                    "street grammar must distinguish each culture: " + district.streetPattern());
            helper.assertTrue(roofStyles.add(district.roofStyle()),
                    "roofline must distinguish each culture: " + district.roofStyle());
            helper.assertTrue(signatures.add(district.cultureSignature()),
                    "duplicate complete culture signature: " + district);
            DistrictLogoBanners.Design logo = DistrictLogoBanners.design(district);
            helper.assertTrue(logos.add(logo), "duplicate district emblem: " + district);
            helper.assertTrue(logo.colors().size() >= 2 && logo.colors().size() <= 3,
                    "district emblem must use two or three colors: " + district);
            helper.assertTrue(district.parcelSize() >= 24 && district.parcelSize() <= 60,
                    "invalid parcel grain for " + district);
            helper.assertTrue(district.minHeight() >= 8 && district.maxHeight() > district.minHeight(),
                    "invalid skyline bounds for " + district);
            helper.assertTrue(district.density() > 0.0 && district.density() <= 1.0,
                    "invalid density for " + district);
            helper.assertTrue(district.vegetation() >= 0.0 && district.vegetation() <= 1.0,
                    "invalid vegetation for " + district);
        }
        helper.assertTrue(architectures.equals(EnumSet.allOf(District.Architecture.class)),
                "not every architectural culture is represented");
        helper.assertTrue(streetPatterns.equals(EnumSet.allOf(District.StreetPattern.class)),
                "not every district-scale street culture is represented");
        helper.assertTrue(roofStyles.equals(EnumSet.allOf(District.RoofStyle.class)),
                "not every district roof culture is represented");

        EnumSet<MegacityLayout.Zone> allZones = EnumSet.of(MegacityLayout.Zone.WILDERNESS);
        for (long seedOffset : ZONE_SEED_OFFSETS) {
            MegacityLayout layout = MegacityLayout.create(TEST_SEED + seedOffset);
            for (MegacityLayout.Node node : layout.nodes()) {
                EnumSet<MegacityLayout.Zone> districtZones =
                        EnumSet.noneOf(MegacityLayout.Zone.class);
                collectDistrictZones(layout, node, districtZones, allZones, 0.02, 24);
                if (!hasInhabitedBands(districtZones)) {
                    // Refine only crowded border cases rather than paying this
                    // sampling cost for every node and seed.
                    collectDistrictZones(layout, node, districtZones, allZones, 0.01, 72);
                }
                String context = node.district() + " at seed offset " + seedOffset;
                helper.assertTrue(districtZones.contains(MegacityLayout.Zone.NEST),
                        context + " has no premium Nest core");
                helper.assertTrue(districtZones.contains(MegacityLayout.Zone.BACKSTREETS),
                        context + " has no Backstreets belt");
            }
        }
        helper.assertTrue(allZones.containsAll(ARNIS_ZONES),
                "layout sampling did not expose both inhabited zones: " + allZones);
        EnumSet<MegacityLayout.Zone> borderTypes = EnumSet.noneOf(MegacityLayout.Zone.class);
        for (long seedOffset : ZONE_SEED_OFFSETS) {
            MegacityLayout typedLayout = MegacityLayout.create(TEST_SEED + seedOffset);
            for (int first = 0; first < District.values().length; first++) {
                for (int second = first + 1; second < District.values().length; second++) {
                    District left = District.values()[first];
                    District right = District.values()[second];
                    MegacityLayout.Zone type = typedLayout.boundaryZone(left, right);
                    helper.assertTrue(type == typedLayout.boundaryZone(right, left),
                            "district border type changes when pair order is reversed");
                    borderTypes.add(type);
                }
            }
        }
        helper.assertTrue(borderTypes.equals(EnumSet.of(
                        MegacityLayout.Zone.BORDER_WALLED,
                        MegacityLayout.Zone.BORDER_FOREST,
                        MegacityLayout.Zone.BORDER_CLIFF)),
                "district pairs do not expose all three border types: " + borderTypes);
        MegacityLayout borderLayout = MegacityLayout.create(TEST_SEED);
        int widenedBorders = 0;
        int legacyBorders = 0;
        for (int z = -5_200; z <= 5_200; z += 40) {
            for (int x = -5_200; x <= 5_200; x += 40) {
                MegacityLayout.Location location = borderLayout.locateDistrict(x, z);
                if (!isBorderZone(location.zone())) {
                    continue;
                }
                widenedBorders++;
                double secondaryScore = location.normalizedDistance()
                        + location.boundaryGap();
                helper.assertTrue(MegacityLayout.isDistrictBorder(
                                location.normalizedDistance(), secondaryScore),
                        "runtime border disagrees with the shared map predicate");
                if (secondaryScore <= 1.12 && location.boundaryGap() < 0.055) {
                    legacyBorders++;
                }
            }
        }
        helper.assertTrue(legacyBorders > 0 && widenedBorders * 10 >= legacyBorders * 16,
                "district edge layers were not materially widened: old=" + legacyBorders
                        + ", new=" + widenedBorders);
        helper.succeed();
    }

    private static void collectDistrictZones(
            MegacityLayout layout,
            MegacityLayout.Node node,
            EnumSet<MegacityLayout.Zone> districtZones,
            EnumSet<MegacityLayout.Zone> allZones,
            double radialStep,
            int angleSteps) {
        int radialSamples = (int) Math.round(1.12 / radialStep);
        for (int radialIndex = 0; radialIndex <= radialSamples; radialIndex++) {
            double radius = Math.min(1.12, radialIndex * radialStep);
            for (int angle = 0; angle < angleSteps; angle++) {
                int[] point = ellipsePoint(node, radius, angle, angleSteps);
                MegacityLayout.Location location = layout.locate(point[0], point[1]);
                allZones.add(location.zone());
                if (location.district() == node.district()) districtZones.add(location.zone());
                if (hasInhabitedBands(districtZones)) return;
            }
        }
    }

    private static boolean hasInhabitedBands(EnumSet<MegacityLayout.Zone> zones) {
        return zones.contains(MegacityLayout.Zone.NEST)
                && zones.contains(MegacityLayout.Zone.BACKSTREETS);
    }

    private static boolean isBorderZone(MegacityLayout.Zone zone) {
        return zone == MegacityLayout.Zone.BORDER_WALLED
                || zone == MegacityLayout.Zone.BORDER_FOREST
                || zone == MegacityLayout.Zone.BORDER_CLIFF;
    }

    public static void connectionContinuity(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        EnumSet<NeonCityGenerator.RoadClass> infrastructure =
                EnumSet.noneOf(NeonCityGenerator.RoadClass.class);
        int gradedSamples = 0;
        int clearanceSamples = 0;
        for (MegacityLayout.Edge edge : layout.edges()) {
            for (int step = 0; step <= 20; step++) {
                int[] point = connectionPoint(edge, step / 20.0);
                MegacityLayout.Location location = layout.locate(point[0], point[1]);
                helper.assertTrue(location.insideCity(),
                        "connection leaves city between " + edge.first().district()
                                + " and " + edge.second().district());
                helper.assertTrue(location.onConnection(),
                        "gap in connection at " + point[0] + "," + point[1]);
                NeonCityGenerator.RoadClass road = NeonCityGenerator.roadAt(point[0], point[1]);
                helper.assertTrue(isTravelInfrastructure(road),
                        "connection classified as " + road + " at " + point[0] + "," + point[1]);
                infrastructure.add(road);
            }

            double chordLength = Math.hypot(
                    edge.second().x() - edge.first().x(),
                    edge.second().z() - edge.first().z());
            int gradeSteps = Math.max(2, (int) Math.ceil(chordLength * 1.5));
            int previousDeck = NeonCityGenerator.highwayDeckY(layout, edge, 0.0);
            for (int step = 1; step <= gradeSteps; step++) {
                double progress = step / (double) gradeSteps;
                int deck = NeonCityGenerator.highwayDeckY(layout, edge, progress);
                helper.assertTrue(Math.abs(deck - previousDeck) <= 1,
                        "highway grade jumps from " + previousDeck + " to " + deck
                                + " on " + edge.first().district() + "-"
                                + edge.second().district() + " at " + progress);
                if (deck > NeonCityGenerator.CITY_GROUND_Y
                        && deck < NeonCityGenerator.CITY_GROUND_Y
                        + NeonCityGenerator.BRIDGE_RISE) {
                    gradedSamples++;
                }
                previousDeck = deck;
            }

            for (int step = 3; step <= 17; step++) {
                MegacityLayout.CurvePoint point = MegacityLayout.curvePoint(edge, step / 20.0);
                double tangentLength = Math.max(
                        1.0, Math.hypot(point.tangentX(), point.tangentZ()));
                int bufferX = (int) Math.round(point.x()
                        - point.tangentZ() / tangentLength * 18.0);
                int bufferZ = (int) Math.round(point.z()
                        + point.tangentX() / tangentLength * 18.0);
                NeonCityGenerator.UrbanSample buffer = NeonCityGenerator.sample(
                        bufferX, bufferZ);
                if (buffer.location().nearestConnection() == edge
                        && buffer.location().connectionDistance()
                                > NeonCityGenerator.HIGHWAY_HALF_WIDTH
                        && buffer.location().connectionDistance()
                                <= NeonCityGenerator.HIGHWAY_CLEARANCE_RADIUS) {
                    helper.assertTrue(
                            buffer.roadClass() == NeonCityGenerator.RoadClass.HIGHWAY_BUFFER,
                            "reserved highway shoulder classified as " + buffer.roadClass()
                                    + " at " + bufferX + "," + bufferZ);
                    helper.assertTrue(!NeonCityGenerator.keepsArnisColumn(
                                    buffer, buffer.district()),
                            "Arnis building entered the reserved highway shoulder");
                    clearanceSamples++;
                }
            }
        }
        helper.assertTrue(infrastructure.contains(NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD)
                        || infrastructure.contains(NeonCityGenerator.RoadClass.BRIDGE),
                "connections contain no drivable interdistrict infrastructure");
        helper.assertTrue(infrastructure.contains(NeonCityGenerator.RoadClass.ELEVATED_RAIL),
                "connections contain no elevated rail");
        helper.assertTrue(gradedSamples >= layout.edges().size()
                        && clearanceSamples >= layout.edges().size(),
                "highway scan missed graded approaches or atlas setbacks: grades="
                        + gradedSamples + ", setbacks=" + clearanceSamples);
        helper.succeed();
    }

    /** Retains the old registration name for roads, bridges, parks, and special districts. */
    public static void organicRoads(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        EnumSet<NeonCityGenerator.RoadClass> roads =
                EnumSet.noneOf(NeonCityGenerator.RoadClass.class);
        Set<Long> validatedParkChunks = new HashSet<>();
        int validatedParks = 0;
        for (MegacityLayout.Node node : layout.nodes()) {
            roads.add(NeonCityGenerator.roadAt(node.x(), node.z()));
            for (int angle = 0; angle < 16; angle++) {
                double radians = angle * Math.PI * 2.0 / 16.0;
                roads.add(NeonCityGenerator.roadAt(
                        node.x() + (int) Math.round(Math.cos(radians) * 24.0),
                        node.z() + (int) Math.round(Math.sin(radians) * 24.0)));
            }
            for (double radius = 0.12; radius <= 1.06; radius += 0.047) {
                for (int angle = 0; angle < RADIAL_STEPS; angle++) {
                    int[] point = ellipsePoint(node, radius, angle, RADIAL_STEPS);
                    NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(point[0], point[1]);
                    if (sample.district() == node.district()) {
                        roads.add(sample.roadClass());
                        if (sample.roadClass() == NeonCityGenerator.RoadClass.PARK) {
                            long parkChunk = ChunkPos.pack(
                                    Math.floorDiv(point[0], 16), Math.floorDiv(point[1], 16));
                            if (validatedParkChunks.add(parkChunk)) {
                                assertAuditedParkSite(
                                        helper, layout, point[0], point[1], sample);
                                validatedParks++;
                            }
                        }
                    }
                }
            }
        }
        for (MegacityLayout.Edge edge : layout.edges()) {
            for (int step = 1; step < 20; step++) {
                int[] point = connectionPoint(edge, step / 20.0);
                roads.add(NeonCityGenerator.roadAt(point[0], point[1]));
            }
        }
        collectBorderRoadClasses(layout, roads);

        NeonCityGenerator.RoadClass[] required = {
                NeonCityGenerator.RoadClass.CENTRAL_PLAZA,
                NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD,
                NeonCityGenerator.RoadClass.LOCAL_STREET,
                NeonCityGenerator.RoadClass.SERVICE_ALLEY,
                NeonCityGenerator.RoadClass.PARK,
                NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD,
                NeonCityGenerator.RoadClass.BRIDGE,
                NeonCityGenerator.RoadClass.ELEVATED_RAIL,
                NeonCityGenerator.RoadClass.BORDER_WALLED,
                NeonCityGenerator.RoadClass.BORDER_FOREST,
                NeonCityGenerator.RoadClass.BORDER_CLIFF
        };
        for (NeonCityGenerator.RoadClass road : required) {
            helper.assertTrue(roads.contains(road), "city is missing infrastructure class " + road);
        }
        helper.assertTrue(validatedParks > 0,
                "road scan found no park backed by an audited open Arnis tile");
        helper.succeed();
    }

    private static void collectBorderRoadClasses(
            MegacityLayout layout,
            EnumSet<NeonCityGenerator.RoadClass> roads) {
        for (int first = 0; first < layout.nodes().size(); first++) {
            for (int second = first + 1; second < layout.nodes().size(); second++) {
                MegacityLayout.Node left = layout.nodes().get(first);
                MegacityLayout.Node right = layout.nodes().get(second);
                double low = 0.0;
                double high = 1.0;
                for (int iteration = 0; iteration < 48; iteration++) {
                    double progress = (low + high) * 0.5;
                    int x = (int) Math.round(left.x() + (right.x() - left.x()) * progress);
                    int z = (int) Math.round(left.z() + (right.z() - left.z()) * progress);
                    double score = layout.normalizedDistanceTo(left, x, z)
                            - layout.normalizedDistanceTo(right, x, z);
                    if (score < 0.0) low = progress;
                    else high = progress;
                }
                double progress = (low + high) * 0.5;
                double centerX = left.x() + (right.x() - left.x()) * progress;
                double centerZ = left.z() + (right.z() - left.z()) * progress;
                double length = Math.max(
                        1.0, Math.hypot(right.x() - left.x(), right.z() - left.z()));
                double tangentX = -(right.z() - left.z()) / length;
                double tangentZ = (right.x() - left.x()) / length;
                double normalX = (right.x() - left.x()) / length;
                double normalZ = (right.z() - left.z()) / length;
                boolean sampled = false;
                for (int offset = -192; offset <= 192; offset += 8) {
                    for (int cross = -40; cross <= 40; cross += 8) {
                        int x = (int) Math.round(
                                centerX + tangentX * offset + normalX * cross);
                        int z = (int) Math.round(
                                centerZ + tangentZ * offset + normalZ * cross);
                        MegacityLayout.Location location = layout.locate(x, z);
                        if (!isBorderZone(location.zone())) continue;
                        NeonCityGenerator.RoadClass road = NeonCityGenerator.roadAt(x, z);
                        if (road == NeonCityGenerator.RoadClass.BORDER_WALLED
                                || road == NeonCityGenerator.RoadClass.BORDER_FOREST
                                || road == NeonCityGenerator.RoadClass.BORDER_CLIFF) {
                            roads.add(road);
                            sampled = true;
                            break;
                        }
                    }
                    if (sampled) break;
                }
                if (roads.contains(NeonCityGenerator.RoadClass.BORDER_WALLED)
                        && roads.contains(NeonCityGenerator.RoadClass.BORDER_FOREST)
                        && roads.contains(NeonCityGenerator.RoadClass.BORDER_CLIFF)) {
                    return;
                }
            }
        }
    }

    private static BorderSample findBorderSample(
            MegacityLayout layout,
            MegacityLayout.Zone targetZone,
            NeonCityGenerator.RoadClass targetRoad) {
        for (int first = 0; first < layout.nodes().size(); first++) {
            for (int second = first + 1; second < layout.nodes().size(); second++) {
                MegacityLayout.Node left = layout.nodes().get(first);
                MegacityLayout.Node right = layout.nodes().get(second);
                if (layout.boundaryZone(left.district(), right.district()) != targetZone) continue;
                double low = 0.0;
                double high = 1.0;
                for (int iteration = 0; iteration < 48; iteration++) {
                    double progress = (low + high) * 0.5;
                    int x = (int) Math.round(left.x() + (right.x() - left.x()) * progress);
                    int z = (int) Math.round(left.z() + (right.z() - left.z()) * progress);
                    if (layout.normalizedDistanceTo(left, x, z)
                            < layout.normalizedDistanceTo(right, x, z)) {
                        low = progress;
                    } else {
                        high = progress;
                    }
                }
                double progress = (low + high) * 0.5;
                double centerX = left.x() + (right.x() - left.x()) * progress;
                double centerZ = left.z() + (right.z() - left.z()) * progress;
                double length = Math.max(
                        1.0, Math.hypot(right.x() - left.x(), right.z() - left.z()));
                double tangentX = -(right.z() - left.z()) / length;
                double tangentZ = (right.x() - left.x()) / length;
                double normalX = (right.x() - left.x()) / length;
                double normalZ = (right.z() - left.z()) / length;
                for (int along = -256; along <= 256; along += 8) {
                    for (int cross = -48; cross <= 48; cross += 4) {
                        int x = (int) Math.round(
                                centerX + tangentX * along + normalX * cross);
                        int z = (int) Math.round(
                                centerZ + tangentZ * along + normalZ * cross);
                        MegacityLayout.Location location = layout.locate(x, z);
                        if (location.zone() != targetZone) continue;
                        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
                        if (sample.roadClass() == targetRoad) {
                            return new BorderSample(x, z, sample);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void assertAuditedParkSite(
            GameTestHelper helper,
            MegacityLayout layout,
            int worldX,
            int worldZ,
            NeonCityGenerator.UrbanSample sample) {
        ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                layout, Math.floorDiv(worldX, 16), Math.floorDiv(worldZ, 16)).orElse(null);
        helper.assertTrue(placement != null
                        && placement.patch().district() == sample.district()
                        && placement.patch().placementZones().contains(sample.zone())
                        && ArnisPatchLibrary.isConservativeOpenParkTile(placement.patch()),
                "park overlaps an occupied or foreign Arnis tile at " + worldX + "," + worldZ);
        helper.assertTrue(!ArnisPatchLibrary.isParkAccessLaneAt(
                        placement, worldX, worldZ),
                "park erased its three-block access lane at " + worldX + "," + worldZ);
        int chunkX = Math.floorDiv(worldX, 16);
        int chunkZ = Math.floorDiv(worldZ, 16);
        int parkColumns = 0;
        int accessColumns = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int x = (chunkX << 4) + localX;
                int z = (chunkZ << 4) + localZ;
                NeonCityGenerator.RoadClass road = NeonCityGenerator.roadAt(x, z);
                if (road == NeonCityGenerator.RoadClass.PARK) parkColumns++;
                if (ArnisPatchLibrary.isParkAccessLaneAt(placement, x, z)) {
                    helper.assertTrue(road == NeonCityGenerator.RoadClass.LOCAL_STREET,
                            "park access lane is obstructed by " + road + " at " + x + "," + z);
                    accessColumns++;
                }
            }
        }
        helper.assertTrue(parkColumns == NeonCityGenerator.parkSiteColumnCount(
                        layout, chunkX, chunkZ)
                        && parkColumns >= NeonCityGenerator.MIN_PARK_SITE_COLUMNS,
                "park survived without one large usable footprint: " + parkColumns);
        helper.assertTrue(accessColumns == 12,
                "park must retain one complete 3x4 entrance lane: " + accessColumns);
    }

    public static void parkTreeLibrary(GameTestHelper helper) {
        helper.assertTrue(ParkTreeLibrary.templates().size() == 68,
                "park tree catalog must contain all 68 curated Exsilit models");
        helper.assertTrue(ParkTreeLibrary.templates().stream()
                        .anyMatch(tree -> tree.form() == ParkTreeLibrary.TreeForm.BROADLEAF)
                        && ParkTreeLibrary.templates().stream()
                        .anyMatch(tree -> tree.form() == ParkTreeLibrary.TreeForm.CONIFER),
                "park tree catalog must separate broadleaf and conifer silhouettes");
        Set<net.minecraft.resources.Identifier> templateIds = new HashSet<>();
        for (ParkTreeLibrary.TreeAsset tree : ParkTreeLibrary.templates()) {
            helper.assertTrue(templateIds.add(tree.templateId()),
                    "duplicate park tree template id " + tree.templateId());
            var loaded = helper.getLevel().getStructureManager().get(tree.templateId());
            helper.assertTrue(loaded.isPresent(),
                    "missing packaged park tree " + tree.templateId());
            var size = loaded.orElseThrow().getSize();
            helper.assertTrue(size.getX() == tree.sizeX()
                            && size.getY() == tree.sizeY()
                            && size.getZ() == tree.sizeZ()
                            && tree.sizeX() <= 11
                            && tree.sizeY() <= 24
                            && tree.sizeZ() <= 11,
                    "park tree size disagrees with catalog for " + tree.catalogId());
        }

        ParkTreeLibrary.TreeAsset tree = ParkTreeLibrary.template("t055");
        BlockPos base = helper.absolutePos(new BlockPos(2, 20, 2));
        for (int y = 0; y < tree.sizeY(); y++) {
            for (int z = 0; z < tree.sizeZ(); z++) {
                for (int x = 0; x < tree.sizeX(); x++) {
                    helper.getLevel().setBlock(
                            base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        helper.assertTrue(ParkTreeLibrary.placeTree(
                        helper.getLevel(), base, District.S_CORP, tree, TEST_SEED),
                "live Exsilit tree placement failed");
        int placedBlocks = 0;
        int cherryLogs = 0;
        int cherryLeaves = 0;
        int sourceOak = 0;
        for (int y = 0; y < tree.sizeY(); y++) {
            for (int z = 0; z < tree.sizeZ(); z++) {
                for (int x = 0; x < tree.sizeX(); x++) {
                    var state = helper.getLevel().getBlockState(base.offset(x, y, z));
                    if (!state.isAir()) placedBlocks++;
                    if (state.is(Blocks.CHERRY_LOG)) cherryLogs++;
                    if (state.is(Blocks.CHERRY_LEAVES)) cherryLeaves++;
                    if (state.is(Blocks.OAK_LOG) || state.is(Blocks.OAK_LEAVES)) sourceOak++;
                }
            }
        }
        helper.assertTrue(placedBlocks == tree.blockCount()
                        && cherryLogs >= 40
                        && cherryLeaves >= 100,
                "live park tree lost source geometry: blocks=" + placedBlocks
                        + ", logs=" + cherryLogs + ", leaves=" + cherryLeaves);
        helper.assertTrue(sourceOak == 0,
                "park tree did not adopt the S Corp cherry material family");
        helper.succeed();
    }

    public static void merchantStalls(GameTestHelper helper) {
        NeonCityGenerator.reset();
        BlockPos testOrigin = helper.absolutePos(BlockPos.ZERO);
        helper.assertTrue(MerchantTruckLibrary.decorateChunk(
                        helper.getLevel(), ChunkPos.containing(testOrigin), null) == 0,
                "legacy Spud trucks are still a world-generation source");

        EnumSet<MerchantTruckLibrary.MerchantRole> distributedRoles = EnumSet.noneOf(
                MerchantTruckLibrary.MerchantRole.class);
        for (District district : District.values()) {
            List<MerchantTruckLibrary.MerchantRole> roles =
                    VendorStallLibrary.plannedRoles(district);
            helper.assertTrue(roles.size() == MerchantTruckLibrary.MerchantRole.values().length
                            && EnumSet.copyOf(roles).equals(
                                    EnumSet.allOf(MerchantTruckLibrary.MerchantRole.class)),
                    district + " does not plan a fixer and all four trading stalls");
            distributedRoles.add(VendorStallLibrary.plannedRole(district));
        }
        helper.assertTrue(distributedRoles.containsAll(EnumSet.of(
                        MerchantTruckLibrary.MerchantRole.GUN,
                        MerchantTruckLibrary.MerchantRole.CYBERWARE,
                        MerchantTruckLibrary.MerchantRole.CLOTHING,
                        MerchantTruckLibrary.MerchantRole.CONSUMABLE)),
                "deterministic district emphasis does not cover all trading specialties");

        List<net.minecraft.world.item.Item> gunResults = MerchantTradeCatalog.resultItems(
                MerchantTruckLibrary.MerchantRole.GUN);
        for (GunType gun : GunType.values()) {
            if (gun != GunType.MANTIS_BLADE) {
                helper.assertTrue(gunResults.contains(WeaponItems.gun(gun).get()),
                        "gun merchant omitted " + gun);
            }
        }
        for (AmmoType ammo : AmmoType.values()) {
            helper.assertTrue(gunResults.contains(AmmoItems.item(ammo).get()),
                    "gun merchant omitted " + ammo);
        }

        List<net.minecraft.world.item.Item> cyberwareResults = MerchantTradeCatalog.resultItems(
                MerchantTruckLibrary.MerchantRole.CYBERWARE);
        long expectedCyberware = 0;
        for (Cyberware cyberware : Cyberware.VALUES) {
            boolean belowTierFour = cyberware.tier().rank() < CyberwareTier.T4.rank();
            helper.assertTrue(cyberwareResults.contains(CyberwareItems.item(cyberware).get())
                            == belowTierFour,
                    "cyberware merchant tier filter failed for " + cyberware.id());
            if (belowTierFour) expectedCyberware++;
        }
        helper.assertTrue(cyberwareResults.size() == expectedCyberware,
                "cyberware merchant has missing or duplicate sub-Tier-4 offers");

        List<net.minecraft.world.item.Item> foodResults = MerchantTradeCatalog.resultItems(
                MerchantTruckLibrary.MerchantRole.CONSUMABLE);
        helper.assertTrue(foodResults.contains(CyberdeckItems.SLOP.get())
                        && CyberdeckItems.SLOP.get().isFoil(
                                CyberdeckItems.SLOP.get().getDefaultInstance())
                        && CyberdeckItems.SLOP.get().getDefaultInstance().has(DataComponents.FOOD),
                "Slop is not an edible, enchanted-looking merchant item");

        BlockPos questAnchor = new BlockPos(-112, 73, 245);
        List<MissionService.MissionOffer> firstOffers = MissionService.offers(
                NeonCityGenerator.layout(), TEST_SEED, questAnchor, District.B_CORP);
        List<MissionService.MissionOffer> secondOffers = MissionService.offers(
                NeonCityGenerator.layout(), TEST_SEED, questAnchor, District.B_CORP);
        helper.assertTrue(firstOffers.equals(secondOffers)
                        && firstOffers.size() == MissionCatalog.definitions().size(),
                "fixer mission offers are not deterministic catalog projections");
        EnumSet<MissionCatalog.MissionType> missionTypes = EnumSet.noneOf(
                MissionCatalog.MissionType.class);
        for (MissionService.MissionOffer offer : firstOffers) {
            helper.assertTrue(offer.reward() > 0 && !offer.title().isBlank()
                            && !offer.objective().isBlank() && !offer.briefing().isBlank(),
                    "fixer offered an incomplete mission");
            MissionCatalog.MissionDefinition offeredDefinition =
                    MissionCatalog.definition(offer.definitionId());
            District offeredDistrict = District.values()[offer.targetDistrictOrdinal()];
            helper.assertTrue(offeredDefinition.targetDistricts().contains(offeredDistrict)
                            && (!offeredDefinition.targetDistricts().contains(District.B_CORP)
                                    || offeredDistrict == District.B_CORP),
                    "fixer mission did not prefer its own configured district");
            missionTypes.add(offer.type());
        }
        helper.assertTrue(missionTypes.equals(EnumSet.allOf(MissionCatalog.MissionType.class)),
                "fixer board does not cover all four configured mission types");

        BlockPos unsupported = helper.absolutePos(new BlockPos(5, 3, 6));
        prepareFlatVendorFloor(helper, unsupported);
        VendorStallLibrary.StallCandidate unsupportedStall =
                new VendorStallLibrary.StallCandidate(
                        District.B_CORP, unsupported, Direction.SOUTH, TEST_SEED);
        helper.assertTrue(!VendorStallLibrary.place(
                        helper.getLevel(), unsupportedStall,
                        MerchantTruckLibrary.MerchantRole.QUEST),
                "a freestanding roadside stall passed the Arnis facade preflight");

        BlockPos stallCenter = helper.absolutePos(new BlockPos(7, 3, 22));
        prepareVendorBay(helper, stallCenter);
        VendorStallLibrary.StallCandidate liveStall = new VendorStallLibrary.StallCandidate(
                District.B_CORP, stallCenter, Direction.SOUTH, TEST_SEED ^ 0x5157L);
        helper.assertTrue(VendorStallLibrary.isAttachedFacade(helper.getLevel(), liveStall),
                "synthetic open building face did not pass facade validation");
        helper.assertTrue(VendorStallLibrary.place(
                        helper.getLevel(), liveStall, MerchantTruckLibrary.MerchantRole.QUEST),
                "live fixer stall refused a verified building bay");
        Map<BlockPos, BlockState> stallBlocks = VendorStallLibrary.stallBlocks(
                liveStall, MerchantTruckLibrary.MerchantRole.QUEST);
        helper.assertTrue(stallBlocks.values().stream().anyMatch(state -> state.is(Blocks.LECTERN))
                        && stallBlocks.values().stream().filter(state -> state.is(Blocks.BARREL)).count() == 2
                        && stallBlocks.values().stream().filter(state ->
                        state.is(Blocks.CONCRETE.pick(DyeColor.BLACK))).count() >= 4
                        && stallBlocks.keySet().stream().allMatch(position ->
                        helper.getLevel().getBlockState(position).equals(stallBlocks.get(position))),
                "fixer stall lost its lectern, barrels, role header, or deterministic block plan");

        BlockPos merchantCenter = helper.absolutePos(new BlockPos(21, 3, 22));
        prepareVendorBay(helper, merchantCenter);
        VendorStallLibrary.StallCandidate merchantStall =
                new VendorStallLibrary.StallCandidate(
                        District.B_CORP, merchantCenter, Direction.SOUTH, TEST_SEED ^ 0xCAFE1L);
        helper.assertTrue(VendorStallLibrary.place(
                        helper.getLevel(), merchantStall,
                        MerchantTruckLibrary.MerchantRole.CYBERWARE),
                "cyberware merchant refused a second verified building bay");

        long expectedCyberwareOffers = expectedCyberware;
        helper.runAfterDelay(1, () -> {
            VendorService.maintainAnchors(helper.getLevel());
        VendorAnchorData anchors = VendorAnchorData.get(helper.getLevel());
        VendorAnchorData.Anchor fixerAnchor = anchors.anchor(
                VendorService.siteId(stallCenter)).orElseThrow();
        VendorAnchorData.Anchor merchantAnchor = anchors.anchor(
                VendorService.siteId(merchantCenter)).orElseThrow();
        Villager fixerEntity = anchoredMerchant(helper.getLevel(), fixerAnchor);
        Villager merchantEntity = anchoredMerchant(helper.getLevel(), merchantAnchor);
        helper.assertTrue(fixerEntity != null
                        && fixerEntity.isNoAi()
                        && fixerEntity.isInvulnerable()
                        && MerchantTruckLibrary.merchantRole(fixerEntity).orElseThrow()
                        == MerchantTruckLibrary.MerchantRole.QUEST,
                "fixer anchor did not resolve to its immovable, invulnerable entity");
        helper.assertTrue(merchantEntity != null
                        && merchantEntity.isNoAi()
                        && merchantEntity.isInvulnerable()
                        && merchantEntity.getOffers().size()
                        == expectedCyberwareOffers,
                "merchant anchor did not preserve the cyberware inventory or lock state");

        Villager fixer = fixerEntity;
        fixer.setPos(stallCenter.getX() + 7.5, stallCenter.getY(), stallCenter.getZ() + 0.5);
        fixer.setDeltaMovement(new net.minecraft.world.phys.Vec3(1.0, 0.5, -1.0));
        VendorService.maintainAnchors(helper.getLevel());
        VendorService.maintainAnchors(helper.getLevel());
        VendorAnchorData.Anchor restoredFixerAnchor = anchors.anchor(
                VendorService.siteId(stallCenter)).orElseThrow();
        Villager restored = anchoredMerchant(helper.getLevel(), restoredFixerAnchor);
        helper.assertTrue(restored != null
                        && restored.blockPosition().equals(stallCenter)
                        && restored.getDeltaMovement().lengthSqr() == 0.0,
                "vendor anchor maintenance did not restore its entity position or velocity");

        BlockPos retiredTruckSite = helper.absolutePos(new BlockPos(34, 3, 8));
        BlockPos retiredTruckMerchant = retiredTruckSite.offset(3, 2, 4);
        Villager legacyMerchant = MerchantTruckLibrary.createMerchant(
                helper.getLevel(), retiredTruckMerchant, 0.0F,
                MerchantTruckLibrary.MerchantRole.GUN, District.C_CORP, retiredTruckSite);
        helper.assertTrue(legacyMerchant != null,
                "could not create legacy truck merchant migration fixture");
        String retiredTruckId = "vendor_" + retiredTruckSite.asLong();
        anchors.register(
                retiredTruckId, MerchantTruckLibrary.MerchantRole.GUN, District.C_CORP,
                retiredTruckSite, retiredTruckMerchant, 0.0F, legacyMerchant.getUUID());
        helper.getLevel().addFreshEntity(legacyMerchant);

        BlockPos retiredOpenSite = helper.absolutePos(new BlockPos(34, 3, 22));
        Villager legacyOpenMerchant = MerchantTruckLibrary.createMerchant(
                helper.getLevel(), retiredOpenSite, 0.0F,
                MerchantTruckLibrary.MerchantRole.CONSUMABLE,
                District.C_CORP, retiredOpenSite);
        helper.assertTrue(legacyOpenMerchant != null,
                "could not create legacy open-space merchant migration fixture");
        String retiredOpenId = "vendor_" + retiredOpenSite.asLong();
        anchors.register(
                retiredOpenId, MerchantTruckLibrary.MerchantRole.CONSUMABLE, District.C_CORP,
                retiredOpenSite, retiredOpenSite, 0.0F, legacyOpenMerchant.getUUID());
        helper.getLevel().addFreshEntity(legacyOpenMerchant);

        VendorService.maintainAnchors(helper.getLevel());
        helper.assertTrue(anchors.anchor(retiredTruckId).isPresent()
                        && anchors.anchor(retiredOpenId).isPresent()
                        && !legacyMerchant.isRemoved()
                        && !legacyOpenMerchant.isRemoved(),
                "legacy vendors were retired before role-matched building stalls existed");

        BlockPos malformedPos = helper.absolutePos(new BlockPos(52, 3, 30));
        Villager malformedMerchant = MerchantTruckLibrary.createMerchant(
                helper.getLevel(), malformedPos, 0.0F,
                MerchantTruckLibrary.MerchantRole.GUN, District.D_CORP, malformedPos);
        helper.assertTrue(malformedMerchant != null,
                "could not create malformed merchant fixture");
        malformedMerchant.getPersistentData().remove("cyberdeck_merchant_role");
        helper.getLevel().addFreshEntity(malformedMerchant);
        helper.assertTrue(malformedMerchant.isRemoved(),
                "malformed tagged merchant remained loaded without an anchor");

        Villager duplicateFixer = MerchantTruckLibrary.createMerchant(
                helper.getLevel(), stallCenter, liveStall.yaw(),
                MerchantTruckLibrary.MerchantRole.QUEST, District.B_CORP, stallCenter);
        helper.assertTrue(duplicateFixer != null
                        && helper.getLevel().addFreshEntity(duplicateFixer),
                "could not create duplicate-suppression fixture");
        helper.runAfterDelay(1, () -> {
            BlockPos replacementGun = helper.absolutePos(new BlockPos(46, 3, 8));
            BlockPos replacementFood = helper.absolutePos(new BlockPos(46, 3, 22));
            anchors.register(
                    VendorService.siteId(replacementGun), MerchantTruckLibrary.MerchantRole.GUN,
                    District.C_CORP, replacementGun, replacementGun, 0.0F, null);
            anchors.register(
                    VendorService.siteId(replacementFood),
                    MerchantTruckLibrary.MerchantRole.CONSUMABLE,
                    District.C_CORP, replacementFood, replacementFood, 0.0F, null);
            VendorService.maintainAnchors(helper.getLevel());
            helper.assertTrue(anchors.anchor(retiredTruckId).isEmpty()
                            && anchors.anchor(retiredOpenId).isEmpty()
                            && anchors.anchor(VendorService.siteId(replacementGun)).isPresent()
                            && anchors.anchor(VendorService.siteId(replacementFood)).isPresent()
                            && legacyMerchant.isRemoved()
                            && legacyOpenMerchant.isRemoved(),
                    "legacy anchors did not retire after role-matched v2 replacements appeared");

            List<OpenCityMapPacket.Marker> vendorMarkers = CityMapService.markers(
                    NeonCityGenerator.layout(), Optional.empty(), anchors.anchors());
            helper.assertTrue(OpenCityMapPacket.MAX_MARKERS >= 256
                            && vendorMarkers.stream().anyMatch(marker ->
                            marker.kind() == OpenCityMapPacket.MarkerKind.FIXER
                                    && marker.x() == fixerAnchor.merchantPos().getX()
                                    && marker.z() == fixerAnchor.merchantPos().getZ())
                            && vendorMarkers.stream().anyMatch(marker ->
                            marker.kind() == OpenCityMapPacket.MarkerKind.MERCHANT
                                    && marker.x() == merchantAnchor.merchantPos().getX()
                                    && marker.z() == merchantAnchor.merchantPos().getZ())
                            && vendorMarkers.stream().noneMatch(marker ->
                            marker.x() == retiredTruckMerchant.getX()
                                    && marker.z() == retiredTruckMerchant.getZ()),
                    "city map did not expose distinct exact fixer and merchant registry anchors");

            int liveAtAnchor = 0;
            for (net.minecraft.world.entity.Entity entity : helper.getLevel().getAllEntities()) {
                if (!entity.isRemoved()
                        && entity instanceof Villager
                        && MerchantTruckLibrary.merchantAnchor(entity)
                        .filter(stallCenter::equals).isPresent()) {
                    liveAtAnchor++;
                }
            }
            VendorAnchorData.Anchor currentFixer = anchors.anchor(
                    VendorService.siteId(stallCenter)).orElseThrow();
            Villager keeper = anchoredMerchant(helper.getLevel(), currentFixer);
            helper.assertTrue(liveAtAnchor == 1
                            && keeper != null
                            && keeper.blockPosition().equals(stallCenter),
                    "vendor maintenance did not suppress a true duplicate at its anchor");
            helper.succeed();
        });
        });
    }

    private static Villager anchoredMerchant(
            ServerLevel level, VendorAnchorData.Anchor anchor) {
        UUID entityId = anchor.entityUuid().orElse(null);
        Villager taggedMatch = null;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Villager villager)
                    || entity.isRemoved()
                    || !MerchantTruckLibrary.isMerchant(entity)) {
                continue;
            }
            if (entityId != null && entity.getUUID().equals(entityId)) {
                return villager;
            }
            if (taggedMatch == null
                    && MerchantTruckLibrary.merchantAnchor(entity)
                    .filter(anchor.sitePos()::equals).isPresent()
                    && MerchantTruckLibrary.merchantRole(entity)
                    .filter(anchor.role()::equals).isPresent()
                    && MerchantTruckLibrary.merchantDistrict(entity)
                    .filter(anchor.district()::equals).isPresent()) {
                taggedMatch = villager;
            }
        }
        return taggedMatch;
    }

    private static void prepareFlatVendorFloor(
            GameTestHelper helper, BlockPos center) {
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                helper.getLevel().setBlock(
                        center.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 7; y++) {
                    helper.getLevel().setBlock(
                            center.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void prepareVendorBay(
            GameTestHelper helper, BlockPos center) {
        prepareFlatVendorFloor(helper, center);
        for (int x : List.of(-3, 3)) {
            for (int z = -2; z <= 0; z++) {
                for (int y = 0; y <= 6; y++) {
                    helper.getLevel().setBlock(
                            center.offset(x, y, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
                }
            }
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 0; z++) {
                helper.getLevel().setBlock(
                        center.offset(x, 4, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
            }
        }
    }

    public static void missionSystem(GameTestHelper helper) {
        MissionService.reset();
        List<MissionCatalog.MissionDefinition> definitions = MissionCatalog.definitions();
        helper.assertTrue(definitions.size() >= 4
                        && definitions.stream().map(MissionCatalog.MissionDefinition::type)
                        .collect(java.util.stream.Collectors.toSet())
                        .containsAll(EnumSet.allOf(MissionCatalog.MissionType.class)),
                "JSON catalog did not validate all four mission types");

        BlockPos origin = helper.absolutePos(new BlockPos(3, 3, 3));
        for (int x = -2; x <= 8; x++) {
            for (int z = -2; z <= 8; z++) {
                helper.getLevel().setBlock(
                        new BlockPos(origin.getX() + x, origin.getY() - 1, origin.getZ() + z),
                        Blocks.STONE.defaultBlockState(), 3);
            }
        }
        ServerPlayer player = MissionFeatureGameTests.makeUniquePlayer(helper, "missions");
        player.setGameMode(GameType.SURVIVAL);
        player.snapTo(origin.getX() + 6.5, origin.getY(), origin.getZ() + 6.5,
                180.0F, 0.0F);
        player.getInventory().clearContent();

        MissionCatalog.MissionDefinition psychoDefinition = definitions.stream()
                .filter(value -> value.type()
                        == MissionCatalog.MissionType.NEUTRALIZE_CYBERPSYCHO)
                .findFirst().orElseThrow();
        MissionService.ActiveMission psychoMission = testMission(
                psychoDefinition, origin, 12, "");
        MissionService.save(player, psychoMission);
        MissionService.ActiveMission spawnedPsycho = MissionService.spawnCyberpsycho(
                helper.getLevel(), player, psychoDefinition, psychoMission);
        helper.assertTrue(spawnedPsycho != null, "cyberpsycho mission did not spawn its boss");
        MissionService.save(player, spawnedPsycho);
        net.minecraft.world.entity.Entity psychoActor = entityByUuid(
                helper.getLevel(), spawnedPsycho.actorUuid());
        helper.assertTrue(psychoActor instanceof CyberpsychoEntity,
                "cyberpsycho mission actor was not immediately addressable by its durable UUID");
        CyberpsychoEntity psycho = (CyberpsychoEntity) psychoActor;
        helper.assertTrue(psycho.getMaxHealth()
                        == CyberpsychoEntity.balancedHealth(
                                psychoDefinition.cyberpsychoHealth())
                        && psycho.installedCyberware().equals(psychoDefinition.cyberware())
                        && psycho.getGrenadeCount() == psychoDefinition.cyberpsychoGrenades()
                        && psycho.getMainHandItem().is(
                                WeaponItems.gun(psychoDefinition.cyberpsychoGun()).get())
                        && MissionService.isMissionActor(psycho),
                "cyberpsycho lost configured health, cyberware, firearm, grenades, or mission tag");
        int emeralds = inventoryCount(player, CyberdeckItems.EMMIES.get());
        MissionService.onEntityDeath(new LivingDeathEvent(
                psycho, helper.getLevel().damageSources().playerAttack(player)));
        helper.assertTrue(MissionService.activeMission(player).isEmpty()
                        && inventoryCount(player, CyberdeckItems.EMMIES.get())
                                == emeralds + psychoMission.reward(),
                "neutralize mission did not complete from the owner's target kill");

        MissionCatalog.MissionDefinition assassinSource = definitions.stream()
                .filter(value -> value.type() == MissionCatalog.MissionType.ASSASSINATE_TARGET)
                .findFirst().orElseThrow();
        MissionCatalog.MissionDefinition assassinDefinition = new MissionCatalog.MissionDefinition(
                assassinSource.id(), assassinSource.type(), assassinSource.title(),
                assassinSource.briefing(), assassinSource.targetName(),
                assassinSource.targetDistricts(), 9, 9, 0,
                assassinSource.objectiveRadius(), 0, null, 0, List.of(), null, 0);
        BlockPos assassinPos = origin.offset(3, 0, 0);
        MissionService.ActiveMission assassinMission = testMission(
                assassinDefinition, assassinPos, 9, "");
        MissionService.save(player, assassinMission);
        MissionService.ActiveMission spawnedAssassin = MissionService.spawnAssassination(
                helper.getLevel(), player, assassinDefinition, assassinMission);
        helper.assertTrue(spawnedAssassin != null,
                "assassination mission did not spawn its executive");
        MissionService.save(player, spawnedAssassin);
        net.minecraft.world.entity.Entity executiveActor = entityByUuid(
                helper.getLevel(), spawnedAssassin.actorUuid());
        helper.assertTrue(executiveActor instanceof CityNpc,
                "assassination target was not immediately addressable by its durable UUID");
        CityNpc executive = (CityNpc) executiveActor;
        helper.assertTrue(executive.getSkinVariant() == CityNpc.MISSION_TARGET_SKIN
                        && executive.isNoAi() && executive.isPersistenceRequired(),
                "assassination target lost its gold mission skin or durable fixed-area state");
        emeralds = inventoryCount(player, CyberdeckItems.EMMIES.get());
        MissionService.onEntityDeath(new LivingDeathEvent(
                executive, helper.getLevel().damageSources().playerAttack(player)));
        helper.assertTrue(MissionService.activeMission(player).isEmpty()
                        && inventoryCount(player, CyberdeckItems.EMMIES.get())
                                == emeralds + assassinMission.reward(),
                "assassination mission did not complete from the owner's target kill");

        MissionCatalog.MissionDefinition dataSource = definitions.stream()
                .filter(value -> value.type() == MissionCatalog.MissionType.STEAL_DATA)
                .findFirst().orElseThrow();
        MissionCatalog.MissionDefinition dataDefinition = new MissionCatalog.MissionDefinition(
                dataSource.id(), dataSource.type(), dataSource.title(), dataSource.briefing(),
                dataSource.targetName(), dataSource.targetDistricts(), 7, 7, 0,
                dataSource.objectiveRadius(), 0, null, 0, List.of(), null, 0);
        BlockPos terminalPos = origin.offset(0, 0, 3);
        MissionService.ActiveMission dataMission = testMission(dataDefinition, terminalPos, 7, "");
        MissionService.save(player, dataMission);
        helper.assertTrue(MissionService.installDataObjective(
                        helper.getLevel(), player, dataDefinition, dataMission) != null
                        && helper.getLevel().getBlockState(terminalPos)
                        .is(MissionBlocks.DATA_TERMINAL.get()),
                "steal-data mission did not install its secured terminal");
        emeralds = inventoryCount(player, CyberdeckItems.EMMIES.get());
        helper.assertTrue(MissionService.activateDataTerminal(player, terminalPos)
                        && MissionService.activeMission(player).isEmpty()
                        && helper.getLevel().isEmptyBlock(terminalPos)
                        && inventoryCount(player, CyberdeckItems.EMMIES.get())
                                == emeralds + dataMission.reward(),
                "secured terminal interaction did not complete and clean up the data mission");

        MissionCatalog.MissionDefinition shipping = definitions.stream()
                .filter(value -> value.type() == MissionCatalog.MissionType.SHIP_ITEM)
                .findFirst().orElseThrow();
        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        MegacityLayout.Node destination = layout.node(District.A_CORP);
        BlockPos delivery = new BlockPos(destination.x(), origin.getY(), destination.z());
        MissionService.ActiveMission shippingMission = testMission(
                shipping, delivery, 11, shipping.cargoItem().toString());
        MissionService.save(player, shippingMission);
        MissionService.ContractContext shippingContext = new MissionService.ContractContext(
                MissionService.ContractKind.GIG,
                shipping.streetCred(),
                UUID.randomUUID(),
                PartyService.participantSnapshot(player),
                true,
                false);
        MissionService.saveContext(player, shippingContext);
        PartyService.registerContract(
                helper.getLevel(), shippingContext.instanceId(), shippingContext.participants());
        MissionService.ActiveMission installedDelivery =
                MissionService.installDeliveryObjective(
                        helper.getLevel(), player, shipping, shippingMission);
        helper.assertTrue(installedDelivery != null
                        && helper.getLevel().getBlockState(delivery)
                        .is(MissionBlocks.DELIVERY_TERMINAL.get())
                        && shipping.objectiveText().contains(shipping.targetName()),
                "shipping mission did not define and install its delivery endpoint");
        MissionService.save(player, installedDelivery);
        net.minecraft.world.item.Item cargo = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getValue(shipping.cargoItem());
        net.minecraft.world.item.Item contractCargo = MissionBlocks.CONTRACT_CARGO.get();
        player.getInventory().add(new net.minecraft.world.item.ItemStack(
                cargo, shipping.cargoCount()));
        helper.assertTrue(!MissionService.activateDeliveryTerminal(player, delivery)
                        && MissionService.activeMission(player).isPresent()
                        && helper.getLevel().getBlockState(delivery)
                        .is(MissionBlocks.DELIVERY_TERMINAL.get()),
                "delivery endpoint accepted ordinary items without the contract cargo tag");
        helper.assertTrue(MissionService.issueCargo(
                        helper.getLevel(), player, shipping, shippingMission) != null,
                "shipping mission did not issue configured cargo");
        net.minecraft.world.item.crafting.CraftingInput cargoInput =
                net.minecraft.world.item.crafting.CraftingInput.of(
                        1, 1, List.of(new net.minecraft.world.item.ItemStack(contractCargo)));
        helper.assertTrue(contractCargo != cargo
                        && inventoryCount(player, cargo) == shipping.cargoCount()
                        && inventoryCount(player, contractCargo) == shipping.cargoCount()
                        && helper.getLevel().getServer().getRecipeManager().getRecipeFor(
                                net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                                cargoInput, helper.getLevel()).isEmpty(),
                "shipping mission did not issue inert, recipe-less contract cargo");
        int heldCargoSlot = -1;
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.getSelectionSize();
                slot++) {
            if (player.getInventory().getItem(slot).is(contractCargo)) {
                heldCargoSlot = slot;
                break;
            }
        }
        helper.assertTrue(heldCargoSlot >= 0,
                "shipping fixture could not hold cargo for endpoint interaction");
        player.getInventory().setSelectedSlot(heldCargoSlot);
        OpenCityMapPacket.Marker missionMarker = MissionService.activeMarker(player).orElseThrow();
        helper.assertTrue(missionMarker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION
                        && missionMarker.x() == delivery.getX()
                        && missionMarker.z() == delivery.getZ(),
                "active shipping mission is not represented by its real map objective");
        emeralds = inventoryCount(player, CyberdeckItems.EMMIES.get());
        player.snapTo(delivery.getX() + 0.5, delivery.getY(), delivery.getZ() + 0.5,
                0.0F, 0.0F);
        MissionService.tickPlayer(player, layout.locate(destination.x(), destination.z()));
        helper.assertTrue(MissionService.activeMission(player).isPresent()
                        && helper.getLevel().getBlockState(delivery)
                        .is(MissionBlocks.DELIVERY_TERMINAL.get()),
                "shipping mission auto-completed from proximity instead of endpoint interaction");
        helper.assertTrue(!MissionService.activateDeliveryTerminal(player, delivery.above())
                        && MissionService.activeMission(player).isPresent(),
                "delivery interaction accepted a position other than its exact endpoint");
        net.minecraft.world.InteractionResult deliveryResult =
                helper.getLevel().getBlockState(delivery).useItemOn(
                        player.getMainHandItem(), helper.getLevel(), player,
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(
                                new net.minecraft.world.phys.Vec3(
                                        delivery.getX() + 0.5,
                                        delivery.getY() + 0.5,
                                        delivery.getZ() + 0.5),
                                Direction.NORTH, delivery, false));
        boolean missionCleared = MissionService.activeMission(player).isEmpty();
        boolean endpointCleared = helper.getLevel().isEmptyBlock(delivery);
        int ordinaryCargoAfter = inventoryCount(player, cargo);
        int contractCargoAfter = inventoryCount(player, contractCargo);
        int emmiesAfter = inventoryCount(player, CyberdeckItems.EMMIES.get());
        helper.assertTrue(deliveryResult.consumesAction()
                        && missionCleared
                        && endpointCleared
                        && ordinaryCargoAfter == shipping.cargoCount()
                        && contractCargoAfter == 0
                        && emmiesAfter == emeralds + shippingMission.reward(),
                "delivery endpoint state mismatch: result=" + deliveryResult
                        + ", missionCleared=" + missionCleared
                        + ", endpointCleared=" + endpointCleared
                        + ", ordinaryCargo=" + ordinaryCargoAfter
                        + ", contractCargo=" + contractCargoAfter
                        + ", emmies=" + emmiesAfter
                        + ", expectedEmmies=" + (emeralds + shippingMission.reward()));
        int paid = inventoryCount(player, CyberdeckItems.EMMIES.get());
        helper.assertTrue(!MissionService.activateDeliveryTerminal(player, delivery)
                        && inventoryCount(player, CyberdeckItems.EMMIES.get()) == paid,
                "completed delivery endpoint paid the contract more than once");

        BlockPos abandonedDelivery = delivery.offset(4, 0, 0);
        MissionService.ActiveMission abandonedMission = testMission(
                shipping, abandonedDelivery, 13, shipping.cargoItem().toString());
        UUID abandonedInstance = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        UUID contractLeader = UUID.randomUUID();
        UUID successorOutsideContract = new UUID(Long.MIN_VALUE, Long.MIN_VALUE);
        PartySavedData partyData = PartySavedData.get(helper.getLevel());
        MissionService.ContractContext soloAuthority = new MissionService.ContractContext(
                MissionService.ContractKind.GIG,
                shipping.streetCred(),
                UUID.randomUUID(),
                new PartyService.ParticipantSnapshot(
                        Optional.empty(), List.of(player.getUUID())),
                true,
                false);
        helper.assertTrue(MissionService.canAbandon(
                        helper.getLevel(), player.getUUID(), soloAuthority),
                "solo player lost authority to abandon their own contract");
        partyData.create(partyId, contractLeader, 0);
        partyData.addMember(partyId, successorOutsideContract, 0).orElseThrow();
        partyData.addMember(partyId, player.getUUID(), 0).orElseThrow();
        PartyService.ParticipantSnapshot abandonedParticipants =
                new PartyService.ParticipantSnapshot(
                        Optional.of(partyId), List.of(contractLeader, player.getUUID()));
        MissionService.ContractContext abandonedContext = new MissionService.ContractContext(
                MissionService.ContractKind.GIG,
                shipping.streetCred(),
                abandonedInstance,
                abandonedParticipants,
                true,
                false);
        MissionService.save(player, abandonedMission);
        MissionService.saveContext(player, abandonedContext);
        MissionService.ActiveMission installedAbandonedDelivery =
                MissionService.installDeliveryObjective(
                        helper.getLevel(), player, shipping, abandonedMission);
        helper.assertTrue(installedAbandonedDelivery != null,
                "abandon test could not install its delivery endpoint");
        MissionService.save(player, installedAbandonedDelivery);
        MissionJournalData.get(helper.getLevel()).accept(
                abandonedParticipants, abandonedContext, installedAbandonedDelivery,
                helper.getLevel().getGameTime());
        PartyService.registerContract(
                helper.getLevel(), abandonedInstance, abandonedParticipants);
        helper.assertTrue(MissionService.issueCargo(
                        helper.getLevel(), player, shipping, abandonedMission) != null
                        && inventoryCount(player, cargo) == shipping.cargoCount()
                        && inventoryCount(player, contractCargo) == shipping.cargoCount(),
                "abandon test could not stage tagged and ordinary cargo");
        int beforeAbandon = inventoryCount(player, CyberdeckItems.EMMIES.get());
        helper.assertTrue(!MissionService.canAbandon(
                                helper.getLevel(), player.getUUID(), abandonedContext)
                        && !MissionService.abandon(player)
                        && MissionService.activeMission(player).isPresent()
                        && helper.getLevel().getBlockState(abandonedDelivery)
                                .is(MissionBlocks.DELIVERY_TERMINAL.get())
                        && inventoryCount(player, contractCargo) == shipping.cargoCount()
                        && inventoryCount(player, CyberdeckItems.EMMIES.get()) == beforeAbandon,
                "non-leader participant abandoned the shared delivery contract");
        partyData.removeMember(contractLeader).orElseThrow();
        helper.assertTrue(partyData.party(partyId).orElseThrow().leader()
                        .equals(successorOutsideContract)
                        && MissionService.canAbandon(
                                helper.getLevel(), player.getUUID(), abandonedContext),
                "leadership outside the accepted cohort left its participants unable to quit");
        partyData.disband(partyId).orElseThrow();
        helper.assertTrue(MissionService.canAbandon(
                                helper.getLevel(), player.getUUID(), abandonedContext)
                        && !MissionService.canAbandon(
                                helper.getLevel(), UUID.randomUUID(), abandonedContext)
                        && MissionService.abandon(player)
                        && MissionService.activeMission(player).isEmpty()
                        && helper.getLevel().isEmptyBlock(abandonedDelivery)
                        && helper.getLevel().getEntitiesOfClass(
                                net.minecraft.world.entity.Entity.class,
                                new AABB(abandonedDelivery).inflate(1.0),
                                MissionService::isMissionActor).isEmpty()
                        && inventoryCount(player, cargo) == shipping.cargoCount()
                        && inventoryCount(player, contractCargo) == 0
                        && inventoryCount(player, CyberdeckItems.EMMIES.get()) == beforeAbandon
                        && MissionService.journalEntries(player).stream().anyMatch(entry ->
                                entry.instanceId().equals(abandonedInstance)
                                        && entry.status()
                                        == MissionService.JournalStatus.ABANDONED),
                "abandon did not clean delivery state without payment or ordinary-item loss");
        PartyService.acknowledgeContractClear(
                helper.getLevel(), abandonedInstance, contractLeader);

        BlockPos unavailableTarget = new BlockPos(
                100_000, NeonCityGenerator.CITY_GROUND_Y + 1, 100_000);
        MissionService.ActiveMission delayedMission = testMission(
                assassinDefinition, unavailableTarget, 9, "");
        MissionService.ContractContext delayedContext = new MissionService.ContractContext(
                MissionService.ContractKind.GIG,
                assassinDefinition.streetCred(),
                UUID.randomUUID(),
                PartyService.participantSnapshot(player),
                false,
                false);
        MissionService.save(player, delayedMission);
        MissionService.saveContext(player, delayedContext);
        PartyService.registerContract(
                helper.getLevel(), delayedContext.instanceId(), delayedContext.participants());
        MissionJournalData.get(helper.getLevel()).accept(
                delayedContext.participants(), delayedContext, delayedMission,
                helper.getLevel().getGameTime());
        player.snapTo(
                unavailableTarget.getX() + 0.5,
                unavailableTarget.getY(),
                unavailableTarget.getZ() + 0.5,
                0.0F,
                0.0F);
        MissionService.tickPlayer(
                player, layout.locate(unavailableTarget.getX(), unavailableTarget.getZ()));
        helper.assertTrue(MissionService.activeMission(player).isPresent()
                        && MissionService.contractContext(player)
                                .filter(context -> !context.deployed()).isPresent()
                        && !PartyService.isContractTerminal(
                                helper.getLevel(), delayedContext.instanceId())
                        && MissionService.journalEntries(player).stream().anyMatch(entry ->
                                entry.instanceId().equals(delayedContext.instanceId())
                                        && entry.status()
                                        == MissionService.JournalStatus.ACTIVE),
                "unavailable mission building incorrectly failed the accepted contract");
        BlockPos canonicalTarget = unavailableTarget.offset(7, 0, 9);
        MissionService.ActiveMission canonicalMission = new MissionService.ActiveMission(
                delayedMission.definitionId(), delayedMission.type(), delayedMission.title(),
                delayedMission.briefing(), delayedMission.objective(),
                delayedMission.targetDistrict(), canonicalTarget, delayedMission.reward(), "",
                delayedMission.cargoItem(), delayedMission.cargoCount(),
                delayedMission.acceptedTick());
        MissionJournalData journal = MissionJournalData.get(helper.getLevel());
        long legacyUpdatedTick = helper.getLevel().getGameTime() + 1_000;
        journal.accept(
                delayedContext.participants(), delayedContext, canonicalMission,
                legacyUpdatedTick);
        journal.accept(
                delayedContext.participants(), delayedContext, delayedMission,
                delayedMission.acceptedTick());
        MissionSiteData legacyReservations = MissionSiteData.get(helper.getLevel());
        legacyReservations.reserve(
                "test:legacy:" + delayedContext.instanceId(), delayedContext.instanceId());
        MissionService.onPlayerLogin(player);
        helper.assertTrue(MissionService.activeMission(player)
                                .filter(mission -> mission.target().equals(canonicalTarget)).isPresent()
                        && MissionService.contractContext(player)
                                .filter(MissionService.ContractContext::deployed).isPresent()
                        && MissionService.journalEntries(player).stream().anyMatch(entry ->
                                entry.instanceId().equals(delayedContext.instanceId())
                                        && entry.deployed()
                                        && entry.targetY() == canonicalTarget.getY()
                                        && entry.updatedTick() == legacyUpdatedTick),
                "offline participant did not restore the canonical deployed objective");
        helper.assertTrue(MissionService.abandon(player)
                        && !legacyReservations.hasReservation(delayedContext.instanceId()),
                "delayed contract could not be cleanly abandoned or release its site");
        MissionFeatureGameTests.disconnect(player);
        helper.succeed();
    }

    public static void missionBuildingPlanner(GameTestHelper helper) {
        BlockPos origin = helper.absolutePos(new BlockPos(4, 4, 4));
        MissionBuildingPlanner.Site site = syntheticMissionSite(origin);
        prepareMissionSite(helper, site);

        MissionBuildingPlanner.Site restored = MissionBuildingPlanner.Site.load(site.save())
                .orElseThrow();
        helper.assertTrue(restored.id().equals(site.id())
                        && restored.district() == site.district()
                        && sameBounds(restored.bounds(), site.bounds())
                        && restored.floorYs().equals(site.floorYs())
                        && restored.target().equals(site.target())
                        && restored.entrance().equals(site.entrance())
                        && restored.stairs().equals(site.stairs())
                        && restored.patrolRoutes().equals(site.patrolRoutes())
                        && restored.decorations().equals(site.decorations())
                        && restored.planSeed() == site.planSeed(),
                "mission building site did not survive its NBT round trip");

        BlockPos untouchedBlockEntity = origin.offset(2, 0, 2);
        helper.getLevel().setBlock(
                untouchedBlockEntity, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(MissionBuildingPlanner.preflight(helper.getLevel(), restored),
                "untouched block entity rejected an otherwise valid mission building");
        helper.assertTrue(MissionBuildingPlanner.install(helper.getLevel(), restored)
                        == MissionBuildingPlanner.InstallationResult.INSTALLED,
                "valid synthetic mission building was not installed");
        helper.assertTrue(helper.getLevel().getBlockState(untouchedBlockEntity).is(Blocks.CHEST),
                "mission installation modified an untouched block entity");
        helper.getLevel().setBlock(
                untouchedBlockEntity, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(MissionBuildingPlanner.install(helper.getLevel(), restored)
                        == MissionBuildingPlanner.InstallationResult.ALREADY_INSTALLED,
                "mission building installation was not idempotent");

        Direction entranceAcross = restored.entrance().outward().getClockWise();
        helper.assertTrue(helper.getLevel().getBlockState(restored.entrance().position())
                        .is(Blocks.COPPER_DOOR.waxed().weathered())
                        && helper.getLevel().getBlockState(restored.entrance().position())
                        .getValue(net.minecraft.world.level.block.DoorBlock.OPEN)
                        && helper.getLevel().getBlockState(
                                restored.entrance().position().relative(entranceAcross))
                        .is(Blocks.COPPER_DOOR.waxed().weathered())
                        && helper.getLevel().getBlockState(
                                restored.entrance().position().relative(entranceAcross))
                        .getValue(net.minecraft.world.level.block.DoorBlock.OPEN),
                "mission building did not install a traversable two-wide entrance");
        for (MissionBuildingPlanner.StairRun stair : restored.stairs()) {
            Direction across = stair.ascending().getClockWise();
            for (int step = 0; step < stair.rise(); step++) {
                for (int lane = 0; lane < 2; lane++) {
                    BlockPos position = stair.start().relative(stair.ascending(), step)
                            .relative(across, lane).above(step);
                    helper.assertTrue(helper.getLevel().getBlockState(position)
                                    .is(Blocks.POLISHED_DEEPSLATE_STAIRS)
                                    && helper.getLevel().isEmptyBlock(position.above())
                                    && helper.getLevel().isEmptyBlock(position.above(2)),
                            "mission stair run lost width or headroom at " + position);
                }
            }
            for (int lane = 0; lane < 2; lane++) {
                for (int depth = 1; depth <= 2; depth++) {
                    assertMissionPassage(helper,
                            stair.start().relative(stair.ascending().getOpposite(), depth)
                                    .relative(across, lane),
                            "lower stair approach");
                }
                for (int depth = 0; depth < 2; depth++) {
                    assertMissionPassage(helper,
                            stair.start().relative(stair.ascending(), stair.rise() + depth)
                                    .relative(across, lane).above(stair.rise()),
                            "upper stair landing");
                }
            }
        }
        helper.assertTrue(stairsHaveTestClearance(restored.stairs()),
                "successive mission stairs overlap or lack horizontal separation");
        helper.assertTrue(restored.patrolRoutes().size() == restored.floorYs().size()
                        && restored.stairs().size() == restored.floorYs().size() - 1
                        && restored.floorYs().stream().allMatch(floorY ->
                                restored.decorations().stream().filter(decoration ->
                                        decoration.position().getY() == floorY).count()
                                        >= (floorY == restored.floorYs().getFirst() ? 3 : 4))
                        && restored.decorations().stream().allMatch(decoration ->
                                !helper.getLevel().isEmptyBlock(decoration.position())),
                "mission site lost multi-floor patrol or office-cover invariants");
        for (MissionBuildingPlanner.PatrolRoute route : restored.patrolRoutes()) {
            helper.assertTrue(restored.floorYs().contains(route.floorY())
                            && route.waypoints().size() == 4,
                    "mission patrol route is not assigned to a usable floor");
            for (BlockPos waypoint : route.waypoints()) {
                helper.assertTrue(helper.getLevel().isEmptyBlock(waypoint)
                                && helper.getLevel().isEmptyBlock(waypoint.above())
                                && helper.getLevel().getBlockState(waypoint.below()).blocksMotion(),
                        "mission decoration obstructed patrol waypoint " + waypoint);
            }
        }
        helper.assertTrue(helper.getLevel().isEmptyBlock(restored.target())
                        && helper.getLevel().isEmptyBlock(restored.target().above()),
                "mission decoration obstructed the objective cell");

        BlockPos compactOrigin = origin.offset(26, 0, 0);
        MissionBuildingPlanner.Site compactSite = syntheticSingleFloorSite(compactOrigin);
        prepareMissionSite(helper, compactSite);
        helper.assertTrue(compactSite.floorYs().size() == 1
                        && compactSite.stairs().isEmpty()
                        && MissionBuildingPlanner.preflight(helper.getLevel(), compactSite)
                        && MissionBuildingPlanner.install(helper.getLevel(), compactSite)
                                == MissionBuildingPlanner.InstallationResult.INSTALLED,
                "large single-floor building was not accepted as a safe mission fallback");

        MissionBuildingPlanner.StairRun lower = restored.stairs().getFirst();
        MissionBuildingPlanner.StairRun overlapping = new MissionBuildingPlanner.StairRun(
                lower.start().above(lower.rise()), lower.ascending(), lower.rise());
        MissionBuildingPlanner.Site legacyOverlappingSite = new MissionBuildingPlanner.Site(
                "test:overlapping-office",
                restored.district(),
                restored.bounds(),
                restored.floorYs(),
                restored.target(),
                restored.entrance(),
                List.of(lower, overlapping),
                restored.patrolRoutes(),
                restored.decorations(),
                restored.planSeed());
        helper.assertTrue(
                MissionBuildingPlanner.Site.load(legacyOverlappingSite.save()).isPresent()
                        && !MissionBuildingPlanner.preflight(
                                helper.getLevel(), legacyOverlappingSite),
                "mission planner did not deserialize but reject a legacy overlapping stair plan");

        MissionSiteData reservations = MissionSiteData.get(helper.getLevel());
        UUID firstContract = UUID.randomUUID();
        UUID nextContract = UUID.randomUUID();
        String reservationId = "test:" + firstContract;
        helper.assertTrue(reservations.reserve(reservationId, firstContract)
                        && reservations.isReservedByOther(reservationId, nextContract),
                "mission site reservation did not exclude a concurrent contract");
        reservations.releaseOwned(firstContract);
        helper.assertTrue(reservations.reserve(reservationId, nextContract),
                "completed mission site reservation was not reusable");
        reservations.releaseOwned(nextContract);

        BlockPos blockEntity = restored.decorations().getFirst().position();
        helper.getLevel().setBlock(blockEntity, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(!MissionBuildingPlanner.preflight(helper.getLevel(), restored)
                        && MissionBuildingPlanner.install(helper.getLevel(), restored)
                                == MissionBuildingPlanner.InstallationResult.UNSAFE,
                "mission building accepted a block entity on a planned edit cell");
        helper.getLevel().setBlock(blockEntity, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(MissionBuildingPlanner.install(helper.getLevel(), restored)
                        == MissionBuildingPlanner.InstallationResult.INSTALLED,
                "mission building could not recover after an edit cell became safe again");
        helper.getLevel().setBlock(
                restored.entrance().position(), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(!MissionBuildingPlanner.preflight(helper.getLevel(), restored)
                        && MissionBuildingPlanner.install(helper.getLevel(), restored)
                                == MissionBuildingPlanner.InstallationResult.UNSAFE,
                "mission building replaced a protected entrance block");
        helper.succeed();
    }

    public static void factionPatrolRoutes(GameTestHelper helper) {
        FactionEnemy enemy = FactionEntities.FACTION_ENEMY.get().create(
                helper.getLevel(), EntitySpawnReason.EVENT);
        helper.assertTrue(enemy != null, "could not create faction enemy for patrol test");

        List<BlockPos> mutable = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            mutable.add(helper.absolutePos(new BlockPos(index, 3, index % 4)));
        }
        BlockPos expectedFirst = mutable.getFirst();
        enemy.setPatrolRoute(mutable);
        mutable.set(0, expectedFirst.offset(100, 0, 0));
        helper.assertTrue(enemy.getPatrolRoute().size() == 32
                        && enemy.getPatrolRoute().getFirst().equals(expectedFirst),
                "faction patrol setter did not bound and defensively copy its route");

        String encoded = FactionEnemy.encodePatrolRoute(enemy.getPatrolRoute());
        helper.assertTrue(FactionEnemy.decodePatrolRoute(encoded).equals(enemy.getPatrolRoute()),
                "faction patrol route did not survive persistence encoding");
        helper.assertTrue(FactionEnemy.decodePatrolRoute(
                        "1,2,3;broken;4,5,6").equals(List.of(
                                new BlockPos(1, 2, 3), new BlockPos(4, 5, 6))),
                "faction patrol persistence did not isolate a malformed waypoint");
        enemy.setPatrolRoute(List.of());
        helper.assertTrue(enemy.getPatrolRoute().isEmpty(),
                "clearing a faction patrol route retained stale waypoints");
        helper.succeed();
    }

    private static void assertMissionPassage(
            GameTestHelper helper, BlockPos position, String description) {
        helper.assertTrue(helper.getLevel().isEmptyBlock(position)
                        && helper.getLevel().isEmptyBlock(position.above())
                        && helper.getLevel().getBlockState(position.below()).blocksMotion(),
                description + " is not a two-block-high passage at " + position);
    }

    private static boolean stairsHaveTestClearance(
            List<MissionBuildingPlanner.StairRun> stairs) {
        for (int first = 0; first < stairs.size(); first++) {
            List<BlockPos> firstEnvelope = stairTestEnvelope(stairs.get(first));
            for (int second = first + 1; second < stairs.size(); second++) {
                for (BlockPos firstCell : firstEnvelope) {
                    for (BlockPos secondCell : stairTestEnvelope(stairs.get(second))) {
                        int distance = Math.abs(firstCell.getX() - secondCell.getX())
                                + Math.abs(firstCell.getZ() - secondCell.getZ());
                        if (distance < 3) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static List<BlockPos> stairTestEnvelope(
            MissionBuildingPlanner.StairRun stair) {
        List<BlockPos> result = new ArrayList<>();
        Direction across = stair.ascending().getClockWise();
        for (int offset = -2; offset < stair.rise() + 2; offset++) {
            for (int lane = 0; lane < 2; lane++) {
                result.add(stair.start().relative(stair.ascending(), offset)
                        .relative(across, lane));
            }
        }
        return result;
    }

    private static MissionBuildingPlanner.Site syntheticMissionSite(BlockPos origin) {
        int lowerY = origin.getY();
        int middleY = lowerY + 5;
        int upperY = middleY + 5;
        BoundingBox bounds = new BoundingBox(
                origin.getX(), lowerY - 1, origin.getZ(),
                origin.getX() + 19, upperY + 3, origin.getZ() + 19);
        MissionBuildingPlanner.Entrance entrance = new MissionBuildingPlanner.Entrance(
                origin.offset(9, 0, 0), Direction.NORTH, 1, false);
        MissionBuildingPlanner.StairRun lowerStairs = new MissionBuildingPlanner.StairRun(
                origin.offset(4, 0, 5), Direction.EAST, middleY - lowerY);
        MissionBuildingPlanner.StairRun upperStairs = new MissionBuildingPlanner.StairRun(
                origin.offset(15, 5, 14), Direction.WEST, upperY - middleY);
        List<MissionBuildingPlanner.PatrolRoute> routes = List.of(
                new MissionBuildingPlanner.PatrolRoute(lowerY, List.of(
                        origin.offset(3, 0, 12), origin.offset(7, 0, 17),
                        origin.offset(14, 0, 17), origin.offset(17, 0, 8))),
                new MissionBuildingPlanner.PatrolRoute(middleY, List.of(
                        origin.offset(3, 5, 10), origin.offset(7, 5, 17),
                        origin.offset(12, 5, 10), origin.offset(18, 5, 4))),
                new MissionBuildingPlanner.PatrolRoute(upperY, List.of(
                        origin.offset(3, 10, 4), origin.offset(5, 10, 17),
                        origin.offset(15, 10, 5), origin.offset(17, 10, 17))));
        List<MissionBuildingPlanner.Decoration> decorations = List.of(
                new MissionBuildingPlanner.Decoration(
                        origin.offset(11, 0, 3),
                        MissionBuildingPlanner.DecorKind.RECEPTION_DESK,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(2, 0, 3),
                        MissionBuildingPlanner.DecorKind.PLANTER,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(15, 0, 3),
                        MissionBuildingPlanner.DecorKind.PLANTER,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(3, 5, 3),
                        MissionBuildingPlanner.DecorKind.CUBICLE_DESK,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(8, 5, 10),
                        MissionBuildingPlanner.DecorKind.COUCH,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(11, 5, 17),
                        MissionBuildingPlanner.DecorKind.CUBICLE_DESK,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(18, 5, 10),
                        MissionBuildingPlanner.DecorKind.PLANTER,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(3, 10, 8),
                        MissionBuildingPlanner.DecorKind.CUBICLE_DESK,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(7, 10, 4),
                        MissionBuildingPlanner.DecorKind.COUCH,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(13, 10, 9),
                        MissionBuildingPlanner.DecorKind.CUBICLE_DESK,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(17, 10, 11),
                        MissionBuildingPlanner.DecorKind.COUCH,
                        Direction.NORTH));
        return new MissionBuildingPlanner.Site(
                "test:office",
                District.A_CORP,
                bounds,
                List.of(lowerY, middleY, upperY),
                origin.offset(18, 10, 18),
                entrance,
                List.of(lowerStairs, upperStairs),
                routes,
                decorations,
                TEST_SEED);
    }

    private static MissionBuildingPlanner.Site syntheticSingleFloorSite(BlockPos origin) {
        int floorY = origin.getY();
        BoundingBox bounds = new BoundingBox(
                origin.getX(), floorY - 1, origin.getZ(),
                origin.getX() + 13, floorY + 3, origin.getZ() + 13);
        MissionBuildingPlanner.Entrance entrance = new MissionBuildingPlanner.Entrance(
                origin.offset(6, 0, 0), Direction.NORTH, 1, false);
        MissionBuildingPlanner.PatrolRoute route = new MissionBuildingPlanner.PatrolRoute(
                floorY,
                List.of(
                        origin.offset(2, 0, 4),
                        origin.offset(3, 0, 11),
                        origin.offset(10, 0, 10),
                        origin.offset(11, 0, 4)));
        List<MissionBuildingPlanner.Decoration> decorations = List.of(
                new MissionBuildingPlanner.Decoration(
                        origin.offset(5, 0, 3),
                        MissionBuildingPlanner.DecorKind.RECEPTION_DESK,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(2, 0, 8),
                        MissionBuildingPlanner.DecorKind.PLANTER,
                        Direction.NORTH),
                new MissionBuildingPlanner.Decoration(
                        origin.offset(10, 0, 8),
                        MissionBuildingPlanner.DecorKind.PLANTER,
                        Direction.NORTH));
        return new MissionBuildingPlanner.Site(
                "test:single-floor-office",
                District.A_CORP,
                bounds,
                List.of(floorY),
                origin.offset(11, 0, 11),
                entrance,
                List.of(),
                List.of(route),
                decorations,
                TEST_SEED ^ 0x51A61EF100L);
    }

    private static void prepareMissionSite(
            GameTestHelper helper, MissionBuildingPlanner.Site site) {
        BoundingBox bounds = site.bounds();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    helper.getLevel().setBlock(
                            new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS);
                }
            }
        }
        for (int floorY : site.floorYs()) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    helper.getLevel().setBlock(
                            new BlockPos(x, floorY - 1, z), Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS);
                }
            }
        }
        Direction across = site.entrance().outward().getClockWise();
        for (int lane = 0; lane < 2; lane++) {
            for (int y = 0; y < 3; y++) {
                helper.getLevel().setBlock(
                        site.entrance().position().relative(across, lane).above(y),
                        Blocks.STONE.defaultBlockState(),
                        Block.UPDATE_SKIP_ALL_SIDEEFFECTS | Block.UPDATE_CLIENTS);
            }
        }
    }

    private static boolean sameBounds(BoundingBox first, BoundingBox second) {
        return first.minX() == second.minX() && first.minY() == second.minY()
                && first.minZ() == second.minZ() && first.maxX() == second.maxX()
                && first.maxY() == second.maxY() && first.maxZ() == second.maxZ();
    }

    private static MissionService.ActiveMission testMission(
            MissionCatalog.MissionDefinition definition,
            BlockPos target,
            int reward,
            String cargoItem) {
        return new MissionService.ActiveMission(
                definition.id(), definition.type(), definition.title(), definition.briefing(),
                definition.objectiveText(), District.A_CORP, target, reward, "", cargoItem,
                definition.cargoCount(), 1L);
    }

    private static net.minecraft.world.entity.Entity entityByUuid(
            ServerLevel level, String encodedUuid) {
        UUID entityId = UUID.fromString(encodedUuid);
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (!entity.isRemoved() && entity.getUUID().equals(entityId)) {
                return entity;
            }
        }
        return null;
    }

    private static int inventoryCount(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    public static void specialDistrictInfrastructure(GameTestHelper helper) {
        NeonCityGenerator.reset();
        assertSpecialRoad(helper, District.S_CORP, NeonCityGenerator.RoadClass.FARM);
        assertSpecialRoad(helper, District.U_CORP, NeonCityGenerator.RoadClass.HARBOR);
        assertSpecialRoad(helper, District.V_CORP, NeonCityGenerator.RoadClass.CANAL);
        assertSpecialRoad(helper, District.X_CORP, NeonCityGenerator.RoadClass.EXTRACTION_SITE);

        helper.assertTrue(District.S_CORP.architecture() == District.Architecture.JOSEON,
                "S Corp lost its Joseon agricultural culture");
        helper.assertTrue(District.U_CORP.architecture() == District.Architecture.HARBOR,
                "U Corp lost its port culture");
        helper.assertTrue(District.V_CORP.architecture() == District.Architecture.ALPINE_CANAL,
                "V Corp lost its Swiss canal culture");
        helper.assertTrue(District.X_CORP.architecture() == District.Architecture.HANOI_INDUSTRIAL,
                "X Corp lost its extraction culture");
        helper.assertTrue(District.Y_CORP.architecture() == District.Architecture.WINTER_MONUMENTAL
                        && District.Y_CORP.flavor().toLowerCase().contains("winter"),
                "Y Corp lost the metadata that drives its snow treatment");

        MegacityLayout.Node winter = NeonCityGenerator.layout().node(District.Y_CORP);
        NeonCityGenerator.UrbanSample center = NeonCityGenerator.sample(winter.x(), winter.z());
        boolean hasCivicPlaza = false;
        for (int angle = 0; angle < 16; angle++) {
            double radians = angle * Math.PI * 2.0 / 16.0;
            if (NeonCityGenerator.roadAt(
                            winter.x() + (int) Math.round(Math.cos(radians) * 24.0),
                            winter.z() + (int) Math.round(Math.sin(radians) * 24.0))
                    == NeonCityGenerator.RoadClass.CENTRAL_PLAZA) {
                hasCivicPlaza = true;
                break;
            }
        }
        helper.assertTrue(center.district() == District.Y_CORP
                        && center.zone() == MegacityLayout.Zone.NEST
                        && isTravelInfrastructure(center.roadClass())
                        && hasCivicPlaza,
                "Y Corp winter capital is not generated as an urban center");
        helper.succeed();
    }

    public static void uCorpPortGeneration(GameTestHelper helper) {
        UCorpPortGeneration.clearCache();
        UCorpPortGeneration.Plan detailedPlan = null;
        for (long seedOffset : ZONE_SEED_OFFSETS) {
            MegacityLayout layout = MegacityLayout.create(TEST_SEED + seedOffset);
            MegacityLayout.Node uCorp = layout.node(District.U_CORP);
            long uDistance = squaredDistanceFromOrigin(uCorp);
            helper.assertTrue(layout.nodes().getLast().district() == District.U_CORP,
                    "U Corp is not reserved for the outermost layout slot");
            for (MegacityLayout.Node other : layout.nodes()) {
                if (other.district() == District.U_CORP) continue;
                helper.assertTrue(uDistance > squaredDistanceFromOrigin(other),
                        "U Corp is not the outermost district at seed offset " + seedOffset
                                + ": " + other.district() + " is at least as distant");
            }

            UCorpPortGeneration.Plan plan = UCorpPortGeneration.plan(layout);
            UCorpPortGeneration.clearCache();
            UCorpPortGeneration.Plan recreated = UCorpPortGeneration.plan(
                    MegacityLayout.createFromLayoutSeed(layout.seed()));
            helper.assertTrue(plan.equals(recreated),
                    "U Corp marine plan changed when recreated from its public layout seed");
            helper.assertTrue(plan.portships().size() >= UCorpPortGeneration.MIN_PORTSHIPS
                            && plan.portships().size() <= UCorpPortGeneration.MAX_PORTSHIPS,
                    "U Corp must generate two or three Portships, found "
                            + plan.portships().size());
            helper.assertTrue(Math.abs(plan.forwardX()) + Math.abs(plan.forwardZ()) == 1
                            && Math.abs(plan.rightX()) + Math.abs(plan.rightZ()) == 1
                            && plan.forwardX() * plan.rightX()
                                    + plan.forwardZ() * plan.rightZ() == 0,
                    "U Corp coast frame is not cardinal and orthogonal");

            for (int index = 0; index < plan.portships().size(); index++) {
                UCorpPortGeneration.Portship ship = plan.portships().get(index);
                helper.assertTrue(ship.maxX() - ship.minX() + 1
                                        == UCorpPortGeneration.PORTSHIP_SIZE
                                && ship.maxZ() - ship.minZ() + 1
                                        == UCorpPortGeneration.PORTSHIP_SIZE,
                        "Portship " + index + " does not occupy a 75x75 bounding square");
                helper.assertTrue(plan.featureAt(ship.centerX(), ship.centerZ())
                                        == UCorpPortGeneration.Feature.PORTSHIP
                                && plan.isOceanBiomeAt(ship.centerX(), ship.centerZ()),
                        "Portship " + index + " is not centered in U Corp ocean");
                for (int otherIndex = index + 1;
                     otherIndex < plan.portships().size(); otherIndex++) {
                    helper.assertTrue(!portshipsOverlap(
                                    ship, plan.portships().get(otherIndex)),
                            "U Corp Portships overlap at seed offset " + seedOffset);
                }
            }
            assertOceanContinuity(helper, plan, seedOffset);
            if (seedOffset == ZONE_SEED_OFFSETS[0]) {
                detailedPlan = plan;
            }
        }

        helper.assertTrue(detailedPlan != null, "U Corp detailed marine plan was not selected");
        assertPortshipFootprints(helper, detailedPlan);
        assertPortArchitecture(helper, detailedPlan);

        NeonCityGenerator.reset();
        UCorpPortGeneration.Plan runtimePlan = UCorpPortGeneration.plan(
                NeonCityGenerator.layout());
        UCorpPortGeneration.Portship runtimeShip = runtimePlan.portships().getFirst();
        NeonCityGenerator.UrbanSample runtimeSample = NeonCityGenerator.sample(
                runtimeShip.centerX(), runtimeShip.centerZ());
        MegacityLayout.Location effectiveLocation = NeonCityGenerator.effectiveLocation(
                runtimeSample);
        helper.assertTrue(runtimeSample.district() == District.U_CORP
                        && runtimeSample.roadClass() == NeonCityGenerator.RoadClass.PORTSHIP
                        && effectiveLocation.district() == District.U_CORP
                        && effectiveLocation.insideCity(),
                "offshore Portships are not exposed to U Corp player services");
        UCorpPortGeneration.clearCache();
        helper.succeed();
    }

    private static void assertOceanContinuity(
            GameTestHelper helper,
            UCorpPortGeneration.Plan plan,
            long seedOffset) {
        EnumSet<UCorpPortGeneration.Feature> features =
                EnumSet.noneOf(UCorpPortGeneration.Feature.class);
        features.add(plan.featureAt(
                plan.worldX(plan.portStart() - 1, 0),
                plan.worldZ(plan.portStart() - 1, 0)));
        features.add(plan.featureAt(
                plan.worldX(plan.portStart(), 0),
                plan.worldZ(plan.portStart(), 0)));

        int harborForward = plan.portStart() + 24;
        for (int lateral = -plan.oceanHalfWidth();
             lateral <= plan.oceanHalfWidth(); lateral++) {
            features.add(plan.featureAt(
                    plan.worldX(harborForward, lateral),
                    plan.worldZ(harborForward, lateral)));
        }

        int lateralStep = Math.max(8, plan.oceanHalfWidth() / 24);
        for (int lateral = -plan.oceanHalfWidth();
             lateral <= plan.oceanHalfWidth(); lateral += lateralStep) {
            int shoreline = plan.shorelineAt(lateral);
            UCorpPortGeneration.Feature portEdge = plan.featureAt(
                    plan.worldX(shoreline - 1, lateral),
                    plan.worldZ(shoreline - 1, lateral));
            helper.assertTrue(portEdge == UCorpPortGeneration.Feature.CONTAINER_PORT
                            || portEdge == UCorpPortGeneration.Feature.HARBOR_WATER,
                    "U Corp ocean is detached from its port at lateral " + lateral
                            + " and seed offset " + seedOffset);
            for (int forward = shoreline; forward <= plan.oceanEnd(); forward++) {
                int worldX = plan.worldX(forward, lateral);
                int worldZ = plan.worldZ(forward, lateral);
                helper.assertTrue(plan.isOceanBiomeAt(worldX, worldZ),
                        "U Corp ocean has a dry gap at " + worldX + "," + worldZ
                                + " and seed offset " + seedOffset);
                features.add(plan.featureAt(worldX, worldZ));
            }
        }
        for (int lateral = -plan.oceanHalfWidth();
             lateral < plan.oceanHalfWidth(); lateral++) {
            helper.assertTrue(Math.abs(
                            plan.shorelineAt(lateral + 1) - plan.shorelineAt(lateral)) <= 2,
                    "U Corp shoreline tears between adjacent columns at lateral " + lateral);
        }
        for (UCorpPortGeneration.Portship ship : plan.portships()) {
            features.add(plan.featureAt(ship.centerX(), ship.centerZ()));
        }
        helper.assertTrue(features.equals(EnumSet.allOf(UCorpPortGeneration.Feature.class)),
                "U Corp marine plan omitted a required feature: " + features);
    }

    private static void assertPortshipFootprints(
            GameTestHelper helper, UCorpPortGeneration.Plan plan) {
        for (UCorpPortGeneration.Portship ship : plan.portships()) {
            for (int localForward = -UCorpPortGeneration.PORTSHIP_HALF;
                 localForward <= UCorpPortGeneration.PORTSHIP_HALF; localForward++) {
                for (int localLateral = -UCorpPortGeneration.PORTSHIP_HALF;
                     localLateral <= UCorpPortGeneration.PORTSHIP_HALF; localLateral++) {
                    if (!UCorpPortGeneration.portshipContains(localForward, localLateral)) {
                        continue;
                    }
                    int worldX = ship.centerX()
                            + localForward * plan.forwardX()
                            + localLateral * plan.rightX();
                    int worldZ = ship.centerZ()
                            + localForward * plan.forwardZ()
                            + localLateral * plan.rightZ();
                    helper.assertTrue(plan.portshipAt(worldX, worldZ) == ship
                                    && plan.isOceanBiomeAt(worldX, worldZ),
                            "Portship footprint escaped its deterministic ocean plan at "
                                    + worldX + "," + worldZ);
                }
            }
        }
    }

    private static void assertPortArchitecture(
            GameTestHelper helper, UCorpPortGeneration.Plan plan) {
        Set<Block> referenceColors = Set.of(
                Blocks.CONCRETE.pick(DyeColor.BLUE),
                Blocks.CONCRETE.pick(DyeColor.LIGHT_BLUE),
                Blocks.CONCRETE.pick(DyeColor.CYAN),
                Blocks.CONCRETE.pick(DyeColor.GREEN),
                Blocks.CONCRETE.pick(DyeColor.ORANGE),
                Blocks.CONCRETE.pick(DyeColor.YELLOW),
                Blocks.CONCRETE.pick(DyeColor.RED),
                Blocks.CONCRETE.pick(DyeColor.GRAY));

        Set<Block> portBlocks = samplePortContainers(plan);
        long portColors = referenceColors.stream().filter(portBlocks::contains).count();
        helper.assertTrue(portColors >= 4
                        && portBlocks.contains(Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY)),
                "U Corp terminal lacks varied reference containers: colors=" + portColors
                        + ", blocks=" + portBlocks);

        Set<Block> craneBlocks = samplePortCranes(plan);
        helper.assertTrue(craneBlocks.contains(Blocks.CONCRETE.pick(DyeColor.YELLOW))
                        && craneBlocks.contains(Blocks.CONCRETE.pick(DyeColor.ORANGE))
                        && craneBlocks.contains(Blocks.IRON_BLOCK),
                "U Corp port cranes lack their mast, boom, or steel frame: " + craneBlocks);

        UCorpPortGeneration.Portship capital = plan.portships().getFirst();
        Set<Block> towerBlocks = new HashSet<>();
        for (int forward = -6; forward <= 6; forward++) {
            for (int lateral = -6; lateral <= 6; lateral++) {
                for (int y = NeonCityGenerator.WATER_Y + 3;
                     y <= NeonCityGenerator.WATER_Y + 38; y++) {
                    addOverlayBlock(towerBlocks, plan, capital, forward, lateral, y);
                }
            }
        }
        helper.assertTrue(towerBlocks.contains(Blocks.CONCRETE.pick(DyeColor.WHITE))
                        && towerBlocks.contains(Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE))
                        && towerBlocks.contains(Blocks.SEA_LANTERN),
                "lead Portship lost its central arcology tower palette: " + towerBlocks);

        Set<Block> shipBlocks = new HashSet<>();
        for (UCorpPortGeneration.Portship ship : plan.portships()) {
            for (int forward = -32; forward <= 32; forward++) {
                for (int lateral = -32; lateral <= 32; lateral++) {
                    for (int y = NeonCityGenerator.WATER_Y + 3;
                         y <= NeonCityGenerator.WATER_Y + 32; y++) {
                        addOverlayBlock(shipBlocks, plan, ship, forward, lateral, y);
                    }
                }
            }
        }
        long shipColors = referenceColors.stream().filter(shipBlocks::contains).count();
        helper.assertTrue(shipColors >= 5
                        && shipBlocks.contains(Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY))
                        && shipBlocks.contains(Blocks.TINTED_GLASS),
                "Portships lack haphazard multicolor container housing: colors="
                        + shipColors + ", blocks=" + shipBlocks);
    }

    private static Set<Block> samplePortContainers(UCorpPortGeneration.Plan plan) {
        Set<Block> result = new HashSet<>();
        int rows = Math.min(28, Math.ceilDiv(plan.shoreline() - plan.portStart() + 20, 10));
        int aisles = Math.ceilDiv(plan.oceanHalfWidth() * 2 + 1, 32);
        int[] widths = {0, 1, 3, 6};
        int[] lengths = {0, 1, 5, 13, 27};
        for (int row = 0; row < rows; row++) {
            for (int aisle = 0; aisle < aisles; aisle++) {
                for (int localWidth : widths) {
                    int forward = plan.portStart() + row * 10 + localWidth;
                    for (int localLength : lengths) {
                        int lateral = -plan.oceanHalfWidth() + aisle * 32 + localLength;
                        if (lateral > plan.oceanHalfWidth()
                                || forward >= plan.shorelineAt(lateral)) {
                            continue;
                        }
                        int worldX = plan.worldX(forward, lateral);
                        int worldZ = plan.worldZ(forward, lateral);
                        for (int y = NeonCityGenerator.CITY_GROUND_Y + 1;
                             y <= NeonCityGenerator.CITY_GROUND_Y + 21; y++) {
                            BlockState state = UCorpPortGeneration.overlayAt(
                                    plan, worldX, y, worldZ);
                            if (state != null) result.add(state.getBlock());
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Set<Block> samplePortCranes(UCorpPortGeneration.Plan plan) {
        Set<Block> result = new HashSet<>();
        int craneForward = UCorpPortGeneration.portCraneForward(plan);
        for (int index = 0; index < 3; index++) {
            int anchor = UCorpPortGeneration.portCraneLateral(plan, index);
            for (int relativeForward = -10; relativeForward <= 34; relativeForward++) {
                for (int relativeLateral = -1; relativeLateral <= 1; relativeLateral++) {
                    int worldX = plan.worldX(
                            craneForward + relativeForward,
                            anchor + relativeLateral);
                    int worldZ = plan.worldZ(
                            craneForward + relativeForward,
                            anchor + relativeLateral);
                    for (int y = NeonCityGenerator.CITY_GROUND_Y + 1;
                         y <= NeonCityGenerator.CITY_GROUND_Y + 31; y++) {
                        BlockState state = UCorpPortGeneration.overlayAt(
                                plan, worldX, y, worldZ);
                        if (state != null) result.add(state.getBlock());
                    }
                }
            }
        }
        return result;
    }

    private static void addOverlayBlock(
            Set<Block> result,
            UCorpPortGeneration.Plan plan,
            UCorpPortGeneration.Portship ship,
            int forward,
            int lateral,
            int y) {
        int worldX = ship.centerX()
                + forward * plan.forwardX() + lateral * plan.rightX();
        int worldZ = ship.centerZ()
                + forward * plan.forwardZ() + lateral * plan.rightZ();
        BlockState state = UCorpPortGeneration.overlayAt(plan, worldX, y, worldZ);
        if (state != null) result.add(state.getBlock());
    }

    private static boolean portshipsOverlap(
            UCorpPortGeneration.Portship first,
            UCorpPortGeneration.Portship second) {
        return first.minX() <= second.maxX() && first.maxX() >= second.minX()
                && first.minZ() <= second.maxZ() && first.maxZ() >= second.minZ();
    }

    private static long squaredDistanceFromOrigin(MegacityLayout.Node node) {
        return (long) node.x() * node.x() + (long) node.z() * node.z();
    }

    public static void districtEnvironment(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        MegacityLayout.Node farmNode = layout.node(District.S_CORP);
        int farmSamples = 0;
        int backstreetSamples = 0;
        int backstreetFarms = 0;
        int backstreetInfrastructure = 0;
        int backstreetSlop = 0;
        int irrigated = 0;
        int planted = 0;
        int minFarmY = Integer.MAX_VALUE;
        int maxFarmY = Integer.MIN_VALUE;
        for (double radius = 0.46; radius <= 1.06; radius += 0.018) {
            for (int angle = 0; angle < 192; angle++) {
                int[] point = ellipsePoint(farmNode, radius, angle, 192);
                NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(
                        point[0], point[1]);
                if (sample.district() != District.S_CORP) {
                    continue;
                }
                if (sample.zone() == MegacityLayout.Zone.BACKSTREETS) {
                    backstreetSamples++;
                    switch (sample.roadClass()) {
                        case FARM -> backstreetFarms++;
                        case INTERDISTRICT_ROAD,
                                BRIDGE,
                                ELEVATED_RAIL,
                                HIGHWAY_BUFFER -> backstreetInfrastructure++;
                        default -> backstreetSlop++;
                    }
                }
                if (sample.roadClass() != NeonCityGenerator.RoadClass.FARM) {
                    continue;
                }
                farmSamples++;
                minFarmY = Math.min(minFarmY, sample.groundY());
                maxFarmY = Math.max(maxFarmY, sample.groundY());
                var surface = DistrictWorldFeatures.farmSurface(sample);
                if (surface.is(Blocks.WATER)) {
                    irrigated++;
                } else {
                    planted++;
                    helper.assertTrue(surface.is(Blocks.FARMLAND)
                                    && surface.getValue(FarmlandBlock.MOISTURE)
                                            == FarmlandBlock.MAX_MOISTURE,
                            "S Corp produced dry or non-farmland crop soil");
                }
            }
        }
        helper.assertTrue(farmSamples >= 100 && irrigated > 0 && planted > irrigated,
                "S Corp outskirts lack broad irrigated wheat coverage");
        helper.assertTrue(backstreetSamples >= 500
                        && backstreetFarms * 4 >= backstreetSamples * 3
                        && backstreetInfrastructure > 0
                        && backstreetSlop == 0,
                "S Corp outskirts must be fields separated only by interdistrict infrastructure: "
                        + "samples=" + backstreetSamples
                        + ", farms=" + backstreetFarms
                        + ", infrastructure=" + backstreetInfrastructure
                        + ", slop=" + backstreetSlop);
        helper.assertTrue(minFarmY == NeonCityGenerator.CITY_GROUND_Y
                        && maxFarmY == NeonCityGenerator.CITY_GROUND_Y,
                "S Corp outskirts must remain flat wheat fields");
        helper.assertTrue(DistrictWorldFeatures.matureWheat().getValue(CropBlock.AGE)
                        == CropBlock.MAX_AGE,
                "S Corp wheat is not generated fully grown");

        Set<Integer> snowDepths = new HashSet<>();
        int snowless = 0;
        int coherentNeighbours = 0;
        int snowPairs = 0;
        for (int z = -64; z < 64; z++) {
            for (int x = -64; x < 64; x++) {
                int layers = DistrictWorldFeatures.snowLayers(TEST_SEED, x, z);
                snowDepths.add(layers);
                if (layers == 0) snowless++;
                if (layers == DistrictWorldFeatures.snowLayers(TEST_SEED, x + 1, z)) {
                    coherentNeighbours++;
                }
                snowPairs++;
            }
        }
        helper.assertTrue(snowDepths.size() >= 6
                        && snowDepths.stream().mapToInt(Integer::intValue).max().orElse(0) >= 6
                        && snowless > 0,
                "Y Corp snow lacks varied multi-layer drifts: " + snowDepths);
        helper.assertTrue(coherentNeighbours > snowPairs * 3 / 4,
                "Y Corp snow still changes like a checkerboard instead of coherent piles");
        helper.assertTrue(DistrictAtmosphere.winterWeather(0)
                        == DistrictAtmosphere.WinterWeather.GENTLE
                        && DistrictAtmosphere.winterWeather(
                                DistrictAtmosphere.GENTLE_SNOW_TICKS)
                                == DistrictAtmosphere.WinterWeather.SNOWSTORM
                        && DistrictAtmosphere.winterWeather(
                                DistrictAtmosphere.WINTER_CYCLE_TICKS)
                                == DistrictAtmosphere.WinterWeather.GENTLE,
                "Y Corp weather does not cycle between gentle snow and storms");
        helper.assertTrue(
                DistrictAtmosphere.WinterWeather.SNOWSTORM.particleCount()
                        > DistrictAtmosphere.WinterWeather.GENTLE.particleCount() * 3,
                "Y Corp snowstorms are not visibly stronger than gentle snowfall");

        int minHill = Integer.MAX_VALUE;
        int maxHill = Integer.MIN_VALUE;
        int maxAdjacentStep = 0;
        Set<String> hillSurfaces = new HashSet<>();
        for (int z = -256; z < 256; z += 2) {
            for (int x = -256; x < 256; x += 2) {
                int height = DistrictWorldFeatures.borderHillHeight(TEST_SEED, x, z);
                minHill = Math.min(minHill, height);
                maxHill = Math.max(maxHill, height);
                maxAdjacentStep = Math.max(maxAdjacentStep,
                        Math.abs(height
                                - DistrictWorldFeatures.borderHillHeight(TEST_SEED, x + 1, z)));
                hillSurfaces.add(DistrictWorldFeatures.borderHillSurface(TEST_SEED, x, z)
                        .getBlock().toString());
            }
        }
        helper.assertTrue(maxHill - minHill >= 10 && maxAdjacentStep <= 2,
                "district border cliffs are flat or form abrupt walls");
        helper.assertTrue(hillSurfaces.size() >= 3,
                "district border cliffs lack coherent grass, soil, and rock patches");

        int forestTreeAnchors = 0;
        int cliffTreeAnchors = 0;
        int villageCandidates = 0;
        for (int z = -128; z < 128; z++) {
            for (int x = -128; x < 128; x++) {
                if (ParkTreeLibrary.isForestTreeAnchor(TEST_SEED, x, z)) forestTreeAnchors++;
                if (ParkTreeLibrary.isCliffTreeAnchor(TEST_SEED, x, z)) cliffTreeAnchors++;
            }
        }
        for (int chunkZ = -32; chunkZ <= 32; chunkZ++) {
            for (int chunkX = -32; chunkX <= 32; chunkX++) {
                if (DistrictWorldFeatures.isHillVillageCandidate(
                        TEST_SEED, chunkX, chunkZ)) {
                    villageCandidates++;
                }
            }
        }
        helper.assertTrue(forestTreeAnchors >= 500
                        && forestTreeAnchors >= cliffTreeAnchors * 4,
                "forested borders are not materially denser than cliff trees: forest="
                        + forestTreeAnchors + ", cliff=" + cliffTreeAnchors);
        helper.assertTrue(cliffTreeAnchors >= 50,
                "cliffs lost their sparse deterministic Exsilit silhouettes");
        helper.assertTrue(villageCandidates >= 100 && villageCandidates <= 260,
                "forest village frequency is not sparse and bounded: " + villageCandidates);
        helper.assertTrue(BorderVillageLibrary.templates().size() >= 12,
                "forested borders lack varied vanilla village structures");
        for (BorderVillageLibrary.VillageAsset asset : BorderVillageLibrary.templates()) {
            var villageTemplate = helper.getLevel().getStructureManager()
                    .get(asset.templateId()).orElse(null);
            helper.assertTrue(villageTemplate != null
                            && villageTemplate.getSize().getX() == asset.sizeX()
                            && villageTemplate.getSize().getY() == asset.sizeY()
                            && villageTemplate.getSize().getZ() == asset.sizeZ(),
                    "missing or mis-sized forest village template " + asset.templateId());
        }
        BorderSample walled = findBorderSample(
                layout,
                MegacityLayout.Zone.BORDER_WALLED,
                NeonCityGenerator.RoadClass.BORDER_WALLED);
        BorderSample forest = findBorderSample(
                layout,
                MegacityLayout.Zone.BORDER_FOREST,
                NeonCityGenerator.RoadClass.BORDER_FOREST);
        BorderSample forestTrail = findBorderSample(
                layout,
                MegacityLayout.Zone.BORDER_FOREST,
                NeonCityGenerator.RoadClass.LOCAL_STREET);
        BorderSample cliff = findBorderSample(
                layout,
                MegacityLayout.Zone.BORDER_CLIFF,
                NeonCityGenerator.RoadClass.BORDER_CLIFF);
        helper.assertTrue(walled != null && forest != null && forestTrail != null && cliff != null,
                "default layout has no usable representative for every border type");
        helper.assertTrue(walled.sample().groundY() == NeonCityGenerator.CITY_GROUND_Y
                        && forest.sample().groundY() == NeonCityGenerator.CITY_GROUND_Y
                        && forestTrail.sample().groundY() == NeonCityGenerator.CITY_GROUND_Y,
                "walled/forested borders must remain at city-road grade");
        helper.assertTrue(layout.boundaryFrame(
                                forestTrail.sample().location(), forestTrail.x(), forestTrail.z())
                                .gapRatio() <= 0.10,
                "forested border trail did not preserve the district bisector");
        helper.assertTrue(cliff.sample().groundY() >= NeonCityGenerator.CITY_GROUND_Y + 4,
                "cliff border lost its raised terrain");
        CliffInfrastructureLibrary.SolarAsset solarAsset =
                CliffInfrastructureLibrary.solarPanel();
        var solarTemplate = helper.getLevel().getStructureManager()
                .get(solarAsset.templateId()).orElse(null);
        helper.assertTrue(solarTemplate != null
                        && solarTemplate.getSize().getX() == solarAsset.sizeX()
                        && solarTemplate.getSize().getY() == solarAsset.sizeY()
                        && solarTemplate.getSize().getZ() == solarAsset.sizeZ()
                        && solarAsset.blockCount() == 367
                        && solarAsset.sha256().length() == 64,
                "supplied cliff solar structure is missing or disagrees with its audit");
        Optional<CliffInfrastructureLibrary.SolarCandidate> solarCandidate =
                findCliffSolarCandidate(layout, cliff);
        helper.assertTrue(solarCandidate.isPresent()
                        && CliffInfrastructureLibrary.isEligibleFootprint(
                                layout, solarCandidate.orElseThrow())
                        && solarCandidate.orElseThrow().baseY() + solarAsset.sizeY() - 1
                                <= NeonCityGenerator.MAX_BUILD_Y,
                "default cliff border exposes no build-safe deterministic solar site");
        assertWalledBorderTopology(helper, layout);
        assertLiveWalledBorderPlacement(helper, walled);

        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        var farmer = DistrictWorldFeatures.createFarmWorker(
                helper.getLevel(), new BlockPos(origin.getX(), 80, origin.getZ()));
        helper.assertTrue(farmer != null
                        && farmer.getVillagerData().profession().is(VillagerProfession.FARMER)
                        && DistrictWorldFeatures.isSCorpFarmer(farmer)
                        && farmer.isPersistenceRequired()
                        && farmer.getInventory().hasAnyMatching(
                                stack -> stack.is(net.minecraft.world.item.Items.WHEAT_SEEDS)),
                "S Corp farm worker lacks farmer AI, persistence, or replanting seed");
        helper.succeed();
    }

    private static Optional<CliffInfrastructureLibrary.SolarCandidate>
            findCliffSolarCandidate(MegacityLayout layout, BorderSample cliff) {
        MegacityLayout.BoundaryFrame frame = layout.boundaryFrame(
                cliff.sample().location(), cliff.x(), cliff.z());
        int cellSize = CliffInfrastructureLibrary.siteCellSize();
        Set<Long> checked = new HashSet<>();
        for (int along = -1_536; along <= 1_536; along += cellSize / 2) {
            int projectedX = (int) Math.round(cliff.x() + frame.tangentX() * along);
            int projectedZ = (int) Math.round(cliff.z() + frame.tangentZ() * along);
            int centerCellX = Math.floorDiv(projectedX, cellSize);
            int centerCellZ = Math.floorDiv(projectedZ, cellSize);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cellX = centerCellX + dx;
                    int cellZ = centerCellZ + dz;
                    if (!checked.add(ChunkPos.pack(cellX, cellZ))) continue;
                    Optional<CliffInfrastructureLibrary.SolarCandidate> candidate =
                            CliffInfrastructureLibrary.candidateForCell(
                                    layout, cellX, cellZ);
                    if (candidate.isPresent()) return candidate;
                }
            }
        }
        return Optional.empty();
    }

    private static void assertWalledBorderTopology(
            GameTestHelper helper, MegacityLayout layout) {
        int promenade = 0;
        int gates = 0;
        int wallNegative = 0;
        int wallPositive = 0;
        int slums = 0;
        int multiStory = 0;
        int reservedLots = 0;
        int serviceAlleys = 0;
        for (int along = 0; along < WalledBorderLibrary.LOT_PERIOD * 2; along++) {
            for (int side : new int[]{-1, 1}) {
                for (int ratioStep = 0; ratioStep <= 100; ratioStep++) {
                    double ratio = ratioStep / 100.0;
                    MegacityLayout.BoundaryFrame frame = new MegacityLayout.BoundaryFrame(
                            District.A_CORP,
                            District.B_CORP,
                            side * ratio * MegacityLayout.BORDER_GAP_LIMIT,
                            ratio,
                            1.0,
                            0.0,
                            0.0,
                            1.0,
                            along);
                    WalledBorderLibrary.ColumnPlan plan = WalledBorderLibrary.planFrame(
                            layout.seed(), frame);
                    switch (plan.role()) {
                        case PROMENADE -> promenade++;
                        case GATE_ALLEY -> gates++;
                        case WALL -> {
                            helper.assertTrue(plan.wallHeight() >= 5 && plan.wallHeight() <= 7,
                                    "walled border escaped its 5-7 block height contract");
                            if (side < 0) wallNegative++;
                            else wallPositive++;
                        }
                        case SLUM -> {
                            slums++;
                            helper.assertTrue(plan.module().stories() >= 2
                                            && plan.module().stories() <= 4
                                            && plan.module().totalHeight() <= 17,
                                    "walled slum module is not bounded multi-level housing");
                            if (plan.module().containsStory(ratio, 1)) multiStory++;
                        }
                        case RESERVED_LOT -> reservedLots++;
                        case SERVICE_ALLEY -> serviceAlleys++;
                        default -> {
                        }
                    }
                    if (ratio == 0.0) {
                        helper.assertTrue(plan.traversableAtGround(),
                                "walled border blocks its central promenade at " + along);
                    }
                }
            }
        }
        helper.assertTrue(promenade > 0 && gates > 0 && serviceAlleys > 0,
                "walled border lacks promenade, transverse gates, or service alleys");
        helper.assertTrue(wallNegative > 0 && wallPositive > 0,
                "walled border does not build a wall on both district sides");
        helper.assertTrue(slums > 0 && multiStory * 3 >= slums * 2,
                "walled border slums are absent or insufficiently multi-layered");
        helper.assertTrue(reservedLots > 0,
                "walled border reserves no empty shop/mission lots");
    }

    private static void assertLiveWalledBorderPlacement(
            GameTestHelper helper, BorderSample representative) {
        int centerChunkX = Math.floorDiv(representative.x(), 16);
        int centerChunkZ = Math.floorDiv(representative.z(), 16);
        int wallColumns = 0;
        int slumColumns = 0;
        int traversableColumns = 0;
        for (int chunkZ = centerChunkZ - 3; chunkZ <= centerChunkZ + 3; chunkZ++) {
            for (int chunkX = centerChunkX - 3; chunkX <= centerChunkX + 3; chunkX++) {
                helper.getLevel().getChunk(chunkX, chunkZ);
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                NeonCityGenerator.UrbanSample[][] samples = NeonCityGenerator.sampleChunk(
                        chunk.getMinBlockX(), chunk.getMinBlockZ());
                WalledBorderLibrary.PlacementMetrics metrics =
                        WalledBorderLibrary.decorateChunk(helper.getLevel(), chunk, samples);
                wallColumns += metrics.wallColumns();
                slumColumns += metrics.slumColumns();
                traversableColumns += metrics.promenadeColumns()
                        + metrics.gateColumns() + metrics.reservedLotColumns();

                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        NeonCityGenerator.UrbanSample sample = samples[localZ + 1][localX + 1];
                        if (sample.roadClass() != NeonCityGenerator.RoadClass.BORDER_WALLED) {
                            continue;
                        }
                        int worldX = chunk.getMinBlockX() + localX;
                        int worldZ = chunk.getMinBlockZ() + localZ;
                        WalledBorderLibrary.ColumnPlan plan = WalledBorderLibrary.planAt(
                                NeonCityGenerator.layout(), sample.location(), worldX, worldZ);
                        if (plan.role() == WalledBorderLibrary.ColumnRole.WALL) {
                            for (int height = 1; height <= plan.wallHeight(); height++) {
                                helper.assertTrue(!helper.getLevel().isEmptyBlock(
                                                new BlockPos(worldX, sample.groundY() + height, worldZ)),
                                        "live walled border has a broken wall column");
                            }
                            helper.assertTrue(helper.getLevel().isEmptyBlock(new BlockPos(
                                            worldX, sample.groundY() + plan.wallHeight() + 1, worldZ)),
                                    "live walled border exceeded its planned 5-7 block wall height");
                        } else if (plan.role() == WalledBorderLibrary.ColumnRole.SLUM) {
                            for (int story = 0; story < plan.module().stories(); story++) {
                                if (!plan.module().containsStory(plan.frame().gapRatio(), story)) {
                                    continue;
                                }
                                int capY = sample.groundY()
                                        + (story + 1) * WalledBorderLibrary.STORY_HEIGHT;
                                helper.assertTrue(!helper.getLevel().isEmptyBlock(
                                                new BlockPos(worldX, capY, worldZ)),
                                        "live stepped slum story is missing its floor/terrace cap");
                            }
                        } else if (plan.role() == WalledBorderLibrary.ColumnRole.PROMENADE
                                || plan.role() == WalledBorderLibrary.ColumnRole.GATE_ALLEY
                                || plan.role() == WalledBorderLibrary.ColumnRole.RESERVED_LOT) {
                            for (int height = 1; height <= 4; height++) {
                                helper.assertTrue(helper.getLevel().isEmptyBlock(
                                                new BlockPos(
                                                        worldX,
                                                        sample.groundY() + height,
                                                        worldZ)),
                                        "live walled border blocks public headroom");
                            }
                        }
                    }
                }
            }
        }
        helper.assertTrue(wallColumns > 0 && slumColumns > 0 && traversableColumns > 0,
                "live walled-border window did not contain walls, slums, and public space");
    }

    public static void arnisPatchSelection(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        int expectedAtlasCount = District.values().length * ARNIS_ZONES.size();
        int expectedPatchCount = expectedAtlasCount * ARNIS_TILES_PER_ATLAS;
        helper.assertTrue(District.values().length == 26
                        && expectedAtlasCount == 52 && expectedPatchCount == 13_312,
                "Arnis coverage contract must remain 26 districts × 2 zones × 256 tiles");
        helper.assertTrue(ArnisPatchLibrary.PATCHES.size() == expectedPatchCount,
                "runtime index must contain 13,312 audited A-Z Arnis tiles, found "
                        + ArnisPatchLibrary.PATCHES.size());
        helper.assertTrue(ArnisPatchLibrary.atlasCount() == expectedAtlasCount,
                "runtime index must contain exactly 52 district-zone atlases, found "
                        + ArnisPatchLibrary.atlasCount());

        Set<String> auditedOpenParkTiles = ArnisPatchLibrary.auditedOpenParkTileIds();
        Map<District, Integer> auditedParkDistricts =
                ArnisPatchLibrary.auditedOpenParkDistrictCounts();
        helper.assertTrue(auditedOpenParkTiles.size() == 1_178
                        && ArnisPatchLibrary.auditedOpenParkMaximumAboveSurface() == 2,
                "open-park NBT audit must retain 1,178 tiles no taller than surface+2");
        helper.assertTrue(ArnisPatchLibrary.auditedOpenParkHeightCounts().equals(
                        Map.of(0, 981, 1, 176, 2, 21)),
                "open-park NBT audit height distribution changed: "
                        + ArnisPatchLibrary.auditedOpenParkHeightCounts());
        helper.assertTrue(auditedParkDistricts.size() == District.values().length
                        && auditedParkDistricts.values().stream()
                                .mapToInt(Integer::intValue).sum() == auditedOpenParkTiles.size(),
                "open-park NBT audit district totals do not cover the allowlist");
        for (ArnisPatchLibrary.Patch patch : ArnisPatchLibrary.PATCHES) {
            helper.assertTrue(ArnisPatchLibrary.isConservativeOpenParkTile(patch)
                            == auditedOpenParkTiles.contains(patch.catalogId()),
                    "open-park runtime policy disagrees with NBT audit for "
                            + patch.catalogId());
        }

        int loadedAtlasTemplates = 0;
        Set<Integer> reflectionModes = new HashSet<>();
        boolean selectedAtNegativeCoordinate = false;
        boolean checkedRealConnector = false;
        for (District district : District.values()) {
            String districtPrefix = district.code().toLowerCase(Locale.ROOT) + "/";
            long districtPatches = ArnisPatchLibrary.PATCHES.stream()
                    .filter(patch -> patch.district() == district).count();
            long openParkTiles = ArnisPatchLibrary.PATCHES.stream()
                    .filter(patch -> patch.district() == district)
                    .filter(ArnisPatchLibrary::isConservativeOpenParkTile)
                    .count();
            helper.assertTrue(districtPatches == ARNIS_TILES_PER_ATLAS * ARNIS_ZONES.size(),
                    district + " must own exactly 512 Arnis tiles, found " + districtPatches);
            helper.assertTrue(openParkTiles == auditedParkDistricts.getOrDefault(district, 0)
                            && openParkTiles > 0,
                    district + " open-park count disagrees with its NBT audit");
            helper.assertTrue(ArnisPatchLibrary.districtAtlasCount(district) == ARNIS_ZONES.size(),
                    district + " must own one Nest and one Backstreets atlas");
            helper.assertTrue(
                    ArnisPatchLibrary.zoneAtlasCount(district, MegacityLayout.Zone.OUTSKIRTS) == 0,
                    district + " incorrectly owns an Outskirts atlas");

            for (MegacityLayout.Zone zone : ARNIS_ZONES) {
                String prefix = districtPrefix + zone.name().toLowerCase() + "_";
                Set<String> catalogIds = new HashSet<>();
                for (ArnisPatchLibrary.Patch patch : ArnisPatchLibrary.PATCHES) {
                    if (patch.district() != district || !patch.placementZones().contains(zone)) continue;
                    helper.assertTrue(patch.placementZones().equals(Set.of(zone)),
                            patch.catalogId() + " crosses district-zone atlas contracts");
                    helper.assertTrue(patch.sizeX() == 16 && patch.sizeZ() == 16
                                    && patch.surfaceOffset() >= 0 && patch.blockCount() >= 256
                                    && patch.sha256().length() == 64,
                            patch.catalogId() + " has invalid audited structure metadata");
                    catalogIds.add(patch.catalogId());
                }
                helper.assertTrue(ArnisPatchLibrary.zoneAtlasCount(district, zone) == 1,
                        district + " " + zone + " must resolve to exactly one coherent atlas");
                helper.assertTrue(catalogIds.size() == ARNIS_TILES_PER_ATLAS,
                        district + " " + zone + " is not a complete 16x16 atlas");
                for (int tileZ = 0; tileZ < ARNIS_ATLAS_AXIS; tileZ++) {
                    for (int tileX = 0; tileX < ARNIS_ATLAS_AXIS; tileX++) {
                        String expected = prefix + tileX + "_" + tileZ;
                        helper.assertTrue(catalogIds.contains(expected),
                                "coherent Arnis atlas is missing " + expected);
                    }
                }

                ArnisPatchLibrary.Placement found = findZonePlacement(layout, district, zone);
                helper.assertTrue(found != null,
                        district + " has no selectable " + zone + " Arnis tile");
                helper.assertTrue(found.patch().district() == district
                                && found.patch().placementZones().equals(Set.of(zone)),
                        "selected Arnis tile escaped " + district + " " + zone);
                helper.assertTrue(ArnisPatchLibrary.select(layout, found.chunkX(), found.chunkZ())
                                .orElseThrow().equals(found),
                        "Arnis selection changed between identical calls for " + district + " " + zone);
                BoundingBox placementBounds = new BoundingBox(
                        0, 0, 0, 15, found.patch().sizeY() - 1, 15);
                helper.assertTrue(NeonCityGenerator.arnisPlaceSettings(found, placementBounds)
                                .getProcessors().isEmpty(),
                        "Arnis placement must preserve source materials for " + district + " " + zone);
                var optionalTemplate = helper.getLevel().getStructureManager()
                        .get(found.patch().templateId());
                helper.assertTrue(optionalTemplate.isPresent(),
                        "packaged structure is missing for " + district + " " + zone
                                + ": " + found.patch().templateId());
                var templateSize = optionalTemplate.orElseThrow().getSize();
                helper.assertTrue(templateSize.getX() == found.patch().sizeX()
                                && templateSize.getY() == found.patch().sizeY()
                                && templateSize.getZ() == found.patch().sizeZ(),
                        "packaged structure dimensions disagree with catalog for "
                                + found.patch().catalogId());
                loadedAtlasTemplates++;
                selectedAtNegativeCoordinate |= found.chunkX() < 0 || found.chunkZ() < 0;
                reflectionModes.add(reflectionMode(found));
                assertCoherentNeighbour(helper, layout, found, prefix);

                if (!checkedRealConnector && !found.patch().connectors().isEmpty()) {
                    ArnisPatchLibrary.Connector transformed = ArnisPatchLibrary.transformConnector(
                            found.patch().connectors().getFirst(), found.flipX(), found.flipZ());
                    assertConnectorApproach(helper, layout, found, transformed);
                    checkedRealConnector = true;
                }
            }

        }

        collectReflectionModes(layout, reflectionModes);
        helper.assertTrue(loadedAtlasTemplates == expectedAtlasCount,
                "did not load one representative packaged template for all 52 atlases: "
                        + loadedAtlasTemplates);
        helper.assertTrue(selectedAtNegativeCoordinate,
                "A-Z selection coverage never exercised a negative world coordinate");
        helper.assertTrue(reflectionModes.equals(Set.of(0, 1, 2, 3)),
                "coherent atlas repetition did not exercise all reflection modes: "
                        + reflectionModes);
        assertConnectorTransforms(helper);
        if (!checkedRealConnector) {
            ArnisPatchLibrary.Placement connectorPlacement = findPlacementWithConnector(layout);
            helper.assertTrue(connectorPlacement != null,
                    "no selected A-Z Arnis tile exposed an inferred road connector");
            ArnisPatchLibrary.Connector transformed = ArnisPatchLibrary.transformConnector(
                    connectorPlacement.patch().connectors().getFirst(),
                    connectorPlacement.flipX(), connectorPlacement.flipZ());
            assertConnectorApproach(helper, layout, connectorPlacement, transformed);
            checkedRealConnector = true;
        }
        helper.assertTrue(checkedRealConnector,
                "no selected A-Z Arnis tile exposed an inferred road connector");
        assertColumnLevelInfrastructureComposition(helper, layout);

        int far = MegacityLayout.NOMINAL_CITY_RADIUS * 3;
        int[][] wildernessChunks = {
                {Math.floorDiv(far, 16), 0}, {Math.floorDiv(-far, 16), 0},
                {0, Math.floorDiv(far, 16)}, {0, Math.floorDiv(-far, 16)},
                {Math.floorDiv(-far, 16), Math.floorDiv(-far, 16)}
        };
        for (int[] chunk : wildernessChunks) {
            helper.assertTrue(ArnisPatchLibrary.select(layout, chunk[0], chunk[1]).isEmpty(),
                    "Arnis patch selection leaked into wilderness chunk "
                            + chunk[0] + "," + chunk[1]);
        }
        helper.succeed();
    }

    private static void assertColumnLevelInfrastructureComposition(
            GameTestHelper helper, MegacityLayout layout) {
        MegacityLayout.Node node = layout.node(District.A_CORP);
        int centerChunkX = Math.floorDiv(node.x(), 16);
        int centerChunkZ = Math.floorDiv(node.z(), 16);
        boolean foundMixedChunk = false;
        for (int ring = 0; ring <= 12 && !foundMixedChunk; ring++) {
            for (int dz = -ring; dz <= ring && !foundMixedChunk; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                            layout, chunkX, chunkZ).orElse(null);
                    if (placement == null || placement.patch().district() != District.A_CORP) {
                        continue;
                    }
                    NeonCityGenerator.UrbanSample[][] samples = NeonCityGenerator.sampleChunk(
                            chunkX << 4, chunkZ << 4);
                    if (!NeonCityGenerator.isArnisCompatibleChunk(
                            samples, placement.patch().district())) {
                        continue;
                    }
                    int overrides = 0;
                    for (int z = 1; z <= 16; z++) {
                        for (int x = 1; x <= 16; x++) {
                            if (NeonCityGenerator.overridesArnis(samples[z][x].roadClass())) {
                                overrides++;
                            }
                        }
                    }
                    if (overrides > 0 && overrides < 256) {
                        foundMixedChunk = true;
                        break;
                    }
                }
            }
        }
        helper.assertTrue(foundMixedChunk,
                "no same-zone Arnis chunk retained buildings beside column-level infrastructure");

        boolean foundMaskedEdge = false;
        Set<Long> visitedChunks = new HashSet<>();
        for (District district : District.values()) {
            MegacityLayout.Node districtNode = layout.node(district);
            for (double radius = 0.96; radius <= 1.08 && !foundMaskedEdge; radius += 0.01) {
                for (int angle = 0; angle < 256; angle++) {
                    int[] point = ellipsePoint(districtNode, radius, angle, 256);
                    int chunkX = Math.floorDiv(point[0], 16);
                    int chunkZ = Math.floorDiv(point[1], 16);
                    if (!visitedChunks.add(ChunkPos.pack(chunkX, chunkZ))) continue;
                    ArnisPatchLibrary.Placement placement = ArnisPatchLibrary.select(
                            layout, chunkX, chunkZ).orElse(null);
                    if (placement == null || placement.patch().district() != district) continue;
                    NeonCityGenerator.UrbanSample[][] samples = NeonCityGenerator.sampleChunk(
                            chunkX << 4, chunkZ << 4);
                    int retained = 0;
                    for (int z = 1; z <= 16; z++) {
                        for (int x = 1; x <= 16; x++) {
                            if (NeonCityGenerator.keepsArnisColumn(
                                    samples[z][x], placement.patch().district())) {
                                retained++;
                            }
                        }
                    }
                    if (retained >= 32 && retained < 256
                            && NeonCityGenerator.isArnisCompatibleChunk(
                                    samples, placement.patch().district())) {
                        foundMaskedEdge = true;
                        break;
                    }
                }
            }
            if (foundMaskedEdge) break;
        }
        helper.assertTrue(foundMaskedEdge,
                "no boundary Arnis chunk retained a masked partial atlas footprint");
    }

    public static void arnisFacadeRepair(GameTestHelper helper) {
        ChunkPos chunk = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int minY = NeonCityGenerator.CITY_GROUND_Y + 1;
        for (int along = 4; along <= 7; along++) {
            for (int y = minY; y < minY + 18; y++) {
                for (int depth = 0; depth < 4; depth++) {
                    helper.getLevel().setBlock(
                            new BlockPos(
                                    chunk.getMaxBlockX() - depth,
                                    y,
                                    chunk.getMinBlockZ() + along),
                            Blocks.AIR.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }
        int[] floorOffsets = {2, 7, 12};
        for (int along = 5; along <= 6; along++) {
            for (int floorOffset : floorOffsets) {
                for (int depth = 1; depth <= 2; depth++) {
                    helper.getLevel().setBlock(
                            new BlockPos(
                                    chunk.getMaxBlockX() - depth,
                                    minY + floorOffset,
                                    chunk.getMinBlockZ() + along),
                            Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_ALL);
                }
            }
        }

        int changed = ArnisFacadeRepair.sealInterruptedEdges(
                helper.getLevel(),
                chunk,
                Set.of(ArnisPatchLibrary.Connector.Edge.EAST));
        helper.assertTrue(changed == 22,
                "live facade repair expected 22 blocks but placed " + changed);
        for (int along = 5; along <= 6; along++) {
            for (int y = minY + 2; y <= minY + 12; y++) {
                BlockPos boundary = new BlockPos(
                        chunk.getMaxBlockX(), y, chunk.getMinBlockZ() + along);
                helper.assertTrue(helper.getLevel().getBlockState(boundary).is(Blocks.STONE),
                        "live facade repair did not reuse the source material at " + boundary);
            }
            helper.assertTrue(helper.getLevel().isEmptyBlock(new BlockPos(
                            chunk.getMaxBlockX(), minY + 1, chunk.getMinBlockZ() + along))
                            && helper.getLevel().isEmptyBlock(new BlockPos(
                                    chunk.getMaxBlockX(), minY + 13,
                                    chunk.getMinBlockZ() + along)),
                    "live facade repair escaped the detected building height");
        }

        boolean[] exposedFloors = new boolean[18];
        exposedFloors[2] = true;
        exposedFloors[7] = true;
        exposedFloors[12] = true;

        helper.assertTrue(ArnisFacadeRepair.isBuildingCrossSection(exposedFloors),
                "three separated structural floors must identify an exposed building section");
        boolean[] completion = ArnisFacadeRepair.completionMask(exposedFloors);
        for (int index = 2; index <= 12; index++) {
            helper.assertTrue(completion[index],
                    "facade completion left a hole between structural floors at " + index);
        }
        helper.assertTrue(!completion[1] && !completion[13],
                "facade completion escaped the detected building height");

        boolean[] isolatedProp = new boolean[18];
        isolatedProp[3] = true;
        isolatedProp[4] = true;
        helper.assertTrue(!ArnisFacadeRepair.isBuildingCrossSection(isolatedProp),
                "a short isolated prop was mistaken for a sliced building");
        for (boolean block : ArnisFacadeRepair.completionMask(isolatedProp)) {
            helper.assertTrue(!block,
                    "a non-building cross-section requested facade blocks");
        }

        BlockPos bannerSupport = new BlockPos(
                chunk.getMinBlockX() + 8, minY + 20, chunk.getMinBlockZ() + 8);
        helper.getLevel().setBlock(bannerSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.getLevel().setBlock(
                bannerSupport.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(DistrictLogoBanners.placeBanner(
                        helper.getLevel(), bannerSupport, Direction.EAST, District.S_CORP),
                "district emblem could not be placed on a valid facade");
        DistrictLogoBanners.Design bannerDesign = DistrictLogoBanners.design(District.S_CORP);
        helper.assertTrue(helper.getLevel().getBlockState(bannerSupport.east())
                        .is(Blocks.WALL_BANNER.pick(bannerDesign.base())),
                "district emblem used the wrong base color");
        helper.assertTrue(helper.getLevel().getBlockEntity(bannerSupport.east())
                        instanceof BannerBlockEntity banner
                        && banner.getPatterns().layers().size() == 2
                        && banner.getPatterns().layers().get(0).color() == bannerDesign.primary()
                        && banner.getPatterns().layers().get(1).color() == bannerDesign.secondary()
                        && banner.getCustomName() != null
                        && banner.getCustomName().getString().equals("S Corp Emblem"),
                "district emblem lost its two pattern colors or identity");

        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        ArnisPatchLibrary.Placement nest = findZonePlacement(
                layout, District.A_CORP, MegacityLayout.Zone.NEST);
        ArnisPatchLibrary.Placement backstreets = findZonePlacement(
                layout, District.A_CORP, MegacityLayout.Zone.BACKSTREETS);
        helper.assertTrue(nest != null && backstreets != null,
                "facade seam regression could not find both A Corp atlases");
        helper.assertTrue(!ArnisPatchLibrary.continuesCoherently(
                        nest, backstreets, 1, 0),
                "different zone atlases were treated as one continuous building");
        helper.succeed();
    }

    private static ArnisPatchLibrary.Placement findZonePlacement(
            MegacityLayout layout, District district, MegacityLayout.Zone zone) {
        MegacityLayout.Node node = layout.node(district);
        int centerChunkX = Math.floorDiv(node.x(), 16);
        int centerChunkZ = Math.floorDiv(node.z(), 16);
        int maxRadius = Math.max(node.radiusX(), node.radiusZ()) / 16 + 4;
        for (int ring = 0; ring <= maxRadius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    Optional<ArnisPatchLibrary.Placement> placement = ArnisPatchLibrary.select(
                            layout, centerChunkX + dx, centerChunkZ + dz);
                    if (placement.isPresent()
                            && placement.get().patch().district() == district
                            && placement.get().patch().placementZones().equals(Set.of(zone))
                            && hasSameZoneNeighbour(layout, placement.get())) {
                        return placement.get();
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasSameZoneNeighbour(
            MegacityLayout layout, ArnisPatchLibrary.Placement placement) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            Optional<ArnisPatchLibrary.Placement> neighbour = ArnisPatchLibrary.select(
                    layout, placement.chunkX() + direction[0], placement.chunkZ() + direction[1]);
            if (neighbour.isPresent()
                    && neighbour.get().patch().district() == placement.patch().district()
                    && neighbour.get().patch().placementZones()
                            .equals(placement.patch().placementZones())) {
                return true;
            }
        }
        return false;
    }

    private static void collectReflectionModes(MegacityLayout layout, Set<Integer> modes) {
        for (MegacityLayout.Node node : layout.nodes()) {
            int centerChunkX = Math.floorDiv(node.x(), 16);
            int centerChunkZ = Math.floorDiv(node.z(), 16);
            for (int dz = -24; dz <= 24 && modes.size() < 4; dz++) {
                for (int dx = -24; dx <= 24 && modes.size() < 4; dx++) {
                    ArnisPatchLibrary.select(layout, centerChunkX + dx, centerChunkZ + dz)
                            .ifPresent(placement -> modes.add(reflectionMode(placement)));
                }
            }
            if (modes.size() == 4) return;
        }
    }

    private static ArnisPatchLibrary.Placement findPlacementWithConnector(
            MegacityLayout layout) {
        for (MegacityLayout.Node node : layout.nodes()) {
            int centerChunkX = Math.floorDiv(node.x(), 16);
            int centerChunkZ = Math.floorDiv(node.z(), 16);
            int maxRadius = Math.max(node.radiusX(), node.radiusZ()) / 16 + 4;
            for (int ring = 0; ring <= maxRadius; ring++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        Optional<ArnisPatchLibrary.Placement> placement = ArnisPatchLibrary.select(
                                layout, centerChunkX + dx, centerChunkZ + dz);
                        if (placement.isPresent()
                                && !placement.get().patch().connectors().isEmpty()) {
                            return placement.get();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int reflectionMode(ArnisPatchLibrary.Placement placement) {
        return (placement.flipX() ? 1 : 0) | (placement.flipZ() ? 2 : 0);
    }

    private static void assertCoherentNeighbour(
            GameTestHelper helper,
            MegacityLayout layout,
            ArnisPatchLibrary.Placement placement,
            String prefix) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            Optional<ArnisPatchLibrary.Placement> neighbour = ArnisPatchLibrary.select(
                    layout, placement.chunkX() + direction[0], placement.chunkZ() + direction[1]);
            if (neighbour.isEmpty()
                    || neighbour.get().patch().district() != placement.patch().district()
                    || !neighbour.get().patch().placementZones()
                            .equals(placement.patch().placementZones())) continue;
            ArnisPatchLibrary.Placement next = neighbour.get();
            helper.assertTrue(ArnisPatchLibrary.continuesCoherently(
                            placement, next, direction[0], direction[1]),
                    "adjacent Arnis tiles were not recognized as one coherent facade");
            if (direction[0] != 0) {
                int expectedX = placement.flipX() == next.flipX()
                        ? placement.sourceTileX() + direction[0] * (placement.flipX() ? -1 : 1)
                        : placement.sourceTileX();
                helper.assertTrue(next.sourceTileX() == expectedX
                                && next.sourceTileZ() == placement.sourceTileZ(),
                        "reflected Arnis X seam shuffled neighbouring source tiles");
            } else {
                int expectedZ = placement.flipZ() == next.flipZ()
                        ? placement.sourceTileZ() + direction[1] * (placement.flipZ() ? -1 : 1)
                        : placement.sourceTileZ();
                helper.assertTrue(next.sourceTileZ() == expectedZ
                                && next.sourceTileX() == placement.sourceTileX(),
                        "reflected Arnis Z seam shuffled neighbouring source tiles");
            }
            String expectedId = prefix + next.sourceTileX() + "_" + next.sourceTileZ();
            helper.assertTrue(next.patch().catalogId().equals(expectedId),
                    "coherent Arnis atlas selected " + next.patch().catalogId()
                            + " instead of " + expectedId);
            return;
        }
        helper.assertTrue(false,
                "selected Arnis tile has no coherent same-zone neighbour: "
                        + placement.patch().catalogId());
    }

    private static void assertConnectorTransforms(GameTestHelper helper) {
        ArnisPatchLibrary.Connector source = new ArnisPatchLibrary.Connector(
                ArnisPatchLibrary.Connector.Edge.WEST, 3, 4);
        helper.assertTrue(ArnisPatchLibrary.transformConnector(source, false, false).equals(source),
                "identity connector transform changed its edge run");
        helper.assertTrue(ArnisPatchLibrary.transformConnector(source, true, false).equals(
                        new ArnisPatchLibrary.Connector(
                                ArnisPatchLibrary.Connector.Edge.EAST, 3, 4)),
                "X reflection did not swap west/east connector edges");
        helper.assertTrue(ArnisPatchLibrary.transformConnector(source, false, true).equals(
                        new ArnisPatchLibrary.Connector(
                                ArnisPatchLibrary.Connector.Edge.WEST, 9, 4)),
                "Z reflection did not reverse connector offsets");
        helper.assertTrue(ArnisPatchLibrary.transformConnector(source, true, true).equals(
                        new ArnisPatchLibrary.Connector(
                                ArnisPatchLibrary.Connector.Edge.EAST, 9, 4)),
                "combined reflection did not transform connector edge and offset");
    }

    private static void assertConnectorApproach(
            GameTestHelper helper,
            MegacityLayout layout,
            ArnisPatchLibrary.Placement placement,
            ArnisPatchLibrary.Connector connector) {
        for (int offset = connector.offset(); offset < connector.offset() + connector.width(); offset++) {
            assertConnectorPoint(helper, layout, placement, connector, offset, true);
        }
    }

    private static void assertConnectorPoint(
            GameTestHelper helper,
            MegacityLayout layout,
            ArnisPatchLibrary.Placement placement,
            ArnisPatchLibrary.Connector connector,
            int offset,
            boolean expected) {
        int minX = placement.chunkX() << 4;
        int minZ = placement.chunkZ() << 4;
        int[] point = switch (connector.edge()) {
            case WEST -> new int[]{minX - 1, minZ + offset};
            case EAST -> new int[]{minX + 16, minZ + offset};
            case NORTH -> new int[]{minX + offset, minZ - 1};
            case SOUTH -> new int[]{minX + offset, minZ + 16};
        };
        boolean actual = ArnisPatchLibrary.connectorApproachAt(layout, point[0], point[1]);
        helper.assertTrue(actual == expected,
                "Arnis road connector " + connector.edge() + " offset " + offset
                        + " expected stitched=" + expected + " but was " + actual);
    }

    private static void assertSpecialRoad(GameTestHelper helper, District district,
                                          NeonCityGenerator.RoadClass expected) {
        MegacityLayout.Node node = NeonCityGenerator.layout().node(district);
        boolean found = false;
        for (double radius = 0.48; radius <= 1.07 && !found; radius += 0.025) {
            for (int angle = 0; angle < 144; angle++) {
                int[] point = ellipsePoint(node, radius, angle, 144);
                NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(point[0], point[1]);
                if (sample.district() == district && sample.roadClass() == expected) {
                    found = true;
                    break;
                }
            }
        }
        helper.assertTrue(found, district + " never generated its required " + expected);
    }

    /** Retains the old registration name while proving buildings are Arnis-only. */
    public static void skylineHierarchy(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        int inhabitedSamples = 0;
        for (MegacityLayout.Node node : layout.nodes()) {
            for (double radius = 0.10; radius <= 1.06; radius += 0.025) {
                for (int angle = 0; angle < 96; angle++) {
                    int[] point = ellipsePoint(node, radius, angle, 96);
                    NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(point[0], point[1]);
                    if (sample.district() != node.district()) continue;
                    if (sample.zone() != MegacityLayout.Zone.NEST
                            && sample.zone() != MegacityLayout.Zone.BACKSTREETS) continue;
                    inhabitedSamples++;
                    helper.assertTrue(sample.buildingHeight() == 0 && !sample.insideFootprint(),
                            "procedural building massing survived in " + node.district()
                                    + " at " + point[0] + "," + point[1]);
                }
            }
        }
        helper.assertTrue(inhabitedSamples > 10_000,
                "Arnis-only regression did not cover enough inhabited samples");

        BlockPos column = helper.absolutePos(new BlockPos(4, 0, 4));
        int topY = NeonCityGenerator.CITY_GROUND_Y + 80;
        for (int y = NeonCityGenerator.CITY_GROUND_Y + 1; y <= topY; y++) {
            helper.getLevel().setBlock(
                    new BlockPos(column.getX(), y, column.getZ()),
                    Blocks.EMERALD_BLOCK.defaultBlockState(),
                    Block.UPDATE_ALL);
        }
        MegacityLayout.Location location = layout.locate(0, 0);
        NeonCityGenerator.UrbanSample forcedTower = new NeonCityGenerator.UrbanSample(
                location,
                District.B_CORP,
                MegacityLayout.Zone.NEST,
                NeonCityGenerator.RoadClass.NONE,
                NeonCityGenerator.CITY_GROUND_Y,
                180,
                District.B_CORP.parcelSize(),
                true,
                0,
                0,
                12.0,
                12.0,
                TEST_SEED);
        NeonCityGenerator.buildColumn(
                helper.getLevel(),
                new BlockPos.MutableBlockPos(),
                column.getX(),
                column.getZ(),
                forcedTower);
        int infrastructureCeiling = NeonCityGenerator.CITY_GROUND_Y + 15;
        for (int y = NeonCityGenerator.CITY_GROUND_Y + 1; y <= topY; y++) {
            BlockState state = helper.getLevel().getBlockState(
                    new BlockPos(column.getX(), y, column.getZ()));
            helper.assertTrue(!state.is(Blocks.EMERALD_BLOCK),
                    "seeded fallback tower survived at y=" + y);
            if (y > infrastructureCeiling) {
                helper.assertTrue(state.isAir(),
                        "non-Arnis fallback placed massing above infrastructure at y=" + y);
            }
        }
        helper.succeed();
    }

    public static void negativeDeterminism(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout firstLayout = MegacityLayout.create(TEST_SEED);
        MegacityLayout secondLayout = MegacityLayout.create(TEST_SEED);
        int[][] points = {
                {-1, -1}, {-16, -16}, {-17, 15}, {-1537, -1537},
                {-4097, 2111}, {8192, -6000}, {-14700, -14700}
        };
        for (int[] point : points) {
            MegacityLayout.Location firstLocation = firstLayout.locate(point[0], point[1]);
            MegacityLayout.Location secondLocation = secondLayout.locate(point[0], point[1]);
            helper.assertTrue(firstLocation.equals(secondLocation),
                    "layout changed at negative/global coordinate " + point[0] + "," + point[1]);

            NeonCityGenerator.UrbanSample first = NeonCityGenerator.sample(point[0], point[1]);
            NeonCityGenerator.UrbanSample second = NeonCityGenerator.sample(point[0], point[1]);
            helper.assertTrue(first.equals(second),
                    "generator sample changed at " + point[0] + "," + point[1]);
            helper.assertTrue(NeonCityGenerator.roadAt(point[0], point[1]) == first.roadClass(),
                    "roadAt disagrees with sample at " + point[0] + "," + point[1]);
            helper.assertTrue(NeonCityGenerator.districtAt(point[0], point[1]) == first.district(),
                    "districtAt disagrees with sample at " + point[0] + "," + point[1]);
        }
        helper.succeed();
    }

    public static void pedestrianPolicy(GameTestHelper helper) {
        EnumSet<NeonCityGenerator.RoadClass> arnisSurfaces = EnumSet.of(
                NeonCityGenerator.RoadClass.NONE,
                NeonCityGenerator.RoadClass.CENTRAL_PLAZA,
                NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD,
                NeonCityGenerator.RoadClass.LOCAL_STREET,
                NeonCityGenerator.RoadClass.SERVICE_ALLEY);
        EnumSet<NeonCityGenerator.RoadClass> highways = EnumSet.of(
                NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD,
                NeonCityGenerator.RoadClass.BRIDGE,
                NeonCityGenerator.RoadClass.ELEVATED_RAIL);
        for (NeonCityGenerator.RoadClass road : NeonCityGenerator.RoadClass.values()) {
            helper.assertTrue(
                    NeonCityGenerator.isCivilianPedestrianTarget(road, false)
                            == (road == NeonCityGenerator.RoadClass.PARK),
                    "non-Arnis civilian policy accepted " + road);
            helper.assertTrue(
                    NeonCityGenerator.isCivilianPedestrianTarget(road, true)
                            == (road == NeonCityGenerator.RoadClass.PARK
                            || arnisSurfaces.contains(road)),
                    "Arnis civilian policy misclassified " + road);
            helper.assertTrue(
                    NeonCityGenerator.isHighwayRoadClass(road) == highways.contains(road),
                    "highway classifier misclassified " + road);
        }

        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        int highwaySamples = 0;
        for (MegacityLayout.Edge edge : layout.edges()) {
            for (int step = 1; step < 10; step++) {
                int[] point = connectionPoint(edge, step / 10.0);
                if (NeonCityGenerator.isHighwayAt(layout, point[0], point[1])) {
                    highwaySamples++;
                }
            }
        }
        helper.assertTrue(highwaySamples >= layout.edges().size(),
                "travel graph scan did not exercise enough highway samples");
        helper.succeed();
    }

    private static boolean isTravelInfrastructure(NeonCityGenerator.RoadClass road) {
        return road == NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD
                || road == NeonCityGenerator.RoadClass.BRIDGE
                || road == NeonCityGenerator.RoadClass.ELEVATED_RAIL
                // Graph edges intentionally terminate at each civic plaza.
                || road == NeonCityGenerator.RoadClass.CENTRAL_PLAZA;
    }

    private static int[] ellipsePoint(MegacityLayout.Node node, double radius,
                                      int angleIndex, int angleCount) {
        double angle = angleIndex * Math.PI * 2.0 / angleCount;
        double localX = Math.cos(angle) * node.radiusX() * radius;
        double localZ = Math.sin(angle) * node.radiusZ() * radius;
        double cosine = Math.cos(node.rotation());
        double sine = Math.sin(node.rotation());
        return new int[] {
                (int) Math.round(node.x() + localX * cosine - localZ * sine),
                (int) Math.round(node.z() + localX * sine + localZ * cosine)
        };
    }

    private static int[] connectionPoint(MegacityLayout.Edge edge, double t) {
        MegacityLayout.Node first = edge.first();
        MegacityLayout.Node second = edge.second();
        double dx = second.x() - first.x();
        double dz = second.z() - first.z();
        double length = Math.max(1.0, Math.hypot(dx, dz));
        double controlX = (first.x() + second.x()) * 0.5 - dz * edge.bend();
        double controlZ = (first.z() + second.z()) * 0.5 + dx * edge.bend();
        double inverse = 1.0 - t;
        return new int[] {
                (int) Math.round(inverse * inverse * first.x()
                        + 2.0 * inverse * t * controlX + t * t * second.x()),
                (int) Math.round(inverse * inverse * first.z()
                        + 2.0 * inverse * t * controlZ + t * t * second.z())
        };
    }

    private static long undirectedPair(District first, District second) {
        int low = Math.min(first.ordinal(), second.ordinal());
        int high = Math.max(first.ordinal(), second.ordinal());
        return ((long) low << 32) | (high & 0xffffffffL);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
