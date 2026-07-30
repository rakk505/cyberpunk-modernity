package dev.modernity.neoncity;

import com.example.cyberdeck.CyberdeckItems;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.cyberware.CyberwareTier;
import com.example.cyberdeck.weapon.AmmoItems;
import com.example.cyberdeck.weapon.AmmoType;
import com.example.cyberdeck.weapon.GunType;
import com.example.cyberdeck.weapon.WeaponItems;
import com.example.cyberdeck.network.OpenCityMapPacket;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/** Pure regression tests for finite megacity topology, culture, and massing. */
public final class ExampleGameTests {
    private static final long TEST_SEED = 0x4E454F4E43495459L;
    private static final int RADIAL_STEPS = 72;
    private static final long[] ZONE_SEED_OFFSETS = {0L, 20L, 85L, 127L};
    private static final int ARNIS_ATLAS_AXIS = 8;
    private static final int ARNIS_TILES_PER_ATLAS = ARNIS_ATLAS_AXIS * ARNIS_ATLAS_AXIS;
    private static final EnumSet<MegacityLayout.Zone> ARNIS_ZONES =
            EnumSet.of(MegacityLayout.Zone.NEST, MegacityLayout.Zone.BACKSTREETS);

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
        helper.assertTrue(markers.size() == District.values().length + 6,
                "city map must expose 26 transit nodes and six future job leads");
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
        helper.assertTrue(layout.isConnected(), "district travel graph is disconnected");
        helper.assertTrue(layout.edges().size() > layout.nodes().size() - 1,
                "travel graph needs loops in addition to its spanning tree");

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

    public static void connectionContinuity(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        EnumSet<NeonCityGenerator.RoadClass> infrastructure =
                EnumSet.noneOf(NeonCityGenerator.RoadClass.class);
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
        }
        helper.assertTrue(infrastructure.contains(NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD)
                        || infrastructure.contains(NeonCityGenerator.RoadClass.BRIDGE),
                "connections contain no drivable interdistrict infrastructure");
        helper.assertTrue(infrastructure.contains(NeonCityGenerator.RoadClass.ELEVATED_RAIL),
                "connections contain no elevated rail");
        helper.succeed();
    }

    /** Retains the old registration name for roads, bridges, parks, and special districts. */
    public static void organicRoads(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        EnumSet<NeonCityGenerator.RoadClass> roads =
                EnumSet.noneOf(NeonCityGenerator.RoadClass.class);
        for (MegacityLayout.Node node : layout.nodes()) {
            roads.add(NeonCityGenerator.roadAt(node.x(), node.z()));
            for (double radius = 0.12; radius <= 1.06; radius += 0.047) {
                for (int angle = 0; angle < RADIAL_STEPS; angle++) {
                    int[] point = ellipsePoint(node, radius, angle, RADIAL_STEPS);
                    NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(point[0], point[1]);
                    if (sample.district() == node.district()) roads.add(sample.roadClass());
                }
            }
        }
        for (MegacityLayout.Edge edge : layout.edges()) {
            for (int step = 1; step < 20; step++) {
                int[] point = connectionPoint(edge, step / 20.0);
                roads.add(NeonCityGenerator.roadAt(point[0], point[1]));
            }
        }

        NeonCityGenerator.RoadClass[] required = {
                NeonCityGenerator.RoadClass.CENTRAL_PLAZA,
                NeonCityGenerator.RoadClass.DISTRICT_BOULEVARD,
                NeonCityGenerator.RoadClass.LOCAL_STREET,
                NeonCityGenerator.RoadClass.SERVICE_ALLEY,
                NeonCityGenerator.RoadClass.PARK,
                NeonCityGenerator.RoadClass.INTERDISTRICT_ROAD,
                NeonCityGenerator.RoadClass.BRIDGE,
                NeonCityGenerator.RoadClass.ELEVATED_RAIL,
                NeonCityGenerator.RoadClass.BORDER_RIVER,
                NeonCityGenerator.RoadClass.BORDER_HILLS
        };
        for (NeonCityGenerator.RoadClass road : required) {
            helper.assertTrue(roads.contains(road), "city is missing infrastructure class " + road);
        }
        helper.succeed();
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

    public static void merchantTrucks(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MerchantTruckLibrary.TruckAsset asset = MerchantTruckLibrary.truck();
        helper.assertTrue(asset.sizeX() == 14 && asset.sizeY() == 8 && asset.sizeZ() == 7
                        && asset.blockCount() == 298 && asset.sha256().length() == 64,
                "converted spud truck catalog metadata changed");
        var template = helper.getLevel().getStructureManager().get(asset.templateId()).orElse(null);
        helper.assertTrue(template != null
                        && template.getSize().getX() == 14
                        && template.getSize().getY() == 8
                        && template.getSize().getZ() == 7,
                "converted spud truck structure is missing or has the wrong dimensions");

        Set<MerchantTruckLibrary.TruckCandidate> blackTrucks = new HashSet<>();
        int plannedTrucks = 0;
        for (District district : District.values()) {
            MerchantTruckLibrary.TruckCandidate black = MerchantTruckLibrary
                    .canonicalBlackTruck(district)
                    .orElseThrow(() -> new AssertionError(
                            district + " has no park space for its canonical black truck"));
            helper.assertTrue(black.district() == district
                            && MerchantTruckLibrary.role(black)
                                    == MerchantTruckLibrary.MerchantRole.QUEST,
                    district + " did not resolve to one canonical black fixer truck");
            helper.assertTrue(blackTrucks.add(black),
                    "two districts selected the same canonical black truck");
            MerchantTruckLibrary.ClusterPlan cluster = MerchantTruckLibrary.plan(
                    black.clusterX(), black.clusterZ());
            helper.assertTrue(cluster.trucks().size() <= 2 && cluster.trucks().contains(black),
                    "merchant cluster exceeded two trucks or lost its canonical fixer");
            plannedTrucks += cluster.trucks().size();
            for (MerchantTruckLibrary.TruckCandidate candidate : cluster.trucks()) {
                for (int z = 0; z < candidate.sizeZ(); z++) {
                    for (int x = 0; x < candidate.sizeX(); x++) {
                        NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(
                                candidate.minX() + x, candidate.minZ() + z);
                        helper.assertTrue(
                                sample.roadClass() == NeonCityGenerator.RoadClass.PARK
                                        && (sample.zone() == MegacityLayout.Zone.NEST
                                        || sample.zone() == MegacityLayout.Zone.BACKSTREETS)
                                        && sample.district() == candidate.district()
                                        && sample.groundY() == candidate.groundY(),
                                "merchant truck escaped a flat same-district park footprint");
                    }
                }
            }
        }
        helper.assertTrue(blackTrucks.size() == District.values().length && plannedTrucks >= 26,
                "not every district owns a unique fixer truck");

        EnumSet<MerchantTruckLibrary.MerchantRole> tradingRoles = EnumSet.noneOf(
                MerchantTruckLibrary.MerchantRole.class);
        for (int index = 0; index < 512; index++) {
            MerchantTruckLibrary.TruckCandidate synthetic =
                    new MerchantTruckLibrary.TruckCandidate(
                            10_000 + index,
                            -10_000,
                            20_000 + index,
                            -20_000,
                            1,
                            1,
                            Rotation.NONE,
                            District.A_CORP,
                            NeonCityGenerator.CITY_GROUND_Y,
                            index);
            MerchantTruckLibrary.MerchantRole role = MerchantTruckLibrary.role(synthetic);
            if (role != MerchantTruckLibrary.MerchantRole.QUEST) {
                tradingRoles.add(role);
            }
        }
        helper.assertTrue(tradingRoles.containsAll(EnumSet.of(
                        MerchantTruckLibrary.MerchantRole.GUN,
                        MerchantTruckLibrary.MerchantRole.CYBERWARE,
                        MerchantTruckLibrary.MerchantRole.CLOTHING,
                        MerchantTruckLibrary.MerchantRole.CONSUMABLE)),
                "deterministic truck roles do not cover all four merchants");

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
        List<MerchantQuestService.QuestOffer> firstOffers = MerchantQuestService.offers(
                NeonCityGenerator.layout(), TEST_SEED, questAnchor, District.B_CORP);
        List<MerchantQuestService.QuestOffer> secondOffers = MerchantQuestService.offers(
                NeonCityGenerator.layout(), TEST_SEED, questAnchor, District.B_CORP);
        helper.assertTrue(firstOffers.equals(secondOffers) && firstOffers.size() == 5,
                "fixer offers are not deterministic five-contract boards");
        helper.assertTrue(firstOffers.getFirst().local()
                        && firstOffers.getFirst().targetDistrictOrdinal()
                                == District.B_CORP.ordinal(),
                "fixer board has no local district delivery");
        Set<Integer> remoteTargets = new HashSet<>();
        for (MerchantQuestService.QuestOffer offer : firstOffers) {
            helper.assertTrue(offer.reward() > 0 && !offer.cargo().isBlank(),
                    "fixer offered a delivery without cargo or pay");
            if (!offer.local()) {
                helper.assertTrue(offer.targetDistrictOrdinal() != District.B_CORP.ordinal(),
                        "remote fixer delivery targeted its source district");
                remoteTargets.add(offer.targetDistrictOrdinal());
            }
        }
        helper.assertTrue(remoteTargets.size() == 4,
                "fixer board does not offer four distinct interdistrict deliveries");

        BlockPos testOrigin = helper.absolutePos(BlockPos.ZERO);
        int testChunkX = Math.floorDiv(testOrigin.getX(), 16);
        int testChunkZ = Math.floorDiv(testOrigin.getZ(), 16);
        MerchantTruckLibrary.TruckCandidate live = new MerchantTruckLibrary.TruckCandidate(
                Math.floorDiv(testChunkX, 2),
                Math.floorDiv(testChunkZ, 2),
                testChunkX,
                testChunkZ,
                1,
                1,
                Rotation.NONE,
                District.B_CORP,
                80,
                TEST_SEED);
        MerchantTruckLibrary.TruckCandidate rotated = new MerchantTruckLibrary.TruckCandidate(
                live.clusterX(),
                live.clusterZ(),
                live.chunkX(),
                live.chunkZ(),
                live.localX(),
                live.localZ(),
                Rotation.CLOCKWISE_90,
                live.district(),
                live.groundY(),
                live.selectionHash());
        helper.assertTrue(
                live.merchantSpawn().equals(live.base().offset(3, 2, 4))
                        && rotated.merchantSpawn().equals(rotated.base().offset(2, 2, 3)),
                "merchant counter spawn does not rotate with its truck");
        helper.assertTrue(MerchantTruckLibrary.placeTruck(
                        helper.getLevel(), live, MerchantTruckLibrary.MerchantRole.CYBERWARE),
                "live merchant truck template refused placement");
        helper.assertTrue(
                helper.getLevel().getBlockState(live.base().offset(3, 2, 4)).isAir()
                        && helper.getLevel().getBlockState(live.base().offset(4, 2, 4)).isAir(),
                "merchant service counter still blocks its ground-level interaction path");
        int yellowBodyBlocks = 0;
        for (int y = 0; y < asset.sizeY(); y++) {
            for (int z = 0; z < live.sizeZ(); z++) {
                for (int x = 0; x < live.sizeX(); x++) {
                    if (helper.getLevel().getBlockState(live.base().offset(x, y, z))
                            .is(Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.YELLOW))) {
                        yellowBodyBlocks++;
                    }
                }
            }
        }
        List<Villager> merchants = helper.getLevel().getEntitiesOfClass(
                Villager.class,
                new AABB(live.base()).expandTowards(
                        live.sizeX(), asset.sizeY(), live.sizeZ()).inflate(1.0),
                MerchantTruckLibrary::isMerchant);
        helper.assertTrue(yellowBodyBlocks >= 50 && merchants.size() == 1
                        && MerchantTruckLibrary.merchantRole(merchants.getFirst()).orElseThrow()
                                == MerchantTruckLibrary.MerchantRole.CYBERWARE
                        && merchants.getFirst().blockPosition().equals(live.merchantSpawn())
                        && helper.getLevel().noCollision(merchants.getFirst())
                        && merchants.getFirst().getOffers().size() == expectedCyberware,
                "live yellow truck lacks its recolored body or reachable cyberware merchant");
        helper.succeed();
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
        helper.assertTrue(center.district() == District.Y_CORP
                        && center.zone() == MegacityLayout.Zone.NEST
                        && center.roadClass() == NeonCityGenerator.RoadClass.CENTRAL_PLAZA,
                "Y Corp winter capital is not generated as an urban center");
        helper.succeed();
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
                                ELEVATED_RAIL -> backstreetInfrastructure++;
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
                "district border hills are flat or form abrupt walls");
        helper.assertTrue(hillSurfaces.size() >= 3,
                "district border hills lack coherent grass, soil, and rock patches");

        int treeAnchors = 0;
        int cottages = 0;
        for (int z = -128; z < 128; z++) {
            for (int x = -128; x < 128; x++) {
                if (DistrictWorldFeatures.isHillTreeAnchor(TEST_SEED, x, z)) {
                    treeAnchors++;
                }
            }
        }
        for (int chunkZ = -32; chunkZ <= 32; chunkZ++) {
            for (int chunkX = -32; chunkX <= 32; chunkX++) {
                if (DistrictWorldFeatures.isHillVillageCandidate(
                        TEST_SEED, chunkX, chunkZ)) {
                    cottages++;
                }
            }
        }
        helper.assertTrue(treeAnchors >= 100,
                "border hills do not contain enough deterministic tree anchors");
        helper.assertTrue(cottages >= 20 && cottages <= 300,
                "border hill cottage frequency is not occasional: " + cottages);

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

    public static void arnisPatchSelection(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        int expectedAtlasCount = District.values().length * ARNIS_ZONES.size();
        int expectedPatchCount = expectedAtlasCount * ARNIS_TILES_PER_ATLAS;
        helper.assertTrue(District.values().length == 26
                        && expectedAtlasCount == 52 && expectedPatchCount == 3_328,
                "Arnis coverage contract must remain 26 districts × 2 zones × 64 tiles");
        helper.assertTrue(ArnisPatchLibrary.PATCHES.size() == expectedPatchCount,
                "runtime index must contain 3,328 audited A-Z Arnis tiles, found "
                        + ArnisPatchLibrary.PATCHES.size());
        helper.assertTrue(ArnisPatchLibrary.atlasCount() == expectedAtlasCount,
                "runtime index must contain exactly 52 district-zone atlases, found "
                        + ArnisPatchLibrary.atlasCount());

        int loadedAtlasTemplates = 0;
        Set<Integer> reflectionModes = new HashSet<>();
        boolean selectedAtNegativeCoordinate = false;
        boolean checkedRealConnector = false;
        for (District district : District.values()) {
            String districtPrefix = district.code().toLowerCase(Locale.ROOT) + "/";
            long districtPatches = ArnisPatchLibrary.PATCHES.stream()
                    .filter(patch -> patch.district() == district).count();
            helper.assertTrue(districtPatches == ARNIS_TILES_PER_ATLAS * ARNIS_ZONES.size(),
                    district + " must own exactly 128 Arnis tiles, found " + districtPatches);
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
                                    && patch.surfaceOffset() >= 0 && patch.sha256().length() == 64,
                            patch.catalogId() + " has invalid audited structure metadata");
                    catalogIds.add(patch.catalogId());
                }
                helper.assertTrue(ArnisPatchLibrary.zoneAtlasCount(district, zone) == 1,
                        district + " " + zone + " must resolve to exactly one coherent atlas");
                helper.assertTrue(catalogIds.size() == ARNIS_TILES_PER_ATLAS,
                        district + " " + zone + " is not a complete 8x8 atlas");
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
        for (int y = NeonCityGenerator.CITY_GROUND_Y + 1; y <= topY; y++) {
            helper.assertTrue(helper.getLevel().isEmptyBlock(
                            new BlockPos(column.getX(), y, column.getZ())),
                    "non-Arnis fallback placed a tower block at y=" + y);
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
