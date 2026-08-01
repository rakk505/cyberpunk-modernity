package com.example.cyberdeck.client.hud;

import com.example.cyberdeck.client.map.CityMapNavigationClient;
import com.example.cyberdeck.client.mission.MissionTrackerClient;
import dev.modernity.neoncity.District;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.neoforge.client.gui.GuiLayer;

/** Compact normal-gameplay objective tracker for the server-owned active mission. */
public final class MissionTrackerOverlay implements GuiLayer {
    private static final int WIDTH = 224;
    private static final int HEIGHT = 61;
    private static final int BACKGROUND = 0xE8070B0E;
    private static final int BORDER = 0xFF8A3040;
    private static final int RED = 0xFFFF435D;
    private static final int CYAN = 0xFF45E8E0;
    private static final int GOLD = 0xFFFFC94A;
    private static final int TEXT = 0xFFF0F4F2;
    private static final int DIM = 0xFF82928F;

    @Override
    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        MissionTrackerClient.Snapshot mission = MissionTrackerClient.active();
        if (mission == null || minecraft.player == null || minecraft.gui.hud.isHidden()
                || minecraft.gui.screen() != null || minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        int width = Math.min(WIDTH, graphics.guiWidth() - 16);
        int left = graphics.guiWidth() - width - 8;
        int top = 8;
        graphics.fill(left, top, left + width, top + HEIGHT, BACKGROUND);
        graphics.outline(left, top, width, HEIGHT, BORDER);
        graphics.horizontalLine(left + 1, left + 74, top + 1, RED);
        graphics.text(minecraft.font,
                mission.kind().displayName() + " // " + mission.type().displayName(),
                left + 9, top + 8, RED, false);
        graphics.text(minecraft.font, elide(minecraft, mission.title(), width - 18),
                left + 9, top + 22, TEXT, false);
        graphics.text(minecraft.font, elide(minecraft, mission.objective(), width - 18),
                left + 9, top + 35, CYAN, false);

        District district = mission.districtOrdinal() >= 0
                && mission.districtOrdinal() < District.values().length
                ? District.values()[mission.districtOrdinal()] : null;
        String destination = district == null ? "OUT"
                : "DISTRICT " + district.code();
        double distance = CityMapNavigationClient.distanceToWaypoint(
                minecraft.player.getX(), minecraft.player.getZ());
        String pay = mission.reward() + " EM  " + mission.streetCred() + " SC";
        int footerWidth = Math.max(0, width - 26 - minecraft.font.width(pay));
        String footer = elide(minecraft,
                String.format(Locale.ROOT, "%s  //  %.0fm", destination, distance), footerWidth);
        graphics.text(minecraft.font, footer, left + 9, top + 49, DIM, false);
        graphics.text(minecraft.font, pay, left + width - 9 - minecraft.font.width(pay),
                top + 49, GOLD, false);
    }

    private static String elide(Minecraft minecraft, String value, int maxWidth) {
        if (minecraft.font.width(value) <= maxWidth) return value;
        String suffix = "...";
        int end = value.length();
        while (end > 0
                && minecraft.font.width(value.substring(0, end) + suffix) > maxWidth) end--;
        return value.substring(0, end) + suffix;
    }
}
