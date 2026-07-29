package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.QuickhackAttachments;
import com.example.cyberdeck.ram.RamAttachments;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * Always-on HUD element showing the player's RAM as a segmented cyan bar above the hotbar, with a
 * numeric readout. Purely presentational; it reads the client-synced RAM attachment.
 */
public final class RamHudOverlay implements GuiLayer {
    private static final int SEG_W = 6;
    private static final int SEG_H = 6;
    private static final int SEG_GAP = 2;

    // Colors (ARGB).
    private static final int LABEL_COLOR = 0xFF33E0FF;
    private static final int SEG_FILLED = 0xFF2BE3FF;
    private static final int SEG_FILLED_HI = 0xFF9BF3FF;
    private static final int SEG_EMPTY = 0x66103038;
    private static final int SEG_BORDER = 0xFF0A2A33;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return;
        }
        // Only show RAM while the player is in quickhacking mode.
        if (!QuickhackAttachments.isQuickhacking(player)) {
            return;
        }

        int ram = RamAttachments.get(player);
        int max = RamAttachments.MAX_RAM;
        Font font = mc.font;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Total width of the segment strip.
        int stripW = max * SEG_W + (max - 1) * SEG_GAP;
        // Sit just left of center, a bit above the hotbar / health area.
        int x = screenW / 2 - 91; // hotbar left edge
        int y = screenH - 48;

        // "RAM" label.
        graphics.text(font, Component.literal("RAM"), x, y - 9, LABEL_COLOR, true);
        // Numeric readout on the right of the strip.
        String num = ram + "/" + max;
        graphics.text(font, Component.literal(num), x + stripW + 4, y - 1, LABEL_COLOR, true);

        // Segments.
        int sx = x;
        for (int i = 0; i < max; i++) {
            int x0 = sx;
            int y0 = y;
            int x1 = sx + SEG_W;
            int y1 = y + SEG_H;
            // border
            graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, SEG_BORDER);
            if (i < ram) {
                graphics.fill(x0, y0, x1, y1, SEG_FILLED);
                // subtle highlight line on top of filled segments
                graphics.fill(x0, y0, x1, y0 + 1, SEG_FILLED_HI);
            } else {
                graphics.fill(x0, y0, x1, y1, SEG_EMPTY);
            }
            sx += SEG_W + SEG_GAP;
        }
    }
}
