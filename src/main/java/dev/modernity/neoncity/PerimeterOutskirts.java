package dev.modernity.neoncity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Finite terrain bands outside the fixed north, east, and west district edges. */
public final class PerimeterOutskirts {
    public static final int BAND_WIDTH = 640;
    private static final int EDGE_OVERLAP = 144;
    private static final long EXTRACTION_SALT = 0x584D494E45454447L;
    private static final Map<Long, Plan> PLAN_CACHE = new ConcurrentHashMap<>();

    public enum Feature {
        NONE,
        NORTH_TUNDRA,
        WEST_LAND,
        EAST_LAND,
        EAST_EXTRACTION
    }

    public record Plan(
            long layoutSeed,
            int northEdge,
            int southEdge,
            int westEdge,
            int eastEdge,
            MegacityLayout.Node extractionDistrict
    ) {
        public Feature featureAt(int worldX, int worldZ) {
            if (worldZ >= northEdge - BAND_WIDTH
                    && worldZ < northEdge + EDGE_OVERLAP
                    && worldX >= westEdge - BAND_WIDTH
                    && worldX <= eastEdge + BAND_WIDTH) {
                return Feature.NORTH_TUNDRA;
            }
            if (worldZ < northEdge || worldZ > southEdge) {
                return Feature.NONE;
            }
            if (worldX >= westEdge - BAND_WIDTH && worldX < westEdge + EDGE_OVERLAP) {
                return Feature.WEST_LAND;
            }
            if (worldX <= eastEdge + BAND_WIDTH && worldX > eastEdge - EDGE_OVERLAP) {
                return isExtractionAt(worldX, worldZ)
                        ? Feature.EAST_EXTRACTION
                        : Feature.EAST_LAND;
            }
            return Feature.NONE;
        }

        public boolean isTundraBiomeAt(int worldX, int worldZ) {
            return featureAt(worldX, worldZ) == Feature.NORTH_TUNDRA;
        }

        public boolean chunkIntersectsManagedArea(int chunkX, int chunkZ) {
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            int[] offsets = {0, 8, 15};
            for (int dz : offsets) {
                for (int dx : offsets) {
                    if (featureAt(minX + dx, minZ + dz) != Feature.NONE) {
                        return true;
                    }
                }
            }
            return false;
        }

        public int maximumAbsoluteCoordinate() {
            return Math.max(
                    Math.max(Math.abs(northEdge - BAND_WIDTH), Math.abs(southEdge)),
                    Math.max(Math.abs(westEdge - BAND_WIDTH), Math.abs(eastEdge + BAND_WIDTH)));
        }

        private boolean isExtractionAt(int worldX, int worldZ) {
            double vertical = Math.abs(worldZ - extractionDistrict.z())
                    / (double) extractionDistrict.radiusZ();
            if (vertical > 1.16) {
                return false;
            }
            double outward = clamp01(
                    (worldX - (eastEdge - EDGE_OVERLAP))
                            / (double) (BAND_WIDTH + EDGE_OVERLAP));
            double latitude = clamp01((1.16 - vertical) / 0.34);
            double density = (1.0 - outward) * latitude;
            int cellX = Math.floorDiv(worldX, 24);
            int cellZ = Math.floorDiv(worldZ, 24);
            long hash = MegacityLayout.mix(layoutSeed ^ EXTRACTION_SALT, cellX, cellZ);
            return unit(hash) < density * 0.88;
        }
    }

    private PerimeterOutskirts() {
    }

    public static Plan plan(MegacityLayout layout) {
        if (PLAN_CACHE.size() > 64) {
            PLAN_CACHE.clear();
        }
        return PLAN_CACHE.computeIfAbsent(layout.seed(), ignored -> createPlan(layout));
    }

    static void clearCache() {
        PLAN_CACHE.clear();
    }

    private static Plan createPlan(MegacityLayout layout) {
        int north = Integer.MAX_VALUE;
        int south = Integer.MIN_VALUE;
        int west = Integer.MAX_VALUE;
        int east = Integer.MIN_VALUE;
        for (MegacityLayout.Node node : layout.nodes()) {
            north = Math.min(north, node.z() - node.radiusZ());
            south = Math.max(south, node.z() + node.radiusZ());
            west = Math.min(west, node.x() - node.radiusX());
            east = Math.max(east, node.x() + node.radiusX());
        }
        return new Plan(
                layout.seed(), north, south, west, east, layout.node(District.X_CORP));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }
}
