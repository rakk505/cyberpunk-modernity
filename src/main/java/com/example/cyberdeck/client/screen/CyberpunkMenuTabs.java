package com.example.cyberdeck.client.screen;

import com.example.cyberdeck.client.CyberdeckClient;
import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.economy.Emmies;
import com.example.cyberdeck.player.StreetCredState;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.entity.player.Player;

/** Shared top-level navigation for the Journal, Cyberware, and city map screens. */
public final class CyberpunkMenuTabs {
    public static final int HEIGHT = 38;

    private static final int BACKGROUND = 0xF208080C;
    private static final int RED = 0xFFFF454D;
    private static final int RED_DIM = 0xFF96333A;
    private static final int CYAN = 0xFF38E8EE;
    private static final int GREEN = 0xFF35D978;
    private static final int GOLD = 0xFFFFC64B;

    private CyberpunkMenuTabs() {
    }

    public enum Tab {
        JOURNAL,
        CYBERWARE,
        MAP
    }

    public static void render(
            GuiGraphicsExtractor graphics,
            Font font,
            int width,
            Tab selected,
            double mouseX,
            double mouseY) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        int level = player == null ? 0 : player.experienceLevel;
        int streetCred = player == null ? 0 : StreetCredState.getStreetCred(player);
        int emmies = player == null ? 0 : Emmies.count(player);

        graphics.fill(0, 0, width, HEIGHT, BACKGROUND);
        graphics.horizontalLine(0, width - 1, HEIGHT - 1, RED);
        graphics.fill(0, HEIGHT - 3, Math.max(1, width / 14), HEIGHT, RED);

        int cellWidth = Math.max(1, width / 6);
        drawStatus(graphics, font, cell(0, width), level + responsiveLabel(width, " LEVEL", " LVL"),
                CYAN);
        drawStatus(graphics, font, cell(1, width),
                streetCred + responsiveLabel(width, " STREET CRED", " SC"),
                GREEN);
        drawTab(graphics, font, cell(2, width), "JOURNAL", selected == Tab.JOURNAL,
                mouseX, mouseY);
        drawTab(graphics, font, cell(3, width), "CYBERWARE", selected == Tab.CYBERWARE,
                mouseX, mouseY);
        drawTab(graphics, font, cell(4, width), "MAP", selected == Tab.MAP,
                mouseX, mouseY);
        drawStatus(graphics, font, cell(5, width),
                responsiveLabel(width, "E$ ", "E$ ") + String.format(Locale.ROOT, "%d", emmies),
                GOLD);

        // Keep rounding in the six equal tracks from leaving an unpainted strip at the right edge.
        if (cellWidth * 6 < width) {
            graphics.fill(cellWidth * 6, 0, width, HEIGHT - 1, BACKGROUND);
        }
    }

    public static boolean handleClick(Tab current, double mouseX, double mouseY, int width) {
        Tab target = tabAt(mouseX, mouseY, width);
        if (target == null) return false;
        if (target != current) open(target);
        return true;
    }

    public static boolean handleKey(Tab current, KeyEvent event) {
        Tab target = null;
        if (CyberdeckClient.OPEN_JOURNAL_KEY.matches(event)) target = Tab.JOURNAL;
        else if (CyberdeckClient.OPEN_CYBERWARE_KEY.matches(event)) target = Tab.CYBERWARE;
        else if (CyberdeckClient.OPEN_CITY_MAP_KEY.matches(event)) target = Tab.MAP;
        if (target == null) return false;
        if (target == current) {
            if (Minecraft.getInstance().gui.screen() != null) {
                Minecraft.getInstance().gui.screen().onClose();
            }
        } else {
            open(target);
        }
        return true;
    }

    private static void open(Tab tab) {
        switch (tab) {
            case JOURNAL -> JournalScreen.open();
            case CYBERWARE -> Minecraft.getInstance().setScreenAndShow(new CyberwareScreen());
            case MAP -> CityMapNavigationClient.requestOpen();
        }
    }

    private static void drawStatus(
            GuiGraphicsExtractor graphics,
            Font font,
            Rect rect,
            String label,
            int color) {
        String fitted = fit(font, label, rect.width() - 8);
        int x = rect.x() + (rect.width() - font.width(fitted)) / 2;
        graphics.text(font, fitted, x, 13, color, false);
    }

    private static void drawTab(
            GuiGraphicsExtractor graphics,
            Font font,
            Rect rect,
            String label,
            boolean selected,
            double mouseX,
            double mouseY) {
        boolean hovered = rect.contains(mouseX, mouseY);
        int color = selected ? CYAN : hovered ? RED : RED_DIM;
        if (hovered || selected) {
            graphics.fill(rect.x() + 2, 2, rect.right() - 2, HEIGHT - 4,
                    selected ? 0x59112D31 : 0x45271218);
        }
        String fitted = fit(font, label, rect.width() - 8);
        graphics.centeredText(font, fitted, rect.x() + rect.width() / 2, 13, color);
        if (selected) {
            graphics.fill(rect.x() + 8, HEIGHT - 3, rect.right() - 8, HEIGHT, CYAN);
        }
        if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
    }

    private static Tab tabAt(double mouseX, double mouseY, int width) {
        if (mouseY < 0 || mouseY >= HEIGHT || mouseX < 0 || mouseX >= width) return null;
        int track = Math.min(5, (int) (mouseX * 6.0 / width));
        return switch (track) {
            case 2 -> Tab.JOURNAL;
            case 3 -> Tab.CYBERWARE;
            case 4 -> Tab.MAP;
            default -> null;
        };
    }

    private static Rect cell(int index, int width) {
        int left = index * width / 6;
        int right = (index + 1) * width / 6;
        return new Rect(left, right - left);
    }

    private static String responsiveLabel(int width, String normal, String compact) {
        return width >= 600 ? normal : compact;
    }

    private static String fit(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) return value;
        if (maxWidth <= font.width("..")) return "";
        return font.plainSubstrByWidth(value, maxWidth - font.width("..")) + "..";
    }

    private record Rect(int x, int width) {
        int right() {
            return x + width;
        }

        boolean contains(double px, double py) {
            return px >= x && px < right() && py >= 0 && py < HEIGHT;
        }
    }
}
