package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.cyberware.BodySlot;
import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareCapacity;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.cyberware.CyberwareFamily;
import com.example.cyberdeck.cyberware.CyberwareItems;
import com.example.cyberdeck.cyberware.SlotUnlock;
import com.example.cyberdeck.network.EquipCyberwarePacket;
import com.example.cyberdeck.network.RemoveCyberwarePacket;
import com.example.cyberdeck.ram.RamAttachments;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import java.util.List;
import java.util.Locale;

/** Full-body ripperdoc UI with indexed sockets, family paging and tier-specific effects. */
public final class CyberwareScreen extends Screen {
    private static final int DESIGN_WIDTH = 960;
    private static final int DESIGN_HEIGHT = 540;
    private static final int SOCKET_WIDTH = 46;
    private static final int SOCKET_HEIGHT = 44;
    private static final int SOCKET_GAP = 2;
    private static final int ACTION_LOCK_MS = 450;
    private static final int FAMILIES_PER_PAGE = 8;
    private static final int ANATOMY_TEXTURE_WIDTH = 330;
    private static final int ANATOMY_TEXTURE_HEIGHT = 537;
    private static final Identifier ANATOMY_TEXTURE = Identifier.fromNamespaceAndPath(
            "cyberdeck", "textures/gui/anatomy_scan.png");

    private static final int BACKGROUND_TOP = 0xFF14070B;
    private static final int BACKGROUND_BOTTOM = 0xFF02070A;
    private static final int PANEL = 0xF6080B10;
    private static final int PANEL_SOFT = 0xE0120C12;
    private static final int PANEL_HOVER = 0xEE241117;
    private static final int RED = 0xFFFF4A4F;
    private static final int RED_BRIGHT = 0xFFFF6A69;
    private static final int RED_DIM = 0xFF813038;
    private static final int RED_FAINT = 0x493D1820;
    private static final int CYAN = 0xFF36E7F2;
    private static final int CYAN_DIM = 0xFF4C9FA7;
    private static final int GREEN = 0xFF35E781;
    private static final int GOLD = 0xFFFFC857;
    private static final int TEXT = 0xFFE9EBEB;
    private static final int TEXT_DIM = 0xFF789096;
    private static final int TEXT_DISABLED = 0xFF46555A;

    private static final Rect BACK_BUTTON = new Rect(866, 505, 70, 20);
    private static final Rect DETAIL_PANEL = new Rect(56, 48, 848, 438);
    private static final Rect DETAIL_CLOSE = new Rect(878, 58, 16, 16);
    private static final Rect FAMILY_PREV = new Rect(76, 418, 84, 20);
    private static final Rect FAMILY_NEXT = new Rect(252, 418, 84, 20);
    private static final Rect DETAIL_ACTION = new Rect(610, 447, 270, 24);

    private static final GroupSpec[] GROUPS = {
            new GroupSpec(BodySlot.FRONTAL_CORTEX, 190, 70, Side.LEFT, 456, 96),
            new GroupSpec(BodySlot.FACE, 238, 155, Side.LEFT, 455, 120),
            new GroupSpec(BodySlot.CIRCULATORY_SYSTEM, 190, 240, Side.LEFT, 444, 204),
            new GroupSpec(BodySlot.NERVOUS_SYSTEM, 190, 325, Side.LEFT, 454, 258),
            new GroupSpec(BodySlot.INTEGUMENTARY_SYSTEM, 190, 410, Side.LEFT, 450, 320),
            new GroupSpec(BodySlot.OPERATING_SYSTEM, 630, 70, Side.RIGHT, 524, 145),
            new GroupSpec(BodySlot.SKELETON, 630, 155, Side.RIGHT, 523, 210),
            new GroupSpec(BodySlot.HANDS, 630, 240, Side.RIGHT, 558, 255),
            new GroupSpec(BodySlot.ARMS, 630, 325, Side.RIGHT, 553, 284),
            new GroupSpec(BodySlot.LEGS, 630, 410, Side.RIGHT, 530, 366)
    };

    private BodySlot selectedSlot = BodySlot.OPERATING_SYSTEM;
    private int selectedSocket;
    private String selectedFamilyId;
    private Cyberware selectedCyberware;
    private int familyPage;
    private boolean detailsOpen;
    private long interactionLockedUntil;

    public CyberwareScreen() {
        super(Component.translatable("screen.cyberdeck.cyberware"));
    }

    @Override
    protected void init() {
        chooseForSocket();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                  float partialTick) {
        graphics.fillGradient(0, 0, this.width, this.height, BACKGROUND_TOP, BACKGROUND_BOTTOM);
        for (int y = 2; y < this.height; y += 4) {
            graphics.fill(0, y, this.width, y + 1, 0x09000000);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        Viewport viewport = viewport();
        double designMouseX = viewport.toDesignX(mouseX);
        double designMouseY = viewport.toDesignY(mouseY);
        ensureSelection();

        graphics.pose().pushMatrix();
        graphics.pose().translate(viewport.offsetX(), viewport.offsetY());
        graphics.pose().scale(viewport.scale(), viewport.scale());
        renderFrame(graphics);
        renderHeader(graphics);
        renderAnatomy(graphics);
        for (GroupSpec group : GROUPS) {
            renderConnection(graphics, group);
        }
        for (GroupSpec group : GROUPS) {
            renderGroup(graphics, group, designMouseX, designMouseY);
        }
        if (detailsOpen) {
            graphics.nextStratum();
            renderCatalog(graphics, designMouseX, designMouseY);
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
            return clickCatalog(mouseX, mouseY);
        }
        if (BACK_BUTTON.contains(mouseX, mouseY)) {
            onClose();
            return true;
        }
        for (GroupSpec group : GROUPS) {
            for (int socket = 0; socket < group.slot().maximumSockets(); socket++) {
                if (socketRect(group, socket).contains(mouseX, mouseY)) {
                    openCatalog(group.slot(), socket);
                    return true;
                }
            }
            if (groupHitRect(group).contains(mouseX, mouseY)) {
                openCatalog(group.slot(), firstUsefulSocket(group.slot()));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickCatalog(double mouseX, double mouseY) {
        if (DETAIL_CLOSE.contains(mouseX, mouseY) || BACK_BUTTON.contains(mouseX, mouseY)) {
            detailsOpen = false;
            return true;
        }
        List<CyberwareFamily> families = families();
        int pageCount = pageCount(families.size());
        if (FAMILY_PREV.contains(mouseX, mouseY) && familyPage > 0) {
            familyPage--;
            return true;
        }
        if (FAMILY_NEXT.contains(mouseX, mouseY) && familyPage + 1 < pageCount) {
            familyPage++;
            return true;
        }
        int start = familyPage * FAMILIES_PER_PAGE;
        int end = Math.min(families.size(), start + FAMILIES_PER_PAGE);
        for (int index = start; index < end; index++) {
            if (familyRect(index - start).contains(mouseX, mouseY)) {
                selectFamily(families.get(index));
                return true;
            }
        }
        CyberwareFamily family = selectedFamily();
        if (family != null) {
            for (int index = 0; index < family.variants().size(); index++) {
                if (tierRect(index).contains(mouseX, mouseY)) {
                    selectedCyberware = family.variants().get(index);
                    return true;
                }
            }
        }
        if (DETAIL_ACTION.contains(mouseX, mouseY)) {
            performSelectedAction();
            return true;
        }
        if (!DETAIL_PANEL.contains(mouseX, mouseY)) {
            detailsOpen = false;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && detailsOpen) {
            detailsOpen = false;
            return true;
        }
        return super.keyPressed(event);
    }

    private void openCatalog(BodySlot slot, int socket) {
        selectedSlot = slot;
        selectedSocket = Math.max(0, Math.min(socket, slot.maximumSockets() - 1));
        familyPage = 0;
        chooseForSocket();
        if (selectedFamilyId != null) {
            List<CyberwareFamily> families = families();
            for (int index = 0; index < families.size(); index++) {
                if (families.get(index).id().equals(selectedFamilyId)) {
                    familyPage = index / FAMILIES_PER_PAGE;
                    break;
                }
            }
        }
        detailsOpen = true;
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
        graphics.text(this.font, "NEURAL_LINK", 17, DESIGN_HEIGHT - 28, TEXT_DISABLED, false);
        String version = "RIPPERDOC // CATALOG 2.0";
        graphics.text(this.font, version, DESIGN_WIDTH - 17 - this.font.width(version),
                DESIGN_HEIGHT - 28, TEXT_DISABLED, false);
    }

    private void renderHeader(GuiGraphicsExtractor graphics) {
        Player player = Minecraft.getInstance().player;
        CyberwareData data = currentData();
        int level = player == null ? 0 : player.experienceLevel;
        int ram = player == null ? 0 : RamAttachments.get(player);
        int maxRam = player == null ? RamAttachments.BASE_MAX_RAM : RamAttachments.max(player);
        int used = data == null ? 0 : data.capacityUsed();
        int maximum = player == null || data == null ? 0 : CyberwareCapacity.maximum(player, data);

        graphics.text(this.font, level + " LEVEL", 38, 11, CYAN, false);
        graphics.text(this.font, ram + "/" + maxRam + " RAM", 118, 11, GREEN, false);
        graphics.centeredText(this.font, "CYBERWARE", DESIGN_WIDTH / 2, 20, CYAN);
        String capacity = "CAPACITY  " + used + "/" + maximum;
        graphics.text(this.font, capacity, 922 - this.font.width(capacity), 11,
                used > maximum ? RED_BRIGHT : GOLD, false);
        graphics.horizontalLine(26, DESIGN_WIDTH - 27, 36, RED);
        graphics.fill(26, 34, 97, 37, RED);
    }

    private void renderAnatomy(GuiGraphicsExtractor graphics) {
        drawCircle(graphics, 480, 286, 124, RED_FAINT);
        drawCircle(graphics, 480, 286, 103, 0x3836E7F2);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ANATOMY_TEXTURE,
                362, 47, 0.0F, 0.0F, 236, 430,
                ANATOMY_TEXTURE_WIDTH, ANATOMY_TEXTURE_HEIGHT,
                ANATOMY_TEXTURE_WIDTH, ANATOMY_TEXTURE_HEIGHT);
        int scanY = 88 + (int) ((Util.getMillis() / 28L) % 342L);
        graphics.fill(397, scanY, 563, scanY + 1, 0x6E36E7F2);
        graphics.text(this.font, "BIOMETRIC SCAN // LIVE", 392, 455, GREEN, false);
    }

    private void renderConnection(GuiGraphicsExtractor graphics, GroupSpec group) {
        int socketsWidth = group.slot().maximumSockets() * SOCKET_WIDTH
                + (group.slot().maximumSockets() - 1) * SOCKET_GAP;
        int startX = group.side() == Side.LEFT ? group.x() + socketsWidth : group.x();
        int startY = group.y() + SOCKET_HEIGHT / 2;
        int bendX = group.side() == Side.LEFT ? Math.min(410, startX + 36) : Math.max(550, startX - 36);
        int color = detailsOpen && group.slot() == selectedSlot ? CYAN_DIM : RED_FAINT;
        drawLine(graphics, startX, startY, bendX, startY, 1, color);
        drawLine(graphics, bendX, startY, group.targetX(), group.targetY(), 1, color);
        graphics.fill(group.targetX() - 2, group.targetY() - 2,
                group.targetX() + 3, group.targetY() + 3, color);
    }

    private void renderGroup(GuiGraphicsExtractor graphics, GroupSpec group,
                             double mouseX, double mouseY) {
        CyberwareData data = currentData();
        int unlocked = data == null ? group.slot().baseSockets() : data.unlockedSockets(group.slot());
        boolean groupSelected = detailsOpen && group.slot() == selectedSlot;
        for (int socket = 0; socket < group.slot().maximumSockets(); socket++) {
            Rect rect = socketRect(group, socket);
            boolean hovered = rect.contains(mouseX, mouseY);
            boolean selected = groupSelected && socket == selectedSocket;
            Cyberware installed = data == null ? null : data.get(group.slot(), socket);
            boolean locked = socket >= unlocked;
            int edge = locked ? TEXT_DISABLED : selected ? CYAN : hovered ? RED_BRIGHT : RED_DIM;
            graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                    locked ? 0xE1080A0D : installed != null ? 0xE20B242B
                            : hovered ? PANEL_HOVER : PANEL_SOFT);
            graphics.outline(rect.x(), rect.y(), rect.width(), rect.height(), RED_FAINT);
            graphics.fill(rect.x(), rect.y(), rect.x() + 3, rect.bottom(),
                    locked ? TEXT_DISABLED : installed == null ? RED_DIM : CYAN);
            drawCornerBrackets(graphics, rect, edge, 6);
            if (installed != null) {
                renderCyberwareIcon(graphics, installed,
                        new Rect(rect.x() + 7, rect.y() + 6, rect.width() - 13, rect.height() - 11));
                graphics.text(this.font, installed.tier().id(), rect.x() + 4,
                        rect.bottom() - 10, GREEN, false);
            } else if (locked) {
                graphics.text(this.font, "LOCK", rect.x() + 10, rect.y() + 17,
                        TEXT_DISABLED, false);
            } else {
                graphics.centeredText(this.font, "+", rect.x() + rect.width() / 2,
                        rect.y() + 16, RED_DIM);
            }
            if (hovered) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
        renderGroupLabel(graphics, group, unlocked);
    }

    private void renderGroupLabel(GuiGraphicsExtractor graphics, GroupSpec group, int unlocked) {
        int width = group.slot().maximumSockets() * SOCKET_WIDTH
                + (group.slot().maximumSockets() - 1) * SOCKET_GAP;
        String name = group.slot().displayName().toUpperCase(Locale.ROOT);
        String sockets = unlocked + "/" + group.slot().maximumSockets() + " SLOTS";
        if (group.side() == Side.LEFT) {
            int right = group.x() - 8;
            graphics.text(this.font, name, right - this.font.width(name), group.y() + 7, RED, false);
            graphics.text(this.font, sockets, right - this.font.width(sockets), group.y() + 22,
                    unlocked == group.slot().maximumSockets() ? CYAN_DIM : GOLD, false);
        } else {
            int x = group.x() + width + 8;
            graphics.text(this.font, name, x, group.y() + 7, RED, false);
            graphics.text(this.font, sockets, x, group.y() + 22,
                    unlocked == group.slot().maximumSockets() ? CYAN_DIM : GOLD, false);
        }
    }

    private void renderCatalog(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        graphics.fill(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT, 0xA9050609);
        graphics.fill(DETAIL_PANEL.x(), DETAIL_PANEL.y(), DETAIL_PANEL.right(), DETAIL_PANEL.bottom(), PANEL);
        graphics.outline(DETAIL_PANEL.x(), DETAIL_PANEL.y(), DETAIL_PANEL.width(), DETAIL_PANEL.height(), RED);
        drawCornerBrackets(graphics, DETAIL_PANEL, CYAN, 12);
        graphics.text(this.font, "X", DETAIL_CLOSE.x() + 4, DETAIL_CLOSE.y() + 3, RED, false);

        CyberwareData data = currentData();
        int unlocked = data == null ? selectedSlot.baseSockets() : data.unlockedSockets(selectedSlot);
        boolean lockedSocket = selectedSocket >= unlocked;
        String title = selectedSlot.displayName().toUpperCase(Locale.ROOT)
                + "  //  SOCKET " + (selectedSocket + 1);
        graphics.text(this.font, title, 74, 62, RED, false);
        if (lockedSocket) {
            SlotUnlock unlock = selectedSlot.unlockForSocket(selectedSocket);
            String requirement = unlock == null ? "LOCKED" : "LOCKED — REQUIRES "
                    + unlock.displayName().toUpperCase(Locale.ROOT);
            graphics.text(this.font, requirement, 370, 62, GOLD, false);
        } else {
            graphics.text(this.font, "TIER-RESOLVED WIKI CATALOG", 370, 62, CYAN_DIM, false);
        }

        renderFamilyList(graphics, mouseX, mouseY);
        renderSelectedDetails(graphics, mouseX, mouseY, lockedSocket);
        if (DETAIL_CLOSE.contains(mouseX, mouseY)) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void renderFamilyList(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        List<CyberwareFamily> families = families();
        int start = familyPage * FAMILIES_PER_PAGE;
        int end = Math.min(families.size(), start + FAMILIES_PER_PAGE);
        graphics.text(this.font, "IMPLANT FAMILIES", 76, 82, CYAN, false);
        for (int index = start; index < end; index++) {
            CyberwareFamily family = families.get(index);
            Rect row = familyRect(index - start);
            boolean selected = family.id().equals(selectedFamilyId);
            boolean hovered = row.contains(mouseX, mouseY);
            Cyberware representative = representative(family);
            int owned = family.variants().stream().mapToInt(this::inventoryCount).sum();
            boolean installed = currentData() != null
                    && currentData().allInstalled().stream().anyMatch(cw -> cw.familyId().equals(family.id()));
            graphics.fill(row.x(), row.y(), row.right(), row.bottom(),
                    selected ? 0xEF16232A : hovered ? PANEL_HOVER : PANEL_SOFT);
            graphics.outline(row.x(), row.y(), row.width(), row.height(), RED_FAINT);
            graphics.fill(row.x(), row.y(), row.x() + 3, row.bottom(),
                    installed ? GREEN : selected ? CYAN : RED_DIM);
            renderCyberwareIcon(graphics, representative,
                    new Rect(row.x() + 7, row.y() + 4, 28, 28));
            graphics.text(this.font, ellipsize(family.displayName().toUpperCase(Locale.ROOT), 190),
                    row.x() + 41, row.y() + 7, selected ? CYAN : TEXT, false);
            String status = installed ? "INSTALLED" : owned > 0 ? owned + " OWNED" : "NOT OWNED";
            graphics.text(this.font, status, row.x() + 41, row.y() + 21,
                    installed ? GREEN : owned > 0 ? CYAN_DIM : TEXT_DISABLED, false);
            if (hovered) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
        int pages = pageCount(families.size());
        renderPageButton(graphics, FAMILY_PREV, "< PREV", familyPage > 0, mouseX, mouseY);
        renderPageButton(graphics, FAMILY_NEXT, "NEXT >", familyPage + 1 < pages, mouseX, mouseY);
        String page = (familyPage + 1) + " / " + pages;
        graphics.centeredText(this.font, page, 206, 424, TEXT_DIM);
    }

    private void renderSelectedDetails(GuiGraphicsExtractor graphics, double mouseX, double mouseY,
                                       boolean lockedSocket) {
        Cyberware cyberware = selectedCyberware;
        if (cyberware == null) {
            graphics.text(this.font, "NO CATALOG ENTRY", 370, 104, TEXT_DISABLED, false);
            return;
        }
        renderCyberwareIcon(graphics, cyberware, new Rect(370, 94, 64, 64));
        graphics.text(this.font, ellipsize(cyberware.displayName().toUpperCase(Locale.ROOT), 430),
                450, 98, TEXT, false);
        graphics.text(this.font, cyberware.tier().displayName().toUpperCase(Locale.ROOT),
                450, 114, tierColor(cyberware), false);
        String stats = "CAPACITY " + cyberware.capacity();
        if (cyberware.armor() > 0.0) {
            stats += "  //  ARMOR " + format(cyberware.armor());
        }
        graphics.text(this.font, stats, 450, 132, GOLD, false);
        graphics.text(this.font, "SOURCE EFFECT(S)", 370, 178, RED, false);

        CyberwareFamily family = selectedFamily();
        if (family != null) {
            for (int index = 0; index < family.variants().size(); index++) {
                Cyberware variant = family.variants().get(index);
                Rect tier = tierRect(index);
                boolean selected = variant == cyberware;
                boolean owned = inventoryCount(variant) > 0;
                boolean installed = currentData() != null
                        && currentData().get(selectedSlot, selectedSocket) == variant;
                int edge = installed ? GREEN : selected ? CYAN : owned ? GOLD : RED_DIM;
                graphics.fill(tier.x(), tier.y(), tier.right(), tier.bottom(),
                        selected ? 0xEE173B41 : PANEL_SOFT);
                graphics.outline(tier.x(), tier.y(), tier.width(), tier.height(), edge);
                graphics.centeredText(this.font, variant.tier().id(),
                        tier.x() + tier.width() / 2, tier.y() + 5,
                        installed ? GREEN : owned ? TEXT : TEXT_DIM);
                if (tier.contains(mouseX, mouseY)) {
                    graphics.requestCursor(CursorTypes.POINTING_HAND);
                }
            }
        }

        int effectY = 245;
        List<FormattedCharSequence> lines = this.font.split(Component.literal(cyberware.effect()), 500);
        int maxLines = 18;
        for (int index = 0; index < lines.size() && index < maxLines; index++) {
            graphics.text(this.font, lines.get(index), 370, effectY + index * 10, TEXT_DIM, false);
        }
        if (lines.size() > maxLines) {
            graphics.text(this.font, "…", 370, effectY + maxLines * 10, TEXT_DIM, false);
        }
        renderAction(graphics, mouseX, mouseY, lockedSocket);
    }

    private void renderAction(GuiGraphicsExtractor graphics, double mouseX, double mouseY,
                              boolean lockedSocket) {
        Cyberware cyberware = selectedCyberware;
        CyberwareData data = currentData();
        Player player = Minecraft.getInstance().player;
        Cyberware installed = data == null ? null : data.get(selectedSlot, selectedSocket);
        boolean exactInstalled = installed == cyberware;
        boolean owned = cyberware != null && inventoryCount(cyberware) > 0;
        boolean syncing = Util.getMillis() < interactionLockedUntil;
        boolean capacity = player != null && data != null && cyberware != null
                && CyberwareCapacity.canInstall(player, data, cyberware, selectedSocket);
        boolean enabled = !syncing && !lockedSocket && cyberware != null
                && (exactInstalled || owned && capacity);
        boolean hovered = DETAIL_ACTION.contains(mouseX, mouseY);
        int edge = enabled ? exactInstalled ? GREEN : CYAN : TEXT_DISABLED;
        graphics.fill(DETAIL_ACTION.x(), DETAIL_ACTION.y(), DETAIL_ACTION.right(),
                DETAIL_ACTION.bottom(), enabled && hovered ? 0xEE173B41 : 0xDB10171B);
        graphics.outline(DETAIL_ACTION.x(), DETAIL_ACTION.y(), DETAIL_ACTION.width(),
                DETAIL_ACTION.height(), edge);

        String action;
        if (syncing) {
            action = "SYNCING...";
        } else if (lockedSocket) {
            action = "SOCKET LOCKED";
        } else if (exactInstalled) {
            action = "REMOVE " + cyberware.tier().id();
        } else if (!owned) {
            action = "TIER ITEM REQUIRED";
        } else if (!capacity) {
            action = "INSUFFICIENT CAPACITY";
        } else if (installed != null) {
            action = "REPLACE WITH " + cyberware.tier().id();
        } else {
            action = "INSTALL " + cyberware.tier().id();
        }
        graphics.centeredText(this.font, action,
                DETAIL_ACTION.x() + DETAIL_ACTION.width() / 2, DETAIL_ACTION.y() + 7,
                enabled ? TEXT : TEXT_DISABLED);
        if (hovered) {
            graphics.requestCursor(enabled ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
        }
    }

    private void renderPageButton(GuiGraphicsExtractor graphics, Rect rect, String label,
                                  boolean enabled, double mouseX, double mouseY) {
        boolean hovered = rect.contains(mouseX, mouseY);
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(),
                enabled && hovered ? PANEL_HOVER : PANEL_SOFT);
        graphics.outline(rect.x(), rect.y(), rect.width(), rect.height(),
                enabled ? RED_DIM : TEXT_DISABLED);
        graphics.centeredText(this.font, label, rect.x() + rect.width() / 2, rect.y() + 6,
                enabled ? TEXT : TEXT_DISABLED);
        if (hovered) {
            graphics.requestCursor(enabled ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
        }
    }

    private void renderFooter(GuiGraphicsExtractor graphics, double mouseX, double mouseY) {
        graphics.horizontalLine(25, DESIGN_WIDTH - 26, 500, RED_DIM);
        KeyMapping mapping = KeyMapping.get("key.cyberdeck.open_cyberware");
        String key = mapping == null ? "G" : mapping.getTranslatedKeyMessage().getString();
        graphics.text(this.font, "[" + key + "] RIPPERDOC", 29, 510, CYAN_DIM, false);
        String hint = detailsOpen ? "FAMILY  //  TIER  //  EFFECTS  //  INSTALL"
                : "SELECT A BODY SOCKET";
        graphics.centeredText(this.font, hint, DESIGN_WIDTH / 2, 510, TEXT_DISABLED);
        boolean hovered = BACK_BUTTON.contains(mouseX, mouseY);
        String label = detailsOpen ? "< OVERVIEW" : "< BACK";
        graphics.text(this.font, label, BACK_BUTTON.right() - this.font.width(label),
                BACK_BUTTON.y() + 5, hovered ? CYAN : RED, false);
        if (hovered) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void performSelectedAction() {
        if (selectedCyberware == null || Util.getMillis() < interactionLockedUntil) {
            return;
        }
        CyberwareData data = currentData();
        if (data == null || selectedSocket >= data.unlockedSockets(selectedSlot)) {
            return;
        }
        Cyberware installed = data.get(selectedSlot, selectedSocket);
        if (installed == selectedCyberware) {
            ClientPacketDistributor.sendToServer(
                    new RemoveCyberwarePacket(selectedSlot.ordinal(), selectedSocket));
            interactionLockedUntil = Util.getMillis() + ACTION_LOCK_MS;
        } else if (inventoryCount(selectedCyberware) > 0) {
            ClientPacketDistributor.sendToServer(
                    new EquipCyberwarePacket(selectedCyberware.id(), selectedSocket));
            interactionLockedUntil = Util.getMillis() + ACTION_LOCK_MS;
        }
    }

    private void chooseForSocket() {
        CyberwareData data = currentData();
        Cyberware installed = data == null ? null : data.get(selectedSlot, selectedSocket);
        if (installed != null) {
            selectedFamilyId = installed.familyId();
            selectedCyberware = installed;
            return;
        }
        List<CyberwareFamily> families = families();
        for (CyberwareFamily family : families) {
            Cyberware owned = highestOwned(family);
            if (owned != null) {
                selectedFamilyId = family.id();
                selectedCyberware = owned;
                return;
            }
        }
        if (families.isEmpty()) {
            selectedFamilyId = null;
            selectedCyberware = null;
        } else {
            selectedFamilyId = families.getFirst().id();
            selectedCyberware = families.getFirst().lowestTier();
        }
    }

    private void selectFamily(CyberwareFamily family) {
        selectedFamilyId = family.id();
        Cyberware installed = currentData() == null ? null
                : currentData().get(selectedSlot, selectedSocket);
        if (installed != null && installed.familyId().equals(family.id())) {
            selectedCyberware = installed;
            return;
        }
        Cyberware owned = highestOwned(family);
        selectedCyberware = owned == null ? family.lowestTier() : owned;
    }

    private Cyberware highestOwned(CyberwareFamily family) {
        for (int index = family.variants().size() - 1; index >= 0; index--) {
            Cyberware cyberware = family.variants().get(index);
            if (inventoryCount(cyberware) > 0) {
                return cyberware;
            }
        }
        return null;
    }

    private Cyberware representative(CyberwareFamily family) {
        if (selectedCyberware != null && selectedCyberware.familyId().equals(family.id())) {
            return selectedCyberware;
        }
        CyberwareData data = currentData();
        if (data != null) {
            Cyberware installed = data.findFamily(family.id());
            if (installed != null) {
                return installed;
            }
        }
        Cyberware owned = highestOwned(family);
        return owned == null ? family.lowestTier() : owned;
    }

    private void ensureSelection() {
        if (selectedCyberware == null || selectedCyberware.slot() != selectedSlot
                || selectedFamilyId == null
                || !selectedCyberware.familyId().equals(selectedFamilyId)) {
            chooseForSocket();
        }
    }

    private int firstUsefulSocket(BodySlot slot) {
        CyberwareData data = currentData();
        if (data == null) {
            return 0;
        }
        int empty = data.firstEmptySocket(slot, data.unlockedSockets(slot));
        return empty >= 0 ? empty : 0;
    }

    private List<CyberwareFamily> families() {
        return Cyberware.familiesForSlot(selectedSlot);
    }

    private CyberwareFamily selectedFamily() {
        return selectedFamilyId == null ? null : Cyberware.family(selectedFamilyId);
    }

    private CyberwareData currentData() {
        Player player = Minecraft.getInstance().player;
        return player == null ? null : CyberwareAttachments.get(player);
    }

    private int inventoryCount(Cyberware cyberware) {
        Player player = Minecraft.getInstance().player;
        if (player == null || cyberware == null) {
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

    private void renderCyberwareIcon(GuiGraphicsExtractor graphics, Cyberware cyberware,
                                     Rect bounds) {
        ItemStack stack = CyberwareItems.item(cyberware).get().getDefaultInstance();
        float scale = Math.min(bounds.width(), bounds.height()) / 16.0F;
        float renderedSize = 16.0F * scale;
        graphics.pose().pushMatrix();
        graphics.pose().translate(bounds.x() + (bounds.width() - renderedSize) / 2.0F,
                bounds.y() + (bounds.height() - renderedSize) / 2.0F);
        graphics.pose().scale(scale, scale);
        graphics.item(stack, 0, 0);
        graphics.pose().popMatrix();
    }

    private Rect socketRect(GroupSpec group, int socket) {
        return new Rect(group.x() + socket * (SOCKET_WIDTH + SOCKET_GAP), group.y(),
                SOCKET_WIDTH, SOCKET_HEIGHT);
    }

    private Rect groupHitRect(GroupSpec group) {
        int width = group.slot().maximumSockets() * SOCKET_WIDTH
                + (group.slot().maximumSockets() - 1) * SOCKET_GAP;
        return group.side() == Side.LEFT
                ? new Rect(group.x() - 170, group.y() - 4, width + 170, SOCKET_HEIGHT + 8)
                : new Rect(group.x(), group.y() - 4, width + 170, SOCKET_HEIGHT + 8);
    }

    private Rect familyRect(int visibleIndex) {
        return new Rect(76, 96 + visibleIndex * 39, 260, 35);
    }

    private Rect tierRect(int index) {
        int column = index % 6;
        int row = index / 6;
        return new Rect(450 + column * 68, 174 + row * 29, 60, 22);
    }

    private static int pageCount(int size) {
        return Math.max(1, (size + FAMILIES_PER_PAGE - 1) / FAMILIES_PER_PAGE);
    }

    private int tierColor(Cyberware cyberware) {
        return switch (cyberware.tier()) {
            case T1, T1_PLUS -> 0xFFB8C1C8;
            case T2, T2_PLUS -> 0xFF54D66B;
            case T3, T3_PLUS -> 0xFF40A9FF;
            case T4, T4_PLUS -> 0xFFC66BFF;
            case T5, T5_PLUS, T5_PLUS_PLUS -> GOLD;
        };
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Double.toString(value);
    }

    private String ellipsize(String value, int maxWidth) {
        if (maxWidth <= 0 || value == null) {
            return "";
        }
        if (this.font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        return this.font.plainSubstrByWidth(value,
                Math.max(0, maxWidth - this.font.width(suffix))) + suffix;
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

    private Viewport viewport() {
        float scale = Math.min(this.width / (float) DESIGN_WIDTH,
                this.height / (float) DESIGN_HEIGHT);
        scale = Math.max(0.01F, scale);
        return new Viewport(scale,
                (this.width - DESIGN_WIDTH * scale) / 2.0F,
                (this.height - DESIGN_HEIGHT * scale) / 2.0F);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private enum Side {
        LEFT,
        RIGHT
    }

    private record GroupSpec(
            BodySlot slot,
            int x,
            int y,
            Side side,
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
