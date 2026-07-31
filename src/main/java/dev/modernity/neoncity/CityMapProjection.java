package dev.modernity.neoncity;

/** Pure world/texture projection shared by the map renderer and regression tests. */
public final class CityMapProjection {
    private static final int MARGIN = 384;
    private static final int EXTENT_STEP = 256;

    private CityMapProjection() {
    }

    public static int extent(MegacityLayout layout) {
        int required = 0;
        for (MegacityLayout.Node node : layout.nodes()) {
            required = Math.max(required, Math.abs(node.x()) + node.radiusX());
            required = Math.max(required, Math.abs(node.z()) + node.radiusZ());
        }
        required = Math.max(
                required,
                UCorpPortGeneration.plan(layout).maximumAbsoluteCoordinate());
        return Math.ceilDiv(required + MARGIN, EXTENT_STEP) * EXTENT_STEP;
    }

    public static double worldToUnit(double coordinate, int extent) {
        return (coordinate + extent) / (extent * 2.0);
    }

    public static double unitToWorld(double unit, int extent) {
        return unit * extent * 2.0 - extent;
    }

    public static double clampCenter(double center, int extent, double zoom) {
        double visibleHalf = extent / Math.max(1.0, zoom);
        double limit = Math.max(0.0, extent - visibleHalf);
        return Math.max(-limit, Math.min(limit, center));
    }
}
