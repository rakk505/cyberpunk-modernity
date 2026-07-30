package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.client.HealingConsumableClient;
import com.example.cyberdeck.healing.HealingConsumable;
import com.example.cyberdeck.healing.HealingState;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.gui.GuiLayer;

/** Bottom-left healing quick slot with selection and synchronized cooldown state. */
public final class HealingHudOverlay implements GuiLayer {
    private static final int ICON_SIZE = 16;
    private static final int READY_BORDER = 0xFFFFC24B;
    private static final int COOLDOWN_BORDER = 0xFF6F777A;
    private static final int SLOT_BACKGROUND = 0xD9081014;
    private static final int COOLDOWN_OVERLAY = 0xB86F777A;

    private static final Identifier BOUNCE_BACK_TEXTURE = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "textures/item/bounce_back.png");
    private static final Identifier MAXDOC_TEXTURE = Identifier.fromNamespaceAndPath(
            Cyberdeck.MODID, "textures/item/maxdoc.png");

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        HealingConsumable selected = HealingConsumableClient.selected();
        HealingState state = HealingState.get(player);
        long gameTick = minecraft.level.getGameTime();
        int x = 8;
        int y = minecraft.getWindow().getGuiScaledHeight() - 29;

        long remaining = HealingConsumableClient.cooldownRemaining(
                state, selected, gameTick);
        drawSlot(graphics, selected, remaining, x, y);
    }

    private static void drawSlot(
            GuiGraphicsExtractor graphics,
            HealingConsumable consumable,
            long remaining,
            int x,
            int y) {
        graphics.fill(x - 1, y - 1, x + ICON_SIZE + 1, y + ICON_SIZE + 1, SLOT_BACKGROUND);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                texture(consumable),
                x,
                y,
                0.0F,
                0.0F,
                ICON_SIZE,
                ICON_SIZE,
                ICON_SIZE,
                ICON_SIZE,
                ICON_SIZE,
                ICON_SIZE);

        if (remaining > 0L) {
            float fraction = Math.min(1.0F, remaining / (float) consumable.cooldownTicks());
            int overlayHeight = Math.max(1, (int) Math.ceil(ICON_SIZE * fraction));
            int overlayTop = y + ICON_SIZE - overlayHeight;
            graphics.fill(x, overlayTop, x + ICON_SIZE, y + ICON_SIZE, COOLDOWN_OVERLAY);
        }
        int border = remaining == 0L ? READY_BORDER : COOLDOWN_BORDER;
        graphics.outline(x - 1, y - 1, ICON_SIZE + 2, ICON_SIZE + 2, border);
    }

    private static Identifier texture(HealingConsumable consumable) {
        return consumable == HealingConsumable.BOUNCE_BACK
                ? BOUNCE_BACK_TEXTURE
                : MAXDOC_TEXTURE;
    }
}
