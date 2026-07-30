package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.client.map.CityMapRenderUtil;
import com.example.cyberdeck.client.map.CityMapViewport;
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

/** North-up live city minimap centered on the player during normal gameplay. */
public final class CityMinimapOverlay implements GuiLayer {
    private static final double WORLD_SPAN = 1_500.0;
    private static final int BACKGROUND = 0xE8050B10;
    private static final int BORDER = 0xFF277E84;
    private static final int CYAN = 0xFF45F5E6;
    private static final int TEXT_DIM = 0xFF789994;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        CityMapNavigationClient.Snapshot snapshot = CityMapNavigationClient.snapshot();
        if (minecraft.player == null || minecraft.level == null
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

        int size = Math.max(72, Math.min(118, graphics.guiWidth() / 5));
        int left = 8;
        int top = 8;
        int mapTop = top + 16;
        CityMapViewport viewport = new CityMapViewport(
                left, mapTop, size, size,
                centerX, centerZ, WORLD_SPAN, WORLD_SPAN);

        graphics.fill(left - 2, top - 2, left + size + 2, mapTop + size + 17, BACKGROUND);
        graphics.outline(left - 2, top - 2, size + 4, size + 19, BORDER);
        MegacityLayout.Location location = snapshot.layout().locateDistrict(
                (int) Math.round(playerX), (int) Math.round(playerZ));
        String district = location.insideCity() ? location.district().code() : "OUT";
        graphics.text(minecraft.font, "CITY // " + district, left + 3, top + 4, CYAN, false);
        graphics.text(minecraft.font, "N", left + size - 9, top + 4, TEXT_DIM, false);

        double unitSpan = WORLD_SPAN / (extent * 2.0);
        double centerU = CityMapProjection.worldToUnit(centerX, extent);
        double centerV = CityMapProjection.worldToUnit(centerZ, extent);
        graphics.enableScissor(left, mapTop, left + size, mapTop + size);
        graphics.blit(
                CityMapTextureCache.TEXTURE,
                left,
                mapTop,
                left + size,
                mapTop + size,
                (float) (centerU - unitSpan * 0.5),
                (float) (centerU + unitSpan * 0.5),
                (float) (centerV - unitSpan * 0.5),
                (float) (centerV + unitSpan * 0.5));
        CityMapRenderUtil.drawRoute(graphics, viewport, CityMapNavigationClient.route().points());
        if (CityMapNavigationClient.waypoint() != null) {
            CityMapRenderUtil.drawWaypoint(
                    graphics, viewport, CityMapNavigationClient.waypoint());
        }
        CityMapRenderUtil.drawPlayer(graphics, viewport, playerX, playerZ, yaw);
        graphics.disableScissor();
        graphics.outline(left, mapTop, size, size, BORDER);

        String footer = CityMapNavigationClient.waypoint() == null
                ? "NAV // STANDBY"
                : String.format(Locale.ROOT, "NAV // %.0fm",
                        CityMapNavigationClient.distanceToWaypoint(playerX, playerZ));
        graphics.text(minecraft.font, footer, left + 3, mapTop + size + 5,
                CityMapNavigationClient.waypoint() == null ? TEXT_DIM : CityMapRenderUtil.ROUTE_COLOR,
                false);
    }
}
