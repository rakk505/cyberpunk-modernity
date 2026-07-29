package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.weapon.SmartLockState;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.client.gui.GuiLayer;

/** Crosshair lock indicator driven by the server-synced Smart Link acquisition state. */
public final class SmartLockOverlay implements GuiLayer {
    private static final int ACQUIRING = 0xFFFFB52E;
    private static final int LOCKED = 0xFF71F268;
    private static final int SHADOW = 0xB0000000;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        SmartLockState state = SmartLockState.get(mc.player);
        if (!state.acquiring()) {
            return;
        }

        long now = mc.level.getGameTime();
        boolean locked = state.locked(now);
        float progress = state.progress(now);
        int color = locked ? LOCKED : ACQUIRING;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        int radius = locked ? 16 : 18;
        int corner = 6;

        // Four angular brackets contract around the crosshair as acquisition completes.
        graphics.horizontalLine(cx - radius, cx - radius + corner, cy - radius, color);
        graphics.verticalLine(cx - radius, cy - radius, cy - radius + corner, color);
        graphics.horizontalLine(cx + radius - corner, cx + radius, cy - radius, color);
        graphics.verticalLine(cx + radius, cy - radius, cy - radius + corner, color);
        graphics.horizontalLine(cx - radius, cx - radius + corner, cy + radius, color);
        graphics.verticalLine(cx - radius, cy + radius - corner, cy + radius, color);
        graphics.horizontalLine(cx + radius - corner, cx + radius, cy + radius, color);
        graphics.verticalLine(cx + radius, cy + radius - corner, cy + radius, color);

        Font font = mc.font;
        String status = locked ? "SMART LOCK" : "ACQUIRING " + Math.round(progress * 100.0F) + "%";
        int textY = cy + radius + 6;
        graphics.centeredText(font, status, cx + 1, textY + 1, SHADOW);
        graphics.centeredText(font, status, cx, textY, color);

        Entity entity = mc.level.getEntity(state.targetId());
        if (entity instanceof LivingEntity target) {
            String name = target.getName().getString();
            graphics.centeredText(font, Component.literal(name), cx + 1,
                    textY + font.lineHeight + 1, SHADOW);
            graphics.centeredText(font, Component.literal(name), cx,
                    textY + font.lineHeight, 0xFFE6ECE8);
        }
    }
}
