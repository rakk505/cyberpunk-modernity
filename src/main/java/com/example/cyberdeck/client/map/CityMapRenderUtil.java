package com.example.cyberdeck.client.map;

import dev.modernity.neoncity.CityRoutePlanner;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
// MerchantMarkerClient is in this same package (com.example.cyberdeck.client.map).

/** Small clipped map primitives shared by the full map and the HUD minimap. */
public final class CityMapRenderUtil {
    public static final int ROUTE_COLOR = 0xFFFFC54A;
    public static final int ROUTE_SHADOW = 0xE005090C;
    public static final int PLAYER_COLOR = 0xFF45F5E6;

    private CityMapRenderUtil() {
    }

    public static void drawRoute(
            GuiGraphicsExtractor graphics,
            CityMapViewport viewport,
            List<CityRoutePlanner.Point> points) {
        drawRoutePass(graphics, viewport, points, 1, ROUTE_SHADOW);
        drawRoutePass(graphics, viewport, points, 0, ROUTE_COLOR);
    }

    private static void drawRoutePass(
            GuiGraphicsExtractor graphics,
            CityMapViewport viewport,
            List<CityRoutePlanner.Point> points,
            int radius,
            int color) {
        for (int index = 1; index < points.size(); index++) {
            CityRoutePlanner.Point first = points.get(index - 1);
            CityRoutePlanner.Point second = points.get(index);
            double[] clipped = clip(
                    viewport,
                    viewport.screenX(first.x()), viewport.screenY(first.z()),
                    viewport.screenX(second.x()), viewport.screenY(second.z()));
            if (clipped == null) continue;
            drawLine(graphics,
                    (int) Math.round(clipped[0]), (int) Math.round(clipped[1]),
                    (int) Math.round(clipped[2]), (int) Math.round(clipped[3]),
                    radius, color);
        }
    }

    /** Draws a single merchant marker (small amber house glyph) at a screen position. */
    public static void drawMerchantMarker(GuiGraphicsExtractor graphics, int x, int y) {
        // Drop-shadow diamond behind the roof for readability over bright map tiles.
        diamond(graphics, x, y - 1, 5, MerchantMarkerClient.MERCHANT_SHADOW);
        // Roof triangle.
        diamond(graphics, x, y - 2, 3, MerchantMarkerClient.MERCHANT_COLOR);
        // Body.
        graphics.fill(x - 2, y, x + 3, y + 4, MerchantMarkerClient.MERCHANT_COLOR);
        graphics.fill(x - 1, y + 1, x + 2, y + 3, MerchantMarkerClient.MERCHANT_SHADOW);
    }

    /** Draws merchant markers on the rotating HUD minimap, clipped to the viewport. */
    public static void drawMerchantMarkers(
            GuiGraphicsExtractor graphics,
            CityMapViewport viewport,
            java.util.List<MerchantMarkerClient.Marker> markers) {
        for (MerchantMarkerClient.Marker marker : markers) {
            int x = viewport.screenX(marker.x());
            int y = viewport.screenY(marker.z());
            if (viewport.contains(x, y)) {
                drawMerchantMarker(graphics, x, y);
            }
        }
    }

    public static void drawWaypoint(
            GuiGraphicsExtractor graphics,
            CityMapViewport viewport,
            CityMapNavigationClient.Waypoint waypoint) {
        int x = viewport.screenX(waypoint.x());
        int y = viewport.screenY(waypoint.z());
        if (viewport.contains(x, y)) {
            diamond(graphics, x, y - 2, 7, ROUTE_SHADOW);
            diamond(graphics, x, y - 2, 5, ROUTE_COLOR);
            diamond(graphics, x, y - 2, 2, 0xFF1B1105);
            graphics.fill(x - 1, y + 3, x + 2, y + 9, ROUTE_COLOR);
        } else {
            drawOffscreenWaypoint(graphics, viewport, x, y);
        }
    }

    public static void drawPlayer(
            GuiGraphicsExtractor graphics,
            CityMapViewport viewport,
            double worldX,
            double worldZ,
            float yaw) {
        int x = viewport.screenX(worldX);
        int y = viewport.screenY(worldZ);
        if (!viewport.contains(x, y)) return;
        graphics.fill(x - 6, y - 1, x + 7, y + 2, 0xFF031014);
        graphics.fill(x - 1, y - 6, x + 2, y + 7, 0xFF031014);
        graphics.fill(x - 5, y, x + 6, y + 1, PLAYER_COLOR);
        graphics.fill(x, y - 5, x + 1, y + 6, PLAYER_COLOR);
        double radians = Math.toRadians(yaw);
        int headingX = x + (int) Math.round(-Math.sin(radians) * 9.0);
        int headingY = y + (int) Math.round(Math.cos(radians) * 9.0);
        drawLine(graphics, x, y, headingX, headingY, 1, 0xFF031014);
        drawLine(graphics, x, y, headingX, headingY, 0, PLAYER_COLOR);
    }

    private static void drawOffscreenWaypoint(
            GuiGraphicsExtractor graphics, CityMapViewport viewport, int targetX, int targetY) {
        double centerX = viewport.x() + viewport.width() * 0.5;
        double centerY = viewport.y() + viewport.height() * 0.5;
        double dx = targetX - centerX;
        double dy = targetY - centerY;
        if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) return;
        double horizontal = (viewport.width() * 0.5 - 8.0) / Math.max(0.001, Math.abs(dx));
        double vertical = (viewport.height() * 0.5 - 8.0) / Math.max(0.001, Math.abs(dy));
        double scale = Math.min(horizontal, vertical);
        int x = (int) Math.round(centerX + dx * scale);
        int y = (int) Math.round(centerY + dy * scale);
        diamond(graphics, x, y, 5, ROUTE_SHADOW);
        diamond(graphics, x, y, 3, ROUTE_COLOR);
    }

    private static double[] clip(
            CityMapViewport viewport, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double[] range = {0.0, 1.0};
        if (!clipTest(-dx, x0 - viewport.x(), range)
                || !clipTest(dx, viewport.right() - 1.0 - x0, range)
                || !clipTest(-dy, y0 - viewport.y(), range)
                || !clipTest(dy, viewport.bottom() - 1.0 - y0, range)) {
            return null;
        }
        return new double[] {
                x0 + range[0] * dx,
                y0 + range[0] * dy,
                x0 + range[1] * dx,
                y0 + range[1] * dy
        };
    }

    private static boolean clipTest(double direction, double distance, double[] range) {
        if (Math.abs(direction) < 1.0E-9) return distance >= 0.0;
        double ratio = distance / direction;
        if (direction < 0.0) {
            if (ratio > range[1]) return false;
            range[0] = Math.max(range[0], ratio);
        } else {
            if (ratio < range[0]) return false;
            range[1] = Math.min(range[1], ratio);
        }
        return true;
    }

    private static void drawLine(
            GuiGraphicsExtractor graphics,
            int startX,
            int startY,
            int endX,
            int endY,
            int radius,
            int color) {
        int dx = Math.abs(endX - startX);
        int stepX = startX < endX ? 1 : -1;
        int dy = -Math.abs(endY - startY);
        int stepY = startY < endY ? 1 : -1;
        int error = dx + dy;
        int x = startX;
        int y = startY;
        while (true) {
            graphics.fill(x - radius, y - radius, x + radius + 1, y + radius + 1, color);
            if (x == endX && y == endY) break;
            int doubled = error * 2;
            if (doubled >= dy) {
                error += dy;
                x += stepX;
            }
            if (doubled <= dx) {
                error += dx;
                y += stepY;
            }
        }
    }

    private static void diamond(
            GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, int color) {
        for (int offset = -radius; offset <= radius; offset++) {
            int half = radius - Math.abs(offset);
            graphics.fill(
                    centerX - half, centerY + offset,
                    centerX + half + 1, centerY + offset + 1, color);
        }
    }
}
