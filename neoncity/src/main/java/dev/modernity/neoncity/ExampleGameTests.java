package dev.modernity.neoncity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import net.minecraft.gametest.framework.GameTestHelper;

/** Pure regression tests for city topology and cultural skyline rules. */
public final class ExampleGameTests {
    private static final long TEST_SEED = 0x4E454F4E43495459L;

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
        return plan.portals().stream().filter(portal -> portal.side() == side)
                .findFirst().orElseThrow();
    }

    public static void districtCoverage(GameTestHelper helper) {
        EnumSet<NeonCityGenerator.District> found = EnumSet.noneOf(NeonCityGenerator.District.class);
        for (int z = -760; z <= 760; z += 32) {
            for (int x = -760; x <= 760; x += 32) {
                found.add(NeonCityGenerator.districtAt(x, z));
            }
        }
        helper.assertTrue(found.equals(EnumSet.allOf(NeonCityGenerator.District.class)),
                "origin metropolis must expose every district, found " + found);
        helper.assertTrue(NeonCityGenerator.districtAt(0, 0)
                        == NeonCityGenerator.District.CROWN_CORE,
                "world origin must be the monumental city centre");
        helper.succeed();
    }

    public static void organicRoads(GameTestHelper helper) {
        int foundRings = 0;
        int minRadius = Integer.MAX_VALUE;
        int maxRadius = Integer.MIN_VALUE;
        for (int ray = 0; ray < 32; ray++) {
            double angle = ray * Math.PI * 2.0 / 32.0;
            int hit = -1;
            for (int radius = 380; radius <= 580; radius += 2) {
                int x = (int) Math.round(Math.cos(angle) * radius);
                int z = (int) Math.round(Math.sin(angle) * radius);
                if (NeonCityGenerator.isExpressway(x, z)) {
                    hit = radius;
                    break;
                }
            }
            if (hit >= 0) {
                foundRings++;
                minRadius = Math.min(minRadius, hit);
                maxRadius = Math.max(maxRadius, hit);
            }
        }
        helper.assertTrue(foundRings >= 28, "raised expressway must wrap most of the centre");
        helper.assertTrue(maxRadius - minRadius >= 60,
                "expressway radius must visibly warp instead of forming a sterile circle/grid");

        EnumSet<NeonCityGenerator.RoadClass> roads = EnumSet.noneOf(NeonCityGenerator.RoadClass.class);
        for (int z = -620; z <= 620; z += 19) {
            for (int x = -620; x <= 620; x += 19) {
                roads.add(NeonCityGenerator.roadAt(x, z));
            }
        }
        helper.assertTrue(roads.contains(NeonCityGenerator.RoadClass.ARTERIAL), "missing curved arterials");
        helper.assertTrue(roads.contains(NeonCityGenerator.RoadClass.LOCAL_STREET), "missing local streets");
        helper.assertTrue(roads.contains(NeonCityGenerator.RoadClass.SERVICE_ALLEY), "missing DFS alleys");
        helper.assertTrue(roads.contains(NeonCityGenerator.RoadClass.EXPRESSWAY), "missing expressway");
        helper.assertTrue(roads.contains(NeonCityGenerator.RoadClass.ELEVATED_RAIL), "missing rail spine");
        helper.succeed();
    }

    public static void skylineHierarchy(GameTestHelper helper) {
        Map<NeonCityGenerator.District, Integer> maxima =
                new EnumMap<>(NeonCityGenerator.District.class);
        for (int z = -720; z <= 720; z += 11) {
            for (int x = -720; x <= 720; x += 11) {
                NeonCityGenerator.UrbanSample sample = NeonCityGenerator.sample(x, z);
                maxima.merge(sample.district(), sample.buildingHeight(), Math::max);
            }
        }
        helper.assertTrue(maxima.getOrDefault(NeonCityGenerator.District.CROWN_CORE, 0) >= 260,
                "city centre lacks monumental towers: " + maxima);
        helper.assertTrue(maxima.getOrDefault(NeonCityGenerator.District.LONGWEI_HARBOR, 0) >= 210,
                "Chinese harbor lacks landmark corporate towers: " + maxima);
        helper.assertTrue(maxima.getOrDefault(NeonCityGenerator.District.HANEUL_TECH, 0) >= 180,
                "Korean tech quarter lacks corporate towers: " + maxima);
        helper.assertTrue(maxima.getOrDefault(NeonCityGenerator.District.KAIROCHO, 0) >= 90,
                "Japanese neon district lacks vertical mixed-use massing: " + maxima);
        helper.assertTrue(maxima.getOrDefault(NeonCityGenerator.District.FOUNDRY_BELT, 999) <= 58,
                "industrial zone should stay broad and low: " + maxima);
        helper.succeed();
    }

    public static void negativeDeterminism(GameTestHelper helper) {
        int[][] points = {{-1, -1}, {-1537, -1537}, {-4097, 2111}, {8192, -6000}};
        for (int[] point : points) {
            NeonCityGenerator.UrbanSample first = NeonCityGenerator.sample(point[0], point[1]);
            NeonCityGenerator.UrbanSample second = NeonCityGenerator.sample(point[0], point[1]);
            helper.assertTrue(first.equals(second), "sample changed at negative/global coordinate");
            NeonCityGenerator.CityCenter center = NeonCityGenerator.nearestCenter(point[0], point[1]);
            helper.assertTrue(Math.abs((long) point[0] - center.x()) < NeonCityGenerator.CITY_SPACING,
                    "nearest centre X is implausibly distant");
            helper.assertTrue(Math.abs((long) point[1] - center.z()) < NeonCityGenerator.CITY_SPACING,
                    "nearest centre Z is implausibly distant");
        }
        helper.succeed();
    }
}
