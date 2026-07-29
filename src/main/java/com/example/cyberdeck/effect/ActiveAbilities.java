package com.example.cyberdeck.effect;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the remaining duration (in server ticks) of timed cyberware abilities per player, plus
 * simple cooldowns. All timers are decremented once per server tick by {@link CyberwareTickHandler}.
 * State is transient (not persisted) - abilities simply expire on relog, which is the desired UX.
 */
public final class ActiveAbilities {
    /** Optical Camo remaining ticks (invisibility + aggro immunity). */
    public static final ConcurrentHashMap<UUID, Integer> opticalCamo = new ConcurrentHashMap<>();
    /** Sandevistan remaining ticks (world slow for others). */
    public static final ConcurrentHashMap<UUID, Integer> sandevistan = new ConcurrentHashMap<>();

    /** Generic cooldowns keyed by "<uuid>:<ability>". */
    private static final ConcurrentHashMap<String, Integer> cooldowns = new ConcurrentHashMap<>();

    private ActiveAbilities() {
    }

    public static boolean isOpticalCamoActive(ServerPlayer player) {
        return opticalCamo.getOrDefault(player.getUUID(), 0) > 0;
    }

    public static boolean isSandevistanActive(ServerPlayer player) {
        return sandevistan.getOrDefault(player.getUUID(), 0) > 0;
    }

    public static boolean onCooldown(ServerPlayer player, String ability) {
        return cooldowns.getOrDefault(key(player, ability), 0) > 0;
    }

    public static void setCooldown(ServerPlayer player, String ability, int ticks) {
        cooldowns.put(key(player, ability), ticks);
    }

    public static void forget(UUID id) {
        opticalCamo.remove(id);
        sandevistan.remove(id);
        cooldowns.keySet().removeIf(k -> k.startsWith(id.toString() + ":"));
    }

    static void tickCooldowns() {
        cooldowns.replaceAll((k, v) -> v - 1);
        cooldowns.values().removeIf(v -> v <= 0);
    }

    private static String key(ServerPlayer player, String ability) {
        return player.getUUID() + ":" + ability;
    }
}
