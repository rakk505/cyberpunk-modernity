package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.lifepath.Lifepath;
import com.example.cyberdeck.network.SelectLifepathPacket;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** One-time, server-confirmed starter archetype picker. */
public final class LifepathScreen extends Screen {
    private static final int MARGIN = 18;
    private static final int HEADER_HEIGHT = 66;
    private static final int FOOTER_HEIGHT = 30;
    private static final int CARD_GAP = 9;
    private static final int BUTTON_HEIGHT = 24;

    private static final int SCRIM = 0xF207090B;
    private static final int PANEL = 0xF20D1114;
    private static final int PANEL_HOVER = 0xF2181E22;
    private static final int PANEL_SELECTED = 0xF21A2024;
    private static final int TEXT = 0xFFF0F3EF;
    private static final int TEXT_DIM = 0xFF8C9B98;
    private static final int TEXT_DARK = 0xFF50615E;
    private static final int RED = 0xFFFF4658;
    private static final int CYAN = 0xFF42E9E3;
    private static final int GOLD = 0xFFFFC94A;

    private static final Choice[] CHOICES = {
            new Choice(Lifepath.NETRUNNER, CYAN,
                    "screen.cyberdeck.lifepath.netrunner.tag",
                    "screen.cyberdeck.lifepath.netrunner.description",
                    List.of(
                            "screen.cyberdeck.lifepath.netrunner.loadout.1",
                            "screen.cyberdeck.lifepath.netrunner.loadout.2",
                            "screen.cyberdeck.lifepath.netrunner.loadout.3",
                            "screen.cyberdeck.lifepath.netrunner.loadout.4")),
            new Choice(Lifepath.BRAWLER, RED,
                    "screen.cyberdeck.lifepath.brawler.tag",
                    "screen.cyberdeck.lifepath.brawler.description",
                    List.of(
                            "screen.cyberdeck.lifepath.brawler.loadout.1",
                            "screen.cyberdeck.lifepath.brawler.loadout.2",
                            "screen.cyberdeck.lifepath.brawler.loadout.3",
                            "screen.cyberdeck.lifepath.brawler.loadout.4")),
            new Choice(Lifepath.MERC, GOLD,
                    "screen.cyberdeck.lifepath.merc.tag",
                    "screen.cyberdeck.lifepath.merc.description",
                    List.of(
                            "screen.cyberdeck.lifepath.merc.loadout.1",
                            "screen.cyberdeck.lifepath.merc.loadout.2",
                            "screen.cyberdeck.lifepath.merc.loadout.3",
                            "screen.cyberdeck.lifepath.merc.loadout.4"))
    };

    private int selectedIndex;
    private boolean selectionPending;
    private int failureTicks;

    private LifepathScreen() {
        super(Component.translatable("screen.cyberdeck.lifepath.title"));
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.gui.screen() instanceof LifepathScreen)) {
            minecraft.setScreenAndShow(new LifepathScreen());
        }
    }

    public static void handleResult(boolean accepted) {
        if (Minecraft.getInstance().gui.screen() instanceof LifepathScreen screen) {
            screen.selectionPending = false;
            if (accepted) {
                screen.onClose();
            } else {
                screen.failureTicks = 80;
            }
        }
    }

    @Override
    public void tick() {
        if (failureTicks > 0) {
            failureTicks--;
        }
    }

    @Override
    public void extractBackground(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, SCRIM);
        for (int y = 2; y < height; y += 4) {
            graphics.fill(0, y, width, y + 1, 0x11000000);
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        renderHeader(graphics);
        if (compact()) {
            renderCompact(graphics, mouseX, mouseY);
        } else {
            renderCards(graphics, mouseX, mouseY);
        }
        renderFooter(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderHeader(GuiGraphicsExtractor graphics) {
        int center = width / 2;
        graphics.centeredText(font, text("screen.cyberdeck.lifepath.header"), center, 18, TEXT);
        List<FormattedCharSequence> subtitle = font.split(
                Component.literal(text("screen.cyberdeck.lifepath.subtitle")),
                Math.max(40, width - MARGIN * 2));
        int subtitleY = subtitle.size() > 1 ? 27 : 34;
        for (int line = 0; line < Math.min(2, subtitle.size()); line++) {
            FormattedCharSequence text = subtitle.get(line);
            graphics.text(font, text, center - font.width(text) / 2,
                    subtitleY + line * font.lineHeight, TEXT_DIM, false);
        }
        int lineWidth = Math.min(330, Math.max(80, width - MARGIN * 4));
        graphics.horizontalLine(center - lineWidth / 2, center - 35, 53, RED);
        graphics.horizontalLine(center + 35, center + lineWidth / 2, 53, CYAN);
        graphics.centeredText(font, String.format(java.util.Locale.ROOT, "%02d // 03",
                selectedIndex + 1), center, 49, TEXT_DARK);
    }

    private void renderCards(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (int index = 0; index < CHOICES.length; index++) {
            Choice choice = CHOICES[index];
            Rect card = card(index);
            boolean hovered = card.contains(mouseX, mouseY);
            boolean selected = index == selectedIndex;
            graphics.fill(card.x(), card.y(), card.right(), card.bottom(),
                    selected ? PANEL_SELECTED : hovered ? PANEL_HOVER : PANEL);
            graphics.outline(card.x(), card.y(), card.width(), card.height(),
                    selected ? choice.accent() : TEXT_DARK);
            graphics.fill(card.x() + 1, card.y() + 1, card.right() - 1, card.y() + 4,
                    choice.accent());

            int contentX = card.x() + 13;
            int contentWidth = card.width() - 26;
            graphics.text(font, "0" + (index + 1), card.right() - 26, card.y() + 13,
                    choice.accent(), false);
            graphics.text(font, roleName(choice), contentX, card.y() + 14, TEXT, false);
            graphics.text(font, fit(text(choice.tagKey()), contentWidth),
                    contentX, card.y() + 30, choice.accent(), false);

            int y = renderWrapped(graphics, text(choice.descriptionKey()), contentX,
                    card.y() + 50, contentWidth, TEXT_DIM, 3);
            y = Math.max(y + 5, card.y() + 90);
            graphics.horizontalLine(contentX, card.right() - 14, y, 0x553B5552);
            graphics.text(font, text("screen.cyberdeck.lifepath.loadout"),
                    contentX, y + 10, choice.accent(), false);
            int itemY = y + 27;
            int listBottom = card.bottom() - BUTTON_HEIGHT - 20;
            for (String key : choice.loadoutKeys()) {
                List<FormattedCharSequence> itemLines = font.split(
                        Component.literal(text(key)), contentWidth - 12);
                int lineCount = Math.min(2, itemLines.size());
                if (itemY + lineCount * font.lineHeight > listBottom) {
                    break;
                }
                graphics.text(font, ">", contentX, itemY, TEXT_DIM, false);
                for (int line = 0; line < lineCount; line++) {
                    graphics.text(font, itemLines.get(line), contentX + 9,
                            itemY, TEXT_DIM, false);
                    itemY += font.lineHeight;
                }
                itemY += 4;
            }

            renderButton(graphics, choice, index, mouseX, mouseY);
            if (hovered) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
    }

    private void renderCompact(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (int index = 0; index < CHOICES.length; index++) {
            Choice choice = CHOICES[index];
            Rect selector = card(index);
            boolean hovered = selector.contains(mouseX, mouseY);
            boolean selected = index == selectedIndex;
            graphics.fill(selector.x(), selector.y(), selector.right(), selector.bottom(),
                    selected ? PANEL_SELECTED : hovered ? PANEL_HOVER : PANEL);
            graphics.outline(selector.x(), selector.y(), selector.width(), selector.height(),
                    selected ? choice.accent() : TEXT_DARK);
            graphics.fill(selector.x() + 1, selector.bottom() - 3,
                    selector.right() - 1, selector.bottom() - 1,
                    selected ? choice.accent() : 0x00333333);
            graphics.centeredText(font, fit(roleName(choice), selector.width() - 8),
                    selector.x() + selector.width() / 2, selector.y() + 11,
                    selected ? choice.accent() : TEXT_DIM);
            if (hovered) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }

        Choice selected = CHOICES[selectedIndex];
        Rect detail = compactDetail();
        graphics.fill(detail.x(), detail.y(), detail.right(), detail.bottom(), PANEL);
        graphics.outline(detail.x(), detail.y(), detail.width(), detail.height(), selected.accent());
        int contentX = detail.x() + 11;
        int contentWidth = detail.width() - 22;
        graphics.text(font, fit(text(selected.tagKey()), contentWidth),
                contentX, detail.y() + 8, selected.accent(), false);
        graphics.text(font, text("screen.cyberdeck.lifepath.loadout"),
                contentX, detail.y() + 22, TEXT, false);
        int itemY = detail.y() + 36;
        int lineStep = detail.height() < 125 ? 11 : 13;
        for (String key : selected.loadoutKeys()) {
            graphics.text(font, "> " + fit(text(key), contentWidth - 8),
                    contentX, itemY, TEXT_DIM, false);
            itemY += lineStep;
        }
        renderButton(graphics, selected, selectedIndex, mouseX, mouseY);
    }

    private void renderButton(
            GuiGraphicsExtractor graphics, Choice choice, int index, int mouseX, int mouseY) {
        Rect button = selectButton(index);
        boolean enabled = !selectionPending;
        boolean hovered = enabled && button.contains(mouseX, mouseY);
        boolean selected = index == selectedIndex;
        int fill = !enabled ? 0xFF111719
                : hovered ? 0xFF26383A : selected ? 0xFF17282A : 0xFF12191B;
        graphics.fill(button.x(), button.y(), button.right(), button.bottom(), fill);
        graphics.outline(button.x(), button.y(), button.width(), button.height(),
                enabled ? choice.accent() : TEXT_DARK);
        String label = selectionPending && selected
                ? text("screen.cyberdeck.lifepath.pending")
                : text("screen.cyberdeck.lifepath.select");
        graphics.centeredText(font, fit(label, button.width() - 8),
                button.x() + button.width() / 2, button.y() + 8,
                enabled ? choice.accent() : TEXT_DARK);
        if (hovered) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private void renderFooter(GuiGraphicsExtractor graphics) {
        int y = height - 18;
        String footer = failureTicks > 0
                ? text("screen.cyberdeck.lifepath.failed")
                : text("screen.cyberdeck.lifepath.controls");
        graphics.centeredText(font, fit(footer, Math.max(40, width - MARGIN * 2)),
                width / 2, y, failureTicks > 0 ? RED : TEXT_DARK);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0 || selectionPending) {
            return super.mouseClicked(event, doubleClick);
        }
        for (int index = 0; index < CHOICES.length; index++) {
            if (selectButton(index).contains(event.x(), event.y())) {
                selectedIndex = index;
                submit();
                return true;
            }
            if (card(index).contains(event.x(), event.y())) {
                selectedIndex = index;
                if (doubleClick) {
                    submit();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!selectionPending && event.isUp()) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            return true;
        }
        if (!selectionPending && event.isDown()) {
            selectedIndex = Math.min(CHOICES.length - 1, selectedIndex + 1);
            return true;
        }
        if (!selectionPending && event.isConfirmation()) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    private void submit() {
        if (selectionPending || selectedIndex < 0 || selectedIndex >= CHOICES.length) {
            return;
        }
        selectionPending = true;
        failureTicks = 0;
        ClientPacketDistributor.sendToServer(
                new SelectLifepathPacket(CHOICES[selectedIndex].lifepath().id()));
    }

    private Rect card(int index) {
        int top = HEADER_HEIGHT;
        int bottom = Math.max(top + 3, height - FOOTER_HEIGHT);
        if (compact()) {
            int available = Math.max(3, width - MARGIN * 2 - CARD_GAP * 2);
            int left = MARGIN + index * (available + CARD_GAP * 2) / CHOICES.length;
            int right = MARGIN + (index + 1) * (available + CARD_GAP * 2) / CHOICES.length
                    - CARD_GAP;
            return new Rect(left, top, Math.max(1, right - left), 29);
        }
        int available = Math.max(3, width - MARGIN * 2 - CARD_GAP * 2);
        int left = MARGIN + index * (available + CARD_GAP * 2) / CHOICES.length;
        int right = MARGIN + (index + 1) * (available + CARD_GAP * 2) / CHOICES.length
                - CARD_GAP;
        return new Rect(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
    }

    private Rect selectButton(int index) {
        Rect card = card(index);
        if (compact()) {
            if (index != selectedIndex) {
                return new Rect(0, 0, 0, 0);
            }
            Rect detail = compactDetail();
            int buttonHeight = Math.min(20, Math.max(17, detail.height() / 5));
            int buttonWidth = Math.min(112, Math.max(82, detail.width() / 3));
            return new Rect(detail.right() - buttonWidth - 9,
                    detail.bottom() - buttonHeight - 7, buttonWidth, buttonHeight);
        }
        return new Rect(card.x() + 12, card.bottom() - BUTTON_HEIGHT - 10,
                card.width() - 24, BUTTON_HEIGHT);
    }

    private boolean compact() {
        return width < 620 || height < 330;
    }

    private Rect compactDetail() {
        int top = HEADER_HEIGHT + 35;
        int bottom = Math.max(top + 1, height - FOOTER_HEIGHT);
        return new Rect(MARGIN, top, Math.max(1, width - MARGIN * 2),
                Math.max(1, bottom - top));
    }

    private int renderWrapped(
            GuiGraphicsExtractor graphics, String value, int x, int y,
            int maxWidth, int color, int maxLines) {
        List<FormattedCharSequence> lines = font.split(Component.literal(value), maxWidth);
        int drawn = Math.min(maxLines, lines.size());
        for (int index = 0; index < drawn; index++) {
            graphics.text(font, lines.get(index), x, y, color, false);
            y += 11;
        }
        if (lines.size() > maxLines && drawn > 0) {
            graphics.text(font, "...", x, y - 11, color, false);
        }
        return y;
    }

    private String roleName(Choice choice) {
        return text(choice.lifepath().translationKey()).toUpperCase(java.util.Locale.ROOT);
    }

    private String fit(String value, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        if (maxWidth <= font.width(suffix)) {
            return "";
        }
        return font.plainSubstrByWidth(value, maxWidth - font.width(suffix)) + suffix;
    }

    private static String text(String translationKey) {
        return Component.translatable(translationKey).getString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Choice(
            Lifepath lifepath,
            int accent,
            String tagKey,
            String descriptionKey,
            List<String> loadoutKeys) {
    }

    private record Rect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }
}
