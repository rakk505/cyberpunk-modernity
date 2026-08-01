package com.example.cyberdeck.client.map;

/** Pure minimap rotation geometry shared by the HUD and server-safe regression tests. */
public final class MinimapGeometry {
    private static final double COVER_SCALE = Math.sqrt(2.0);

    private MinimapGeometry() {
    }

    /** Half-size of a square that covers the viewport at every rotation angle. */
    public static int coveringHalfSize(int viewportSize) {
        return (int) Math.ceil(Math.max(0, viewportSize) * 0.5 * COVER_SCALE);
    }

    /** Matching UV half-span; using the same scale keeps pixels-per-world uniform. */
    public static double coveringHalfSpan(double viewportSpan, int viewportSize) {
        if (viewportSize <= 0) {
            return 0.0;
        }
        return Math.max(0.0, viewportSpan) * coveringHalfSize(viewportSize) / viewportSize;
    }

    /** Axis-aligned half-extent required after rotating a square by {@code degrees}. */
    public static double requiredHalfExtent(int viewportSize, double degrees) {
        double radians = Math.toRadians(degrees);
        return Math.max(0, viewportSize) * 0.5
                * (Math.abs(Math.cos(radians)) + Math.abs(Math.sin(radians)));
    }

    /**
     * Width produced when Minecraft's two-corner axis-aligned scissor transform is applied after
     * rotating a square. This is diagnostic only: minimap scissors must be registered before the
     * content pose is rotated.
     */
    public static double rotatedTwoCornerScissorWidth(int viewportSize, double degrees) {
        double radians = Math.toRadians(degrees);
        return Math.max(0, viewportSize) * Math.abs(Math.cos(radians) - Math.sin(radians));
    }
}
