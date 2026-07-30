package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.network.AcceptMerchantQuestPacket;
import com.example.cyberdeck.network.OpenMerchantQuestPacket;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.modernity.neoncity.District;
import dev.modernity.neoncity.MerchantQuestService;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Fixer terminal for reading and accepting one of five delivery contracts. */
public final class MerchantQuestScreen extends Screen {
    private static final int PANEL_WIDTH = 500;
    private static final int PANEL_HEIGHT = 364;
    private static final int MARGIN = 14;
    private static final int ROW_HEIGHT = 48;
    private static final int BUTTON_WIDTH = 124;
    private static final int BUTTON_HEIGHT = 23;
    private static final int SCRIM = 0xD8040708;
    private static final int PANEL = 0xF00B0E10;
    private static final int INSET = 0xE50F1416;
    private static final int ROW = 0xE014191C;
    private static final int ROW_HOVER = 0xEF1B2529;
    private static final int ROW_SELECTED = 0xF3292025;
    private static final int RED = 0xFFFF435D;
    private static final int CYAN = 0xFF45E8E0;
    private static final int GOLD = 0xFFFFC94A;
    private static final int TEXT = 0xFFF0F4F2;
    private static final int TEXT_DIM = 0xFF82928F;
    private static final int BORDER = 0xFF653541;

    private final int merchantEntityId;
    private final int sourceDistrictOrdinal;
    private final List<MerchantQuestService.QuestOffer> offers;
    private int selectedIndex;
    private boolean accepted;

    private MerchantQuestScreen(OpenMerchantQuestPacket packet) {
        super(Component.translatable("screen.cyberdeck.merchant_quest.title"));
        merchantEntityId = packet.merchantEntityId();
        sourceDistrictOrdinal = packet.sourceDistrictOrdinal();
        offers = packet.offers();
        selectedIndex = offers.isEmpty() ? -1 : 0;
    }

    public static void open(OpenMerchantQuestPacket packet) {
        Minecraft.getInstance().setScreenAndShow(new MerchantQuestScreen(packet));
    }

    @Override
    public void extractBackground(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, SCRIM);
        for (int y = 1; y < height; y += 4) {
            graphics.fill(0, y, width, y + 1, 0x12000000);
        }
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Layout layout = layout();
        graphics.fill(layout.x(), layout.y(), layout.right(), layout.bottom(), PANEL);
        graphics.outline(layout.x(), layout.y(), layout.width(), layout.height(), BORDER);
        graphics.horizontalLine(layout.x() + 1, layout.x() + 102, layout.y() + 2, RED);
        graphics.horizontalLine(layout.right() - 74, layout.right() - 1,
                layout.bottom() - 3, CYAN);

        graphics.text(font, text("screen.cyberdeck.merchant_quest.header"),
                layout.x() + 18, layout.y() + 15, RED, false);
        graphics.text(font, text("screen.cyberdeck.merchant_quest.subtitle"),
                layout.x() + 18, layout.y() + 31, TEXT, false);
        District source = district(sourceDistrictOrdinal);
        String sourceText = source == null ? "UNKNOWN"
                : "DISTRICT " + source.code() + " // " + source.label().toUpperCase(Locale.ROOT);
        graphics.text(font, sourceText,
                layout.right() - 18 - font.width(sourceText), layout.y() + 20, TEXT_DIM, false);

        renderOffers(graphics, layout, mouseX, mouseY);
        renderFooter(graphics, layout, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderOffers(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        int left = layout.x() + 18;
        int right = layout.right() - 18;
        int top = layout.y() + 62;
        int bottom = top + Math.max(1, offers.size()) * ROW_HEIGHT;
        graphics.fill(left, top, right, bottom, INSET);
        if (offers.isEmpty()) {
            graphics.centeredText(font, text("screen.cyberdeck.merchant_quest.empty"),
                    (left + right) / 2, top + 18, RED);
            return;
        }

        for (int index = 0; index < offers.size(); index++) {
            int rowTop = top + index * ROW_HEIGHT;
            Rect row = new Rect(left + 2, rowTop + 2, right - left - 4, ROW_HEIGHT - 4);
            boolean hovered = row.contains(mouseX, mouseY);
            boolean selected = selectedIndex == index;
            graphics.fill(row.x(), row.y(), row.right(), row.bottom(),
                    selected ? ROW_SELECTED : hovered ? ROW_HOVER : ROW);
            graphics.verticalLine(row.x(), row.y(), row.bottom() - 1,
                    selected ? RED : BORDER);

            MerchantQuestService.QuestOffer offer = offers.get(index);
            District target = district(offer.targetDistrictOrdinal());
            String destination = target == null ? "UNKNOWN"
                    : (offer.local() ? "LOCAL // " : "DISTRICT ") + target.code();
            graphics.text(font, destination, row.x() + 11, row.y() + 7,
                    selected ? RED : CYAN, false);
            graphics.text(font, offer.cargo(), row.x() + 11, row.y() + 23, TEXT, false);
            String coordinates = String.format(Locale.ROOT, "%+d, %+d", offer.targetX(), offer.targetZ());
            graphics.text(font, coordinates,
                    row.right() - 104 - font.width(coordinates), row.y() + 23, TEXT_DIM, false);
            String payment = offer.reward() + " EM";
            graphics.text(font, payment, row.right() - 12 - font.width(payment),
                    row.y() + 14, GOLD, false);
            if (hovered) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
    }

    private void renderFooter(
            GuiGraphicsExtractor graphics, Layout layout, int mouseX, int mouseY) {
        Rect button = acceptButton(layout);
        boolean enabled = selectedIndex >= 0 && !accepted;
        boolean hovered = enabled && button.contains(mouseX, mouseY);
        graphics.fill(button.x(), button.y(), button.right(), button.bottom(),
                hovered ? 0xFF50303A : enabled ? 0xFF291A20 : 0xFF151719);
        graphics.outline(button.x(), button.y(), button.width(), button.height(),
                enabled ? RED : TEXT_DIM);
        graphics.centeredText(font, text(accepted
                        ? "screen.cyberdeck.merchant_quest.accepted"
                        : "screen.cyberdeck.merchant_quest.accept"),
                button.x() + button.width() / 2, button.y() + 7,
                enabled ? TEXT : TEXT_DIM);
        if (hovered) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        Layout layout = layout();
        if (acceptButton(layout).contains(event.x(), event.y())) {
            accept();
            return true;
        }
        int index = offerAt(layout, event.x(), event.y());
        if (index >= 0) {
            selectedIndex = index;
            if (doubleClick) {
                accept();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!offers.isEmpty() && event.isUp()) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            return true;
        }
        if (!offers.isEmpty() && event.isDown()) {
            selectedIndex = Math.min(offers.size() - 1, selectedIndex + 1);
            return true;
        }
        if (event.isConfirmation()) {
            accept();
            return true;
        }
        return super.keyPressed(event);
    }

    private void accept() {
        if (accepted || selectedIndex < 0 || selectedIndex >= offers.size()) {
            return;
        }
        accepted = true;
        ClientPacketDistributor.sendToServer(
                new AcceptMerchantQuestPacket(merchantEntityId, selectedIndex));
        onClose();
    }

    private int offerAt(Layout layout, double mouseX, double mouseY) {
        int left = layout.x() + 20;
        int right = layout.right() - 20;
        int top = layout.y() + 64;
        if (mouseX < left || mouseX >= right || mouseY < top) {
            return -1;
        }
        int index = (int) (mouseY - top) / ROW_HEIGHT;
        return index >= 0 && index < offers.size() ? index : -1;
    }

    private Layout layout() {
        int panelWidth = Math.min(PANEL_WIDTH, Math.max(300, width - MARGIN * 2));
        int panelHeight = Math.min(PANEL_HEIGHT, Math.max(330, height - MARGIN * 2));
        return new Layout((width - panelWidth) / 2, (height - panelHeight) / 2,
                panelWidth, panelHeight);
    }

    private Rect acceptButton(Layout layout) {
        return new Rect(layout.right() - 18 - BUTTON_WIDTH, layout.bottom() - 36,
                BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    private static District district(int ordinal) {
        District[] districts = District.values();
        return ordinal >= 0 && ordinal < districts.length ? districts[ordinal] : null;
    }

    private static String text(String key) {
        return Component.translatable(key).getString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Layout(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }
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
