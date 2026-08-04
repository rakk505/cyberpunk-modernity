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
    /** Generic cooldowns keyed by "<uuid>:<ability>". */
    private static final ConcurrentHashMap<String, Integer> cooldowns = new ConcurrentHashMap<>();
    /** Generic active-effect durations keyed by "<uuid>:<ability>". */
    private static final ConcurrentHashMap<String, Integer> active = new ConcurrentHashMap<>();

    private ActiveAbilities() {
    }

    public static boolean isOpticalCamoActive(ServerPlayer player) {
        return opticalCamo.getOrDefault(player.getUUID(), 0) > 0;
    }

    public static boolean onCooldown(ServerPlayer player, String ability) {
        return cooldowns.getOrDefault(key(player, ability), 0) > 0;
    }

    public static void setCooldown(ServerPlayer player, String ability, int ticks) {
        int remaining = Math.max(0, ticks);
        com.example.cyberdeck.cyberware.Cyberware tuner =
                CyberwareEffects.findFlag(player, "quantum_tuner");
        if (tuner != null
                && !ability.equals(tuner.id())
                && !onCooldown(player, tuner.id())) {
            int restored = Math.max(0,
                    (int) Math.round(tuner.value("cooldown_restore_max_seconds") * 20.0));
            if (restored > 0) {
                remaining = Math.max(0, remaining - restored);
                cooldowns.put(key(player, tuner.id()), Math.max(1,
                        (int) Math.round(tuner.value("trigger_cooldown_seconds") * 20.0)));
            }
        }
        if (remaining > 0) {
            cooldowns.put(key(player, ability), remaining);
        } else {
            cooldowns.remove(key(player, ability));
        }
    }

    public static int cooldownRemaining(ServerPlayer player, String ability) {
        return cooldowns.getOrDefault(key(player, ability), 0);
    }

    public static void reduceCooldowns(ServerPlayer player, double fraction) {
        if (fraction <= 0.0) {
            return;
        }
        String prefix = player.getUUID() + ":";
        cooldowns.replaceAll((key, value) -> key.startsWith(prefix)
                ? Math.max(0, (int) Math.floor(value * (1.0 - Math.min(1.0, fraction))))
                : value);
        cooldowns.values().removeIf(value -> value <= 0);
    }

    public static void activate(ServerPlayer player, String ability, int ticks) {
        active.put(key(player, ability), Math.max(1, ticks));
    }

    public static void deactivate(ServerPlayer player, String ability) {
        active.remove(key(player, ability));
    }

    public static boolean isActive(ServerPlayer player, String ability) {
        return active.getOrDefault(key(player, ability), 0) > 0;
    }

    public static int activeRemaining(ServerPlayer player, String ability) {
        return active.getOrDefault(key(player, ability), 0);
    }

    public static void forget(UUID id) {
        opticalCamo.remove(id);
        cooldowns.keySet().removeIf(k -> k.startsWith(id.toString() + ":"));
        active.keySet().removeIf(k -> k.startsWith(id.toString() + ":"));
    }

    static void tickCooldowns() {
        cooldowns.replaceAll((k, v) -> v - 1);
        cooldowns.values().removeIf(v -> v <= 0);
        active.replaceAll((k, v) -> v - 1);
        active.values().removeIf(v -> v <= 0);
    }

    private static String key(ServerPlayer player, String ability) {
        return player.getUUID() + ":" + ability;
    }
}
