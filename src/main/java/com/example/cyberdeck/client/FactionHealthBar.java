package com.example.cyberdeck.client;

import com.example.cyberdeck.Cyberdeck;
import com.example.cyberdeck.faction.FactionEnemy;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.TriState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

/**
 * Draws a health bar above every {@link FactionEnemy}'s head using the name-tag slot. The bar is
 * yellow while the soldier is unaware of the player and turns red once it has detected the player and
 * is hunting/fighting. Filled segments scale with the soldier's remaining health.
 */
@EventBusSubscriber(modid = Cyberdeck.MODID, value = Dist.CLIENT)
public final class FactionHealthBar {
    private static final int SEGMENTS = 12;

    private FactionHealthBar() {
    }

    @SubscribeEvent
    public static void onNameTag(RenderNameTagEvent.CanRender event) {
        if (!(event.getEntity() instanceof FactionEnemy enemy) || !enemy.isAlive()) {
            return;
        }
        // The scanner's right-side intel panel owns enemy health while quickhacking.
        if (QuickhackScannerClient.isActive()) {
            event.setCanRender(TriState.FALSE);
            return;
        }

        float ratio = Math.max(0.0f, Math.min(1.0f, enemy.getHealth() / enemy.getMaxHealth()));
        int filled = Math.round(ratio * SEGMENTS);
        // Keep at least one segment while alive so a nearly-dead enemy is still visible.
        if (filled == 0 && ratio > 0.0f) {
            filled = 1;
        }

        boolean hostile = enemy.isTriggered();
        ChatFormatting fillColor = hostile ? ChatFormatting.RED : ChatFormatting.YELLOW;

        MutableComponent bar = Component.empty();
        for (int i = 0; i < SEGMENTS; i++) {
            boolean on = i < filled;
            bar.append(Component.literal("\u2588")
                    .withStyle(on ? fillColor : ChatFormatting.DARK_GRAY));
        }

        event.setContent(bar);
        event.setCanRender(TriState.TRUE);
    }
}
