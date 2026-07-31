package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.faction.CrouchCombat;
import com.example.cyberdeck.faction.FactionEnemy;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * Shows a centered "Press F to instant kill" prompt whenever the client believes a valid
 * crouch-behind stealth takedown is available. Candidacy is computed client-side from the same
 * {@link CrouchCombat#findStealthTakedownTarget(Player)} heuristic the server uses; the server
 * re-validates every condition before actually killing anything, so this overlay is purely a hint.
 */
public final class StealthTakedownOverlay implements GuiLayer {
    private static final int PROMPT_COLOR = 0xFFFF2A2A;

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

        FactionEnemy target = CrouchCombat.findStealthTakedownTarget(player);
        if (target == null) {
            return;
        }

        Font font = mc.font;
        Component prompt = Component.literal("Press F to instant kill");
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = (screenW - font.width(prompt)) / 2;
        int y = screenH / 2 + 12; // just below the crosshair
        graphics.text(font, prompt, x, y, PROMPT_COLOR, true);
    }
}
