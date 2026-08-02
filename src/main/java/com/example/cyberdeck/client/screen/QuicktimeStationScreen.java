package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.network.OpenQuicktimeStationPacket;
import com.example.cyberdeck.network.TravelQuicktimePacket;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import dev.modernity.neoncity.District;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;
import java.util.Locale;

/** Compact in-world route picker for a Quicktime station. */
public final class QuicktimeStationScreen extends Screen {
    private static final int PANEL_WIDTH = 452;
    private static final int PANEL_HEIGHT = 372;
    private static final int PANEL_MARGIN = 14;
    private static final int LIST_SIDE_MARGIN = 18;
    private static final int LIST_TOP_OFFSET = 74;
    private static final int LIST_BOTTOM_OFFSET = 48;
    private static final int ROW_HEIGHT = 31;
    private static final int BUTTON_WIDTH = 116;
    private static final int BUTTON_HEIGHT = 22;

    private static final int SCRIM_TOP = 0xCE03100F;
    private static final int SCRIM_BOTTOM = 0xE908070C;
    private static final int PANEL = 0xF20A1013;
    private static final int PANEL_INSET = 0xE8081719;
    private static final int ROW = 0xD70C171A;
    private static final int ROW_HOVER = 0xE612292B;
    private static final int ROW_SELECTED = 0xF1133334;
    private static final int CYAN = 0xFF42F4E8;
    private static final int CYAN_DIM = 0xFF318F8C;
    private static final int CYAN_FAINT = 0x6642F4E8;
    private static final int RED = 0xFFFF4B5D;
    private static final int TEXT = 0xFFE4F2EF;
    private static final int TEXT_DIM = 0xFF789994;
    private static final int TEXT_DARK = 0xFF3F605C;

    private final long sourcePos;
    private final int currentDistrictOrdinal;
    private final List<OpenQuicktimeStationPacket.Destination> destinations;
    private int selectedIndex;
    private double targetScroll;
    private double displayedScroll;
    private boolean travelRequested;

    private QuicktimeStationScreen(OpenQuicktimeStationPacket packet) {
        super(Component.translatable("screen.cyberdeck.quicktime.title"));
        this.sourcePos = packet.sourcePos();
        this.currentDistrictOrdinal = packet.currentDistrictOrdinal();
        this.destinations = packet.destinations();
        this.selectedIndex = destinations.isEmpty() ? -1 : 0;
    }

    public static void open(OpenQuicktimeStationPacket packet) {
        Minecraft.getInstance().setScreenAndShow(new QuicktimeStationScreen(packet));
    }

    @Override
    public void tick() {
        displayedScroll += (targetScroll - displayedScroll) * 0.38;
        if (Math.abs(targetScroll - displayedScroll) < 0.1) {
            displayedScroll = targetScroll;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                  float partialTick) {
        graphics.fillGradient(0, 0, width, height, SCRIM_TOP, SCRIM_BOTTOM);
        for (int y = 1; y < height; y += 4) {
            graphics.fill(0, y, width, y + 1, 0x10000000);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        Layout layout = layout();
        renderFrame(graphics, layout);
        renderDestinations(graphics, layout, mouseX, mouseY);
        renderFooter(graphics, layout, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderFrame(GuiGraphicsExtractor graphics, Layout layout) {
        graphics.fill(layout.x(), layout.y(), layout.right(), layout.bottom(), PANEL);
        graphics.outline(layout.x(), layout.y(), layout.width(), layout.height(), CYAN_DIM);
        graphics.horizontalLine(layout.x() + 1, layout.x() + 82, layout.y() + 2, CYAN);
        graphics.horizontalLine(layout.right() - 68, layout.right() - 1, layout.bottom() - 3, RED);

        graphics.text(font, text("screen.cyberdeck.quicktime.header"), layout.x() + 18, layout.y() + 15,
                CYAN, false);
        graphics.text(font, text("screen.cyberdeck.quicktime.select"), layout.x() + 18, layout.y() + 31,
                TEXT, false);

        District current = district(currentDistrictOrdinal);
        String currentLabel = current == null ? "UNKNOWN" : current.code() + " // " + current.label();
        String node = text("screen.cyberdeck.quicktime.current",
                currentLabel.toUpperCase(Locale.ROOT));
        graphics.text(font, node, layout.right() - 18 - font.width(node), layout.y() + 16,
                TEXT_DIM, false);
        String routeCount = text("screen.cyberdeck.quicktime.routes", destinations.size());
        graphics.text(font, routeCount, layout.right() - 18 - font.width(routeCount),
                layout.y() + 32, TEXT_DARK, false);
        graphics.horizontalLine(layout.x() + 18, layout.right() - 19, layout.y() + 55, CYAN_FAINT);
    }

    private void renderDestinations(GuiGraphicsExtractor graphics, Layout layout,
                                    int mouseX, int mouseY) {
        int listLeft = layout.x() + LIST_SIDE_MARGIN;
        int listRight = layout.right() - LIST_SIDE_MARGIN;
        int listTop = layout.y() + LIST_TOP_OFFSET;
        int listBottom = layout.bottom() - LIST_BOTTOM_OFFSET;
        graphics.fill(listLeft, listTop, listRight, listBottom, PANEL_INSET);

        if (destinations.isEmpty()) {
            graphics.centeredText(font, text("screen.cyberdeck.quicktime.empty"),
                    (listLeft + listRight) / 2, listTop + (listBottom - listTop) / 2 - 5, RED);
            graphics.centeredText(font, text("screen.cyberdeck.quicktime.empty_hint"),
                    (listLeft + listRight) / 2, listTop + (listBottom - listTop) / 2 + 11, TEXT_DIM);
            return;
        }

        int scroll = (int) Math.round(displayedScroll);
        int districtTextX = listLeft + 20 + maxDistrictCodeWidth();
        graphics.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int index = 0; index < destinations.size(); index++) {
            int rowTop = listTop + index * ROW_HEIGHT - scroll;
            int rowBottom = rowTop + ROW_HEIGHT - 2;
            if (rowBottom <= listTop || rowTop >= listBottom) {
                continue;
            }
            boolean hovered = mouseX >= listLeft && mouseX < listRight
                    && mouseY >= Math.max(rowTop, listTop) && mouseY < Math.min(rowBottom, listBottom);
            boolean selected = index == selectedIndex;
            int background = selected ? ROW_SELECTED : hovered ? ROW_HOVER : ROW;
            graphics.fill(listLeft + 2, rowTop, listRight - 7, rowBottom, background);
            graphics.verticalLine(listLeft + 2, rowTop, rowBottom - 1,
                    selected ? CYAN : hovered ? CYAN_DIM : TEXT_DARK);

            OpenQuicktimeStationPacket.Destination destination = destinations.get(index);
            District district = district(destination.districtOrdinal());
            String code = district == null ? "?" : district.code();
            String label = district == null ? "Unknown district" : district.label();
            String flavor = district == null ? "route data unavailable" : district.flavor();
            String distance = formatDistance(destination.distanceBlocks());

            graphics.text(font, code, listLeft + 12, rowTop + 10, selected ? CYAN : RED, false);
            graphics.text(font, label.toUpperCase(Locale.ROOT), districtTextX, rowTop + 5,
                    selected ? TEXT : 0xFFBBD0CC, false);
            int availableFlavorWidth = Math.max(30,
                    listRight - 85 - districtTextX);
            graphics.text(font, elide(flavor, availableFlavorWidth), districtTextX, rowTop + 16,
                    TEXT_DIM, false);
            graphics.text(font, distance, listRight - 14 - font.width(distance), rowTop + 10,
                    selected ? CYAN : TEXT_DIM, false);
            if (hovered) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
        graphics.disableScissor();

        int contentHeight = destinations.size() * ROW_HEIGHT;
        int viewportHeight = listBottom - listTop;
        if (contentHeight > viewportHeight) {
            int trackX = listRight - 4;
            int thumbHeight = Math.max(18, viewportHeight * viewportHeight / contentHeight);
            int travel = viewportHeight - thumbHeight;
            int thumbTop = listTop + (int) Math.round(travel * displayedScroll / maxScroll(layout));
            graphics.fill(trackX, listTop, trackX + 2, listBottom, 0x55264847);
            graphics.fill(trackX, thumbTop, trackX + 2, thumbTop + thumbHeight, CYAN_DIM);
        }
    }

    private int maxDistrictCodeWidth() {
        int width = font.width("?");
        for (District district : District.values()) {
            width = Math.max(width, font.width(district.code()));
        }
        return width;
    }

    private void renderFooter(GuiGraphicsExtractor graphics, Layout layout,
                              int mouseX, int mouseY) {
        int footerY = layout.bottom() - 36;
        graphics.text(font, text("screen.cyberdeck.quicktime.controls"),
                layout.x() + 18, footerY + 8, TEXT_DARK, false);

        Rect button = travelButton(layout);
        boolean enabled = selectedIndex >= 0 && !travelRequested;
        boolean hovered = enabled && button.contains(mouseX, mouseY);
        graphics.fill(button.x(), button.y(), button.right(), button.bottom(),
                hovered ? 0xFF174A49 : enabled ? 0xFF102E2F : 0xFF10191B);
        graphics.outline(button.x(), button.y(), button.width(), button.height(),
                enabled ? CYAN_DIM : TEXT_DARK);
        graphics.centeredText(font, text(travelRequested
                        ? "screen.cyberdeck.quicktime.routing"
                        : "screen.cyberdeck.quicktime.initiate"),
                button.x() + button.width() / 2, button.y() + 7,
                enabled ? CYAN : TEXT_DARK);
        if (hovered) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        Layout layout = layout();
        if (travelButton(layout).contains(event.x(), event.y())) {
            requestTravel();
            return true;
        }
        int index = destinationAt(layout, event.x(), event.y());
        if (index >= 0) {
            selectedIndex = index;
            ensureSelectedVisible(layout);
            if (doubleClick) {
                requestTravel();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (destinations.isEmpty()) {
            return false;
        }
        targetScroll = clamp(targetScroll - deltaY * ROW_HEIGHT * 1.8, 0.0, maxScroll(layout()));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!destinations.isEmpty() && event.isUp()) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            ensureSelectedVisible(layout());
            return true;
        }
        if (!destinations.isEmpty() && event.isDown()) {
            selectedIndex = Math.min(destinations.size() - 1, selectedIndex + 1);
            ensureSelectedVisible(layout());
            return true;
        }
        if (event.isConfirmation()) {
            requestTravel();
            return true;
        }
        return super.keyPressed(event);
    }

    private void requestTravel() {
        if (travelRequested || selectedIndex < 0 || selectedIndex >= destinations.size()) {
            return;
        }
        travelRequested = true;
        int districtOrdinal = destinations.get(selectedIndex).districtOrdinal();
        ClientPacketDistributor.sendToServer(new TravelQuicktimePacket(sourcePos, districtOrdinal));
        onClose();
    }

    private void ensureSelectedVisible(Layout layout) {
        if (selectedIndex < 0) {
            return;
        }
        int viewportHeight = listHeight(layout);
        int selectedTop = selectedIndex * ROW_HEIGHT;
        int selectedBottom = selectedTop + ROW_HEIGHT;
        if (selectedTop < targetScroll) {
            targetScroll = selectedTop;
        } else if (selectedBottom > targetScroll + viewportHeight) {
            targetScroll = selectedBottom - viewportHeight;
        }
        targetScroll = clamp(targetScroll, 0.0, maxScroll(layout));
    }

    private int destinationAt(Layout layout, double mouseX, double mouseY) {
        int listLeft = layout.x() + LIST_SIDE_MARGIN;
        int listRight = layout.right() - LIST_SIDE_MARGIN;
        int listTop = layout.y() + LIST_TOP_OFFSET;
        int listBottom = layout.bottom() - LIST_BOTTOM_OFFSET;
        if (mouseX < listLeft || mouseX >= listRight || mouseY < listTop || mouseY >= listBottom) {
            return -1;
        }
        int contentY = (int) Math.floor(mouseY - listTop + displayedScroll);
        int index = contentY / ROW_HEIGHT;
        return index >= 0 && index < destinations.size() ? index : -1;
    }

    private Layout layout() {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(240, width - PANEL_MARGIN * 2));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(210, height - PANEL_MARGIN * 2));
        return new Layout((width - panelWidth) / 2, (height - panelHeight) / 2,
                panelWidth, panelHeight);
    }

    private Rect travelButton(Layout layout) {
        return new Rect(layout.right() - 18 - BUTTON_WIDTH, layout.bottom() - 37,
                BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    private int listHeight(Layout layout) {
        return Math.max(1, layout.height() - LIST_TOP_OFFSET - LIST_BOTTOM_OFFSET);
    }

    private double maxScroll(Layout layout) {
        return Math.max(0, destinations.size() * ROW_HEIGHT - listHeight(layout));
    }

    private String elide(String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        int allowed = Math.max(0, maxWidth - font.width(ellipsis));
        int end = value.length();
        while (end > 0 && font.width(value.substring(0, end)) > allowed) {
            end--;
        }
        return value.substring(0, end) + ellipsis;
    }

    private static District district(int ordinal) {
        District[] districts = District.values();
        return ordinal >= 0 && ordinal < districts.length ? districts[ordinal] : null;
    }

    private static String formatDistance(int blocks) {
        if (blocks >= 1_000) {
            return String.format(Locale.ROOT, "%.1f km", blocks / 1_000.0);
        }
        return blocks + " m";
    }

    private static String text(String translationKey, Object... arguments) {
        return Component.translatable(translationKey, arguments).getString();
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Layout(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }
    }

    private record Rect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }
}
