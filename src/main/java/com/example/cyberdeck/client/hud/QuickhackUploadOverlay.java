package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.client.QuickhackUploadClient;
import com.example.cyberdeck.network.QuickhackUploadPacket;
import com.example.cyberdeck.skill.Skill;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * HUD marker shown while a quickhack is uploading onto a target: the target's name, the quickhack
 * being uploaded, and a progress bar, drawn centered just above the crosshair.
 */
public final class QuickhackUploadOverlay implements GuiLayer {
    private static final int BOX_BG = 0xC0100018;
    private static final int BOX_BORDER = 0xFFB040FF;
    private static final int TITLE_COLOR = 0xFFE0B0FF;
    private static final int NAME_COLOR = 0xFFFF6060;
    private static final int BAR_BG = 0xC0000000;
    private static final int BAR_FILL = 0xFFB040FF;

    private static final int BOX_W = 128;
    private static final int PAD = 4;
    private static final int BAR_H = 4;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        if (!QuickhackUploadClient.isUploading()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        QuickhackUploadPacket p = QuickhackUploadClient.get();
        Skill skill = Skill.fromSlot(p.skillOrdinal());
        if (skill == null) {
            return;
        }

        Entity target = mc.level.getEntity(p.targetId());
        String targetName = target instanceof LivingEntity living
                ? living.getName().getString()
                : "TARGET";

        long total = p.endTick() - p.startTick();
        long elapsed = Math.max(0, mc.level.getGameTime() - p.startTick());
        float progress = total <= 0 ? 1.0f : Math.min(1.0f, elapsed / (float) total);

        Font font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int boxH = PAD + font.lineHeight + 2 + font.lineHeight + 3 + BAR_H + PAD;
        int x = screenW / 2 - BOX_W / 2;
        int y = screenH / 2 - 60 - boxH;

        // Panel.
        graphics.fill(x - 1, y - 1, x + BOX_W + 1, y + boxH + 1, BOX_BORDER);
        graphics.fill(x, y, x + BOX_W, y + boxH, BOX_BG);

        int ty = y + PAD;
        graphics.text(font, Component.literal("UPLOADING: " + skill.displayName()), x + PAD, ty, TITLE_COLOR, false);
        ty += font.lineHeight + 2;
        graphics.text(font, Component.literal(targetName), x + PAD, ty, NAME_COLOR, false);
        ty += font.lineHeight + 3;

        int barX0 = x + PAD;
        int barX1 = x + BOX_W - PAD;
        int barW = barX1 - barX0;
        graphics.fill(barX0, ty, barX1, ty + BAR_H, BAR_BG);
        graphics.fill(barX0, ty, barX0 + (int) (barW * progress), ty + BAR_H, BAR_FILL);
    }
}
