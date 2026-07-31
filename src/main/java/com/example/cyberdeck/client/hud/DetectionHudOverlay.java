package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.faction.FactionEnemy;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.gui.GuiLayer;

import java.util.List;

/**
 * Cyberpunk-style stealth "detection" meter shown just above the crosshair. It scans nearby
 * {@link FactionEnemy} soldiers client-side and reads their server-synced detection level (see
 * {@link FactionEnemy#getDetection()}), showing a bar that fills with the highest current detection
 * of the player. The bar is hidden when no enemy is aware of the player (detection 0) and once an
 * enemy has fully spotted the player (bar full while the nearest enemy is triggered) it flashes red
 * to signal the squad has aggroed. Purely presentational; no new packet is required because the
 * detection value already travels via synced entity data.
 */
public final class DetectionHudOverlay implements GuiLayer {
    /** How far around the player to scan for enemies whose awareness feeds the meter. */
    private static final double SCAN_RADIUS = 32.0;
    private static final int BAR_WIDTH = 80;
    private static final int BAR_HEIGHT = 5;

    private static final int BACKGROUND = 0xC00A0F14;
    private static final int BORDER = 0xFF0A2A33;
    private static final int FILL_LOW = 0xFFF2D14A;   // amber while building
    private static final int FILL_HIGH = 0xFFFF7A3C;  // orange as it nears threshold
    private static final int FILL_ALERT = 0xFFFF2A2A; // red once fully detected / aggroed
    private static final int LABEL_COLOR = 0xFFF2D14A;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null
                || mc.gui.hud.isHidden()
                || mc.gui.screen() != null
                || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }

        int threshold = FactionEnemy.detectionThreshold();
        int best = 0;
        boolean aggroed = false;
        AABB scan = player.getBoundingBox().inflate(SCAN_RADIUS);
        List<FactionEnemy> enemies = mc.level.getEntitiesOfClass(FactionEnemy.class, scan,
                FactionEnemy::isAlive);
        for (FactionEnemy enemy : enemies) {
            int detection = enemy.getDetection();
            if (enemy.isTriggered() && enemy.getTarget() == player) {
                aggroed = true;
                best = threshold;
            } else if (detection > best) {
                best = detection;
            }
        }

        // Nothing is aware of the player: keep the HUD clean.
        if (best <= 0 && !aggroed) {
            return;
        }

        float ratio = threshold <= 0 ? 0.0f : Math.min(1.0f, (float) best / threshold);
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = (screenW - BAR_WIDTH) / 2;
        int y = screenH / 2 - 24; // sit above the crosshair

        Font font = mc.font;
        Component label = aggroed
                ? Component.literal("DETECTED")
                : Component.literal("DETECTION");
        int labelWidth = font.width(label);
        graphics.text(font, label, (screenW - labelWidth) / 2, y - 10, LABEL_COLOR, true);

        // Frame + background.
        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, BORDER);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, BACKGROUND);

        // Fill.
        int fillWidth = Math.round(BAR_WIDTH * ratio);
        if (fillWidth > 0) {
            int color;
            if (aggroed || ratio >= 1.0f) {
                color = FILL_ALERT;
            } else if (ratio >= 0.6f) {
                color = FILL_HIGH;
            } else {
                color = FILL_LOW;
            }
            graphics.fill(x, y, x + fillWidth, y + BAR_HEIGHT, color);
        }
    }
}
