package com.example.cyberdeck;

import net.minecraft.world.entity.LivingEntity;

/**
 * Timed Weapon Glitch fallback for ranged mobs that do not have the faction soldier's dedicated
 * gun state machine. The deadline is stored in persistent data so unloading an entity cannot clear
 * the interruption early.
 */
public final class WeaponGlitchData {
    public static final int RECOVERY_TICKS = 50;

    private static final String GLITCH_END_TICK_KEY = "cyberdeck_weapon_glitch_end_tick";

    private WeaponGlitchData() {
    }

    public static void glitch(LivingEntity entity) {
        glitchFor(entity, RECOVERY_TICKS);
    }

    public static void glitchFor(LivingEntity entity, int durationTicks) {
        entity.getPersistentData().putLong(
                GLITCH_END_TICK_KEY,
                entity.level().getGameTime() + Math.max(0, durationTicks));
    }

    public static boolean isGlitched(LivingEntity entity) {
        long endTick = entity.getPersistentData().getLong(GLITCH_END_TICK_KEY).orElse(0L);
        return entity.level().getGameTime() < endTick;
    }

    public static void clear(LivingEntity entity) {
        entity.getPersistentData().remove(GLITCH_END_TICK_KEY);
    }
}
