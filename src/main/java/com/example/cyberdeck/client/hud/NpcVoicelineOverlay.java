package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.client.NpcVoicelineClient;
import com.example.cyberdeck.client.QuickhackScannerClient;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.gui.GuiLayer;

/** Compact wrapped NPC subtitle placed immediately above the survival status rows. */
public final class NpcVoicelineOverlay implements GuiLayer {
    private static final int SPEAKER_COLOR = 0xFFFFC24B;
    private static final int LINE_COLOR = 0xFFF2F5F4;
    private static final int BACKGROUND = 0xB8050B10;
    private static final int BORDER = 0xAA59676A;
    private static final int MAX_TEXT_WIDTH = 280;
    private static final int PADDING = 4;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.gui.hud.isHidden()
                || minecraft.gui.screen() != null
                || minecraft.getDebugOverlay().showDebugScreen()
                || QuickhackScannerClient.isActive()) {
            return;
        }
        NpcVoicelineClient.Snapshot subtitle = NpcVoicelineClient.active();
        if (subtitle == null) {
            return;
        }

        Font font = minecraft.font;
        int availableWidth = Math.max(80, graphics.guiWidth() - 24);
        int wrapWidth = Math.min(MAX_TEXT_WIDTH, availableWidth);
        List<FormattedCharSequence> wrapped = font.split(
                Component.literal(subtitle.line()), wrapWidth);
        if (wrapped.isEmpty()) {
            return;
        }

        int textWidth = Math.min(wrapWidth, font.width(subtitle.speaker()));
        for (FormattedCharSequence line : wrapped) {
            textWidth = Math.max(textWidth, font.width(line));
        }
        int contentHeight = font.lineHeight * (wrapped.size() + 1) + 2;
        int armorOffset = minecraft.player.getArmorValue() > 0 ? 10 : 0;
        int bottom = graphics.guiHeight() - 49 - armorOffset;
        int top = bottom - contentHeight - PADDING * 2;
        int left = graphics.guiWidth() / 2 - textWidth / 2 - PADDING;
        int right = left + textWidth + PADDING * 2;

        graphics.fill(left, top, right, bottom, BACKGROUND);
        graphics.fill(left, bottom - 1, right, bottom, BORDER);
        int textX = graphics.guiWidth() / 2 - textWidth / 2;
        int y = top + PADDING;
        graphics.text(font, subtitle.speaker(), textX, y, SPEAKER_COLOR, true);
        y += font.lineHeight + 2;
        for (FormattedCharSequence line : wrapped) {
            graphics.text(font, line, textX, y, LINE_COLOR, true);
            y += font.lineHeight;
        }
    }
}
