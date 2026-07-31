package com.example.cyberdeck.cyberware;

import net.minecraft.world.entity.player.Player;

/** Cyberpunk 2077 capacity curve and prospective-loadout validation. */
public final class CyberwareCapacity {
    private static final int BASE_AT_LEVEL_ONE = 24;
    private static final int PER_LEVEL = 3;
    private static final int BASE_CAP = 201;
    private static final int ABSOLUTE_CAP = 450;

    private CyberwareCapacity() {
    }

    public static int baseMaximum(Player player) {
        int level = Math.max(1, player.experienceLevel);
        return Math.min(BASE_CAP, BASE_AT_LEVEL_ONE + (level - 1) * PER_LEVEL);
    }

    public static int maximum(Player player, CyberwareData data) {
        int bonus = 0;
        Cyberware compressor = data.findFamily("chrome_compressor");
        if (compressor != null) {
            bonus = (int) Math.round(compressor.value("capacity_bonus"));
        }
        bonus += CyberwareAttachments.getBonusCapacity(player);
        return Math.min(ABSOLUTE_CAP, baseMaximum(player) + bonus);
    }

    public static int absoluteCap() {
        return ABSOLUTE_CAP;
    }

    public static boolean canInstall(Player player, CyberwareData data,
                                     Cyberware cyberware, int socket) {
        CyberwareData prospective = data.copy();
        prospective.install(cyberware, socket);
        return prospective.capacityUsed() <= maximum(player, prospective);
    }

    public static boolean isValid(Player player, CyberwareData data) {
        return data.capacityUsed() <= maximum(player, data);
    }
}
