package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.Cyberware;
import com.example.cyberdeck.cyberware.CyberwareAttachments;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Jenkins' Tendons sprint boost: +30% tapering to +10% over five seconds. */
public final class LegSpeed {
    private static final int TAPER_TICKS = 5 * 20;
    private static final ConcurrentHashMap<UUID, Integer> SPRINT_TICKS = new ConcurrentHashMap<>();

    private LegSpeed() {
    }

    public static double tick(ServerPlayer player) {
        Cyberware tendons = CyberwareAttachments.get(player).findFlag("sprint_ramp");
        if (tendons == null || !player.isSprinting()) {
            SPRINT_TICKS.computeIfPresent(player.getUUID(), (ignored, ticks) -> {
                int recovered = Math.max(0, ticks - 1);
                return recovered == 0 ? null : recovered;
            });
            return 0.0;
        }
        int ticks = SPRINT_TICKS.merge(player.getUUID(), 1,
                (oldValue, increment) -> Math.min(TAPER_TICKS, oldValue + increment));
        double progress = ticks / (double) TAPER_TICKS;
        double start = tendons.value("sprint_speed_start_percent") / 100.0;
        double end = tendons.value("sprint_speed_end_percent") / 100.0;
        return start + (end - start) * progress;
    }

    public static void forget(UUID id) {
        SPRINT_TICKS.remove(id);
    }
}
