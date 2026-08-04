package com.example.cyberdeck.faction;

import net.minecraft.util.RandomSource;

/** Hostile-only quickhacks. This stays separate from the player's eight-slot skill protocol. */
public enum EnemyQuickhack {
    NONE("none"),
    CRIPPLE_MOVEMENT("cripple_movement"),
    WEAPON_GLITCH("weapon_glitch"),
    BLIND("blind");

    public static final int EFFECT_TICKS = 5 * 20;

    private final String id;

    EnemyQuickhack(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static EnemyQuickhack randomHostile(RandomSource random) {
        return switch (random.nextInt(3)) {
            case 0 -> CRIPPLE_MOVEMENT;
            case 1 -> WEAPON_GLITCH;
            default -> BLIND;
        };
    }

    public static EnemyQuickhack byId(String id) {
        for (EnemyQuickhack quickhack : values()) {
            if (quickhack.id.equals(id)) {
                return quickhack;
            }
        }
        return NONE;
    }

    public static EnemyQuickhack byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : NONE;
    }
}
