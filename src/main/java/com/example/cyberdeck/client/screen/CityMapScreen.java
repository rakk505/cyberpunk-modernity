package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.client.CyberdeckClient;
import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.client.map.CityMapRenderUtil;
import com.example.cyberdeck.client.map.CityMapViewport;
import com.example.cyberdeck.client.mission.MissionTrackerClient;
import com.example.cyberdeck.network.OpenCityMapPacket;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.modernity.neoncity.CityMapProjection;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MegacityLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/** Full-screen Project Moon city plan with pan, zoom, player tracking and active missions. */
public final class CityMapScreen extends Screen {
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 5.0;

    private static final int BACKGROUND_TOP = 0xF403090F;
    private static final int BACKGROUND_BOTTOM = 0xFF08060B;
    private static final int SIDEBAR = 0xF20A1016;
    private static final int MAP_BORDER = 0xFF277E84;
    private static final int MAP_INSET = 0xFF02060B;
    private static final int CYAN = 0xFF45F5E6;
    private static final int CYAN_DIM = 0xFF277E84;
    private static final int CYAN_FAINT = 0x4427A6AE;
    private static final int RED = 0xFFFF4058;
    private static final int RED_DIM = 0xFF9A2C3F;
    private static final int AMBER = 0xFFFFC54A;
    private static final int AMBER_DIM = 0xFF8C6A26;
    private static final int TEXT = 0xFFE5F2F0;
    private static final int TEXT_DIM = 0xFF789994;
    private static final int TEXT_DARK = 0xFF385B5B;

    private final OpenCityMapPacket packet;
    private final MegacityLayout cityLayout;
    private final int extent;
    private final List<OpenCityMapPacket.Marker> missions;
    private double centerX;
    private double centerZ;
    private double zoom = MIN_ZOOM;
    private boolean mapPressActive;
    private boolean dragging;
    private boolean pressDoubleClick;
    private double pressX;
    private double pressY;
    private double dragOriginCenterX;
    private double dragOriginCenterZ;
    private boolean showMissions = true;
    private boolean showTransit = true;
    private boolean showDistricts = true;
    private OpenCityMapPacket.Marker selectedMarker;

    private CityMapScreen(OpenCityMapPacket packet) {
        super(Component.translatable("screen.cyberdeck.city_map.title"));
        this.packet = packet;
        this.cityLayout = packet.available()
                ? MegacityLayout.createFromLayoutSeed(packet.layoutSeed())
                : MegacityLayout.create(0L);
        this.extent = CityMapProjection.extent(cityLayout);
        this.missions = packet.markers().stream()
                .filter(marker -> marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION)
                .toList();
        CityMapNavigationClient.Waypoint waypoint = CityMapNavigationClient.waypoint();
        if (waypoint == null) {
            this.selectedMarker = missions.isEmpty() ? null : missions.getFirst();
        } else if (!waypoint.marker()) {
            this.selectedMarker = null;
        } else {
            this.selectedMarker = packet.markers().stream()
                    .filter(marker -> marker.x() == waypoint.x()
                            && marker.z() == waypoint.z()
                            && marker.labelKey().equals(waypoint.labelKey()))
                    .findFirst().orElse(null);
        }
        if (packet.available()) {
            CityMapTextureCache.prepare(packet);
        }
    }

    public static void open(OpenCityMapPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen currentScreen = minecraft.gui.screen();
        if (!packet.forceOpen() && (minecraft.level == null || minecraft.player == null
                || currentScreen != null && !(currentScreen instanceof CityMapScreen))) {
            return;
        }
        minecraft.setScreenAndShow(new CityMapScreen(packet));
    }

    @Override
    public void extractBackground(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
        for (int y = 2; y < height; y += 4) {
            graphics.fill(0, y, width, y + 1, 0x10000000);
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Layout layout = layout();
        renderHeader(graphics, layout, mouseX, mouseY);
        renderMissionBand(graphics, layout, mouseX, mouseY);
        renderMap(graphics, layout, mouseX, mouseY);
        if (layout.rightWidth() > 0) {
            renderDetailsBand(graphics, layout, mouseX, mouseY);
        }
        renderFooter(graphics, layout, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderHeader(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, layout.headerHeight(), 0xE8081016);
        graphics.horizontalLine(0, width - 1, layout.headerHeight() - 1, CYAN_DIM);
        graphics.horizontalLine(0, 110, layout.headerHeight() - 2, CYAN);
        graphics.text(font, "PROJECT MOON // CITY GRID", 14, 10, CYAN, false);
        graphics.text(font, "NIGHT CITY NETWORK", 14, 23, TEXT_DARK, false);

        String location = cursorReadout(layout, mouseX, mouseY);
        graphics.text(font, location, width - 14 - font.width(location), 11, TEXT, false);
        String zoomText = String.format(Locale.ROOT, "ZOOM  %.1fX", zoom);
        graphics.text(font, zoomText, width - 14 - font.width(zoomText), 24, TEXT_DIM, false);
    }

    private void renderMissionBand(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        graphics.fill(0, layout.headerHeight(), layout.leftWidth(), layout.footerTop(), SIDEBAR);
        graphics.verticalLine(layout.leftWidth() - 1, layout.headerHeight(), layout.footerTop(), CYAN_DIM);
        graphics.text(font, "ACTIVE MISSION", 12, layout.headerHeight() + 13, AMBER, false);
        graphics.text(font, String.format(Locale.ROOT, "%02d TRACKED", missions.size()),
                12, layout.headerHeight() + 27, TEXT_DARK, false);
        int rowY = layout.headerHeight() + 48;
        int rowHeight = 32;
        for (int index = 0; index < missions.size(); index++) {
            OpenCityMapPacket.Marker marker = missions.get(index);
            int top = rowY + index * rowHeight;
            if (top + rowHeight > layout.footerTop() - 62) break;
            boolean selected = marker == selectedMarker;
            boolean hovered = mouseX >= 8 && mouseX < layout.leftWidth() - 8
                    && mouseY >= top && mouseY < top + rowHeight - 2;
            graphics.fill(8, top, layout.leftWidth() - 8, top + rowHeight - 2,
                    selected ? 0xDD291C1D : hovered ? 0xCC152326 : 0x9910181D);
            graphics.verticalLine(8, top, top + rowHeight - 3,
                    selected ? AMBER : hovered ? AMBER_DIM : TEXT_DARK);
            District district = district(marker.districtOrdinal());
            String code = district == null ? "?" : district.code();
            graphics.text(font, code, 17, top + 11, selected ? AMBER : RED, false);
            graphics.text(font, elide(markerLabel(marker), layout.leftWidth() - 50),
                    36, top + 6, selected ? TEXT : 0xFFB2C8C5, false);
            graphics.text(font, district == null ? "UNKNOWN" : district.label().toUpperCase(Locale.ROOT),
                    36, top + 17, TEXT_DIM, false);
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }

        int filterY = layout.footerTop() - 52;
        graphics.text(font, "LAYERS", 12, filterY, TEXT_DARK, false);
        renderToggle(graphics, toggleMissions(layout), "!", showMissions, AMBER, mouseX, mouseY);
        renderToggle(graphics, toggleTransit(layout), "T", showTransit, CYAN, mouseX, mouseY);
        renderToggle(graphics, toggleDistricts(layout), "A", showDistricts, RED, mouseX, mouseY);
    }

    private void renderMap(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        Rect map = layout.map();
        graphics.fill(map.x() - 2, map.y() - 2, map.right() + 2, map.bottom() + 2, 0xFF05090D);
        graphics.outline(map.x() - 1, map.y() - 1, map.width() + 2, map.height() + 2, MAP_BORDER);
        graphics.fill(map.x(), map.y(), map.right(), map.bottom(), MAP_INSET);
        graphics.enableScissor(map.x(), map.y(), map.right(), map.bottom());
        if (!packet.available()) {
            renderUnavailable(graphics, map);
        } else if (CityMapTextureCache.readyFor(
                packet.layoutSeed(), packet.generatorFingerprint())) {
            double visible = 1.0 / zoom;
            double centerU = CityMapProjection.worldToUnit(centerX, extent);
            double centerV = CityMapProjection.worldToUnit(centerZ, extent);
            float u0 = (float) (centerU - visible * 0.5);
            float u1 = (float) (centerU + visible * 0.5);
            float v0 = (float) (centerV - visible * 0.5);
            float v1 = (float) (centerV + visible * 0.5);
            graphics.blit(CityMapTextureCache.TEXTURE,
                    map.x(), map.y(), map.right(), map.bottom(), u0, u1, v0, v1);
            renderGrid(graphics, map);
            CityMapViewport viewport = viewport(map);
            CityMapRenderUtil.drawRoute(
                    graphics, viewport, CityMapNavigationClient.route().points());
            if (showDistricts) renderDistricts(graphics, map);
            renderMarkers(graphics, map, mouseX, mouseY);
            if (CityMapNavigationClient.waypoint() != null) {
                CityMapRenderUtil.drawWaypoint(
                        graphics, viewport, CityMapNavigationClient.waypoint());
            }
            renderPlayer(graphics, viewport);
        } else {
            renderLoading(graphics, map);
        }
        graphics.disableScissor();

        if (packet.available()) renderMapTools(graphics, layout, mouseX, mouseY);
        if (packet.available() && map.contains(mouseX, mouseY)) {
            graphics.requestCursor(dragging ? CursorTypes.RESIZE_ALL : CursorTypes.CROSSHAIR);
        }
    }

    private void renderLoading(GuiGraphicsExtractor graphics, Rect map) {
        int center = map.x() + map.width() / 2;
        String statusText = CityMapTextureCache.status() == CityMapTextureCache.Status.FAILED
                ? "CITY GRID UNAVAILABLE" : "SYNCING CITY GEOMETRY";
        graphics.centeredText(font, statusText, center, map.y() + map.height() / 2 - 16,
                CityMapTextureCache.status() == CityMapTextureCache.Status.FAILED ? RED : CYAN);
        int barWidth = Math.min(220, map.width() - 48);
        int barX = center - barWidth / 2;
        int barY = map.y() + map.height() / 2 + 4;
        graphics.fill(barX, barY, barX + barWidth, barY + 3, 0xFF102329);
        graphics.fill(barX, barY,
                barX + (int) Math.round(barWidth * CityMapTextureCache.progress()),
                barY + 3, CYAN);
        if (CityMapTextureCache.status() == CityMapTextureCache.Status.FAILED) {
            graphics.centeredText(font, elide(CityMapTextureCache.failure(), map.width() - 60),
                    center, barY + 16, TEXT_DIM);
        }
    }

    private void renderUnavailable(GuiGraphicsExtractor graphics, Rect map) {
        int center = map.x() + map.width() / 2;
        graphics.centeredText(font, "CITY GRID OFFLINE", center,
                map.y() + map.height() / 2 - 7, RED);
        graphics.centeredText(font, "PROJECT MOON OVERWORLD REQUIRED", center,
                map.y() + map.height() / 2 + 9, TEXT_DIM);
    }

    private void renderGrid(GuiGraphicsExtractor graphics, Rect map) {
        double half = extent / zoom;
        int firstX = (int) Math.ceil((centerX - half) / 512.0) * 512;
        int firstZ = (int) Math.ceil((centerZ - half) / 512.0) * 512;
        for (int worldX = firstX; worldX <= centerX + half; worldX += 512) {
            int x = worldToScreenX(map, worldX);
            graphics.verticalLine(x, map.y(), map.bottom() - 1,
                    worldX == 0 ? 0x6635D6D0 : 0x2520A9B1);
        }
        for (int worldZ = firstZ; worldZ <= centerZ + half; worldZ += 512) {
            int y = worldToScreenY(map, worldZ);
            graphics.horizontalLine(map.x(), map.right() - 1, y,
                    worldZ == 0 ? 0x6635D6D0 : 0x2520A9B1);
        }
        for (int y = map.y() + 2; y < map.bottom(); y += 4) {
            graphics.fill(map.x(), y, map.right(), y + 1, 0x0D000000);
        }
    }

    private void renderDistricts(GuiGraphicsExtractor graphics, Rect map) {
        for (MegacityLayout.Node node : cityLayout.nodes()) {
            int x = worldToScreenX(map, node.x());
            int y = worldToScreenY(map, node.z());
            if (!map.contains(x, y)) continue;
            graphics.fill(x - 7, y - 7, x + 8, y + 8, 0xA8071014);
            graphics.outline(x - 7, y - 7, 15, 15, RED_DIM);
            graphics.centeredText(font, node.district().code(), x, y - 4, RED);
            if (zoom >= 2.0) {
                graphics.text(font, node.district().label().toUpperCase(Locale.ROOT),
                        x + 11, y - 4, TEXT_DIM, false);
            }
        }
    }

    private void renderMarkers(
            GuiGraphicsExtractor graphics, Rect map, int mouseX, int mouseY) {
        OpenCityMapPacket.Marker hovered = null;
        for (OpenCityMapPacket.Marker marker : packet.markers()) {
            if (!isLayerVisible(marker)) continue;
            int x = worldToScreenX(map, marker.x());
            int y = worldToScreenY(map, marker.z());
            if (!map.contains(x, y)) continue;
            boolean isHovered = Math.abs(mouseX - x) <= 7 && Math.abs(mouseY - y) <= 7;
            boolean selected = marker == selectedMarker;
            if (marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION) {
                int pulse = (int) ((Math.sin(Util.getMillis() / 180.0) + 1.0) * 2.0);
                drawDiamond(graphics, x, y, selected ? 6 : 4 + pulse / 2,
                        selected || isHovered ? AMBER : 0xFFCF9831);
                graphics.fill(x - 1, y - 1, x + 2, y + 2, 0xFF1B1105);
            } else {
                drawTransit(graphics, x, y, isHovered ? CYAN : 0xFF43BDB7);
            }
            if (isHovered) hovered = marker;
        }
        if (hovered != null) {
            String label = elide(markerLabel(hovered), Math.max(40, map.width() - 20));
            int textWidth = font.width(label);
            int textX = Math.max(map.x() + 6,
                    Math.min(map.right() - textWidth - 6, mouseX + 10));
            int textY = Math.max(map.y() + 6,
                    Math.min(map.bottom() - 10, mouseY - 17));
            graphics.fill(textX - 4, textY - 4, textX + textWidth + 4, textY + 9, 0xE9060D12);
            graphics.text(font, label, textX, textY - 2,
                    hovered.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION ? AMBER : CYAN,
                    false);
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void renderPlayer(GuiGraphicsExtractor graphics, CityMapViewport viewport) {
        if (minecraft.player == null) return;
        CityMapRenderUtil.drawPlayer(
                graphics,
                viewport,
                minecraft.player.getX(),
                minecraft.player.getZ(),
                minecraft.player.getYRot());
    }

    private void renderDetailsBand(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        int left = width - layout.rightWidth();
        graphics.fill(left, layout.headerHeight(), width, layout.footerTop(), SIDEBAR);
        graphics.verticalLine(left, layout.headerHeight(), layout.footerTop(), CYAN_DIM);
        OpenCityMapPacket.Marker marker = selectedMarker;
        graphics.text(font, "SIGNAL DATA", left + 14, layout.headerHeight() + 14, CYAN, false);
        if (CityMapNavigationClient.waypoint() != null
                && !CityMapNavigationClient.waypoint().marker()) {
            renderWaypointDetails(graphics, layout, left);
            return;
        }
        if (marker == null) {
            renderWaypointDetails(graphics, layout, left);
            return;
        }
        District district = district(marker.districtOrdinal());
        int y = layout.headerHeight() + 44;
        graphics.text(font, marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION
                ? "ACTIVE CONTRACT" : "TRANSIT NODE", left + 14, y, AMBER, false);
        y += 19;
        for (String line : wrap(markerLabel(marker), layout.rightWidth() - 28)) {
            graphics.text(font, line, left + 14, y, TEXT, false);
            y += 12;
        }
        MissionTrackerClient.Snapshot mission = MissionTrackerClient.active();
        if (marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION && mission != null) {
            y += 8;
            graphics.text(font, mission.type().displayName(), left + 14, y, RED, false);
            y += 15;
            for (String line : wrap(mission.objective(), layout.rightWidth() - 28)) {
                graphics.text(font, line, left + 14, y, CYAN, false);
                y += 12;
            }
            y += 4;
            graphics.text(font, mission.reward() + " EM ON COMPLETION", left + 14, y, AMBER, false);
        }
        y += 11;
        graphics.text(font, "DISTRICT", left + 14, y, TEXT_DARK, false);
        y += 12;
        graphics.text(font, district == null ? "UNKNOWN" : district.label(), left + 14, y, RED, false);
        y += 24;
        graphics.text(font, "COORDINATES", left + 14, y, TEXT_DARK, false);
        y += 12;
        graphics.text(font, String.format(Locale.ROOT, "%+06d  %+06d", marker.x(), marker.z()),
                left + 14, y, TEXT_DIM, false);
        y += 24;
        graphics.text(font, "STATUS", left + 14, y, TEXT_DARK, false);
        y += 12;
        graphics.text(font, marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION
                ? "OBJECTIVE // ACTIVE" : "NETWORK // ONLINE", left + 14, y,
                marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION ? AMBER : CYAN, false);
    }

    private void renderWaypointDetails(
            GuiGraphicsExtractor graphics, Layout layout, int left) {
        CityMapNavigationClient.Waypoint waypoint = CityMapNavigationClient.waypoint();
        if (waypoint == null) {
            graphics.text(font, "NO SIGNAL SELECTED", left + 14,
                    layout.headerHeight() + 42, TEXT_DIM, false);
            return;
        }
        District district = district(waypoint.districtOrdinal());
        int y = layout.headerHeight() + 44;
        graphics.text(font, "ACTIVE WAYPOINT", left + 14, y, AMBER, false);
        y += 19;
        graphics.text(font, waypoint.marker() && !waypoint.labelKey().isEmpty()
                ? displayLabel(waypoint.labelKey())
                : "CUSTOM MAP PIN", left + 14, y, TEXT, false);
        y += 24;
        graphics.text(font, "DISTRICT", left + 14, y, TEXT_DARK, false);
        y += 12;
        graphics.text(font, district == null ? "OUTSIDE CITY" : district.label(),
                left + 14, y, RED, false);
        y += 24;
        graphics.text(font, "COORDINATES", left + 14, y, TEXT_DARK, false);
        y += 12;
        graphics.text(font, String.format(Locale.ROOT, "%+06d  %+06d", waypoint.x(), waypoint.z()),
                left + 14, y, TEXT_DIM, false);
        y += 24;
        graphics.text(font, "ROUTE", left + 14, y, TEXT_DARK, false);
        y += 12;
        graphics.text(font, String.format(Locale.ROOT, "%.0f BLOCKS // %02d DISTRICTS",
                        CityMapNavigationClient.route().length(),
                        CityMapNavigationClient.route().districts().size()),
                left + 14, y, AMBER, false);
    }

    private void renderFooter(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        graphics.fill(0, layout.footerTop(), width, height, 0xF2081015);
        graphics.horizontalLine(0, width - 1, layout.footerTop(), CYAN_DIM);
        String district = currentDistrict();
        graphics.text(font, district, 12, layout.footerTop() + 7, TEXT_DIM, false);
        String fingerprint = "GRID " + packet.generatorFingerprint();
        graphics.text(font, elide(fingerprint, width / 2),
                width - 12 - font.width(elide(fingerprint, width / 2)),
                layout.footerTop() + 7, TEXT_DARK, false);
    }

    private void renderMapTools(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        renderTool(graphics, zoomIn(layout), "+", mouseX, mouseY);
        renderTool(graphics, zoomOut(layout), "-", mouseX, mouseY);
        renderTool(graphics, recenter(layout), "O", mouseX, mouseY);
        if (CityMapNavigationClient.waypoint() != null) {
            renderTool(graphics, clearWaypoint(layout), "X", mouseX, mouseY);
        }
    }

    private void renderTool(
            GuiGraphicsExtractor graphics, Rect rect, String icon, int mouseX, int mouseY) {
        boolean hovered = rect.contains(mouseX, mouseY);
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                hovered ? 0xE91B3E42 : 0xDD08161C);
        graphics.outline(rect.x(), rect.y(), rect.width(), rect.height(), hovered ? CYAN : CYAN_DIM);
        graphics.centeredText(font, icon, rect.x() + rect.width() / 2, rect.y() + 6,
                hovered ? CYAN : TEXT_DIM);
        if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
    }

    private void renderToggle(
            GuiGraphicsExtractor graphics,
            Rect rect,
            String icon,
            boolean active,
            int activeColor,
            int mouseX,
            int mouseY) {
        boolean hovered = rect.contains(mouseX, mouseY);
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                active ? 0xD51A282B : 0xAA0A1115);
        graphics.outline(rect.x(), rect.y(), rect.width(), rect.height(),
                active ? activeColor : TEXT_DARK);
        graphics.centeredText(font, icon, rect.x() + rect.width() / 2, rect.y() + 6,
                active ? activeColor : TEXT_DARK);
        if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!packet.available()) return super.mouseClicked(event, doubleClick);
        Layout layout = layout();
        if (event.button() == 1 && layout.map().contains(event.x(), event.y())) {
            CityMapNavigationClient.clearWaypoint();
            selectedMarker = null;
            return true;
        }
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        if (zoomIn(layout).contains(event.x(), event.y())) {
            setZoom(zoom * 1.35, layout.map().centerX(), layout.map().centerY(), layout.map());
            return true;
        }
        if (zoomOut(layout).contains(event.x(), event.y())) {
            setZoom(zoom / 1.35, layout.map().centerX(), layout.map().centerY(), layout.map());
            return true;
        }
        if (recenter(layout).contains(event.x(), event.y())) {
            recenterOnPlayer();
            return true;
        }
        if (CityMapNavigationClient.waypoint() != null
                && clearWaypoint(layout).contains(event.x(), event.y())) {
            CityMapNavigationClient.clearWaypoint();
            selectedMarker = null;
            return true;
        }
        if (toggleMissions(layout).contains(event.x(), event.y())) {
            showMissions = !showMissions;
            return true;
        }
        if (toggleTransit(layout).contains(event.x(), event.y())) {
            showTransit = !showTransit;
            return true;
        }
        if (toggleDistricts(layout).contains(event.x(), event.y())) {
            showDistricts = !showDistricts;
            return true;
        }
        OpenCityMapPacket.Marker mission = missionRowAt(layout, event.x(), event.y());
        if (mission != null) {
            selectAndFocus(mission);
            return true;
        }
        if (layout.map().contains(event.x(), event.y())) {
            mapPressActive = true;
            dragging = false;
            pressDoubleClick = doubleClick;
            pressX = event.x();
            pressY = event.y();
            dragOriginCenterX = centerX;
            dragOriginCenterZ = centerZ;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && mapPressActive) {
            mapPressActive = false;
            if (!dragging && layout().map().contains(event.x(), event.y())) {
                Rect map = layout().map();
                OpenCityMapPacket.Marker marker = markerAt(map, event.x(), event.y());
                if (marker != null) {
                    selectedMarker = marker;
                    CityMapNavigationClient.setWaypoint(marker);
                    if (pressDoubleClick) focus(marker.x(), marker.z());
                } else {
                    selectedMarker = null;
                    CityMapViewport viewport = viewport(map);
                    CityMapNavigationClient.setWaypoint(
                            (int) Math.round(viewport.worldX(event.x())),
                            (int) Math.round(viewport.worldZ(event.y())));
                }
            }
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!mapPressActive || event.button() != 0) {
            return super.mouseDragged(event, dragX, dragY);
        }
        double totalX = event.x() - pressX;
        double totalY = event.y() - pressY;
        if (!dragging && totalX * totalX + totalY * totalY >= 16.0) dragging = true;
        if (!dragging) return true;
        Rect map = layout().map();
        double worldPerPixelX = extent * 2.0 / zoom / map.width();
        double worldPerPixelZ = extent * 2.0 / zoom / map.height();
        centerX = CityMapProjection.clampCenter(
                dragOriginCenterX - totalX * worldPerPixelX, extent, zoom);
        centerZ = CityMapProjection.clampCenter(
                dragOriginCenterZ - totalY * worldPerPixelZ, extent, zoom);
        return true;
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double deltaX, double deltaY) {
        if (!packet.available()) return false;
        Rect map = layout().map();
        if (!map.contains(mouseX, mouseY)) return false;
        mapPressActive = false;
        dragging = false;
        setZoom(zoom * Math.pow(1.22, deltaY), mouseX, mouseY, map);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (CyberdeckClient.OPEN_CITY_MAP_KEY.matches(event)) {
            onClose();
            return true;
        }
        double step = 220.0 / zoom;
        switch (event.key()) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> centerX -= step;
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> centerX += step;
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> centerZ -= step;
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> centerZ += step;
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                setZoom(zoom * 1.25, layout().map().centerX(), layout().map().centerY(), layout().map());
                return true;
            }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                setZoom(zoom / 1.25, layout().map().centerX(), layout().map().centerY(), layout().map());
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                recenterOnPlayer();
                return true;
            }
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                CityMapNavigationClient.clearWaypoint();
                selectedMarker = null;
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
        centerX = CityMapProjection.clampCenter(centerX, extent, zoom);
        centerZ = CityMapProjection.clampCenter(centerZ, extent, zoom);
        return true;
    }

    private void setZoom(double requested, double anchorX, double anchorY, Rect map) {
        double oldWorldX = screenToWorldX(map, anchorX);
        double oldWorldZ = screenToWorldZ(map, anchorY);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, requested));
        double normalizedX = (anchorX - map.centerX()) / map.width();
        double normalizedY = (anchorY - map.centerY()) / map.height();
        centerX = oldWorldX - normalizedX * extent * 2.0 / zoom;
        centerZ = oldWorldZ - normalizedY * extent * 2.0 / zoom;
        centerX = CityMapProjection.clampCenter(centerX, extent, zoom);
        centerZ = CityMapProjection.clampCenter(centerZ, extent, zoom);
    }

    private void selectAndFocus(OpenCityMapPacket.Marker marker) {
        selectedMarker = marker;
        CityMapNavigationClient.setWaypoint(marker);
        focus(marker.x(), marker.z());
    }

    private void focus(int worldX, int worldZ) {
        zoom = Math.max(zoom, 2.65);
        centerX = CityMapProjection.clampCenter(worldX, extent, zoom);
        centerZ = CityMapProjection.clampCenter(worldZ, extent, zoom);
    }

    private void recenterOnPlayer() {
        if (minecraft.player == null) {
            centerX = 0.0;
            centerZ = 0.0;
            zoom = MIN_ZOOM;
            return;
        }
        zoom = Math.max(zoom, 2.0);
        centerX = CityMapProjection.clampCenter(minecraft.player.getX(), extent, zoom);
        centerZ = CityMapProjection.clampCenter(minecraft.player.getZ(), extent, zoom);
    }

    private OpenCityMapPacket.Marker missionRowAt(Layout layout, double mouseX, double mouseY) {
        if (mouseX < 8 || mouseX >= layout.leftWidth() - 8) return null;
        int rowY = layout.headerHeight() + 48;
        if (mouseY < rowY) return null;
        int index = (int) ((mouseY - rowY) / 32);
        int visibleRows = Math.min(missions.size(), visibleMissionRows(layout));
        return index >= 0 && index < visibleRows ? missions.get(index) : null;
    }

    private OpenCityMapPacket.Marker markerAt(Rect map, double mouseX, double mouseY) {
        OpenCityMapPacket.Marker nearest = null;
        double nearestDistance = 64.0;
        for (OpenCityMapPacket.Marker marker : packet.markers()) {
            if (!isLayerVisible(marker)) continue;
            int markerX = worldToScreenX(map, marker.x());
            int markerY = worldToScreenY(map, marker.z());
            if (!map.contains(markerX, markerY)) continue;
            double dx = mouseX - markerX;
            double dy = mouseY - markerY;
            double distance = dx * dx + dy * dy;
            if (distance < nearestDistance) {
                nearest = marker;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private boolean isLayerVisible(OpenCityMapPacket.Marker marker) {
        return marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION
                ? showMissions : showTransit && zoom >= 1.25;
    }

    private int worldToScreenX(Rect map, double worldX) {
        return viewport(map).screenX(worldX);
    }

    private int worldToScreenY(Rect map, double worldZ) {
        return viewport(map).screenY(worldZ);
    }

    private double screenToWorldX(Rect map, double screenX) {
        return viewport(map).worldX(screenX);
    }

    private double screenToWorldZ(Rect map, double screenY) {
        return viewport(map).worldZ(screenY);
    }

    private CityMapViewport viewport(Rect map) {
        double worldSpan = extent * 2.0 / zoom;
        return new CityMapViewport(
                map.x(), map.y(), map.width(), map.height(),
                centerX, centerZ, worldSpan, worldSpan);
    }

    private String cursorReadout(Layout layout, int mouseX, int mouseY) {
        if (!packet.available()) return "CITY GRID OFFLINE";
        if (!layout.map().contains(mouseX, mouseY)) {
            return "CITYWIDE OVERVIEW";
        }
        int x = (int) Math.round(screenToWorldX(layout.map(), mouseX));
        int z = (int) Math.round(screenToWorldZ(layout.map(), mouseY));
        MegacityLayout.Location location = cityLayout.locateDistrict(x, z);
        String district = location.insideCity() ? location.district().code() : "OUT";
        return String.format(Locale.ROOT, "%s  //  X %+06d  Z %+06d", district, x, z);
    }

    private String currentDistrict() {
        if (!packet.available()) return "CITY GRID OFFLINE";
        if (minecraft.player == null) return "PLAYER SIGNAL LOST";
        MegacityLayout.Location location = cityLayout.locateDistrict(
                minecraft.player.getBlockX(), minecraft.player.getBlockZ());
        return location.insideCity()
                ? location.district().code() + " // " + location.district().label().toUpperCase(Locale.ROOT)
                : "OUTSIDE CITY LIMITS";
    }

    private String markerLabel(OpenCityMapPacket.Marker marker) {
        District district = district(marker.districtOrdinal());
        if (marker.kind() == OpenCityMapPacket.MarkerKind.TRANSIT) {
            return Component.translatable(marker.labelKey(),
                    district == null ? "?" : district.label()).getString();
        }
        return displayLabel(marker.labelKey());
    }

    private static String displayLabel(String label) {
        return label.startsWith("literal:")
                ? label.substring("literal:".length())
                : Component.translatable(label).getString();
    }

    private static District district(int ordinal) {
        District[] districts = District.values();
        return ordinal >= 0 && ordinal < districts.length ? districts[ordinal] : null;
    }

    private void drawDiamond(GuiGraphicsExtractor graphics, int x, int y, int radius, int color) {
        for (int offset = -radius; offset <= radius; offset++) {
            int half = radius - Math.abs(offset);
            graphics.fill(x - half, y + offset, x + half + 1, y + offset + 1, color);
        }
    }

    private void drawTransit(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.outline(x - 4, y - 4, 9, 9, color);
        graphics.fill(x - 1, y - 3, x + 2, y + 4, color);
        graphics.fill(x - 3, y - 1, x + 4, y + 2, color);
    }

    private String elide(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        String suffix = "...";
        int end = value.length();
        while (end > 0 && font.width(value.substring(0, end) + suffix) > maxWidth) end--;
        return value.substring(0, end) + suffix;
    }

    private List<String> wrap(String value, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : value.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.isEmpty() ? List.of("") : List.copyOf(lines);
    }

    private Layout layout() {
        int header = 39;
        int footer = 24;
        int left = Math.max(124, Math.min(164, width / 5));
        int right = width >= 700 ? Math.max(164, Math.min(196, width / 5)) : 0;
        int contentTop = header + 8;
        int contentBottom = height - footer - 8;
        int availableLeft = left + 12;
        int availableRight = width - right - 12;
        int availableWidth = Math.max(96, availableRight - availableLeft);
        int availableHeight = Math.max(96, contentBottom - contentTop);
        int mapSize = Math.min(availableWidth, availableHeight);
        int mapX = availableLeft + (availableWidth - mapSize) / 2;
        int mapY = contentTop + (availableHeight - mapSize) / 2;
        return new Layout(header, height - footer, left, right,
                new Rect(mapX, mapY, mapSize, mapSize));
    }

    private int visibleMissionRows(Layout layout) {
        int rowY = layout.headerHeight() + 48;
        return Math.max(0, (layout.footerTop() - 62 - rowY) / 32);
    }

    private Rect zoomIn(Layout layout) {
        return new Rect(layout.map().right() - 26, layout.map().y() + 8, 19, 19);
    }

    private Rect zoomOut(Layout layout) {
        return new Rect(layout.map().right() - 26, layout.map().y() + 30, 19, 19);
    }

    private Rect recenter(Layout layout) {
        return new Rect(layout.map().right() - 26, layout.map().y() + 52, 19, 19);
    }

    private Rect clearWaypoint(Layout layout) {
        return new Rect(layout.map().right() - 26, layout.map().y() + 74, 19, 19);
    }

    private Rect toggleMissions(Layout layout) {
        return new Rect(12, layout.footerTop() - 36, 26, 22);
    }

    private Rect toggleTransit(Layout layout) {
        return new Rect(44, layout.footerTop() - 36, 26, 22);
    }

    private Rect toggleDistricts(Layout layout) {
        return new Rect(76, layout.footerTop() - 36, 26, 22);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Layout(
            int headerHeight,
            int footerTop,
            int leftWidth,
            int rightWidth,
            Rect map) {
    }

    private record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        int centerX() { return x + width / 2; }
        int centerY() { return y + height / 2; }
        boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }
}
