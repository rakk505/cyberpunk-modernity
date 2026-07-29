package dev.modernity.neoncity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTestHelper;

/** Pure regression tests for finite megacity topology, culture, and massing. */
public final class ExampleGameTests {
    private static final long TEST_SEED = 0x4E454F4E43495459L;
    private static final int RADIAL_STEPS = 72;

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
        EnumSet<District.Architecture> architectures = EnumSet.noneOf(District.Architecture.class);
        for (District district : District.values()) {
            helper.assertTrue(codes.add(district.code()) && district.code().length() == 1,
                    "district code must be one unique letter: " + district);
            helper.assertTrue(labels.add(district.label()), "duplicate district label: " + district.label());
            helper.assertTrue(!district.flavor().isBlank(), "missing cultural flavor for " + district);
            helper.assertTrue(architectures.add(district.architecture()),
                    "architecture must distinguish each culture: " + district.architecture());
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

        MegacityLayout layout = MegacityLayout.create(TEST_SEED);
        EnumSet<MegacityLayout.Zone> allZones = EnumSet.of(MegacityLayout.Zone.WILDERNESS);
        for (MegacityLayout.Node node : layout.nodes()) {
            EnumSet<MegacityLayout.Zone> districtZones = EnumSet.noneOf(MegacityLayout.Zone.class);
            for (double radius = 0.0; radius <= 1.12; radius += 0.07) {
                for (int angle = 0; angle < RADIAL_STEPS; angle++) {
                    int[] point = ellipsePoint(node, radius, angle, RADIAL_STEPS);
                    MegacityLayout.Location location = layout.locate(point[0], point[1]);
                    allZones.add(location.zone());
                    if (location.district() == node.district()) districtZones.add(location.zone());
                }
            }
            helper.assertTrue(districtZones.contains(MegacityLayout.Zone.NEST),
                    node.district() + " has no premium Nest core");
            helper.assertTrue(districtZones.contains(MegacityLayout.Zone.BACKSTREETS),
                    node.district() + " has no Backstreets belt");
        }
        helper.assertTrue(allZones.equals(EnumSet.allOf(MegacityLayout.Zone.class)),
                "layout sampling did not expose every zone: " + allZones);
        helper.succeed();
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

    public static void arnisPatchSelection(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        ArnisPatchLibrary.Patch patch = ArnisPatchLibrary.SHINJUKU_CORE;
        helper.assertTrue(ArnisPatchLibrary.PATCHES.size() == 1,
                "unexpected unaudited Arnis patch in runtime index");
        helper.assertTrue(patch.district() == District.Z_CORP
                        && patch.sizeX() == 16 && patch.sizeZ() == 16
                        && patch.surfaceOffset() == 132
                        && patch.sha256().length() == 64,
                "Shinjuku patch metadata disagrees with its audited catalog");

        MegacityLayout.Node node = layout.node(District.Z_CORP);
        ArnisPatchLibrary.Placement found = null;
        int radius = Math.max(node.radiusX(), node.radiusZ());
        for (int chunkZ = Math.floorDiv(node.z() - radius, 16);
             chunkZ <= Math.floorDiv(node.z() + radius, 16) && found == null; chunkZ++) {
            for (int chunkX = Math.floorDiv(node.x() - radius, 16);
                 chunkX <= Math.floorDiv(node.x() + radius, 16); chunkX++) {
                var candidate = ArnisPatchLibrary.select(layout, chunkX, chunkZ);
                if (candidate.isPresent()) {
                    found = candidate.get();
                    break;
                }
            }
        }
        helper.assertTrue(found != null, "Z Corp contains no deterministic Arnis patch placement");
        helper.assertTrue(ArnisPatchLibrary.select(layout, found.chunkX(), found.chunkZ())
                        .orElseThrow().equals(found),
                "Arnis selection changed between identical calls");
        int patchCenterX = (found.chunkX() << 4) + 8;
        int patchCenterZ = (found.chunkZ() << 4) + 8;
        helper.assertTrue(layout.locate(patchCenterX, patchCenterZ).district() == District.Z_CORP,
                "Arnis patch escaped its assigned district");
        helper.assertTrue(ArnisPatchLibrary.connectorApproachAt(
                        layout, (found.chunkX() << 4) - 1, (found.chunkZ() << 4) + 3),
                "west Arnis road connector was not stitched into its neighbour");
        helper.assertTrue(ArnisPatchLibrary.connectorApproachAt(
                        layout, ((found.chunkX() + 1) << 4), (found.chunkZ() << 4) + 6),
                "east Arnis road connector was not stitched into its neighbour");
        helper.assertTrue(ArnisPatchLibrary.select(layout, 1000, 1000).isEmpty(),
                "Arnis patch selection leaked into distant wilderness");
        helper.succeed();
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

    /** Retains the old registration name while proving per-district core-to-edge tapering. */
    public static void skylineHierarchy(GameTestHelper helper) {
        NeonCityGenerator.reset();
        MegacityLayout layout = NeonCityGenerator.layout();
        int districtsWithOutskirts = 0;
        for (MegacityLayout.Node node : layout.nodes()) {
            EnumMap<MegacityLayout.Zone, Integer> maxima = new EnumMap<>(MegacityLayout.Zone.class);
            EnumMap<MegacityLayout.Zone, Integer> buildings = new EnumMap<>(MegacityLayout.Zone.class);
            for (double radius = 0.10; radius <= 1.06; radius += 0.025) {
                for (int angle = 0; angle < 96; angle++) {
                    int[] point = ellipsePoint(node, radius, angle, 96);
                    NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(point[0], point[1]);
                    if (sample.district() != node.district() || sample.buildingHeight() <= 0) continue;
                    if (sample.zone() != MegacityLayout.Zone.NEST
                            && sample.zone() != MegacityLayout.Zone.BACKSTREETS
                            && sample.zone() != MegacityLayout.Zone.OUTSKIRTS) continue;
                    maxima.merge(sample.zone(), sample.buildingHeight(), Math::max);
                    buildings.merge(sample.zone(), 1, Integer::sum);
                }
            }
            int nest = maxima.getOrDefault(MegacityLayout.Zone.NEST, 0);
            int backstreets = maxima.getOrDefault(MegacityLayout.Zone.BACKSTREETS, 0);
            int outskirts = maxima.getOrDefault(MegacityLayout.Zone.OUTSKIRTS, 0);
            helper.assertTrue(buildings.getOrDefault(MegacityLayout.Zone.NEST, 0) >= 2,
                    node.district() + " has no sampled Nest buildings");
            helper.assertTrue(buildings.getOrDefault(MegacityLayout.Zone.BACKSTREETS, 0) >= 2,
                    node.district() + " has no sampled Backstreets buildings");
            helper.assertTrue(nest > backstreets,
                    node.district() + " core does not taper into its Backstreets: "
                            + nest + " > " + backstreets);
            if (buildings.getOrDefault(MegacityLayout.Zone.OUTSKIRTS, 0) >= 1) {
                districtsWithOutskirts++;
                helper.assertTrue(backstreets > outskirts,
                        node.district() + " Backstreets do not taper into outskirts: "
                                + backstreets + " > " + outskirts);
            }
        }
        // Dense neighboring blobs can replace an outer belt with border rivers
        // or hills, but the exposed perimeter must still demonstrate tapering.
        helper.assertTrue(districtsWithOutskirts >= District.values().length / 3,
                "too few districts expose a sampled sparse perimeter: " + districtsWithOutskirts);
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
