package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.client.map.CityMapRenderUtil;
import com.example.cyberdeck.client.map.CityMapViewport;
import com.example.cyberdeck.client.mission.GigJournalClient;
import com.example.cyberdeck.client.mission.MissionTrackerClient;
import com.example.cyberdeck.network.AcceptDiscoveredGigPacket;
import com.example.cyberdeck.network.GigJournalPacket;
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
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
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
    private final List<OpenCityMapPacket.Marker> contractSignals;
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
    // Session-persistent full-map layers. These are intentionally independent of the HUD minimap.
    private static boolean showMissions = true;
    private static boolean showTransit;
    private static boolean showDistricts;
    private static boolean showMerchants = true;
    private OpenCityMapPacket.Marker selectedMarker;
    private int contractScroll;
    private long acceptingGigUntil;

    private CityMapScreen(OpenCityMapPacket packet) {
        super(Component.translatable("screen.cyberdeck.city_map.title"));
        this.packet = packet;
        this.cityLayout = packet.available()
                ? MegacityLayout.createFromLayoutSeed(packet.layoutSeed())
                : MegacityLayout.create(0L);
        this.extent = CityMapProjection.extent(cityLayout);
        this.contractSignals = packet.markers().stream()
                .filter(marker -> marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION
                        || marker.kind() == OpenCityMapPacket.MarkerKind.AVAILABLE_GIG)
                .toList();
        CityMapNavigationClient.Waypoint waypoint = CityMapNavigationClient.waypoint();
        if (waypoint == null || !waypoint.marker()) {
            this.selectedMarker = null;
        } else {
            this.selectedMarker = packet.markers().stream()
                    .filter(marker -> CityMapNavigationClient.matchesWaypoint(marker, waypoint))
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
                || currentScreen != null
                        && !(currentScreen instanceof CityMapScreen)
                        && !(currentScreen instanceof JournalScreen)
                        && !(currentScreen instanceof CyberwareScreen))) {
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
        renderFooter(graphics, layout, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderHeader(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        CyberpunkMenuTabs.render(graphics, font, width,
                CyberpunkMenuTabs.Tab.MAP, mouseX, mouseY);
    }

    private void renderMissionBand(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        graphics.fill(0, layout.headerHeight(), layout.leftWidth(), layout.footerTop(), SIDEBAR);
        graphics.verticalLine(layout.leftWidth() - 1, layout.headerHeight(), layout.footerTop(), CYAN_DIM);
        graphics.text(font, "CONTRACTS", 12, layout.headerHeight() + 10, AMBER, false);
        graphics.text(font, String.format(Locale.ROOT, "%02d SIGNALS", contractSignals.size()),
                12, layout.headerHeight() + 23, TEXT_DARK, false);
        int rowY = layout.headerHeight() + 39;
        int rowHeight = 25;
        int signalTextX = 25 + maxDistrictCodeWidth();
        int signalTextWidth = Math.max(16, layout.leftWidth() - signalTextX - 14);
        int visibleRows = Math.min(contractSignals.size(), visibleMissionRows(layout));
        contractScroll = Math.min(contractScroll,
                Math.max(0, contractSignals.size() - visibleRows));
        for (int slot = 0; slot < visibleRows; slot++) {
            OpenCityMapPacket.Marker marker = contractSignals.get(contractScroll + slot);
            int top = rowY + slot * rowHeight;
            boolean selected = marker == selectedMarker;
            boolean hovered = mouseX >= 8 && mouseX < layout.leftWidth() - 8
                    && mouseY >= top && mouseY < top + rowHeight - 2;
            graphics.fill(8, top, layout.leftWidth() - 8, top + rowHeight - 2,
                    selected ? 0xDD291C1D : hovered ? 0xCC152326 : 0x9910181D);
            graphics.verticalLine(8, top, top + rowHeight - 3,
                    selected ? AMBER : hovered ? AMBER_DIM : TEXT_DARK);
            District district = district(marker.districtOrdinal());
            String code = district == null ? "?" : district.code();
            graphics.text(font,
                    CityMapRenderUtil.isGigMarker(marker) ? "!" : code,
                    17, top + 8, selected ? AMBER : RED, false);
            graphics.text(font, elide(markerLabel(marker), signalTextWidth),
                    signalTextX, top + 3, selected ? TEXT : 0xFFB2C8C5, false);
            String location = (district == null ? "UNKNOWN" : district.code())
                    + " // " + distanceTo(marker.x(), marker.z());
            graphics.text(font, elide(location, signalTextWidth),
                    signalTextX, top + 14, TEXT_DIM, false);
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        if (contractSignals.size() > visibleRows && visibleRows > 0) {
            int trackTop = rowY;
            int trackHeight = visibleRows * rowHeight - 2;
            int thumbHeight = Math.max(8,
                    trackHeight * visibleRows / contractSignals.size());
            int maxScroll = contractSignals.size() - visibleRows;
            int thumbOffset = maxScroll == 0 ? 0
                    : (trackHeight - thumbHeight) * contractScroll / maxScroll;
            graphics.fill(layout.leftWidth() - 5, trackTop,
                    layout.leftWidth() - 3, trackTop + trackHeight, 0x5524474A);
            graphics.fill(layout.leftWidth() - 5, trackTop + thumbOffset,
                    layout.leftWidth() - 3, trackTop + thumbOffset + thumbHeight, CYAN_DIM);
        }

        int detailsY = rowY + visibleRows * rowHeight + 8;
        renderSelectedSignal(graphics, layout, detailsY, mouseX, mouseY);
        renderLayerToggles(graphics, layout, mouseX, mouseY);
    }

    private int maxDistrictCodeWidth() {
        int width = font.width("?");
        for (District district : District.values()) {
            width = Math.max(width, font.width(district.code()));
        }
        return width;
    }

    private void renderSelectedGig(
            GuiGraphicsExtractor graphics,
            Layout layout,
            GigJournalPacket.AvailableGig gig,
            int startY,
            int mouseX,
            int mouseY) {
        int x = 12;
        int maxWidth = layout.leftWidth() - 24;
        int y = startY;
        int statsY = availableGigAccept(layout).y() - 30;
        if (y + 11 < statsY) {
            graphics.text(font, "AVAILABLE GIG", x, y, AMBER, false);
            y += 14;
        }
        if (y + 11 < statsY) {
            graphics.text(font, elide(gig.title(), maxWidth), x, y, TEXT, false);
            y += 14;
        }
        if (y + 11 < statsY) {
            graphics.text(font, dev.modernity.neoncity.MissionCatalog.MissionType
                    .values()[gig.typeOrdinal()].displayName(), x, y, RED, false);
            y += 15;
        }
        if (y + 22 < statsY) {
            graphics.text(font, "OBJECTIVE", x, y, TEXT_DARK, false);
            y += 13;
            int objectiveLines = 0;
            for (String line : wrap(gig.objective(), maxWidth)) {
                if (objectiveLines++ >= 2 || y + 11 >= statsY) break;
                graphics.text(font, line, x, y, CYAN, false);
                y += 11;
            }
        }
        District district = district(gig.districtOrdinal());
        graphics.text(font, elide(district == null ? "UNKNOWN DISTRICT"
                        : district.code() + " // " + distanceTo(gig.targetX(), gig.targetZ()), maxWidth),
                x, statsY, TEXT_DIM, false);
        String reward = gig.reward() + " EM // " + gig.streetCred() + " SC";
        graphics.text(font, elide(reward, maxWidth), x, statsY + 13, AMBER, false);

        Rect accept = availableGigAccept(layout);
        boolean waiting = Util.getMillis() < acceptingGigUntil;
        boolean blocked = MissionTrackerClient.active() != null;
        boolean enabled = !waiting && !blocked;
        boolean hovered = accept.contains(mouseX, mouseY);
        graphics.fill(accept.x(), accept.y(), accept.right(), accept.bottom(),
                hovered && enabled ? 0xE42A2920 : 0xDD11171B);
        graphics.outline(accept.x(), accept.y(), accept.width(), accept.height(),
                enabled ? hovered ? AMBER : AMBER_DIM : TEXT_DARK);
        String action = blocked ? "CONTRACT ACTIVE" : waiting ? "ACCEPTING..." : "ACCEPT GIG";
        graphics.centeredText(font, action, accept.centerX(), accept.y() + 7,
                enabled ? AMBER : TEXT_DIM);
        if (hovered) graphics.requestCursor(
                enabled ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
    }

    private void renderSelectedSignal(
            GuiGraphicsExtractor graphics,
            Layout layout,
            int startY,
            int mouseX,
            int mouseY) {
        GigJournalPacket.AvailableGig available = GigJournalClient.availableAt(selectedMarker);
        if (available != null) {
            renderSelectedGig(graphics, layout, available, startY, mouseX, mouseY);
            return;
        }

        int x = 12;
        int maxWidth = layout.leftWidth() - 24;
        int bottom = layout.footerTop() - 61;
        if (startY + 10 >= bottom) return;
        OpenCityMapPacket.Marker marker = selectedMarker;
        if (marker == null) {
            CityMapNavigationClient.Waypoint waypoint = CityMapNavigationClient.waypoint();
            if (waypoint == null) {
                graphics.text(font, "SELECT A SIGNAL", x, startY, TEXT_DARK, false);
                return;
            }
            graphics.text(font, "CUSTOM WAYPOINT", x, startY, AMBER, false);
            if (startY + 17 < bottom) {
                graphics.text(font, distanceTo(waypoint.x(), waypoint.z()), x, startY + 15,
                        TEXT_DIM, false);
            }
            return;
        }

        int y = startY;
        graphics.text(font, markerHeading(marker), x, y,
                marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION ? AMBER : CYAN, false);
        y += 15;
        if (y + 10 < bottom) {
            graphics.text(font, elide(markerLabel(marker), maxWidth), x, y, TEXT, false);
            y += 15;
        }
        MissionTrackerClient.Snapshot mission = MissionTrackerClient.active();
        if (marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION && mission != null) {
            if (y + 10 < bottom) {
                graphics.text(font, mission.type().displayName(), x, y, RED, false);
                y += 15;
            }
            if (y + 21 < bottom) {
                graphics.text(font, "OBJECTIVE", x, y, TEXT_DARK, false);
                y += 13;
                int lines = 0;
                for (String line : wrap(mission.objective(), maxWidth)) {
                    if (lines++ >= 2 || y + 10 >= bottom) break;
                    graphics.text(font, line, x, y, CYAN, false);
                    y += 11;
                }
            }
            if (y + 12 < bottom) {
                graphics.text(font, mission.reward() + " EM // " + mission.streetCred() + " SC",
                        x, y + 2, AMBER, false);
                y += 15;
            }
        }
        if (y + 10 < bottom) {
            District district = district(marker.districtOrdinal());
            String location = (district == null ? "UNKNOWN" : district.code())
                    + " // " + distanceTo(marker.x(), marker.z());
            graphics.text(font, elide(location, maxWidth), x, y, TEXT_DIM, false);
        }
    }

    private void renderLayerToggles(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        int filterY = layout.footerTop() - 52;
        graphics.text(font, "MAP LAYERS", 12, filterY, TEXT_DARK, false);
        renderToggle(graphics, toggleMissions(layout), "C", showMissions, AMBER, mouseX, mouseY);
        renderToggle(graphics, toggleMerchants(layout), "$", showMerchants,
                CityMapRenderUtil.MERCHANT_COLOR, mouseX, mouseY);
        renderToggle(graphics, toggleTransit(layout), "T", showTransit, CYAN, mouseX, mouseY);
        renderToggle(graphics, toggleDistricts(layout), "D", showDistricts, RED, mouseX, mouseY);
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
            if (showMerchants) renderMerchants(graphics, map, mouseX, mouseY);
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
            String code = node.district().code();
            int markerWidth = Math.max(15, font.width(code) + 6);
            int markerLeft = x - markerWidth / 2;
            graphics.fill(markerLeft, y - 7, markerLeft + markerWidth, y + 8, 0xA8071014);
            graphics.outline(markerLeft, y - 7, markerWidth, 15, RED_DIM);
            graphics.centeredText(font, code, x, y - 4, RED);
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
            if (isVendor(marker)) continue;
            if (!isLayerVisible(marker)) continue;
            int x = worldToScreenX(map, marker.x());
            int y = worldToScreenY(map, marker.z());
            if (!map.contains(x, y)) continue;
            boolean isHovered = Math.abs(mouseX - x) <= 7 && Math.abs(mouseY - y) <= 7;
            boolean selected = marker == selectedMarker;
            if (CityMapRenderUtil.isGigMarker(marker)) {
                CityMapRenderUtil.drawGigMarker(graphics, x, y);
            } else if (marker.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION) {
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
                    hovered.kind() == OpenCityMapPacket.MarkerKind.ACTIVE_MISSION
                            || hovered.kind() == OpenCityMapPacket.MarkerKind.AVAILABLE_GIG
                            ? AMBER : CYAN,
                    false);
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void renderMerchants(
            GuiGraphicsExtractor graphics, Rect map, int mouseX, int mouseY) {
        OpenCityMapPacket.Marker hovered = null;
        for (OpenCityMapPacket.Marker marker : packet.markers()) {
            if (!isVendor(marker)) continue;
            int x = worldToScreenX(map, marker.x());
            int y = worldToScreenY(map, marker.z());
            if (!map.contains(x, y)) continue;
            CityMapRenderUtil.drawVendorMarker(graphics, x, y, marker.kind());
            if (Math.abs(mouseX - x) <= 6 && Math.abs(mouseY - y) <= 6) {
                hovered = marker;
            }
        }
        if (hovered != null) {
            String label = elide(markerLabel(hovered), Math.max(40, map.width() - 20));
            int textWidth = font.width(label);
            int textX = Math.max(map.x() + 6,
                    Math.min(map.right() - textWidth - 6, mouseX + 10));
            int textY = Math.max(map.y() + 6, Math.min(map.bottom() - 10, mouseY - 17));
            graphics.fill(textX - 4, textY - 4, textX + textWidth + 4, textY + 9, 0xE9060D12);
            graphics.text(font, label, textX, textY - 2,
                    hovered.kind() == OpenCityMapPacket.MarkerKind.FIXER
                            ? CityMapRenderUtil.FIXER_COLOR
                            : CityMapRenderUtil.MERCHANT_COLOR,
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

    private void renderFooter(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        graphics.fill(0, layout.footerTop(), width, height, 0xF2081015);
        graphics.horizontalLine(0, width - 1, layout.footerTop(), CYAN_DIM);
        String district = currentDistrict();
        graphics.text(font, elide(district, width / 2 - 18),
                12, layout.footerTop() + 7, TEXT_DIM, false);
        CityMapNavigationClient.Waypoint waypoint = CityMapNavigationClient.waypoint();
        String navigation = waypoint == null
                ? cursorReadout(layout, mouseX, mouseY)
                : String.format(Locale.ROOT, "ROUTE // %.0fm",
                        CityMapNavigationClient.distanceToWaypoint(
                                minecraft.player == null ? waypoint.x() : minecraft.player.getX(),
                                minecraft.player == null ? waypoint.z() : minecraft.player.getZ()));
        String fitted = elide(navigation, width / 2 - 18);
        graphics.text(font, fitted, width - 12 - font.width(fitted),
                layout.footerTop() + 7, waypoint == null ? TEXT_DARK : AMBER, false);
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
        if (event.button() == 0 && CyberpunkMenuTabs.handleClick(
                CyberpunkMenuTabs.Tab.MAP, event.x(), event.y(), width)) {
            return true;
        }
        if (!packet.available()) return super.mouseClicked(event, doubleClick);
        Layout layout = layout();
        if (event.button() == 1 && layout.map().contains(event.x(), event.y())) {
            CityMapNavigationClient.clearWaypoint();
            selectedMarker = null;
            return true;
        }
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        GigJournalPacket.AvailableGig availableGig = GigJournalClient.availableAt(selectedMarker);
        if (availableGig != null && availableGigAccept(layout).contains(event.x(), event.y())) {
            if (MissionTrackerClient.active() == null && Util.getMillis() >= acceptingGigUntil) {
                acceptingGigUntil = Util.getMillis() + 1_000L;
                ClientPacketDistributor.sendToServer(
                        new AcceptDiscoveredGigPacket(availableGig.offerId()));
            }
            return true;
        }
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
        if (toggleMerchants(layout).contains(event.x(), event.y())) {
            showMerchants = !showMerchants;
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
        Layout layout = layout();
        int visibleRows = Math.min(contractSignals.size(), visibleMissionRows(layout));
        if (mouseX >= 0 && mouseX < layout.leftWidth()
                && mouseY >= layout.headerHeight() && mouseY < layout.footerTop()
                && contractSignals.size() > visibleRows) {
            int maximum = contractSignals.size() - visibleRows;
            contractScroll = Math.max(0, Math.min(
                    maximum, contractScroll - (int) Math.signum(deltaY)));
            return true;
        }
        Rect map = layout.map();
        if (!map.contains(mouseX, mouseY)) return false;
        mapPressActive = false;
        dragging = false;
        setZoom(zoom * Math.pow(1.22, deltaY), mouseX, mouseY, map);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (CyberpunkMenuTabs.handleKey(CyberpunkMenuTabs.Tab.MAP, event)) {
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
        int rowY = layout.headerHeight() + 39;
        if (mouseY < rowY) return null;
        int index = (int) ((mouseY - rowY) / 25);
        int visibleRows = Math.min(contractSignals.size(), visibleMissionRows(layout));
        int actual = contractScroll + index;
        return index >= 0 && index < visibleRows && actual < contractSignals.size()
                ? contractSignals.get(actual) : null;
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
        return switch (marker.kind()) {
            case ACTIVE_MISSION, AVAILABLE_GIG -> showMissions;
            case FIXER, MERCHANT -> showMerchants;
            case TRANSIT -> showTransit && zoom >= 1.25;
        };
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

    private static boolean isVendor(OpenCityMapPacket.Marker marker) {
        return marker.kind() == OpenCityMapPacket.MarkerKind.FIXER
                || marker.kind() == OpenCityMapPacket.MarkerKind.MERCHANT;
    }

    private static String markerHeading(OpenCityMapPacket.Marker marker) {
        return switch (marker.kind()) {
            case ACTIVE_MISSION -> "ACTIVE CONTRACT";
            case AVAILABLE_GIG -> "AVAILABLE GIG";
            case FIXER -> "DISTRICT FIXER";
            case MERCHANT -> "MERCHANT STALL";
            case TRANSIT -> "TRANSIT NODE";
        };
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
        int header = CyberpunkMenuTabs.HEIGHT;
        int footer = 24;
        int left = Math.max(142, Math.min(174, width / 5));
        int contentTop = header + 8;
        int contentBottom = height - footer - 8;
        int availableLeft = left + 12;
        int availableRight = width - 12;
        int availableWidth = Math.max(96, availableRight - availableLeft);
        int availableHeight = Math.max(96, contentBottom - contentTop);
        int mapSize = Math.min(availableWidth, availableHeight);
        int mapX = availableLeft + (availableWidth - mapSize) / 2;
        int mapY = contentTop + (availableHeight - mapSize) / 2;
        return new Layout(header, height - footer, left,
                new Rect(mapX, mapY, mapSize, mapSize));
    }

    private int visibleMissionRows(Layout layout) {
        int rowY = layout.headerHeight() + 39;
        int detailReserve = 112;
        return Math.max(0, (layout.footerTop() - 61 - rowY - detailReserve) / 25);
    }

    private Rect availableGigAccept(Layout layout) {
        return new Rect(12, layout.footerTop() - 88,
                Math.max(40, layout.leftWidth() - 24), 25);
    }

    private String distanceTo(int targetX, int targetZ) {
        if (minecraft.player == null || minecraft.level == null
                || !net.minecraft.world.level.Level.OVERWORLD.equals(minecraft.level.dimension())) {
            return "OFF-GRID";
        }
        return String.format(Locale.ROOT, "%.0fm", Math.hypot(
                targetX - minecraft.player.getX(), targetZ - minecraft.player.getZ()));
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
        return new Rect(76, layout.footerTop() - 36, 26, 22);
    }

    private Rect toggleDistricts(Layout layout) {
        return new Rect(108, layout.footerTop() - 36, 26, 22);
    }

    private Rect toggleMerchants(Layout layout) {
        return new Rect(44, layout.footerTop() - 36, 26, 22);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Layout(
            int headerHeight,
            int footerTop,
            int leftWidth,
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
