package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.client.QuickhackScannerClient;
import com.example.cyberdeck.client.QuickhackUploadClient;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.skill.Skill;
import com.example.cyberdeck.weapon.GunItem;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** Full-screen Cyberpunk-style scanner presentation shown while quickhacking is active. */
public final class QuickhackScannerOverlay implements GuiLayer {
    private static final int CYAN = 0xFF48E7E2;
    private static final int CYAN_BRIGHT = 0xFFC8FFFF;
    private static final int CYAN_DIM = 0xFF1E7378;
    private static final int CYAN_DARK = 0xFF0B3038;
    private static final int RED = 0xFFFF4E55;
    private static final int RED_DIM = 0xFF8F2931;
    private static final int ORANGE = 0xFFFF653C;
    private static final int ORANGE_DIM = 0xA0D8462E;
    private static final int AMBER = 0xFFFFB94C;
    private static final int WHITE = 0xFFE7F7F5;
    private static final int PANEL = 0xC9061118;
    private static final int CARD = 0xB5071820;
    private static final int CARD_SELECTED = 0xC80B2931;
    private static final int CARD_UNAVAILABLE = 0xB53A0A12;
    private static final int RED_WASH = 0x18160709;
    private static final int GREEN_WASH = 0x1000B866;
    private static final int SCANLINE = 0x093CFFD0;
    private static final int AXIS = 0x35FF4E55;
    private static final int FRAME = 0x669F3239;
    private static final int EMPTY_RAM = 0xD00A262D;
    private static final int RESERVED_RAM = 0xFFE0633E;

    private float panelVisibility;
    private @Nullable LivingEntity animatedTarget;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!QuickhackScannerClient.isActive()) {
            resetAnimation();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        Font font = minecraft.font;
        long gameTime = minecraft.level.getGameTime();
        LivingEntity lockedTarget = QuickhackScannerClient.target(minecraft.level);
        float panelProgress = updatePanelVisibility(lockedTarget, deltaTracker);

        drawScannerBackdrop(graphics, font, screenWidth, screenHeight, gameTime);
        drawRamRail(graphics, font, player, screenWidth, screenHeight);

        boolean compact = screenWidth < 520 || screenHeight < 300;
        int leftX = compact ? 12 : Math.max(18, screenWidth / 11);
        int leftY = compact ? 48 : Math.max(62, screenHeight / 5);
        int leftWidth = compact
                ? Mth.clamp((int) (screenWidth * 0.30F), 120, 145)
                : Mth.clamp((int) (screenWidth * 0.29F), 168, 280);
        int rightInset = compact ? 12 : Math.max(18, screenWidth / 28);
        int rightWidth = compact
                ? Mth.clamp((int) (screenWidth * 0.30F), 120, 145)
                : Mth.clamp((int) (screenWidth * 0.255F), 160, 260);
        int rightX = screenWidth - rightInset - rightWidth;
        int rightY = compact ? 52 : Math.max(68, (int) (screenHeight * 0.22F));

        drawTargetReticle(graphics, font, screenWidth, screenHeight, leftX + leftWidth,
                rightX, gameTime, lockedTarget, panelProgress);

        if (animatedTarget != null && panelProgress > 0.01F) {
            float eased = smoothstep(panelProgress);
            int leftClipRight = Math.round((leftX + leftWidth + 12) * eased);
            int leftOffset = -Math.round(14.0F * (1.0F - eased));
            graphics.enableScissor(0, 0, leftClipRight, screenHeight);
            graphics.pose().pushMatrix();
            graphics.pose().translate(leftOffset, 0.0F);
            drawQuickhackMenu(graphics, font, player, leftX, leftY, leftWidth, screenHeight);
            graphics.pose().popMatrix();
            graphics.disableScissor();

            int rightClipLeft = Math.round(screenWidth
                    - (screenWidth - rightX + 12) * eased);
            int rightOffset = Math.round(14.0F * (1.0F - eased));
            graphics.enableScissor(rightClipLeft, 0, screenWidth, screenHeight);
            graphics.pose().pushMatrix();
            graphics.pose().translate(rightOffset, 0.0F);
            drawIntelPanel(graphics, font, player, animatedTarget,
                    rightX, rightY, rightWidth, screenHeight);
            graphics.pose().popMatrix();
            graphics.disableScissor();

            graphics.pose().pushMatrix();
            graphics.pose().translate(0.0F, Math.round(28.0F * (1.0F - eased)));
            drawControls(graphics, font, screenWidth, screenHeight);
            graphics.pose().popMatrix();
        }
    }

    private float updatePanelVisibility(@Nullable LivingEntity lockedTarget,
                                        DeltaTracker deltaTracker) {
        float realtimeTicks = Mth.clamp(deltaTracker.getRealtimeDeltaTicks(), 0.0F, 2.0F);
        if (lockedTarget != null) {
            animatedTarget = lockedTarget;
            panelVisibility = Mth.approach(panelVisibility, 1.0F, realtimeTicks * 0.22F);
        } else {
            panelVisibility = Mth.approach(panelVisibility, 0.0F, realtimeTicks * 0.30F);
            if (panelVisibility == 0.0F) {
                animatedTarget = null;
            }
        }
        return panelVisibility;
    }

    private void resetAnimation() {
        panelVisibility = 0.0F;
        animatedTarget = null;
    }

    private static float smoothstep(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    private static void drawScannerBackdrop(GuiGraphicsExtractor graphics, Font font, int width,
                                            int height, long gameTime) {
        graphics.fill(0, 0, width, height, RED_WASH);
        graphics.fill(0, 0, width, height, GREEN_WASH);

        int phase = (int) (gameTime % 6L);
        for (int y = phase; y < height; y += 6) {
            graphics.fill(0, y, width, y + 1, SCANLINE);
        }
        int sweepY = (int) ((gameTime * 2L) % Math.max(1, height));
        graphics.fill(0, sweepY, width, sweepY + 1, 0x183DFFC5);

        // Layered edge bands read as an optical vignette without hiding the world in solid slabs.
        edgeBand(graphics, width, height, 0, 8, 0x6810080A);
        edgeBand(graphics, width, height, 8, 9, 0x3D10070A);
        edgeBand(graphics, width, height, 17, 10, 0x2010070A);

        int centerX = width / 2;
        graphics.verticalLine(centerX, 16, height - 25, AXIS);
        graphics.horizontalLine(20, width - 21, height / 2, 0x20FF4E55);

        // Angular frame corners and fine red optical rails.
        frameCorner(graphics, 14, 14, 1, 1, 22, FRAME);
        frameCorner(graphics, width - 15, 14, -1, 1, 22, FRAME);
        frameCorner(graphics, 14, height - 15, 1, -1, 22, FRAME);
        frameCorner(graphics, width - 15, height - 15, -1, -1, 22, FRAME);
        graphics.horizontalLine(30, centerX - 92, 17, RED_DIM);
        graphics.horizontalLine(centerX + 92, width - 31, 17, RED_DIM);
        graphics.verticalLine(centerX - 94, 14, 21, RED);
        graphics.verticalLine(centerX + 94, 14, 21, RED);

        graphics.text(font, "REC", 20, 23, RED, false);
        graphics.fill(15, 26, 18, 29, RED);
        graphics.text(font, "SCAN MODE // LIVE", 20, 34, RED_DIM, false);
        String power = "POWER CONNECTED";
        graphics.text(font, power, width - 20 - font.width(power), 23, RED_DIM, false);
    }

    private static void drawRamRail(GuiGraphicsExtractor graphics, Font font, Player player,
                                    int screenWidth, int screenHeight) {
        int raw = RamAttachments.get(player);
        int reserved = Math.min(raw, QuickhackUploadClient.reservedRam());
        int available = Math.max(0, raw - reserved);
        int max = RamAttachments.MAX_RAM;
        int railWidth = Mth.clamp((int) (screenWidth * 0.31F), 180, 300);
        int x = screenWidth / 2 - railWidth / 2;
        int y = Math.max(28, (int) (screenHeight * 0.075F));
        int gap = 2;
        int segmentWidth = Math.max(5, (railWidth - (max - 1) * gap) / max);
        int usedWidth = max * segmentWidth + (max - 1) * gap;
        x = screenWidth / 2 - usedWidth / 2;

        String title = "CYBERDECK RAM  " + available + "/" + max;
        graphics.text(font, title, x, y - 13, CYAN, false);
        String reserve = "RESERVED " + reserved;
        graphics.text(font, reserve, x + usedWidth - font.width(reserve), y - 13,
                reserved > 0 ? ORANGE : CYAN_DIM, false);
        graphics.horizontalLine(x - 20, x + usedWidth + 20, y - 3, RED_DIM);
        graphics.verticalLine(x - 21, y - 6, y + 3, RED);
        graphics.verticalLine(x + usedWidth + 21, y - 6, y + 3, RED);

        for (int i = 0; i < max; i++) {
            int color = i < available ? CYAN
                    : (i < raw ? RESERVED_RAM : EMPTY_RAM);
            int sx = x + i * (segmentWidth + gap);
            graphics.fill(sx - 1, y - 1, sx + segmentWidth + 1, y + 16, CYAN_DARK);
            graphics.fill(sx, y, sx + segmentWidth, y + 15, color);
            if (i < available) {
                graphics.fill(sx, y, sx + segmentWidth, y + 2, CYAN_BRIGHT);
                graphics.fill(sx, y + 12, sx + segmentWidth, y + 15, 0xFF299B9B);
            }
        }
        graphics.fill(x + usedWidth + 2, y - 1, x + usedWidth + 4, y + 16, RED);
    }

    private static void drawQuickhackMenu(GuiGraphicsExtractor graphics, Font font, Player player,
                                          int x, int y, int width, int screenHeight) {
        int availableRam = Math.max(0,
                RamAttachments.get(player) - QuickhackUploadClient.reservedRam());
        graphics.text(font, "AVAILABLE QUICKHACKS:", x, y, CYAN, false);
        graphics.horizontalLine(x, x + width - 24, y + 11, CYAN_DIM);
        graphics.horizontalLine(x + width - 23, x + width, y + 11, RED_DIM);

        int maxRowHeight = screenHeight >= 600 ? 40 : 27;
        int rowHeight = Mth.clamp((screenHeight - y - 48) / Skill.STANDBY.ordinal(), 22,
                maxRowHeight);
        int rowY = y + 17;
        for (int ordinal = 0; ordinal < Skill.STANDBY.ordinal(); ordinal++) {
            Skill skill = Skill.fromSlot(ordinal);
            if (skill == null) {
                continue;
            }
            boolean selected = ordinal == QuickhackScannerClient.selectedSkillOrdinal();
            boolean uploading = ordinal == QuickhackUploadClient.activeSkillOrdinal();
            int queuePosition = QuickhackUploadClient.queuePosition(ordinal);
            boolean committed = uploading || queuePosition > 0;
            boolean affordable = committed || skill.ramCost() <= availableRam;
            drawHackCard(graphics, font, skill, x, rowY, width, rowHeight, selected,
                    affordable, uploading, queuePosition);
            rowY += rowHeight;
        }
    }

    private static void drawHackCard(GuiGraphicsExtractor graphics, Font font, Skill skill,
                                     int x, int y, int width, int height, boolean selected,
                                     boolean affordable, boolean uploading, int queuePosition) {
        int cardX = selected ? x - 6 : x + 3;
        int cardWidth = selected ? width + 7 : width - 3;
        int border = !affordable ? RED : (selected ? CYAN_BRIGHT : CYAN_DIM);
        int fill = !affordable ? CARD_UNAVAILABLE : (selected ? CARD_SELECTED : CARD);

        if (selected) {
            drawCutBorder(graphics, cardX - 2, y - 2, cardWidth + 4, height + 3, CYAN_DIM);
        }
        fillCutRect(graphics, cardX, y, cardWidth, height - 1, fill);
        drawCutBorder(graphics, cardX, y, cardWidth, height - 1, border);

        int iconX = cardX + cardWidth - 21;
        int iconY = y + Math.max(3, (height - 16) / 2);
        graphics.fill(iconX - 1, iconY - 1, iconX + 17, iconY + 17, 0xB2051118);
        graphics.outline(iconX - 1, iconY - 1, 18, 18, border);
        graphics.item(skill.stack(), iconX, iconY);

        String cost = "↓ " + skill.ramCost();
        int costX = iconX - 5 - font.width(cost);
        int textX = cardX + 7;
        String name = trim(font, skill.displayName().toUpperCase(Locale.ROOT),
                Math.max(24, costX - textX - 4));
        graphics.text(font, name, textX, y + 3, affordable ? CYAN_BRIGHT : RED, false);
        graphics.text(font, cost, costX, y + 3, affordable ? CYAN : RED, false);

        int chipY = y + 14;
        int chipX = textX;
        if (!affordable) {
            drawChip(graphics, font, chipX, chipY, "INSUFFICIENT RAM", RED, 0x663A0A12);
        } else {
            chipX += drawChip(graphics, font, chipX, chipY, "READY", CYAN, 0x66113C44) + 3;
            String state = uploading ? "UPLOADING"
                    : (queuePosition > 0 ? "QUEUE " + queuePosition
                    : (selected ? "SELECTED" : "TRACEABLE"));
            int stateColor = uploading ? ORANGE : (queuePosition > 0 ? AMBER : CYAN_DIM);
            drawChip(graphics, font, chipX, chipY, state, stateColor, 0x55101820);
        }
    }

    private static void drawTargetReticle(GuiGraphicsExtractor graphics, Font font, int width,
                                          int height, int leftEdge, int rightEdge, long gameTime,
                                          @Nullable LivingEntity target, float panelProgress) {
        int centerX = width / 2;
        int centerY = height / 2 + 5;
        boolean compact = width < 520 || height < 300;
        int radius = compact
                ? Mth.clamp((int) (Math.min(width, height) * 0.16F), 36, 58)
                : Mth.clamp((int) (Math.min(width, height) * 0.19F), 55, 135);
        int color = target == null ? CYAN_DIM : ORANGE;
        int faint = target == null ? 0x6050C9C6 : ORANGE_DIM;

        float railProgress = smoothstep(panelProgress);
        int idleLeft = centerX - radius - 55;
        int idleRight = centerX + radius + 55;
        int railLeft = Math.round(idleLeft + (leftEdge + 8 - idleLeft) * railProgress);
        int railRight = Math.round(idleRight + (rightEdge - 8 - idleRight) * railProgress);
        graphics.horizontalLine(railLeft, centerX - radius - 15, centerY, AXIS);
        graphics.horizontalLine(centerX + radius + 15, railRight, centerY, AXIS);
        graphics.verticalLine(centerX, 56, centerY - radius - 14, AXIS);
        graphics.verticalLine(centerX, centerY + radius + 14, height - 34, AXIS);

        drawOctagon(graphics, centerX, centerY, radius, faint);
        drawOctagon(graphics, centerX, centerY, radius - 8, 0x382AD9D4);
        drawChevron(graphics, centerX - radius - 12, centerY, 1, color);
        drawChevron(graphics, centerX - radius - 20, centerY, 1, faint);
        drawChevron(graphics, centerX + radius + 12, centerY, -1, color);
        drawChevron(graphics, centerX + radius + 20, centerY, -1, faint);

        int frameRadius = radius + 24;
        frameCorner(graphics, centerX - frameRadius, centerY - frameRadius, 1, 1, 14, FRAME);
        frameCorner(graphics, centerX + frameRadius, centerY - frameRadius, -1, 1, 14, FRAME);
        frameCorner(graphics, centerX - frameRadius, centerY + frameRadius, 1, -1, 14, FRAME);
        frameCorner(graphics, centerX + frameRadius, centerY + frameRadius, -1, -1, 14, FRAME);

        graphics.horizontalLine(centerX - 8, centerX - 3, centerY, color);
        graphics.horizontalLine(centerX + 3, centerX + 8, centerY, color);
        graphics.verticalLine(centerX, centerY - 8, centerY - 3, color);
        graphics.verticalLine(centerX, centerY + 3, centerY + 8, color);
        graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, color);

        int nameY = centerY - radius - 29;
        String targetName = target == null ? "SCANNING // ACQUIRE HOSTILE"
                : target.getName().getString().toUpperCase(Locale.ROOT);
        String shownTargetName = trim(font, targetName, 150);
        int targetNameWidth = font.width(shownTargetName);
        graphics.fill(centerX - targetNameWidth / 2 - 9, nameY - 2,
                centerX + targetNameWidth / 2 + 9, nameY + 10, 0x79050E12);
        graphics.fill(centerX - targetNameWidth / 2 - 5, nameY + 10,
                centerX + targetNameWidth / 2 + 5, nameY + 11, color);
        graphics.centeredText(font, shownTargetName, centerX, nameY, color);
        if (target != null) {
            graphics.centeredText(font, "TARGET LOCKED", centerX, nameY + 13, ORANGE_DIM);
        }

        if (QuickhackUploadClient.isUploading()) {
            Skill active = Skill.fromSlot(QuickhackUploadClient.activeSkillOrdinal());
            float progress = QuickhackUploadClient.uploadProgress(gameTime);
            int barWidth = Math.min(132, Math.max(90, width / 6));
            int barX = centerX - barWidth / 2;
            int barY = centerY + radius + 16;
            String upload = active == null ? "UPLOADING" : "UPLOADING "
                    + active.displayName().toUpperCase(Locale.ROOT);
            graphics.centeredText(font, upload, centerX, barY - 11, CYAN);
            graphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 4, CYAN_DARK);
            graphics.fill(barX, barY, barX + barWidth, barY + 3, EMPTY_RAM);
            graphics.fill(barX, barY, barX + Math.round(barWidth * progress), barY + 3, CYAN);
            graphics.verticalLine(barX + Math.round(barWidth * progress), barY - 2, barY + 5, RED);
        }
    }

    private static void drawIntelPanel(GuiGraphicsExtractor graphics, Font font, Player player,
                                       LivingEntity target, int x, int y, int width,
                                       int screenHeight) {
        boolean compact = screenHeight < 300;
        int panelY = y + 10;
        int panelHeight = compact
                ? Math.max(160, screenHeight - panelY - 18)
                : Mth.clamp(screenHeight - panelY - 42, 190,
                        Math.max(238, (int) (screenHeight * 0.65F)));
        fillCutRect(graphics, x, panelY, width, panelHeight, PANEL);
        drawCutBorder(graphics, x, panelY, width, panelHeight, CYAN_DIM);

        fillCutRect(graphics, x + 4, y, 48, 15, 0xB12A0A12);
        drawCutBorder(graphics, x + 4, y, 48, 15, RED_DIM);
        graphics.text(font, "DATA [Z]", x + 9, y + 3, RED, false);
        fillCutRect(graphics, x + 53, y, 64, 15, 0xD20A2229);
        drawCutBorder(graphics, x + 53, y, 64, 15, CYAN);
        graphics.text(font, "HACKING", x + 61, y + 3, CYAN_BRIGHT, false);

        int lineY = panelY + 8;
        String name = trim(font, target.getName().getString().toUpperCase(Locale.ROOT), width - 14);
        graphics.text(font, name, x + 7, lineY, CYAN_BRIGHT, false);
        String type = trim(font, target.getType().getDescription().getString().toUpperCase(Locale.ROOT),
                width - 14);
        graphics.text(font, type, x + 7, lineY + 11, CYAN_DIM, false);

        lineY += 27;
        String health = String.format(Locale.ROOT, "%.0f / %.0f",
                target.getHealth(), target.getMaxHealth());
        labeledValue(graphics, font, x, width, lineY, "HEALTH", health, healthColor(target));
        int healthWidth = width - 14;
        float healthRatio = target.getMaxHealth() <= 0.0F ? 0.0F
                : Mth.clamp(target.getHealth() / target.getMaxHealth(), 0.0F, 1.0F);
        graphics.fill(x + 7, lineY + 10, x + 7 + healthWidth, lineY + 13, CYAN_DARK);
        graphics.fill(x + 7, lineY + 10,
                x + 7 + Math.round(healthWidth * healthRatio), lineY + 13, healthColor(target));

        lineY += 19;
        String faction = target instanceof FactionEnemy enemy
                ? formatId(enemy.getFaction().id()) : "UNAFFILIATED";
        labeledValue(graphics, font, x, width, lineY, "FACTION", faction, AMBER);
        labeledValue(graphics, font, x, width, lineY + 11, "WEAPON", weaponName(target), WHITE);
        labeledValue(graphics, font, x, width, lineY + 22, "ARMOR",
                Integer.toString(target.getArmorValue()), WHITE);
        labeledValue(graphics, font, x, width, lineY + 33, "DISTANCE",
                String.format(Locale.ROOT, "%.1f M", player.distanceTo(target)), WHITE);
        labeledValue(graphics, font, x, width, lineY + 44, "STATUS",
                targetStatus(target), RED);
        lineY += 58;

        int separatorY = Math.max(lineY + 5, panelY + 116);
        separatorY = Math.min(separatorY, panelY + panelHeight - 63);
        graphics.horizontalLine(x + 7, x + width - 8, separatorY, CYAN_DARK);
        graphics.text(font, "SELECTED QUICKHACK", x + 7, separatorY + 7, CYAN_DIM, false);

        Skill selected = QuickhackScannerClient.selectedSkill();
        if (selected != null) {
            String selectedName = trim(font, selected.displayName().toUpperCase(Locale.ROOT), width - 14);
            graphics.text(font, selectedName, x + 7, separatorY + 18, CYAN_BRIGHT, false);
            String stats = selected.ramCost() + " RAM  //  "
                    + String.format(Locale.ROOT, "%.1f S", selected.uploadTicks() / 20.0F);
            graphics.text(font, stats, x + 7, separatorY + 29, AMBER, false);
            graphics.text(font, trim(font, hackSummary(selected), width - 14),
                    x + 7, separatorY + 40, WHITE, false);
            int queuePosition = QuickhackUploadClient.queuePosition(selected.ordinal());
            String queue = selected.ordinal() == QuickhackUploadClient.activeSkillOrdinal()
                    ? "UPLOAD ACTIVE"
                    : (queuePosition > 0 ? "QUEUE POSITION " + queuePosition : "READY TO QUEUE");
            graphics.text(font, queue, x + 7, separatorY + 51,
                    queuePosition > 0 ? ORANGE : CYAN, false);
        }
    }

    private static void drawControls(GuiGraphicsExtractor graphics, Font font, int width, int height) {
        int y = height - 23;
        if (width < 520) {
            int x = 10;
            x += drawControl(graphics, font, x, y, "F", "EXECUTE") + 8;
            x += drawControl(graphics, font, x, y, "1/3", "SELECT") + 8;
            drawControl(graphics, font, x, y, "RMB", "QUEUE");
            return;
        }
        int x = Math.max(width / 2 + 18, width - 315);
        x += drawControl(graphics, font, x, y, "F", "EXECUTE") + 10;
        x += drawControl(graphics, font, x, y, "1", "") + 2;
        x += drawControl(graphics, font, x, y, "3", "CHANGE QUICKHACK") + 10;
        drawControl(graphics, font, x, y, "RMB", "QUEUE");
    }

    private static int drawControl(GuiGraphicsExtractor graphics, Font font, int x, int y,
                                   String key, String label) {
        int keyWidth = Math.max(13, font.width(key) + 6);
        graphics.fill(x, y, x + keyWidth, y + 13, 0xB207141A);
        graphics.outline(x, y, keyWidth, 13, CYAN);
        graphics.centeredText(font, key, x + keyWidth / 2, y + 2, CYAN_BRIGHT);
        if (label.isEmpty()) {
            return keyWidth;
        }
        graphics.text(font, label, x + keyWidth + 4, y + 2, RED, false);
        return keyWidth + 4 + font.width(label);
    }

    private static void edgeBand(GuiGraphicsExtractor graphics, int width, int height, int inset,
                                 int thickness, int color) {
        graphics.fill(inset, inset, width - inset, inset + thickness, color);
        graphics.fill(inset, height - inset - thickness, width - inset, height - inset, color);
        graphics.fill(inset, inset + thickness, inset + thickness, height - inset - thickness, color);
        graphics.fill(width - inset - thickness, inset + thickness, width - inset,
                height - inset - thickness, color);
    }

    private static void frameCorner(GuiGraphicsExtractor graphics, int x, int y, int dx, int dy,
                                    int length, int color) {
        graphics.horizontalLine(x, x + dx * length, y, color);
        graphics.verticalLine(x, y, y + dy * length, color);
        graphics.horizontalLine(x, x + dx * 5, y + dy * 3, color);
        graphics.verticalLine(x + dx * 3, y, y + dy * 5, color);
    }

    private static void drawOctagon(GuiGraphicsExtractor graphics, int centerX, int centerY,
                                    int radius, int color) {
        int cut = Math.max(8, radius / 3);
        int left = centerX - radius;
        int right = centerX + radius;
        int top = centerY - radius;
        int bottom = centerY + radius;
        graphics.horizontalLine(centerX - cut, centerX + cut, top, color);
        drawPixelLine(graphics, centerX + cut, top, right, centerY - cut, color);
        graphics.verticalLine(right, centerY - cut, centerY + cut, color);
        drawPixelLine(graphics, right, centerY + cut, centerX + cut, bottom, color);
        graphics.horizontalLine(centerX - cut, centerX + cut, bottom, color);
        drawPixelLine(graphics, centerX - cut, bottom, left, centerY + cut, color);
        graphics.verticalLine(left, centerY - cut, centerY + cut, color);
        drawPixelLine(graphics, left, centerY - cut, centerX - cut, top, color);
    }

    private static void drawChevron(GuiGraphicsExtractor graphics, int x, int y, int direction,
                                    int color) {
        drawPixelLine(graphics, x, y - 6, x + direction * 5, y, color);
        drawPixelLine(graphics, x + direction * 5, y, x, y + 6, color);
    }

    private static void fillCutRect(GuiGraphicsExtractor graphics, int x, int y, int width,
                                    int height, int color) {
        if (width <= 8 || height <= 8) {
            graphics.fill(x, y, x + width, y + height, color);
            return;
        }
        graphics.fill(x + 4, y, x + width - 4, y + height, color);
        graphics.fill(x, y + 4, x + width, y + height - 4, color);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, color);
    }

    private static void drawCutBorder(GuiGraphicsExtractor graphics, int x, int y, int width,
                                      int height, int color) {
        int right = x + width - 1;
        int bottom = y + height - 1;
        graphics.horizontalLine(x + 4, right - 4, y, color);
        graphics.horizontalLine(x + 4, right - 4, bottom, color);
        graphics.verticalLine(x, y + 4, bottom - 4, color);
        graphics.verticalLine(right, y + 4, bottom - 4, color);
        drawPixelLine(graphics, x, y + 4, x + 4, y, color);
        drawPixelLine(graphics, right - 4, y, right, y + 4, color);
        drawPixelLine(graphics, right, bottom - 4, right - 4, bottom, color);
        drawPixelLine(graphics, x + 4, bottom, x, bottom - 4, color);
    }

    private static void drawPixelLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1,
                                      int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int twice = error * 2;
            if (twice >= dy) {
                error += dy;
                x0 += sx;
            }
            if (twice <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    private static int drawChip(GuiGraphicsExtractor graphics, Font font, int x, int y,
                                String text, int color, int fill) {
        int width = font.width(text) + 5;
        graphics.fill(x, y, x + width, y + 9, fill);
        graphics.outline(x, y, width, 9, color);
        graphics.text(font, text, x + 3, y, color, false);
        return width;
    }

    private static void labeledValue(GuiGraphicsExtractor graphics, Font font, int x, int width,
                                     int y, String label, String value, int color) {
        graphics.text(font, label, x + 7, y, CYAN_DIM, false);
        String shown = trim(font, value, Math.max(20, width - 18 - font.width(label)));
        graphics.text(font, shown, x + width - 7 - font.width(shown), y, color, false);
    }

    private static String weaponName(LivingEntity target) {
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()) {
            return "UNARMED";
        }
        if (stack.getItem() instanceof GunItem gun) {
            return formatId(gun.gun().id());
        }
        return stack.getHoverName().getString().toUpperCase(Locale.ROOT);
    }

    private static String targetStatus(LivingEntity target) {
        if (target instanceof FactionEnemy enemy) {
            return enemy.isTriggered() ? "COMBAT ALERT" : "UNAWARE";
        }
        return target instanceof Enemy ? "HOSTILE" : "NEUTRAL";
    }

    private static int healthColor(LivingEntity target) {
        float ratio = target.getMaxHealth() <= 0.0F ? 0.0F : target.getHealth() / target.getMaxHealth();
        return ratio < 0.3F ? RED : (ratio < 0.65F ? AMBER : CYAN);
    }

    private static String hackSummary(Skill skill) {
        return switch (skill) {
            case OVERHEAT -> "THERMAL DAMAGE / BURN";
            case CRIPPLE -> "MOVEMENT SYSTEM LOCK";
            case SHORT_CIRCUIT -> "ELECTRICAL DISRUPTION";
            case CONTAGION -> "SPREADING TOXIN PAYLOAD";
            case WEAPON_GLITCH -> "WEAPON CONTROL FAILURE";
            case CYBERPSYCHOSIS -> "HOSTILITY OVERRIDE";
            case DETONATE -> "EXPLOSIVE SYSTEM TRIGGER";
            case STANDBY -> "SYSTEM IDLE";
        };
    }

    private static String formatId(String id) {
        return id.replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private static String trim(Font font, String text, int width) {
        if (width <= 0 || font.width(text) <= width) {
            return width <= 0 ? "" : text;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }
}
