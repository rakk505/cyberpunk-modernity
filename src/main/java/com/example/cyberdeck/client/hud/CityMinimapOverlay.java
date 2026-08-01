package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.client.map.CityMapRenderUtil;
import com.example.cyberdeck.client.map.CityMapViewport;
import com.example.cyberdeck.client.map.MerchantMarkerClient;
import com.example.cyberdeck.client.map.MinimapGeometry;
import com.example.cyberdeck.client.screen.CityMapTextureCache;
import dev.modernity.neoncity.CityMapProjection;
import dev.modernity.neoncity.MegacityLayout;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * Heading-locked live city minimap centered on the player during normal gameplay.
 *
 * <p>The map rotates with the player's orientation so the player arrow always points up, and a
 * compass ring around the map renders live N/S/E/W markers. Visibility and merchant markers can be
 * toggled at runtime via {@link MinimapClientState}.</p>
 */
public final class CityMinimapOverlay implements GuiLayer {
    private static final double WORLD_SPAN = 1_200.0;
    private static final int BACKGROUND = 0xE8050B10;
    private static final int BORDER = 0xFF277E84;
    private static final int CYAN = 0xFF45F5E6;
    private static final int TEXT_DIM = 0xFF789994;
    private static final int COMPASS = 0xFFE5F2F0;
    private static final int COMPASS_DIM = 0xFF789994;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        CityMapNavigationClient.Snapshot snapshot = CityMapNavigationClient.snapshot();
        if (!MinimapClientState.minimapVisible()
                || minecraft.player == null || minecraft.level == null
                || !Level.OVERWORLD.equals(minecraft.level.dimension())
                || minecraft.gui.hud.isHidden()
                || minecraft.gui.screen() != null
                || minecraft.getDebugOverlay().showDebugScreen()
                || snapshot == null
                || !CityMapTextureCache.readyFor(
                        snapshot.layoutSeed(), snapshot.fingerprint())) {
            return;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        double playerX = Mth.lerp(partialTick, minecraft.player.xOld, minecraft.player.getX());
        double playerZ = Mth.lerp(partialTick, minecraft.player.zOld, minecraft.player.getZ());
        float yaw = minecraft.player.getYRot(partialTick);
        int extent = snapshot.extent();
        double minimapZoom = extent * 2.0 / WORLD_SPAN;
        double centerX = CityMapProjection.clampCenter(playerX, extent, minimapZoom);
        double centerZ = CityMapProjection.clampCenter(playerZ, extent, minimapZoom);

        // Shrunk clamp so the minimap does not obstruct the player's field of view.
        int size = Math.max(56, Math.min(88, graphics.guiWidth() / 7));
        int right = graphics.guiWidth() - 8;
        int left = right - size;
        int top = 8;
        int mapTop = top + 16;
        int mapCenterX = left + size / 2;
        int mapCenterY = mapTop + size / 2;

        graphics.fill(left - 2, top - 2, right + 2, mapTop + size + 17, BACKGROUND);
        graphics.outline(left - 2, top - 2, size + 4, size + 19, BORDER);
        MegacityLayout.Location location = snapshot.layout().locateDistrict(
                (int) Math.round(playerX), (int) Math.round(playerZ));
        String district = location.insideCity() ? location.district().code() : "OUT";
        graphics.text(minecraft.font, "CITY // " + district, left + 3, top + 4, CYAN, false);

        CityMapViewport viewport = new CityMapViewport(
                left, mapTop, size, size,
                centerX, centerZ, WORLD_SPAN, WORLD_SPAN);
        double unitSpan = WORLD_SPAN / (extent * 2.0);
        double centerU = CityMapProjection.worldToUnit(centerX, extent);
        double centerV = CityMapProjection.worldToUnit(centerZ, extent);
        float rotation = (float) Math.toRadians(-yaw);

        // Rotate the entire map content about the map center so the player faces up.
        //
        // The visible map is the axis-aligned scissor square [left, mapTop, size, size]. A square
        // blit of exactly `size` rotates its corners outside that square, so at intermediate yaw
        // angles the axis-aligned scissor clips the corners and leaves empty triangles inside the
        // ring -- read on screen as the map skewing/shearing as the player turns. To fix this we
        // rotate (rigidly, no non-uniform scale) an OVERSIZED blit quad that circumscribes the
        // visible square. Scaling the destination quad and the sampled UV span by the same factor
        // (the diagonal ratio) keeps pixels-per-world identical, so route/marker/waypoint drawing
        // below -- which uses the unscaled `viewport` -- still lines up exactly.
        int halfQuad = MinimapGeometry.coveringHalfSize(size);
        double halfUv = MinimapGeometry.coveringHalfSpan(unitSpan, size);
        // Register the screen-space clip before rotating. GuiGraphicsExtractor transforms a new
        // scissor through the current pose using only two opposite corners; under rotation that
        // makes its width collapse near 45 degrees and produces the apparent squeeze/shear.
        graphics.enableScissor(left, mapTop, left + size, mapTop + size);
        graphics.pose().pushMatrix();
        graphics.pose().rotateAbout(rotation, mapCenterX, mapCenterY);
        graphics.blit(
                CityMapTextureCache.TEXTURE,
                mapCenterX - halfQuad,
                mapCenterY - halfQuad,
                mapCenterX + halfQuad,
                mapCenterY + halfQuad,
                (float) (centerU - halfUv),
                (float) (centerU + halfUv),
                (float) (centerV - halfUv),
                (float) (centerV + halfUv));
        CityMapRenderUtil.drawRoute(graphics, viewport, CityMapNavigationClient.route().points());
        if (MinimapClientState.merchantMarkersVisible()) {
            CityMapRenderUtil.drawMerchantMarkers(
                    graphics, viewport, MerchantMarkerClient.markers(snapshot.layout()));
        }
        if (CityMapNavigationClient.waypoint() != null) {
            CityMapRenderUtil.drawWaypoint(
                    graphics, viewport, CityMapNavigationClient.waypoint());
        }
        graphics.pose().popMatrix();
        graphics.disableScissor();

        // The player arrow stays upright at the center because the map beneath it is rotated.
        graphics.fill(mapCenterX - 6, mapCenterY - 1, mapCenterX + 7, mapCenterY + 2, 0xFF031014);
        graphics.fill(mapCenterX - 1, mapCenterY - 6, mapCenterX + 2, mapCenterY + 7, 0xFF031014);
        graphics.fill(mapCenterX - 5, mapCenterY, mapCenterX + 6, mapCenterY + 1,
                CityMapRenderUtil.PLAYER_COLOR);
        graphics.fill(mapCenterX, mapCenterY - 5, mapCenterX + 1, mapCenterY + 6,
                CityMapRenderUtil.PLAYER_COLOR);
        graphics.fill(mapCenterX - 1, mapCenterY - 9, mapCenterX + 2, mapCenterY - 4,
                CityMapRenderUtil.PLAYER_COLOR);

        graphics.outline(left, mapTop, size, size, BORDER);
        drawCompass(graphics, minecraft, mapCenterX, mapCenterY, size, yaw);

        String footer = CityMapNavigationClient.waypoint() == null
                ? "NAV // STANDBY"
                : String.format(Locale.ROOT, "NAV // %.0fm",
                        CityMapNavigationClient.distanceToWaypoint(playerX, playerZ));
        graphics.text(minecraft.font, footer, left + 3, mapTop + size + 5,
                CityMapNavigationClient.waypoint() == null ? TEXT_DIM : CityMapRenderUtil.ROUTE_COLOR,
                false);
    }

    /** Draws the rotating N/S/E/W compass letters just inside the minimap ring. */
    private void drawCompass(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            int centerX,
            int centerY,
            int size,
            float yaw) {
        double radius = size / 2.0 - 6.0;
        // World directions map to on-screen angles (measured clockwise from up) after the map
        // rotates by -yaw: north sits at -yaw, and each other cardinal is 90 degrees apart.
        drawCompassLetter(graphics, minecraft, "N", centerX, centerY, radius, -yaw, COMPASS);
        drawCompassLetter(graphics, minecraft, "E", centerX, centerY, radius, -yaw + 90.0, COMPASS_DIM);
        drawCompassLetter(graphics, minecraft, "S", centerX, centerY, radius, -yaw + 180.0, COMPASS_DIM);
        drawCompassLetter(graphics, minecraft, "W", centerX, centerY, radius, -yaw + 270.0, COMPASS_DIM);
    }

    private void drawCompassLetter(
            GuiGraphicsExtractor graphics,
            Minecraft minecraft,
            String letter,
            int centerX,
            int centerY,
            double radius,
            double angleDegrees,
            int color) {
        double radians = Math.toRadians(angleDegrees);
        int x = centerX + (int) Math.round(Math.sin(radians) * radius);
        int y = centerY - (int) Math.round(Math.cos(radians) * radius);
        int textX = x - minecraft.font.width(letter) / 2;
        int textY = y - 4;
        graphics.text(minecraft.font, letter, textX + 1, textY + 1, 0xFF031014, false);
        graphics.text(minecraft.font, letter, textX, textY, color, false);
    }
}
