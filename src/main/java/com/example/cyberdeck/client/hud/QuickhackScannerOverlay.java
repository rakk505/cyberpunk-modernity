package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.client.QuickhackScannerClient;
import com.example.cyberdeck.client.QuickhackUploadClient;
import com.example.cyberdeck.cyberware.CyberwareAttachments;
import com.example.cyberdeck.cyberware.CyberwareData;
import com.example.cyberdeck.defense.KangTaoTurret;
import com.example.cyberdeck.effect.CyberwareEffects;
import com.example.cyberdeck.faction.FactionEnemy;
import com.example.cyberdeck.faction.CyberpsychoEntity;
import com.example.cyberdeck.npc.CityNpc;
import com.example.cyberdeck.npc.NpcRole;
import com.example.cyberdeck.ram.RamAttachments;
import com.example.cyberdeck.skill.DeviceQuickhack;
import com.example.cyberdeck.skill.QuickhackTargets;
import com.example.cyberdeck.skill.Skill;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.gui.GuiLayer;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** Readable, selection-first scanner HUD inspired by Cyberpunk's quickhack list. */
public final class QuickhackScannerOverlay implements GuiLayer {
    private static final int CYAN = 0xFF43E4E0;
    private static final int CYAN_BRIGHT = 0xFFD1FFFF;
    private static final int CYAN_DIM = 0xFF247B82;
    private static final int CYAN_DARK = 0xFF0A3038;
    private static final int RED = 0xFFFF4D59;
    private static final int RED_DIM = 0xFF8C2933;
    private static final int ORANGE = 0xFFFF754D;
    private static final int AMBER = 0xFFFFC15A;
    private static final int WHITE = 0xFFE8F5F3;
    private static final int PANEL = 0xD0061118;
    private static final int ROW = 0xC9081920;
    private static final int ROW_SELECTED = 0xE00B2D35;
    private static final int ROW_UNAVAILABLE = 0xD02E0A12;
    private static final int EMPTY_RAM = 0xD109272E;
    private static final int RESERVED_RAM = 0xFFE36B48;

    private float panelVisibility;
    private @Nullable Entity animatedTarget;

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

        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        Font font = minecraft.font;
        Entity lockedTarget = QuickhackScannerClient.target(minecraft.level);
        float panelProgress = updatePanelVisibility(lockedTarget, deltaTracker);
        boolean quickhacking = QuickhackScannerClient.isQuickhacking();

        drawScannerBackdrop(graphics, font, screenWidth, screenHeight,
                minecraft.level.getGameTime());
        if (quickhacking) {
            drawRamRail(graphics, font, player, screenWidth, screenHeight);
        }

        boolean compact = screenWidth < 560;
        int leftX = compact ? 9 : Math.max(18, Math.round(screenWidth * 0.055F));
        int leftY = screenHeight < 240 ? 26
                : Math.max(46, Math.round(screenHeight * 0.15F));
        int leftWidth = compact
                ? Mth.clamp(Math.round(screenWidth * 0.40F), 136, 220)
                : Mth.clamp(Math.round(screenWidth * 0.31F), 230, 340);
        int rightWidth = compact
                ? Mth.clamp(Math.round(screenWidth * 0.31F), 112, 136)
                : Mth.clamp(Math.round(screenWidth * 0.16F), 132, 176);
        int rightInset = compact ? 9 : Math.max(16, Math.round(screenWidth * 0.035F));
        int rightX = screenWidth - rightInset - rightWidth;
        int rightY = Math.max(48, Math.round(screenHeight * 0.17F));

        drawTargetReticle(graphics, font, screenWidth, screenHeight, lockedTarget);

        if (animatedTarget == null || panelProgress <= 0.01F) {
            return;
        }

        float eased = smoothstep(panelProgress);
        if (quickhacking && QuickhackTargets.isActionable(animatedTarget)) {
            int leftClipRight = Math.round((leftX + leftWidth + 12) * eased);
            int leftOffset = -Math.round(12.0F * (1.0F - eased));
            graphics.enableScissor(0, 0, leftClipRight, screenHeight);
            graphics.pose().pushMatrix();
            graphics.pose().translate(leftOffset, 0.0F);
            drawQuickhackMenu(graphics, font, player, animatedTarget,
                    leftX, leftY, leftWidth, screenHeight);
            graphics.pose().popMatrix();
            graphics.disableScissor();
        }

        int rightClipLeft = Math.round(screenWidth
                - (screenWidth - rightX + 12) * eased);
        int rightOffset = Math.round(12.0F * (1.0F - eased));
        graphics.enableScissor(rightClipLeft, 0, screenWidth, screenHeight);
        graphics.pose().pushMatrix();
        graphics.pose().translate(rightOffset, 0.0F);
        drawIntelPanel(graphics, font, player, animatedTarget, rightX, rightY, rightWidth);
        graphics.pose().popMatrix();
        graphics.disableScissor();
    }

    private float updatePanelVisibility(@Nullable Entity lockedTarget,
                                        DeltaTracker deltaTracker) {
        float realtimeTicks = Mth.clamp(deltaTracker.getRealtimeDeltaTicks(), 0.0F, 2.0F);
        if (lockedTarget != null) {
            animatedTarget = lockedTarget;
            panelVisibility = Mth.approach(panelVisibility, 1.0F, realtimeTicks * 0.24F);
        } else {
            panelVisibility = Mth.approach(panelVisibility, 0.0F, realtimeTicks * 0.34F);
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
        graphics.fill(0, 0, width, height, 0x13001818);
        graphics.fill(0, 0, width, height, 0x100E0005);

        int phase = (int) (gameTime % 7L);
        for (int y = phase; y < height; y += 7) {
            graphics.fill(0, y, width, y + 1, 0x073CFFD0);
        }
        int sweepY = (int) ((gameTime * 2L) % Math.max(1, height));
        graphics.fill(0, sweepY, width, sweepY + 1, 0x163DFFC5);

        edgeBand(graphics, width, height, 0, 7, 0x5810080A);
        edgeBand(graphics, width, height, 7, 8, 0x2810070A);
        frameCorner(graphics, 14, 14, 1, 1, 20, 0x779F3239);
        frameCorner(graphics, width - 15, 14, -1, 1, 20, 0x779F3239);
        frameCorner(graphics, 14, height - 15, 1, -1, 20, 0x779F3239);
        frameCorner(graphics, width - 15, height - 15, -1, -1, 20, 0x779F3239);

        graphics.fill(15, 22, 18, 25, RED);
        graphics.text(font, "SCAN // LIVE", 22, 19, RED_DIM, false);
        String link = "NEURAL LINK";
        graphics.text(font, link, width - 18 - font.width(link), 19, CYAN_DIM, false);
    }

    private static void drawRamRail(GuiGraphicsExtractor graphics, Font font, Player player,
                                    int screenWidth, int screenHeight) {
        int raw = RamAttachments.get(player);
        int reserved = Math.min(raw, QuickhackUploadClient.reservedRam());
        int available = Math.max(0, raw - reserved);
        int max = RamAttachments.MAX_RAM;
        int railWidth = Mth.clamp(Math.round(screenWidth * 0.25F), 138, 234);
        int gap = 2;
        int segmentWidth = Math.max(4, (railWidth - (max - 1) * gap) / max);
        int usedWidth = max * segmentWidth + (max - 1) * gap;
        int x = screenWidth / 2 - usedWidth / 2;
        int y = screenHeight < 240 ? 20 : Math.max(28, Math.round(screenHeight * 0.07F));

        String title = "RAM  " + available + "/" + max;
        graphics.text(font, title, x, y - 12, CYAN, false);
        if (reserved > 0) {
            String reserve = "RES " + reserved;
            graphics.text(font, reserve, x + usedWidth - font.width(reserve), y - 12,
                    ORANGE, false);
        }

        for (int i = 0; i < max; i++) {
            int color = i < available ? CYAN : (i < raw ? RESERVED_RAM : EMPTY_RAM);
            int sx = x + i * (segmentWidth + gap);
            graphics.fill(sx - 1, y - 1, sx + segmentWidth + 1, y + 9, CYAN_DARK);
            graphics.fill(sx, y, sx + segmentWidth, y + 8, color);
            if (i < available) {
                graphics.fill(sx, y, sx + segmentWidth, y + 2, CYAN_BRIGHT);
            }
        }
    }

    private static void drawQuickhackMenu(GuiGraphicsExtractor graphics, Font font, Player player,
                                          Entity target, int x, int y, int width,
                                          int screenHeight) {
        int availableRam = Math.max(0,
                RamAttachments.get(player) - QuickhackUploadClient.reservedRam());
        CyberwareData cyberware = CyberwareAttachments.get(player);
        boolean dense = screenHeight < 250;
        boolean showDetail = screenHeight >= 330;
        int detailHeight = showDetail ? 27 : 0;
        int count = QuickhackTargets.actionCount(target);
        if (count <= 0) {
            return;
        }
        int availableHeight = screenHeight - y - 18 - detailHeight - 10;
        int rowHeight = Mth.clamp(availableHeight / count, dense ? 18 : 23, dense ? 22 : 36);

        graphics.text(font, "QUICKHACKS", x + 5, y, CYAN_BRIGHT, false);
        String targetLabel = trim(font, target.getName().getString().toUpperCase(Locale.ROOT),
                Math.max(34, width - 76));
        graphics.text(font, targetLabel, x + width - 4 - font.width(targetLabel), y,
                ORANGE, false);
        graphics.horizontalLine(x, x + width - 1, y + 12, CYAN_DIM);
        graphics.fill(x, y + 12, x + 36, y + 14, CYAN);

        int rowY = y + 18;
        int targetId = target.getId();
        if (QuickhackTargets.isDevice(target)) {
            int requiredTier = target instanceof KangTaoTurret turret
                    ? turret.getSecurityLevel() : 0;
            int deckTier = KangTaoTurret.cyberdeckSecurityLevel(
                    CyberwareAttachments.get(player));
            boolean tierLocked = requiredTier > deckTier;
            for (int slot = 0; slot < count; slot++) {
                DeviceQuickhack action = DeviceQuickhack.fromSlot(target, slot);
                if (action == null) {
                    continue;
                }
                int wireId = action.wireId();
                int ramCost = CyberwareEffects.quickhackRamCost(cyberware, action);
                boolean selected = slot == QuickhackScannerClient.selectedSkillOrdinal();
                boolean uploading = wireId == QuickhackUploadClient.activeSkillOrdinal(targetId);
                int queuePosition = QuickhackUploadClient.queuePosition(targetId, wireId);
                boolean committed = uploading || queuePosition > 0;
                boolean affordable = committed || ramCost <= availableRam;
                String unavailable = tierLocked ? "TIER " + requiredTier + " REQUIRED" : null;
                if (unavailable == null && target instanceof KangTaoTurret turret) {
                    if (turret.isDeactivated()
                            && action == DeviceQuickhack.TURRET_TAKE_CONTROL) {
                        unavailable = "DEVICE OFFLINE";
                    } else if (turret.isDeactivated()
                            && action == DeviceQuickhack.TURRET_DEACTIVATE) {
                        unavailable = "ALREADY OFFLINE";
                    } else if (turret.isRemotelyControlled()
                            && action == DeviceQuickhack.TURRET_TAKE_CONTROL) {
                        unavailable = "LINK IN USE";
                    }
                }
                if (unavailable == null && !affordable) {
                    unavailable = "RAM REQUIRED";
                }
                drawHackRow(graphics, font, action.stack(), action.displayName(),
                        ramCost, x, rowY, width, rowHeight, selected,
                        unavailable, uploading, queuePosition, dense);
                rowY += rowHeight;
            }
        } else {
            for (int ordinal = 0; ordinal < count; ordinal++) {
                Skill skill = Skill.fromSlot(ordinal);
                if (skill == null) {
                    continue;
                }
                boolean selected = ordinal == QuickhackScannerClient.selectedSkillOrdinal();
                int ramCost = CyberwareEffects.quickhackRamCost(cyberware, skill);
                boolean uploading = ordinal == QuickhackUploadClient.activeSkillOrdinal(targetId);
                int queuePosition = QuickhackUploadClient.queuePosition(targetId, ordinal);
                boolean committed = uploading || queuePosition > 0;
                boolean affordable = committed || ramCost <= availableRam;
                drawHackRow(graphics, font, skill.stack(), skill.displayName(), ramCost,
                        x, rowY, width, rowHeight, selected,
                        affordable ? null : "RAM REQUIRED", uploading, queuePosition, dense);
                rowY += rowHeight;
            }
        }

        if (showDetail) {
            DeviceQuickhack selectedDevice = QuickhackScannerClient.selectedDeviceQuickhack(
                    Minecraft.getInstance().level);
            Skill selectedSkill = QuickhackScannerClient.selectedSkill();
            if (selectedDevice != null || selectedSkill != null) {
                int detailY = rowY + 3;
                graphics.horizontalLine(x, x + width - 1, detailY, RED_DIM);
                int ramCost = selectedDevice == null
                        ? CyberwareEffects.quickhackRamCost(cyberware, selectedSkill)
                        : CyberwareEffects.quickhackRamCost(cyberware, selectedDevice);
                int uploadTicks = selectedDevice == null
                        ? CyberwareEffects.quickhackUploadTicks(cyberware, selectedSkill)
                        : CyberwareEffects.quickhackUploadTicks(cyberware, selectedDevice);
                String timing = String.format(Locale.ROOT, "%d RAM  //  %.1f SEC",
                        ramCost, uploadTicks / 20.0F);
                graphics.text(font, timing, x + 5, detailY + 5, AMBER, false);
                String summary = selectedDevice == null
                        ? hackSummary(selectedSkill) : selectedDevice.summary();
                graphics.text(font, trim(font, summary, width - 10),
                        x + 5, detailY + 15, WHITE, false);
            }
        }
    }

    private static void drawHackRow(GuiGraphicsExtractor graphics, Font font, ItemStack icon,
                                    String displayName, int ramCost, int x, int y, int width,
                                    int height, boolean selected,
                                    @Nullable String unavailableState, boolean uploading,
                                    int queuePosition, boolean dense) {
        int rowX = selected ? x - 5 : x;
        int rowWidth = selected ? width + 5 : width;
        int rowBottom = y + height - 2;
        boolean available = unavailableState == null;
        int border = !available ? RED : (selected ? CYAN_BRIGHT : CYAN_DIM);
        int fill = !available ? ROW_UNAVAILABLE : (selected ? ROW_SELECTED : ROW);

        fillCutRect(graphics, rowX, y, rowWidth, height - 1, fill);
        drawCutBorder(graphics, rowX, y, rowWidth, height - 1, border);
        if (selected) {
            graphics.fill(rowX - 3, y + 4, rowX, rowBottom - 3, RED);
            graphics.fill(rowX, y, rowX + Math.min(46, rowWidth / 3), y + 2, CYAN_BRIGHT);
        }

        int iconSize = dense ? 16 : Math.min(22, height - 6);
        int iconX = rowX + rowWidth - iconSize - 5;
        int iconY = y + Math.max(2, (height - iconSize - 1) / 2);
        graphics.fill(iconX - 1, iconY - 1, iconX + iconSize + 1, iconY + iconSize + 1,
                0xD0051118);
        graphics.outline(iconX - 1, iconY - 1, iconSize + 2, iconSize + 2, border);
        int itemX = iconX + (iconSize - 16) / 2;
        int itemY = iconY + (iconSize - 16) / 2;
        graphics.item(icon, itemX, itemY);

        String cost = Integer.toString(ramCost);
        int costX = iconX - 8 - font.width(cost);
        int textX = rowX + 7;
        int nameY = dense ? y + Math.max(3, (height - font.lineHeight) / 2) : y + 4;
        String name = trim(font, displayName.toUpperCase(Locale.ROOT),
                Math.max(24, costX - textX - 7));
        graphics.text(font, name, textX, nameY, available ? CYAN_BRIGHT : RED, false);
        graphics.text(font, cost, costX, nameY, available ? CYAN : RED, false);
        if (!dense) {
            graphics.text(font, "RAM", costX - 1, y + height - 11, CYAN_DIM, false);
            String state = !available ? unavailableState
                    : (uploading ? "UPLOADING"
                    : (queuePosition > 0 ? "QUEUED " + queuePosition : "READY"));
            int stateColor = !available ? RED
                    : (uploading ? ORANGE : (queuePosition > 0 ? AMBER : CYAN));
            drawStatus(graphics, font, textX, y + height - 12, state, stateColor);
        }
    }

    private static void drawTargetReticle(GuiGraphicsExtractor graphics, Font font, int width,
                                          int height, @Nullable Entity target) {
        int centerX = width / 2;
        int centerY = height / 2 + 3;
        int color = target == null ? CYAN_DIM : ORANGE;
        int radius = target == null ? 17 : 23;

        frameCorner(graphics, centerX - radius, centerY - radius, 1, 1, 8, color);
        frameCorner(graphics, centerX + radius, centerY - radius, -1, 1, 8, color);
        frameCorner(graphics, centerX - radius, centerY + radius, 1, -1, 8, color);
        frameCorner(graphics, centerX + radius, centerY + radius, -1, -1, 8, color);
        graphics.horizontalLine(centerX - 7, centerX - 3, centerY, color);
        graphics.horizontalLine(centerX + 3, centerX + 7, centerY, color);
        graphics.verticalLine(centerX, centerY - 7, centerY - 3, color);
        graphics.verticalLine(centerX, centerY + 3, centerY + 7, color);

        String state = target == null ? "ACQUIRE TARGET" : "TARGET LOCKED";
        int stateWidth = font.width(state);
        int stateY = centerY - radius - 15;
        graphics.fill(centerX - stateWidth / 2 - 5, stateY - 2,
                centerX + stateWidth / 2 + 5, stateY + 10, 0xA0051016);
        graphics.centeredText(font, state, centerX, stateY, color);
    }

    private static void drawIntelPanel(GuiGraphicsExtractor graphics, Font font, Player player,
                                       Entity target, int x, int y, int width) {
        if (target instanceof KangTaoTurret turret) {
            drawTurretIntelPanel(graphics, font, player, turret, x, y, width);
            return;
        }
        if (QuickhackTargets.isDevice(target)) {
            drawVehicleIntelPanel(graphics, font, player, target, x, y, width);
            return;
        }
        if (target instanceof LivingEntity living) {
            drawLivingIntelPanel(graphics, font, player, living, x, y, width);
        }
    }

    private static void drawLivingIntelPanel(GuiGraphicsExtractor graphics, Font font,
                                             Player player, LivingEntity target,
                                             int x, int y, int width) {
        int height = 128;
        fillCutRect(graphics, x, y, width, height, PANEL);
        drawCutBorder(graphics, x, y, width, height, CYAN_DIM);
        graphics.fill(x, y, x + 38, y + 2, RED);
        graphics.text(font, "TARGET DATA", x + 7, y + 6, CYAN_DIM, false);

        String name = trim(font, target.getName().getString().toUpperCase(Locale.ROOT), width - 14);
        graphics.text(font, name, x + 7, y + 18, CYAN_BRIGHT, false);
        String affiliation = target instanceof CyberpsychoEntity
                ? "CYBERPSYCHO"
                : target instanceof FactionEnemy enemy && enemy.isTraumaTeam()
                ? "TRAUMA TEAM"
                : target instanceof FactionEnemy enemy && enemy.isExcision()
                ? "EXCISION"
                : target instanceof FactionEnemy enemy
                ? enemy.getDistrict() == null
                        ? "CORPORATE SECURITY"
                        : enemy.getDistrict().label().toUpperCase(Locale.ROOT)
                : target instanceof CityNpc ? "CITY DATABASE"
                : target.getType().getDescription().getString().toUpperCase(Locale.ROOT);
        graphics.text(font, trim(font, affiliation, width - 14), x + 7, y + 29,
                AMBER, false);

        float healthRatio = target.getMaxHealth() <= 0.0F ? 0.0F
                : Mth.clamp(target.getHealth() / target.getMaxHealth(), 0.0F, 1.0F);
        String health = String.format(Locale.ROOT, "HP %.0f/%.0f",
                target.getHealth(), target.getMaxHealth());
        graphics.text(font, health, x + 7, y + 43, healthColor(target), false);
        int barWidth = width - 14;
        graphics.fill(x + 7, y + 54, x + 7 + barWidth, y + 58, CYAN_DARK);
        graphics.fill(x + 7, y + 54, x + 7 + Math.round(barWidth * healthRatio), y + 58,
                healthColor(target));

        boolean armed = isArmed(target);
        boolean exec = isExec(target);
        drawIntelLine(graphics, font, "TYPE", npcType(target), x, y + 65, width, AMBER);
        drawIntelLine(graphics, font, "DROP", expectedMoneyDrop(target), x, y + 76, width, CYAN);
        drawIntelLine(graphics, font, "ARMED", armed ? "YES" : "NO",
                x, y + 87, width, armed ? RED : CYAN);
        drawIntelLine(graphics, font, "EXEC", exec ? "YES" : "NO",
                x, y + 98, width, exec ? RED : CYAN_DIM);

        String distance = String.format(Locale.ROOT, "%.0f M", player.distanceTo(target));
        graphics.text(font, distance, x + 7, y + 113, WHITE, false);
        String status = targetStatus(target);
        graphics.text(font, status, x + width - 7 - font.width(status), y + 113, RED, false);
    }

    private static void drawTurretIntelPanel(GuiGraphicsExtractor graphics, Font font,
                                             Player player, KangTaoTurret turret,
                                             int x, int y, int width) {
        int height = 128;
        fillCutRect(graphics, x, y, width, height, PANEL);
        drawCutBorder(graphics, x, y, width, height, CYAN_DIM);
        graphics.fill(x, y, x + 38, y + 2, RED);
        graphics.text(font, "DEVICE DATA", x + 7, y + 6, CYAN_DIM, false);

        String name = trim(font, turret.getName().getString().toUpperCase(Locale.ROOT),
                width - 14);
        graphics.text(font, name, x + 7, y + 18, CYAN_BRIGHT, false);
        graphics.text(font, trim(font, "AUTOMATED DEFENSE", width - 14),
                x + 7, y + 29, AMBER, false);

        float healthRatio = turret.getMaxHealth() <= 0.0F ? 0.0F
                : Mth.clamp(turret.getHealth() / turret.getMaxHealth(), 0.0F, 1.0F);
        String health = String.format(Locale.ROOT, "HP %.0f/%.0f",
                turret.getHealth(), turret.getMaxHealth());
        graphics.text(font, health, x + 7, y + 43, healthColor(turret), false);
        int barWidth = width - 14;
        graphics.fill(x + 7, y + 54, x + 7 + barWidth, y + 58, CYAN_DARK);
        graphics.fill(x + 7, y + 54, x + 7 + Math.round(barWidth * healthRatio), y + 58,
                healthColor(turret));

        int securityTier = turret.getSecurityLevel();
        int deckTier = KangTaoTurret.cyberdeckSecurityLevel(CyberwareAttachments.get(player));
        boolean locked = deckTier < securityTier;
        String deviceState = turret.isDeactivated() ? "DEACTIVATED"
                : turret.isRemotelyControlled() ? "REMOTE CONTROL" : "ACTIVE";
        drawIntelLine(graphics, font, "TYPE", "SECURITY TURRET",
                x, y + 65, width, AMBER);
        drawIntelLine(graphics, font, "SECURITY", "LEVEL " + securityTier,
                x, y + 76, width, locked ? RED : AMBER);
        drawIntelLine(graphics, font, "DECK", "LEVEL " + deckTier,
                x, y + 87, width, locked ? RED : CYAN);
        drawIntelLine(graphics, font, "STATE", deviceState,
                x, y + 98, width, turret.isDeactivated() ? CYAN_DIM : RED);

        String distance = String.format(Locale.ROOT, "%.0f M", player.distanceTo(turret));
        graphics.text(font, distance, x + 7, y + 113, WHITE, false);
        String status = locked ? "ACCESS DENIED"
                : turret.isDeactivated() ? "OFFLINE" : "HOSTILE";
        status = trim(font, status,
                Math.max(0, width - 21 - font.width(distance)));
        int statusColor = locked ? RED : turret.isDeactivated() ? CYAN_DIM : RED;
        graphics.text(font, status, x + width - 7 - font.width(status), y + 113,
                statusColor, false);
    }

    private static void drawVehicleIntelPanel(GuiGraphicsExtractor graphics, Font font,
                                              Player player, Entity vehicle,
                                              int x, int y, int width) {
        int height = 128;
        fillCutRect(graphics, x, y, width, height, PANEL);
        drawCutBorder(graphics, x, y, width, height, CYAN_DIM);
        graphics.fill(x, y, x + 38, y + 2, RED);
        graphics.text(font, "DEVICE DATA", x + 7, y + 6, CYAN_DIM, false);

        String name = trim(font, vehicle.getName().getString().toUpperCase(Locale.ROOT),
                width - 14);
        graphics.text(font, name, x + 7, y + 18, CYAN_BRIGHT, false);
        graphics.text(font, trim(font, "VEHICLE CONTROL BUS", width - 14),
                x + 7, y + 29, AMBER, false);
        graphics.text(font, "SYSTEM ONLINE", x + 7, y + 43, CYAN, false);
        int barWidth = width - 14;
        graphics.fill(x + 7, y + 54, x + 7 + barWidth, y + 58, CYAN_DARK);
        graphics.fill(x + 7, y + 54, x + 7 + barWidth, y + 58, CYAN);

        String type = vehicle.getType().getDescription().getString().toUpperCase(Locale.ROOT);
        double speed = vehicle.getDeltaMovement().horizontalDistance() * 20.0;
        drawIntelLine(graphics, font, "TYPE", type, x, y + 65, width, AMBER);
        drawIntelLine(graphics, font, "SPEED",
                String.format(Locale.ROOT, "%.1f M/S", speed),
                x, y + 76, width, speed > 0.1 ? ORANGE : CYAN);
        drawIntelLine(graphics, font, "RIDERS", Integer.toString(vehicle.getPassengers().size()),
                x, y + 87, width, vehicle.getPassengers().isEmpty() ? CYAN_DIM : AMBER);
        drawIntelLine(graphics, font, "STATE", speed > 0.1 ? "MOVING" : "STATIONARY",
                x, y + 98, width, speed > 0.1 ? ORANGE : CYAN);

        String distance = String.format(Locale.ROOT, "%.0f M", player.distanceTo(vehicle));
        graphics.text(font, distance, x + 7, y + 113, WHITE, false);
        String status = "LINK READY";
        status = trim(font, status,
                Math.max(0, width - 21 - font.width(distance)));
        graphics.text(font, status, x + width - 7 - font.width(status), y + 113,
                CYAN, false);
    }

    private static void drawIntelLine(GuiGraphicsExtractor graphics, Font font, String label,
                                      String value, int x, int y, int width, int valueColor) {
        graphics.text(font, label, x + 7, y, CYAN_DIM, false);
        int available = width - 17 - font.width(label);
        String fitted = trim(font, value, available);
        graphics.text(font, fitted, x + width - 7 - font.width(fitted), y, valueColor, false);
    }

    private static String npcType(LivingEntity target) {
        if (target instanceof CityNpc npc) {
            return formatId(npc.getRole().id());
        }
        if (target instanceof CyberpsychoEntity) {
            return "CYBERPSYCHO";
        }
        if (target instanceof FactionEnemy enemy && enemy.isTraumaTeam()) {
            return "TRAUMA RESPONDER";
        }
        if (target instanceof FactionEnemy enemy && enemy.isExcision()) {
            return "EXCISION AGENT";
        }
        if (target instanceof FactionEnemy) {
            return "CORPORATE SOLDIER";
        }
        return target.getType().getDescription().getString().toUpperCase(Locale.ROOT);
    }

    private static String expectedMoneyDrop(LivingEntity target) {
        if (!(target instanceof CityNpc npc)) {
            return "E$ 0";
        }
        NpcRole role = npc.getRole();
        return role.minimumCredits() == role.maximumCredits()
                ? "E$ " + role.minimumCredits()
                : "E$ " + role.minimumCredits() + "-" + role.maximumCredits();
    }

    private static boolean isArmed(LivingEntity target) {
        return !target.getMainHandItem().isEmpty()
                || !target.getOffhandItem().isEmpty();
    }

    private static boolean isExec(LivingEntity target) {
        return target instanceof CityNpc npc && npc.getRole() == NpcRole.EXEC;
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

    private static void drawStatus(GuiGraphicsExtractor graphics, Font font, int x, int y,
                                   String text, int color) {
        int width = font.width(text) + 6;
        graphics.fill(x, y, x + width, y + 9, 0x6A07181E);
        graphics.outline(x, y, width, 9, color);
        graphics.text(font, text, x + 3, y, color, false);
    }

    private static String targetStatus(LivingEntity target) {
        if (target instanceof FactionEnemy enemy) {
            return enemy.isTriggered() ? "ALERT" : "UNAWARE";
        }
        return target instanceof Enemy ? "HOSTILE" : "NEUTRAL";
    }

    private static int healthColor(LivingEntity target) {
        float ratio = target.getMaxHealth() <= 0.0F ? 0.0F
                : target.getHealth() / target.getMaxHealth();
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
