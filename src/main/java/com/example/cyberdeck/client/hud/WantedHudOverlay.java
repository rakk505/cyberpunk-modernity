package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.wanted.WantedState;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;

/** Compact top-center Excision wanted indicator. */
public final class WantedHudOverlay implements GuiLayer {
    private static final int LABEL_COLOR = 0xFFFF435D;
    private static final int STAR_COLOR = 0xFFFFC94A;
    private static final int SHADOW_COLOR = 0xD0000000;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.gui.hud.isHidden()
                || minecraft.gui.screen() != null
                || minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        int stars = WantedState.get(minecraft.player).stars();
        if (stars == 0) {
            return;
        }

        String label = "EXCISION";
        String markers = stars >= 3
                ? "\u2605 \u2605 \u2605"
                : "\u2605 \u2606 \u2606";
        int centerX = graphics.guiWidth() / 2;
        int labelX = centerX - minecraft.font.width(label) / 2;
        int starsX = centerX - minecraft.font.width(markers) / 2;
        graphics.text(minecraft.font, label, labelX + 1, 9, SHADOW_COLOR, false);
        graphics.text(minecraft.font, markers, starsX + 1, 20, SHADOW_COLOR, false);
        graphics.text(minecraft.font, label, labelX, 8, LABEL_COLOR, false);
        graphics.text(minecraft.font, markers, starsX, 19, STAR_COLOR, false);
    }
}
