package com.example.cyberdeck.client.screen;

import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.network.EquipCyberwarePacket;
import com.example.cyberdeck.network.RemoveCyberwarePacket;
import com.example.cyberdeck.ram.RamAttachments;

/** Cyberpunk-style full-body cyberware overview with a secondary installation catalog. */
public final class CyberwareScreen extends Screen {
    private static final int DESIGN_WIDTH = 960;
    private static final int DESIGN_HEIGHT = 540;
    private static final int SOCKET_WIDTH = 55;
    private static final int SOCKET_HEIGHT = 50;
    private static final int SOCKET_GAP = 1;
    private static final int ACTION_LOCK_MS = 450;
    private static final int ANATOMY_TEXTURE_WIDTH = 330;
    private static final int ANATOMY_TEXTURE_HEIGHT = 537;
    private static final Identifier ANATOMY_TEXTURE = Identifier.fromNamespaceAndPath(
            "cyberdeck", "textures/gui/anatomy_scan.png");

    private static final int BACKGROUND_TOP = 0xFF14070B;
    private static final int BACKGROUND_BOTTOM = 0xFF02070A;
    private static final int PANEL = 0xF20A0A0F;
    private static final int PANEL_SOFT = 0xE0120C12;
    private static final int PANEL_HOVER = 0xEE241117;
    private static final int RED = 0xFFFF4A4F;
    private static final int RED_BRIGHT = 0xFFFF6A69;
    private static final int RED_DIM = 0xFF813038;
    private static final int RED_FAINT = 0x493D1820;
    private static final int CYAN = 0xFF36E7F2;
    private static final int CYAN_DIM = 0xFF4C9FA7;
    private static final int CYAN_FAINT = 0x3736E7F2;
    private static final int GREEN = 0xFF35E781;
    private static final int TEXT = 0xFFE9EBEB;
    private static final int TEXT_DIM = 0xFF789096;
    private static final int TEXT_DISABLED = 0xFF46555A;

    private static final Rect BACK_BUTTON = new Rect(866, 505, 70, 20);

    private static final GroupSpec[] GROUPS = {
            new GroupSpec("FRONTAL CORTEX", null, 193, 99, 3, Side.LEFT,
                    Glyph.BRAIN, 459, 100),
            new GroupSpec("OCULAR SYSTEM", null, 306, 150, 1, Side.LEFT,
                    Glyph.EYE, 455, 119),
            new GroupSpec("CIRCULATORY\nSYSTEM", null, 193, 238, 3, Side.LEFT,
                    Glyph.HEART, 444, 204),
            new GroupSpec("IMMUNE SYSTEM", null, 250, 306, 2, Side.LEFT,
                    Glyph.SHIELD, 442, 230),
            new GroupSpec("NERVOUS\nSYSTEM", BodySlot.NERVOUS_SYSTEM, 250, 357, 2, Side.LEFT,
                    Glyph.CHIP, 454, 258),
            new GroupSpec("INTEGUMENTARY\nSYSTEM", BodySlot.INTEGUMENTARY_SYSTEM, 193, 412, 3, Side.LEFT,
                    Glyph.SKIN, 450, 320),

            new GroupSpec("OPERATING\nSYSTEM", BodySlot.OPERATING_SYSTEM, 596, 152, 1, Side.RIGHT,
                    Glyph.DRIVE, 524, 145),
            new GroupSpec("SKELETON", null, 596, 261, 2, Side.RIGHT,
                    Glyph.BONE, 523, 210),
            new GroupSpec("HANDS", BodySlot.HANDS, 596, 312, 1, Side.RIGHT,
                    Glyph.HAND, 558, 255),
            new GroupSpec("ARMS", BodySlot.ARMS, 596, 362, 1, Side.RIGHT,
                    Glyph.ARM, 553, 284),
            new GroupSpec("LEGS", BodySlot.LEGS, 596, 412, 1, Side.RIGHT,
                    Glyph.LEG, 530, 366)
    };

    private BodySlot selectedSlot = BodySlot.OPERATING_SYSTEM;
    private Cyberware selectedCyberware;
    private boolean detailsOpen;
    private long interactionLockedUntil;

    public CyberwareScreen() {
        super(Component.translatable("screen.cyberdeck.cyberware"));
    }

    @Override
    protected void init() {
        chooseCyberwareForSlot(selectedSlot);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
        for (int y = 2; y < this.height; y += 4) {
            graphics.fill(0, y, this.width, y + 1, 0x09000000);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Viewport viewport = viewport();
        double designMouseX = viewport.toDesignX(mouseX);
        double designMouseY = viewport.toDesignY(mouseY);
        ensureCyberwareSelection();

        graphics.pose().pushMatrix();
        graphics.pose().translate(viewport.offsetX(), viewport.offsetY());
        graphics.pose().scale(viewport.scale(), viewport.scale());

        renderFrame(graphics);
        renderHeader(graphics);
        renderScannerBackdrop(graphics);
        renderAnatomyModel(graphics);
        for (GroupSpec group : GROUPS) {
            renderConnection(graphics, group);
        }
        renderPlayerScanOverlay(graphics);
        for (int index = 0; index < GROUPS.length; index++) {
            renderGroup(graphics, GROUPS[index], index,
                    designMouseX, designMouseY, mouseX, mouseY);
        }
        if (detailsOpen) {
            graphics.nextStratum();
            renderDetailOverlay(graphics, designMouseX, designMouseY, mouseX, mouseY);
        }
        renderFooter(graphics, designMouseX, designMouseY);

        graphics.pose().popMatrix();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        Viewport viewport = viewport();
        double mouseX = viewport.toDesignX(event.x());
        double mouseY = viewport.toDesignY(event.y());
        if (!viewport.contains(event.x(), event.y())) {
            return true;
        }

        if (detailsOpen) {
            Rect panel = detailPanel();
            if (detailCloseRect().contains(mouseX, mouseY) || BACK_BUTTON.contains(mouseX, mouseY)) {
                detailsOpen = false;
                return true;
            }

            List<Cyberware> options = Cyberware.forSlot(selectedSlot);
            for (int index = 0; index < options.size(); index++) {
                if (optionRect(index, options.size()).contains(mouseX, mouseY)) {
                    selectedCyberware = options.get(index);
                    return true;
                }
            }

            if (detailActionRect().contains(mouseX, mouseY)) {
                performSelectedAction();
                return true;
            }
            if (!panel.contains(mouseX, mouseY)) {
                detailsOpen = false;
            }
            return true;
        }

        if (BACK_BUTTON.contains(mouseX, mouseY)) {
            onClose();
            return true;
        }
        for (GroupSpec group : GROUPS) {
            if (groupHitRect(group).contains(mouseX, mouseY)) {
                if (group.slot() != null) {
                    selectedSlot = group.slot();
                    chooseCyberwareForSlot(selectedSlot);
                    detailsOpen = true;
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && detailsOpen) {
            detailsOpen = false;
            return true;
        }
        return super.keyPressed(event);
    }

    private void renderFrame(GuiGraphicsExtractor graphics) {
        graphics.fill(0, 0, 9, DESIGN_HEIGHT, 0xB008070A);
        graphics.fill(DESIGN_WIDTH - 9, 0, DESIGN_WIDTH, DESIGN_HEIGHT, 0xB008070A);
        graphics.verticalLine(12, 0, DESIGN_HEIGHT, RED_FAINT);
        graphics.verticalLine(DESIGN_WIDTH - 13, 0, DESIGN_HEIGHT, RED_FAINT);
        for (int y = 8; y < DESIGN_HEIGHT - 8; y += 12) {
            graphics.fill(5, y, 9, y + 2, RED_DIM);
            graphics.fill(DESIGN_WIDTH - 9, y, DESIGN_WIDTH - 5, y + 2, RED_DIM);
        }

        graphics.text(this.font, "0110", 16, 42, RED_DIM, false);
        graphics.text(this.font, "1001", DESIGN_WIDTH - 40, 42, RED_DIM, false);
        graphics.text(this.font, "NEURAL_LINK", 17, DESIGN_HEIGHT - 28, TEXT_DISABLED, false);
        String version = "RIPPERDOC // MK.IV";
        graphics.text(this.font, version, DESIGN_WIDTH - 17 - this.font.width(version),
                DESIGN_HEIGHT - 28, TEXT_DISABLED, false);
    }

    private void renderHeader(GuiGraphicsExtractor graphics) {
        Player player = Minecraft.getInstance().player;
        int level = player == null ? 0 : player.experienceLevel;
        int ram = player == null ? 0 : RamAttachments.get(player);
        int installed = installedCount(currentData());

        graphics.text(this.font, level + " LEVEL", 38, 11, CYAN, false);
        graphics.fill(38, 25, 96, 27, 0xFF26363A);
        if (player != null) {
            graphics.fill(38, 25, 38 + Math.round(58 * player.experienceProgress), 27, CYAN);
        }
        graphics.text(this.font, ram + " RAM", 118, 11, GREEN, false);

        graphics.centeredText(this.font, "CYBERWARE", DESIGN_WIDTH / 2, 25, CYAN);

        String capacity = "CAPACITY  " + installed + "/" + BodySlot.VALUES.length;
        graphics.text(this.font, capacity, 905 - this.font.width(capacity), 11, RED, false);
        graphics.horizontalLine(26, DESIGN_WIDTH - 27, 36, RED);
        graphics.fill(26, 34, 97, 37, RED);
    }

    private void renderScannerBackdrop(GuiGraphicsExtractor graphics) {
        int centerX = 480;
        int centerY = 286;
        drawCircle(graphics, centerX, centerY, 124, RED_FAINT);
        drawCircle(graphics, centerX, centerY, 103, 0x3836E7F2);
        drawCircle(graphics, centerX, centerY, 82, 0x343D1820);
        for (int angle = 0; angle < 360; angle += 30) {
            double radians = Math.toRadians(angle);
            int x = centerX + (int) Math.round(Math.cos(radians) * 124);
            int y = centerY + (int) Math.round(Math.sin(radians) * 124);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, angle % 60 == 0 ? CYAN_DIM : RED_DIM);
        }

        graphics.verticalLine(centerX, 55, 466, RED_FAINT);
        graphics.horizontalLine(366, 594, centerY, RED_FAINT);
        graphics.text(this.font, "MODEL_V", 365, 53, RED_DIM, false);
        graphics.text(this.font, "BIO_MONITOR", 548, 53, RED_DIM, false);
    }

    private void renderConnection(GuiGraphicsExtractor graphics, GroupSpec group) {
        int socketsWidth = group.socketCount() * SOCKET_WIDTH
                + Math.max(0, group.socketCount() - 1) * SOCKET_GAP;
        int startX = group.side() == Side.LEFT ? group.x() + socketsWidth : group.x();
        int startY = group.y() + SOCKET_HEIGHT / 2;
        int bendX = group.side() == Side.LEFT ? Math.min(410, startX + 54) : Math.max(550, startX - 44);
        int color = group.slot() == selectedSlot && detailsOpen ? CYAN_DIM : RED_FAINT;

        drawLine(graphics, startX, startY, bendX, startY, 1, color);
        drawLine(graphics, bendX, startY, group.targetX(), group.targetY(), 1, color);
        graphics.fill(group.targetX() - 2, group.targetY() - 2,
                group.targetX() + 3, group.targetY() + 3, color);
        graphics.fill(group.targetX() - 1, group.targetY() - 1,
                group.targetX() + 2, group.targetY() + 2, BACKGROUND_BOTTOM);
    }

    private void renderAnatomyModel(GuiGraphicsExtractor graphics) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                ANATOMY_TEXTURE,
                362,
                47,
                0.0F,
                0.0F,
                236,
                430,
                ANATOMY_TEXTURE_WIDTH,
                ANATOMY_TEXTURE_HEIGHT,
                ANATOMY_TEXTURE_WIDTH,
                ANATOMY_TEXTURE_HEIGHT);
    }

    private void renderPlayerScanOverlay(GuiGraphicsExtractor graphics) {
        int anatomyRed = 0x68FF4A4F;
        int anatomyCyan = 0x5836E7F2;
        drawCircle(graphics, 480, 111, 21, anatomyRed);
        drawLine(graphics, 480, 132, 480, 292, 1, anatomyCyan);
        drawLine(graphics, 448, 158, 512, 158, 1, anatomyRed);
        drawLine(graphics, 450, 160, 425, 258, 1, anatomyRed);
        drawLine(graphics, 510, 160, 535, 258, 1, anatomyRed);
        drawLine(graphics, 458, 292, 502, 292, 1, anatomyCyan);
        drawLine(graphics, 464, 292, 452, 440, 1, anatomyRed);
        drawLine(graphics, 496, 292, 508, 440, 1, anatomyRed);
        for (int y = 154; y <= 292; y += 23) {
            graphics.fill(478, y - 2, 483, y + 3, y % 46 == 0 ? CYAN_DIM : RED_DIM);
        }

        int scanY = 88 + (int) ((Util.getMillis() / 28L) % 342L);
        graphics.fill(397, scanY, 563, scanY + 1, 0x6E36E7F2);
        graphics.fill(420, scanY + 1, 540, scanY + 2, 0x3036E7F2);
        graphics.text(this.font, "BIOMETRIC SCAN // LIVE", 392, 455, GREEN, false);
        graphics.text(this.font, "NEURAL SYNC 100%", 445, 467, CYAN_DIM, false);
    }

    private void renderGroup(GuiGraphicsExtractor graphics, GroupSpec group, int groupIndex,
                             double mouseX, double mouseY, int rawMouseX, int rawMouseY) {
        boolean interactive = group.slot() != null;
        boolean hovered = groupHitRect(group).contains(mouseX, mouseY);
        boolean selected = interactive && detailsOpen && group.slot() == selectedSlot;
        int edge = selected ? CYAN : hovered ? RED_BRIGHT : RED_DIM;

        CyberwareData data = currentData();
        Cyberware installed = interactive && data != null ? data.get(group.slot()) : null;
        for (int socketIndex = 0; socketIndex < group.socketCount(); socketIndex++) {
            int socketX = group.x() + socketIndex * (SOCKET_WIDTH + SOCKET_GAP);
            Rect socket = new Rect(socketX, group.y(), SOCKET_WIDTH, SOCKET_HEIGHT);
            Cyberware cyberware = socketIndex == 0 ? installed : null;
            Glyph glyph = !interactive && (socketIndex == 0 || group.glyph() == Glyph.BONE)
                    ? group.glyph() : null;
            renderSocket(graphics, socket, edge, hovered, cyberware, glyph, groupIndex);
        }

        renderGroupLabel(graphics, group, selected ? CYAN : RED, interactive);
        if (hovered) {
            graphics.requestCursor(interactive ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
            Rect installedIcon = new Rect(group.x(), group.y(), SOCKET_WIDTH, SOCKET_HEIGHT);
            if (!detailsOpen && installed != null && installedIcon.contains(mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(
                        this.font, cyberwareItemStack(installed), rawMouseX, rawMouseY);
            }
        }
    }

    private void renderGroupLabel(GuiGraphicsExtractor graphics, GroupSpec group,
                                  int titleColor, boolean interactive) {
        int socketsWidth = group.socketCount() * SOCKET_WIDTH
                + Math.max(0, group.socketCount() - 1) * SOCKET_GAP;
        String[] labelLines = group.label().split("\\n");
        int labelEdge = group.side() == Side.LEFT ? group.x() - 8 : group.x() + socketsWidth + 8;
        for (int line = 0; line < labelLines.length; line++) {
            String label = labelLines[line];
            int labelX = group.side() == Side.LEFT ? labelEdge - this.font.width(label) : labelEdge;
            graphics.text(this.font, label, labelX, group.y() + 3 + line * 10, titleColor, false);
        }

        int available = interactive ? availableOptions(group.slot()) : 0;
        String status = available > 0 ? "AVAILABLE MODS" : "MODS UNAVAILABLE";
        int statusY = group.y() + (labelLines.length > 1 ? 29 : 23);
        if (group.side() == Side.LEFT) {
            int statusRight = group.x() - 8;
            if (available > 0) {
                Rect badge = new Rect(statusRight - 11, statusY - 2, 11, 11);
                renderCountBadge(graphics, badge, available);
                statusRight = badge.x() - 4;
            }
            graphics.text(this.font, status, statusRight - this.font.width(status),
                    statusY, available > 0 ? CYAN_DIM : TEXT_DISABLED, false);
        } else {
            int statusX = group.x() + socketsWidth + 8;
            graphics.text(this.font, status, statusX, statusY,
                    available > 0 ? CYAN_DIM : TEXT_DISABLED, false);
            if (available > 0) {
                Rect badge = new Rect(statusX + this.font.width(status) + 4, statusY - 2, 11, 11);
                renderCountBadge(graphics, badge, available);
            }
        }
    }

    private void renderCountBadge(GuiGraphicsExtractor graphics, Rect badge, int count) {
        graphics.fill(badge.x(), badge.y(), badge.right(), badge.bottom(), CYAN_DIM);
        String countText = Integer.toString(count);
        graphics.centeredText(this.font, countText, badge.x() + badge.width() / 2,
                badge.y() + 1, BACKGROUND_BOTTOM);
    }

    private void renderSocket(GuiGraphicsExtractor graphics, Rect socket, int edge, boolean hovered,
                              Cyberware cyberware, Glyph glyph, int seed) {
        graphics.fill(socket.x(), socket.y(), socket.right(), socket.bottom(),
                cyberware != null ? 0xE20B242B : hovered ? PANEL_HOVER : PANEL_SOFT);
        graphics.outline(socket.x(), socket.y(), socket.width(), socket.height(), RED_FAINT);
        graphics.fill(socket.x(), socket.y(), socket.x() + 3, socket.bottom(),
                cyberware == null ? RED_DIM : CYAN);
        graphics.fill(socket.x() + 5, socket.y() + 4, socket.x() + 8, socket.y() + 7, CYAN_DIM);
        graphics.fill(socket.x() + 10, socket.y() + 4, socket.x() + 13, socket.y() + 7, GREEN);
        graphics.fill(socket.x() + 15, socket.y() + 4, socket.x() + 18, socket.y() + 7, RED);
        drawCornerBrackets(graphics, socket, edge, 6);

        if (cyberware != null) {
            renderCyberwareItem(graphics, cyberware,
                    new Rect(socket.x() + 8, socket.y() + 9,
                            socket.width() - 16, socket.height() - 14));
            drawLine(graphics, socket.right() - 10, socket.bottom() - 1,
                    socket.right() - 1, socket.bottom() - 10, 2, CYAN);
        } else if (glyph != null) {
            renderGlyph(graphics, glyph, socket, seed);
        } else {
            int centerX = socket.x() + socket.width() / 2;
            int centerY = socket.y() + socket.height() / 2 + 2;
            graphics.horizontalLine(centerX - 6, centerX + 6, centerY, RED_DIM);
            graphics.verticalLine(centerX, centerY - 6, centerY + 6, RED_DIM);
        }
    }

    private void renderGlyph(GuiGraphicsExtractor graphics, Glyph glyph, Rect socket, int seed) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(socket.x() + 9, socket.y() + 8);
        graphics.pose().scale(1.55F, 1.55F);
        int x = 0;
        int y = 0;
        int color = seed % 3 == 0 ? CYAN_DIM : TEXT_DIM;
        switch (glyph) {
            case BRAIN -> {
                drawCircle(graphics, x + 11, y + 9, 8, color);
                drawLine(graphics, x + 11, y + 2, x + 11, y + 17, 1, color);
                drawLine(graphics, x + 4, y + 8, x + 18, y + 8, 1, color);
            }
            case EYE -> {
                drawLine(graphics, x, y + 9, x + 11, y + 2, 1, color);
                drawLine(graphics, x + 11, y + 2, x + 22, y + 9, 1, color);
                drawLine(graphics, x + 22, y + 9, x + 11, y + 16, 1, color);
                drawLine(graphics, x + 11, y + 16, x, y + 9, 1, color);
                graphics.fill(x + 9, y + 7, x + 14, y + 12, CYAN);
            }
            case HEART -> {
                drawLine(graphics, x + 1, y + 4, x + 11, y + 17, 2, color);
                drawLine(graphics, x + 21, y + 4, x + 11, y + 17, 2, color);
                drawLine(graphics, x + 1, y + 4, x + 7, y + 1, 2, color);
                drawLine(graphics, x + 21, y + 4, x + 15, y + 1, 2, color);
            }
            case SHIELD -> {
                graphics.outline(x + 3, y, 16, 17, color);
                drawLine(graphics, x + 3, y + 12, x + 11, y + 20, 1, color);
                drawLine(graphics, x + 19, y + 12, x + 11, y + 20, 1, color);
            }
            case BONE -> {
                drawLine(graphics, x + 3, y + 17, x + 19, y + 1, 3, color);
                graphics.fill(x, y + 15, x + 6, y + 20, color);
                graphics.fill(x + 17, y, x + 22, y + 5, color);
            }
            case HAND -> {
                graphics.outline(x + 6, y + 8, 12, 11, color);
                for (int finger = 0; finger < 4; finger++) {
                    graphics.fill(x + 6 + finger * 3, y + 1 + finger % 2,
                            x + 8 + finger * 3, y + 10, color);
                }
            }
            case SKIN -> {
                for (int line = 0; line < 4; line++) {
                    graphics.horizontalLine(x + line, x + 21 - line, y + 3 + line * 4, color);
                }
            }
            case CHIP, DRIVE, ARM, LEG -> {
                graphics.outline(x + 3, y + 2, 16, 16, color);
                graphics.fill(x + 7, y + 6, x + 15, y + 14, CYAN_DIM);
                for (int pin = 0; pin < 4; pin++) {
                    graphics.fill(x, y + 3 + pin * 4, x + 4, y + 4 + pin * 4, color);
                    graphics.fill(x + 18, y + 3 + pin * 4, x + 22, y + 4 + pin * 4, color);
                }
            }
        }
        graphics.pose().popMatrix();
    }

    private void renderDetailOverlay(GuiGraphicsExtractor graphics, double mouseX, double mouseY,
                                     int rawMouseX, int rawMouseY) {
        Rect panel = detailPanel();
        Rect close = detailCloseRect();
        graphics.fill(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT, 0x72050609);
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), PANEL);
        graphics.outline(panel.x(), panel.y(), panel.width(), panel.height(), RED);
        drawCornerBrackets(graphics, panel, CYAN, 12);

        String slotName = selectedSlot.displayName().toUpperCase(Locale.ROOT);
        graphics.text(this.font, ellipsize(slotName, panel.width() - 42),
                panel.x() + 11, panel.y() + 11, RED, false);
        graphics.text(this.font, "CYBERWARE CATALOG", panel.x() + 11,
                panel.y() + 25, CYAN_DIM, false);
        graphics.text(this.font, "X", close.x() + 4, close.y() + 3, RED, false);

        List<Cyberware> options = Cyberware.forSlot(selectedSlot);
        for (int index = 0; index < options.size(); index++) {
            renderOptionCard(graphics, options.get(index), optionRect(index, options.size()),
                    mouseX, mouseY, rawMouseX, rawMouseY);
        }

        renderDetailAction(graphics, mouseX, mouseY);
        if (close.contains(mouseX, mouseY)) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void renderOptionCard(GuiGraphicsExtractor graphics, Cyberware cyberware, Rect card,
                                  double mouseX, double mouseY,
                                  int rawMouseX, int rawMouseY) {
        CyberwareData data = currentData();
        boolean installed = data != null && data.get(cyberware.slot()) == cyberware;
        boolean selected = cyberware == selectedCyberware;
        boolean hovered = card.contains(mouseX, mouseY);
        int edge = installed ? GREEN : selected ? CYAN : hovered ? RED_BRIGHT : RED_DIM;

        graphics.fill(card.x(), card.y(), card.right(), card.bottom(),
                selected ? 0xF01A1015 : hovered ? PANEL_HOVER : PANEL_SOFT);
        graphics.outline(card.x(), card.y(), card.width(), card.height(), RED_FAINT);
        drawCornerBrackets(graphics, card, edge, 8);
        if (installed) {
            graphics.fill(card.x(), card.y(), card.x() + 3, card.bottom(), GREEN);
        }

        Rect iconBox = new Rect(card.x() + 8, card.y() + 10, 46, 46);
        graphics.fill(iconBox.x(), iconBox.y(), iconBox.right(), iconBox.bottom(), 0xE30A171C);
        drawCornerBrackets(graphics, iconBox, edge, 6);
        renderCyberwareItem(graphics, cyberware,
                new Rect(iconBox.x() + 7, iconBox.y() + 7,
                        iconBox.width() - 14, iconBox.height() - 14));

        String name = cyberware.displayName().toUpperCase(Locale.ROOT);
        int textX = card.x() + 62;
        int textWidth = Math.max(1, card.right() - textX - 7);
        graphics.text(this.font, ellipsize(name, textWidth),
                textX, card.y() + 10, selected ? CYAN : TEXT, false);

        int owned = inventoryCount(cyberware);
        String status = installed ? "INSTALLED" : owned > 0 ? owned + " OWNED" : "ITEM REQUIRED";
        int statusColor = installed ? GREEN : owned > 0 ? CYAN_DIM : TEXT_DISABLED;
        graphics.text(this.font, ellipsize(status, textWidth),
                textX, card.y() + 24, statusColor, false);

        Component description = Component.translatable("tooltip.cyberdeck.cyberware." + cyberware.id());
        List<FormattedCharSequence> lines = this.font.split(description, textWidth);
        int maxLines = Math.max(1, (card.height() - 45) / 9);
        for (int line = 0; line < lines.size() && line < maxLines; line++) {
            graphics.text(this.font, lines.get(line), textX,
                    card.y() + 39 + line * 9, TEXT_DIM, false);
        }

        if (hovered) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
            if (iconBox.contains(mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(
                        this.font, cyberwareItemStack(cyberware), rawMouseX, rawMouseY);
            }
        }
    }

    private void renderDetailAction(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        Cyberware cyberware = selectedCyberware;
        if (cyberware == null) {
            return;
        }

        CyberwareData data = currentData();
        Cyberware active = data == null ? null : data.get(cyberware.slot());
        boolean installed = active == cyberware;
        boolean replacementBlocked = active != null && !installed;
        int owned = inventoryCount(cyberware);
        boolean locked = Util.getMillis() < interactionLockedUntil;
        boolean enabled = !locked && (installed || owned > 0 && !replacementBlocked);
        Rect panel = detailPanel();
        Rect actionRect = detailActionRect();
        boolean hovered = actionRect.contains(mouseX, mouseY);
        int edge = enabled ? installed ? GREEN : CYAN : TEXT_DISABLED;

        String status = installed ? "ACTIVE AUGMENT"
                : replacementBlocked ? "ACTIVE: " + active.displayName().toUpperCase(Locale.ROOT)
                : owned + " IN INVENTORY";
        graphics.text(this.font, status, panel.x() + 12, actionRect.y() - 13,
                installed ? GREEN : TEXT_DIM, false);
        graphics.fill(actionRect.x(), actionRect.y(), actionRect.right(), actionRect.bottom(),
                enabled && hovered ? 0xEE173B41 : 0xDB10171B);
        graphics.outline(actionRect.x(), actionRect.y(), actionRect.width(), actionRect.height(), edge);

        String action;
        if (locked) {
            action = "SYNCING...";
        } else if (installed) {
            action = "REMOVE CYBERWARE";
        } else if (replacementBlocked) {
            action = "REMOVE ACTIVE FIRST";
        } else if (owned > 0) {
            action = "INSTALL CYBERWARE";
        } else {
            action = "ITEM REQUIRED";
        }
        graphics.centeredText(this.font, action,
                actionRect.x() + actionRect.width() / 2, actionRect.y() + 7,
                enabled ? TEXT : TEXT_DISABLED);

        if (hovered) {
            graphics.requestCursor(enabled ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
        }
    }

    private void renderFooter(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        graphics.horizontalLine(25, DESIGN_WIDTH - 26, 500, RED_DIM);
        KeyMapping mapping = KeyMapping.get("key.cyberdeck.open_cyberware");
        String key = mapping == null ? "G" : mapping.getTranslatedKeyMessage().getString();
        graphics.text(this.font, "[" + key + "] CYBERWARE", 29, 510, CYAN_DIM, false);
        String hint = detailsOpen ? "SELECT AUGMENT  //  INSTALL OR REMOVE" : "SELECT BODY SYSTEM";
        graphics.centeredText(this.font, hint, DESIGN_WIDTH / 2, 510, TEXT_DISABLED);

        boolean hovered = BACK_BUTTON.contains(mouseX, mouseY);
        String label = detailsOpen ? "< OVERVIEW" : "< BACK";
        graphics.text(this.font, label, BACK_BUTTON.right() - this.font.width(label),
                BACK_BUTTON.y() + 5, hovered ? CYAN : RED, false);
        if (hovered) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private Rect optionRect(int index, int count) {
        Rect panel = detailPanel();
        int gap = 5;
        int startY = panel.y() + 48;
        int availableHeight = detailActionRect().y() - startY - 10;
        int maximumHeight = count == 1 ? 180 : count == 2 ? 145 : 106;
        int cardHeight = Math.min(maximumHeight,
                (availableHeight - gap * Math.max(0, count - 1)) / Math.max(1, count));
        return new Rect(panel.x() + 10, startY + index * (cardHeight + gap),
                panel.width() - 20, cardHeight);
    }

    private Rect detailPanel() {
        return selectedGroupSide() == Side.LEFT
                ? new Rect(716, 68, 222, 410)
                : new Rect(22, 68, 222, 410);
    }

    private Rect detailCloseRect() {
        Rect panel = detailPanel();
        return new Rect(panel.right() - 23, panel.y() + 8, 15, 15);
    }

    private Rect detailActionRect() {
        Rect panel = detailPanel();
        return new Rect(panel.x() + 12, panel.bottom() - 34, panel.width() - 24, 22);
    }

    private Side selectedGroupSide() {
        for (GroupSpec group : GROUPS) {
            if (group.slot() == selectedSlot) {
                return group.side();
            }
        }
        return Side.RIGHT;
    }

    private Rect groupHitRect(GroupSpec group) {
        int socketsWidth = group.socketCount() * SOCKET_WIDTH
                + Math.max(0, group.socketCount() - 1) * SOCKET_GAP;
        if (group.side() == Side.LEFT) {
            return new Rect(group.x() - 145, group.y() - 4,
                    socketsWidth + 145, SOCKET_HEIGHT + 8);
        }
        return new Rect(group.x(), group.y() - 4,
                socketsWidth + 145, SOCKET_HEIGHT + 8);
    }

    private void performSelectedAction() {
        if (selectedCyberware == null || Util.getMillis() < interactionLockedUntil) {
            return;
        }

        CyberwareData data = currentData();
        Cyberware installed = data == null ? null : data.get(selectedCyberware.slot());
        if (installed == selectedCyberware) {
            ClientPacketDistributor.sendToServer(new RemoveCyberwarePacket(selectedSlot.ordinal()));
            interactionLockedUntil = Util.getMillis() + ACTION_LOCK_MS;
        } else if (installed == null && inventoryCount(selectedCyberware) > 0) {
            ClientPacketDistributor.sendToServer(new EquipCyberwarePacket(selectedCyberware.id()));
            interactionLockedUntil = Util.getMillis() + ACTION_LOCK_MS;
        }
    }

    private void chooseCyberwareForSlot(BodySlot slot) {
        CyberwareData data = currentData();
        Cyberware installed = data == null ? null : data.get(slot);
        if (installed != null) {
            selectedCyberware = installed;
            return;
        }

        List<Cyberware> options = Cyberware.forSlot(slot);
        selectedCyberware = options.stream()
                .filter(option -> inventoryCount(option) > 0)
                .findFirst()
                .orElse(options.isEmpty() ? null : options.getFirst());
    }

    private void ensureCyberwareSelection() {
        if (selectedCyberware == null || selectedCyberware.slot() != selectedSlot) {
            chooseCyberwareForSlot(selectedSlot);
        }
    }

    private CyberwareData currentData() {
        Player player = Minecraft.getInstance().player;
        return player == null ? null : CyberwareAttachments.get(player);
    }

    private int installedCount(CyberwareData data) {
        if (data == null) {
            return 0;
        }
        int count = 0;
        for (BodySlot slot : BodySlot.VALUES) {
            if (data.get(slot) != null) {
                count++;
            }
        }
        return count;
    }

    private int availableOptions(BodySlot slot) {
        int count = 0;
        for (Cyberware cyberware : Cyberware.forSlot(slot)) {
            if (inventoryCount(cyberware) > 0) {
                count++;
            }
        }
        return count;
    }

    private int inventoryCount(Cyberware cyberware) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }

        int count = 0;
        for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (stack.getItem() == CyberwareItems.item(cyberware).get()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private ItemStack cyberwareItemStack(Cyberware cyberware) {
        return new ItemStack(CyberwareItems.item(cyberware).get());
    }

    /** Renders the same registered item model players see in their inventory. */
    private void renderCyberwareItem(GuiGraphicsExtractor graphics, Cyberware cyberware,
                                     Rect bounds) {
        ItemStack stack = cyberwareItemStack(cyberware);
        float itemSize = Math.min(32.0F, Math.min(bounds.width(), bounds.height()));
        float scale = itemSize / 16.0F;
        float x = bounds.x() + (bounds.width() - itemSize) * 0.5F;
        float y = bounds.y() + (bounds.height() - itemSize) * 0.5F;

        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.item(stack, 0, 0, cyberware.ordinal());
        graphics.pose().popMatrix();
    }

    private void drawCircle(GuiGraphicsExtractor graphics, int centerX, int centerY,
                            int radius, int color) {
        int step = radius > 20 ? 3 : 12;
        for (int angle = 0; angle < 360; angle += step) {
            double radians = Math.toRadians(angle);
            int x = centerX + (int) Math.round(Math.cos(radians) * radius);
            int y = centerY + (int) Math.round(Math.sin(radians) * radius);
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void drawLine(GuiGraphicsExtractor graphics, float x1, float y1,
                          float x2, float y2, int thickness, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.5F) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(x1, y1);
        graphics.pose().rotate((float) Math.atan2(dy, dx));
        graphics.fill(0, 0, (int) Math.ceil(length), Math.max(1, thickness), color);
        graphics.pose().popMatrix();
    }

    private void drawCornerBrackets(GuiGraphicsExtractor graphics, Rect rect, int color, int length) {
        int actual = Math.min(length, Math.min(rect.width(), rect.height()) / 2);
        graphics.horizontalLine(rect.x(), rect.x() + actual, rect.y(), color);
        graphics.verticalLine(rect.x(), rect.y(), rect.y() + actual, color);
        graphics.horizontalLine(rect.right() - actual - 1, rect.right() - 1, rect.y(), color);
        graphics.verticalLine(rect.right() - 1, rect.y(), rect.y() + actual, color);
        graphics.horizontalLine(rect.x(), rect.x() + actual, rect.bottom() - 1, color);
        graphics.verticalLine(rect.x(), rect.bottom() - actual - 1, rect.bottom() - 1, color);
        graphics.horizontalLine(rect.right() - actual - 1, rect.right() - 1, rect.bottom() - 1, color);
        graphics.verticalLine(rect.right() - 1, rect.bottom() - actual - 1, rect.bottom() - 1, color);
    }

    private String ellipsize(String value, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (this.font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int contentWidth = Math.max(0, maxWidth - this.font.width(suffix));
        return this.font.plainSubstrByWidth(value, contentWidth) + suffix;
    }

    private Viewport viewport() {
        float scale = Math.min(this.width / (float) DESIGN_WIDTH, this.height / (float) DESIGN_HEIGHT);
        scale = Math.max(0.01F, scale);
        float offsetX = (this.width - DESIGN_WIDTH * scale) / 2.0F;
        float offsetY = (this.height - DESIGN_HEIGHT * scale) / 2.0F;
        return new Viewport(scale, offsetX, offsetY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum Side {
        LEFT,
        RIGHT
    }

    private enum Glyph {
        BRAIN,
        EYE,
        HEART,
        SHIELD,
        CHIP,
        SKIN,
        DRIVE,
        BONE,
        HAND,
        ARM,
        LEG
    }

    private record GroupSpec(
            String label,
            BodySlot slot,
            int x,
            int y,
            int socketCount,
            Side side,
            Glyph glyph,
            int targetX,
            int targetY) {
    }

    private record Viewport(float scale, float offsetX, float offsetY) {
        double toDesignX(double screenX) {
            return (screenX - offsetX) / scale;
        }

        double toDesignY(double screenY) {
            return (screenY - offsetY) / scale;
        }

        int toScreenX(int designX) {
            return Math.round(offsetX + designX * scale);
        }

        int toScreenY(int designY) {
            return Math.round(offsetY + designY * scale);
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
