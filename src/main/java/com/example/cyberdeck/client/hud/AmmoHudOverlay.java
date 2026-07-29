package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.weapon.GunItem;
import com.example.cyberdeck.weapon.ReloadState;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * Bottom-left HUD showing the held gun's ammo as "loaded / magazine". While a reload is in progress
 * a short timer bar fills up over the reload duration. Only rendered while a gun is in the main hand.
 */
public final class AmmoHudOverlay implements GuiLayer {
    private static final int TEXT_COLOR = 0xFFFFC24B;
    private static final int TEXT_LOW = 0xFFFF5555;
    private static final int LABEL_COLOR = 0xFFB0B0B0;

    private static final int BAR_W = 70;
    private static final int BAR_H = 5;
    private static final int BAR_BG = 0xCC101010;
    private static final int BAR_BORDER = 0xFF000000;
    private static final int BAR_FILL = 0xFFFFC24B;
    private static final int RELOAD_TEXT = 0xFFFFC24B;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof GunItem gun)) {
            return;
        }

        Font font = mc.font;
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = 8;
        int y = screenH - 34;

        int loaded = gun.magazine(held);
        int max = gun.gun().magazineSize();

        ReloadState reload = ReloadState.get(player);
        if (reload.active()) {
            // Reloading: label + progress bar.
            graphics.text(font, Component.literal("RELOADING"), x, y - 10, RELOAD_TEXT, true);

            long total = reload.endTick() - reload.startTick();
            long elapsed = Math.max(0, mc.level == null ? 0 : mc.level.getGameTime() - reload.startTick());
            float progress = total <= 0 ? 1.0f : Math.min(1.0f, elapsed / (float) total);

            int bx0 = x;
            int by0 = y;
            int bx1 = x + BAR_W;
            int by1 = y + BAR_H;
            graphics.fill(bx0 - 1, by0 - 1, bx1 + 1, by1 + 1, BAR_BORDER);
            graphics.fill(bx0, by0, bx1, by1, BAR_BG);
            graphics.fill(bx0, by0, bx0 + (int) (BAR_W * progress), by1, BAR_FILL);
        } else {
            // Ready: ammo count.
            int color = loaded == 0 ? TEXT_LOW : TEXT_COLOR;
            graphics.text(font, Component.literal("AMMO"), x, y - 10, LABEL_COLOR, true);
            graphics.text(font, Component.literal(loaded + " / " + max), x, y, color, true);
        }
    }
}
