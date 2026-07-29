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
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.gui.GuiLayer;

import java.util.Locale;

/** Full-screen Cyberpunk-style scanner presentation shown while quickhacking is active. */
public final class QuickhackScannerOverlay implements GuiLayer {
    private static final int GREEN = 0xFF55FF88;
    private static final int GREEN_BRIGHT = 0xFFB3FFD0;
    private static final int GREEN_DIM = 0xFF287B4C;
    private static final int GREEN_DARK = 0xFF123824;
    private static final int CYAN = 0xFF65E9E4;
    private static final int RED = 0xFFFF6470;
    private static final int AMBER = 0xFFFFBD55;
    private static final int WHITE = 0xFFE4F8EB;
    private static final int PANEL = 0xD407130D;
    private static final int PANEL_SELECTED = 0xD7194A31;
    private static final int WASH = 0x2814B85C;
    private static final int SCANLINE = 0x1517E070;
    private static final int VIGNETTE = 0x80010805;
    private static final int EMPTY_RAM = 0xAA102419;
    private static final int RESERVED_RAM = 0xFFB87938;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!QuickhackScannerClient.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        Font font = minecraft.font;

        drawScannerWash(graphics, width, height, minecraft.level.getGameTime());
        drawRam(graphics, font, player, width);

        int margin = Math.max(6, Math.min(14, width / 64));
        int panelWidth = Mth.clamp((width - 160) / 2, 120, 215);
        drawQuickhackMenu(graphics, font, player, margin, 36, panelWidth, height);
        drawTargetReticle(graphics, font, width, height, minecraft.level.getGameTime());
        drawIntelPanel(graphics, font, player, width - margin - panelWidth, 36,
                panelWidth, height);
    }

    private static void drawScannerWash(GuiGraphicsExtractor graphics, int width, int height,
                                        long gameTime) {
        graphics.fill(0, 0, width, height, WASH);
        int phase = (int) (gameTime & 3L);
        for (int y = phase; y < height; y += 4) {
            graphics.fill(0, y, width, y + 1, SCANLINE);
        }

        int edgeX = Math.max(10, width / 22);
        int edgeY = Math.max(8, height / 18);
        graphics.fill(0, 0, edgeX, height, VIGNETTE);
        graphics.fill(width - edgeX, 0, width, height, VIGNETTE);
        graphics.fill(0, 0, width, edgeY, VIGNETTE);
        graphics.fill(0, height - edgeY, width, height, VIGNETTE);

        graphics.horizontalLine(8, width - 9, 7, GREEN_DIM);
        graphics.horizontalLine(14, width - 15, height - 8, GREEN_DIM);
        graphics.verticalLine(7, 8, height - 9, GREEN_DIM);
        graphics.verticalLine(width - 8, 8, height - 9, GREEN_DIM);
    }

    private static void drawRam(GuiGraphicsExtractor graphics, Font font, Player player,
                                int screenWidth) {
        int raw = RamAttachments.get(player);
        int reserved = Math.min(raw, QuickhackUploadClient.reservedRam());
        int available = Math.max(0, raw - reserved);
        int max = RamAttachments.MAX_RAM;
        int segmentWidth = screenWidth < 500 ? 7 : 10;
        int gap = 2;
        int stripWidth = max * segmentWidth + (max - 1) * gap;
        int x = screenWidth / 2 - stripWidth / 2;
        int y = 13;

        String label = "RAM " + available + " AVAILABLE / " + raw + " RAW / "
                + reserved + " RESERVED";
        graphics.centeredText(font, label, screenWidth / 2, 2, GREEN_BRIGHT);
        for (int i = 0; i < max; i++) {
            int color = i < available ? GREEN
                    : (i < raw ? RESERVED_RAM : EMPTY_RAM);
            int sx = x + i * (segmentWidth + gap);
            graphics.fill(sx - 1, y - 1, sx + segmentWidth + 1, y + 6, GREEN_DARK);
            graphics.fill(sx, y, sx + segmentWidth, y + 5, color);
            if (i < available) {
                graphics.fill(sx, y, sx + segmentWidth, y + 1, GREEN_BRIGHT);
            }
        }
    }

    private static void drawQuickhackMenu(GuiGraphicsExtractor graphics, Font font, Player player,
                                          int x, int y, int width, int screenHeight) {
        int availableRam = Math.max(0,
                RamAttachments.get(player) - QuickhackUploadClient.reservedRam());
        int rowHeight = Mth.clamp((screenHeight - y - 22) / Skill.STANDBY.ordinal(), 18, 25);
        int panelHeight = 16 + rowHeight * Skill.STANDBY.ordinal() + 4;

        panel(graphics, x, y, width, panelHeight, "QUICKHACK PRESETS", font);
        int rowY = y + 15;
        for (int ordinal = 0; ordinal < Skill.STANDBY.ordinal(); ordinal++) {
            Skill skill = Skill.fromSlot(ordinal);
            if (skill == null) {
                continue;
            }
            boolean selected = ordinal == QuickhackScannerClient.selectedSkillOrdinal();
            boolean affordable = skill.ramCost() <= availableRam;
            boolean uploading = ordinal == QuickhackUploadClient.activeSkillOrdinal();
            int queuePosition = QuickhackUploadClient.queuePosition(ordinal);

            int background = selected ? PANEL_SELECTED : 0xA407170F;
            graphics.fill(x + 2, rowY, x + width - 2, rowY + rowHeight - 1, background);
            graphics.verticalLine(x + 2, rowY, rowY + rowHeight - 2,
                    selected ? GREEN_BRIGHT : GREEN_DARK);
            if (selected) {
                graphics.fill(x + 2, rowY, x + 5, rowY + rowHeight - 1, GREEN);
            }

            if (rowHeight >= 20) {
                graphics.item(skill.stack(), x + 7, rowY + (rowHeight - 16) / 2);
            }
            int textX = x + (rowHeight >= 20 ? 27 : 8);
            int nameColor = selected ? GREEN_BRIGHT : (affordable ? WHITE : RED);
            String name = trim(font, skill.displayName(), width - (textX - x) - 49);
            graphics.text(font, name, textX, rowY + 3, nameColor, false);

            String cost = skill.ramCost() + " RAM";
            graphics.text(font, cost, x + width - 5 - font.width(cost), rowY + 3,
                    affordable ? GREEN : RED, false);
            if (rowHeight >= 23) {
                String state = uploading ? "UPLOADING"
                        : (queuePosition > 0 ? "QUEUE #" + queuePosition
                        : (selected ? "SELECTED" : "READY"));
                graphics.text(font, state, textX, rowY + 12,
                        uploading ? CYAN : (queuePosition > 0 ? AMBER : GREEN_DIM), false);
            }
            rowY += rowHeight;
        }
    }

    private static void drawTargetReticle(GuiGraphicsExtractor graphics, Font font, int width,
                                          int height, long gameTime) {
        LivingEntity target = QuickhackScannerClient.target(Minecraft.getInstance().level);
        int centerX = width / 2;
        int centerY = height / 2;
        int radius = target == null ? 15 : 19;
        int color = target == null ? GREEN_DIM : GREEN_BRIGHT;
        int corner = 7;

        graphics.horizontalLine(centerX - radius, centerX - radius + corner, centerY - radius, color);
        graphics.verticalLine(centerX - radius, centerY - radius, centerY - radius + corner, color);
        graphics.horizontalLine(centerX + radius - corner, centerX + radius, centerY - radius, color);
        graphics.verticalLine(centerX + radius, centerY - radius, centerY - radius + corner, color);
        graphics.horizontalLine(centerX - radius, centerX - radius + corner, centerY + radius, color);
        graphics.verticalLine(centerX - radius, centerY + radius - corner, centerY + radius, color);
        graphics.horizontalLine(centerX + radius - corner, centerX + radius, centerY + radius, color);
        graphics.verticalLine(centerX + radius, centerY + radius - corner, centerY + radius, color);
        graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, color);

        String status = target == null ? "SCANNING..." : "TARGET LOCKED";
        graphics.centeredText(font, status, centerX, centerY + radius + 5, color);
        if (target != null) {
            graphics.centeredText(font, trim(font, target.getName().getString(), 150),
                    centerX, centerY + radius + 15, WHITE);
        }

        if (QuickhackUploadClient.isUploading()) {
            Skill active = Skill.fromSlot(QuickhackUploadClient.activeSkillOrdinal());
            float progress = QuickhackUploadClient.uploadProgress(gameTime);
            int barWidth = Math.min(150, Math.max(80, width / 5));
            int barX = centerX - barWidth / 2;
            int barY = centerY - radius - 20;
            String upload = active == null ? "UPLOADING" : "UPLOADING " + active.displayName();
            graphics.centeredText(font, upload, centerX, barY - 10, CYAN);
            graphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 5, GREEN_DARK);
            graphics.fill(barX, barY, barX + barWidth, barY + 4, EMPTY_RAM);
            graphics.fill(barX, barY, barX + Math.round(barWidth * progress), barY + 4, CYAN);
            graphics.centeredText(font, Math.round(progress * 100.0F) + "%",
                    centerX, barY + 7, CYAN);
        }
    }

    private static void drawIntelPanel(GuiGraphicsExtractor graphics, Font font, Player player,
                                       int x, int y, int width, int screenHeight) {
        int height = Math.min(screenHeight - y - 12, 224);
        panel(graphics, x, y, width, height, "TARGET INTELLIGENCE", font);
        LivingEntity target = QuickhackScannerClient.target(Minecraft.getInstance().level);
        int lineY = y + 18;
        if (target == null) {
            line(graphics, font, x, width, lineY, "NO TARGET", RED);
            line(graphics, font, x, width, lineY + 13, "Align reticle with a living target", GREEN_DIM);
        } else {
            line(graphics, font, x, width, lineY,
                    trim(font, target.getName().getString(), width - 12), GREEN_BRIGHT);
            lineY += 13;
            line(graphics, font, x, width, lineY, "TYPE",
                    trim(font, target.getType().getDescription().getString(), width / 2), WHITE);
            lineY += 11;
            String faction = target instanceof FactionEnemy enemy
                    ? formatId(enemy.getFaction().id())
                    : "UNAFFILIATED";
            line(graphics, font, x, width, lineY, "FACTION", faction, AMBER);
            lineY += 11;
            line(graphics, font, x, width, lineY, "WEAPON", weaponName(target), WHITE);
            lineY += 11;
            String health = String.format(Locale.ROOT, "%.0f / %.0f",
                    target.getHealth(), target.getMaxHealth());
            line(graphics, font, x, width, lineY, "HEALTH", health, healthColor(target));
            lineY += 11;
            line(graphics, font, x, width, lineY, "ARMOR",
                    Integer.toString(target.getArmorValue()), WHITE);
            lineY += 11;
            line(graphics, font, x, width, lineY, "DISTANCE",
                    String.format(Locale.ROOT, "%.1f m", player.distanceTo(target)), WHITE);
            lineY += 11;
            line(graphics, font, x, width, lineY, "STATUS", targetStatus(target), RED);
        }

        Skill selected = QuickhackScannerClient.selectedSkill();
        int detailsY = Math.max(lineY + 18, y + height - 66);
        graphics.horizontalLine(x + 5, x + width - 6, detailsY - 5, GREEN_DARK);
        if (selected != null) {
            line(graphics, font, x, width, detailsY, "SELECTED",
                    trim(font, selected.displayName(), width / 2), GREEN_BRIGHT);
            line(graphics, font, x, width, detailsY + 11, "COST",
                    selected.ramCost() + " RAM", AMBER);
            line(graphics, font, x, width, detailsY + 22, "UPLOAD",
                    String.format(Locale.ROOT, "%.1f s", selected.uploadTicks() / 20.0F), CYAN);
        }
        graphics.text(font, "[1] PREV   [3] NEXT", x + 6, y + height - 22, GREEN, false);
        graphics.text(font, "[F / RMB] QUEUE", x + 6, y + height - 11, GREEN_BRIGHT, false);
    }

    private static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                              String title, Font font) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, GREEN_DIM);
        graphics.fill(x, y, x + width, y + height, PANEL);
        graphics.fill(x, y, x + width, y + 2, GREEN);
        graphics.text(font, Component.literal(title), x + 6, y + 5, GREEN_BRIGHT, false);
    }

    private static void line(GuiGraphicsExtractor graphics, Font font, int x, int width, int y,
                             String label, String value, int color) {
        graphics.text(font, label, x + 6, y, GREEN_DIM, false);
        String shown = trim(font, value, Math.max(20, width - 12 - font.width(label) - 5));
        graphics.text(font, shown, x + width - 6 - font.width(shown), y, color, false);
    }

    private static void line(GuiGraphicsExtractor graphics, Font font, int x, int width, int y,
                             String value, int color) {
        graphics.text(font, trim(font, value, width - 12), x + 6, y, color, false);
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
        return ratio < 0.3F ? RED : (ratio < 0.65F ? AMBER : GREEN);
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
