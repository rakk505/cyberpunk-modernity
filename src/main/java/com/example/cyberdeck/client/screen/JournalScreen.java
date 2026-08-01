package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.client.mission.GigJournalClient;
import com.example.cyberdeck.client.mission.MissionTrackerClient;
import com.example.cyberdeck.network.GigJournalPacket;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MissionCatalog;
import dev.modernity.neoncity.MissionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;

/** Accepted mission and gig history, including full active-contract navigation details. */
public final class JournalScreen extends Screen {
    private static final int DESIGN_WIDTH = 960;
    private static final int DESIGN_HEIGHT = 540;
    private static final int BACKGROUND_TOP = 0xFF13070B;
    private static final int BACKGROUND_BOTTOM = 0xFF03080B;
    private static final int PANEL = 0xEC090E13;
    private static final int PANEL_ALT = 0xE5131116;
    private static final int RED = 0xFFFF4850;
    private static final int RED_DIM = 0xFF873038;
    private static final int CYAN = 0xFF3DE8EC;
    private static final int GREEN = 0xFF3CDB83;
    private static final int AMBER = 0xFFFFC54A;
    private static final int TEXT = 0xFFE8EEEE;
    private static final int TEXT_DIM = 0xFF7C9192;
    private static final int TEXT_DARK = 0xFF41585B;

    private static final Rect LIST_PANEL = new Rect(34, 72, 330, 414);
    private static final Rect DETAIL_PANEL = new Rect(386, 72, 540, 414);
    private static final Rect ABANDON_ACTION = new Rect(706, 411, 192, 25);
    private static final Rect CONFIRM_ABANDON_ACTION = new Rect(706, 411, 116, 25);
    private static final Rect CANCEL_ABANDON_ACTION = new Rect(828, 411, 70, 25);
    private static final Rect MAP_ACTION = new Rect(706, 443, 192, 25);
    private static final int LIST_TOP = 111;
    private static final int LIST_BOTTOM = 470;

    private UUID selectedId;
    private UUID confirmAbandonId;
    private int scrollOffset;

    private JournalScreen() {
        super(Component.translatable("screen.cyberdeck.journal"));
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) return;
        minecraft.setScreenAndShow(new JournalScreen());
        GigJournalClient.requestRefresh();
    }

    @Override
    public void extractBackground(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
        for (int y = 2; y < height; y += 4) graphics.fill(0, y, width, y + 1, 0x0C000000);
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Viewport viewport = viewport();
        double designMouseX = viewport.toDesignX(mouseX);
        double designMouseY = viewport.toDesignY(mouseY);
        ensureSelection();

        graphics.pose().pushMatrix();
        graphics.pose().translate(viewport.offsetX(), viewport.offsetY());
        graphics.pose().scale(viewport.scale(), viewport.scale());
        CyberpunkMenuTabs.render(graphics, font, DESIGN_WIDTH,
                CyberpunkMenuTabs.Tab.JOURNAL, designMouseX, designMouseY);
        renderFrame(graphics);
        renderList(graphics, designMouseX, designMouseY);
        renderDetails(graphics, designMouseX, designMouseY);
        graphics.pose().popMatrix();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderFrame(GuiGraphicsExtractor graphics) {
        graphics.text(font, "JOURNAL", 35, 50, RED, false);
        graphics.text(font, "ACCEPTED MISSIONS & GIGS", 106, 50, TEXT_DIM, false);
        graphics.fill(LIST_PANEL.x(), LIST_PANEL.y(), LIST_PANEL.right(), LIST_PANEL.bottom(), PANEL);
        graphics.outline(LIST_PANEL.x(), LIST_PANEL.y(), LIST_PANEL.width(), LIST_PANEL.height(), RED_DIM);
        graphics.fill(DETAIL_PANEL.x(), DETAIL_PANEL.y(), DETAIL_PANEL.right(), DETAIL_PANEL.bottom(), PANEL);
        graphics.outline(DETAIL_PANEL.x(), DETAIL_PANEL.y(), DETAIL_PANEL.width(), DETAIL_PANEL.height(), RED_DIM);
        graphics.text(font, String.format(Locale.ROOT, "%02d ENTRIES", contracts().size()),
                LIST_PANEL.x() + 14, LIST_PANEL.y() + 14, TEXT_DIM, false);
        graphics.text(font, "CONTRACT DATA", DETAIL_PANEL.x() + 18,
                DETAIL_PANEL.y() + 14, CYAN, false);
    }

    private void renderList(
            GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        List<ListRow> rows = rows();
        int totalHeight = rows.stream().mapToInt(ListRow::height).sum();
        int viewportHeight = LIST_BOTTOM - LIST_TOP;
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, totalHeight - viewportHeight)));
        int y = LIST_TOP - scrollOffset;
        graphics.enableScissor(LIST_PANEL.x() + 1, LIST_TOP,
                LIST_PANEL.right() - 1, LIST_BOTTOM);
        for (ListRow row : rows) {
            if (y + row.height() > LIST_TOP && y < LIST_BOTTOM) {
                if (row.contract() == null) {
                    graphics.text(font, row.heading(), LIST_PANEL.x() + 14, y + 4,
                            row.heading().equals("ACTIVE") ? AMBER : TEXT_DARK, false);
                    graphics.horizontalLine(LIST_PANEL.x() + 72, LIST_PANEL.right() - 14,
                            y + 8, RED_DIM);
                } else {
                    renderContractRow(graphics, row.contract(), y, mouseX, mouseY);
                }
            }
            y += row.height();
        }
        graphics.disableScissor();

        if (totalHeight > viewportHeight) {
            int trackTop = LIST_TOP;
            int trackHeight = viewportHeight;
            int thumbHeight = Math.max(24, trackHeight * viewportHeight / totalHeight);
            int thumbY = trackTop + scrollOffset * (trackHeight - thumbHeight)
                    / Math.max(1, totalHeight - viewportHeight);
            graphics.fill(LIST_PANEL.right() - 5, trackTop,
                    LIST_PANEL.right() - 3, trackTop + trackHeight, 0xFF162126);
            graphics.fill(LIST_PANEL.right() - 5, thumbY,
                    LIST_PANEL.right() - 3, thumbY + thumbHeight, CYAN);
        }
    }

    private void renderContractRow(
            GuiGraphicsExtractor graphics,
            GigJournalPacket.Contract contract,
            int y,
            double mouseX,
            double mouseY) {
        Rect row = new Rect(LIST_PANEL.x() + 9, y, LIST_PANEL.width() - 18, 44);
        boolean selected = contract.instanceId().equals(selectedId);
        boolean hovered = row.contains(mouseX, mouseY);
        graphics.fill(row.x(), row.y(), row.right(), row.bottom(),
                selected ? 0xE9241A1D : hovered ? 0xDA172126 : PANEL_ALT);
        graphics.fill(row.x(), row.y(), row.x() + 3, row.bottom(), statusColor(contract));
        graphics.text(font, elide(contract.title(), row.width() - 28),
                row.x() + 12, row.y() + 7, selected ? TEXT : 0xFFBDCBCB, false);
        String meta = kind(contract).displayName() + " // " + status(contract).name();
        graphics.text(font, meta, row.x() + 12, row.y() + 23,
                statusColor(contract), false);
        if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
    }

    private void renderDetails(
            GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        GigJournalPacket.Contract contract = selected();
        int x = DETAIL_PANEL.x() + 18;
        int maxWidth = DETAIL_PANEL.width() - 36;
        if (contract == null) {
            graphics.text(font, "NO ACCEPTED CONTRACTS", x, DETAIL_PANEL.y() + 47,
                    TEXT_DIM, false);
            return;
        }

        MissionService.JournalStatus status = status(contract);
        District district = district(contract.districtOrdinal());
        int y = DETAIL_PANEL.y() + 44;
        graphics.text(font, kind(contract).displayName() + " // " + type(contract).displayName(),
                x, y, RED, false);
        String statusText = status.name();
        graphics.text(font, statusText, DETAIL_PANEL.right() - 18 - font.width(statusText),
                y, statusColor(contract), false);
        y += 22;
        y = renderWrapped(graphics, contract.title(), x, y, maxWidth, TEXT, 3);

        y += 9;
        graphics.text(font, "BRIEFING", x, y, TEXT_DARK, false);
        y += 15;
        y = renderWrapped(graphics,
                contract.briefing().isBlank() ? contract.objective() : contract.briefing(),
                x, y, maxWidth, TEXT_DIM, 7);
        y += 10;
        graphics.text(font, "OBJECTIVE", x, y, TEXT_DARK, false);
        y += 15;
        y = renderWrapped(graphics, contract.objective(), x, y, maxWidth, CYAN, 5);

        int infoY = 348;
        graphics.text(font, "DISTRICT", x, infoY, TEXT_DARK, false);
        graphics.text(font, district == null ? "UNKNOWN" : district.label().toUpperCase(Locale.ROOT),
                x + 80, infoY, RED, false);
        infoY += 18;
        graphics.text(font, "LOCATION", x, infoY, TEXT_DARK, false);
        graphics.text(font, String.format(Locale.ROOT, "%+06d  %+06d",
                contract.targetX(), contract.targetZ()), x + 80, infoY, TEXT_DIM, false);

        if (status == MissionService.JournalStatus.ACTIVE) {
            infoY += 18;
            graphics.text(font, "DISTANCE", x, infoY, TEXT_DARK, false);
            graphics.text(font, distance(contract), x + 80, infoY, AMBER, false);
            renderAbandonAction(graphics, contract, mouseX, mouseY);
        }

        String payout = contract.reward() + " EM POOL  //  " + contract.streetCred() + " STREET CRED";
        graphics.text(font, elide(payout, maxWidth - 210), x, 451, AMBER, false);
        if (status == MissionService.JournalStatus.ACTIVE) {
            boolean hovered = MAP_ACTION.contains(mouseX, mouseY);
            graphics.fill(MAP_ACTION.x(), MAP_ACTION.y(), MAP_ACTION.right(), MAP_ACTION.bottom(),
                    hovered ? 0xE72A2920 : 0xD912171B);
            graphics.outline(MAP_ACTION.x(), MAP_ACTION.y(), MAP_ACTION.width(), MAP_ACTION.height(),
                    hovered ? AMBER : RED_DIM);
            graphics.centeredText(font, "VIEW ON MAP", MAP_ACTION.x() + MAP_ACTION.width() / 2,
                    MAP_ACTION.y() + 7, hovered ? AMBER : TEXT_DIM);
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void renderAbandonAction(
            GuiGraphicsExtractor graphics,
            GigJournalPacket.Contract contract,
            double mouseX,
            double mouseY) {
        boolean confirming = contract.instanceId().equals(confirmAbandonId);
        if (!confirming) {
            boolean hovered = ABANDON_ACTION.contains(mouseX, mouseY);
            graphics.fill(ABANDON_ACTION.x(), ABANDON_ACTION.y(),
                    ABANDON_ACTION.right(), ABANDON_ACTION.bottom(),
                    hovered ? 0xE72A171B : 0xD912171B);
            graphics.outline(ABANDON_ACTION.x(), ABANDON_ACTION.y(),
                    ABANDON_ACTION.width(), ABANDON_ACTION.height(), hovered ? RED : RED_DIM);
            graphics.centeredText(font, "ABANDON CONTRACT",
                    ABANDON_ACTION.x() + ABANDON_ACTION.width() / 2,
                    ABANDON_ACTION.y() + 7, hovered ? RED : TEXT_DIM);
            if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
            return;
        }

        boolean confirmHovered = CONFIRM_ABANDON_ACTION.contains(mouseX, mouseY);
        boolean cancelHovered = CANCEL_ABANDON_ACTION.contains(mouseX, mouseY);
        graphics.fill(CONFIRM_ABANDON_ACTION.x(), CONFIRM_ABANDON_ACTION.y(),
                CONFIRM_ABANDON_ACTION.right(), CONFIRM_ABANDON_ACTION.bottom(),
                confirmHovered ? 0xFFF04750 : 0xFFD32D38);
        graphics.outline(CONFIRM_ABANDON_ACTION.x(), CONFIRM_ABANDON_ACTION.y(),
                CONFIRM_ABANDON_ACTION.width(), CONFIRM_ABANDON_ACTION.height(), RED);
        graphics.centeredText(font, "YES, ABANDON",
                CONFIRM_ABANDON_ACTION.x() + CONFIRM_ABANDON_ACTION.width() / 2,
                CONFIRM_ABANDON_ACTION.y() + 7, 0xFFFFFFFF);
        graphics.fill(CANCEL_ABANDON_ACTION.x(), CANCEL_ABANDON_ACTION.y(),
                CANCEL_ABANDON_ACTION.right(), CANCEL_ABANDON_ACTION.bottom(),
                cancelHovered ? 0xE72A2920 : 0xD912171B);
        graphics.outline(CANCEL_ABANDON_ACTION.x(), CANCEL_ABANDON_ACTION.y(),
                CANCEL_ABANDON_ACTION.width(), CANCEL_ABANDON_ACTION.height(),
                cancelHovered ? AMBER : RED_DIM);
        graphics.centeredText(font, "CANCEL",
                CANCEL_ABANDON_ACTION.x() + CANCEL_ABANDON_ACTION.width() / 2,
                CANCEL_ABANDON_ACTION.y() + 7, cancelHovered ? AMBER : TEXT_DIM);
        if (confirmHovered || cancelHovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
    }

    private int renderWrapped(
            GuiGraphicsExtractor graphics,
            String value,
            int x,
            int y,
            int maxWidth,
            int color,
            int maxLines) {
        List<FormattedCharSequence> lines = font.split(Component.literal(value), maxWidth);
        for (int index = 0; index < lines.size() && index < maxLines; index++) {
            graphics.text(font, lines.get(index), x, y, color, false);
            y += 11;
        }
        if (lines.size() > maxLines) graphics.text(font, "...", x, y - 11, color, false);
        return y;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        Viewport viewport = viewport();
        if (!viewport.contains(event.x(), event.y())) return true;
        double mouseX = viewport.toDesignX(event.x());
        double mouseY = viewport.toDesignY(event.y());
        if (CyberpunkMenuTabs.handleClick(
                CyberpunkMenuTabs.Tab.JOURNAL, mouseX, mouseY, DESIGN_WIDTH)) return true;

        GigJournalPacket.Contract clicked = contractAt(mouseX, mouseY);
        if (clicked != null) {
            if (!clicked.instanceId().equals(selectedId)) confirmAbandonId = null;
            selectedId = clicked.instanceId();
            return true;
        }
        GigJournalPacket.Contract selected = selected();
        if (selected != null && status(selected) == MissionService.JournalStatus.ACTIVE) {
            boolean confirming = selected.instanceId().equals(confirmAbandonId);
            if (confirming && CONFIRM_ABANDON_ACTION.contains(mouseX, mouseY)) {
                confirmAbandonId = null;
                GigJournalClient.requestAbandon(selected.instanceId());
                return true;
            }
            if (confirming && CANCEL_ABANDON_ACTION.contains(mouseX, mouseY)) {
                confirmAbandonId = null;
                return true;
            }
            if (!confirming && ABANDON_ACTION.contains(mouseX, mouseY)) {
                confirmAbandonId = selected.instanceId();
                return true;
            }
            if (MAP_ACTION.contains(mouseX, mouseY)) {
                confirmAbandonId = null;
                MissionTrackerClient.Snapshot active = MissionTrackerClient.active();
                int navigationX = active != null
                                && active.targetX() == selected.targetX()
                                && active.targetZ() == selected.targetZ()
                        ? active.navigationX() : selected.targetX();
                int navigationZ = active != null
                                && active.targetX() == selected.targetX()
                                && active.targetZ() == selected.targetZ()
                        ? active.navigationZ() : selected.targetZ();
                CityMapNavigationClient.setMissionWaypoint(
                        navigationX, navigationZ,
                        selected.districtOrdinal(), selected.title());
                CityMapNavigationClient.requestOpen();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double deltaX, double deltaY) {
        Viewport viewport = viewport();
        double designX = viewport.toDesignX(mouseX);
        double designY = viewport.toDesignY(mouseY);
        if (!LIST_PANEL.contains(designX, designY)) return false;
        scrollOffset = Math.max(0, scrollOffset - (int) Math.round(deltaY * 30.0));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (CyberpunkMenuTabs.handleKey(CyberpunkMenuTabs.Tab.JOURNAL, event)) return true;
        return super.keyPressed(event);
    }

    private GigJournalPacket.Contract contractAt(double mouseX, double mouseY) {
        if (mouseX < LIST_PANEL.x() + 9 || mouseX >= LIST_PANEL.right() - 9
                || mouseY < LIST_TOP || mouseY >= LIST_BOTTOM) return null;
        int y = LIST_TOP - scrollOffset;
        for (ListRow row : rows()) {
            if (row.contract() != null && mouseY >= y && mouseY < y + row.height()) {
                return row.contract();
            }
            y += row.height();
        }
        return null;
    }

    private List<ListRow> rows() {
        List<GigJournalPacket.Contract> active = contracts().stream()
                .filter(contract -> status(contract) == MissionService.JournalStatus.ACTIVE)
                .toList();
        List<GigJournalPacket.Contract> history = contracts().stream()
                .filter(contract -> status(contract) != MissionService.JournalStatus.ACTIVE)
                .toList();
        ArrayList<ListRow> rows = new ArrayList<>();
        rows.add(ListRow.heading("ACTIVE"));
        active.forEach(contract -> rows.add(ListRow.contract(contract)));
        rows.add(ListRow.heading("HISTORY"));
        history.forEach(contract -> rows.add(ListRow.contract(contract)));
        return List.copyOf(rows);
    }

    private void ensureSelection() {
        if (confirmAbandonId != null && contracts().stream().noneMatch(contract ->
                contract.instanceId().equals(confirmAbandonId)
                        && status(contract) == MissionService.JournalStatus.ACTIVE)) {
            confirmAbandonId = null;
        }
        if (selectedId != null && contracts().stream()
                .anyMatch(contract -> contract.instanceId().equals(selectedId))) return;
        selectedId = contracts().stream()
                .filter(contract -> status(contract) == MissionService.JournalStatus.ACTIVE)
                .findFirst().or(() -> contracts().stream().findFirst())
                .map(GigJournalPacket.Contract::instanceId).orElse(null);
    }

    private GigJournalPacket.Contract selected() {
        if (selectedId == null) return null;
        return contracts().stream()
                .filter(contract -> contract.instanceId().equals(selectedId))
                .findFirst().orElse(null);
    }

    private List<GigJournalPacket.Contract> contracts() {
        return GigJournalClient.contracts();
    }

    private String distance(GigJournalPacket.Contract contract) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || !Level.OVERWORLD.equals(minecraft.level.dimension())) return "OFF-GRID";
        double distance = Math.hypot(
                contract.targetX() - minecraft.player.getX(),
                contract.targetZ() - minecraft.player.getZ());
        return String.format(Locale.ROOT, "%.0f m", distance);
    }

    private int statusColor(GigJournalPacket.Contract contract) {
        return switch (status(contract)) {
            case ACTIVE -> AMBER;
            case COMPLETED -> GREEN;
            case FAILED -> RED;
            case ABANDONED -> TEXT_DIM;
        };
    }

    private static MissionService.ContractKind kind(GigJournalPacket.Contract contract) {
        return MissionService.ContractKind.values()[contract.kindOrdinal()];
    }

    private static MissionCatalog.MissionType type(GigJournalPacket.Contract contract) {
        return MissionCatalog.MissionType.values()[contract.typeOrdinal()];
    }

    private static MissionService.JournalStatus status(GigJournalPacket.Contract contract) {
        return MissionService.JournalStatus.values()[contract.statusOrdinal()];
    }

    private static District district(int ordinal) {
        return ordinal >= 0 && ordinal < District.values().length ? District.values()[ordinal] : null;
    }

    private String elide(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(value) <= maxWidth) return value;
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private Viewport viewport() {
        float scale = Math.min(width / (float) DESIGN_WIDTH, height / (float) DESIGN_HEIGHT);
        scale = Math.max(0.01F, scale);
        return new Viewport(scale,
                (width - DESIGN_WIDTH * scale) / 2.0F,
                (height - DESIGN_HEIGHT * scale) / 2.0F);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record ListRow(String heading, GigJournalPacket.Contract contract, int height) {
        static ListRow heading(String value) {
            return new ListRow(value, null, 22);
        }

        static ListRow contract(GigJournalPacket.Contract value) {
            return new ListRow("", value, 48);
        }
    }

    private record Viewport(float scale, float offsetX, float offsetY) {
        double toDesignX(double screenX) {
            return (screenX - offsetX) / scale;
        }

        double toDesignY(double screenY) {
            return (screenY - offsetY) / scale;
        }

        boolean contains(double screenX, double screenY) {
            return screenX >= offsetX && screenX < offsetX + DESIGN_WIDTH * scale
                    && screenY >= offsetY && screenY < offsetY + DESIGN_HEIGHT * scale;
        }
    }

    private record Rect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
    }
}
